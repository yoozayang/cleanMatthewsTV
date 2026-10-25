package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * Writes the NAS photo frame's connection over adb, so the password is never typed with the remote and never
 * lands in the repo or a theme. Only the shell can send it (WRITE_SECURE_SETTINGS), as with GuardConfig:
 * {@code adb shell am broadcast -n com.gathin.tvhome/.NasPhotoConfig --es url https://<nas ip>:5001/<share>
 * --es user <name> --es password <password> --es pin <sha-256 of the NAS certificate> --ez enabled true}
 * tools/nas-photos.sh builds that call. An extra left out keeps its value; --ez clear true forgets them all.
 */
public class NasPhotoConfig extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        SharedPreferences.Editor e = NasPhotos.prefs(context).edit();
        if (intent.getBooleanExtra("clear", false)) e.clear();
        copy(intent, e, "url", NasPhotos.KEY_URL);
        copy(intent, e, "user", NasPhotos.KEY_USER);
        copy(intent, e, "password", NasPhotos.KEY_PASSWORD);
        copy(intent, e, "pin", NasPhotos.KEY_PIN);
        copy(intent, e, "index", NasPhotos.KEY_INDEX);
        if (intent.hasExtra("enabled")) e.putBoolean(NasPhotos.KEY_ENABLED, intent.getBooleanExtra("enabled", false));
        // A new NAS or list: fetch it again rather than trust the cached copy's ETag.
        e.remove("index_etag");
        e.commit();
        NasPhotos.bumpRevision(context);
        NasPhotos s = NasPhotos.load(context);
        setResultData("configured=" + s.configured() + " enabled=" + s.enabled + " url=" + s.url
                + " user=" + s.user + " pin=" + (s.pin.isEmpty() ? "none" : "set"));
    }

    private static void copy(Intent intent, SharedPreferences.Editor e, String extra, String key) {
        String v = intent.getStringExtra(extra);
        if (v != null) e.putString(key, v.trim());
    }
}
