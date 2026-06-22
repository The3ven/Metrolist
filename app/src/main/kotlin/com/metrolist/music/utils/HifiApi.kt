/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Adapted from sbhushan01/Metrolist: resolve a lossless ("HiFi") stream URL for
 * a track from a user-supplied external API. Returns null on any failure so
 * playback falls back to the normal YouTube stream.
 */
package com.metrolist.music.utils

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit

object HifiApi {
    private const val TAG = "HifiApi"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * @param apiBaseUrl Base URL of the HiFi resolver (e.g. https://my-hifi.example).
     * @param videoId    Track id to resolve.
     * @param quality    "lossless" or "hi_res_lossless".
     * @return A direct stream URL, or null if unavailable / on error.
     */
    fun resolveStreamUrlOrNull(
        apiBaseUrl: String,
        videoId: String,
        quality: String,
    ): String? {
        val endpoint = apiBaseUrl.trim().trimEnd('/')
        if (endpoint.isBlank()) return null

        return runCatching {
            val requestUrl = "$endpoint/stream?videoId=$videoId&quality=$quality"
            val request = Request.Builder().url(requestUrl).get().build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return null
                JSONObject(body).optString("url").takeIf { it.isNotBlank() }
            }
        }.onFailure {
            Timber.tag(TAG).w(it, "Failed to resolve HiFi stream URL")
        }.getOrNull()
    }
}
