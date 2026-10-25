package com.hhst.youtubelite.cast

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.InetAddress

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

    @Test
    fun lanAddress_privateLinkLocalLoopbackOnly() {
        for (ip in listOf("10.0.0.1", "172.16.5.5", "172.31.255.1", "192.168.0.9", "169.254.1.1", "127.0.0.1", "100.64.0.1", "100.127.255.254",
                          "fe80::1", "fc00::1", "fd12:3456::1", "::1")) {
            assertTrue(ip, LanHttp.isLanAddress(InetAddress.getByName(ip)))
        }
        for (ip in listOf("8.8.8.8", "1.1.1.1", "172.32.0.1", "100.128.0.1", "100.63.255.255", "203.0.113.7", "2001:4860:4860::8888", "2606:4700::1")) {
            assertFalse(ip, LanHttp.isLanAddress(InetAddress.getByName(ip)))
        }
    }

    @Test
    fun request_refusesPublicHostBeforeConnecting() {
        // SSDP LOCATION pointing at the internet: must fail without a socket.
        try {
            LanHttp.get("http://203.0.113.7:8080/dd.xml", connectTimeoutMs = 50)
            fail("expected refusal")
        } catch (e: IOException) {
            assertTrue(e.message, e.message!!.contains("not a LAN address"))
        }
    }
}
