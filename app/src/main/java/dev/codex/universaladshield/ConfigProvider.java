package dev.codex.universaladshield;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

public final class ConfigProvider extends ContentProvider {
    @Override public boolean onCreate() {
        return true;
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (!"get".equals(method) || getContext() == null) return Bundle.EMPTY;
        String pkg = arg;
        if ((pkg == null || pkg.isEmpty()) && extras != null) pkg = extras.getString("package");
        SharedPreferences prefs = getContext().getSharedPreferences(AppConfig.PREFS, 0);
        return AppConfig.fromPrefs(prefs, pkg == null ? "" : pkg);
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override public String getType(Uri uri) {
        return null;
    }

    @Override public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
