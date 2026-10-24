@file:OptIn(UnstableApi::class)
package com.hhst.youtubelite.player.datasource

import android.media.MediaCodecList
import android.os.Build
import androidx.annotation.OptIn
import androidx.annotation.VisibleForTesting
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi

/**
 * Device decoder quirks that stream selection and the renderer consult.
 *
 * Exynos 9610 / 9611 (Galaxy A50, A51, M31, Tab S6 Lite 2020) ship a
 * hardware VP9 decoder whose output corrupts on high-motion, high-bitrate
 * segments: reference macroblocks are lost and the picture smears or
 * blocks until the next keyframe (~5 s on YouTube). H.264 on the same MFC
 * is fine, so the fix is to keep VP9 out of the candidate pool there.
 * NewPipe and ReVanced users on these devices apply the same workaround
 * by hand ("prefer MPEG-4").
 */
object DecoderQuirks {
    /** Decoder name prefixes whose VP9 output is known to corrupt. */
    private val BROKEN_VP9_DECODERS = listOf("OMX.Exynos.vp9", "c2.exynos.vp9")
    /** `ro.board.platform` / `Build.HARDWARE` values of the affected SoCs. */
    private val BROKEN_VP9_PLATFORMS = setOf("universal9610", "universal9611", "exynos9610", "exynos9611")

    @Volatile private var detected: Boolean? = null
    @Volatile private var testDetected: Boolean? = null
    @Volatile private var testPlatform: String? = null

    /** Whether this device's hardware VP9 decoder is on the broken list. */
    fun hasBrokenVp9Decoder(): Boolean {
        testDetected?.let { return it }
        detected?.let { return it }
        return detect().also { detected = it }
    }

    /**
     * Whether VP9 should be kept out of the candidate pool right now.
     * Seeded from the detection; the preference toggle writes it so a user
     * on another SoC with the same symptom can opt in, and an affected
     * user can opt out.
     */
    @Volatile var avoidVp9: Boolean? = null

    fun shouldAvoidVp9(): Boolean = avoidVp9 ?: hasBrokenVp9Decoder()

    /**
     * Track-selector video MIME preference. The DASH pool is filtered in
     * [StreamSelection.avoidBrokenVp9]; HLS variants come straight from
     * YouTube's multivariant playlist, so the selector has to steer there.
     * A preference, not a ban: an all-VP9 playlist still plays.
     */
    fun preferredVideoMimeTypes(): Array<String> =
        if (shouldAvoidVp9()) arrayOf(MimeTypes.VIDEO_H264, MimeTypes.VIDEO_H265, MimeTypes.VIDEO_AV1) else emptyArray()

    /** Whether [codecName] needs the output-surface rebuild workaround. */
    fun needsSetOutputSurfaceWorkaround(codecName: String): Boolean =
        codecName.startsWith("OMX.Exynos.", ignoreCase = true)

    fun isVp9(codec: String?): Boolean {
        val lower = codec?.lowercase() ?: return false
        return lower.startsWith("vp9") || lower.startsWith("vp09")
    }

    private fun detect(): Boolean {
        // Build.HARDWARE is null under the unit-test Android stubs.
        val platform = (testPlatform ?: Build.HARDWARE)?.lowercase() ?: return false
        if (platform in BROKEN_VP9_PLATFORMS) return true
        return runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.orEmpty().any { info ->
                !info.isEncoder &&
                    info.supportedTypes.orEmpty().any { it.equals(MimeTypes.VIDEO_VP9, ignoreCase = true) } &&
                    BROKEN_VP9_DECODERS.any { prefix -> info.name.orEmpty().startsWith(prefix, ignoreCase = true) }
            }
        }.getOrDefault(false)
    }

    @VisibleForTesting
    internal fun setForTest(detected: Boolean? = null, platform: String? = null) {
        testDetected = detected
        testPlatform = platform
        this.detected = null
        avoidVp9 = null
    }
}
