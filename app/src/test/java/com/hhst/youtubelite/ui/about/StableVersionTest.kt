package com.hhst.youtubelite.ui.about
import org.junit.Assert.*
import org.junit.Test
class StableVersionTest {
    @Test fun comparesComponentsAndSeparatesDevelopmentChannel() {
        assertTrue(StableVersion.isDevelopment("3.0.0-devx-debug"))
        assertFalse(StableVersion.isDevelopment("2.1.4"))
        assertTrue(StableVersion.isNewer("2.9.9", "v2.10.0"))
        assertFalse(StableVersion.isNewer("3.0.0-devx", "v2.1.4"))
        assertFalse(StableVersion.isNewer("2.1.4", "v2.1.4"))
    }

    @Test fun forkRevisionIsAFourthComponent() {
        assertTrue(StableVersion.isNewer("3.0.3.1", "v3.0.3.2"))
        assertFalse(StableVersion.isNewer("3.0.3.2", "v3.0.3.1"))
        assertTrue(StableVersion.isNewer("3.0.3.9", "v3.0.4.1"))
        assertFalse(StableVersion.isNewer("3.0.3.1", "v3.0.3.1"))
        // An upstream-style three-part tag compares as revision 0.
        assertFalse(StableVersion.isNewer("3.0.3.1", "v3.0.3"))
    }
}
