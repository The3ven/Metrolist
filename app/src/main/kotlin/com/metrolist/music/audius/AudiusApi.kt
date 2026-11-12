package com.metrolist.music.audius

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
 * Minimal Audius client used as an alternate audio source. Tracks carry an id
 * prefixed with [ID_PREFIX]; [streamUrl] builds a direct, public stream URL.
 */
object AudiusApi {
    const val ID_PREFIX = "AU::"

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.36"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun search(query: String): List<AudiusTrack> = withContext(Dispatchers.IO) {
        try {
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            val body = httpGet("https://api.audius.co/v1/tracks/search?query=$encoded&app_name=Metrolist")
                ?: return@withContext emptyList()
            val data = JSONObject(body).optJSONArray("data") ?: return@withContext emptyList()

            (0 until data.length()).mapNotNull { i ->
                val obj = data.optJSONObject(i) ?: return@mapNotNull null
                val id = obj.optString("id")
                if (id.isBlank()) return@mapNotNull null
                val artwork = obj.optJSONObject("artwork")
                val title = decode(obj.optString("title"))
                val artist = decode(obj.optJSONObject("user")?.optString("name").orEmpty())
                val duration = obj.optInt("duration", 0)
                val thumbnail = artwork?.let {
                    it.optString("480x480").takeIf { s -> s.isNotBlank() }
                        ?: it.optString("1000x1000").takeIf { s -> s.isNotBlank() }
                        ?: it.optString("150x150").takeIf { s -> s.isNotBlank() }
                }
                val permalink = obj.optString("permalink").takeIf { it.isNotBlank() }
                AudiusTrack(id, title, artist, duration, thumbnail, permalink?.let { "https://audius.co$it" })
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun streamUrl(id: String): String =
        "https://api.audius.co/v1/tracks/${URLEncoder.encode(id, "UTF-8")}/stream?app_name=Metrolist"

    private fun httpGet(url: String): String? {
        val request = Request.Builder().url(url).header("User-Agent", UA).build()
        client.newCall(request).execute().use { response ->
            return if (response.isSuccessful) response.body?.string() else null
        }
    }

    private fun decode(s: String): String =
        if (s.isBlank()) s else Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY).toString().trim()
}

/** Converts an [AudiusTrack] into the player's [MediaMetadata] model. */
fun AudiusTrack.toMediaMetadata(): MediaMetadata = MediaMetadata(
    id = AudiusApi.ID_PREFIX + id,
    title = title,
    artists = listOf(MediaMetadata.Artist(null, artist.ifBlank { "Audius" })),
    duration = durationSec,
    thumbnailUrl = thumbnail,
)
