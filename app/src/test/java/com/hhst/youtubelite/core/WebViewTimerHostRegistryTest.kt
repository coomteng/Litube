package com.hhst.youtubelite.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebViewTimerHostRegistryTest {

    private val calls = mutableListOf<String>()
    private val registry = WebViewTimerHostRegistry<String> { host, suspended ->
        calls += "$host:${if (suspended) "pause" else "resume"}"
    }

    @Test
    fun pauseThroughOneHost_resumeThroughSurvivor() {
        // The regression: pause went through the PoToken WebView, which was then
        // reaped during a long background stay; resume must still reach a renderer.
        registry.attach("browser")
        registry.attach("potoken")
        registry.setSuspended(true)
        assertEquals(listOf("potoken:pause"), calls)
        registry.detach("potoken")
        registry.setSuspended(false)
        assertEquals(listOf("potoken:pause", "browser:resume"), calls)
        assertFalse(registry.wantsSuspended)
    }

    @Test
    fun suspendedWithNoHosts_replaysOnNextAttach() {
        registry.setSuspended(true)
        assertTrue(calls.isEmpty())
        assertTrue(registry.wantsSuspended)
        registry.attach("browser")
        assertEquals(listOf("browser:pause"), calls)
    }

    @Test
    fun runningState_isNotReplayedOnAttach() {
        registry.setSuspended(true)
        registry.setSuspended(false)
        registry.attach("browser")
        assertTrue("a fresh WebView already runs timers; no redundant resume", calls.isEmpty())
    }

    @Test
    fun detachUnknownHost_isHarmless_andCountTracksAttach() {
        registry.detach("ghost")
        registry.attach("a")
        registry.attach("a")
        assertEquals(1, registry.hostCount)
        registry.detach("a")
        assertEquals(0, registry.hostCount)
    }
}
