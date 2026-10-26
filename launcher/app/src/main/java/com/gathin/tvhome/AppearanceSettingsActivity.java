package com.gathin.tvhome;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * This fork's Appearance page: how strongly the time-of-day sky and the weather effects show, Off to Strong.
 * Saved at once; Home rebuilds with them when it next resumes (the change count, as Launcher settings does).
 */
public final class AppearanceSettingsActivity extends Activity {
    private static final int[] SKY = {0, 35, 60, 85};
    private static final int[] FX = {0, 35, 65, 100};
    private static final int[] LABELS = {R.string.off, R.string.level_low, R.string.level_medium, R.string.level_high};

    private SharedPreferences choices;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_appearance_settings);
        choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        int sky = Config.load(this).skyIntensity;
        row(findViewById(R.id.skyChoices), Config.SKY_CHOICE_KEY, SKY, sky);
        row(findViewById(R.id.fxChoices), Config.WEATHER_FX_KEY, FX, choices.getInt(Config.WEATHER_FX_KEY, 0));
        ((LinearLayout) findViewById(R.id.skyChoices)).getChildAt(0).requestFocus();
    }

    /** Four choices in a row; the saved value, or the one nearest to it, is the selected one. */
    private void row(LinearLayout row, String key, int[] values, int current) {
        int selected = nearest(values, current);
        for (int i = 0; i < values.length; i++) {
            TextView choice = (TextView) getLayoutInflater().inflate(R.layout.item_appearance_choice, row, false);
            choice.setText(LABELS[i]);
            choice.setSelected(i == selected);
            final int value = values[i];
            choice.setOnClickListener(v -> {
                choices.edit().putInt(key, value).apply();
                choices.edit().putLong(Config.SETTINGS_REVISION_KEY,
                        choices.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1).apply();
                setResult(RESULT_OK);
                for (int j = 0; j < row.getChildCount(); j++) row.getChildAt(j).setSelected(row.getChildAt(j) == v);
            });
            row.addView(choice);
        }
    }

    private static int nearest(int[] values, int value) {
        int best = 0;
        for (int i = 1; i < values.length; i++) {
            if (Math.abs(values[i] - value) < Math.abs(values[best] - value)) best = i;
        }
        return best;
    }
}
