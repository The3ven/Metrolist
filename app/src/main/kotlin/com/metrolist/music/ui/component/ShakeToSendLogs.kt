package com.metrolist.music.ui.component

import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.metrolist.music.R
import com.metrolist.music.constants.ShakeToSendLogsKey
import com.metrolist.music.utils.LogCollector
import com.metrolist.music.utils.ShakeDetector
import com.metrolist.music.utils.rememberPreference
import kotlinx.coroutines.launch

/**
 * Host composable that listens for a shake gesture (while enabled and resumed) and,
 * on shake, offers to collect and share a diagnostic log.
 *
 * Call once from the app's root content.
 */
@Composable
fun ShakeToSendLogs() {
    val (enabled) = rememberPreference(ShakeToSendLogsKey, defaultValue = false)
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    var showDialog by remember { mutableStateOf(false) }
    val shakeDetector = remember { ShakeDetector { showDialog = true } }

    DisposableEffect(enabled, lifecycleOwner) {
        if (!enabled) {
            shakeDetector.stop()
            onDispose { }
        } else {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> shakeDetector.start(context)
                    Lifecycle.Event.ON_PAUSE -> shakeDetector.stop()
                    else -> {}
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                shakeDetector.start(context)
            }
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
                shakeDetector.stop()
            }
        }
    }

    if (showDialog) {
        val chooserTitle = stringResource(R.string.send_logs)
        val subject = stringResource(R.string.send_logs_subject)
        val failed = stringResource(R.string.send_logs_failed)
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(stringResource(R.string.send_logs)) },
            text = { Text(stringResource(R.string.send_logs_prompt)) },
            confirmButton = {
                TextButton(onClick = {
                    showDialog = false
                    coroutineScope.launch {
                        val ok = LogCollector.share(context, chooserTitle, subject)
                        if (!ok) {
                            Toast.makeText(context, failed, Toast.LENGTH_LONG).show()
                        }
                    }
                }) {
                    Text(stringResource(R.string.send_logs))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}
