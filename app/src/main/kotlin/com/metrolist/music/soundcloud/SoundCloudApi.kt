package com.metrolist.music.soundcloud

import android.text.Html
import com.metrolist.music.models.MediaMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Minimal SoundCloud (api-v2) client used as an alternate audio source. SoundCloud
 * requires a public `client_id` which is scraped from the site and cached; tracks
 * carry an id prefixed with [ID_PREFIX] and the progressive stream is resolved lazily.
 */
object SoundCloudApi {
    const val ID_PREFIX = "SC::"

    private const val API = "https://api-v2.soundcloud.com"
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.36"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var cachedClientId: String? = null

    suspend fun search(query: String): List<SoundCloudTrack> = withContext(Dispatchers.IO) {
        try {
            val cid = clientId() ?: return@withContext emptyList()
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            val body = apiGet("$API/search/tracks?q=$encoded&limit=20", cid)
                ?: return@withContext emptyList()
            val collection = JSONObject(body).optJSONArray("collection") ?: return@withContext emptyList()

            (0 until collection.length()).mapNotNull { i ->
                val obj = collection.optJSONObject(i) ?: return@mapNotNull null
                val id = obj.optLong("id", 0L)
                if (id == 0L || !obj.optBoolean("streamable", true)) return@mapNotNull null

                val title = decode(obj.optString("title"))
                val artist = decode(obj.optJSONObject("user")?.optString("username").orEmpty())
                val durationMs = obj.optLong("duration", 0L)
                val thumbnail = (obj.optString("artwork_url").takeIf { it.isNotBlank() }
                    ?: obj.optJSONObject("user")?.optString("avatar_url")?.takeIf { it.isNotBlank() })
                    ?.replace("-large", "-t500x500")
                val permaUrl = obj.optString("permalink_url").takeIf { it.isNotBlank() }

                SoundCloudTrack(id, title, artist, durationMs, thumbnail, permaUrl)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun getStreamUrl(id: String): String? = withContext(Dispatchers.IO) {
        try {
            val cid = clientId() ?: return@withContext null
            val trackJson = apiGet("$API/tracks/${URLEncoder.encode(id, "UTF-8")}", cid)
                ?: return@withContext null
            val transcodings = JSONObject(trackJson).optJSONObject("media")?.optJSONArray("transcodings")
                ?: return@withContext null

            var progressiveUrl: String? = null
            for (i in 0 until transcodings.length()) {
                val t = transcodings.optJSONObject(i) ?: continue
                if (t.optJSONObject("format")?.optString("protocol") == "progressive") {
                    progressiveUrl = t.optString("url").takeIf { it.isNotBlank() }
                    break
                }
            }
            val url = progressiveUrl ?: return@withContext null

            val sep = if (url.contains("?")) "&" else "?"
            val resolved = apiGet("$url${sep}client_id=$cid", cid, alreadyHasClientId = true)
                ?: return@withContext null
            JSONObject(resolved).optString("url").takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    private fun clientId(): String? {
        cachedClientId?.let { return it }
        return resolveClientId()?.also { cachedClientId = it }
    }

    /** Scrapes a public client_id from soundcloud.com's bundled JS. */
    private fun resolveClientId(): String? {
        val html = httpGet("https://soundcloud.com/") ?: return null
        val scripts = Regex("<script[^>]+src=\"(https://a-v2\\.sndcdn\\.com/assets/[^\"]+\\.js)\"")
            .findAll(html).map { it.groupValues[1] }.toList().asReversed()
        for (scriptUrl in scripts) {
            val js = httpGet(scriptUrl) ?: continue
            val match = Regex("client_id[=:]\\\\?\"?([0-9a-zA-Z]{32})").find(js)
            if (match != null) return match.groupValues[1]
        }
        return null
    }

    /**
     * GETs a SoundCloud api-v2 URL, appending client_id unless [alreadyHasClientId].
     * On a 401 the cached client_id is refreshed and the request retried once.
     */
    private fun apiGet(url: String, cid: String, alreadyHasClientId: Boolean = false): String? {
        val firstUrl = if (alreadyHasClientId) url else "$url${if (url.contains("?")) "&" else "?"}client_id=$cid"
        client.newCall(Request.Builder().url(firstUrl).header("User-Agent", UA).build()).execute().use { response ->
            if (response.code != 401) {
                return if (response.isSuccessful) response.body?.string() else null
            }
        }
        // 401: refresh client_id and retry once.
        cachedClientId = null
        val fresh = resolveClientId() ?: return null
        cachedClientId = fresh
        val retryUrl = if (alreadyHasClientId) {
            Regex("client_id=[0-9a-zA-Z]+").replace(url, "client_id=$fresh")
        } else {
            "$url${if (url.contains("?")) "&" else "?"}client_id=$fresh"
        }
        client.newCall(Request.Builder().url(retryUrl).header("User-Agent", UA).build()).execute().use { response ->
            return if (response.isSuccessful) response.body?.string() else null
        }
    }

    private fun httpGet(url: String): String? = try {
        client.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use { response ->
            if (response.isSuccessful) response.body?.string() else null
        }
    } catch (e: Exception) {
        null
    }

    private fun decode(s: String): String =
        if (s.isBlank()) s else Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY).toString().trim()
}

/** Converts a [SoundCloudTrack] into the player's [MediaMetadata] model. */
fun SoundCloudTrack.toMediaMetadata(): MediaMetadata = MediaMetadata(
    id = SoundCloudApi.ID_PREFIX + id,
    title = title,
    artists = listOf(MediaMetadata.Artist(null, artist.ifBlank { "SoundCloud" })),
    duration = (durationMs / 1000).toInt(),
    thumbnailUrl = thumbnail,
)
