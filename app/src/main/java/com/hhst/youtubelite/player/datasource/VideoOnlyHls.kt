package com.hhst.youtubelite.player.datasource

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistParserFactory
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory
import androidx.media3.exoplayer.upstream.ParsingLoadable
import java.io.InputStream

/**
 * Audio renditions name each language but omit `CODECS`, channel count,
 * and sample rate. Media3 then either skips chunkless preparation or marks the
 * track unsupported, so the menu never sees the other languages. Fill those in
 * from the variant so every name is a supported track.
 *
 * YouTube also leaves every rendition without `DEFAULT=YES`, including the
 * original, while listing auto-dubs first. With no preference set, Media3's
 * selector then falls through to channel count / bitrate and takes the first
 * tie — the dub — at prepare time, before any post-hoc override can land.
 * The NAME does say which one is original (`… - original` vs `… - dubbed-auto`),
 * so stamp the flags from it: original gets DEFAULT + MAIN, dubs lose DEFAULT.
 * A manual pick from the audio menu still overrides this via track selection.
 */
@UnstableApi
internal fun declareAudioCodecs(playlist: HlsPlaylist): HlsPlaylist {
    val multi = playlist as? HlsMultivariantPlaylist ?: return playlist
    if (multi.audios.isEmpty()) return playlist
    val codecByGroup = HashMap<String, String>()
    var fallback: String? = null
    for (variant in multi.variants) {
        val codec = singleAudioCodec(variant.format.codecs) ?: continue
        if (fallback == null) fallback = codec
        val group = variant.audioGroupId ?: continue
        codecByGroup.putIfAbsent(group, codec)
    }
    val fallbackCodec = fallback ?: return playlist
    var changed = false
    val audios = multi.audios.map { rendition ->
        val format = rendition.format
        val codec = singleAudioCodec(format.codecs)
            ?: codecByGroup[rendition.groupId]
            ?: fallbackCodec
        val needsCodec = singleAudioCodec(format.codecs) == null
        val needsLayout = format.channelCount == Format.NO_VALUE || format.sampleRate == Format.NO_VALUE
        val wantedSelection = preferredSelectionFlags(rendition.name, format.selectionFlags)
        val wantedRoles = preferredRoleFlags(rendition.name, format.roleFlags)
        val needsPreference = wantedSelection != format.selectionFlags || wantedRoles != format.roleFlags
        if (!needsCodec && !needsLayout && !needsPreference) {
            rendition
        } else {
            changed = true
            val builder = format.buildUpon()
            if (needsCodec) {
                builder.setCodecs(codec).setSampleMimeType(MimeTypes.getMediaMimeType(codec))
            }
            if (format.channelCount == Format.NO_VALUE) builder.setChannelCount(2)
            if (format.sampleRate == Format.NO_VALUE) builder.setSampleRate(48_000)
            if (needsPreference) builder.setSelectionFlags(wantedSelection).setRoleFlags(wantedRoles)
            HlsMultivariantPlaylist.Rendition(
                rendition.url,
                builder.build(),
                rendition.groupId,
                rendition.name,
                null,
            )
        }
    }
    if (!changed) return playlist
    return HlsMultivariantPlaylist(
        multi.baseUri,
        multi.tags,
        multi.variants,
        multi.videos,
        audios,
        multi.subtitles,
        multi.closedCaptions,
        multi.muxedAudioFormat,
        multi.muxedCaptionFormats,
        multi.hasIndependentSegments,
        multi.variableDefinitions,
        multi.sessionKeyDrmInitData,
    )
}

/** Original renditions are DEFAULT; dubs never are. Other names keep the manifest's flags. */
@UnstableApi
internal fun preferredSelectionFlags(name: String?, current: @C.SelectionFlags Int): @C.SelectionFlags Int = when {
    AudioTrackIdentity.isOriginalLabel(name) -> current or C.SELECTION_FLAG_DEFAULT
    AudioTrackIdentity.isDubbedLabel(name) -> current and C.SELECTION_FLAG_DEFAULT.inv()
    else -> current
}

/** Originals are the MAIN programme audio; dubs are an alternate rendition. */
@UnstableApi
internal fun preferredRoleFlags(name: String?, current: @C.RoleFlags Int): @C.RoleFlags Int = when {
    AudioTrackIdentity.isOriginalLabel(name) -> current or C.ROLE_FLAG_MAIN
    AudioTrackIdentity.isDubbedLabel(name) -> (current and C.ROLE_FLAG_MAIN.inv()) or C.ROLE_FLAG_DUB
    else -> current
}

/** One audio codec, or null when the string is missing or mixed. */
@UnstableApi
private fun singleAudioCodec(codecs: String?): String? {
    if (codecs.isNullOrBlank()) return null
    val audio = Util.getCodecsOfType(codecs, C.TRACK_TYPE_AUDIO) ?: return null
    return audio.takeIf { Util.getCodecCountOfType(it, C.TRACK_TYPE_AUDIO) == 1 }
}

@UnstableApi
internal class AudioCodecHlsPlaylistParserFactory : HlsPlaylistParserFactory {
    private val delegate = DefaultHlsPlaylistParserFactory()

    override fun createPlaylistParser(): ParsingLoadable.Parser<HlsPlaylist> =
        stamp(delegate.createPlaylistParser())

    override fun createPlaylistParser(
        multivariantPlaylist: HlsMultivariantPlaylist,
        previousMediaPlaylist: HlsMediaPlaylist?,
    ): ParsingLoadable.Parser<HlsPlaylist> =
        stamp(delegate.createPlaylistParser(multivariantPlaylist, previousMediaPlaylist))

    private fun stamp(parser: ParsingLoadable.Parser<HlsPlaylist>) =
        ParsingLoadable.Parser { uri: Uri, inputStream: InputStream ->
            declareAudioCodecs(parser.parse(uri, inputStream))
        }
}
