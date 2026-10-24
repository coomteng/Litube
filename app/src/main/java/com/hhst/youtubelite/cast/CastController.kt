package com.hhst.youtubelite.cast

import com.hhst.youtubelite.diagnostics.*

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.net.toUri
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.ChunkIndex
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.MoreExecutors
import com.hhst.youtubelite.cast.dlna.DlnaDevice
import com.hhst.youtubelite.cast.dlna.DlnaMedia
import com.hhst.youtubelite.cast.dlna.DlnaRemotePlayer
import com.hhst.youtubelite.cast.dlna.DlnaRenderer
import com.hhst.youtubelite.cast.lounge.DialClient
import com.hhst.youtubelite.cast.lounge.DialScreen
import com.hhst.youtubelite.cast.lounge.LoungeApi
import com.hhst.youtubelite.cast.lounge.LoungeRemotePlayer
import com.hhst.youtubelite.cast.protocol.CastV2Discovery
import com.hhst.youtubelite.cast.protocol.CastV2Session
import com.hhst.youtubelite.cast.ssdp.SsdpDiscovery
import com.hhst.youtubelite.extractor.Format
import com.hhst.youtubelite.extractor.YoutubeMediaRequests
import com.hhst.youtubelite.player.engine.CastSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.OkHttpClient
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

enum class CastDeviceKind { CHROMECAST, DLNA, YOUTUBE_TV }

data class CastDevice(
    val id: String,
    val name: String,
    val selected: Boolean,
    val kind: CastDeviceKind = CastDeviceKind.CHROMECAST,
)

data class CastUiState(
    val available: Boolean = false,
    /** Any remote session (Chromecast, DLNA renderer or YouTube TV) is connected. */
    val remoteSession: Boolean = false,
    val deviceName: String? = null,
    val deviceKind: CastDeviceKind? = null,
    val devices: List<CastDevice> = emptyList(),
    val linkUrl: String? = null,
    val linkConnected: Boolean = false,
    /** Web Video Caster is installed; the dialog offers a hand-off row. */
    val wvcAvailable: Boolean = false,
    /** A TV-code pairing is in progress. */
    val pairing: Boolean = false,
)

/**
 * Multi-backend sender. Discovery merges Cast mDNS with one SSDP scan (DLNA
 * MediaRenderer + DIAL). A selected device becomes one [RemoteSession];
 * [PlayerViewModel] drives whichever is active through the same
 * start/stop/end calls and the Media3 [remotePlayer].
 *
 * Chromecast and DLNA stream through the LAN proxy ([LocalStreamProxy]);
 * YouTube TV (Lounge API) plays the video id itself. Web Video Caster is a
 * hand-off, not a session: [wvcIntent] gives the proxy URL to the WVC app.
 */
@UnstableApi
class CastController(
    private val appContext: Context,
    private val http: OkHttpClient,
    private val mediaRequests: YoutubeMediaRequests? = null,
    private val sessionFactory: (String, Int, CastV2Session.Listener) -> CastV2Session =
        { host, port, listener -> CastV2Session.forDefaultMediaReceiver(host, port, listener) },
) {
    private val main = Handler(Looper.getMainLooper())
    private val io: ExecutorService = Executors.newCachedThreadPool { r -> Thread(r, "CastSidx").apply { isDaemon = true } }
    private val listeningIo = MoreExecutors.listeningDecorator(io)
    private val _state = MutableStateFlow(CastUiState())
    val state: StateFlow<CastUiState> = _state.asStateFlow()
    @Volatile private var proxy: LocalStreamProxy? = null
    private var discovery: CastV2Discovery? = null
    private var ssdp: SsdpDiscovery? = null
    private var discovering = false

    private sealed interface Endpoint {
        val name: String
        data class Chromecast(override val name: String, val host: String, val port: Int) : Endpoint
        data class Dlna(override val name: String, val device: DlnaDevice) : Endpoint
        data class YoutubeTv(override val name: String, val screen: DialScreen) : Endpoint
    }
    private val endpoints = linkedMapOf<String, Endpoint>()
    private var selectedId: String? = null
    private var sessionGeneration = 0L
    private var diagnosticTrace: CastDiagnosticTrace? = null
    private val configureGen = AtomicLong()
    private val configureLock = Any()
    @Volatile private var proxyConfiguredVideoId: String? = null
    private var castingActive = false
    private var discoveryUserEnabled = false
    private var loadingUpgrade = false
    var urlRefresher: LocalStreamProxy.UrlRefresher? = null
        set(value) { field = value; proxy?.urlRefresher = value }
    var onSessionStarted: (() -> Unit)? = null
    var onSessionEnded: (() -> Unit)? = null
    var onSessionFailed: (() -> Unit)? = null

    // ------------------------------------------------------------------ sessions

    /** One connected remote target. Implementations own their Media3 player. */
    private interface RemoteSession {
        val kind: CastDeviceKind
        val player: Player
        /** Pushes [source] at [positionMs]; false when it cannot be cast there. */
        fun load(source: CastSource, positionMs: Long): Boolean
        /** Stops the current media; the session stays up. Returns the position. */
        fun stopMedia(): Long
        fun close()
    }
    private var currentSession: RemoteSession? = null
    private var connecting: Boolean = false

    fun initialize(): Boolean {
        if (discovery == null) discovery = CastV2Discovery(appContext)
        if (ssdp == null) ssdp = SsdpDiscovery(appContext, listOf(SsdpDiscovery.ST_MEDIA_RENDERER, SsdpDiscovery.ST_DIAL))
        _state.update { it.copy(wvcAvailable = isWvcInstalled()) }
        applyDiscovery()
        return true
    }
    fun remotePlayer(): Player? = if (castingActive) currentSession?.player else null
    fun setDiscoveryEnabled(enabled: Boolean) { discoveryUserEnabled = enabled; applyDiscovery() }

    private fun applyDiscovery() {
        val discovery = discovery ?: return
        val active = discoveryUserEnabled || castingActive
        if (active == discovering) return
        discovering = active
        AppLog.event(AppLog.Category.CAST, "discovery_state", mapOf("active" to active))
        if (active) {
            // Keep the connected endpoint so its row stays selected.
            val keep = selectedId?.let { id -> endpoints[id]?.let { id to it } }
            endpoints.clear()
            if (keep != null) endpoints[keep.first] = keep.second
            _state.update { it.copy(wvcAvailable = isWvcInstalled()) }
            publishRoutes()
            discovery.start { name, host, port ->
                val id = "cast:$host:$port"
                endpoints[id] = Endpoint.Chromecast(name, host, port)
                AppLog.event(AppLog.Category.CAST, "device_resolved", mapOf("device_name" to name, "host" to host, "port" to port))
                publishRoutes()
            }
            ssdp?.start { response -> resolveSsdp(response.location, response.searchTarget, response.usn, response.host) }
        } else {
            discovery.stop()
            ssdp?.stop()
        }
    }

    /** Fetches the SSDP description off-thread and registers the endpoint. */
    private fun resolveSsdp(location: String, st: String, usn: String, host: String) {
        io.execute {
            val endpoint: Pair<String, Endpoint>? = runCatching {
                when (st) {
                    SsdpDiscovery.ST_MEDIA_RENDERER -> {
                        val xml = LanHttp.get(location).takeIf { it.isSuccessful }?.body ?: return@runCatching null
                        val device = DlnaDevice.parse(xml, location) ?: return@runCatching null
                        "dlna:${device.udn}" to Endpoint.Dlna(device.friendlyName, device)
                    }
                    SsdpDiscovery.ST_DIAL -> {
                        val screen = DialClient().describe(location, usn, host) ?: return@runCatching null
                        // Only screens that actually have the YouTube app.
                        val info = runCatching { DialClient().appInfo(screen) }.getOrNull() ?: return@runCatching null
                        if (info.state == "not_installed") return@runCatching null
                        "yt:${screen.applicationUrl}" to Endpoint.YoutubeTv(screen.friendlyName, screen)
                    }
                    else -> null
                }
            }.onFailure {
                AppLog.event(AppLog.Category.CAST, "ssdp_resolve_failed", mapOf("st" to st, "location" to location), it)
            }
                .getOrNull()
            if (endpoint != null) main.post {
                if (!discovering) return@post
                endpoints[endpoint.first] = endpoint.second
                AppLog.event(AppLog.Category.CAST, "device_resolved", mapOf("device_name" to endpoint.second.name, "kind" to endpoint.second::class.simpleName))
                publishRoutes()
            }
        }
    }

    fun publishRoutes() {
        _state.update { it.copy(available = endpoints.isNotEmpty(), devices = endpoints.map { (id, e) ->
            CastDevice(id, e.name, id == selectedId, when (e) {
                is Endpoint.Chromecast -> CastDeviceKind.CHROMECAST
                is Endpoint.Dlna -> CastDeviceKind.DLNA
                is Endpoint.YoutubeTv -> CastDeviceKind.YOUTUBE_TV
            })
        }) }
    }

    fun selectDevice(id: String) {
        val endpoint = endpoints[id] ?: return
        endSession()
        val generation = ++sessionGeneration
        diagnosticTrace = CastDiagnosticTrace()
        selectedId = id
        publishRoutes()
        when (endpoint) {
            is Endpoint.Chromecast -> connectChromecast(endpoint, generation)
            is Endpoint.Dlna -> connectDlna(endpoint, generation)
            is Endpoint.YoutubeTv -> connectYoutubeTv(endpoint, generation)
        }
    }

    /** `Link with TV code`: pairs a YouTube TV that DIAL did not find. */
    fun pairYoutubeTv(code: String) {
        endSession()
        val generation = ++sessionGeneration
        _state.update { it.copy(pairing = true) }
        io.execute {
            val api = LoungeApi(http, deviceName())
            val screen = runCatching { api.pairWithCode(code) }
                .onFailure { AppLog.event(AppLog.Category.CAST, "lounge_pair_failed", failure = it) }
                .getOrNull()
            main.post {
                _state.update { it.copy(pairing = false) }
                if (generation != sessionGeneration) return@post
                if (screen == null) { onSessionFailed?.invoke(); return@post }
                val name = screen.name ?: "YouTube TV"
                val id = "yt:paired:${screen.screenId}"
                endpoints[id] = Endpoint.YoutubeTv(name, DialScreen("paired", name, "", ""))
                selectedId = id
                publishRoutes()
                openLounge(api, screen, name, generation)
            }
        }
    }

    private fun sessionConnected(session: RemoteSession, name: String) {
        currentSession = session
        connecting = false
        _state.update { it.copy(remoteSession = true, deviceName = name, deviceKind = session.kind) }
        publishRoutes()
        onSessionStarted?.invoke()
    }

    // ---------------------------------------------------------------- Chromecast

    private fun connectChromecast(endpoint: Endpoint.Chromecast, generation: Long) {
        val trace = diagnosticTrace!!
        var connected = false
        lateinit var session: CastV2Session
        lateinit var remote: ChromecastSession
        connecting = true
        val listener = object : CastV2Session.Listener {
            override fun onConnected() { main.post {
                if (generation != sessionGeneration) return@post
                connected = true
                trace.phase("session_connected")
                sessionConnected(remote, endpoint.name)
            } }
            override fun onMediaStatus(raw: String?, position: Long, duration: Long, idleReason: String?) { main.post {
                if (generation == sessionGeneration) remote.castPlayer.status(raw, position, duration, idleReason)
            } }
            override fun onLaunchError(reason: String) { trace.error("receiver_launch_failed", null); failed() }
            override fun onChannelError(reason: String) { trace.error("channel_failed", null); failed() }
            override fun onLoadFailed(type: String) { main.post {
                if (generation == sessionGeneration && !loadingUpgrade) finishSession(true, connected)
            } }
            override fun onClosed() { main.post {
                if (generation == sessionGeneration) finishSession(false, connected)
            } }
            private fun failed() { main.post {
                if (generation == sessionGeneration) finishSession(true, connected)
            } }
        }
        session = sessionFactory(endpoint.host, endpoint.port, listener)
        session.setDiagnostics(trace)
        remote = ChromecastSession(session)
        pendingSession = remote
        session.start()
    }

    /** Session object created before onConnected; closed if the attempt is abandoned. */
    private var pendingSession: RemoteSession? = null

    private inner class ChromecastSession(val session: CastV2Session) : RemoteSession {
        override val kind = CastDeviceKind.CHROMECAST
        val castPlayer = CastRemotePlayer(session)
        override val player: Player get() = castPlayer
        override fun load(source: CastSource, positionMs: Long): Boolean {
            val p = (proxy ?: freshProxy()) ?: return false
            p.rangeChunkCap = true
            if (!configureProxy(p, source)) return false
            scheduleSidxUpgradeAndReceiverLoad(p, session, castPlayer, source, positionMs)
            return true
        }
        override fun stopMedia(): Long {
            val position = castPlayer.currentPosition
            castPlayer.reset()
            return position
        }
        override fun close() { session.close() }
    }

    // ---------------------------------------------------------------------- DLNA

    private fun connectDlna(endpoint: Endpoint.Dlna, generation: Long) {
        connecting = true
        io.execute {
            val renderer = DlnaRenderer(endpoint.device)
            // Reachability + capability probe; a dead renderer fails here, not at load.
            val reachable = runCatching { renderer.transportInfo() }.isSuccess
            val sinks = if (reachable) renderer.sinkProtocols() else emptyList()
            main.post {
                if (generation != sessionGeneration) return@post
                if (!reachable) { finishSession(true, false); return@post }
                val dash = sinks.any { it.contains("application/dash+xml", true) }
                val remote = DlnaSession(renderer, dashCapable = dash, generation)
                pendingSession = remote
                AppLog.event(AppLog.Category.CAST, "dlna_connected", mapOf("device_name" to endpoint.name, "dash" to dash, "sinks" to sinks.size))
                sessionConnected(remote, endpoint.name)
            }
        }
    }

    private inner class DlnaSession(renderer: DlnaRenderer, private val dashCapable: Boolean, private val generation: Long) : RemoteSession {
        override val kind = CastDeviceKind.DLNA
        /** Remaining media plans for the current source; tried in order on a recoverable failure. */
        private var fallbacks: ArrayDeque<() -> DlnaMedia?> = ArrayDeque()
        private var currentSource: CastSource? = null
        val dlnaPlayer = DlnaRemotePlayer(renderer) { reason ->
            main.post {
                if (generation != sessionGeneration || currentSession !== this) return@post
                if (reason in FALLBACK_REASONS && tryNextPlan(0L)) return@post
                finishSession(true, true)
            }
        }
        override val player: Player get() = dlnaPlayer
        override fun load(source: CastSource, positionMs: Long): Boolean {
            val p = (proxy ?: freshProxy()) ?: return false
            // Renderers stream one long-lived ranged GET: no chunk cap.
            p.rangeChunkCap = false
            currentSource = source
            fallbacks = plans(p, source)
            return tryNextPlan(positionMs)
        }

        /**
         * Video first (DASH when the renderer declares it, else progressive
         * MP4), audio-only last. A music-mode source (no video track) goes
         * straight to audio. Each plan publishes its own proxy generation
         * when it runs, so an abandoned plan never leaves stale tokens.
         */
        private fun plans(p: LocalStreamProxy, source: CastSource): ArrayDeque<() -> DlnaMedia?> {
            val out = ArrayDeque<() -> DlnaMedia?>()
            val hasDash = source.video != null && source.audio != null
            val dur = source.durationSec * 1000
            if (source.video != null) {
                // DASH first even when GetProtocolInfo is silent about it: LG
                // webOS plays MPDs without declaring them, and only DASH gives
                // picture AND sound above 360p. Undeclared support gets a short
                // start grace so speakers fall through quickly.
                if (hasDash) out += {
                    // TVs do not parse sidx either: publish a SegmentList
                    // manifest up front (blocking sidx fetch, bounded).
                    if (configureProxy(p, source) && publishSegmentList(p, source)) DlnaMedia(p.proxyUrl("/manifest.mpd"), "application/dash+xml", source.title, source.author, source.thumbnailUrl, dur, isVideo = true,
                        startGraceMs = if (dashCapable) DlnaRemotePlayer.DEFAULT_START_GRACE_MS else DlnaRemotePlayer.PROBE_START_GRACE_MS) else null
                }
                source.progressive?.let { prog -> out += {
                    if (configureProxy(p, source.copy(video = prog, audio = null))) DlnaMedia(p.proxyUrl("/stream/v?g=${p.currentConfigId()}"), "video/mp4", source.title, source.author, source.thumbnailUrl, dur, isVideo = true) else null
                } }
            }
            if (source.audio != null) out += {
                if (configureProxy(p, source.copy(video = null))) DlnaMedia(p.proxyUrl("/stream/a?g=${p.currentConfigId()}"), "audio/mp4", source.title, source.author, source.thumbnailUrl, dur, isVideo = false) else null
            }
            return out
        }

        /**
         * Fetches both sidx boxes and swaps in a SegmentList manifest. Runs on
         * the caller's thread (IO); false when either index is unusable.
         */
        private fun publishSegmentList(p: LocalStreamProxy, source: CastSource): Boolean {
            val gen = configureGen.get()
            val vFuture = listeningIo.submit(Callable { p.fetchChunkIndex("v") })
            val aFuture = listeningIo.submit(Callable { p.fetchChunkIndex("a") })
            val vci = runCatching { vFuture.get(SIDX_FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS) }.getOrNull()
            val aci = runCatching { aFuture.get(SIDX_FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS) }.getOrNull()
            val ok = upgradeToSegmentList(p, source, vci, aci, expectedGen = gen)
            AppLog.event(AppLog.Category.CAST, "dlna_segment_list", mapOf("ok" to ok, "v" to (vci?.length ?: -1), "a" to (aci?.length ?: -1)))
            return ok
        }

        /**
         * Runs the next plan off the main thread (sidx fetches block) and
         * loads it; when every plan fails the session ends. Returns false only
         * when no plan is left to try.
         */
        private fun tryNextPlan(positionMs: Long): Boolean {
            val source = currentSource ?: return false
            if (fallbacks.isEmpty()) return false
            val attemptGen = sessionGeneration
            io.execute {
                var media: DlnaMedia? = null
                while (media == null) {
                    val plan = synchronized(fallbacks) { fallbacks.removeFirstOrNull() } ?: break
                    if (attemptGen != sessionGeneration) return@execute
                    media = plan()
                }
                main.post {
                    if (attemptGen != sessionGeneration || currentSession !== this) return@post
                    if (media == null) { finishSession(true, true); return@post }
                    AppLog.event(AppLog.Category.CAST, "dlna_load_plan", mapOf("mime" to media.mimeType, "video" to media.isVideo, "remaining" to fallbacks.size))
                    dlnaPlayer.load(media, source.videoId, positionMs)
                }
            }
            return true
        }
        override fun stopMedia(): Long {
            val position = dlnaPlayer.currentPosition
            fallbacks.clear()
            dlnaPlayer.reset()
            return position
        }
        override fun close() { dlnaPlayer.shutdown() }
    }

    // ---------------------------------------------------------------- YouTube TV

    private fun connectYoutubeTv(endpoint: Endpoint.YoutubeTv, generation: Long) {
        connecting = true
        io.execute {
            val api = LoungeApi(http, deviceName())
            val screen = runCatching {
                val screenId = DialClient().resolveScreenId(endpoint.screen) ?: error("no screenId")
                api.loungeToken(screenId)
            }.onFailure { AppLog.event(AppLog.Category.CAST, "dial_resolve_failed", failure = it) }.getOrNull()
            main.post {
                if (generation != sessionGeneration) return@post
                if (screen == null) { finishSession(true, false); return@post }
                openLounge(api, screen, endpoint.name, generation)
            }
        }
    }

    private fun openLounge(api: LoungeApi, screen: LoungeApi.Screen, name: String, generation: Long) {
        connecting = true
        val remote = LoungeSession(api, screen, generation)
        pendingSession = remote
        remote.loungePlayer.connect { ok ->
            if (generation != sessionGeneration) { remote.close(); return@connect }
            if (!ok) { finishSession(true, false); return@connect }
            sessionConnected(remote, name)
        }
    }

    private inner class LoungeSession(api: LoungeApi, screen: LoungeApi.Screen, private val generation: Long) : RemoteSession {
        override val kind = CastDeviceKind.YOUTUBE_TV
        val loungePlayer = LoungeRemotePlayer(api, screen) { _ ->
            main.post { if (generation == sessionGeneration && currentSession === this) finishSession(true, true) }
        }
        override val player: Player get() = loungePlayer
        override fun load(source: CastSource, positionMs: Long): Boolean {
            loungePlayer.load(source.videoId, source.title, source.author, positionMs)
            return true
        }
        override fun stopMedia(): Long {
            val position = loungePlayer.currentPosition
            loungePlayer.reset()
            return position
        }
        override fun close() { loungePlayer.shutdown(stopVideo = true) }
    }

    // ------------------------------------------------------------- lifecycle

    private fun finishSession(failed: Boolean, hadSession: Boolean) {
        val session = currentSession ?: pendingSession
        sessionGeneration++
        currentSession = null
        pendingSession = null
        connecting = false
        selectedId = null
        _state.update { it.copy(remoteSession = false, deviceName = null, deviceKind = null) }
        if (hadSession) onSessionEnded?.invoke() else if (failed) onSessionFailed?.invoke()
        stopCasting()
        session?.close()
        publishRoutes()
    }
    fun endSession() {
        if (currentSession != null || pendingSession != null || connecting) finishSession(false, _state.value.remoteSession)
    }
    fun startCasting(source: CastSource, positionMs: Long): Boolean {
        val session = currentSession ?: return false
        castingActive = true
        applyDiscovery()
        if (!session.load(source, positionMs)) { stopCasting(); return false }
        return true
    }
    fun stopCasting(): Long {
        val position = currentSession?.stopMedia() ?: 0L
        castingActive = false
        configureGen.incrementAndGet()
        applyDiscovery()
        stopProxy()
        return position
    }

    // ------------------------------------------------------------- link / WVC

    fun ensureProxy(source: CastSource): String? {
        // A live cast session owns the proxy for its own video only; reconfiguring for another
        // source would retarget the receiver's segment requests (garbled bytes / 403). Refuse
        // link-cast during a session instead.
        if (castingActive) {
            val p = proxy ?: return null
            return if (proxyConfiguredVideoId == source.videoId) p.proxyUrl("/player") else null
        }
        val p = proxy ?: createProxy() ?: return null
        p.rangeChunkCap = true
        if (!configureProxy(p, source)) return null
        _state.update { it.copy(linkUrl = p.proxyUrl("/player"), linkConnected = p.isLinkPeerConnected()) }
        return p.proxyUrl("/player")
    }

    /**
     * Hand-off to Web Video Caster: publishes [source] on the proxy and
     * returns a VIEW intent for the WVC app (DASH MPD, or the progressive MP4
     * when the video has no DASH pair). Null when WVC is missing or the proxy
     * cannot start. Not a session: WVC owns playback from here.
     */
    fun wvcIntent(source: CastSource): Intent? {
        if (!isWvcInstalled()) return null
        if (castingActive) return null
        val p = proxy ?: createProxy() ?: return null
        p.rangeChunkCap = false
        val (url, mime) = when {
            source.video != null && source.audio != null -> {
                if (!configureProxy(p, source)) return null
                p.proxyUrl("/manifest.mpd") to "application/dash+xml"
            }
            source.progressive != null -> {
                if (!configureProxy(p, source.copy(video = source.progressive, audio = null))) return null
                p.proxyUrl("/stream/v?g=${p.currentConfigId()}") to "video/mp4"
            }
            source.audio != null -> {
                if (!configureProxy(p, source.copy(video = null))) return null
                p.proxyUrl("/stream/a?g=${p.currentConfigId()}") to "audio/mp4"
            }
            else -> return null
        }
        _state.update { it.copy(linkUrl = p.proxyUrl("/player"), linkConnected = p.isLinkPeerConnected()) }
        AppLog.event(AppLog.Category.CAST, "wvc_handoff", mapOf("mime" to mime, "video_id" to source.videoId))
        return Intent(Intent.ACTION_VIEW).apply {
            setPackage(WVC_PACKAGE)
            setDataAndType(url.toUri(), mime)
            putExtra("title", source.title)
            source.thumbnailUrl?.let { putExtra("poster", it) }
            putExtra("secure_uri", true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun isWvcInstalled(): Boolean =
        runCatching { appContext.packageManager.getPackageInfo(WVC_PACKAGE, 0) }.isSuccess

    private fun deviceName(): String = "Litube Plus"

    fun refreshLinkBanner() {
        val p = proxy ?: return
        _state.update { it.copy(linkConnected = p.isLinkPeerConnected(), linkUrl = p.proxyUrl("/player")) }
    }

    fun stopLink() = stopProxy()

    private fun stopProxy() {
        runCatching { proxy?.stop() }
        proxy = null
        proxyConfiguredVideoId = null
        _state.update { it.copy(linkUrl = null, linkConnected = false) }
    }

    /** Fresh proxy per cast session. Stored on [proxy] so teardown and the sidx identity check see it. */
    private fun freshProxy(): LocalStreamProxy? {
        stopProxy()
        return createProxy()
    }

    private fun createProxy(): LocalStreamProxy? {
        val host = LocalStreamProxy.resolveBindHost(appContext) ?: return null
        return runCatching {
            LocalStreamProxy(appContext, http, advertisedHost = host, mediaRequests = mediaRequests).also {
                it.urlRefresher = urlRefresher
                it.start()
                proxy = it
            }
        }.onFailure {
            AppLog.event(AppLog.Category.CAST, "proxy_start_failed", failure = it, context = diagnosticTrace?.context)
            proxy = null
        }.getOrNull()
    }

    private fun configureProxy(p: LocalStreamProxy, source: CastSource): Boolean {
        diagnosticTrace?.video(source.videoId, configureGen.get())
        p.diagnosticContext = diagnosticTrace?.context ?: DiagnosticContext(videoId = source.videoId)
        val generation = p.allocateGeneration()
        // A progressive/audio-only publish has no DASH pair; the manifest is
        // then absent and only /stream tokens are served.
        val manifest = if (source.video?.hasDashRanges == true || source.audio?.hasDashRanges == true) {
            CastManifest.build(
                video = source.video?.takeIf { it.hasDashRanges },
                audio = source.audio?.takeIf { it.hasDashRanges },
                durationMs = source.durationSec * 1000,
                proxyBase = p.proxyUrl(""),
                generation = generation,
            )
        } else null
        if (manifest == null && source.video == null && source.audio == null) return false
        synchronized(configureLock) {
            configureGen.incrementAndGet()
            applySource(p, manifest, source, generation)
        }
        return true
    }

    /** Single configure site: applies the manifest and stream metadata to [p]. */
    private fun applySource(
        p: LocalStreamProxy,
        manifestXml: String?,
        source: CastSource,
        generation: Long,
    ) {
        p.configure(
            manifestXml = manifestXml,
            videoTitle = source.title,
            videoId = source.videoId,
            videoUrl = source.video?.url,
            videoMime = source.video?.mimeType,
            audioUrl = source.audio?.url,
            audioMime = source.audio?.mimeType,
            video = source.video?.ranges(),
            audio = source.audio?.ranges(),
            videoItag = source.video?.itag,
            audioItag = source.audio?.itag,
            generation = generation,
            videoRequestPlan = source.video?.requestPlan,
            audioRequestPlan = source.audio?.requestPlan,
        )
        proxyConfiguredVideoId = source.videoId
    }

    /**
     * Sidx wait → load → upgrade protocol. The default receiver
     * cannot parse sidx, so a SegmentList manifest is required for segment
     * addressing; SegmentBase loads immediately when sidx is slow so the TV
     * still starts, then gets reloaded once SegmentList becomes available.
     */
    private fun scheduleSidxUpgradeAndReceiverLoad(
        p: LocalStreamProxy,
        session: CastV2Session,
        player: CastRemotePlayer,
        source: CastSource,
        positionMs: Long,
    ) {
        val gen = configureGen.get()
        io.execute {
            val vFuture = listeningIo.submit(Callable { p.fetchChunkIndex("v") })
            val aFuture = listeningIo.submit(Callable { p.fetchChunkIndex("a") })
            var sidxReady = false
            try {
                Futures.whenAllComplete(vFuture, aFuture).call(Callable {
                    Futures.getDone(vFuture); Futures.getDone(aFuture)
                }, MoreExecutors.directExecutor()).get(SIDX_FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                sidxReady = true
            } catch (_: TimeoutException) {
                Log.w(TAG, "sidx fetch timed out, loading SegmentBase first")
            } catch (e: Exception) {
                Log.w(TAG, "sidx fetch failed", e)
            }

            val vci = runCatching { Futures.getDone(vFuture) }.getOrNull()
            val aci = runCatching { Futures.getDone(aFuture) }.getOrNull()
            val firstLoadSegmentList = sidxReady && (vci != null || aci != null)
            main.post {
                if (this.proxy !== p || gen != configureGen.get()) return@post
                if (firstLoadSegmentList) {
                    upgradeToSegmentList(p, source, vci, aci, expectedGen = gen)
                }
                loadOnReceiver(session, player, p.proxyUrl("/manifest.mpd"), source, positionMs)
            }

            if (sidxReady) return@execute

            // SegmentBase was loaded; keep waiting and reload with SegmentList.
            try {
                Futures.whenAllComplete(vFuture, aFuture).call(Callable {
                    Futures.getDone(vFuture); Futures.getDone(aFuture)
                }, MoreExecutors.directExecutor()).get(SIDX_RELOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                Log.w(TAG, "sidx reload timed out, staying on SegmentBase")
                return@execute
            } catch (e: ExecutionException) {
                Log.w(TAG, "sidx reload failed", e)
                return@execute
            }
            val vReload = runCatching { Futures.getDone(vFuture) }.getOrNull()
            val aReload = runCatching { Futures.getDone(aFuture) }.getOrNull()
            if (this.proxy !== p || gen != configureGen.get()) return@execute
            if (!upgradeToSegmentList(p, source, vReload, aReload, expectedGen = gen)) return@execute
            main.post {
                if (this.proxy !== p || gen != configureGen.get()) return@post
                val reloadPos = runCatching { player.currentPosition }.getOrDefault(positionMs)
                loadOnReceiver(
                    session, player, p.proxyUrl("/manifest.mpd"), source, reloadPos,
                    upgrade = true,
                )
            }
        }
    }

    /** Rebuilds the manifest with per-segment URLs and hot-swaps it in. */
    private fun upgradeToSegmentList(
        p: LocalStreamProxy,
        source: CastSource,
        vci: ChunkIndex?,
        aci: ChunkIndex?,
        expectedGen: Long,
    ): Boolean {
        if (vci == null && aci == null) return false
        // Default receiver cannot parse sidx: every track that exists must
        // have a SegmentList, otherwise the other half is silent.
        if (source.video != null && !CastManifest.usableSegmentIndex(vci)) return false
        if (source.audio != null && !CastManifest.usableSegmentIndex(aci)) return false
        val generation = p.currentConfigId()
        val manifest = CastManifest.build(
            video = source.video,
            audio = source.audio,
            durationMs = source.durationSec * 1000,
            proxyBase = p.proxyUrl(""),
            videoIndex = vci,
            audioIndex = aci,
            generation = generation,
        ) ?: return false
        // Re-check under the lock: a main-thread configureProxy may have
        // landed between the caller's gen check and this swap.
        synchronized(configureLock) {
            if (proxy !== p || expectedGen != configureGen.get()) return false
            applySource(p, manifest, source, generation)
        }
        return true
    }

    private fun loadOnReceiver(
        session: CastV2Session, player: CastRemotePlayer, manifestUrl: String,
        source: CastSource, positionMs: Long, upgrade: Boolean = false,
    ) {
        if ((currentSession as? ChromecastSession)?.session !== session || !castingActive) return
        loadingUpgrade = upgrade && player.playbackState == Player.STATE_READY
        player.loading(manifestUrl, source, positionMs)
        session.load(manifestUrl, MimeTypes.APPLICATION_MPD, source.title, source.thumbnailUrl, positionMs.coerceAtLeast(0), source.author)
    }

    private fun Format.ranges() = LocalStreamProxy.StreamRanges(
        indexStart = indexStart.toLong(),
        indexEnd = indexEnd.toLong(),
        // Total size comes from the URL's clen= when present (progressive
        // clients need it up front); -1 otherwise keeps the proxy from
        // emitting a bogus Content-Range total.
        contentLength = LocalStreamProxy.contentLengthFromUrl(url),
    )

    companion object {
        private const val TAG = "CastController"
        const val WVC_PACKAGE = "com.instantbits.cast.webvideo"
        /** DLNA failures that justify trying the next media plan (video → audio). */
        private val FALLBACK_REASONS = setOf("load", "no_start", "stalled")
        private const val SIDX_FETCH_TIMEOUT_MS = 15_000L
        private const val SIDX_RELOAD_TIMEOUT_MS = 15_000L
    }
}
