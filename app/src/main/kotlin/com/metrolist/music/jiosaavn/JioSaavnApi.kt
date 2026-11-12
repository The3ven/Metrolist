package com.metrolist.music.jiosaavn

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
 * Minimal JioSaavn client used as an alternate audio source. Songs surfaced from
 * here carry an id prefixed with [ID_PREFIX]; playback resolves the stream lazily
 * via [getStreamUrl].
 */
object JioSaavnApi {
    const val ID_PREFIX = "JS::"

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.36"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun search(query: String): List<JioSaavnTrack> = withContext(Dispatchers.IO) {
        try {
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            val body = httpGet(
                "https://www.jiosaavn.com/api.php?__call=search.getResults&_format=json&n=20&p=1&_marker=0&api_version=4&ctx=android&q=$encoded",
            ) ?: return@withContext emptyList()
            if (body.isBlank()) return@withContext emptyList()
            val results = JSONObject(body).optJSONArray("results") ?: return@withContext emptyList()

            (0 until results.length()).mapNotNull { i ->
                val obj = results.optJSONObject(i) ?: return@mapNotNull null
                val id = obj.optString("id")
                if (id.isBlank()) return@mapNotNull null
                val moreInfo = obj.optJSONObject("more_info")

                val title = decode(obj.optString("title"))
                var artist = parseArtists(moreInfo)
                if (artist.isBlank()) artist = decode(obj.optString("subtitle"))
                val album = moreInfo?.optString("album")?.takeIf { it.isNotBlank() }?.let { decode(it) }
                val duration = moreInfo?.optString("duration")?.toIntOrNull() ?: 0
                val image = obj.optString("image").takeIf { it.isNotBlank() }
                    ?.replace("150x150", "500x500")
                val permaUrl = obj.optString("perma_url").takeIf { it.isNotBlank() }

                JioSaavnTrack(id, title, artist, album, duration, image, permaUrl)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun getStreamUrl(id: String): String? = withContext(Dispatchers.IO) {
        try {
            val details = httpGet(
                "https://www.jiosaavn.com/api.php?__call=song.getDetails&cc=in&_marker=0&_format=json&api_version=4&ctx=android&pids=${URLEncoder.encode(id, "UTF-8")}",
            ) ?: return@withContext null
            val encrypted = Regex("\"encrypted_media_url\"\\s*:\\s*\"(.*?)\"")
                .find(details)?.groupValues?.getOrNull(1)
                ?.replace("\\/", "/")
            if (encrypted.isNullOrBlank()) return@withContext null

            val authJson = httpGet(
                "https://www.jiosaavn.com/api.php?__call=song.generateAuthToken&url=${URLEncoder.encode(encrypted, "UTF-8")}&bitrate=320&api_version=4&_format=json&ctx=web6dot0&_marker=0",
            ) ?: return@withContext null
            val authUrl = JSONObject(authJson).optString("auth_url")
            if (authUrl.isBlank()) null else authUrl.replaceFirst("web", "aac")
        } catch (e: Exception) {
            null
        }
    }

    private fun httpGet(url: String): String? {
        val request = Request.Builder().url(url).header("User-Agent", UA).build()
        client.newCall(request).execute().use { response ->
            return if (response.isSuccessful) response.body?.string() else null
        }
    }

    private fun parseArtists(moreInfo: JSONObject?): String {
        val artists = moreInfo?.optJSONObject("artistMap")?.optJSONArray("primary_artists")
            ?: return ""
        return (0 until artists.length())
            .mapNotNull { i -> artists.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() } }
            .joinToString(", ") { decode(it) }
    }

    private fun decode(s: String): String =
        if (s.isBlank()) s else Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY).toString().trim()
}

/** Converts a [JioSaavnTrack] into the player's [MediaMetadata] model. */
fun JioSaavnTrack.toMediaMetadata(): MediaMetadata = MediaMetadata(
    id = JioSaavnApi.ID_PREFIX + id,
    title = title,
    artists = listOf(MediaMetadata.Artist(null, artist.ifBlank { "JioSaavn" })),
    duration = durationSec,
    thumbnailUrl = thumbnail,
    album = album?.let { MediaMetadata.Album("", it) },
)
