package com.hhst.youtubelite.cast.ssdp

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import com.hhst.youtubelite.diagnostics.AppLog
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/** One SSDP M-SEARCH answer. [location] is the device description URL. */
data class SsdpResponse(
    val location: String,
    val searchTarget: String,
    val usn: String,
    val host: String,
)

/**
 * SSDP M-SEARCH client used for DLNA MediaRenderers and DIAL screens. One UDP
 * socket, several search targets, answers deduped per `USN|ST` so a device
 * that answers both targets is reported once per role. Rounds repeat every
 * [REPEAT_MS] while running: TVs often miss the first multicast.
 *
 * Callbacks land on the main thread.
 */
class SsdpDiscovery(context: Context, private val searchTargets: List<String>) {
    fun interface Listener { fun onResponse(response: SsdpResponse) }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var generation = 0
    private var thread: Thread? = null
    private var lock: WifiManager.MulticastLock? = null

    fun start(listener: Listener) {
        stop()
        val gen = ++generation
        acquireLock()
        val seen = HashSet<String>()
        thread = Thread({
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket().apply { soTimeout = RECV_TIMEOUT_MS; broadcast = true }
                val group = InetAddress.getByName(SSDP_ADDR)
                var lastSend = 0L
                val buf = ByteArray(4096)
                while (gen == generation && !Thread.currentThread().isInterrupted) {
                    val now = System.currentTimeMillis()
                    if (now - lastSend >= REPEAT_MS) {
                        for (st in searchTargets) {
                            val msg = mSearch(st).toByteArray(Charsets.US_ASCII)
                            runCatching { socket.send(DatagramPacket(msg, msg.size, group, SSDP_PORT)) }
                        }
                        lastSend = now
                    }
                    val packet = DatagramPacket(buf, buf.size)
                    try { socket.receive(packet) } catch (_: SocketTimeoutException) { continue }
                    val text = String(packet.data, 0, packet.length, Charsets.ISO_8859_1)
                    val response = parse(text, packet.address.hostAddress ?: continue) ?: continue
                    if (!seen.add("${response.usn}|${response.searchTarget}")) continue
                    AppLog.event(AppLog.Category.CAST, "ssdp_response", mapOf("st" to response.searchTarget, "host" to response.host))
                    main.post { if (gen == generation) listener.onResponse(response) }
                }
            } catch (e: Exception) {
                AppLog.event(AppLog.Category.CAST, "ssdp_failed", failure = e)
            } finally {
                runCatching { socket?.close() }
            }
        }, "SsdpDiscovery").apply { isDaemon = true; start() }
    }

    fun stop() {
        generation++
        thread?.interrupt()
        thread = null
        releaseLock()
    }

    private fun acquireLock() {
        if (lock != null) return
        val wifi = appContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        lock = runCatching {
            wifi.createMulticastLock("litube-ssdp").apply { setReferenceCounted(false); acquire() }
        }.getOrNull()
    }

    private fun releaseLock() {
        runCatching { lock?.takeIf { it.isHeld }?.release() }
        lock = null
    }

    companion object {
        const val ST_MEDIA_RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1"
        const val ST_DIAL = "urn:dial-multiscreen-org:service:dial:1"
        private const val SSDP_ADDR = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private const val RECV_TIMEOUT_MS = 1_000
        private const val REPEAT_MS = 4_000L

        internal fun mSearch(st: String): String =
            "M-SEARCH * HTTP/1.1\r\nHOST: $SSDP_ADDR:$SSDP_PORT\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $st\r\n\r\n"

        /** Parses an M-SEARCH response (or NOTIFY); null when it carries no LOCATION. */
        internal fun parse(text: String, host: String): SsdpResponse? {
            val lines = text.split("\r\n", "\n")
            if (lines.isEmpty() || !lines[0].startsWith("HTTP/1.1 200", ignoreCase = true)) return null
            val headers = HashMap<String, String>()
            for (line in lines.drop(1)) {
                val idx = line.indexOf(':')
                if (idx <= 0) continue
                headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }
            val location = headers["location"]?.takeIf { it.startsWith("http", ignoreCase = true) } ?: return null
            return SsdpResponse(
                location = location,
                searchTarget = headers["st"].orEmpty(),
                usn = headers["usn"] ?: location,
                host = host,
            )
        }
    }
}
