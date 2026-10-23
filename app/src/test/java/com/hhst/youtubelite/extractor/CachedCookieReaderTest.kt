package com.hhst.youtubelite.extractor

import org.junit.Assert.assertEquals
import org.junit.Test

class CachedCookieReaderTest {
    @Test fun readsAreCoalescedWithinTheTtlAndRefreshedAfterIt() {
        var now = 1_000L
        var reads = 0
        var value = "SAPISID=a"
        val reader = CachedCookieReader(ttlMs = 1_000, clock = { now }) { reads++; value }
        // A 2.4 MB playlist is pulled in ~300 reads of 8 KiB; every read used to ask CookieManager (~2 ms each).
        repeat(300) { assertEquals("SAPISID=a", reader.get()) }
        assertEquals(1, reads)
        value = "SAPISID=b"
        assertEquals("SAPISID=a", reader.get())
        now += 1_000
        assertEquals("SAPISID=b", reader.get())
        assertEquals(2, reads)
    }

    @Test fun invalidateForcesTheNextReadThrough() {
        var reads = 0
        val reader = CachedCookieReader(ttlMs = 60_000, clock = { 0L }) { reads++; "x" }
        reader.get(); reader.invalidate(); reader.get()
        assertEquals(2, reads)
    }
}
