package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/** The NAS photo frame's on-TV choices: on or off, how often it changes, which folders and formats. */
public final class NasPhotoSettingsActivity extends Activity {
    private static final int[] INTERVAL_IDS = {R.id.nasInterval0, R.id.nasInterval1, R.id.nasInterval2,
            R.id.nasInterval3, R.id.nasInterval4};

    private SharedPreferences prefs;
    private final TextView[] intervals = new TextView[INTERVAL_IDS.length];
    private TextView status;
    private TextView enabled;
    private TextView folders;
    private TextView shuffle;
    private TextView jpeg;
    private TextView heic;
    private TextView caption;
    private TextView hidden;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_nas_photo_settings);
        prefs = NasPhotos.prefs(this);
        status = findViewById(R.id.nasStatus);
        enabled = findViewById(R.id.nasEnabled);
        enabled.setOnClickListener(v -> toggle(NasPhotos.KEY_ENABLED, false));
        for (int i = 0; i < INTERVAL_IDS.length; i++) {
            final int seconds = NasPhotos.INTERVALS[i];
            intervals[i] = findViewById(INTERVAL_IDS[i]);
            intervals[i].setText(intervalLabel(seconds));
            intervals[i].setOnClickListener(v -> {
                prefs.edit().putInt(NasPhotos.KEY_INTERVAL, seconds).apply();
                changed();
            });
        }
        folders = findViewById(R.id.nasFolders);
        folders.setOnClickListener(v -> showFolderPicker());
        shuffle = findViewById(R.id.nasShuffle);
        shuffle.setOnClickListener(v -> toggle(NasPhotos.KEY_SHUFFLE, true));
        jpeg = findViewById(R.id.nasJpeg);
        jpeg.setOnClickListener(v -> toggleFormat(NasPhotos.KEY_JPEG, NasPhotos.KEY_HEIC));
        heic = findViewById(R.id.nasHeic);
        heic.setOnClickListener(v -> toggleFormat(NasPhotos.KEY_HEIC, NasPhotos.KEY_JPEG));
        caption = findViewById(R.id.nasCaption);
        caption.setOnClickListener(v -> toggle(NasPhotos.KEY_CAPTION, true));
        hidden = findViewById(R.id.nasHidden);
        hidden.setOnClickListener(v -> showHidden());
        findViewById(R.id.nasRefresh).setOnClickListener(v -> {
            // Forget the cached list's ETag, and Home fetches the whole list again when it next resumes.
            prefs.edit().remove("index_etag").apply();
            //noinspection ResultOfMethodCallIgnored
            NasPhotoIndex.cacheFile(this).delete();
            changed();
            Toast.makeText(this, R.string.nas_refresh_done, Toast.LENGTH_SHORT).show();
        });
        refresh();
        enabled.requestFocus();
    }

    private void toggle(String key, boolean byDefault) {
        prefs.edit().putBoolean(key, !prefs.getBoolean(key, byDefault)).apply();
        changed();
    }

    /** JPEG and HEIC can't both be off: the frame would have nothing to show. */
    private void toggleFormat(String key, String other) {
        boolean on = prefs.getBoolean(key, true);
        if (on && !prefs.getBoolean(other, true)) {
            Toast.makeText(this, R.string.nas_formats_one, Toast.LENGTH_SHORT).show();
            return;
        }
        prefs.edit().putBoolean(key, !on).apply();
        changed();
    }

    /** Folders two levels deep from the cached photo list, with their photo counts; several can be ticked. */
    private void showFolderPicker() {
        NasPhotos s = NasPhotos.load(this);
        TreeMap<String, Integer> found;
        try {
            found = NasPhotoIndex.folders(this, s);
        } catch (IOException e) {
            found = new TreeMap<>();
        }
        if (found.isEmpty()) {
            Toast.makeText(this, R.string.nas_folders_none, Toast.LENGTH_LONG).show();
            return;
        }
        List<String> keys = new ArrayList<>(found.keySet());
        String[] labels = new String[keys.size()];
        boolean[] checked = new boolean[keys.size()];
        NumberFormat n = NumberFormat.getIntegerInstance();
        for (int i = 0; i < keys.size(); i++) {
            String k = keys.get(i);
            int slash = k.indexOf('/');
            labels[i] = (slash < 0 ? k : "　　" + k.substring(slash + 1)) + "  (" + n.format(found.get(k)) + ")";
            checked[i] = s.folders.contains(k);
        }
        Set<String> picked = new HashSet<>(s.folders);
        new AlertDialog.Builder(this)
                .setTitle(R.string.nas_folders_label)
                .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> {
                    if (isChecked) picked.add(keys.get(which));
                    else picked.remove(keys.get(which));
                })
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    prefs.edit().putStringSet(NasPhotos.KEY_FOLDERS, new HashSet<>(picked)).apply();
                    changed();
                })
                .setNeutralButton(R.string.nas_folders_all, (d, w) -> {
                    prefs.edit().remove(NasPhotos.KEY_FOLDERS).apply();
                    changed();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** The photos and folders hidden from the frame; ticked ones are shown again. */
    private void showHidden() {
        NasHidden list = NasHidden.get(this);
        List<String> entries = list.list();
        if (entries.isEmpty()) {
            Toast.makeText(this, R.string.nas_hidden_none, Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[entries.size()];
        for (int i = 0; i < labels.length; i++) {
            String e = entries.get(i);
            labels[i] = e.endsWith("/") ? getString(R.string.nas_hidden_folder_item, e.substring(0, e.length() - 1)) : e;
        }
        List<String> picked = new ArrayList<>();
        new AlertDialog.Builder(this)
                .setTitle(R.string.nas_hidden_restore_title)
                .setMultiChoiceItems(labels, new boolean[labels.length], (d, which, isChecked) -> {
                    if (isChecked) picked.add(entries.get(which));
                    else picked.remove(entries.get(which));
                })
                .setPositiveButton(R.string.nas_hidden_restore, (d, w) -> {
                    list.restore(picked);
                    changed();
                })
                .setNeutralButton(R.string.nas_hidden_restore_all, (d, w) -> {
                    list.restoreAll();
                    changed();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Saved; Home rebuilds with it when it next resumes, however these settings are left. */
    private void changed() {
        NasPhotos.bumpRevision(this);
        setResult(RESULT_OK);
        refresh();
    }

    private void refresh() {
        NasPhotos s = NasPhotos.load(this);
        status.setText(statusText(s));
        enabled.setText(getString(R.string.nas_enabled_state, onOff(s.enabled)));
        enabled.setEnabled(s.configured());
        enabled.setAlpha(s.configured() ? 1f : 0.5f);
        for (int i = 0; i < intervals.length; i++) intervals[i].setSelected(NasPhotos.INTERVALS[i] == s.intervalSeconds);
        folders.setText(getString(R.string.nas_folders_state, s.folders.isEmpty() ? getString(R.string.nas_folders_all)
                : s.folders.size() == 1 ? s.folders.iterator().next()
                : getString(R.string.nas_folders_count, s.folders.size())));
        shuffle.setText(getString(R.string.nas_order_state,
                getString(s.shuffle ? R.string.nas_order_random : R.string.nas_order_sequential)));
        jpeg.setText(getString(R.string.nas_jpeg_state, onOff(s.jpeg)));
        heic.setText(getString(R.string.nas_heic_state, onOff(s.heic)));
        caption.setText(getString(R.string.nas_caption_state, onOff(s.caption)));
        hidden.setText(getString(R.string.nas_hidden_state, NasHidden.get(this).size()));
    }

    private String statusText(NasPhotos s) {
        if (!s.configured()) return getString(R.string.nas_status_unconfigured);
        String st = prefs.getString(NasPhotos.KEY_STATUS, "");
        int count = prefs.getInt(NasPhotos.KEY_COUNT, 0);
        String host = s.url.replaceFirst("^https?://", "");
        if ("ok".equals(st)) return getString(R.string.nas_status_ok, host, NumberFormat.getIntegerInstance().format(count));
        if ("empty".equals(st)) return getString(R.string.nas_status_empty, host);
        if (st.isEmpty()) return getString(R.string.nas_status_waiting, host);
        return getString(R.string.nas_status_error, host, st);
    }

    private String onOff(boolean on) {
        return getString(on ? R.string.on : R.string.off);
    }

    private String intervalLabel(int seconds) {
        return seconds < 60 ? getString(R.string.nas_seconds, seconds) : getString(R.string.nas_minutes, seconds / 60);
    }
}
