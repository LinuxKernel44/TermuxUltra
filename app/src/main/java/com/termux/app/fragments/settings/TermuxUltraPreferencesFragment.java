package com.termux.app.fragments.settings;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Keep;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.termux.R;
import com.termux.app.TermuxService;
import com.termux.app.TermuxUltraPreferences;
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_SERVICE;

/** The preferences that are specific to TermuxUltra. */
@Keep
public class TermuxUltraPreferencesFragment extends PreferenceFragmentCompat {

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        getPreferenceManager().setSharedPreferencesName(TermuxUltraPreferences.FILE_NAME);
        setPreferencesFromResource(R.xml.termuxultra_preferences, rootKey);

        Preference wakeLockPreference = findPreference(TermuxUltraPreferences.KEY_WAKE_LOCK_ON_START);
        if (wakeLockPreference != null) {
            wakeLockPreference.setOnPreferenceChangeListener((preference, newValue) -> {
                // Take effect now, the notification is updated by the service.
                Context context = getContext();
                if (context != null && Boolean.TRUE.equals(newValue)) {
                    context.startService(new Intent(context, TermuxService.class).setAction(TERMUX_SERVICE.ACTION_WAKE_LOCK));
                }
                return true;
            });
        }
    }

}
