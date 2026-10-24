package com.hhst.youtubelite.cast

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI

/**
 * Minimal HTTP/1.1 client over a plain socket for LAN control traffic (UPnP
 * descriptions, SOAP, DIAL). The app ships `usesCleartextTraffic=false`, which
 * OkHttp and HttpURLConnection enforce; DLNA/DIAL are cleartext by protocol
 * and talk to private addresses only, so this bypasses the policy for them
 * without relaxing it for YouTube/CDN traffic. Not for large bodies.
 */
object LanHttp {
    class Response(val code: Int, val message: String, val headers: Map<String, String>, val body: String) {
        val isSuccessful: Boolean get() = code in 200..299
        fun header(name: String): String? = headers[name.lowercase()]
    }

    private const val MAX_BODY = 2 * 1024 * 1024
    private const val DEFAULT_UA = "Android/10 UPnP/1.0 LitubePlus/1.0"

    fun get(url: String, headers: Map<String, String> = emptyMap(), connectTimeoutMs: Int = 5_000, readTimeoutMs: Int = 10_000): Response =
        request("GET", url, headers, null, null, connectTimeoutMs, readTimeoutMs)

    fun post(url: String, body: ByteArray, contentType: String, headers: Map<String, String> = emptyMap(),
             connectTimeoutMs: Int = 5_000, readTimeoutMs: Int = 10_000): Response =
        request("POST", url, headers, body, contentType, connectTimeoutMs, readTimeoutMs)

    private fun request(
        method: String, url: String, headers: Map<String, String>, body: ByteArray?, contentType: String?,
        connectTimeoutMs: Int, readTimeoutMs: Int,
    ): Response {
        val uri = URI(url)
        if (!uri.scheme.equals("http", true)) throw IOException("LanHttp: only http:// is supported: $url")
        val host = uri.host ?: throw IOException("LanHttp: no host in $url")
        val port = if (uri.port > 0) uri.port else 80
        val path = buildString {
            append(uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/")
            uri.rawQuery?.let { append('?').append(it) }
        }
        val hostHeader = if (port == 80) host else "$host:$port"
        Socket().use { socket ->
            socket.soTimeout = readTimeoutMs
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
            val head = StringBuilder()
            head.append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
            head.append("Host: ").append(hostHeader).append("\r\n")
            head.append("Connection: close\r\n")
            if (headers.keys.none { it.equals("User-Agent", true) }) head.append("User-Agent: ").append(DEFAULT_UA).append("\r\n")
            for ((k, v) in headers) head.append(k).append(": ").append(v).append("\r\n")
            if (body != null) {
                head.append("Content-Type: ").append(contentType ?: "application/octet-stream").append("\r\n")
                head.append("Content-Length: ").append(body.size).append("\r\n")
            }
            head.append("\r\n")
            val out = socket.getOutputStream()
            out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
            if (body != null) out.write(body)
            out.flush()
            return readResponse(BufferedInputStream(socket.getInputStream()))
        }
    }

    internal fun readResponse(input: InputStream): Response {
        val statusLine = readLine(input) ?: throw IOException("LanHttp: empty response")
        val parts = statusLine.split(' ', limit = 3)
        if (parts.size < 2 || !parts[0].startsWith("HTTP/")) throw IOException("LanHttp: bad status line: $statusLine")
        val code = parts[1].toIntOrNull() ?: throw IOException("LanHttp: bad status code: $statusLine")
        val message = parts.getOrNull(2).orEmpty()
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }
        val bytes = when {
            headers["transfer-encoding"]?.contains("chunked", true) == true -> readChunked(input)
            headers["content-length"] != null -> readExact(input, headers["content-length"]!!.trim().toLongOrNull() ?: 0L)
            else -> readToEnd(input)
        }
        val charset = headers["content-type"]?.let { ct ->
            Regex("charset=\"?([\\w-]+)\"?", RegexOption.IGNORE_CASE).find(ct)?.groupValues?.get(1)
        }?.let { runCatching { charset(it) }.getOrNull() } ?: Charsets.UTF_8
        return Response(code, message, headers, String(bytes, charset))
    }

    /** CRLF or LF terminated line, ISO-8859-1; null at EOF before any byte. */
    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        var any = false
        while (true) {
            val b = input.read()
            if (b < 0) return if (any) buf.toString(Charsets.ISO_8859_1.name()) else null
            any = true
            if (b == '\n'.code) break
            if (b != '\r'.code) buf.write(b)
        }
        return buf.toString(Charsets.ISO_8859_1.name())
    }

    private fun readExact(input: InputStream, length: Long): ByteArray {
        val cap = length.coerceAtMost(MAX_BODY.toLong()).toInt()
        val out = ByteArray(cap)
        var read = 0
        while (read < cap) {
            val n = input.read(out, read, cap - read)
            if (n < 0) break
            read += n
        }
        return if (read == cap) out else out.copyOf(read)
    }

    private fun readToEnd(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < MAX_BODY) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun readChunked(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (out.size() < MAX_BODY) {
            val sizeLine = readLine(input) ?: break
            if (sizeLine.isBlank()) continue
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: throw IOException("LanHttp: bad chunk size: $sizeLine")
            if (size == 0) break
            out.write(readExact(input, size.toLong()))
            readLine(input) // trailing CRLF after the chunk data
        }
        return out.toByteArray()
    }
}
