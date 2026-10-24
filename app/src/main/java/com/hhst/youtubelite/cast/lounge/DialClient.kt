package com.hhst.youtubelite.cast.lounge

import com.hhst.youtubelite.cast.LanHttp
import com.hhst.youtubelite.cast.dlna.DlnaRenderer
import java.io.IOException

/** A DIAL server (smart TV) resolved from its device description. */
data class DialScreen(
    val usn: String,
    val friendlyName: String,
    /** `Application-URL` header of the description response, with trailing slash. */
    val applicationUrl: String,
    val host: String,
)

/** State of the YouTube DIAL app on a screen. */
data class DialAppInfo(val state: String, val screenId: String?)

/**
 * DIAL (Discovery and Launch) REST client, YouTube app only. Blocking; run
 * off the main thread. Reference: DIAL 2.2.1 spec and ytcast/pyytlounge.
 * Cleartext LAN HTTP via [LanHttp] (see there for why).
 */
class DialClient {
    private val headers = mapOf("Origin" to ORIGIN, "User-Agent" to USER_AGENT)

    /** GET the SSDP LOCATION; null unless it is a DIAL server with an Application-URL. */
    fun describe(location: String, usn: String, host: String): DialScreen? {
        val response = LanHttp.get(location, mapOf("User-Agent" to USER_AGENT))
        if (!response.isSuccessful) return null
        val appUrl = response.header("Application-URL")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val name = DlnaRenderer.tagText(response.body, "friendlyName")?.takeIf { it.isNotBlank() } ?: host
        return DialScreen(usn, name, if (appUrl.endsWith("/")) appUrl else "$appUrl/", host)
    }

    fun appInfo(screen: DialScreen): DialAppInfo {
        val response = LanHttp.get(screen.applicationUrl + APP_NAME, headers)
        if (response.code == 404) return DialAppInfo("not_installed", null)
        if (!response.isSuccessful) throw IOException("DIAL app info ${response.code}")
        return parseAppInfo(response.body)
    }

    /** POST launches the app; the TV may take several seconds to report `running`. */
    fun launch(screen: DialScreen) {
        val response = LanHttp.post(screen.applicationUrl + APP_NAME, ByteArray(0), "text/plain; charset=utf-8", headers)
        if (!response.isSuccessful && response.code != 201) throw IOException("DIAL launch ${response.code}")
    }

    /**
     * Ensures YouTube runs on [screen] and returns its screenId, polling for
     * up to [timeoutMs]. Null when the app never exposes one.
     */
    fun resolveScreenId(screen: DialScreen, timeoutMs: Long = LAUNCH_TIMEOUT_MS): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        var launched = false
        while (System.currentTimeMillis() < deadline) {
            val info = appInfo(screen)
            when (info.state) {
                "running" -> if (!info.screenId.isNullOrBlank()) return info.screenId
                "stopped", "hidden" -> if (!launched) { launch(screen); launched = true }
                "not_installed" -> return null
            }
            Thread.sleep(POLL_MS)
        }
        return null
    }

    companion object {
        const val APP_NAME = "YouTube"
        const val ORIGIN = "https://www.youtube.com"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10) LitubePlus"
        private const val LAUNCH_TIMEOUT_MS = 25_000L
        private const val POLL_MS = 1_000L

        internal fun parseAppInfo(xml: String): DialAppInfo {
            val state = DlnaRenderer.tagText(xml, "state")?.lowercase().orEmpty()
            val screenId = DlnaRenderer.tagText(xml, "screenId")?.takeIf { it.isNotBlank() }
            return DialAppInfo(state, screenId)
        }
    }
}
