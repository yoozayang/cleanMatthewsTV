package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Now's weather for the status line, from Open-Meteo (no account, no key) at the TV's own location: the last
 * position Android's network location knows, rounded to two decimals (about a kilometre) before it leaves the
 * TV. Fetched only while Home is on screen, at most every 30 minutes; the last answer is kept for the next start.
 */
final class Weather {
    interface Listener {
        void onWeather(Weather.Now now);
    }

    static final class Now {
        final int temperature;
        final int code;
        final boolean day;

        Now(int temperature, int code, boolean day) {
            this.temperature = temperature;
            this.code = code;
            this.day = day;
        }

        /** WMO weather code to a short description. */
        int description() {
            if (code == 0) return R.string.wx_clear;
            if (code <= 2) return R.string.wx_partly;
            if (code == 3) return R.string.wx_overcast;
            if (code == 45 || code == 48) return R.string.wx_fog;
            if (code >= 51 && code <= 67 || code >= 80 && code <= 82) return R.string.wx_rain;
            if (code >= 71 && code <= 77 || code == 85 || code == 86) return R.string.wx_snow;
            if (code >= 95) return R.string.wx_storm;
            return R.string.wx_overcast;
        }

        int icon() {
            if (code <= 1) return day ? R.drawable.ic_wx_sun : R.drawable.ic_moon;
            return R.drawable.ic_wx_cloud;
        }
    }

    private static final String PREFS = "weather";
    private static final long MAX_AGE_MS = 30 * 60_000L;
    private static final long RETRY_MS = 5 * 60_000L;

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean fetching;

    Weather(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    /** The saved answer at once, then a fresh one if it is old. */
    void refresh() {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long at = p.getLong("wx_at", 0);
        if (p.contains("wx_temp")) listener.onWeather(new Now(p.getInt("wx_temp", 0), p.getInt("wx_code", 0), p.getBoolean("wx_day", true)));
        long age = System.currentTimeMillis() - at;
        long failedAt = p.getLong("wx_failed", 0);
        if (age < MAX_AGE_MS || fetching || System.currentTimeMillis() - failedAt < RETRY_MS) return;
        Location where = lastLocation();
        if (where == null) return;
        fetching = true;
        double lat = Math.round(where.getLatitude() * 100) / 100.0;
        double lon = Math.round(where.getLongitude() * 100) / 100.0;
        new Thread(() -> {
            Now now = null;
            try {
                now = fetch(lat, lon);
            } catch (IOException | RuntimeException | org.json.JSONException ignored) {
                // Offline, or Open-Meteo is down: keep the last answer and try again in a few minutes.
            }
            final Now done = now;
            main.post(() -> {
                fetching = false;
                SharedPreferences.Editor e = p.edit();
                if (done == null) {
                    e.putLong("wx_failed", System.currentTimeMillis()).apply();
                    return;
                }
                e.putInt("wx_temp", done.temperature).putInt("wx_code", done.code).putBoolean("wx_day", done.day)
                        .putLong("wx_at", System.currentTimeMillis()).remove("wx_failed").apply();
                listener.onWeather(done);
            });
        }, "weather").start();
    }

    private Location lastLocation() {
        if (context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
                && context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) return null;
        LocationManager lm = context.getSystemService(LocationManager.class);
        if (lm == null) return null;
        Location best = null;
        for (String provider : new String[]{"fused", LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER}) {
            try {
                Location l = lm.getLastKnownLocation(provider);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            } catch (IllegalArgumentException | SecurityException ignored) {
                // No such provider on this TV.
            }
        }
        return best;
    }

    private static Now fetch(double lat, double lon) throws IOException, org.json.JSONException {
        URL url = new URL(String.format(Locale.ROOT, "https://api.open-meteo.com/v1/forecast?latitude=%.2f"
                + "&longitude=%.2f&current=temperature_2m,weather_code,is_day&timezone=auto", lat, lon));
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(6_000);
        c.setReadTimeout(10_000);
        try {
            if (c.getResponseCode() != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + c.getResponseCode());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[8192];
                for (int n; (n = in.read(buf)) > 0 && out.size() < 64 * 1024; ) out.write(buf, 0, n);
            }
            JSONObject current = new JSONObject(out.toString(StandardCharsets.UTF_8.name())).getJSONObject("current");
            return new Now((int) Math.round(current.getDouble("temperature_2m")), current.getInt("weather_code"),
                    current.optInt("is_day", 1) == 1);
        } finally {
            c.disconnect();
        }
    }
}
