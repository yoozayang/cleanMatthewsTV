package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * The sleep timer over adb, for a home automation or to see the warning card without waiting half an hour:
 * {@code adb shell am broadcast -n com.gathin.tvhome/.SleepTimerConfig --ei seconds 70} (0 turns it off).
 * Only the shell can send it (WRITE_SECURE_SETTINGS), as with GuardConfig.
 */
public class SleepTimerConfig extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent.hasExtra("seconds")) SleepTimer.setSeconds(intent.getIntExtra("seconds", 0));
        else if (intent.hasExtra("minutes")) SleepTimer.set(intent.getIntExtra("minutes", 0));
        setResultData("available=" + SleepTimer.available() + " minutes_left=" + SleepTimer.minutesLeft());
    }
}
