package com.hhst.youtubelite.extractor

import android.os.SystemClock

/**
 * Short-lived cache in front of a cookie-store read.
 *
 * `CookieManager.getCookie` is a binder round-trip into the WebView provider (~2 ms on a 2017
 * phone). The media path asks "is this session still current?" on every `read()` of every data
 * source, i.e. hundreds of times per playlist; the answer cannot change faster than a user can
 * sign in or out, so a sub-second TTL keeps the account fence intact while taking the IPC off the
 * hot path. [invalidate] is for callers that just changed cookies themselves.
 */
internal class CachedCookieReader(
    private val ttlMs: Long = 500,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val read: () -> String,
) {
    private var value = ""
    private var readAt = Long.MIN_VALUE

    @Synchronized fun get(): String {
        val now = clock()
        if (readAt == Long.MIN_VALUE || now - readAt >= ttlMs) { value = read(); readAt = now }
        return value
    }

    @Synchronized fun invalidate() { readAt = Long.MIN_VALUE }
}
