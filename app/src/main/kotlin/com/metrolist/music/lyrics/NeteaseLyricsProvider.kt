/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Netease Cloud Music lyrics, re-implemented for Metrolist's current lyrics
 * provider architecture (inspired by grill-glitch/Metrolist). Uses Netease's
 * public search + lyric endpoints — no EAPI/encryption required. On any failure
 * it returns Result.failure so the lyrics pipeline falls back to other providers.
 */
package com.metrolist.music.lyrics

import android.content.Context
import com.metrolist.music.constants.EnableNeteaseKey
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.math.abs

object NeteaseLyricsProvider : LyricsProvider {
    override val name = "Netease"

    private const val BASE = "https://music.163.com/api"

    private val client =
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()

    // Enabled by default; it sits last in the provider order, so it's only
    // queried when every other provider returns nothing.
    override fun isEnabled(context: Context): Boolean = context.dataStore[EnableNeteaseKey] ?: true

    override suspend fun getLyrics(
        context: Context,
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val songId = searchSongId(title, artist, duration)
                ?: throw IllegalStateException("No Netease match for $title - $artist")
            val lyric = fetchLyric(songId)
                ?: throw IllegalStateException("No Netease lyrics for id $songId")
            lyric
        }
    }

    /**
     * Search Netease for the closest matching track id (best duration match).
     * @return Netease song id, or null if nothing was found.
     */
    private fun searchSongId(title: String, artist: String, duration: Int): Long? {
        val query = URLEncoder.encode("$title $artist".trim(), "UTF-8")
        val request = Request.Builder()
            .url("$BASE/search/get?s=$query&type=1&limit=5&offset=0")
            .header("User-Agent", "Mozilla/5.0")
            .header("Referer", "https://music.163.com")
            .header("Cookie", "os=pc; appver=8.0.0")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            val songs = JSONObject(body)
                .optJSONObject("result")
                ?.optJSONArray("songs") ?: return null

            var bestId: Long? = null
            var bestDiff = Long.MAX_VALUE
            val targetMs = duration.toLong() * 1000L
            for (i in 0 until songs.length()) {
                val song = songs.optJSONObject(i) ?: continue
                val sid = song.optLong("id", -1L)
                if (sid <= 0) continue
                // Netease "duration" is milliseconds; pick the nearest in length.
                val diff = abs(song.optLong("duration", 0L) - targetMs)
                if (bestId == null || diff < bestDiff) {
                    bestId = sid
                    bestDiff = diff
                }
            }
            return bestId
        }
    }

    /**
     * Fetch the (synced) LRC lyrics for a Netease song id.
     * @return LRC string, or null if unavailable.
     */
    private fun fetchLyric(songId: Long): String? {
        val request = Request.Builder()
            .url("$BASE/song/lyric?id=$songId&lv=1&kv=1&tv=-1")
            .header("User-Agent", "Mozilla/5.0")
            .header("Referer", "https://music.163.com")
            .header("Cookie", "os=pc; appver=8.0.0")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            val lyric = JSONObject(body).optJSONObject("lrc")?.optString("lyric").orEmpty()
            return lyric.ifBlank { null }
        }
    }
}
