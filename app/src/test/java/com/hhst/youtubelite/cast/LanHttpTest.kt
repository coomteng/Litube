package com.hhst.youtubelite.cast

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class LanHttpTest {

    @Test
    fun readResponse_contentLength_andHeadersCaseInsensitive() {
        val raw = "HTTP/1.1 200 OK\r\nContent-Type: text/xml; charset=\"utf-8\"\r\nApplication-URL: http://h:8008/apps/\r\nContent-Length: 5\r\n\r\nhello"
        val r = LanHttp.readResponse(ByteArrayInputStream(raw.toByteArray()))
        assertEquals(200, r.code)
        assertEquals("hello", r.body)
        assertEquals("http://h:8008/apps/", r.header("application-url"))
        assertEquals("http://h:8008/apps/", r.header("Application-URL"))
    }

    @Test
    fun readResponse_chunked() {
        val raw = "HTTP/1.1 500 Internal Server Error\r\nTransfer-Encoding: chunked\r\n\r\n4\r\n<a>x\r\n3\r\n</a\r\n1\r\n>\r\n0\r\n\r\n"
        val r = LanHttp.readResponse(ByteArrayInputStream(raw.toByteArray()))
        assertEquals(500, r.code)
        assertEquals("<a>x</a>", r.body)
    }

    @Test
    fun readResponse_noLength_readsToEnd_lfOnlyLines() {
        val raw = "HTTP/1.0 201 Created\nLocation: http://h/apps/YouTube/run\n\nbody"
        val r = LanHttp.readResponse(ByteArrayInputStream(raw.toByteArray()))
        assertEquals(201, r.code)
        assertEquals("body", r.body)
        assertEquals("http://h/apps/YouTube/run", r.header("Location"))
    }

    @Test
    fun get_and_post_againstRealServer() {
        val server = MockWebServer().also { it.start() }
        try {
            server.enqueue(MockResponse().setBody("<x/>").setHeader("Application-URL", "http://x/apps/"))
            server.enqueue(MockResponse().setResponseCode(201).setBody(""))
            val get = LanHttp.get(server.url("/dd.xml?a=1").toString())
            assertTrue(get.isSuccessful)
            assertEquals("<x/>", get.body)
            val req = server.takeRequest()
            assertEquals("GET /dd.xml?a=1 HTTP/1.1", req.requestLine)
            assertEquals("close", req.getHeader("Connection"))
            val post = LanHttp.post(server.url("/apps/YouTube").toString(), "pairingCode=1".toByteArray(), "text/plain", mapOf("Origin" to "https://www.youtube.com"))
            assertEquals(201, post.code)
            val preq = server.takeRequest()
            assertEquals("POST", preq.method)
            assertEquals("https://www.youtube.com", preq.getHeader("Origin"))
            assertEquals("13", preq.getHeader("Content-Length"))
            assertEquals("pairingCode=1", preq.body.readUtf8())
        } finally {
            server.shutdown()
        }
    }
}
