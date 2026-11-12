package com.metrolist.music.webremote

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import com.metrolist.music.constants.EnableWebRemoteKey
import com.metrolist.music.constants.WebRemotePortKey
import com.metrolist.music.extensions.toggleRepeatMode
import com.metrolist.music.playback.PlayerConnection
import com.metrolist.music.utils.dataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONObject
import timber.log.Timber
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/**
 * Hosts a small web remote on the local network so any browser (PC, tablet, …) on
 * the same Wi-Fi can see what's playing and control it. Serves a single-page UI at
 * `http://<phone-ip>:<port>/` plus `/np` (now-playing JSON) and `/cmd` (commands).
 */
@Singleton
class WebRemoteServer @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var connection: PlayerConnection? = null
    private var server: Server? = null
    private var observeJob: Job? = null

    private var jmdns: JmDNS? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    @Volatile var mdnsName: String? = null
        private set

    @Volatile var runningPort: Int = 0
        private set

    val isRunning: Boolean get() = server?.isAlive == true

    fun start() {
        if (observeJob != null) return
        observeJob = scope.launch {
            context.dataStore.data
                .map { prefs ->
                    (prefs[EnableWebRemoteKey] ?: false) to (prefs[WebRemotePortKey] ?: DEFAULT_PORT)
                }
                .distinctUntilChanged()
                .collect { (enabled, port) ->
                    if (enabled) startServer(port) else stopServer()
                }
        }
    }

    fun setPlayerConnection(conn: PlayerConnection?) {
        connection = conn
    }

    private fun startServer(port: Int) {
        if (server?.isAlive == true && runningPort == port) return
        stopServer()
        runCatching {
            Server(port).apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
        }.onSuccess {
            server = it
            runningPort = port
            registerMdns(port)
            Timber.tag(TAG).i("web remote listening on http://%s:%d", localIpAddress() ?: "?", port)
        }.onFailure {
            Timber.tag(TAG).w(it, "failed to start web remote on port %d", port)
        }
    }

    private fun stopServer() {
        unregisterMdns()
        runCatching { server?.stop() }
        server = null
        runningPort = 0
    }

    /**
     * Advertises an mDNS hostname + service via jmdns so the remote can be opened as
     * `http://metrolist.local:<port>/` from any device whose OS resolves `.local`
     * (macOS, Linux, Windows 10/11, iOS, Android). Unlike Android's NsdManager, jmdns
     * publishes a real A record for [MDNS_HOSTNAME], which browsers can resolve directly.
     */
    private fun registerMdns(port: Int) {
        // jmdns does blocking network I/O and needs a multicast lock; run off the caller.
        scope.launch {
            runCatching {
                val address = wifiInetAddress() ?: return@launch
                unregisterMdns()
                val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                multicastLock = wifi?.createMulticastLock("metrolist-webremote")?.apply {
                    setReferenceCounted(true)
                    runCatching { acquire() }
                }
                val instance = JmDNS.create(address, MDNS_HOSTNAME)
                val service = ServiceInfo.create(
                    MDNS_SERVICE_TYPE_FQ, "Metrolist Remote", port, "Metrolist web remote",
                )
                instance.registerService(service)
                jmdns = instance
                mdnsName = MDNS_HOST
                Timber.tag(TAG).i("mDNS advertising %s -> %s:%d", MDNS_HOST, address.hostAddress, port)
            }.onFailure { Timber.tag(TAG).w(it, "mDNS registration failed") }
        }
    }

    private fun unregisterMdns() {
        runCatching { jmdns?.unregisterAllServices() }
        runCatching { jmdns?.close() }
        jmdns = null
        runCatching { multicastLock?.takeIf { it.isHeld }?.release() }
        multicastLock = null
        mdnsName = null
    }

    private fun wifiInetAddress(): InetAddress? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
    }.getOrNull()

    /** Runs [block] on the main thread and returns its result (ExoPlayer is main-thread only). */
    private fun <T> onMain(default: T, block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return runCatching(block).getOrDefault(default)
        val latch = CountDownLatch(1)
        var result = default
        mainHandler.post {
            try {
                result = block()
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "main action failed")
            } finally {
                latch.countDown()
            }
        }
        latch.await(600, TimeUnit.MILLISECONDS)
        return result
    }

    private fun nowPlayingJson(): String {
        val conn = connection ?: return "{}"
        val meta = conn.mediaMetadata.value ?: return "{}"
        val obj = JSONObject()
        obj.put("id", meta.id)
        obj.put("ti", meta.title)
        obj.put("ar", meta.artists.joinToString(", ") { it.name })
        obj.put("al", meta.album?.title ?: "")
        obj.put("du", if (meta.duration > 0) meta.duration.toLong() * 1000 else 0L)
        obj.put("th", meta.thumbnailUrl ?: "")
        obj.put("pl", conn.isPlaying.value)
        obj.put("fa", conn.currentSong.value?.song?.liked ?: false)
        obj.put("po", onMain(0L) { conn.player.currentPosition })
        obj.put("sh", onMain(false) { conn.player.shuffleModeEnabled })
        obj.put("rp", onMain(0) { conn.player.repeatMode })
        obj.put("vo", onMain(100) { ((conn.service.playerVolume.value) * 100f).toInt() })
        return obj.toString()
    }

    private fun applyCommand(action: String, params: Map<String, List<String>>) {
        val conn = connection ?: return
        val value = params["v"]?.firstOrNull()
        mainHandler.post {
            runCatching {
                when (action) {
                    "play" -> conn.play()
                    "pause" -> conn.pause()
                    "playpause" -> if (conn.isPlaying.value) conn.pause() else conn.play()
                    "next" -> conn.seekToNext()
                    "prev" -> conn.seekToPrevious()
                    "seek" -> value?.toLongOrNull()?.let { conn.seekTo(it) }
                    "vol" -> value?.toIntOrNull()?.let {
                        conn.service.playerVolume.value = (it / 100f).coerceIn(0f, 1f)
                    }
                    "like" -> conn.toggleLike()
                    "shuffle" -> conn.player.shuffleModeEnabled = !conn.player.shuffleModeEnabled
                    "repeat" -> conn.player.toggleRepeatMode()
                }
            }
        }
    }

    private inner class Server(port: Int) : NanoHTTPD(port) {
        override fun serve(session: IHTTPSession): Response {
            return when (session.uri) {
                "/" -> newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", PAGE)
                "/np" -> json(nowPlayingJson())
                "/cmd" -> {
                    val action = session.parameters["a"]?.firstOrNull().orEmpty()
                    if (action.isNotEmpty()) applyCommand(action, session.parameters)
                    json("{\"ok\":true}")
                }
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
            }
        }

        private fun json(body: String): Response =
            newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", body).apply {
                addHeader("Access-Control-Allow-Origin", "*")
                addHeader("Cache-Control", "no-store")
            }
    }

    companion object {
        private const val TAG = "WebRemoteServer"
        const val DEFAULT_PORT = 8181
        const val MDNS_HOSTNAME = "metrolist-remote"
        const val MDNS_SERVICE_TYPE_FQ = "_http._tcp.local."
        /** Hostname to type in a browser (resolves where .local/mDNS resolution is available). */
        const val MDNS_HOST = "metrolist-remote.local"

        /** First non-loopback IPv4 (the Wi-Fi/LAN address) for display in settings. */
        fun localIpAddress(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
                ?.hostAddress
        }.getOrNull()

        private val PAGE = """
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
<title>Metrolist Remote</title>
<style>
  :root { color-scheme: dark; }
  * { box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
  body { margin:0; font-family: system-ui, -apple-system, Segoe UI, Roboto, sans-serif;
         background:#0e0f13; color:#eceef2; display:flex; justify-content:center; }
  .wrap { width:100%; max-width:460px; padding:24px 20px 36px; }
  .art { width:100%; aspect-ratio:1; border-radius:18px; object-fit:cover; background:#1c1e26;
         box-shadow:0 12px 40px rgba(0,0,0,.5); }
  .title { font-size:1.4rem; font-weight:700; margin:18px 2px 2px; line-height:1.2; }
  .artist { color:#9aa0ac; margin:0 2px 2px; }
  .album { color:#6b7280; font-size:.85rem; margin:2px; }
  .bar { width:100%; margin:16px 0 4px; accent-color:#6ea8fe; }
  .times { display:flex; justify-content:space-between; color:#9aa0ac; font-size:.8rem; }
  .row { display:flex; align-items:center; justify-content:center; gap:14px; margin-top:18px; }
  button { background:#1c1e26; color:#eceef2; border:none; border-radius:50%; width:56px; height:56px;
           font-size:1.3rem; cursor:pointer; transition:background .15s; }
  button:active { background:#2a2d38; }
  button.play { width:72px; height:72px; background:#6ea8fe; color:#06121f; font-size:1.6rem; }
  .toggles { display:flex; justify-content:center; gap:10px; margin-top:16px; }
  .toggles button { width:46px; height:46px; font-size:1rem; }
  .toggles button.on { background:#6ea8fe; color:#06121f; }
  .vol { display:flex; align-items:center; gap:10px; margin-top:18px; }
  .vol input { flex:1; accent-color:#6ea8fe; }
  .muted { color:#6b7280; }
</style>
</head>
<body>
<div class="wrap">
  <img id="art" class="art" alt="">
  <div id="title" class="title">—</div>
  <div id="artist" class="artist"></div>
  <div id="album" class="album"></div>
  <input id="seek" class="bar" type="range" min="0" max="1000" value="0">
  <div class="times"><span id="pos">0:00</span><span id="dur">0:00</span></div>
  <div class="row">
    <button onclick="cmd('shuffle')" id="shuffle" title="Shuffle">🔀</button>
    <button onclick="cmd('prev')" title="Previous">⏮</button>
    <button class="play" onclick="cmd('playpause')" id="play" title="Play/Pause">▶</button>
    <button onclick="cmd('next')" title="Next">⏭</button>
    <button onclick="cmd('repeat')" id="repeat" title="Repeat">🔁</button>
  </div>
  <div class="toggles">
    <button onclick="cmd('like')" id="like" title="Like">♡</button>
  </div>
  <div class="vol">
    <span class="muted">🔉</span>
    <input id="vol" type="range" min="0" max="100" value="100">
    <span class="muted">🔊</span>
  </div>
</div>
<script>
  let seeking = false, dragDur = 0;
  const ${'$'} = id => document.getElementById(id);
  const fmt = ms => { const s = Math.max(0, Math.floor(ms/1000)); return Math.floor(s/60)+':'+String(s%60).padStart(2,'0'); };
  function cmd(a, v){ let u='/cmd?a='+a; if(v!==undefined) u+='&v='+v; fetch(u).then(poll); }
  ${'$'}('seek').addEventListener('input', ()=>{ seeking=true; ${'$'}('pos').textContent=fmt(${'$'}('seek').value/1000*dragDur); });
  ${'$'}('seek').addEventListener('change', ()=>{ cmd('seek', Math.floor(${'$'}('seek').value/1000*dragDur)); seeking=false; });
  ${'$'}('vol').addEventListener('change', ()=> cmd('vol', ${'$'}('vol').value));
  async function poll(){
    try {
      const r = await fetch('/np', {cache:'no-store'}); const d = await r.json();
      if(!d.id){ ${'$'}('title').textContent='Nothing playing'; return; }
      ${'$'}('title').textContent = d.ti || '';
      ${'$'}('artist').textContent = d.ar || '';
      ${'$'}('album').textContent = d.al || '';
      if(d.th) ${'$'}('art').src = d.th;
      ${'$'}('play').textContent = d.pl ? '⏸' : '▶';
      ${'$'}('like').textContent = d.fa ? '♥' : '♡';
      ${'$'}('like').classList.toggle('on', !!d.fa);
      ${'$'}('shuffle').classList.toggle('on', !!d.sh);
      ${'$'}('repeat').classList.toggle('on', d.rp>0);
      ${'$'}('repeat').textContent = d.rp===1 ? '🔂' : '🔁';
      dragDur = d.du || 0;
      if(!seeking){
        ${'$'}('seek').value = d.du>0 ? Math.floor(d.po/d.du*1000) : 0;
        ${'$'}('pos').textContent = fmt(d.po); ${'$'}('dur').textContent = fmt(d.du);
      }
      if(document.activeElement !== ${'$'}('vol')) ${'$'}('vol').value = d.vo;
    } catch(e){}
  }
  poll(); setInterval(poll, 1000);
</script>
</body>
</html>
""".trimIndent()
    }
}
