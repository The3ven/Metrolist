package com.metrolist.music.ui.screens.settings.integrations

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.constants.EnableWebRemoteKey
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.rememberPreference
import com.metrolist.music.viewmodels.WebRemoteViewModel
import com.metrolist.music.webremote.WebRemoteServer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebRemoteSettings(
    navController: NavController,
    viewModel: WebRemoteViewModel = hiltViewModel(),
) {
    var enabled by rememberPreference(EnableWebRemoteKey, defaultValue = false)

    val ip = viewModel.localIp()
    val port = viewModel.runningPort.takeIf { it != 0 } ?: WebRemoteServer.DEFAULT_PORT
    val urlText = when {
        !enabled -> stringResource(R.string.web_remote_off)
        ip == null -> stringResource(R.string.web_remote_no_wifi)
        else -> "http://$ip:$port"
    }

    Column(
        Modifier
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(
            Modifier.windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top)),
        )

        Material3SettingsGroup(
            title = stringResource(R.string.web_remote),
            items = listOf(
                Material3SettingsItem(
                    icon = painterResource(R.drawable.cast),
                    title = { Text(stringResource(R.string.enable_web_remote)) },
                    description = { Text(stringResource(R.string.enable_web_remote_desc)) },
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
                    icon = painterResource(R.drawable.link),
                    title = { Text(stringResource(R.string.web_remote_url)) },
                    description = { Text(urlText) },
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.link),
                    title = { Text(stringResource(R.string.web_remote_mdns)) },
                    description = {
                        Text(if (enabled) "http://${viewModel.mdnsHost}:$port" else stringResource(R.string.web_remote_off))
                    },
                ),
            ),
        )
    }

    TopAppBar(
        title = { Text(stringResource(R.string.web_remote)) },
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
