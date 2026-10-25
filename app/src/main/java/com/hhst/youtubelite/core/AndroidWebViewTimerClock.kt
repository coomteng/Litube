package com.hhst.youtubelite.core

import android.webkit.WebView

/**
 * Carries the process-global pause/resume through whichever registered
 * WebView is still alive. Every owner that creates a WebView must [attach]
 * it and [detach] it before `destroy()`: Chromium silently ignores
 * pause/resume on a destroyed WebView, which once left the whole process
 * with frozen JS timers after a background idle.
 */
class AndroidWebViewTimerClock(
    private val main: WebViewMainGate = AndroidWebViewMainGate(),
) : WebViewTimerClock {
    // Fire-and-forget hop: a blocking hop here deadlocked App-startup mints
    // against the gate's main-thread timeout. Ordering is the occupancy
    // lock's job.
    private val registry = WebViewTimerHostRegistry<WebView> { host, suspended ->
        main.post { if (suspended) host.pauseTimers() else host.resumeTimers() }
    }

    override fun attach(host: Any) {
        registry.attach(host as? WebView ?: return)
    }

    override fun detach(host: Any) {
        registry.detach(host as? WebView ?: return)
    }

    override fun pauseTimers() = registry.setSuspended(true)

    override fun resumeTimers() = registry.setSuspended(false)
}
