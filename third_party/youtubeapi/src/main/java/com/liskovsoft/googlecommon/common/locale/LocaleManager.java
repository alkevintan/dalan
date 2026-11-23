package com.liskovsoft.googlecommon.common.locale;

import android.os.Build.VERSION;
import com.liskovsoft.sharedutils.locale.LocaleUpdater;
import com.liskovsoft.sharedutils.locale.LocaleUtility;
import com.liskovsoft.sharedutils.prefs.GlobalPreferences;

import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public class LocaleManager {
    private static volatile LocaleManager sInstance;
    private static volatile String sLanguageOverride;
    private String mLang;
    private String mCountry;
    private int mOffsetFromUtcMinutes;

    private LocaleManager() {
        initLang();
        initUtcOffset();
    }

    public static LocaleManager instance() {
        LocaleManager result = sInstance;
        if (result == null) {
            synchronized (LocaleManager.class) {
                result = sInstance;
                if (result == null) {
                    result = sInstance = new LocaleManager();
                }
            }
        }

        return result;
    }

    /**
     * Forces the content language (BCP-47) and survives {@link #unhold()}. Empty or null means no override.
     */
    public static void setLanguageOverride(String tagOrNull) {
        sLanguageOverride = (tagOrNull == null || tagOrNull.isEmpty()) ? null : tagOrNull;
    }

    public static void unhold() {
        sInstance = null;
    }

    public String getLanguage() {
        String override = sLanguageOverride;
        return override != null ? override : mLang;
    }

    /**
     * Used in tests only
     */
    public void setLanguage(String lang) {
        mLang = lang;
    }

    public String getCountry() {
        return mCountry;
    }

    /**
     * Used in tests only
     */
    public void setCountry(String country) {
        mCountry = country;
    }

    public int getUtcOffsetMinutes() {
        return mOffsetFromUtcMinutes;
    }

    private void initLang() {
        Locale locale = getLocale();

        if (VERSION.SDK_INT >= 21) {
            // Use BCP-47 code for language code to get content with correct language.
            // For example, BCP-47 has zn-CN for simplified Chinese and zh-TW for traditional Chinese.
            mLang = locale.toLanguageTag();
        } else {
            mLang = locale.getLanguage();
        }

        mCountry = locale.getCountry();
    }

    private void initUtcOffset() {
        TimeZone tz = TimeZone.getDefault();
        Date now = new Date();
        mOffsetFromUtcMinutes = tz.getOffset(now.getTime()) / 1_000 / 60;
    }

    private Locale getLocale() {
        Locale locale;

        // Proper locale
        if (GlobalPreferences.isInitialized()) {
            locale = LocaleUpdater.getSavedLocale(GlobalPreferences.sInstance.getContext());

            if (locale == null) {
                locale = LocaleUtility.getCurrentLocale(GlobalPreferences.sInstance.getContext());
            }
        } else { // Fallback locale
            locale = Locale.getDefault();
        }

        return locale;
    }
}
