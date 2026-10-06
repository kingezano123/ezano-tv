package com.ezano.tv.data

import kotlinx.serialization.Serializable

@Serializable
enum class SourceType { XTREAM, M3U }

/** A saved IPTV login. */
@Serializable
data class Playlist(
    val id: String,
    val name: String,
    val type: SourceType,
    val server: String = "",
    val username: String = "",
    val password: String = "",
    val m3uUrl: String = "",
    val epgUrl: String = "",
    val userAgent: String = "",
)

@Serializable
enum class ContentType { LIVE, MOVIE, SERIES }

@Serializable
data class Category(
    val id: String,
    val name: String,
    val type: ContentType,
)

@Serializable
data class Channel(
    val id: String,
    val number: Int,
    val name: String,
    val logo: String = "",
    val categoryId: String = "",
    val url: String,
    val epgId: String = "",
    /** Days of catch-up/timeshift available (Xtream tv_archive_duration). 0 = none. */
    val catchupDays: Int = 0,
    val streamId: Int = 0,
)

@Serializable
data class Movie(
    val id: String,
    val name: String,
    val poster: String = "",
    val categoryId: String = "",
    val rating: Double = 0.0,
    val year: String = "",
    val url: String,
    val added: Long = 0,
    val streamId: Int = 0,
    val extension: String = "mp4",
)

@Serializable
data class Series(
    val id: String,
    val name: String,
    val poster: String = "",
    val backdrop: String = "",
    val categoryId: String = "",
    val rating: Double = 0.0,
    val plot: String = "",
    val year: String = "",
    val genre: String = "",
    val added: Long = 0,
    val seriesId: Int = 0,
)

@Serializable
data class Episode(
    val id: String,
    val title: String,
    val season: Int,
    val episode: Int,
    val plot: String = "",
    val image: String = "",
    val durationSecs: Int = 0,
    val url: String,
)

data class SeriesInfo(
    val series: Series,
    val backdrop: String,
    val plot: String,
    val cast: String,
    val genre: String,
    val seasons: List<Int>,
    val episodes: Map<Int, List<Episode>>,
)

data class MovieInfo(
    val movie: Movie,
    val plot: String = "",
    val cast: String = "",
    val director: String = "",
    val genre: String = "",
    val duration: String = "",
    val backdrop: String = "",
    val trailerYoutubeId: String = "",
    val rating: Double = 0.0,
    val releaseDate: String = "",
)

@Serializable
data class Programme(
    val channelKey: String,
    val start: Long,
    val stop: Long,
    val title: String,
    val desc: String = "",
) {
    fun isLive(now: Long = System.currentTimeMillis()) = now in start until stop
    fun progress(now: Long = System.currentTimeMillis()): Float =
        if (stop <= start) 0f else ((now - start).toFloat() / (stop - start)).coerceIn(0f, 1f)
}

/** Everything loaded from one playlist. Cached to disk as JSON for instant startup. */
@Serializable
data class Library(
    val playlistId: String,
    val liveCategories: List<Category> = emptyList(),
    val channels: List<Channel> = emptyList(),
    val movieCategories: List<Category> = emptyList(),
    val movies: List<Movie> = emptyList(),
    val seriesCategories: List<Category> = emptyList(),
    val series: List<Series> = emptyList(),
    /** Only filled for M3U playlists (Xtream fetches episodes on demand). */
    val m3uEpisodes: Map<String, List<Episode>> = emptyMap(),
    val loadedAt: Long = 0,
    val accountExpiry: Long = 0,
    val maxConnections: Int = 0,
    /** EPG url discovered in the M3U header (url-tvg). */
    val discoveredEpgUrl: String = "",
)

/** Item that can be resumed / shown in Continue Watching. */
@Serializable
data class HistoryEntry(
    val key: String,          // "live:<id>", "movie:<id>", "ep:<seriesId>:<episodeId>"
    val type: ContentType,
    val title: String,
    val subtitle: String = "",
    val image: String = "",
    val url: String,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val updatedAt: Long = System.currentTimeMillis(),
    val refId: String = "",   // channel/movie/series id
) {
    val progress: Float get() = if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
}

@Serializable
data class Reminder(
    val channelId: String,
    val channelName: String,
    val title: String,
    val start: Long,
)

@Serializable
data class AppSettings(
    val liveFormat: String = "ts",            // "ts" or "m3u8" for Xtream live
    val epgHoursAhead: Int = 72,
    val epgTimeShiftMinutes: Int = 0,
    val bufferSeconds: Int = 30,
    val defaultAspect: Int = 0,               // 0 fit, 1 fill, 2 zoom
    val autoplayNext: Boolean = true,
    val showAdult: Boolean = false,
    val parentalPin: String = "",
    val use24h: Boolean = false,
    val previewOnFocus: Boolean = true,
    val hiddenCategories: Set<String> = emptySet(),
    val preferredAudioLang: String = "",
    val preferredSubtitleLang: String = "",
    val subtitlesEnabled: Boolean = false,
)
