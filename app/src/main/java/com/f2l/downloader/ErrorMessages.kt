package com.f2l.downloader

import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Turns raw exceptions into plain-language messages that tell the user what to do next. */
object ErrorMessages {
    fun friendly(e: Throwable): String = friendly(e.message, e)

    fun friendly(raw: String?, e: Throwable? = null): String {
        val m = raw.orEmpty()
        return when {
            e is UnknownHostException || m.contains("Unable to resolve host", true) ->
                "Can't reach the server. Check your internet connection, then tap Retry."
            e is SocketTimeoutException || m.contains("timeout", true) || m.contains("timed out", true) ->
                "Connection timed out. The server or your network is slow — tap Retry."
            m.contains("ENOSPC", true) || m.contains("No space left", true) ->
                "Not enough storage space. Free up some space, then tap Retry."
            e is SecurityException || m.contains("permission revoked", true) ||
                m.contains("re-select the download folder", true) || m.contains("Cannot write to download folder", true) ->
                "F2L lost access to the download folder. Go to Settings → Download folder, pick it again, then tap Retry."
            e is SSLException ->
                "Secure connection failed. The site certificate may be invalid, or your phone date/time is wrong."
            e is ConnectException || m.contains("Failed to connect", true) ->
                "Couldn't connect to the server. It may be down — try again later."
            m.contains("Software caused connection abort", true) || m.contains("unexpected end of stream", true) ||
                m.contains("Connection reset", true) || e is SocketException ->
                "Connection dropped. Tap Retry to continue where it stopped."
            m.contains("Missing segment", true) ->
                "Some downloaded parts went missing. Delete this download and add it again."
            m.contains("Cannot create", true) || m.contains("Cannot open", true) ->
                "Couldn't write the file to your folder. Check free space and folder access, then tap Retry."
            m.isBlank() -> "Something went wrong. Tap Retry."
            else -> m
        }
    }
}
