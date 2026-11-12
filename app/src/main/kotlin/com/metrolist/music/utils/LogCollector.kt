package com.metrolist.music.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Process
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * Collects the app's own logcat output and shares it as a text file, for the
 * "shake to send logs" / manual "send logs" diagnostics feature.
 */
object LogCollector {
    private const val CACHE_DIR = "logs"
    private const val MAX_LINES = 2000
    private const val MAX_CHARS = 512 * 1024

    /** Reads this process' recent logcat, trimmed to the last [MAX_LINES] / [MAX_CHARS]. */
    fun readLogcat(): String {
        return try {
            val process = ProcessBuilder(
                "logcat", "-d", "-v", "threadtime", "--pid=${Process.myPid()}",
            ).redirectErrorStream(true).start()

            val lines = process.inputStream.bufferedReader().use { it.readLines() }
            process.waitFor()

            val report = lines.takeLast(MAX_LINES).joinToString("\n").takeLast(MAX_CHARS)
            report.ifBlank { "(no log entries captured)" }
        } catch (e: Exception) {
            Timber.e(e, "Failed to read logcat")
            "(failed to read logcat: ${e.message})"
        }
    }

    suspend fun collect(context: Context): String = withContext(Dispatchers.IO) {
        readLogcat()
    }

    private suspend fun writeToCache(context: Context, report: String): Uri =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, CACHE_DIR)
            dir.mkdirs()
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, "metrolist-log-${System.currentTimeMillis()}.txt")
            file.writeText(report)
            FileProvider.getUriForFile(context, "${context.packageName}.FileProvider", file)
        }

    /**
     * Collects logs, writes them to a cache file and launches a share-sheet.
     * @return true if the chooser was launched.
     */
    suspend fun share(context: Context, chooserTitle: String, subject: String): Boolean {
        return try {
            val report = collect(context)
            val uri = writeToCache(context, report)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, subject)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, chooserTitle))
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Failed to share diagnostic log")
            false
        }
    }
}
