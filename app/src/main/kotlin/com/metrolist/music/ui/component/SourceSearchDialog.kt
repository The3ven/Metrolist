package com.metrolist.music.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil3.compose.AsyncImage
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.audius.AudiusApi
import com.metrolist.music.audius.toMediaMetadata
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.jiosaavn.JioSaavnApi
import com.metrolist.music.jiosaavn.toMediaMetadata
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.playback.queues.ListQueue
import com.metrolist.music.soundcloud.SoundCloudApi
import com.metrolist.music.soundcloud.toMediaMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dedicated search dialog for the alternate audio sources (JioSaavn, SoundCloud,
 * Audius). Searches all three in parallel, merges the results, and plays the
 * tapped track (queueing the rest).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceSearchDialog(onDismiss: () -> Unit) {
    val playerConnection = LocalPlayerConnection.current
    val coroutineScope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<MediaMetadata>>(emptyList()) }

    fun runSearch() {
        val q = query.trim()
        if (q.isBlank()) return
        loading = true
        coroutineScope.launch {
            results = withContext(Dispatchers.IO) {
                val js = async { runCatching { JioSaavnApi.search(q).map { it.toMediaMetadata() } }.getOrDefault(emptyList()) }
                val sc = async { runCatching { SoundCloudApi.search(q).map { it.toMediaMetadata() } }.getOrDefault(emptyList()) }
                val au = async { runCatching { AudiusApi.search(q).map { it.toMediaMetadata() } }.getOrDefault(emptyList()) }
                js.await() + sc.await() + au.await()
            }
            loading = false
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = stringResource(R.string.jiosaavn_search),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 12.dp),
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text(stringResource(R.string.search)) },
                    )
                    IconButton(onClick = { runSearch() }) {
                        Icon(painterResource(R.drawable.search), contentDescription = null)
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 360.dp)
                        .padding(top = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        loading -> CircularProgressIndicator()
                        results.isEmpty() -> Text(
                            text = stringResource(R.string.no_results_found),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        else -> LazyColumn(modifier = Modifier.fillMaxWidth()) {
                            itemsIndexed(results) { index, track ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    AsyncImage(
                                        model = track.thumbnailUrl,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(44.dp)
                                            .padding(end = 10.dp),
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = track.title,
                                            style = MaterialTheme.typography.bodyLarge,
                                            maxLines = 1,
                                        )
                                        Text(
                                            text = track.artists.joinToString(", ") { it.name },
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1,
                                        )
                                    }
                                    IconButton(onClick = {
                                        playerConnection?.playQueue(
                                            ListQueue(
                                                title = "Sources",
                                                items = results.map { it.toMediaItem() },
                                                startIndex = index,
                                            ),
                                        )
                                        onDismiss()
                                    }) {
                                        Icon(painterResource(R.drawable.play), contentDescription = null)
                                    }
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(android.R.string.ok))
                    }
                }
            }
        }
    }
}
