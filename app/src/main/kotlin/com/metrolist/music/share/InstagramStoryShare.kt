package com.metrolist.music.share

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * Hands a rendered [StoryPoster.Assets] off to the Instagram "add to story" flow:
 * the background is shared as the story image and the card as an interactive sticker.
 */
object InstagramStoryShare {
    private const val ADD_TO_STORY_ACTION = "com.instagram.share.ADD_TO_STORY"
    private const val INSTAGRAM_PACKAGE = "com.instagram.android"
    private const val CACHE_DIR = "story_share"

    enum class Result {
        HANDED_OFF,
        INSTAGRAM_NOT_INSTALLED,
        MISSING_APP_ID,
        FAILED,
    }

    fun hasAppId(appId: String): Boolean = appId.isNotBlank()

    fun isInstagramAvailable(context: Context): Boolean = try {
        val intent = Intent(ADD_TO_STORY_ACTION).setType("image/jpeg")
        context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null
    } catch (e: Exception) {
        false
    }

    suspend fun share(context: Context, assets: StoryPoster.Assets, appId: String): Result {
        if (!hasAppId(appId)) return Result.MISSING_APP_ID
        if (!isInstagramAvailable(context)) return Result.INSTAGRAM_NOT_INSTALLED
        return try {
            val stickerUri = cacheAsUri(context, assets.sticker, "story_sticker", png = true)
            val backgroundUri = cacheAsUri(context, assets.background, "story_bg", png = false)
            val intent = Intent(ADD_TO_STORY_ACTION).apply {
                putExtra("source_application", appId)
                setDataAndType(backgroundUri, "image/jpeg")
                putExtra("interactive_asset_uri", stickerUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.grantUriPermission(INSTAGRAM_PACKAGE, stickerUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.grantUriPermission(INSTAGRAM_PACKAGE, backgroundUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(intent)
            Result.HANDED_OFF
        } catch (e: ActivityNotFoundException) {
            Timber.w(e, "Instagram resolved but would not start")
            Result.INSTAGRAM_NOT_INSTALLED
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Failed to hand off story poster to Instagram")
            Result.FAILED
        }
    }

    /**
     * Fallback when the direct Instagram handoff isn't available: flattens the poster
     * into one image and shares it through the system chooser (optionally with [text]).
     */
    suspend fun shareViaChooser(
        context: Context,
        assets: StoryPoster.Assets,
        chooserTitle: String,
        text: String?,
    ): Result {
        return try {
            val poster = StoryPoster.flatten(assets)
            val uri = cacheAsUri(context, poster, "story_poster", png = false)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, uri)
                if (!text.isNullOrBlank()) putExtra(Intent.EXTRA_TEXT, text)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, chooserTitle))
            Result.HANDED_OFF
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Failed to share story poster via chooser")
            Result.FAILED
        }
    }

    private suspend fun cacheAsUri(
        context: Context,
        bitmap: Bitmap,
        name: String,
        png: Boolean,
    ): Uri = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, CACHE_DIR)
        dir.mkdirs()
        val ext = if (png) ".png" else ".jpg"
        val file = File(dir, "${name}_${System.currentTimeMillis()}$ext")
        file.outputStream().use { out ->
            if (png) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            } else {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 98, out)
            }
        }
        FileProvider.getUriForFile(context, "${context.packageName}.FileProvider", file)
    }

    /** Deletes story-share cache files older than 10 minutes. */
    suspend fun clearCache(context: Context) = withContext(Dispatchers.IO) {
        try {
            val cutoff = System.currentTimeMillis() - 600_000
            File(context.cacheDir, CACHE_DIR).listFiles()
                ?.filter { it.lastModified() < cutoff }
                ?.forEach { it.delete() }
            Unit
        } catch (e: Exception) {
            Timber.w(e, "Failed to prune story share cache")
            Unit
        }
    }
}
