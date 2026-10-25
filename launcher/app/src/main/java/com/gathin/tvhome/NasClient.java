package com.gathin.tvhome;

import android.util.Base64;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Locale;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Plain GETs from the NAS's WebDAV share, nothing else: no listing, no writes. With a certificate pin
 * (SHA-256 of the server certificate, set by NasPhotoConfig) a self-signed NAS certificate is accepted
 * only when it is exactly that one, and the host name is not checked, since a LAN IP never matches it.
 */
final class NasClient {
    static final class NotModified extends IOException {
        NotModified() {
            super("not modified");
        }
    }

    /**
     * The NAS said no to this account (401, 403). Not retried: a NAS that blocks an address after a few failed
     * logins (QNAP's network access protection) would otherwise lock the TV out for good.
     */
    static final class Refused extends IOException {
        Refused(int code) {
            super("HTTP " + code + ": the NAS refused the account");
        }
    }

    private static final int CONNECT_MS = 6_000;
    private static final int READ_MS = 20_000;

    private final NasPhotos settings;
    private final String auth;
    private final SSLSocketFactory pinnedFactory;

    NasClient(NasPhotos settings) {
        this.settings = settings;
        auth = settings.user.isEmpty() ? null : "Basic " + Base64.encodeToString(
                (settings.user + ":" + settings.password).getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        pinnedFactory = settings.pin.isEmpty() ? null : pinned(settings.pin);
    }

    /** The share-relative path ("2023/Kyoto/IMG_1.HEIC") as a URL, each segment percent-encoded. */
    String urlFor(String path) {
        StringBuilder b = new StringBuilder(settings.url);
        for (String segment : path.split("/")) {
            if (segment.isEmpty()) continue;
            try {
                b.append('/').append(URLEncoder.encode(segment, "UTF-8").replace("+", "%20"));
            } catch (java.io.UnsupportedEncodingException e) {
                throw new IllegalStateException(e);
            }
        }
        return b.toString();
    }

    /**
     * Downloads path into dest (through a temporary file, so a broken transfer never leaves half a file).
     * With an etag, an unchanged file answers NotModified. Returns the new etag, or "" when there is none.
     */
    String download(String path, File dest, long maxBytes, String etag) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(urlFor(path)).openConnection();
        try {
            if (c instanceof HttpsURLConnection && pinnedFactory != null) {
                HttpsURLConnection https = (HttpsURLConnection) c;
                https.setSSLSocketFactory(pinnedFactory);
                https.setHostnameVerifier((host, session) -> true);
            }
            c.setConnectTimeout(CONNECT_MS);
            c.setReadTimeout(READ_MS);
            c.setInstanceFollowRedirects(false);
            c.setUseCaches(false);
            if (auth != null) c.setRequestProperty("Authorization", auth);
            if (etag != null && !etag.isEmpty()) c.setRequestProperty("If-None-Match", etag);
            int code = c.getResponseCode();
            if (code == HttpURLConnection.HTTP_NOT_MODIFIED) throw new NotModified();
            if (code == HttpURLConnection.HTTP_UNAUTHORIZED || code == HttpURLConnection.HTTP_FORBIDDEN) {
                throw new Refused(code);
            }
            if (code != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + code);
            long length = c.getContentLengthLong();
            if (length > maxBytes) throw new IOException("too large: " + length);
            File tmp = new File(dest.getPath() + ".part");
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                long total = 0;
                for (int n; (n = in.read(buf)) > 0; ) {
                    total += n;
                    if (total > maxBytes) throw new IOException("too large");
                    out.write(buf, 0, n);
                }
            } catch (IOException e) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                throw e;
            }
            if (!tmp.renameTo(dest)) throw new IOException("rename failed");
            String tag = c.getHeaderField("ETag");
            return tag == null ? "" : tag;
        } finally {
            c.disconnect();
        }
    }

    private static SSLSocketFactory pinned(String pinHex) {
        TrustManager tm = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                throw new CertificateException("client certificates are not used");
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                if (chain == null || chain.length == 0) throw new CertificateException("no certificate");
                if (!pinHex.equals(sha256(chain[0].getEncoded()))) throw new CertificateException("pin mismatch");
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{tm}, null);
            return ctx.getSocketFactory();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static String sha256(byte[] data) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder b = new StringBuilder(d.length * 2);
            for (byte x : d) b.append(String.format(Locale.ROOT, "%02x", x));
            return b.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
