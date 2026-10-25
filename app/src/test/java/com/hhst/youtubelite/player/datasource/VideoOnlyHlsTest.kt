package com.hhst.youtubelite.player.datasource

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoOnlyHlsTest {

    @Test fun declareAudioCodecsStampsRenditionsThatOmitCodecs() {
        val format = androidx.media3.common.Format.Builder()
            .setId("v")
            .setCodecs("mp4a.40.5,avc1.4d4015")
            .setSampleMimeType(MimeTypes.VIDEO_MP4)
            .build()
        val variant = HlsMultivariantPlaylist.Variant(
            Uri.parse("https://example.com/v.m3u8"),
            format,
            null,
            "233",
            null,
            null,
            null,
            null,
        )
        val english = androidx.media3.common.Format.Builder()
            .setId("en")
            .setLabel("English (US) original")
            .setLanguage("en-US")
            .build()
        val japanese = androidx.media3.common.Format.Builder()
            .setId("ja")
            .setLabel("Japanese")
            .setLanguage("ja")
            .setCodecs("mp4a.40.2")
            .build()
        val playlist = HlsMultivariantPlaylist(
            "https://example.com/master.m3u8",
            emptyList(),
            listOf(variant),
            emptyList(),
            listOf(
                HlsMultivariantPlaylist.Rendition(Uri.parse("https://example.com/en.m3u8"), english, "233", "English (US) original", null),
                HlsMultivariantPlaylist.Rendition(Uri.parse("https://example.com/ja.m3u8"), japanese, "233", "Japanese", null),
            ),
            emptyList(),
            emptyList(),
            null,
            emptyList(),
            true,
            emptyMap(),
            emptyList(),
        )
        val declared = declareAudioCodecs(playlist) as HlsMultivariantPlaylist
        assertEquals(2, declared.audios.size)
        assertEquals("mp4a.40.5", declared.audios[0].format.codecs)
        assertEquals(MimeTypes.AUDIO_AAC, declared.audios[0].format.sampleMimeType)
        assertEquals(2, declared.audios[0].format.channelCount)
        assertEquals(48_000, declared.audios[0].format.sampleRate)
        assertEquals("English (US) original", declared.audios[0].name)
        assertEquals("mp4a.40.2", declared.audios[1].format.codecs)
        assertEquals(2, declared.audios[1].format.channelCount)
    }

    /**
     * Real master for a Chinese video with an auto-dub (Q1EoisvxUQw): both
     * renditions AUTOSELECT only, the dub listed first. Media3 took the dub.
     */
    @Test fun declareAudioCodecs_marksOriginalDefault_andDubsNotDefault() {
        val variant = HlsMultivariantPlaylist.Variant(
            Uri.parse("https://example.com/v.m3u8"),
            androidx.media3.common.Format.Builder().setId("v").setCodecs("mp4a.40.2,avc1.4d4015").setSampleMimeType(MimeTypes.VIDEO_MP4).build(),
            null, "233", null, null, null, null,
        )
        fun rendition(name: String, language: String) = HlsMultivariantPlaylist.Rendition(
            Uri.parse("https://example.com/$language.m3u8"),
            androidx.media3.common.Format.Builder().setId("233:$name").setLabel(name).setLanguage(language)
                .setCodecs("mp4a.40.2").setChannelCount(2).setSampleRate(48_000)
                .setSelectionFlags(C.SELECTION_FLAG_AUTOSELECT).build(),
            "233", name, null,
        )
        val playlist = HlsMultivariantPlaylist(
            "https://example.com/master.m3u8", emptyList(), listOf(variant), emptyList(),
            listOf(rendition("American English - dubbed-auto", "en-us"), rendition("zh-Hant - original", "zh-hant")),
            emptyList(), emptyList(), null, emptyList(), true, emptyMap(), emptyList(),
        )
        val declared = declareAudioCodecs(playlist) as HlsMultivariantPlaylist
        val dub = declared.audios[0].format
        val original = declared.audios[1].format
        assertEquals(0, dub.selectionFlags and C.SELECTION_FLAG_DEFAULT)
        assertEquals(C.ROLE_FLAG_DUB, dub.roleFlags and (C.ROLE_FLAG_DUB or C.ROLE_FLAG_MAIN))
        assertEquals(C.SELECTION_FLAG_DEFAULT, original.selectionFlags and C.SELECTION_FLAG_DEFAULT)
        assertEquals(C.ROLE_FLAG_MAIN, original.roleFlags and C.ROLE_FLAG_MAIN)
        // AUTOSELECT and the manifest's own name/language survive the rewrite.
        assertEquals(C.SELECTION_FLAG_AUTOSELECT, original.selectionFlags and C.SELECTION_FLAG_AUTOSELECT)
        assertEquals("zh-Hant - original", declared.audios[1].name)
    }

    @Test fun preferredFlags_leaveUnnamedKindsAlone() {
        assertEquals(C.SELECTION_FLAG_AUTOSELECT, preferredSelectionFlags("Japanese", C.SELECTION_FLAG_AUTOSELECT))
        assertEquals(0, preferredRoleFlags("Japanese", 0))
        // A manifest that already marks the original DEFAULT keeps it.
        assertEquals(C.SELECTION_FLAG_DEFAULT or C.SELECTION_FLAG_AUTOSELECT,
            preferredSelectionFlags("English (US) original", C.SELECTION_FLAG_DEFAULT or C.SELECTION_FLAG_AUTOSELECT))
        // A manifest that (wrongly) marks the dub DEFAULT loses it.
        assertEquals(0, preferredSelectionFlags("Deutsch - dubbed", C.SELECTION_FLAG_DEFAULT))
        assertEquals(0, preferredSelectionFlags("中文（简体）配音", C.SELECTION_FLAG_DEFAULT))
    }
}
