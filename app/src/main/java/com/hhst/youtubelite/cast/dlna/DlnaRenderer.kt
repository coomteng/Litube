package com.hhst.youtubelite.cast.dlna

import com.hhst.youtubelite.cast.LanHttp
import com.hhst.youtubelite.diagnostics.AppLog
import java.io.IOException

/** Media handed to a renderer. [mimeType] drives the DIDL protocolInfo. */
data class DlnaMedia(
    val url: String,
    val mimeType: String,
    val title: String,
    val author: String? = null,
    val artworkUrl: String? = null,
    val durationMs: Long = 0L,
    val isVideo: Boolean,
    /** How long the renderer may sit in STOPPED/TRANSITIONING after Play before the load counts as failed. */
    val startGraceMs: Long = 20_000L,
)

data class DlnaTransportInfo(val state: String, val status: String)

data class DlnaPositionInfo(val positionMs: Long, val durationMs: Long, val trackUri: String?)

/**
 * Synchronous SOAP control point for one AVTransport:1 instance (InstanceID 0).
 * All calls block on the network; callers run them off the main thread.
 * Uses [LanHttp] (plain sockets): UPnP control is cleartext HTTP to a LAN
 * host, which the app's network security policy otherwise forbids.
 */
class DlnaRenderer(private val device: DlnaDevice) {

    class SoapException(val code: Int, message: String) : IOException(message)

    /** Sink protocolInfo entries from ConnectionManager, empty when unavailable. */
    fun sinkProtocols(): List<String> {
        val url = device.connectionManagerUrl ?: return emptyList()
        val body = runCatching { call(url, DlnaDevice.SERVICE_CONNECTION_MANAGER, "GetProtocolInfo", emptyList()) }
            .getOrElse { return emptyList() }
        return tagText(body, "Sink")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
    }

    fun setMedia(media: DlnaMedia) {
        avTransport("SetAVTransportURI", listOf(
            "CurrentURI" to media.url,
            "CurrentURIMetaData" to Didl.metadata(media),
        ))
    }

    fun play() = avTransport("Play", listOf("Speed" to "1"))
    fun pause() = avTransport("Pause", emptyList())
    fun stop() = avTransport("Stop", emptyList())

    fun seek(positionMs: Long) {
        avTransport("Seek", listOf("Unit" to "REL_TIME", "Target" to Didl.hms(positionMs)))
    }

    fun transportInfo(): DlnaTransportInfo {
        val body = avTransport("GetTransportInfo", emptyList())
        return DlnaTransportInfo(
            state = tagText(body, "CurrentTransportState").orEmpty(),
            status = tagText(body, "CurrentTransportStatus").orEmpty(),
        )
    }

    fun positionInfo(): DlnaPositionInfo {
        val body = avTransport("GetPositionInfo", emptyList())
        return DlnaPositionInfo(
            positionMs = Didl.parseHms(tagText(body, "RelTime")),
            durationMs = Didl.parseHms(tagText(body, "TrackDuration")),
            trackUri = tagText(body, "TrackURI")?.let(Didl::unescape),
        )
    }

    private fun avTransport(action: String, args: List<Pair<String, String>>): String =
        call(device.avTransportUrl, DlnaDevice.SERVICE_AV_TRANSPORT, action, listOf("InstanceID" to "0") + args)

    private fun call(controlUrl: String, service: String, action: String, args: List<Pair<String, String>>): String {
        val envelope = envelope(service, action, args)
        val response = LanHttp.post(
            controlUrl, envelope.toByteArray(Charsets.UTF_8), "text/xml; charset=\"utf-8\"",
            headers = mapOf("SOAPAction" to "\"$service#$action\"", "User-Agent" to USER_AGENT),
        )
        val body = response.body
        if (!response.isSuccessful) {
            val code = tagText(body, "errorCode")?.toIntOrNull() ?: response.code
            val desc = tagText(body, "errorDescription") ?: response.message
            AppLog.event(AppLog.Category.CAST, "dlna_soap_error", mapOf("action" to action, "code" to code, "desc" to desc))
            throw SoapException(code, "$action failed: $code $desc")
        }
        return body
    }

    companion object {
        private const val USER_AGENT = "Android/10 UPnP/1.0 LitubePlus/1.0"

        internal fun envelope(service: String, action: String, args: List<Pair<String, String>>): String = buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
            append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"")
            append(" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>")
            append("<u:").append(action).append(" xmlns:u=\"").append(service).append("\">")
            for ((name, value) in args) {
                append('<').append(name).append('>').append(Didl.escape(value)).append("</").append(name).append('>')
            }
            append("</u:").append(action).append("></s:Body></s:Envelope>")
        }

        /** First `<name>` or `<prefix:name>` text in [xml], XML-unescaped. */
        internal fun tagText(xml: String, name: String): String? {
            val regex = Regex("<(?:[\\w.-]+:)?$name(?:\\s[^>]*)?>(.*?)</(?:[\\w.-]+:)?$name>", RegexOption.DOT_MATCHES_ALL)
            return regex.find(xml)?.groupValues?.get(1)?.let(Didl::unescape)?.trim()
        }
    }
}
