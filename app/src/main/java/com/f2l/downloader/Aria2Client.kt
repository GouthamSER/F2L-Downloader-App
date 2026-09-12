package com.f2l.downloader

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Talks to a running aria2c process over its JSON-RPC interface.
 * aria2c itself is NOT bundled here — point this at:
 *   - a local aria2c you launch as a subprocess / bundle in jniLibs, or
 *   - a remote/PC aria2 daemon reachable on your network (host:port).
 * Handles direct HTTP(S) links, magnet links, and .torrent files (base64 payload) via one API.
 */
class Aria2Client(private val host: String, private val port: Int, private val secret: String?) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val rpcUrl get() = "http://$host:$port/jsonrpc"
    private fun token() = secret?.takeIf { it.isNotBlank() }?.let { "token:$it" }

    private suspend fun call(method: String, params: JSONArray): JSONObject = withContext(Dispatchers.IO) {
        val allParams = JSONArray()
        token()?.let { allParams.put(it) }
        for (i in 0 until params.length()) allParams.put(params.get(i))

        val body = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", "f2l")
            put("method", method)
            put("params", allParams)
        }
        val req = Request.Builder()
            .url(rpcUrl)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: throw Exception("Empty aria2 response")
            val json = JSONObject(text)
            if (json.has("error")) throw Exception(json.getJSONObject("error").optString("message", "aria2 error"))
            json
        }
    }

    /** Add a direct HTTP(S) link or a magnet URI. Returns the aria2 GID. */
    suspend fun addUri(uri: String, saveDir: String, fileName: String? = null): String {
        val opts = JSONObject().apply {
            put("dir", saveDir)
            if (fileName != null) put("out", fileName)
        }
        val res = call("aria2.addUri", JSONArray().put(JSONArray().put(uri)).put(opts))
        return res.getString("result")
    }

    /** Add a .torrent file (raw bytes) for download. Returns the aria2 GID. */
    suspend fun addTorrent(torrentBytes: ByteArray, saveDir: String): String {
        val b64 = Base64.encodeToString(torrentBytes, Base64.NO_WRAP)
        val opts = JSONObject().apply { put("dir", saveDir) }
        val res = call("aria2.addTorrent", JSONArray().put(b64).put(JSONArray()).put(opts))
        return res.getString("result")
    }

    suspend fun pause(gid: String) = call("aria2.pause", JSONArray().put(gid))
    suspend fun unpause(gid: String) = call("aria2.unpause", JSONArray().put(gid))
    suspend fun remove(gid: String) = runCatching { call("aria2.remove", JSONArray().put(gid)) }
        .recoverCatching { call("aria2.forceRemove", JSONArray().put(gid)) }

    data class Status(val gid: String, val completed: Long, val total: Long, val speed: Long, val state: String, val error: String?)

    suspend fun tellStatus(gid: String): Status {
        val res = call("aria2.tellStatus", JSONArray().put(gid).put(
            JSONArray().put("gid").put("completedLength").put("totalLength").put("downloadSpeed").put("status").put("errorMessage")
        ))
        val r = res.getJSONObject("result")
        return Status(
            gid = r.getString("gid"),
            completed = r.optString("completedLength", "0").toLongOrNull() ?: 0,
            total = r.optString("totalLength", "0").toLongOrNull() ?: 0,
            speed = r.optString("downloadSpeed", "0").toLongOrNull() ?: 0,
            state = r.optString("status", "unknown"),
            error = r.optString("errorMessage", null)
        )
    }

    /** Quick reachability check for Settings screen "Test connection". */
    suspend fun ping(): Boolean = runCatching { call("aria2.getVersion", JSONArray()) }.isSuccess
}
