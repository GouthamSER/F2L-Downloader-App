package com.f2l.downloader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class DownloadRepository(context: Context) {
    private val prefs = context.getSharedPreferences("f2l_downloads", Context.MODE_PRIVATE)

    fun load(): List<DownloadItem> {
        val a = JSONArray(prefs.getString("items", "[]") ?: "[]")
        return buildList {
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                add(DownloadItem(
                    id = o.getLong("id"),
                    url = o.getString("url"),
                    fileName = o.getString("fileName"),
                    folderUri = o.optString("folderUri", ""),
                    totalBytes = o.optLong("totalBytes", -1),
                    downloadedBytes = o.optLong("downloadedBytes", 0),
                    status = runCatching {
                        DownloadItem.Status.valueOf(o.optString("status", "QUEUED"))
                    }.getOrDefault(DownloadItem.Status.QUEUED),
                    error = if (o.has("error") && !o.isNull("error")) o.getString("error") else null,
                    connections = o.optInt("connections", 8),
                    userAgent = if (o.has("userAgent") && !o.isNull("userAgent")) o.getString("userAgent") else null,
                    referer = if (o.has("referer") && !o.isNull("referer")) o.getString("referer") else null,
                    customHeaders = if (o.has("customHeaders") && !o.isNull("customHeaders")) o.getString("customHeaders") else null,
                    isTorrent = o.optBoolean("isTorrent", false),
                    magnetHash = if (o.has("magnetHash") && !o.isNull("magnetHash")) o.getString("magnetHash") else null
                ))
            }
        }
    }

    fun save(items: List<DownloadItem>) {
        val a = JSONArray()
        items.forEach { item ->
            a.put(JSONObject().apply {
                put("id", item.id)
                put("url", item.url)
                put("fileName", item.fileName)
                put("folderUri", item.folderUri)
                put("totalBytes", item.totalBytes)
                put("downloadedBytes", item.downloadedBytes)
                put("status", item.status.name)
                put("connections", item.connections)
                if (item.error != null) put("error", item.error)
                if (item.userAgent != null) put("userAgent", item.userAgent)
                if (item.referer != null) put("referer", item.referer)
                if (item.customHeaders != null) put("customHeaders", item.customHeaders)
                if (item.isTorrent) put("isTorrent", true)
                if (item.magnetHash != null) put("magnetHash", item.magnetHash)
            })
        }
        prefs.edit().putString("items", a.toString()).apply()
    }
}
