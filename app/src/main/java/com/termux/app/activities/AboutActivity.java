package com.termux.app.activities;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.pm.PackageInfoCompat;

import com.termux.R;
import com.termux.shared.activity.media.AppCompatActivityUtils;
import com.termux.shared.android.PackageUtils;
import com.termux.shared.interact.ShareUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.theme.NightMode;

import java.util.Locale;

/** Shows the version of TermuxUltra, its links, credits and information about the device. */
public class AboutActivity extends AppCompatActivity {

    private static final String LOG_TAG = "AboutActivity";

    private static final String REPO_URL = "https://github.com/LinuxKernel44/TermuxUltra";
    private static final String RELEASES_URL = REPO_URL + "/releases";
    private static final String ISSUES_URL = REPO_URL + "/issues";
    private static final String WIKI_URL = "https://wiki.termux.com";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        AppCompatActivityUtils.setNightMode(this, NightMode.getAppNightMode().getName(), true);

        setContentView(R.layout.activity_about);
        AppCompatActivityUtils.setToolbar(this, com.termux.shared.R.id.toolbar);
        AppCompatActivityUtils.setToolbarTitle(this, com.termux.shared.R.id.toolbar, getString(R.string.about_title), 0);
        AppCompatActivityUtils.setShowBackButtonInActionBar(this, true);

        final String versionInfo = getVersionInfo();
        final String systemInfo = getSystemInfo();

        ((TextView) findViewById(R.id.about_version_info)).setText(versionInfo);
        ((TextView) findViewById(R.id.about_system_info)).setText(systemInfo);

        LinearLayout links = findViewById(R.id.about_links);
        addLink(links, R.string.about_link_repo, REPO_URL);
        addLink(links, R.string.about_link_releases, RELEASES_URL);
        addLink(links, R.string.about_link_report, ISSUES_URL);
        addLink(links, R.string.about_link_wiki, WIKI_URL);

        findViewById(R.id.about_copy_button).setOnClickListener(v -> {
            ShareUtils.copyTextToClipboard(this, TermuxConstants.TERMUX_APP_NAME + "\n" + versionInfo + "\n\n" + systemInfo);
            Logger.showToast(this, getString(R.string.about_copied), false);
        });
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }

    private void addLink(@NonNull LinearLayout parent, int labelResId, @NonNull String url) {
        TextView link = new TextView(this);
        link.setText(labelResId);
        link.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        link.setTextColor(getColorFromAttr(androidx.appcompat.R.attr.colorPrimary));
        int padding = Math.round(10 * getResources().getDisplayMetrics().density);
        link.setPadding(0, padding, 0, padding);
        link.setOnClickListener((View v) -> ShareUtils.openUrl(this, url));
        parent.addView(link);
    }

    private int getColorFromAttr(int attr) {
        TypedValue value = new TypedValue();
        getTheme().resolveAttribute(attr, value, true);
        return value.data;
    }

    @NonNull
    private String getVersionInfo() {
        String unknown = getString(R.string.about_unknown);
        String versionName = unknown;
        String versionCode = unknown;
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            if (info.versionName != null) versionName = info.versionName;
            versionCode = String.valueOf(PackageInfoCompat.getLongVersionCode(info));
        } catch (PackageManager.NameNotFoundException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to get package info", e);
        }

        boolean debuggable = (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;

        String certificate = PackageUtils.getSigningCertificateSHA256DigestForPackage(this);
        String certificateText = certificate == null ? unknown : formatDigest(certificate);

        return "Version: " + versionName + "\n" +
            "Version code: " + versionCode + "\n" +
            "Build type: " + (debuggable ? "debug" : "release") + "\n" +
            "Package: " + getPackageName() + "\n" +
            "Signing certificate SHA-256:\n" + certificateText;
    }

    /** Format a hexadecimal digest as upper case pairs separated by colons. */
    @NonNull
    private static String formatDigest(@NonNull String digest) {
        String hex = digest.replace(":", "").toUpperCase(Locale.ROOT);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < hex.length(); i += 2) {
            if (i > 0) out.append(':');
            out.append(hex, i, Math.min(i + 2, hex.length()));
        }
        return out.toString();
    }

    @NonNull
    private String getSystemInfo() {
        String abi = Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : getString(R.string.about_unknown);
        return "Android: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n" +
            "Device: " + Build.MANUFACTURER + " " + Build.MODEL + "\n" +
            "ABI: " + abi;
    }

}
