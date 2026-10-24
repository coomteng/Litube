package com.hhst.youtubelite.cast.lounge

import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.io.BufferedReader
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * YouTube Lounge ("MDX") remote-control client: the protocol the YouTube
 * phone app uses to drive YouTube on a TV. Undocumented; field names follow
 * pyytlounge and ytcast. Blocking HTTP; callers run it off the main thread.
 *
 * Flow: screenId (DIAL or TV pairing code) → loungeToken → bc/bind POST
 * opens a BrowserChannel session (SID + gsessionid) → commands are POSTs
 * with an incrementing RID/ofs → a long-poll GET streams events.
 */
class LoungeApi(http: OkHttpClient, private val deviceName: String) {
    private val http = http.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    /** Long-poll client: the server holds the GET for minutes. */
    private val pollHttp = http.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val deviceId = UUID.randomUUID().toString()

    data class Screen(val screenId: String, val loungeToken: String, val name: String?)

    class LoungeException(message: String, val code: Int = 0) : IOException(message)

    /** `Link with TV code` flow: the 12-digit code shown by YouTube on the TV. */
    fun pairWithCode(code: String): Screen {
        val digits = code.filter(Char::isDigit)
        val json = postForm("$API/pairing/get_screen", FormBody.Builder().add("pairing_code", digits).build())
        val screen = parseObject(json)?.getAsJsonObject("screen") ?: throw LoungeException("No screen in pairing reply")
        return Screen(
            screenId = screen.str("screenId") ?: throw LoungeException("No screenId"),
            loungeToken = screen.str("loungeToken") ?: throw LoungeException("No loungeToken"),
            name = screen.str("name"),
        )
    }

    fun loungeToken(screenId: String): Screen {
        val json = postForm("$API/pairing/get_lounge_token_batch", FormBody.Builder().add("screen_ids", screenId).build())
        val first = parseObject(json)?.getAsJsonArray("screens")?.takeIf { it.size() > 0 }?.get(0)?.asJsonObject
            ?: throw LoungeException("No lounge token for screen")
        return Screen(
            first.str("screenId") ?: throw LoungeException("No screenId"),
            first.str("loungeToken") ?: throw LoungeException("No loungeToken"),
            first.str("name"),
        )
    }

    /** One connected BrowserChannel session. */
    inner class Session internal constructor(
        val screen: Screen,
        val sid: String,
        val gsessionid: String,
        private var lastEventId: Long,
    ) {
        private var rid = 1L
        private var ofs = 0L
        @Volatile private var closed = false

        fun setPlaylist(videoId: String, startSec: Long) = command(
            "setPlaylist", "videoId" to videoId, "currentTime" to startSec.toString(),
            "currentIndex" to "0", "listId" to "", "audioOnly" to "false",
        )
        fun play() = command("play")
        fun pause() = command("pause")
        fun seekTo(sec: Double) = command("seekTo", "newTime" to sec.toString())
        fun stopVideo() = command("stopVideo")
        fun setPlaybackSpeed(speed: Float) = command("setPlaybackSpeed", "playbackSpeed" to speed.toString())
        fun getNowPlaying() = command("getNowPlaying")

        @Synchronized
        private fun command(name: String, vararg args: Pair<String, String>) {
            if (closed) throw LoungeException("Session closed")
            val form = FormBody.Builder().add("count", "1").add("ofs", ofs.toString()).add("req0__sc", name)
            for ((k, v) in args) form.add("req0_$k", v)
            ofs++
            rid++
            val url = commonUrl().addQueryParameter("RID", rid.toString()).addQueryParameter("CVER", "1").build()
            postForm(url.toString(), form.build())
        }

        /**
         * Blocks, streaming events to [onEvent] until the server closes the
         * poll or [close] is called. Returns normally on a clean close;
         * throws on session loss (caller may re-bind).
         */
        fun subscribe(onEvent: (String, JsonObject?) -> Unit) {
            val url = commonUrl()
                .addQueryParameter("RID", "rpc").addQueryParameter("CI", "0").addQueryParameter("TYPE", "xmlhttp")
                .addQueryParameter("AID", lastEventId.toString())
                .build()
            val request = Request.Builder().url(url).header("Origin", ORIGIN).header("User-Agent", UA).build()
            pollHttp.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw LoungeException("subscribe ${response.code}", response.code)
                val reader = response.body?.charStream()?.buffered() ?: return
                parseChunks(reader) { events ->
                    for (e in events) {
                        lastEventId = e.id
                        if (e.type != "c" && e.type != "S") onEvent(e.type, e.payload)
                    }
                    !closed
                }
            }
        }

        fun close() {
            closed = true
            runCatching {
                val url = commonUrl().addQueryParameter("RID", (++rid).toString()).addQueryParameter("CVER", "1").build()
                postForm(url.toString(), FormBody.Builder().add("ui", "").add("TYPE", "terminate")
                    .add("clientDisconnectReason", "MDX_SESSION_DISCONNECT_REASON_DISCONNECTED_BY_USER").build())
            }
        }

        private fun commonUrl(): HttpUrl.Builder = "$API/bc/bind".toHttpUrl().newBuilder()
            .addQueryParameter("device", "REMOTE_CONTROL")
            .addQueryParameter("app", "youtube-desktop")
            .addQueryParameter("name", deviceName)
            .addQueryParameter("id", deviceId)
            .addQueryParameter("VER", "8")
            .addQueryParameter("v", "2")
            .addQueryParameter("loungeIdToken", screen.loungeToken)
            .addQueryParameter("SID", sid)
            .addQueryParameter("gsessionid", gsessionid)
    }

    /** Opens a session; the TV shows "<deviceName> connected". */
    fun connect(screen: Screen): Session {
        val form = FormBody.Builder()
            .add("app", "web").add("mdx-version", "3").add("name", deviceName).add("id", deviceId)
            .add("device", "REMOTE_CONTROL").add("capabilities", "que,dsdtr,atp,vsp")
            .add("magnaKey", "cloudPairedDevice").add("ui", "false")
            .add("deviceContext", "user_agent=dunno&window_width_points=&window_height_points=&os_name=android&ms=")
            .add("theme", "cl").add("loungeIdToken", screen.loungeToken)
            .build()
        val body = postForm("$API/bc/bind?RID=1&VER=8&CVER=1&auth_failure_option=send_error", form)
        val ids = parseSessionIds(body) ?: throw LoungeException("No SID/gsessionid in bind reply")
        return Session(screen, ids.first, ids.second, ids.third)
    }

    private fun postForm(url: String, form: FormBody): String {
        val request = Request.Builder().url(url).header("Origin", ORIGIN).header("User-Agent", UA).post(form).build()
        http.newCall(request).execute().use { response -> return readOrThrow(response) }
    }

    private fun readOrThrow(response: Response): String {
        val text = response.body?.string().orEmpty()
        if (!response.isSuccessful) throw LoungeException("Lounge ${response.code}: ${text.take(200)}", response.code)
        return text
    }

    data class Event(val id: Long, val type: String, val payload: JsonObject?)

    private fun parseObject(json: String): JsonObject? =
        runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull()

    companion object {
        /** String member or null when absent/blank/not a primitive. */
        internal fun JsonObject.str(name: String): String? =
            get(name)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }

        private const val API = "https://www.youtube.com/api/lounge"
        private const val ORIGIN = "https://www.youtube.com"
        private const val UA = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"

        /** SID ("c"), gsessionid ("S") and last event id from a bind reply. */
        internal fun parseSessionIds(body: String): Triple<String, String, Long>? {
            var sid: String? = null
            var gsession: String? = null
            var last = 0L
            parseChunks(body.reader().buffered()) { events ->
                for (e in events) {
                    last = e.id
                    when (e.type) {
                        "c" -> sid = e.payload?.str("_0")
                        "S" -> gsession = e.payload?.str("_0")
                    }
                }
                true
            }
            val s = sid ?: return null
            val g = gsession ?: return null
            return Triple(s, g, last)
        }

        /**
         * BrowserChannel framing: a decimal character count on its own line,
         * then that many characters of a JSON array `[[id,[type,arg...]],...]`.
         * [sink] returns false to stop reading.
         */
        internal fun parseChunks(reader: BufferedReader, sink: (List<Event>) -> Boolean) {
            while (true) {
                val lengthLine = reader.readLine() ?: return
                if (lengthLine.isBlank()) continue
                val length = lengthLine.trim().toIntOrNull() ?: throw LoungeException("Bad chunk length: $lengthLine")
                val chars = CharArray(length)
                var read = 0
                while (read < length) {
                    val n = reader.read(chars, read, length - read)
                    if (n < 0) return
                    read += n
                }
                val events = parseEvents(String(chars))
                if (!sink(events)) return
            }
        }

        internal fun parseEvents(json: String): List<Event> {
            val out = ArrayList<Event>()
            val array = runCatching { JsonParser.parseString(json.trim()).asJsonArray }.getOrNull() ?: return out
            for (row in array) {
                val pair = row.takeIf { it.isJsonArray }?.asJsonArray ?: continue
                if (pair.size() < 2) continue
                val id = runCatching { pair[0].asLong }.getOrDefault(-1L)
                val inner = pair[1].takeIf { it.isJsonArray }?.asJsonArray ?: continue
                if (inner.size() == 0) continue
                val type = inner[0].takeIf { it.isJsonPrimitive }?.asString ?: continue
                val arg = if (inner.size() > 1) inner[1] else null
                val payload: JsonObject? = when {
                    arg == null || arg.isJsonNull -> null
                    arg.isJsonObject -> arg.asJsonObject
                    // "c"/"S" carry a bare string: wrap it so callers have one shape.
                    else -> JsonObject().apply { add("_0", JsonPrimitive(arg.asString)) }
                }
                out += Event(id, type, payload)
            }
            return out
        }
    }
}
