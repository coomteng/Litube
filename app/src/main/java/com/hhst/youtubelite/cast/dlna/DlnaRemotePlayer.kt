package com.hhst.youtubelite.cast.dlna

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
import com.hhst.youtubelite.diagnostics.AppLog
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Media3 adapter over a [DlnaRenderer]. Commands are serialized on one IO
 * thread; state is polled (GetTransportInfo + GetPositionInfo) every
 * [POLL_MS] because AVTransport eventing (GENA) would need an HTTP callback
 * server. Renderers report STOPPED briefly while loading, so STOPPED only
 * counts as ENDED once playback was seen running and the position is near
 * the end (or [STOP_GRACE_MS] has elapsed without any PLAYING).
 */
@UnstableApi
internal class DlnaRemotePlayer(
    private val renderer: DlnaRenderer,
    /**
     * Terminal failure. [reason] "load" / "no_start" / "stalled" happen before
     * or shortly after the first start and are candidates for a media
     * fallback by the owner (e.g. video → audio); "poll" / "error" are not.
     */
    private val onFatal: (String) -> Unit,
) : SimpleBasePlayer(Looper.getMainLooper()) {
    private val main = Handler(Looper.getMainLooper())
    private val io: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "DlnaPlayer").apply { isDaemon = true } }
    private var item: MediaItem? = null
    private var media: DlnaMedia? = null
    private var durationMs = 0L
    private var positionMs = 0L
    private var sampledAt = SystemClock.elapsedRealtime()
    private var state = STATE_IDLE
    private var playing = false
    private var wanted = false
    private var repeat = REPEAT_MODE_OFF
    private var error: PlaybackException? = null
    private var everPlaying = false
    private var loadedAt = 0L
    private var released = false
    @Volatile private var generation = 0L
    private val poll = object : Runnable {
        override fun run() {
            if (released || item == null) return
            val gen = generation
            io.execute {
                val t = runCatching { renderer.transportInfo() }.getOrNull()
                val p = if (t != null) runCatching { renderer.positionInfo() }.getOrNull() else null
                main.post { if (gen == generation && !released) applyPoll(t, p) }
            }
            main.postDelayed(this, POLL_MS)
        }
    }

    /** SetAVTransportURI + Play, asynchronously. [startMs] is accepted for API symmetry but ignored (see below). */
    fun load(media: DlnaMedia, mediaId: String, @Suppress("UNUSED_PARAMETER") startMs: Long) {
        val gen = ++generation
        if (this.media !== media || mediaId != item?.mediaId) recoveredOnce = false
        this.media = media
        item = MediaItem.Builder().setMediaId(mediaId).setUri(media.url).setMimeType(media.mimeType)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(media.title).setArtist(media.author).build()).build()
        durationMs = media.durationMs
        positionMs = 0L
        sampledAt = SystemClock.elapsedRealtime()
        playing = false; wanted = true; error = null; everPlaying = false
        loadedAt = SystemClock.elapsedRealtime()
        stalledPolls = 0; lastPolledPosition = -1L
        state = STATE_BUFFERING
        invalidateState()
        io.execute {
            val result = runCatching {
                runCatching { renderer.stop() } // some renderers refuse SetAVTransportURI while PLAYING
                renderer.setMedia(media)
                renderer.play()
                // No start-position Seek here: HUAWEI Sound and LG webOS drop
                // to STOPPED when a REL_TIME Seek lands within seconds of Play
                // on a fresh URI. Playback starts at 0; a user seek once the
                // renderer reports PLAYING is honoured by handleSeek.
            }
            main.post {
                if (gen != generation || released) return@post
                result.onFailure { failure ->
                    AppLog.event(AppLog.Category.CAST, "dlna_load_failed", failure = failure)
                    fail("DLNA load failed: ${failure.message}")
                    onFatal("load")
                }.onSuccess {
                    main.removeCallbacks(poll)
                    main.postDelayed(poll, FIRST_POLL_MS)
                }
            }
        }
    }

    private fun applyPoll(t: DlnaTransportInfo?, p: DlnaPositionInfo?) {
        if (t == null) {
            consecutivePollFailures++
            if (consecutivePollFailures >= MAX_POLL_FAILURES) { fail("Renderer unreachable"); onFatal("poll") }
            return
        }
        consecutivePollFailures = 0
        if (p != null) {
            if (p.durationMs > 0) durationMs = p.durationMs
            // Position is authoritative only while the renderer plays our URI;
            // during the first seconds TrackURI may still be the previous item.
            if (p.positionMs > 0 || playing) { positionMs = p.positionMs; sampledAt = SystemClock.elapsedRealtime() }
        }
        val now = SystemClock.elapsedRealtime()
        when (t.state) {
            "PLAYING" -> { everPlaying = true; playing = true; wanted = true; state = STATE_READY }
            "PAUSED_PLAYBACK", "PAUSED_RECORDING" -> { playing = false; wanted = false; state = STATE_READY }
            "TRANSITIONING" -> {
                playing = false; state = STATE_BUFFERING
                if (!everPlaying && now - loadedAt > (media?.startGraceMs ?: DEFAULT_START_GRACE_MS)) {
                    fail("Renderer did not start"); onFatal("no_start"); return
                }
            }
            "STOPPED", "NO_MEDIA_PRESENT" -> {
                playing = false
                val nearEnd = durationMs > 0 && positionMs >= durationMs - END_TOLERANCE_MS
                if (everPlaying && (nearEnd || positionMs == 0L)) {
                    state = STATE_ENDED; wanted = false
                } else if (!everPlaying && now - loadedAt > (media?.startGraceMs ?: DEFAULT_START_GRACE_MS)) {
                    fail("Renderer did not start"); onFatal("no_start"); return
                } else if (everPlaying) {
                    // Stopped mid-stream by the TV remote: treat as ended-by-user.
                    state = STATE_ENDED; wanted = false
                } else state = STATE_BUFFERING
            }
            else -> {}
        }
        if (t.status.equals("ERROR_OCCURRED", true)) { fail("Renderer error"); onFatal("error"); return }
        // Some renderers (HUAWEI Sound after a Seek) report PLAYING forever
        // with a frozen position and a dropped stream connection. Reload from
        // zero once; a second stall is fatal.
        if (t.state == "PLAYING" && p != null) {
            if (p.positionMs == lastPolledPosition) stalledPolls++ else stalledPolls = 0
            lastPolledPosition = p.positionMs
            if (stalledPolls >= STALL_POLLS) {
                stalledPolls = 0
                val m = media
                if (!recoveredOnce && m != null) {
                    recoveredOnce = true
                    AppLog.event(AppLog.Category.CAST, "dlna_stall_reload", mapOf("position_ms" to p.positionMs))
                    load(m, item?.mediaId ?: "", 0)
                } else {
                    fail("Renderer stalled"); onFatal("stalled")
                }
                return
            }
        }
        if (state == STATE_ENDED && repeat == REPEAT_MODE_ONE) { media?.let { load(it, item?.mediaId ?: "", 0) }; return }
        invalidateState()
    }
    private var consecutivePollFailures = 0
    /** Polls in a row that reported PLAYING with an unchanged position. */
    private var stalledPolls = 0
    private var lastPolledPosition = -1L
    /** One reload-from-zero per media after a stall or a failed start-seek. */
    private var recoveredOnce = false

    fun currentMedia(): DlnaMedia? = media

    fun fail(reason: String) {
        playing = false
        wanted = false
        // SimpleBasePlayer only accepts a player error in STATE_IDLE.
        state = STATE_IDLE
        error = PlaybackException(reason, null, PlaybackException.ERROR_CODE_REMOTE_ERROR)
        invalidateState()
    }

    fun reset() {
        generation++
        main.removeCallbacks(poll)
        playing = false; wanted = false; item = null; media = null
        state = STATE_IDLE; error = null
        invalidateState()
    }

    /** Stops the renderer and the poll loop; the instance is dead afterwards. */
    fun shutdown() {
        released = true
        generation++
        main.removeCallbacks(poll)
        io.execute { runCatching { renderer.stop() } }
        io.shutdown()
    }

    private fun currentPosition() = (positionMs + if (playing) SystemClock.elapsedRealtime() - sampledAt else 0L)
        .coerceIn(0, durationMs.coerceAtLeast(positionMs))

    override fun getState(): State {
        val playlist = item?.let { listOf(MediaItemData.Builder(it.mediaId).setMediaItem(it)
            .setDurationUs(if (durationMs > 0) durationMs * 1000 else C.TIME_UNSET).setIsSeekable(true).build()) }.orEmpty()
        return State.Builder().setAvailableCommands(Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE, Player.COMMAND_STOP,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_GET_METADATA, Player.COMMAND_SET_REPEAT_MODE, Player.COMMAND_RELEASE,
        ).build()).setPlaylist(playlist).setPlaybackState(state)
            .setPlayWhenReady(wanted, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setRepeatMode(repeat).setPlaybackParameters(PlaybackParameters.DEFAULT).setPlayerError(error)
            .setContentPositionMs(PositionSupplier { currentPosition() }).build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        wanted = playWhenReady
        if (playWhenReady && state == STATE_ENDED) {
            media?.let { load(it, item?.mediaId ?: "", 0) }
            return Futures.immediateVoidFuture()
        }
        // Optimistic local state; the next poll confirms.
        playing = playWhenReady
        positionMs = currentPosition(); sampledAt = SystemClock.elapsedRealtime()
        io.execute { runCatching { if (playWhenReady) renderer.play() else renderer.pause() } }
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val target = positionMs.takeIf { it != C.TIME_UNSET } ?: 0L
        if (state == STATE_ENDED) { media?.let { load(it, item?.mediaId ?: "", target) }; return Futures.immediateVoidFuture() }
        this.positionMs = target.coerceAtLeast(0)
        sampledAt = SystemClock.elapsedRealtime()
        io.execute { runCatching { renderer.seek(target) } }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        repeat = repeatMode
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        generation++
        main.removeCallbacks(poll)
        state = STATE_IDLE; playing = false; wanted = false
        io.execute { runCatching { renderer.stop() } }
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        shutdown()
        return Futures.immediateVoidFuture()
    }

    companion object {
        const val POLL_MS = 1_000L
        const val FIRST_POLL_MS = 1_500L
        const val DEFAULT_START_GRACE_MS = 20_000L
        /** For media the renderer did not declare support for (DASH probe). */
        const val PROBE_START_GRACE_MS = 9_000L
        const val END_TOLERANCE_MS = 3_000L
        const val MAX_POLL_FAILURES = 8
        const val STALL_POLLS = 5
    }
}
