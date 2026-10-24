package com.hhst.youtubelite.cast.dlna

import java.util.Locale

/** DIDL-Lite metadata and the UPnP time format (`H:MM:SS`). */
object Didl {

    /** DIDL-Lite document (unescaped). Embedding into SOAP escapes it again. */
    fun metadata(media: DlnaMedia): String {
        val upnpClass = if (media.isVideo) "object.item.videoItem" else "object.item.audioItem.musicTrack"
        // DLNA.ORG_OP=01: byte-range seek supported; renderers that seek need it.
        val protocolInfo = "http-get:*:${media.mimeType}:DLNA.ORG_OP=01;DLNA.ORG_FLAGS=01700000000000000000000000000000"
        return buildString {
            append("<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\"")
            append(" xmlns:dc=\"http://purl.org/dc/elements/1.1/\"")
            append(" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\"")
            append(" xmlns:dlna=\"urn:schemas-dlna-org:metadata-1-0/\">")
            append("<item id=\"litube-1\" parentID=\"0\" restricted=\"1\">")
            append("<dc:title>").append(escape(media.title)).append("</dc:title>")
            media.author?.takeIf { it.isNotBlank() }?.let {
                append("<dc:creator>").append(escape(it)).append("</dc:creator>")
                append("<upnp:artist>").append(escape(it)).append("</upnp:artist>")
            }
            append("<upnp:class>").append(upnpClass).append("</upnp:class>")
            media.artworkUrl?.takeIf { it.isNotBlank() }?.let {
                append("<upnp:albumArtURI>").append(escape(it)).append("</upnp:albumArtURI>")
            }
            append("<res protocolInfo=\"").append(escape(protocolInfo)).append('"')
            if (media.durationMs > 0) append(" duration=\"").append(hms(media.durationMs)).append('"')
            append('>').append(escape(media.url)).append("</res>")
            append("</item></DIDL-Lite>")
        }
    }

    fun escape(s: String): String = buildString(s.length + 16) {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(c)
        }
    }

    fun unescape(s: String): String {
        if ('&' !in s) return s
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&apos;", "'").replace("&#39;", "'").replace("&amp;", "&")
    }

    /** `H:MM:SS` (no fractional part; some renderers reject `.mmm`). */
    fun hms(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        return String.format(Locale.US, "%d:%02d:%02d", total / 3600, (total % 3600) / 60, total % 60)
    }

    /** Parses `H:MM:SS[.fff]`; `NOT_IMPLEMENTED`, blank or malformed → 0. */
    fun parseHms(s: String?): Long {
        if (s.isNullOrBlank()) return 0L
        val parts = s.trim().split(':')
        if (parts.size !in 2..3) return 0L
        var seconds = 0.0
        for (part in parts) {
            val v = part.toDoubleOrNull() ?: return 0L
            seconds = seconds * 60 + v
        }
        return (seconds * 1000).toLong().coerceAtLeast(0)
    }
}
