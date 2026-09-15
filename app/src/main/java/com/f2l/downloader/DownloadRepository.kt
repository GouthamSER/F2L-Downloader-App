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
                    error = o.optString("error", null),
                    connections = o.optInt("connections", 8),
                    isTorrent = o.optBoolean("isTorrent", false),
                    peers = o.optInt("peers", 0),
                    seeders = o.optInt("seeders", 0)
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
                put("isTorrent", item.isTorrent)
                put("peers", item.peers)
                put("seeders", item.seeders)
                if (item.error != null) put("error", item.error)
            })
        }
        prefs.edit().putString("items", a.toString()).apply()
    }
}
