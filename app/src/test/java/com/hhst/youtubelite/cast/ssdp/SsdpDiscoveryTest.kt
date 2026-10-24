package com.hhst.youtubelite.cast.ssdp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SsdpDiscoveryTest {

    @Test
    fun parse_mediaRendererResponse() {
        val raw = "HTTP/1.1 200 OK\r\n" +
            "CACHE-CONTROL: max-age=1800\r\n" +
            "EXT:\r\n" +
            "Location: http://192.168.0.9:49152/uuid-071d/description.xml\r\n" +
            "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
            "USN: uuid:071d0dd7::urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"
        val r = SsdpDiscovery.parse(raw, "192.168.0.9")!!
        assertEquals("http://192.168.0.9:49152/uuid-071d/description.xml", r.location)
        assertEquals(SsdpDiscovery.ST_MEDIA_RENDERER, r.searchTarget)
        assertEquals("uuid:071d0dd7::urn:schemas-upnp-org:device:MediaRenderer:1", r.usn)
        assertEquals("192.168.0.9", r.host)
    }

    @Test
    fun parse_rejectsNonOkAndMissingLocation() {
        assertNull(SsdpDiscovery.parse("NOTIFY * HTTP/1.1\r\nLOCATION: http://x/\r\n\r\n", "1.2.3.4"))
        assertNull(SsdpDiscovery.parse("HTTP/1.1 200 OK\r\nST: foo\r\n\r\n", "1.2.3.4"))
    }

    @Test
    fun parse_usnFallsBackToLocation() {
        val r = SsdpDiscovery.parse("HTTP/1.1 200 OK\r\nLOCATION: http://h/d.xml\r\n\r\n", "h")!!
        assertEquals("http://h/d.xml", r.usn)
    }

    @Test
    fun mSearch_format() {
        val m = SsdpDiscovery.mSearch(SsdpDiscovery.ST_DIAL)
        assertTrue(m.startsWith("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n"))
        assertTrue(m.contains("MAN: \"ssdp:discover\"\r\n"))
        assertTrue(m.contains("ST: urn:dial-multiscreen-org:service:dial:1\r\n"))
        assertTrue(m.endsWith("\r\n\r\n"))
    }
}
