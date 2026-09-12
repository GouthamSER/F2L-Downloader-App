package com.f2l.downloader

import android.content.Context

class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("f2l_settings", Context.MODE_PRIVATE)

    var wifiOnly: Boolean
        get() = prefs.getBoolean("wifi_only", false)
        set(v) = prefs.edit().putBoolean("wifi_only", v).apply()

    var notifications: Boolean
        get() = prefs.getBoolean("notifications", true)
        set(v) = prefs.edit().putBoolean("notifications", v).apply()

    var autoResume: Boolean
        get() = prefs.getBoolean("auto_resume", true)
        set(v) = prefs.edit().putBoolean("auto_resume", v).apply()

    var retryAttempts: Int
        get() = prefs.getInt("retry_attempts", 3)
        set(v) = prefs.edit().putInt("retry_attempts", v).apply()

    var defaultConnections: Int
        get() = prefs.getInt("default_connections", 8)
        set(v) = prefs.edit().putInt("default_connections", v).apply()

    var defaultFolderUri: String?
        get() = prefs.getString("default_folder", null)
        set(v) = prefs.edit().putString("default_folder", v).apply()

    // aria2 JSON-RPC connection (bring your own aria2c: bundled binary or remote/PC daemon)
    var aria2Enabled: Boolean
        get() = prefs.getBoolean("aria2_enabled", false)
        set(v) = prefs.edit().putBoolean("aria2_enabled", v).apply()

    var aria2Host: String
        get() = prefs.getString("aria2_host", "127.0.0.1") ?: "127.0.0.1"
        set(v) = prefs.edit().putString("aria2_host", v).apply()

    var aria2Port: Int
        get() = prefs.getInt("aria2_port", 6800)
        set(v) = prefs.edit().putInt("aria2_port", v).apply()

    var aria2Secret: String
        get() = prefs.getString("aria2_secret", "") ?: ""
        set(v) = prefs.edit().putString("aria2_secret", v).apply()

    // aria2 writes directly to a filesystem path (not a SAF tree Uri like the HTTP engine uses)
    var aria2SaveDir: String
        get() = prefs.getString("aria2_save_dir", "/sdcard/Download") ?: "/sdcard/Download"
        set(v) = prefs.edit().putString("aria2_save_dir", v).apply()
}
