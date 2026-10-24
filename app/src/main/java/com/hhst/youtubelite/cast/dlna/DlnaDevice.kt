package com.hhst.youtubelite.cast.dlna

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory

/** A UPnP MediaRenderer resolved from its device description. */
data class DlnaDevice(
    val udn: String,
    val friendlyName: String,
    /** Absolute AVTransport:1 control URL. */
    val avTransportUrl: String,
    /** Absolute ConnectionManager:1 control URL (null when absent). */
    val connectionManagerUrl: String?,
    val manufacturer: String? = null,
    val modelName: String? = null,
) {
    companion object {
        const val SERVICE_AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
        const val SERVICE_CONNECTION_MANAGER = "urn:schemas-upnp-org:service:ConnectionManager:1"

        /**
         * Parses a device description XML fetched from [locationUrl]. Returns
         * null when the document has no AVTransport service (not a renderer).
         * Relative control URLs resolve against `URLBase` then [locationUrl].
         */
        fun parse(xml: String, locationUrl: String): DlnaDevice? {
            val doc = runCatching {
                DocumentBuilderFactory.newInstance().apply {
                    isNamespaceAware = false
                    runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                }.newDocumentBuilder().parse(InputSource(StringReader(xml)))
            }.getOrNull() ?: return null
            val root = doc.documentElement ?: return null
            val urlBase = childText(root, "URLBase")
            // Root device = first <device> under <root>. Embedded devices live inside <deviceList>.
            val device = firstChild(root, "device") ?: return null
            var avUrl: String? = null
            var cmUrl: String? = null
            val services = device.getElementsByTagName("service")
            for (i in 0 until services.length) {
                val service = services.item(i) as? Element ?: continue
                val type = childText(service, "serviceType") ?: continue
                val url = childText(service, "controlURL") ?: continue
                if (type.startsWith("urn:schemas-upnp-org:service:AVTransport:") && avUrl == null) avUrl = url
                if (type.startsWith("urn:schemas-upnp-org:service:ConnectionManager:") && cmUrl == null) cmUrl = url
            }
            val av = avUrl ?: return null
            val base = urlBase?.takeIf { it.isNotBlank() } ?: locationUrl
            return DlnaDevice(
                udn = childText(device, "UDN") ?: locationUrl,
                friendlyName = childText(device, "friendlyName")?.takeIf { it.isNotBlank() }
                    ?: runCatching { URI(locationUrl).host }.getOrNull().orEmpty(),
                avTransportUrl = resolve(base, av),
                connectionManagerUrl = cmUrl?.let { resolve(base, it) },
                manufacturer = childText(device, "manufacturer"),
                modelName = childText(device, "modelName"),
            )
        }

        private fun firstChild(parent: Element, name: String): Element? {
            val children = parent.childNodes
            for (i in 0 until children.length) {
                val node = children.item(i)
                if (node is Element && node.tagName == name) return node
            }
            return null
        }

        private fun childText(parent: Element, name: String): String? =
            firstChild(parent, name)?.textContent?.trim()?.takeIf { it.isNotEmpty() }

        internal fun resolve(base: String, ref: String): String =
            runCatching { URI(base).resolve(ref).toString() }.getOrDefault(ref)
    }
}
