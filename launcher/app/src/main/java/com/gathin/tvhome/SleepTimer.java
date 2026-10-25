package com.gathin.tvhome;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A sleep timer that runs in this process, which the remote-key helper (HomeKeyService) keeps alive while other
 * apps are on screen. A minute before the end a small card over whatever is playing offers "15 more minutes",
 * "Turn off now" and "Cancel"; with no answer the TV goes to standby through the helper's lock-screen action.
 * Without the helper bound there is no way to sleep the TV, so the timer refuses to start (available()).
 */
final class SleepTimer {
    static final int[] CHOICES = {0, 30, 60, 90, 120};
    private static final long WARN_MS = 60_000L;

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static long deadline;   // elapsedRealtime, 0 when off
    private static View warning;
    private static TextView warningText;
    /** The minutes it was last set to, for the control centre's cycle; 0 when off. */
    private static int setMinutes;
    // Fixed instances: a method reference is a new object each time, and removeCallbacks would match none.
    private static final Runnable WARN = SleepTimer::warn;
    private static final Runnable SLEEP = SleepTimer::sleep;
    private static final Runnable TICK = SleepTimer::tickWarning;

    private SleepTimer() {
    }

    static boolean available() {
        return HomeKeyService.overlayContext() != null;
    }

    static boolean running() {
        return deadline != 0;
    }

    /** Whole minutes left, rounded up; 0 when off. */
    static int minutesLeft() {
        if (deadline == 0) return 0;
        long ms = Math.max(0, deadline - SystemClock.elapsedRealtime());
        return (int) ((ms + 59_999) / 60_000);
    }

    static void set(int minutes) {
        setSeconds(minutes * 60);
        setMinutes = Math.max(0, minutes);
    }

    /** In seconds, for SleepTimerConfig over adb (a short timer to see the warning card); 0 turns it off. */
    static void setSeconds(int seconds) {
        main.removeCallbacksAndMessages(null);
        dismissWarning();
        setMinutes = 0;
        if (seconds <= 0) {
            deadline = 0;
            return;
        }
        deadline = SystemClock.elapsedRealtime() + seconds * 1000L;
        main.postDelayed(WARN, Math.max(0, seconds * 1000L - WARN_MS));
    }

    /** The next choice after the current one (Off, 30, 60, 90, 120, Off ...), as the control centre's toggle. */
    static int next() {
        for (int i = 0; i < CHOICES.length; i++) {
            if (CHOICES[i] == setMinutes) return CHOICES[(i + 1) % CHOICES.length];
        }
        return CHOICES[1];
    }

    private static void warn() {
        if (deadline == 0) return;
        main.postDelayed(SLEEP, Math.max(0, deadline - SystemClock.elapsedRealtime()));
        Context ctx = HomeKeyService.overlayContext();
        if (ctx == null) return;
        showWarning(ctx);
        tickWarning();
    }

    private static void sleep() {
        dismissWarning();
        deadline = 0;
        setMinutes = 0;
        HomeKeyService.sleepNow();
    }

    private static void tickWarning() {
        if (warningText == null || deadline == 0) return;
        long s = Math.max(0, (deadline - SystemClock.elapsedRealtime() + 999) / 1000);
        warningText.setText(warningText.getContext().getString(R.string.sleep_warning, s));
        main.postDelayed(TICK, 1000);
    }

    private static void showWarning(Context ctx) {
        if (warning != null) return;
        float d = ctx.getResources().getDisplayMetrics().density;
        LinearLayout panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(22 * d);
        panel.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF0181C22);
        bg.setCornerRadius(22 * d);
        panel.setBackground(bg);

        warningText = new TextView(ctx);
        warningText.setTextColor(0xFFFFFFFF);
        warningText.setTextSize(20);
        panel.addView(warningText);

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, Math.round(16 * d), 0, 0);
        TextView more = button(ctx, R.string.sleep_more, v -> set(minutesLeft() + 15));
        row.addView(more);
        row.addView(button(ctx, R.string.sleep_now, v -> sleep()));
        row.addView(button(ctx, android.R.string.cancel, v -> set(0)));
        panel.addView(row);
        // Back puts the card away; the timer keeps running.
        panel.setOnKeyListener((v, code, e) -> {
            if (code == KeyEvent.KEYCODE_BACK && e.getAction() == KeyEvent.ACTION_UP) dismissWarning();
            return code == KeyEvent.KEYCODE_BACK;
        });

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.END;
        lp.x = Math.round(48 * d);
        lp.y = Math.round(48 * d);
        try {
            ctx.getSystemService(WindowManager.class).addView(panel, lp);
            warning = panel;
            more.requestFocus();
        } catch (RuntimeException e) {
            warningText = null;   // no overlay on this firmware: the TV still sleeps on time
        }
    }

    private static TextView button(Context ctx, int text, View.OnClickListener click) {
        float d = ctx.getResources().getDisplayMetrics().density;
        TextView b = new TextView(ctx);
        b.setText(text);
        b.setTextColor(0xFFFFFFFF);
        b.setTextSize(17);
        b.setGravity(Gravity.CENTER);
        b.setBackgroundResource(R.drawable.settings_choice_bg);
        b.setFocusable(true);
        b.setClickable(true);
        b.setOnClickListener(click);
        int h = Math.round(18 * d);
        b.setPadding(h, Math.round(10 * d), h, Math.round(10 * d));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(Math.round(10 * d));
        b.setLayoutParams(lp);
        return b;
    }

    private static void dismissWarning() {
        main.removeCallbacks(TICK);
        if (warning == null) return;
        try {
            warning.getContext().getSystemService(WindowManager.class).removeView(warning);
        } catch (RuntimeException ignored) {
            // The helper was unbound and took its window with it.
        }
        warning = null;
        warningText = null;
    }
}
