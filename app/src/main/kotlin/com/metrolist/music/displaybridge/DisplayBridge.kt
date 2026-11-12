package com.metrolist.music.displaybridge

import android.content.Context
import android.media.audiofx.Visualizer
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.metrolist.music.constants.EnableMiniPlayerDisplayKey
import com.metrolist.music.constants.MiniPlayerDisplayHostKey
import com.metrolist.music.extensions.metadata
import com.metrolist.music.extensions.toggleRepeatMode
import com.metrolist.music.playback.PlayerConnection
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import timber.log.Timber
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Bridges the player to an external "miniplayer display" device on the LAN.
 *
 * Finds a display over NSD (service name containing "metrolist") or connects to a
 * manual host, opens a WebSocket to `ws://host[:port]/app`, streams now-playing
 * state + an audio-visualizer spectrum, and applies remote commands (play, next,
 * seek, volume, …) sent back by the display.
 */
@Singleton
class DisplayBridge @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    enum class LinkState { Disabled, Searching, Connecting, Connected }

    data class Status(val state: LinkState = LinkState.Searching, val peer: String = "")

    @Serializable
    private data class NpPayload(
        val id: String,
        val ti: String,
        val ar: String,
        val al: String?,
        val du: Long,
        val po: Long,
        val pl: Boolean,
        val fa: Boolean,
        val vo: Int,
        val sh: Boolean,
        val rp: Int,
        val nx: String?,
        val sl: Int,
        val th: String?,
    )

    @Serializable
    private data class CmdMsg(
        val t: String? = null,
        val a: String? = null,
        val po: Long? = null,
        val m: Int? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val httpClient = OkHttpClient()
    private val nsdManager: NsdManager? by lazy {
        runCatching { context.getSystemService(Context.NSD_SERVICE) as? NsdManager }.getOrNull()
    }

    private var connection: PlayerConnection? = null
    @Volatile private var webSocket: WebSocket? = null
    private var viz: Visualizer? = null
    private var vizSession = 0
    @Volatile private var enabled = false
    private var manualHost = ""

    private val _status = MutableStateFlow(Status(LinkState.Disabled, ""))
    val status: StateFlow<Status> = _status.asStateFlow()

    private var observeJob: Job? = null
    private var stateJob: Job? = null
    private var heartbeatJob: Job? = null
    private var volumeJob: Job? = null
    private var reconnectJob: Job? = null
    private var discoveryWatchdogJob: Job? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    @Volatile private var resolving = false
    @Volatile private var resolveStartedAtMs = 0L
    @Volatile private var connected = false

    fun start() {
        if (observeJob != null) return
        observeJob = scope.launch {
            context.dataStore.data
                .map { prefs ->
                    (prefs[EnableMiniPlayerDisplayKey] ?: true) to (prefs[MiniPlayerDisplayHostKey] ?: "")
                }
                .distinctUntilChanged()
                .collect { (en, host) ->
                    enabled = en
                    manualHost = host.trim()
                    if (enabled) {
                        openConnection()
                    } else {
                        sendOffline()
                        delay(300) // let the "bye" frame flush before we close the socket
                        teardown()
                        _status.value = Status(LinkState.Disabled, "")
                    }
                }
        }
    }

    fun retry() {
        if (enabled) scope.launch { openConnection() }
    }

    fun setPlayerConnection(conn: PlayerConnection?) {
        connection = conn
        stateJob?.cancel()
        heartbeatJob?.cancel()
        volumeJob?.cancel()
        if (conn == null) {
            releaseVisualizer()
            return
        }
        // Push now-playing whenever the track or play/pause state changes.
        stateJob = scope.launch {
            combine(conn.mediaMetadata, conn.isPlaying, conn.currentSong) { _, _, _ -> }
                .collect {
                    ensureVisualizer()
                    pushNowPlaying()
                }
        }
        // Heartbeat keeps position fresh.
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_MS)
                pushNowPlaying()
            }
        }
        // Mirror local volume changes to the display.
        volumeJob = scope.launch {
            conn.service.playerVolume.collect { pushVolume(currentVolumePct()) }
        }
    }

    // --- connection -------------------------------------------------------------

    private fun openConnection() {
        teardown()
        val url = normalizeWsUrl(manualHost)
        if (url != null) {
            connectTo(url)
        } else {
            startDiscovery()
        }
    }

    /** Builds a ws:// URL from a user-entered host, or null if the host is blank/unusable. */
    private fun normalizeWsUrl(host: String): String? {
        val cleaned = host.trim().removePrefix("ws://").removePrefix("http://").removeSuffix("/")
        // Need at least a host[:port] before the /app path.
        val hostPart = cleaned.substringBefore(WS_PATH)
        if (hostPart.isBlank() || hostPart.startsWith(":")) return null
        return if (cleaned.contains(WS_PATH)) "ws://$cleaned" else "ws://$cleaned$WS_PATH"
    }

    private fun connectTo(url: String) {
        Timber.tag(TAG).i("connecting to %s", url)
        _status.value = Status(LinkState.Connecting, url)
        val request = runCatching {
            Request.Builder().url(url).header("User-Agent", context.packageName).build()
        }.getOrElse {
            Timber.tag(TAG).w(it, "invalid display address: %s", url)
            scheduleReconnect("invalid address")
            return
        }
        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected = true
                discoveryWatchdogJob?.cancel()
                _status.value = Status(LinkState.Connected, url)
                Timber.tag(TAG).i("connected")
                scope.launch {
                    ensureVisualizer()
                    pushNowPlaying()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = runCatching { json.decodeFromString(CmdMsg.serializer(), text) }.getOrNull() ?: return
                val action = msg.t ?: return
                scope.launch(Dispatchers.Main) { applyCommand(action, msg.po, msg.m) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                scheduleReconnect("closed: $reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                scheduleReconnect("failure: ${t.message}")
            }
        })
    }

    private fun scheduleReconnect(why: String) {
        connected = false
        webSocket = null
        if (!enabled) return
        if (reconnectJob?.isActive == true) return
        _status.value = Status(LinkState.Searching, "")
        Timber.tag(TAG).d("reconnect scheduled (%s)", why)
        reconnectJob = scope.launch {
            delay(RECONNECT_DELAY_MS)
            openConnection()
        }
    }

    private fun teardown() {
        reconnectJob?.cancel(); reconnectJob = null
        discoveryWatchdogJob?.cancel(); discoveryWatchdogJob = null
        stopDiscovery()
        runCatching { webSocket?.close(1000, "bridge teardown") }
        webSocket = null
        connected = false
    }

    // --- NSD discovery ----------------------------------------------------------

    private fun startDiscovery() {
        _status.value = Status(LinkState.Searching, "")
        registerDiscovery()
        discoveryWatchdogJob?.cancel()
        discoveryWatchdogJob = scope.launch {
            while (isActive) {
                delay(DISCOVERY_RETRY_MS)
                if (!connected) registerDiscovery()
            }
        }
    }

    private fun registerDiscovery() {
        val nsd = nsdManager ?: run { Timber.tag(TAG).w("NSD unavailable"); return }
        stopDiscovery()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Timber.tag(TAG).w("discovery start failed: %d", errorCode)
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                val name = info.serviceName
                if (!name.contains(SERVICE_NAME_MATCH, ignoreCase = true)) return
                // Ignore the in-app Web Remote server (advertises "Metrolist Remote" /
                // metrolist-remote.local): it's an HTTP control page, not a display WS host,
                // so connecting to it just loops on a failed handshake.
                if (name.contains(SERVICE_NAME_EXCLUDE, ignoreCase = true)) return
                val now = System.currentTimeMillis()
                if (resolving && now - resolveStartedAtMs < RESOLVE_TIMEOUT_MS) return
                resolving = true
                resolveStartedAtMs = now
                runCatching { nsd.resolveService(info, resolveListener()) }
                    .onFailure { resolving = false }
            }
        }
        discoveryListener = listener
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { Timber.tag(TAG).w(it, "discoverServices failed") }
    }

    private fun resolveListener() = object : NsdManager.ResolveListener {
        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
            resolving = false
            Timber.tag(TAG).w("resolve failed: %d", errorCode)
        }

        override fun onServiceResolved(info: NsdServiceInfo) {
            resolving = false
            val hostAddress = info.host?.hostAddress ?: return
            stopDiscovery()
            connectTo("ws://$hostAddress:${info.port}$WS_PATH")
        }
    }

    private fun stopDiscovery() {
        val nsd = nsdManager ?: return
        discoveryListener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discoveryListener = null
        resolving = false
    }

    // --- outbound payloads ------------------------------------------------------

    private fun pushNowPlaying() {
        val ws = webSocket ?: return
        val conn = connection ?: return
        val meta = conn.mediaMetadata.value ?: return

        val artists = meta.artists.joinToString(", ") { it.name }
        val duration = if (meta.duration > 0) meta.duration.toLong() * 1000 else 0L
        val position = runCatching { conn.player.currentPosition }.getOrDefault(0L)
        val playing = conn.isPlaying.value
        val liked = conn.currentSong.value?.song?.liked ?: false
        val shuffle = runCatching { conn.player.shuffleModeEnabled }.getOrDefault(false)
        val repeat = runCatching { conn.player.repeatMode }.getOrDefault(0)
        val next = runCatching { nextTrackTitle() }.getOrNull()

        val payload = NpPayload(
            id = meta.id,
            ti = meta.title,
            ar = artists,
            al = meta.album?.title,
            du = duration,
            po = position,
            pl = playing,
            fa = liked,
            vo = currentVolumePct(),
            sh = shuffle,
            rp = repeat,
            nx = next,
            sl = sleepRemainingSecs(conn),
            th = meta.thumbnailUrl,
        )
        runCatching { ws.send(json.encodeToString(NpPayload.serializer(), payload)) }
            .onFailure { Timber.tag(TAG).w(it, "send failed") }
    }

    private fun pushViz(fft: ByteArray) {
        val ws = webSocket ?: return
        val n = fft.size / 2
        if (n < 2) return
        val sb = StringBuilder("{\"t\":\"viz\",\"b\":[")
        for (band in 0 until BANDS) {
            val start = (n - 1) * band / BANDS + 1
            val end = (band + 1) * (n - 1) / BANDS + 1
            var peak = 0f
            var i = start
            while (i < end && i < n) {
                val re = fft[i * 2].toFloat()
                val im = fft[i * 2 + 1].toFloat()
                val mag = sqrt(re * re + im * im)
                if (mag > peak) peak = mag
                i++
            }
            val db = if (peak > 0f) log10(peak) * 20f else 0f
            val value = ((db / 45f) * 100f).toInt().coerceIn(0, 100)
            if (band > 0) sb.append(',')
            sb.append(value)
        }
        sb.append("]}")
        runCatching { ws.send(sb.toString()) }
    }

    private fun pushVolume(pct: Int) {
        val ws = webSocket ?: return
        runCatching { ws.send("{\"t\":\"vol\",\"vo\":$pct}") }
            .onFailure { Timber.tag(TAG).w(it, "volume send failed") }
    }

    /** Tells the display we are going offline so it can clear its screen, before closing. */
    private fun sendOffline() {
        val ws = webSocket ?: run { Timber.tag(TAG).i("sendOffline: no socket, nothing to notify"); return }
        val ok = runCatching { ws.send("{\"t\":\"bye\"}") }.getOrDefault(false)
        Timber.tag(TAG).i("sendOffline: bye sent=%s", ok)
    }

    // --- command handling -------------------------------------------------------

    private fun applyCommand(action: String, po: Long?, m: Int?) {
        val conn = connection ?: return
        when (action) {
            "playpause" -> if (conn.isPlaying.value) conn.pause() else conn.play()
            "repeat" -> { conn.player.toggleRepeatMode(); pushNowPlaying() }
            "fav" -> conn.toggleLike()
            "next" -> conn.seekToNext()
            "prev" -> conn.seekToPrevious()
            "seek" -> po?.let { conn.seekTo(it); pushNowPlaying() }
            "sleep" -> {
                val value = m ?: 0
                val sleepTimer = conn.service.sleepTimer
                if (value > 0) sleepTimer?.start(value) else sleepTimer?.clear()
                pushNowPlaying()
            }
            "voldn" -> stepVolume(-VOLUME_STEP)
            "volup" -> stepVolume(VOLUME_STEP)
            "shuffle" -> {
                conn.player.shuffleModeEnabled = !conn.player.shuffleModeEnabled
                pushNowPlaying()
            }
            else -> Timber.tag(TAG).w("unknown command: %s", action)
        }
    }

    private fun stepVolume(delta: Float) {
        val volume = connection?.service?.playerVolume ?: return
        volume.value = (volume.value + delta).coerceIn(0f, 1f)
    }

    // --- helpers ----------------------------------------------------------------

    private fun currentVolumePct(): Int {
        val volume = connection?.service?.playerVolume?.value ?: return 100
        return (volume * 100f).roundToInt().coerceIn(0, 100)
    }

    private fun nextTrackTitle(): String? {
        val player = connection?.player ?: return null
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return null
        val nextIndex = timeline.getNextWindowIndex(
            player.currentMediaItemIndex, player.repeatMode, player.shuffleModeEnabled,
        )
        if (nextIndex == -1) return null
        return player.getMediaItemAt(nextIndex).metadata?.title
    }

    private fun sleepRemainingSecs(conn: PlayerConnection): Int {
        val triggerTime = runCatching { conn.service.sleepTimer?.triggerTime ?: -1L }.getOrDefault(-1L)
        if (triggerTime <= 0) return 0
        return ((triggerTime - System.currentTimeMillis()) / 1000).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }

    private fun ensureVisualizer() {
        val conn = connection ?: return
        val session = runCatching { conn.player.audioSessionId }.getOrDefault(0)
        if (session == 0) return
        if (viz != null && session == vizSession) return
        releaseVisualizer()
        runCatching {
            val visualizer = Visualizer(session).apply {
                captureSize = Visualizer.getCaptureSizeRange()[0]
                setDataCaptureListener(
                    object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(v: Visualizer, waveform: ByteArray, rate: Int) {}
                        override fun onFftDataCapture(v: Visualizer, fft: ByteArray, rate: Int) {
                            pushViz(fft)
                        }
                    },
                    minOf(Visualizer.getMaxCaptureRate(), VIZ_MAX_RATE), false, true,
                )
                enabled = true
            }
            viz = visualizer
            vizSession = session
            Timber.tag(TAG).i("Visualizer attached on session %d", session)
        }.onFailure { Timber.tag(TAG).w(it, "Visualizer unavailable (mic permission?)") }
    }

    private fun releaseVisualizer() {
        runCatching {
            viz?.enabled = false
            viz?.release()
        }
        viz = null
        vizSession = -1
    }

    companion object {
        private const val TAG = "DisplayBridge"
        private const val SERVICE_TYPE = "_http._tcp."
        private const val SERVICE_NAME_MATCH = "metrolist"
        private const val SERVICE_NAME_EXCLUDE = "remote"
        private const val WS_PATH = "/app"
        private const val BANDS = 20
        private const val VIZ_MAX_RATE = 20000
        private const val VOLUME_STEP = 0.1f
        private const val HEARTBEAT_MS = 5000L
        private const val RECONNECT_DELAY_MS = 4000L
        private const val DISCOVERY_RETRY_MS = 12000L
        private const val RESOLVE_TIMEOUT_MS = 8000L
    }
}
