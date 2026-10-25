package com.gathin.tvhome;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;

/**
 * The photo list the NAS writes (tools/nas-photo-index.sh): one share-relative path per line, gzipped, RAW,
 * video and QNAP's thumbnail folders already left out. It is kept unpacked in the cache, and only the byte
 * offsets of the lines the current settings let through stay in memory, so a list of 100,000 photos costs
 * 400 KB, not the strings.
 */
final class NasPhotoIndex {
    private static final String ETAG_KEY = "index_etag";
    private static final long MAX_INDEX_BYTES = 64L * 1024 * 1024;

    private final File file;
    private int[] offsets = new int[0];
    private int count;

    private NasPhotoIndex(File file) {
        this.file = file;
    }

    static File cacheFile(Context context) {
        return new File(context.getCacheDir(), "nas_index.txt");
    }

    /**
     * Fetches the list when it changed on the NAS (an ETag check otherwise) and unpacks it into the cache.
     * A failed fetch keeps the copy from last time, so a NAS that is asleep still leaves photos to show.
     */
    static void refresh(Context context, NasClient client, NasPhotos settings) throws IOException {
        File txt = cacheFile(context);
        File gz = new File(context.getCacheDir(), "nas_index.gz");
        String etag = txt.isFile() ? NasPhotos.prefs(context).getString(ETAG_KEY, "") : "";
        String newTag;
        try {
            newTag = client.download(settings.indexPath, gz, MAX_INDEX_BYTES, etag);
        } catch (NasClient.NotModified e) {
            return;
        }
        File tmp = new File(txt.getPath() + ".part");
        try (InputStream in = new GZIPInputStream(new FileInputStream(gz), 64 * 1024);
             OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[64 * 1024];
            long total = 0;
            for (int n; (n = in.read(buf)) > 0; ) {
                total += n;
                if (total > Integer.MAX_VALUE) throw new IOException("index too large");
                out.write(buf, 0, n);
            }
        } finally {
            //noinspection ResultOfMethodCallIgnored
            gz.delete();
        }
        if (!tmp.renameTo(txt)) throw new IOException("rename failed");
        NasPhotos.prefs(context).edit().putString(ETAG_KEY, newTag).apply();
    }

    /** The cached list, filtered by the folder and format switches. Empty when there is no list yet. */
    static NasPhotoIndex load(Context context, NasPhotos settings) throws IOException {
        NasPhotoIndex index = new NasPhotoIndex(cacheFile(context));
        if (!index.file.isFile()) return index;
        NasHidden hidden = NasHidden.get(context);
        index.scan((offset, path) -> {
            if (settings.wantsType(path) && settings.wantsFolder(path) && !hidden.hides(path)) index.add(offset);
        });
        index.offsets = Arrays.copyOf(index.offsets, index.count);
        return index;
    }

    /** Photo counts per folder, two levels deep ("2023" and "2023/Kyoto"), for the folder picker. */
    static TreeMap<String, Integer> folders(Context context, NasPhotos settings) throws IOException {
        TreeMap<String, Integer> folders = new TreeMap<>();
        NasPhotoIndex index = new NasPhotoIndex(cacheFile(context));
        if (!index.file.isFile()) return folders;
        index.scan((offset, path) -> {
            if (!settings.wantsType(path)) return;
            int first = path.indexOf('/');
            if (first <= 0) return;
            bump(folders, path.substring(0, first));
            int second = path.indexOf('/', first + 1);
            if (second > first + 1) bump(folders, path.substring(0, second));
        });
        return folders;
    }

    private static void bump(Map<String, Integer> map, String key) {
        Integer n = map.get(key);
        map.put(key, n == null ? 1 : n + 1);
    }

    int size() {
        return count;
    }

    int[] offsets() {
        return offsets;
    }

    /** The path on the line that starts at this byte offset. */
    String pathAt(int offset) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.seek(offset);
            byte[] buf = new byte[2048];
            int len = 0;
            for (int b; len < buf.length && (b = raf.read()) >= 0 && b != '\n'; ) buf[len++] = (byte) b;
            if (len > 0 && buf[len - 1] == '\r') len--;
            return new String(buf, 0, len, StandardCharsets.UTF_8);
        }
    }

    private interface LineVisitor {
        void visit(int offset, String path);
    }

    private void add(int offset) {
        if (count == offsets.length) offsets = Arrays.copyOf(offsets, Math.max(1024, count * 2));
        offsets[count++] = offset;
    }

    /** Every path line with the offset it starts at; "#" lines and blank lines are skipped. */
    private void scan(LineVisitor visitor) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            byte[] chunk = new byte[64 * 1024];
            byte[] line = new byte[2048];
            int len = 0;
            int pos = 0;
            int start = 0;
            boolean overflow = false;
            for (int n; (n = in.read(chunk)) > 0; ) {
                for (int i = 0; i < n; i++, pos++) {
                    byte b = chunk[i];
                    if (b == '\n') {
                        if (!overflow) emit(visitor, start, line, len);
                        len = 0;
                        overflow = false;
                        start = pos + 1;
                    } else if (len < line.length) {
                        line[len++] = b;
                    } else {
                        overflow = true;   // a path over 2 KB is not a photo this frame will show
                    }
                }
            }
            if (len > 0 && !overflow) emit(visitor, start, line, len);
        }
    }

    private static void emit(LineVisitor visitor, int start, byte[] line, int len) {
        if (len > 0 && line[len - 1] == '\r') len--;
        if (len == 0 || line[0] == '#') return;
        visitor.visit(start, new String(line, 0, len, StandardCharsets.UTF_8));
    }
}
