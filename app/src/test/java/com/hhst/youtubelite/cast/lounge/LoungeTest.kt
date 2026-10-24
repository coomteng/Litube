package com.hhst.youtubelite.cast.lounge

import com.hhst.youtubelite.cast.lounge.LoungeApi.Companion.str
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.URLDecoder

class LoungeTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    private val bindReply = "50\n[[0,[\"c\",\"SID123\",\"\",8]]\n,[1,[\"S\",\"GSESS456\"]]\n]\n" +
        "165\n[[2,[\"loungeStatus\",{\"devices\":\"[]\"}]]\n,[3,[\"onStateChange\",{\"state\":\"1\",\"currentTime\":\"12.5\",\"duration\":\"300\",\"cpn\":\"x\"}]]\n]\n"

    @Test
    fun parseSessionIds_fromChunkedBindReply() {
        // Recompute lengths so the fixture is self-consistent.
        val chunk1 = "[[0,[\"c\",\"SID123\",\"\",8]],[1,[\"S\",\"GSESS456\"]]]"
        val chunk2 = "[[2,[\"loungeStatus\",{\"devices\":\"[]\"}]],[3,[\"onStateChange\",{\"state\":\"1\",\"currentTime\":\"12.5\"}]]]"
        val body = "${chunk1.length}\n$chunk1${chunk2.length}\n$chunk2"
        val (sid, gsession, last) = LoungeApi.parseSessionIds(body)!!
        assertEquals("SID123", sid)
        assertEquals("GSESS456", gsession)
        assertEquals(3L, last)
    }

    @Test
    fun parseSessionIds_missing_isNull() {
        val chunk = "[[0,[\"noop\"]]]"
        assertNull(LoungeApi.parseSessionIds("${chunk.length}\n$chunk"))
    }

    @Test
    fun parseEvents_shapes() {
        val events = LoungeApi.parseEvents(
            "[[7,[\"nowPlaying\",{\"videoId\":\"dQw4w9WgXcQ\",\"state\":\"3\",\"currentTime\":\"0\"}]],[8,[\"noop\"]],[9,[\"S\",\"abc\"]]]",
        )
        assertEquals(3, events.size)
        assertEquals("nowPlaying", events[0].type)
        assertEquals("dQw4w9WgXcQ", events[0].payload!!.str("videoId"))
        assertEquals("noop", events[1].type)
        assertNull(events[1].payload)
        assertEquals("abc", events[2].payload!!.str("_0"))
    }

    @Test
    fun parseChunks_handlesMultiByteAndStopsOnSinkFalse() {
        val chunk = "[[1,[\"nowPlaying\",{\"title\":\"周杰伦\"}]]]"
        val body = "${chunk.length}\n$chunk${chunk.length}\n$chunk"
        var count = 0
        LoungeApi.parseChunks(body.reader().buffered()) { count += it.size; false }
        assertEquals(1, count)
    }

    @Test
    fun dialAppInfo_parse() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <service xmlns="urn:dial-multiscreen-org:schemas:dial" dialVer="2.1">
              <name>YouTube</name><options allowStop="true"/><state>running</state>
              <link rel="run" href="run"/>
              <additionalData><screenId>abc-def-123</screenId><sessionId>tok</sessionId></additionalData>
            </service>"""
        val info = DialClient.parseAppInfo(xml)
        assertEquals("running", info.state)
        assertEquals("abc-def-123", info.screenId)
        assertNull(DialClient.parseAppInfo("<service><state>stopped</state></service>").screenId)
    }

    @Test
    fun dialDescribe_requiresApplicationUrl() {
        server.enqueue(MockResponse().setHeader("Application-URL", server.url("/apps").toString())
            .setBody("<root><device><friendlyName>LG TV</friendlyName></device></root>"))
        server.enqueue(MockResponse().setBody("<root><device><friendlyName>Speaker</friendlyName></device></root>"))
        val client = DialClient()
        val screen = client.describe(server.url("/dd.xml").toString(), "uuid:1", "10.0.0.2")!!
        assertEquals("LG TV", screen.friendlyName)
        assertTrue(screen.applicationUrl.endsWith("/apps/"))
        assertNull(client.describe(server.url("/dd.xml").toString(), "uuid:2", "10.0.0.3"))
    }

    @Test
    fun connectAndSetPlaylist_sendsExpectedForm() {
        val chunk = "[[0,[\"c\",\"SID1\",\"\",8]],[1,[\"S\",\"G1\"]]]"
        server.enqueue(MockResponse().setBody("${chunk.length}\n$chunk"))
        server.enqueue(MockResponse().setBody("ok"))
        // Point the API at the mock server through a rewriting client.
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val original = chain.request()
            val rewritten = original.url.newBuilder().scheme("http").host(server.hostName).port(server.port).build()
            chain.proceed(original.newBuilder().url(rewritten).build())
        }.build()
        val api = LoungeApi(client, "Litube Plus")
        val session = api.connect(LoungeApi.Screen("scr", "tok", "TV"))
        assertEquals("SID1", session.sid)
        assertEquals("G1", session.gsessionid)
        val bind = server.takeRequest()
        assertEquals("/api/lounge/bc/bind", bind.requestUrl!!.encodedPath)
        assertEquals("1", bind.requestUrl!!.queryParameter("RID"))
        val bindForm = URLDecoder.decode(bind.body.readUtf8(), "UTF-8")
        assertTrue(bindForm.contains("loungeIdToken=tok"))
        assertTrue(bindForm.contains("name=Litube Plus"))

        session.setPlaylist("dQw4w9WgXcQ", 42)
        val cmd = server.takeRequest()
        val url = cmd.requestUrl!!
        assertEquals("SID1", url.queryParameter("SID"))
        assertEquals("G1", url.queryParameter("gsessionid"))
        assertEquals("tok", url.queryParameter("loungeIdToken"))
        assertEquals("2", url.queryParameter("RID"))
        val form = URLDecoder.decode(cmd.body.readUtf8(), "UTF-8")
        assertTrue(form.contains("count=1"))
        assertTrue(form.contains("ofs=0"))
        assertTrue(form.contains("req0__sc=setPlaylist"))
        assertTrue(form.contains("req0_videoId=dQw4w9WgXcQ"))
        assertTrue(form.contains("req0_currentTime=42"))
    }
}
