package de.levingamer8.modlauncher.ui.dialogs;

import java.util.Locale;
import java.util.prefs.Preferences;

public final class LauncherSettings {
    private static final Preferences PREFS = Preferences.userNodeForPackage(LauncherSettings.class);

    private static final String KEY_LANG = "lang";
    private static final String KEY_COUNTRY = "country";
    private static final String KEY_RAM_MB = "ram_mb";

    private LauncherSettings() {}

    public static Locale getLocale() {
        String lang = PREFS.get(KEY_LANG, "");
        String country = PREFS.get(KEY_COUNTRY, "");
        if (lang == null || lang.isBlank()) return Locale.getDefault();
        if (country != null && !country.isBlank()) return Locale.of(lang, country);
        return Locale.of(lang);
    }

    public static void setLocale(Locale locale) {
        if (locale == null) return;
        PREFS.put(KEY_LANG, locale.getLanguage());
        PREFS.put(KEY_COUNTRY, locale.getCountry() == null ? "" : locale.getCountry());
    }

    /** Default 4096MB */
    public static int getRamMb() {
        return PREFS.getInt(KEY_RAM_MB, 4096);
    }

    public static void setRamMb(int ramMb) {
        int clamped = Math.max(512, Math.min(ramMb, 65536));
        PREFS.putInt(KEY_RAM_MB, clamped);
    }
}
