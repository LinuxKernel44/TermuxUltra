package com.termux.app;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

/** Names and accessors of the preferences that are specific to TermuxUltra. */
public final class TermuxUltraPreferences {

    public static final String FILE_NAME = "termuxultra_prefs";

    /** How long the tab bar stays visible, in milliseconds as a string. "0" means never hide. */
    public static final String KEY_TAB_BAR_HIDE_DELAY_MS = "tab_bar_hide_delay";
    public static final int DEFAULT_TAB_BAR_HIDE_DELAY_MS = 4000;

    /** Whether the wake lock is acquired every time Termux is opened. */
    public static final String KEY_WAKE_LOCK_ON_START = "wake_lock_on_start";
    public static final boolean DEFAULT_WAKE_LOCK_ON_START = false;

    private TermuxUltraPreferences() {}

    @NonNull
    public static SharedPreferences get(@NonNull Context context) {
        return context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE);
    }

    public static boolean shouldAcquireWakeLockOnStart(@NonNull Context context) {
        return get(context).getBoolean(KEY_WAKE_LOCK_ON_START, DEFAULT_WAKE_LOCK_ON_START);
    }

}
