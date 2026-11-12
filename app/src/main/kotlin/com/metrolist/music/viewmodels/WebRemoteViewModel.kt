package com.metrolist.music.viewmodels

import androidx.lifecycle.ViewModel
import com.metrolist.music.webremote.WebRemoteServer
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class WebRemoteViewModel @Inject constructor(
    val webRemoteServer: WebRemoteServer,
) : ViewModel() {
    val isRunning: Boolean get() = webRemoteServer.isRunning
    val runningPort: Int get() = webRemoteServer.runningPort
    val mdnsHost: String get() = WebRemoteServer.MDNS_HOST

    fun localIp(): String? = WebRemoteServer.localIpAddress()
}
