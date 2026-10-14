package com.termux.app.fragments.settings;

import android.os.Bundle;

import androidx.annotation.Keep;
import androidx.preference.PreferenceFragmentCompat;

import com.termux.R;
import com.termux.app.terminal.TermuxTabBarController;

/** The preferences that are specific to TermuxUltra. */
@Keep
public class TermuxUltraPreferencesFragment extends PreferenceFragmentCompat {

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        getPreferenceManager().setSharedPreferencesName(TermuxTabBarController.PREFERENCES_FILE);
        setPreferencesFromResource(R.xml.termuxultra_preferences, rootKey);
    }

}
