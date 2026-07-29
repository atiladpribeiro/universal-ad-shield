package io.github.atiladpribeiro.universaladshield;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;

final class AppConfig {
    static final String AUTHORITY = "io.github.atiladpribeiro.universaladshield.config";
    static final String PREFS = "uas_config";
    private static volatile long providerRetryAfter;

    final boolean enabled;
    final boolean overlay;
    final boolean blockTouches;
    final boolean muteAds;
    final boolean blockExternal;
    final boolean accelerate;
    final boolean autoClose;
    final boolean playableHelper;
    final int maxSpeed;

    AppConfig(Bundle b) {
        enabled = b.getBoolean("enabled", true);
        overlay = b.getBoolean("overlay", true);
        blockTouches = b.getBoolean("blockTouches", true);
        muteAds = b.getBoolean("muteAds", true);
        blockExternal = b.getBoolean("blockExternal", true);
        accelerate = b.getBoolean("accelerate", true);
        autoClose = b.getBoolean("autoClose", true);
        playableHelper = b.getBoolean("playableHelper", true);
        maxSpeed = Math.max(1, Math.min(8, b.getInt("maxSpeed", 4)));
    }

    static AppConfig defaults() {
        return new AppConfig(defaultBundle(null));
    }

    static AppConfig load(Context context, String packageName) {
        if (context == null) return defaults();
        long now = SystemClock.uptimeMillis();
        if (now < providerRetryAfter) return new AppConfig(defaultBundle(packageName));
        try {
            ContentResolver resolver = context.getContentResolver();
            Bundle args = new Bundle();
            args.putString("package", packageName);
            Bundle b = resolver.call(Uri.parse("content://" + AUTHORITY), "get", packageName, args);
            if (b != null) return new AppConfig(b);
        } catch (Throwable ignored) {
            providerRetryAfter = now + 30000;
        }
        return new AppConfig(defaultBundle(packageName));
    }

    static Bundle defaultBundle(String packageName) {
        Bundle b = new Bundle();
        b.putBoolean("enabled", true);
        b.putBoolean("overlay", true);
        b.putBoolean("blockTouches", true);
        b.putBoolean("muteAds", true);
        b.putBoolean("blockExternal", true);
        b.putBoolean("accelerate", true);
        b.putBoolean("autoClose", true);
        b.putBoolean("playableHelper", true);
        b.putInt("maxSpeed", 4);
        return b;
    }

    static Bundle fromPrefs(SharedPreferences prefs, String packageName) {
        Bundle b = defaultBundle(packageName);
        applyPrefs(b, prefs, "global.");
        if (prefs.getBoolean("app." + packageName + ".custom", false)) {
            applyPrefs(b, prefs, "app." + packageName + ".");
        }
        return b;
    }

    private static void applyPrefs(Bundle b, SharedPreferences prefs, String prefix) {
        putBool(b, prefs, prefix, "enabled");
        putBool(b, prefs, prefix, "overlay");
        putBool(b, prefs, prefix, "blockTouches");
        putBool(b, prefs, prefix, "muteAds");
        putBool(b, prefs, prefix, "blockExternal");
        putBool(b, prefs, prefix, "accelerate");
        putBool(b, prefs, prefix, "autoClose");
        putBool(b, prefs, prefix, "playableHelper");
        String speedKey = prefix + "maxSpeed";
        if (prefs.contains(speedKey)) b.putInt("maxSpeed", prefs.getInt(speedKey, b.getInt("maxSpeed", 4)));
    }

    private static void putBool(Bundle b, SharedPreferences prefs, String prefix, String name) {
        String key = prefix + name;
        if (prefs.contains(key)) b.putBoolean(name, prefs.getBoolean(key, b.getBoolean(name, true)));
    }
}
