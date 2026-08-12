package com.xjw.bilifix.in.feature.settings;

import static com.xjw.bilifix.in.core.ModuleConstants.TARGET_PACKAGE;
import static com.xjw.bilifix.in.feature.settings.SettingsManager.ADVANCED_SETTINGS_FRAGMENT;
import static com.xjw.bilifix.in.feature.settings.SettingsManager.ARG_BILIFIX_SETTINGS_PAGE;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

final class SettingsDeepLink {
    static final String URI = "bilibili://home/bf_settings";

    private static final String SCHEME = "bilibili";
    private static final String HOST = "home";
    private static final String PATH = "/bf_settings";
    private static final String HOST_SETTINGS_ACTIVITY =
            "com.bilibili.app.preferences.BiliPreferencesActivity";
    private static final String EXTRA_FRAGMENT = "extra:key:fragment";
    private static final String EXTRA_TITLE = "extra:key:title";

    private SettingsDeepLink() {
    }

    static boolean matches(Intent intent) {
        if (intent == null) {
            return false;
        }
        Uri uri = intent.getData();
        if (uri == null) {
            Bundle extras = intent.getExtras();
            String rawUri = extras == null ? null : extras.getString("uri");
            if (rawUri != null) {
                try {
                    uri = Uri.parse(rawUri);
                } catch (Throwable ignored) {
                    return false;
                }
            }
        }
        return matches(uri);
    }

    static boolean matches(Uri uri) {
        if (uri == null
                || !SCHEME.equalsIgnoreCase(uri.getScheme())
                || !HOST.equalsIgnoreCase(uri.getHost())) {
            return false;
        }
        return PATH.equals(uri.getPath());
    }

    static Intent settingsPageIntent() {
        return new Intent()
                .setClassName(TARGET_PACKAGE, HOST_SETTINGS_ACTIVITY)
                .putExtra(EXTRA_FRAGMENT, ADVANCED_SETTINGS_FRAGMENT)
                .putExtra(EXTRA_TITLE, "BiliFix")
                .putExtra(ARG_BILIFIX_SETTINGS_PAGE, true);
    }
}
