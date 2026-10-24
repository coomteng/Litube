package com.hhst.youtubelite.cast.lounge

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.gson.JsonObject
import com.hhst.youtubelite.cast.lounge.LoungeApi.Companion.str
import com.hhst.youtubelite.diagnostics.AppLog
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Media3 adapter over a YouTube-on-TV Lounge session. The TV fetches the
 * video itself (no proxy); this side only sends commands and mirrors the
 * `onStateChange` / `nowPlaying` events the TV pushes over the long-poll.
 *
 * Lounge state codes: -1 unstarted, 0 ended, 1 playing, 2 paused,
 * 3 buffering, 5 cued.
 */
@UnstableApi
internal class LoungeRemotePlayer(
    private val api: LoungeApi,
    private val screen: LoungeApi.Screen,
    private val onDisconnected: (reason: String) -> Unit,
) : SimpleBasePlayer(Looper.getMainLooper()) {
    private val main = Handler(Looper.getMainLooper())
    private val io: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "LoungeCmd").apply { isDaemon = true } }
    private var session: LoungeApi.Session? = null
    private var pollThread: Thread? = null
    private var item: MediaItem? = null
    private var videoId: String? = null
    private var durationMs = 0L
    private var positionMs = 0L
    private var sampledAt = SystemClock.elapsedRealtime()
    private var state = STATE_IDLE
    private var playing = false
    private var wanted = false
    private var repeat = REPEAT_MODE_OFF
    private var speed = 1f
    private var error: PlaybackException? = null
    @Volatile private var released = false
    /** Ignore stale TV events that still describe the previous video after a switch. */
    private var pendingVideoId: String? = null

    /** Binds the session and starts the event poll; [onReady] fires on the main thread. */
    fun connect(onReady: (Boolean) -> Unit) {
        io.execute {
            val s = runCatching { api.connect(screen) }
                .onFailure { AppLog.event(AppLog.Category.CAST, "lounge_connect_failed", failure = it) }
                .getOrNull()
            main.post {
                if (released) { s?.close(); return@post }
                session = s
                if (s != null) startPoll(s)
                onReady(s != null)
            }
        }
    }

    private fun startPoll(s: LoungeApi.Session) {
        pollThread?.interrupt()
        pollThread = Thread({
            var failures = 0
            while (!released && session === s) {
                try {
                    s.subscribe { type, payload -> main.post { if (!released && session === s) onEvent(type, payload) } }
                    failures = 0
                } catch (e: Exception) {
                    if (released) return@Thread
                    failures++
                    AppLog.event(AppLog.Category.CAST, "lounge_poll_failed", mapOf("failures" to failures), failure = e)
                    if (e is LoungeApi.LoungeException && e.code in setOf(400, 401, 410) || failures >= 3) {
                        main.post { if (session === s) lost("poll") }
                        return@Thread
                    }
                    Thread.sleep(1_000L * failures)
                }
            }
        }, "LoungePoll").apply { isDaemon = true; start() }
    }

    private fun onEvent(type: String, payload: JsonObject?) {
        when (type) {
            "onStateChange", "nowPlaying" -> {
                val id = payload?.str("videoId")
                if (type == "nowPlaying" && id != null) {
                    if (pendingVideoId != null && id != pendingVideoId) return
                    pendingVideoId = null
                    if (id != videoId) { videoId = id; rebuildItem() }
                } else if (pendingVideoId != null && type == "onStateChange") {
                    // No id on state changes: still the old video until nowPlaying confirms.
                    return
                }
                payload?.str("currentTime")?.toDoubleOrNull()?.let { positionMs = (it * 1000).toLong(); sampledAt = SystemClock.elapsedRealtime() }
                payload?.str("duration")?.toDoubleOrNull()?.takeIf { it > 0 }?.let { durationMs = (it * 1000).toLong() }
                when (payload?.str("state")?.toIntOrNull()) {
                    1 -> { playing = true; wanted = true; state = STATE_READY }
                    2 -> { playing = false; wanted = false; state = STATE_READY }
                    3 -> { playing = false; state = STATE_BUFFERING }
                    0 -> { playing = false; wanted = false; state = STATE_ENDED }
                    -1, 5 -> { playing = false; state = if (item != null) STATE_BUFFERING else STATE_IDLE }
                    else -> {}
                }
                if (state == STATE_ENDED && repeat == REPEAT_MODE_ONE) { videoId?.let { load(it, item?.mediaMetadata?.title?.toString(), null, 0) }; return }
                invalidateState()
            }
            "onPlaybackSpeedChanged" -> payload?.str("playbackSpeed")?.toFloatOrNull()?.let { speed = it; invalidateState() }
            "loungeScreenDisconnected", "loungeScreenDisconnectedForUnexpectedReason" -> lost(type)
        }
    }

    private fun lost(reason: String) {
        if (released) return
        AppLog.event(AppLog.Category.CAST, "lounge_session_lost", mapOf("reason" to reason))
        fail("TV disconnected")
        onDisconnected(reason)
    }

    fun load(videoId: String, title: String?, author: String?, startMs: Long) {
        val s = session ?: return
        this.videoId = videoId
        pendingVideoId = videoId
        item = MediaItem.Builder().setMediaId(videoId).setUri("https://www.youtube.com/watch?v=$videoId")
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(author).build()).build()
        positionMs = startMs.coerceAtLeast(0); sampledAt = SystemClock.elapsedRealtime()
        durationMs = 0L; playing = false; wanted = true; error = null
        state = STATE_BUFFERING
        invalidateState()
        io.execute {
            runCatching { s.setPlaylist(videoId, startMs / 1000) }
                .onFailure { e ->
                    AppLog.event(AppLog.Category.CAST, "lounge_load_failed", failure = e)
                    main.post { if (session === s) { fail("Load failed"); onDisconnected("load") } }
                }
        }
    }

    private fun rebuildItem() {
        val id = videoId ?: return
        val old = item?.mediaMetadata
        item = MediaItem.Builder().setMediaId(id).setUri("https://www.youtube.com/watch?v=$id")
            .setMediaMetadata(old ?: MediaMetadata.EMPTY).build()
    }

    fun currentVideoId(): String? = videoId

    fun fail(reason: String) {
        playing = false
        wanted = false
        // SimpleBasePlayer only accepts a player error in STATE_IDLE.
        state = STATE_IDLE
        error = PlaybackException(reason, null, PlaybackException.ERROR_CODE_REMOTE_ERROR)
        invalidateState()
    }

    fun reset() {
        playing = false; wanted = false; item = null; videoId = null; pendingVideoId = null
        state = STATE_IDLE; error = null
        invalidateState()
    }

    /** Ends the TV session ("disconnected" on the TV) and kills the poll. */
    fun shutdown(stopVideo: Boolean) {
        released = true
        val s = session
        session = null
        pollThread?.interrupt()
        io.execute {
            if (s != null) {
                if (stopVideo) runCatching { s.stopVideo() }
                s.close()
            }
        }
        io.shutdown()
    }

    private fun currentPosition() = (positionMs + if (playing) ((SystemClock.elapsedRealtime() - sampledAt) * speed).toLong() else 0L)
        .coerceIn(0, durationMs.coerceAtLeast(positionMs))

    override fun getState(): State {
        val playlist = item?.let { listOf(MediaItemData.Builder(it.mediaId).setMediaItem(it)
            .setDurationUs(if (durationMs > 0) durationMs * 1000 else C.TIME_UNSET).setIsSeekable(true).build()) }.orEmpty()
        return State.Builder().setAvailableCommands(Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE, Player.COMMAND_STOP,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_GET_METADATA, Player.COMMAND_SET_REPEAT_MODE, Player.COMMAND_SET_SPEED_AND_PITCH,
            Player.COMMAND_RELEASE,
        ).build()).setPlaylist(playlist).setPlaybackState(state)
            .setPlayWhenReady(wanted, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setRepeatMode(repeat).setPlaybackParameters(PlaybackParameters(speed)).setPlayerError(error)
            .setContentPositionMs(PositionSupplier { currentPosition() }).build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        wanted = playWhenReady
        val s = session ?: return Futures.immediateVoidFuture()
        if (playWhenReady && state == STATE_ENDED) {
            videoId?.let { load(it, item?.mediaMetadata?.title?.toString(), item?.mediaMetadata?.artist?.toString(), 0) }
            return Futures.immediateVoidFuture()
        }
        positionMs = currentPosition(); sampledAt = SystemClock.elapsedRealtime()
        playing = playWhenReady
        io.execute { runCatching { if (playWhenReady) s.play() else s.pause() } }
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val s = session ?: return Futures.immediateVoidFuture()
        val target = positionMs.takeIf { it != C.TIME_UNSET } ?: 0L
        this.positionMs = target.coerceAtLeast(0); sampledAt = SystemClock.elapsedRealtime()
        io.execute { runCatching { s.seekTo(target / 1000.0) } }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        repeat = repeatMode
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        positionMs = currentPosition(); sampledAt = SystemClock.elapsedRealtime()
        speed = playbackParameters.speed.coerceIn(0.25f, 2f)
        session?.let { s -> io.execute { runCatching { s.setPlaybackSpeed(speed) } } }
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        state = STATE_IDLE; playing = false; wanted = false
        session?.let { s -> io.execute { runCatching { s.stopVideo() } } }
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        shutdown(stopVideo = false)
        return Futures.immediateVoidFuture()
    }
}
