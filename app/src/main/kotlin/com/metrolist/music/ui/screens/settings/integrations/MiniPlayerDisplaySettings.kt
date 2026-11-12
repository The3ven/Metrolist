package com.metrolist.music.ui.screens.settings.integrations

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.constants.EnableMiniPlayerDisplayKey
import com.metrolist.music.constants.MiniPlayerDisplayHostKey
import com.metrolist.music.displaybridge.DisplayBridge
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.rememberPreference
import com.metrolist.music.viewmodels.MiniPlayerDisplayViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MiniPlayerDisplaySettings(
    navController: NavController,
    viewModel: MiniPlayerDisplayViewModel = hiltViewModel(),
) {
    var enabled by rememberPreference(EnableMiniPlayerDisplayKey, defaultValue = true)
    var host by rememberPreference(MiniPlayerDisplayHostKey, defaultValue = "")
    val status by viewModel.status.collectAsStateWithLifecycle()

    val statusText = when (status.state) {
        DisplayBridge.LinkState.Disabled -> stringResource(R.string.miniplayer_display_off)
        DisplayBridge.LinkState.Searching -> stringResource(R.string.miniplayer_display_searching)
        DisplayBridge.LinkState.Connecting -> stringResource(R.string.miniplayer_display_connecting, status.peer)
        DisplayBridge.LinkState.Connected -> stringResource(R.string.miniplayer_display_connected, status.peer)
    }

    Column(
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
            title = stringResource(R.string.miniplayer_display),
            items = listOf(
                Material3SettingsItem(
                    icon = painterResource(R.drawable.cast),
                    title = { Text(stringResource(R.string.enable_miniplayer_display)) },
                    description = { Text(stringResource(R.string.enable_miniplayer_display_desc)) },
                    trailingContent = {
                        Switch(
                            checked = enabled,
                            onCheckedChange = { enabled = it },
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (enabled) R.drawable.check else R.drawable.close,
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize),
                                )
                            },
                        )
                    },
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.cast),
                    title = { Text(stringResource(R.string.miniplayer_display_status)) },
                    description = { Text(statusText) },
                    onClick = {
                        if (status.state == DisplayBridge.LinkState.Searching) viewModel.retry()
                    },
                ),
            ),
        )

        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            singleLine = true,
            label = { Text(stringResource(R.string.miniplayer_display_host)) },
            placeholder = { Text(stringResource(R.string.miniplayer_display_host_placeholder)) },
            supportingText = { Text(stringResource(R.string.miniplayer_display_host_auto)) },
        )
    }

    TopAppBar(
        title = { Text(stringResource(R.string.miniplayer_display)) },
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
