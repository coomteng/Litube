package com.hhst.youtubelite.core

/**
 * Tracks the live WebViews that can carry the process-global pause/resume
 * timer call, plus the state the process is supposed to be in.
 *
 * `WebView.pauseTimers()` / `resumeTimers()` flip one renderer-wide flag, but
 * Chromium silently drops the call when the receiving WebView has already been
 * destroyed. Routing the call through whichever host is still alive, and
 * replaying the wanted state when a new host appears, keeps a destroyed carrier
 * from leaving every page's `setTimeout` frozen after the app returns to the
 * foreground.
 */
class WebViewTimerHostRegistry<H : Any>(
    private val apply: (host: H, suspended: Boolean) -> Unit,
) {
    private val lock = Any()
    private val hosts = LinkedHashSet<H>()
    private var suspended = false

    val wantsSuspended: Boolean
        get() = synchronized(lock) { suspended }

    val hostCount: Int
        get() = synchronized(lock) { hosts.size }

    /**
     * Registers [host]. A host that arrives while timers are meant to be
     * suspended gets the pause re-applied so the process state stays coherent
     * no matter which WebView the earlier call went through.
     */
    fun attach(host: H) {
        val replay: Boolean
        synchronized(lock) {
            hosts.add(host)
            replay = suspended
        }
        if (replay) apply(host, true)
    }

    fun detach(host: H) {
        synchronized(lock) { hosts.remove(host) }
    }

    fun setSuspended(value: Boolean) {
        val carrier: H?
        synchronized(lock) {
            suspended = value
            carrier = hosts.lastOrNull()
        }
        // No host alive means no renderer with timers to pause; the next
        // attach replays the wanted state.
        if (carrier != null) apply(carrier, value)
    }
}
