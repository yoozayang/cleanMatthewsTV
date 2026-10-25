package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * The control centre, opened from the top bar's control pill: toggles in a glass panel, like a phone's. Sleep timer
 * and the NAS photo frame switch in place (OK); the others open their page. Back, Home or any app closes it.
 */
public final class ControlCenterActivity extends Activity {
    /** The TV's own AirPlay receiver on MediaTek Xiaomi sets; any receiver app works if it has a launcher entry. */
    private static final String[] AIRPLAY_APPS = {"com.mediatek.AirplayAPK", "com.xiaomi.mitv.smartshare"};

    private GridLayout tiles;
    private View sleepTile;
    private View frameTile;
    private View airplayTile;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_control_center);
        findViewById(R.id.scrim).setOnClickListener(v -> finish());
        tiles = findViewById(R.id.tiles);
        sleepTile = tile(R.drawable.ic_moon, R.string.sleep_timer, v -> {
            if (!SleepTimer.available()) {
                Toast.makeText(this, R.string.sleep_unavailable, Toast.LENGTH_LONG).show();
                return;
            }
            SleepTimer.set(SleepTimer.next());
            refresh();
        });
        frameTile = tile(R.drawable.ic_picture, R.string.nas_photos, v -> {
            NasPhotos s = NasPhotos.load(this);
            if (!s.configured()) {
                Toast.makeText(this, R.string.nas_status_unconfigured, Toast.LENGTH_LONG).show();
                return;
            }
            NasPhotos.prefs(this).edit().putBoolean(NasPhotos.KEY_ENABLED, !s.enabled).apply();
            NasPhotos.bumpRevision(this);
            refresh();
        });
        airplayTile = tile(R.drawable.ic_airplay, R.string.airplay, v -> open(airplayApp()));
        tile(R.drawable.ic_picture, R.string.nas_photos_settings, v -> open(new Intent(this, NasPhotoSettingsActivity.class)));
        // Through Home, so it rebuilds with the new choices when Launcher settings closes.
        tile(R.drawable.ic_home_settings, R.string.launcher_settings, v -> open(
                new Intent(this, MainActivity.class).setAction(MainActivity.ACTION_LAUNCHER_SETTINGS)));
        tile(R.drawable.ic_settings, R.string.tv_settings, v -> open(SystemSettings.intent(this, Settings.ACTION_SETTINGS)));
        refresh();
        sleepTile.requestFocus();
    }

    /** The control pill pressed again while the panel is up. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        finish();
    }

    @Override
    protected void onPause() {
        super.onPause();
        finish();
        overridePendingTransition(0, 0);
    }

    private View tile(int icon, int title, View.OnClickListener click) {
        View t = getLayoutInflater().inflate(R.layout.item_cc_tile, tiles, false);
        ((ImageView) t.findViewById(R.id.icon)).setImageResource(icon);
        ((TextView) t.findViewById(R.id.title)).setText(title);
        t.setOnClickListener(click);
        tiles.addView(t);
        setState(t, false, "");
        return t;
    }

    /** On tiles are light with dark text, like a phone's toggles; off ones are glass with white text. */
    private void setState(View t, boolean on, String state) {
        t.setActivated(on);
        int text = on ? 0xFF111317 : 0xFFFFFFFF;
        ((TextView) t.findViewById(R.id.title)).setTextColor(text);
        TextView s = t.findViewById(R.id.state);
        s.setTextColor(on ? 0xB3111317 : 0x99FFFFFF);
        s.setText(state);
        s.setVisibility(state.isEmpty() ? View.GONE : View.VISIBLE);
        ((ImageView) t.findViewById(R.id.icon)).setColorFilter(text);
    }

    private void refresh() {
        int left = SleepTimer.minutesLeft();
        setState(sleepTile, left > 0, !SleepTimer.available() ? getString(R.string.sleep_unavailable_short)
                : left > 0 ? getString(R.string.sleep_left, left) : getString(R.string.off));
        NasPhotos s = NasPhotos.load(this);
        setState(frameTile, s.active(), getString(s.active() ? R.string.on : R.string.off));
        String name = AirPlayStatus.name();
        setState(airplayTile, name != null, name != null ? name
                : AirPlayStatus.known() ? getString(R.string.airplay_off) : "");
    }

    private Intent airplayApp() {
        PackageManager pm = getPackageManager();
        for (String pkg : AIRPLAY_APPS) {
            Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (i == null) i = pm.getLaunchIntentForPackage(pkg);
            if (i != null) return i;
        }
        return null;
    }

    private void open(Intent intent) {
        if (intent == null) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }
}
