package com.ezano.tv.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Tracks
import java.util.Locale

/** One selectable row in the Audio & Subtitles panel. [group] == null means "Off". */
data class TrackOption(
    val title: String,
    val detail: String,
    val selected: Boolean,
    val group: Tracks.Group?,
    val index: Int,
)

object TrackOptions {

    fun audio(tracks: Tracks): List<TrackOption> = build(tracks, C.TRACK_TYPE_AUDIO)

    /** Subtitle options, always starting with "Off". */
    fun text(tracks: Tracks): List<TrackOption> {
        val list = build(tracks, C.TRACK_TYPE_TEXT)
        val off = TrackOption("Off", "", list.none { it.selected }, null, -1)
        return listOf(off) + list
    }

    private fun build(tracks: Tracks, type: Int): List<TrackOption> {
        val out = ArrayList<TrackOption>()
        for (group in tracks.groups) {
            if (group.type != type) continue
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                val f = group.getTrackFormat(i)
                out += TrackOption(
                    title = title(f, type, out.size + 1),
                    detail = detail(f, type),
                    selected = group.isTrackSelected(i),
                    group = group,
                    index = i,
                )
            }
        }
        return out
    }

    fun languageName(code: String?): String? {
        if (code.isNullOrBlank() || code == C.LANGUAGE_UNDETERMINED) return null
        val name = runCatching { Locale.forLanguageTag(code).getDisplayLanguage(Locale.getDefault()) }.getOrNull()
        if (name.isNullOrBlank() || name.equals(code, ignoreCase = true)) return code.uppercase(Locale.ROOT)
        return name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
    }

    private fun title(f: Format, type: Int, n: Int): String {
        val lang = languageName(f.language)
        val label = f.label?.trim()?.takeIf { it.isNotEmpty() && !it.equals(lang, ignoreCase = true) }
        val parts = listOfNotNull(lang, label)
        if (parts.isNotEmpty()) return parts.joinToString(" · ")
        return if (type == C.TRACK_TYPE_AUDIO) "Audio track $n" else "Subtitles $n"
    }

    private fun detail(f: Format, type: Int): String {
        val parts = ArrayList<String>()
        if (type == C.TRACK_TYPE_AUDIO) {
            when (f.channelCount) {
                1 -> parts += "Mono"
                2 -> parts += "Stereo"
                6 -> parts += "5.1"
                8 -> parts += "7.1"
                else -> if (f.channelCount > 0) parts += "${f.channelCount} ch"
            }
            codecName(f.sampleMimeType)?.let { parts += it }
            if (f.bitrate > 0) parts += "${f.bitrate / 1000} kbps"
        } else {
            if (f.selectionFlags and C.SELECTION_FLAG_FORCED != 0) parts += "Forced"
            if (f.roleFlags and C.ROLE_FLAG_CAPTION != 0) parts += "CC"
            codecName(f.sampleMimeType)?.let { parts += it }
        }
        return parts.joinToString(" · ")
    }

    private fun codecName(mime: String?): String? = when (mime) {
        null -> null
        "audio/mp4a-latm" -> "AAC"
        "audio/ac3" -> "Dolby Digital"
        "audio/eac3" -> "Dolby Digital+"
        "audio/eac3-joc" -> "Dolby Atmos"
        "audio/ac4" -> "Dolby AC-4"
        "audio/true-hd" -> "Dolby TrueHD"
        "audio/vnd.dts" -> "DTS"
        "audio/vnd.dts.hd" -> "DTS-HD"
        "audio/mpeg" -> "MP3"
        "audio/mpeg-L2" -> "MP2"
        "audio/opus" -> "Opus"
        "audio/vorbis" -> "Vorbis"
        "audio/flac" -> "FLAC"
        "text/vtt" -> "WebVTT"
        "application/x-subrip" -> "SRT"
        "text/x-ssa" -> "SSA"
        "application/ttml+xml" -> "TTML"
        "application/cea-608", "application/cea-708" -> "Closed captions"
        "application/dvbsubs" -> "DVB"
        "application/pgs" -> "PGS"
        else -> null
    }
}

/** 4K / FHD / HD / SD badge from the decoded video size. */
fun resolutionLabel(width: Int, height: Int): String? = when {
    width <= 0 || height <= 0 -> null
    height >= 2000 || width >= 3800 -> "4K"
    height >= 1000 || width >= 1900 -> "FHD"
    height >= 700 || width >= 1260 -> "HD"
    else -> "SD"
}
