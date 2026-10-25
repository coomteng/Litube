package com.hhst.youtubelite.update

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class UpdateCheckerTest {
    private fun release(vararg names: String, prerelease: Boolean = false, draft: Boolean = false) = JsonParser.parseString(
        """{"tag_name":"v3.1","prerelease":$prerelease,"draft":$draft,"html_url":"https://github.com/x/y/releases/tag/v3.1",
           "assets":[${names.joinToString(",") { """{"name":"$it","size":1000,"browser_download_url":"https://dl/$it"}""" }}]}""",
    ).asJsonObject

    private val full = arrayOf(
        "litube-plus-3.1-arm64-v8a-release.apk", "litube-plus-3.1-armeabi-v7a-release.apk",
        "litube-plus-3.1-universal-release.apk", "litube-plus-3.1-x86-release.apk", "litube-plus-3.1-x86_64-release.apk",
        "mapping-release-3.1.zip", "SHA256SUMS.txt",
    )

    @Test fun picksFirstSupportedAbi() {
        val info = UpdateChecker.selectUpdate(release(*full), listOf("arm64-v8a", "armeabi-v7a"), emptyMap())!!
        assertEquals("litube-plus-3.1-arm64-v8a-release.apk", info.apkName)
        assertEquals("https://dl/litube-plus-3.1-arm64-v8a-release.apk", info.apkUrl)
        assertEquals("v3.1", info.tag)
        assertEquals(1000L, info.apkSize)
        assertNull(info.sha256)
    }

    @Test fun secondaryAbiWhenPrimaryMissing() {
        val info = UpdateChecker.selectUpdate(release("litube-plus-3.1-armeabi-v7a-release.apk"), listOf("arm64-v8a", "armeabi-v7a"), emptyMap())!!
        assertEquals("litube-plus-3.1-armeabi-v7a-release.apk", info.apkName)
    }

    @Test fun fallsBackToUniversalAndIgnoresUnsigned() {
        val info = UpdateChecker.selectUpdate(
            release("litube-plus-3.1-arm64-v8a-release-unsigned.apk", "litube-plus-3.1-universal-release.apk"),
            listOf("arm64-v8a"), emptyMap(),
        )!!
        assertEquals("litube-plus-3.1-universal-release.apk", info.apkName)
        assertNull(UpdateChecker.selectUpdate(release("litube-plus-3.1-arm64-v8a-release-unsigned.apk", "SHA256SUMS.txt"), listOf("arm64-v8a"), emptyMap()))
    }

    @Test fun attachesChecksumByFileName() {
        val sums = UpdateChecker.parseSha256Sums(
            "ABCDEF0123456789abcdef0123456789abcdef0123456789abcdef0123456789  litube-plus-3.1-arm64-v8a-release.apk\n" +
                "0000000000000000000000000000000000000000000000000000000000000000 *litube-plus-3.1-universal-release.apk\n" +
                "garbage line\n",
        )
        assertEquals(2, sums.size)
        val info = UpdateChecker.selectUpdate(release(*full), listOf("arm64-v8a"), sums)!!
        assertEquals("abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789", info.sha256)
    }

    @Test fun rejectsPrereleaseAndDraft() {
        assertEquals("v3.1", UpdateChecker.latestStableTag(release(*full)))
        assertThrows(IllegalStateException::class.java) { UpdateChecker.latestStableTag(release(*full, prerelease = true)) }
        assertThrows(IllegalStateException::class.java) { UpdateChecker.latestStableTag(release(*full, draft = true)) }
    }
}
