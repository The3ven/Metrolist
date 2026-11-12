package com.metrolist.music.ui.component

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.metrolist.music.R
import com.metrolist.music.share.InstagramStoryShare
import com.metrolist.music.share.StoryBackgroundStyle
import com.metrolist.music.share.StoryFont
import com.metrolist.music.share.StoryPoster
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lets the user compose a song poster (background style, font, optional link) and
 * share it to an Instagram story, with a generic share-sheet fallback.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareToStoryDialog(
    title: String,
    artist: String,
    coverArtUrl: String?,
    linkUrl: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var style by remember { mutableStateOf(StoryBackgroundStyle.GRADIENT) }
    var font by remember { mutableStateOf(StoryFont.APP) }
    var addLink by remember { mutableStateOf(true) }

    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var rendering by remember { mutableStateOf(true) }
    var sharing by remember { mutableStateOf(false) }

    val shareFailed = stringResource(R.string.story_share_failed)
    val renderFailed = stringResource(R.string.story_failed)
    val chooserTitle = stringResource(R.string.share_to_story)

    suspend fun renderAssets(): StoryPoster.Assets =
        StoryPoster.render(
            context = context,
            coverArtUrl = coverArtUrl,
            title = title,
            artist = artist,
            linkUrl = if (addLink) linkUrl else "",
            style = style,
            font = font,
        )

    // Live preview: re-render whenever the spec changes.
    androidx.compose.runtime.LaunchedEffect(style, font, addLink) {
        rendering = true
        preview = try {
            val assets = renderAssets()
            StoryPoster.flatten(assets)
        } catch (e: Exception) {
            null
        }
        rendering = false
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = AlertDialogDefaults.containerColor,
            tonalElevation = AlertDialogDefaults.TonalElevation,
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = stringResource(R.string.share_to_instagram_story),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 16.dp),
                )

                // Preview
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.6f)
                        .aspectRatio(9f / 16f)
                        .align(Alignment.CenterHorizontally),
                    contentAlignment = Alignment.Center,
                ) {
                    val bitmap = preview
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (rendering) {
                        CircularProgressIndicator()
                    }
                }

                Text(
                    text = stringResource(R.string.story_preparing).takeIf { rendering } ?: "",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 4.dp),
                )

                // Background style
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = style == StoryBackgroundStyle.GRADIENT,
                        onClick = { style = StoryBackgroundStyle.GRADIENT },
                        label = { Text(stringResource(R.string.story_background_gradient)) },
                    )
                    FilterChip(
                        selected = style == StoryBackgroundStyle.BLURRED_ART,
                        onClick = { style = StoryBackgroundStyle.BLURRED_ART },
                        label = { Text(stringResource(R.string.story_background_blurred)) },
                    )
                }

                // Font
                Text(
                    text = stringResource(R.string.story_font),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StoryFont.entries.forEach { option ->
                        FilterChip(
                            selected = font == option,
                            onClick = { font = option },
                            label = { Text(stringResource(option.labelRes)) },
                        )
                    }
                }

                // Add link
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.story_add_link_hint),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = addLink,
                        onCheckedChange = { addLink = it },
                    )
                }

                // Actions
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss, enabled = !sharing) {
                        Text(stringResource(android.R.string.cancel))
                    }
                    TextButton(
                        enabled = !sharing && !rendering,
                        onClick = {
                            sharing = true
                            coroutineScope.launch {
                                val result = try {
                                    val assets = renderAssets()
                                    // appId is empty in this build; falls through to the chooser.
                                    val direct = InstagramStoryShare.share(context, assets, "")
                                    if (direct == InstagramStoryShare.Result.HANDED_OFF) {
                                        direct
                                    } else {
                                        InstagramStoryShare.shareViaChooser(
                                            context = context,
                                            assets = assets,
                                            chooserTitle = chooserTitle,
                                            text = if (addLink) linkUrl else null,
                                        )
                                    }
                                } catch (e: Exception) {
                                    InstagramStoryShare.Result.FAILED
                                }
                                sharing = false
                                if (result == InstagramStoryShare.Result.FAILED) {
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(context, shareFailed, Toast.LENGTH_LONG).show()
                                    }
                                } else {
                                    onDismiss()
                                }
                            }
                        },
                    ) {
                        Text(stringResource(R.string.share))
                    }
                }
            }
        }
    }
}
