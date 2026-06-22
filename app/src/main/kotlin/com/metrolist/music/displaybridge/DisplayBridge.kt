/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.displaybridge

import android.content.Context
import android.media.audiofx.Visualizer
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.metrolist.music.constants.EnableMiniPlayerDisplayKey
import com.metrolist.music.constants.MiniPlayerDisplayHostKey
import com.metrolist.music.playback.PlayerConnection
import com.metrolist.music.utils.dataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import timber.log.Timber
import java.util.concurrent.TimeUnit
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Bridges Metrolist's player to an external "mini-player" display (e.g. an
 * ESP32 desk gadget) over a WebSocket.
 *
 * Topology: the **display is the server**, this class is a **client** that
 * connects out — the right shape for Android, where the app is the transient,
 * power-managed, dynamically-addressed peer. We:
 *   - push a compact now-playing snapshot whenever the track or play-state
 *     changes (and a light 5 s heartbeat while playing, to absorb seeks);
 *   - apply transport commands (next/prev/play-pause/volume) sent back by the
 *     display over the same socket.
 *
 * The display is found via mDNS (service type `_http._tcp`, instance name
 * "metrolist"), so no IP needs to be configured; a manual host can be set to
 * override discovery.
 *
 * Wire format matches the ESP firmware (see its README): outgoing
 * `{"id","ti","ar","al","du","po","pl","th"}`, incoming `{"t":"cmd","a":...}`.
 */
class DisplayBridge(
    private val context: Context,
) {
    companion object {
        private const val TAG = "DisplayBridge"
        private const val WS_PATH = "/app"
        private const val SERVICE_TYPE = "_http._tcp."
        private const val SERVICE_NAME_MATCH = "metrolist"
        private const val RECONNECT_DELAY_MS = 4000L
        private const val HEARTBEAT_MS = 5000L
        private const val VOLUME_STEP = 0.1f
        private const val BANDS = 20          // visualizer columns; must match ESP NUM_BANDS
        private const val VIZ_MAX_RATE = 20000 // capture rate cap in milliHz (~20 Hz)
    }

    /** Compact now-playing payload. Field names are the on-wire JSON keys. */
    @Serializable
    private data class NpPayload(
        val id: String,
        val ti: String,
        val ar: String,
        val al: String? = null,
        val du: Long,
        val po: Long,
        val pl: Boolean,
        val th: String? = null,
    )

    /** Inbound control message from the display. */
    @Serializable
    private data class CmdMsg(
        val t: String? = null,
        val a: String? = null,
    )

    // Player operations and ExoPlayer reads must happen on the main thread.
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val httpClient =
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS) // keep the socket alive; ESP auto-pongs
            .build()

    private val nsdManager: NsdManager? by lazy {
        runCatching { context.getSystemService(Context.NSD_SERVICE) as NsdManager }.getOrNull()
    }

    private var connection: PlayerConnection? = null
    private var webSocket: WebSocket? = null
    private var viz: Visualizer? = null   ///< FFT capture on the player's audio session
    private var vizSession = -1           ///< Audio session id the Visualizer is bound to

    private var enabled = true
    private var manualHost = ""

    private var observeJob: Job? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    @Volatile private var resolving = false
    @Volatile private var connected = false

    /**
     * Begin observing settings and (if enabled) connecting to the display.
     * Safe to call once at app startup; repeated calls are ignored.
     */
    fun start() {
        if (observeJob != null) return
        observeJob =
            scope.launch {
                context.dataStore.data
                    .map { prefs ->
                        (prefs[EnableMiniPlayerDisplayKey] ?: true) to (prefs[MiniPlayerDisplayHostKey] ?: "")
                    }.distinctUntilChanged()
                    .collectLatest { (en, host) ->
                        enabled = en
                        manualHost = host.trim()
                        if (enabled) openConnection() else teardown()
                    }
            }
    }

    /**
     * Attach (or detach) the player. Called by MainActivity when the music
     * service binds/unbinds.
     * @param conn The live player connection, or null on unbind.
     */
    fun setPlayerConnection(conn: PlayerConnection?) {
        connection = conn
        heartbeatJob?.cancel()
        if (conn == null) {
            releaseVisualizer()
            return
        }

        // Push whenever the track or play/pause state changes. PlayerConnection
        // keeps these flows current even when the underlying ExoPlayer is swapped
        // (e.g. crossfade), so we never re-register a raw listener.
        scope.launch {
            combine(conn.mediaMetadata, conn.isPlaying) { meta, playing -> meta?.id to playing }
                .distinctUntilChanged()
                .collectLatest {
                    pushNowPlaying()
                    ensureVisualizer()  // (re)attach on track/session change
                }
        }

        // Light heartbeat: while playing, refresh ~every 5 s so seeks/drift are
        // corrected on the display, and (re)attach the visualizer if needed.
        heartbeatJob =
            scope.launch {
                while (true) {
                    delay(HEARTBEAT_MS)
                    if (connection?.isPlaying?.value == true) {
                        pushNowPlaying()
                        ensureVisualizer()
                    }
                }
            }
    }

    // ----------------------------------------------------------------------
    //  Audio-reactive visualizer (real FFT from the playback audio session)
    // ----------------------------------------------------------------------

    /**
     * Attach a [Visualizer] to the player's current audio session (idempotent).
     * Streams FFT-derived band levels to the display. Requires RECORD_AUDIO;
     * on failure the ESP falls back to its built-in simulated animation.
     */
    private fun ensureVisualizer() {
        val conn = connection ?: return
        val sid = runCatching { conn.player.audioSessionId }.getOrDefault(0)
        if (sid == 0) return                  // audio renderer not ready yet
        if (viz != null && sid == vizSession) return
        releaseVisualizer()
        try {
            val v = Visualizer(sid)
            v.captureSize = Visualizer.getCaptureSizeRange()[0]  // smallest = fastest
            val rate = minOf(Visualizer.getMaxCaptureRate(), VIZ_MAX_RATE)
            v.setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(vv: Visualizer?, wf: ByteArray?, sr: Int) {}
                    override fun onFftDataCapture(vv: Visualizer?, fft: ByteArray?, sr: Int) {
                        if (fft != null) pushViz(fft)
                    }
                },
                rate, false, true, // captureWaveform = false, captureFft = true
            )
            v.enabled = true
            viz = v
            vizSession = sid
            Timber.tag(TAG).i("Visualizer attached on session $sid")
        } catch (e: Throwable) {
            Timber.tag(TAG).w(e, "Visualizer unavailable (mic permission?) - using simulated bars")
        }
    }

    /** Disable and release the Visualizer. */
    private fun releaseVisualizer() {
        runCatching {
            viz?.enabled = false
            viz?.release()
        }
        viz = null
        vizSession = -1
    }

    /**
     * Derive [BANDS] column levels (0..100) from an FFT capture and send them as
     * `{"t":"viz","b":[...]}`. Called from the Visualizer capture thread.
     * @param fft Interleaved real/imaginary FFT bytes from the Visualizer.
     */
    private fun pushViz(fft: ByteArray) {
        val ws = webSocket ?: return
        val bins = fft.size / 2
        if (bins < 2) return
        val sb = StringBuilder("{\"t\":\"viz\",\"b\":[")
        for (b in 0 until BANDS) {
            val lo = 1 + b * (bins - 1) / BANDS
            val hi = 1 + (b + 1) * (bins - 1) / BANDS
            var peak = 0f
            var i = lo
            while (i < hi && i < bins) {
                val re = fft[2 * i].toFloat()
                val im = fft[2 * i + 1].toFloat()
                val mag = sqrt(re * re + im * im)
                if (mag > peak) peak = mag
                i++
            }
            val db = if (peak > 0f) 20f * log10(peak) else 0f   // ~0..45 dB
            val level = ((db / 45f) * 100f).toInt().coerceIn(0, 100)
            if (b > 0) sb.append(',')
            sb.append(level)
        }
        sb.append("]}")
        runCatching { ws.send(sb.toString()) }
    }

    // ----------------------------------------------------------------------
    //  Now-playing push
    // ----------------------------------------------------------------------

    /**
     * Read the current track + position + play-state and send it to the display.
     * No-op if disabled, disconnected, or nothing is playing. Runs on the main
     * thread (required to read ExoPlayer position).
     */
    private fun pushNowPlaying() {
        val ws = webSocket ?: return
        val conn = connection ?: return
        val meta = conn.mediaMetadata.value ?: return
        val payload =
            NpPayload(
                id = meta.id,
                ti = meta.title,
                ar = meta.artists.joinToString(", ") { it.name },
                al = meta.album?.title,
                du = if (meta.duration > 0) meta.duration.toLong() * 1000 else 0L,
                po = runCatching { conn.player.currentPosition }.getOrDefault(0L),
                pl = conn.isPlaying.value,
                th = meta.thumbnailUrl,
            )
        runCatching { ws.send(json.encodeToString(payload)) }
            .onFailure { Timber.tag(TAG).w(it, "send failed") }
    }

    // ----------------------------------------------------------------------
    //  Inbound commands
    // ----------------------------------------------------------------------

    /**
     * Apply a control command from the display to the player (on the main thread).
     * @param action One of next, prev, playpause, volup, voldn.
     */
    private fun applyCommand(action: String) {
        val conn = connection ?: return
        when (action) {
            "next" -> conn.seekToNext()
            "prev" -> conn.seekToPrevious()
            "playpause" -> if (conn.isPlaying.value) conn.pause() else conn.play()
            "volup" -> stepVolume(+VOLUME_STEP)
            "voldn" -> stepVolume(-VOLUME_STEP)
            else -> Timber.tag(TAG).w("unknown command: $action")
        }
    }

    /** Nudge player volume by [delta], clamped to [0,1]. */
    private fun stepVolume(delta: Float) {
        val vol = connection?.service?.playerVolume ?: return
        vol.value = (vol.value + delta).coerceIn(0f, 1f)
    }

    // ----------------------------------------------------------------------
    //  Connection lifecycle
    // ----------------------------------------------------------------------

    /** Resolve the display address (manual host or mDNS) and open the socket. */
    private fun openConnection() {
        teardown()
        if (manualHost.isNotEmpty()) {
            connectTo(normalizeWsUrl(manualHost))
        } else {
            startDiscovery()
        }
    }

    /** Turn a host / partial URL into a full `ws://host[:port]/app` URL. */
    private fun normalizeWsUrl(host: String): String {
        val h = host.removePrefix("ws://").removePrefix("http://").removeSuffix("/")
        return if (h.contains(WS_PATH)) "ws://$h" else "ws://$h$WS_PATH"
    }

    /** Open a WebSocket to [url] and wire up its callbacks. */
    private fun connectTo(url: String) {
        Timber.tag(TAG).i("connecting to $url")
        val request = Request.Builder().url(url).header("User-Agent", context.packageName).build()
        webSocket =
            httpClient.newWebSocket(
                request,
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        connected = true
                        Timber.tag(TAG).i("connected")
                        scope.launch { pushNowPlaying() } // send current state immediately
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val cmd = runCatching { json.decodeFromString<CmdMsg>(text) }.getOrNull() ?: return
                        cmd.a?.let { a -> scope.launch { applyCommand(a) } }
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        scheduleReconnect("closed: $reason")
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        scheduleReconnect("failure: ${t.message}")
                    }
                },
            )
    }

    /** Reconnect after a delay (re-running discovery if in mDNS mode). */
    private fun scheduleReconnect(why: String) {
        connected = false
        webSocket = null
        if (!enabled) return
        if (reconnectJob?.isActive == true) return
        Timber.tag(TAG).d("reconnect scheduled ($why)")
        reconnectJob =
            scope.launch {
                delay(RECONNECT_DELAY_MS)
                if (enabled) openConnection()
            }
    }

    /** Close the socket, stop discovery and cancel pending reconnects. */
    private fun teardown() {
        reconnectJob?.cancel()
        reconnectJob = null
        stopDiscovery()
        runCatching { webSocket?.close(1000, "bridge teardown") }
        webSocket = null
        connected = false
    }

    // ----------------------------------------------------------------------
    //  mDNS discovery (NsdManager)
    // ----------------------------------------------------------------------

    /** Start discovering the `metrolist` `_http._tcp` service on the LAN. */
    private fun startDiscovery() {
        val mgr = nsdManager ?: run { Timber.tag(TAG).w("NSD unavailable"); return }
        stopDiscovery()
        val listener =
            object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {}

                override fun onServiceFound(info: NsdServiceInfo) {
                    if (info.serviceName.contains(SERVICE_NAME_MATCH, ignoreCase = true) && !resolving) {
                        resolving = true
                        runCatching { mgr.resolveService(info, resolveListener()) }
                            .onFailure { resolving = false }
                    }
                }

                override fun onServiceLost(info: NsdServiceInfo) {}

                override fun onDiscoveryStopped(serviceType: String) {}

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Timber.tag(TAG).w("discovery start failed: $errorCode")
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            }
        discoveryListener = listener
        runCatching { mgr.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { Timber.tag(TAG).w(it, "discoverServices failed") }
    }

    /** Resolve a found service to a host:port and connect to it. */
    private fun resolveListener(): NsdManager.ResolveListener =
        object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                resolving = false
                Timber.tag(TAG).w("resolve failed: $errorCode")
            }

            @Suppress("DEPRECATION") // NsdServiceInfo.host is fine for IPv4 LAN use
            override fun onServiceResolved(info: NsdServiceInfo) {
                resolving = false
                val host = info.host?.hostAddress ?: return
                stopDiscovery()
                connectTo("ws://$host:${info.port}$WS_PATH")
            }
        }

    /** Stop any in-progress mDNS discovery. */
    private fun stopDiscovery() {
        val mgr = nsdManager ?: return
        discoveryListener?.let { runCatching { mgr.stopServiceDiscovery(it) } }
        discoveryListener = null
        resolving = false
    }
}
