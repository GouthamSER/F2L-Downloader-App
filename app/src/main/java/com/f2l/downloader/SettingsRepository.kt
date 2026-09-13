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

    /** "dark" or "light" */
    var themeMode: String
        get() = prefs.getString("theme_mode", "dark") ?: "dark"
        set(v) = prefs.edit().putString("theme_mode", v).apply()
}
