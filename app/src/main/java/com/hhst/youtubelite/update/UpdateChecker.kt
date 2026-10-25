package com.hhst.youtubelite.update

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.hhst.youtubelite.BuildConfig
import com.hhst.youtubelite.ui.about.StableVersion
import okhttp3.OkHttpClient
import okhttp3.Request

/** One installable stable release: the APK picked for this device plus its published SHA-256. */
data class UpdateInfo(
    val tag: String,
    val apkName: String,
    val apkUrl: String,
    val apkSize: Long,
    /** Lower-case hex from SHA256SUMS.txt, or null when the release carries no checksum file. */
    val sha256: String?,
    val releaseUrl: String,
)

/** Result of one check: the current version is already the latest ([latestTag]), or [update] is available. */
data class UpdateCheck(val latestTag: String, val update: UpdateInfo?)

/**
 * Looks up the newest stable GitHub release of this fork and chooses the APK asset
 * matching the device ABI. Pure parsing lives in the companion so it can be unit tested.
 */
class UpdateChecker(private val client: OkHttpClient, private val repo: String = BuildConfig.FORK_REPO) {

    /** Network call; throws on HTTP errors or malformed responses. */
    fun check(currentVersion: String, supportedAbis: List<String>): UpdateCheck {
        val release = Request.Builder().url("https://api.github.com/repos/$repo/releases/latest")
            .header("Accept", "application/vnd.github+json").build()
        val json = client.newCall(release).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            JsonParser.parseString(response.body?.string() ?: error("Empty release response")).asJsonObject
        }
        val tag = latestStableTag(json)
        if (!StableVersion.isNewer(currentVersion, tag)) return UpdateCheck(tag, null)
        val assets = json.getAsJsonArray("assets").map { it.asJsonObject }
        val sums = assets.firstOrNull { it.get("name").asString == SUMS_FILE }?.get("browser_download_url")?.asString
        val checksums = sums?.let { url ->
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (response.isSuccessful) parseSha256Sums(response.body?.string().orEmpty()) else emptyMap()
            }
        } ?: emptyMap()
        return UpdateCheck(tag, selectUpdate(json, supportedAbis, checksums))
    }

    companion object {
        const val SUMS_FILE = "SHA256SUMS.txt"
        private val APK_NAME = Regex("""^litube-plus-(.+?)-([A-Za-z0-9_-]+?)-release\.apk$""")

        /** Tag of a published stable release; rejects drafts and prereleases. */
        fun latestStableTag(release: JsonObject): String {
            check(!release.get("prerelease").asBoolean && !release.get("draft").asBoolean) { "Latest release is not stable" }
            return release.get("tag_name").asString
        }

        /**
         * Picks the release-signed APK for the first supported ABI, falling back to the universal
         * build. Returns null when the release has no usable APK (e.g. unsigned artifacts only).
         */
        fun selectUpdate(release: JsonObject, supportedAbis: List<String>, checksums: Map<String, String>): UpdateInfo? {
            val tag = release.get("tag_name").asString
            val assets = release.getAsJsonArray("assets").map { it.asJsonObject }
            val byAbi = assets.mapNotNull { asset ->
                val name = asset.get("name").asString
                APK_NAME.find(name)?.let { it.groupValues[2] to asset }
            }.toMap()
            val chosen = (supportedAbis + "universal").firstNotNullOfOrNull { byAbi[it] } ?: return null
            val name = chosen.get("name").asString
            return UpdateInfo(
                tag = tag,
                apkName = name,
                apkUrl = chosen.get("browser_download_url").asString,
                apkSize = chosen.get("size")?.asLong ?: -1L,
                sha256 = checksums[name],
                releaseUrl = release.get("html_url")?.asString ?: "https://github.com/${BuildConfig.FORK_REPO}/releases/tag/$tag",
            )
        }

        /** Parses `sha256sum` output (`<hex>  <file>` or `<hex> *<file>`) into file -> lower-case hex. */
        fun parseSha256Sums(text: String): Map<String, String> = text.lineSequence().mapNotNull { line ->
            val match = Regex("""^([0-9a-fA-F]{64})\s+\*?(.+?)\s*$""").find(line.trim()) ?: return@mapNotNull null
            match.groupValues[2].substringAfterLast('/') to match.groupValues[1].lowercase()
        }.toMap()
    }
}
