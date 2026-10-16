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

    /** Height of the zone, from the top edge of the terminal, where a vertical swipe shows or hides the tab bar. */
    public static final String KEY_TAB_BAR_SWIPE_ZONE_DP = "tab_bar_swipe_zone_dp";
    public static final int DEFAULT_TAB_BAR_SWIPE_ZONE_DP = 48;
    public static final int MIN_TAB_BAR_SWIPE_ZONE_DP = 24;
    public static final int MAX_TAB_BAR_SWIPE_ZONE_DP = 240;

    /** Whether the volume keys keep their normal behaviour instead of acting as Ctrl and Fn keys. */
    public static final String KEY_NORMAL_VOLUME_KEYS = "normal_volume_keys";
    public static final boolean DEFAULT_NORMAL_VOLUME_KEYS = false;

    private TermuxUltraPreferences() {}

    @NonNull
    public static SharedPreferences get(@NonNull Context context) {
        return context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE);
    }

    public static int getTabBarSwipeZoneDp(@NonNull Context context) {
        int value = get(context).getInt(KEY_TAB_BAR_SWIPE_ZONE_DP, DEFAULT_TAB_BAR_SWIPE_ZONE_DP);
        return Math.max(MIN_TAB_BAR_SWIPE_ZONE_DP, Math.min(MAX_TAB_BAR_SWIPE_ZONE_DP, value));
    }

    public static boolean shouldUseNormalVolumeKeys(@NonNull Context context) {
        return get(context).getBoolean(KEY_NORMAL_VOLUME_KEYS, DEFAULT_NORMAL_VOLUME_KEYS);
    }

    public static boolean shouldAcquireWakeLockOnStart(@NonNull Context context) {
        return get(context).getBoolean(KEY_WAKE_LOCK_ON_START, DEFAULT_WAKE_LOCK_ON_START);
    }

}
