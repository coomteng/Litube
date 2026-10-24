@file:OptIn(UnstableApi::class)
package com.hhst.youtubelite.player.engine

import android.content.Context
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import com.hhst.youtubelite.player.datasource.DecoderQuirks

/**
 * [DefaultRenderersFactory] whose video renderer applies [DecoderQuirks]:
 * on Exynos OMX decoders a surface change (fullscreen toggle, PiP) and a
 * resolution change rebuild the decoder instead of reconfiguring it in
 * place. The Exynos MFC keeps reference frames in the output buffers, so
 * an in-place `setOutputSurface` or an adaptive resolution switch can
 * leave the next P-frames predicting from garbage until the next keyframe.
 * Media3's own workaround list stops at Android 8; these devices run 11-13.
 */
class QuirkAwareRenderersFactory(context: Context) : DefaultRenderersFactory(context) {

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) {
        out.add(
            QuirkAwareVideoRenderer(
                context, codecAdapterFactory, mediaCodecSelector, allowedVideoJoiningTimeMs,
                enableDecoderFallback, eventHandler, eventListener, MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY,
            ),
        )
        // Extension renderers (libvpx etc.) are not bundled; nothing else to add.
    }

    private class QuirkAwareVideoRenderer(
        context: Context,
        codecAdapterFactory: androidx.media3.exoplayer.mediacodec.MediaCodecAdapter.Factory,
        mediaCodecSelector: MediaCodecSelector,
        allowedJoiningTimeMs: Long,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        maxDroppedFramesToNotify: Int,
    ) : MediaCodecVideoRenderer(
        context, codecAdapterFactory, mediaCodecSelector, allowedJoiningTimeMs,
        enableDecoderFallback, eventHandler, eventListener, maxDroppedFramesToNotify,
    ) {
        override fun codecNeedsSetOutputSurfaceWorkaround(name: String): Boolean =
            DecoderQuirks.needsSetOutputSurfaceWorkaround(name) || super.codecNeedsSetOutputSurfaceWorkaround(name)

        override fun canReuseCodec(
            codecInfo: MediaCodecInfo,
            oldFormat: Format,
            newFormat: Format,
            isReconfigureAllowed: Boolean,
        ): DecoderReuseEvaluation {
            val evaluation = super.canReuseCodec(codecInfo, oldFormat, newFormat, isReconfigureAllowed)
            val resized = oldFormat.width != newFormat.width || oldFormat.height != newFormat.height
            if (resized && DecoderQuirks.needsSetOutputSurfaceWorkaround(codecInfo.name) &&
                evaluation.result != DecoderReuseEvaluation.REUSE_RESULT_NO
            ) {
                return DecoderReuseEvaluation(
                    codecInfo.name, oldFormat, newFormat,
                    DecoderReuseEvaluation.REUSE_RESULT_NO,
                    DecoderReuseEvaluation.DISCARD_REASON_VIDEO_RESOLUTION_CHANGED,
                )
            }
            return evaluation
        }
    }
}
