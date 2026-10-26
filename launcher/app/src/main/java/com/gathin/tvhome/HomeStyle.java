package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * This fork's choice of Home: the Apple TV style (the launcher's own) or the Wii style (WiiHome). config.xml's
 * wii_style is the default; the Appearance page (and the control centre) chooses on the TV, and Home rebuilds
 * with it the next time it resumes, through the same change count as Launcher settings.
 */
final class HomeStyle {
    static final String KEY = "home_style";
    static final String WII_MUSIC_KEY = "wii_music";
    static final String APPLE = "apple", WII = "wii";

    private HomeStyle() {
    }

    static boolean wii(Context context) {
        String chosen = prefs(context).getString(KEY, null);
        return chosen == null ? context.getResources().getBoolean(R.bool.wii_style) : WII.equals(chosen);
    }

    static void set(Context context, boolean wii) {
        SharedPreferences p = prefs(context);
        p.edit().putString(KEY, wii ? WII : APPLE)
                .putLong(Config.SETTINGS_REVISION_KEY, p.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1).apply();
    }

    static boolean wiiMusic(Context context) {
        return prefs(context).getBoolean(WII_MUSIC_KEY, context.getResources().getBoolean(R.bool.wii_music));
    }

    static void setWiiMusic(Context context, boolean on) {
        SharedPreferences p = prefs(context);
        p.edit().putBoolean(WII_MUSIC_KEY, on)
                .putLong(Config.SETTINGS_REVISION_KEY, p.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
    }
}
