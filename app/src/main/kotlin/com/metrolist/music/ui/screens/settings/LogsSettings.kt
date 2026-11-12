package com.metrolist.music.ui.screens.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.constants.ShakeToSendLogsKey
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.LogCollector
import com.metrolist.music.utils.ShakeDetector
import com.metrolist.music.utils.rememberPreference
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsSettings(
    navController: NavController,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val (shakeToSendLogs, onShakeToSendLogsChange) = rememberPreference(
        key = ShakeToSendLogsKey,
        defaultValue = false,
    )
    val shakeSupported = remember { ShakeDetector {}.isSupported(context) }

    var showPrompt by remember { mutableStateOf(false) }

    val chooserTitle = stringResource(R.string.send_logs)
    val subject = stringResource(R.string.send_logs_subject)

    if (showPrompt) {
        AlertDialog(
            onDismissRequest = { showPrompt = false },
            title = { Text(stringResource(R.string.send_logs_now)) },
            text = { Text(stringResource(R.string.send_logs_prompt)) },
            confirmButton = {
                TextButton(onClick = {
                    showPrompt = false
                    coroutineScope.launch {
                        LogCollector.share(context, chooserTitle, subject)
                    }
                }) {
                    Text(stringResource(R.string.send_logs))
                }
            },
            dismissButton = {
                TextButton(onClick = { showPrompt = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    androidx.compose.foundation.layout.Column(
        Modifier
            .windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top),
            )
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(
            Modifier.windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top),
            ),
        )

        Material3SettingsGroup(
            title = stringResource(R.string.logs),
            items = listOf(
                Material3SettingsItem(
                    icon = painterResource(R.drawable.bug_report),
                    title = { Text(stringResource(R.string.send_logs_now)) },
                    description = { Text(stringResource(R.string.send_logs_now_desc)) },
                    onClick = { showPrompt = true },
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.bug_report),
                    title = { Text(stringResource(R.string.shake_to_send_logs)) },
                    description = {
                        Text(
                            if (shakeSupported) {
                                stringResource(R.string.shake_to_send_logs_desc)
                            } else {
                                stringResource(R.string.shake_to_send_logs_unsupported)
                            },
                        )
                    },
                    enabled = shakeSupported,
                    trailingContent = {
                        Switch(
                            enabled = shakeSupported,
                            checked = shakeToSendLogs,
                            onCheckedChange = onShakeToSendLogsChange,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (shakeToSendLogs) R.drawable.check else R.drawable.close,
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize),
                                )
                            },
                        )
                    },
                ),
            ),
        )
    }

    TopAppBar(
        title = { Text(stringResource(R.string.logs)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(
                    painterResource(R.drawable.arrow_back),
                    contentDescription = null,
                )
            }
        },
    )
}
