package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The NAS photo frame's settings, kept apart from Launcher settings (their own file, so "Follow theme" never
 * clears them). The connection (url, user, password, certificate pin) is written by NasPhotoConfig over adb;
 * everything else is chosen on the TV in NasPhotoSettingsActivity.
 */
final class NasPhotos {
    static final String PREFS = "nas_photos";

    static final String KEY_ENABLED = "enabled";
    static final String KEY_URL = "url";
    static final String KEY_USER = "user";
    static final String KEY_PASSWORD = "password";
    static final String KEY_PIN = "pin";
    static final String KEY_INDEX = "index_path";
    static final String KEY_INTERVAL = "interval_s";
    static final String KEY_SHUFFLE = "shuffle";
    static final String KEY_JPEG = "jpeg";
    static final String KEY_HEIC = "heic";
    static final String KEY_FOLDERS = "folders";
    static final String KEY_CAPTION = "caption";
    static final String KEY_STATUS = "last_result";
    static final String KEY_COUNT = "photo_count";
    /** Share of turns (percent) that go to a favourite photo or folder, each with its own cool-down. */
    static final String KEY_FAV_RATE = "favorite_rate";
    /** "On this day": photos taken on today's date in other years come round more often. */
    static final String KEY_MEMORIES = "memories";
    static final int[] FAV_RATES = {0, 10, 20, 33};
    static final int DEFAULT_FAV_RATE = 20;

    static final String DEFAULT_INDEX = ".tvphotos/index.txt.gz";
    static final int[] INTERVALS = {15, 30, 60, 300, 900};
    static final int DEFAULT_INTERVAL = 30;

    final boolean enabled;
    final String url;
    final String user;
    final String password;
    final String pin;
    final String indexPath;
    final int intervalSeconds;
    final boolean shuffle;
    final boolean jpeg;
    final boolean heic;
    /** Folder prefixes ("2023/Kyoto"); empty means every folder. */
    final Set<String> folders;
    final boolean caption;
    final int favoriteRate;
    final boolean memories;

    private NasPhotos(SharedPreferences p) {
        enabled = p.getBoolean(KEY_ENABLED, false);
        url = trimSlash(p.getString(KEY_URL, ""));
        user = p.getString(KEY_USER, "");
        password = p.getString(KEY_PASSWORD, "");
        pin = p.getString(KEY_PIN, "").replace(":", "").toLowerCase(Locale.ROOT);
        indexPath = p.getString(KEY_INDEX, DEFAULT_INDEX);
        intervalSeconds = Math.max(5, p.getInt(KEY_INTERVAL, DEFAULT_INTERVAL));
        shuffle = p.getBoolean(KEY_SHUFFLE, true);
        jpeg = p.getBoolean(KEY_JPEG, true);
        heic = p.getBoolean(KEY_HEIC, true);
        folders = Collections.unmodifiableSet(new HashSet<>(p.getStringSet(KEY_FOLDERS, Collections.emptySet())));
        caption = p.getBoolean(KEY_CAPTION, true);
        favoriteRate = Math.max(0, Math.min(50, p.getInt(KEY_FAV_RATE, DEFAULT_FAV_RATE)));
        memories = p.getBoolean(KEY_MEMORIES, true);
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static NasPhotos load(Context context) {
        return new NasPhotos(prefs(context));
    }

    /** A NAS is set up (NasPhotoConfig ran); the frame still waits for the switch. */
    boolean configured() {
        return url.startsWith("http://") || url.startsWith("https://");
    }

    boolean active() {
        return enabled && configured() && (jpeg || heic);
    }

    /** True for a path the format switches let through. RAW and video never reach the index. */
    boolean wantsType(String path) {
        return wantsType(path, jpeg, heic);
    }

    static boolean wantsType(String path, boolean jpeg, boolean heic) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return jpeg;
        if (lower.endsWith(".heic") || lower.endsWith(".heif")) return heic;
        return false;
    }

    /** True for a path inside one of the chosen folders, or any path when none is chosen. */
    boolean wantsFolder(String path) {
        return inFolders(path, folders);
    }

    /** "2023" holds "2023/Kyoto/a.jpg" but not "2023-backup/a.jpg". */
    static boolean inFolders(String path, Set<String> folders) {
        if (folders.isEmpty()) return true;
        for (String f : folders) {
            if (path.startsWith(f) && (path.length() == f.length() || path.charAt(f.length()) == '/')) return true;
        }
        return false;
    }

    private static final java.util.regex.Pattern DATE = java.util.regex.Pattern.compile(
            "(?<!\\d)(19[7-9]\\d|20[0-4]\\d)[-_. ]?(0[1-9]|1[0-2])[-_. ]?(0[1-9]|[12]\\d|3[01])(?!\\d)");

    /**
     * The date a photo was taken, from its name or failing that the nearest folder name that holds one
     * ("IMG_20240428_171025.jpg", "2024-03-05 19.53.43.jpg", "20101003 部長請吃飯/DSC03333.JPG"), as
     * {year, month, day}; null when no part of the path names a date.
     */
    static int[] dateOf(String path) {
        String[] parts = path.split("/");
        for (int i = parts.length - 1; i >= 0; i--) {
            java.util.regex.Matcher m = DATE.matcher(parts[i]);
            if (m.find()) return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))};
        }
        return null;
    }

    /** Taken on this month and day in an earlier year: cheap text checks first, the pattern only for a hit. */
    static boolean onThisDay(String path, int year, int month, int day) {
        String mm = month < 10 ? "0" + month : String.valueOf(month);
        String dd = day < 10 ? "0" + day : String.valueOf(day);
        if (!path.contains(dd) || !path.contains(mm)) return false;
        int[] d = dateOf(path);
        return d != null && d[1] == month && d[2] == day && d[0] < year;
    }

    /** Home rebuilds when it next resumes, as it does after a Launcher settings change. */
    static void bumpRevision(Context context) {
        SharedPreferences s = context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        s.edit().putLong(Config.SETTINGS_REVISION_KEY, s.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1).apply();
    }

    static void setStatus(Context context, String status, int count) {
        prefs(context).edit().putString(KEY_STATUS, status).putInt(KEY_COUNT, count).apply();
    }

    private static String trimSlash(String s) {
        s = s == null ? "" : s.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }
}
