package com.hhst.youtubelite.cast.dlna

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DlnaTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    private val description = """
        <?xml version="1.0"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0">
          <specVersion><major>1</major><minor>0</minor></specVersion>
          <device>
            <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
            <friendlyName>HUAWEI Sound-0174</friendlyName>
            <manufacturer>HUAWEI</manufacturer>
            <modelName>Sound</modelName>
            <UDN>uuid:abc-123</UDN>
            <serviceList>
              <service>
                <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
                <controlURL>/upnp/control/rendercontrol1</controlURL>
              </service>
              <service>
                <serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>
                <controlURL>/upnp/control/connectionmanager1</controlURL>
              </service>
              <service>
                <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                <controlURL>upnp/control/rendertransport1</controlURL>
              </service>
            </serviceList>
          </device>
        </root>
    """.trimIndent()

    @Test
    fun deviceParse_resolvesControlUrls() {
        val d = DlnaDevice.parse(description, "http://192.168.0.145:49494/description.xml")!!
        assertEquals("HUAWEI Sound-0174", d.friendlyName)
        assertEquals("uuid:abc-123", d.udn)
        assertEquals("http://192.168.0.145:49494/upnp/control/rendertransport1", d.avTransportUrl)
        assertEquals("http://192.168.0.145:49494/upnp/control/connectionmanager1", d.connectionManagerUrl)
        assertEquals("HUAWEI", d.manufacturer)
    }

    @Test
    fun deviceParse_urlBaseWins_andNonRendererIsNull() {
        val withBase = description.replace("<device>", "<URLBase>http://10.0.0.2:8080/</URLBase><device>")
        assertEquals("http://10.0.0.2:8080/upnp/control/rendertransport1", DlnaDevice.parse(withBase, "http://x/d.xml")!!.avTransportUrl)
        val noAv = description.replace("urn:schemas-upnp-org:service:AVTransport:1", "urn:foo:1")
        assertNull(DlnaDevice.parse(noAv, "http://x/d.xml"))
    }

    @Test
    fun setMedia_sendsSoapWithEscapedDidl() {
        server.enqueue(MockResponse().setBody("<s:Envelope><s:Body><u:SetAVTransportURIResponse/></s:Body></s:Envelope>"))
        val device = DlnaDevice("u", "n", server.url("/av").toString(), null)
        val renderer = DlnaRenderer(device)
        renderer.setMedia(DlnaMedia(
            url = "http://192.168.0.5:4321/k/stream/a?g=3&x=1", mimeType = "audio/mp4",
            title = "Tom & Jerry <live>", author = "Ch", artworkUrl = "http://i/x.jpg", durationMs = 61_000, isVideo = false,
        ))
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("\"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\"", req.getHeader("SOAPAction"))
        assertTrue(req.getHeader("Content-Type")!!.startsWith("text/xml"))
        val body = req.body.readUtf8()
        assertTrue(body.contains("<InstanceID>0</InstanceID>"))
        assertTrue(body.contains("<CurrentURI>http://192.168.0.5:4321/k/stream/a?g=3&amp;x=1</CurrentURI>"))
        // DIDL is escaped once inside the SOAP element.
        assertTrue(body.contains("<CurrentURIMetaData>&lt;DIDL-Lite"))
        assertTrue(body.contains("&lt;dc:title&gt;Tom &amp;amp; Jerry &amp;lt;live&amp;gt;&lt;/dc:title&gt;"))
        assertTrue(body.contains("object.item.audioItem.musicTrack"))
        assertTrue(body.contains("duration=&quot;0:01:01&quot;"))
        assertFalse(body.contains("<DIDL-Lite"))
    }

    @Test
    fun positionInfo_parsesTimes_andSoapFaultThrows() {
        server.enqueue(MockResponse().setBody(
            "<s:Envelope xmlns:s=\"x\"><s:Body><u:GetPositionInfoResponse xmlns:u=\"y\">" +
                "<Track>1</Track><TrackDuration>0:03:25</TrackDuration><TrackURI>http://a/b?c=1&amp;d=2</TrackURI>" +
                "<RelTime>0:01:02.500</RelTime></u:GetPositionInfoResponse></s:Body></s:Envelope>",
        ))
        server.enqueue(MockResponse().setResponseCode(500).setBody(
            "<s:Envelope><s:Body><s:Fault><detail><UPnPError><errorCode>716</errorCode>" +
                "<errorDescription>Resource not found</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>",
        ))
        val renderer = DlnaRenderer(DlnaDevice("u", "n", server.url("/av").toString(), null))
        val p = renderer.positionInfo()
        assertEquals(62_500L, p.positionMs)
        assertEquals(205_000L, p.durationMs)
        assertEquals("http://a/b?c=1&d=2", p.trackUri)
        try {
            renderer.play()
            throw AssertionError("expected fault")
        } catch (e: DlnaRenderer.SoapException) {
            assertEquals(716, e.code)
        }
    }

    @Test
    fun seek_usesRelTimeHms() {
        server.enqueue(MockResponse().setBody("<ok/>"))
        val renderer = DlnaRenderer(DlnaDevice("u", "n", server.url("/av").toString(), null))
        renderer.seek(3_725_000)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("<Unit>REL_TIME</Unit><Target>1:02:05</Target>"))
    }

    @Test
    fun sinkProtocols_parsesList_andEmptyWithoutConnectionManager() {
        server.enqueue(MockResponse().setBody(
            "<s:Envelope><s:Body><u:GetProtocolInfoResponse><Source></Source>" +
                "<Sink>http-get:*:audio/mpeg:*,http-get:*:video/mp4:*, http-get:*:application/dash+xml:*</Sink>" +
                "</u:GetProtocolInfoResponse></s:Body></s:Envelope>",
        ))
        val withCm = DlnaRenderer(DlnaDevice("u", "n", server.url("/av").toString(), server.url("/cm").toString()))
        assertEquals(listOf("http-get:*:audio/mpeg:*", "http-get:*:video/mp4:*", "http-get:*:application/dash+xml:*"), withCm.sinkProtocols())
        val without = DlnaRenderer(DlnaDevice("u", "n", server.url("/av").toString(), null))
        assertTrue(without.sinkProtocols().isEmpty())
    }

    @Test
    fun hms_roundTrip() {
        assertEquals("0:00:00", Didl.hms(0))
        assertEquals("1:00:00", Didl.hms(3_600_000))
        assertEquals(0L, Didl.parseHms("NOT_IMPLEMENTED"))
        assertEquals(90_000L, Didl.parseHms("01:30"))
        assertEquals(5_000L, Didl.parseHms("0:00:05"))
    }
}
