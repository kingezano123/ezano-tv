package com.ezano.tv.data

import android.util.Base64
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.IOException
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class XtreamApi(private val p: Playlist, private val liveFormat: () -> String) {

    private val base: String = normalizeServer(p.server)
    private val u = enc(p.username)
    private val pw = enc(p.password)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    var serverTimeZone: String = ""
        private set

    private fun api(action: String? = null, extra: String = ""): String =
        "$base/player_api.php?username=$u&password=$pw" + (action?.let { "&action=$it" } ?: "") + extra

    data class Account(val active: Boolean, val status: String, val expiry: Long, val maxConnections: Int, val message: String)

    suspend fun authenticate(): Account {
        val text = Net.getText(api(), p.userAgent)
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: throw IOException("The server didn't return a valid response. Check the server URL.")
        val ui = root["user_info"] as? JsonObject ?: throw IOException("Login failed. Check your username and password.")
        val auth = ui.str("auth")
        if (auth == "0") throw IOException("Login failed. Check your username and password.")
        (root["server_info"] as? JsonObject)?.let { serverTimeZone = it.str("timezone") }
        val status = ui.str("status")
        return Account(
            active = status.equals("Active", true) || status.isBlank(),
            status = status,
            expiry = (ui.str("exp_date").toLongOrNull() ?: 0L) * 1000L,
            maxConnections = ui.str("max_connections").toIntOrNull() ?: 0,
            message = ui.str("message"),
        )
    }

    suspend fun loadLibrary(onProgress: (String) -> Unit): Library = coroutineScope {
        onProgress("Signing in…")
        val account = authenticate()
        if (!account.active) throw IOException("Account status: ${account.status}")
        onProgress("Loading channels, movies and series…")
        val liveCats = async { categories("get_live_categories", ContentType.LIVE) }
        val vodCats = async { categories("get_vod_categories", ContentType.MOVIE) }
        val serCats = async { categories("get_series_categories", ContentType.SERIES) }
        val live = async { liveStreams() }
        val vod = async { vodStreams() }
        val ser = async { seriesList() }
        Library(
            playlistId = p.id,
            liveCategories = liveCats.await(),
            channels = live.await(),
            movieCategories = vodCats.await(),
            movies = vod.await(),
            seriesCategories = serCats.await(),
            series = ser.await(),
            loadedAt = System.currentTimeMillis(),
            accountExpiry = account.expiry,
            maxConnections = account.maxConnections,
        )
    }

    private suspend fun categories(action: String, type: ContentType): List<Category> = runCatching {
        Net.open(api(action), p.userAgent) { stream ->
            val out = ArrayList<Category>()
            FlatJson.readArray(stream) { m -> out += Category(m.s("category_id"), m.s("category_name").trim(), type) }
            out
        }
    }.getOrDefault(emptyList())

    private suspend fun liveStreams(): List<Channel> = runCatching {
        Net.open(api("get_live_streams"), p.userAgent) { stream ->
            val out = ArrayList<Channel>(4096)
            var idx = 0
            FlatJson.readArray(stream) { m ->
                idx++
                val sid = m.i("stream_id")
                out += Channel(
                    id = sid.toString(),
                    number = m.i("num").takeIf { it > 0 } ?: idx,
                    name = m.s("name").trim(),
                    logo = m.s("stream_icon"),
                    categoryId = m.s("category_id"),
                    url = liveUrl(sid),
                    epgId = m.s("epg_channel_id"),
                    catchupDays = if (m.i("tv_archive") == 1) m.i("tv_archive_duration") else 0,
                    streamId = sid,
                )
            }
            out
        }
    }.getOrDefault(emptyList())

    private suspend fun vodStreams(): List<Movie> = runCatching {
        Net.open(api("get_vod_streams"), p.userAgent) { stream ->
            val out = ArrayList<Movie>(8192)
            FlatJson.readArray(stream) { m ->
                val sid = m.i("stream_id")
                val ext = m.s("container_extension").ifBlank { "mp4" }
                val name = m.s("name").trim()
                out += Movie(
                    id = sid.toString(),
                    name = name,
                    poster = m.s("stream_icon"),
                    categoryId = m.s("category_id"),
                    rating = m.d("rating"),
                    year = m.s("year").ifBlank { Regex("\\((\\d{4})\\)").find(name)?.groupValues?.get(1) ?: "" },
                    url = "$base/movie/$u/$pw/$sid.$ext",
                    added = m.l("added"),
                    streamId = sid,
                    extension = ext,
                )
            }
            out
        }
    }.getOrDefault(emptyList())

    private suspend fun seriesList(): List<Series> = runCatching {
        Net.open(api("get_series"), p.userAgent) { stream ->
            val out = ArrayList<Series>(4096)
            FlatJson.readArray(stream) { m ->
                val id = m.i("series_id")
                out += Series(
                    id = id.toString(),
                    name = m.s("name").trim(),
                    poster = m.s("cover"),
                    backdrop = m.s("backdrop_path"),
                    categoryId = m.s("category_id"),
                    rating = m.d("rating"),
                    plot = m.s("plot"),
                    year = m.s("releaseDate").take(4).ifBlank { m.s("year") },
                    genre = m.s("genre"),
                    added = m.l("last_modified"),
                    seriesId = id,
                )
            }
            out
        }
    }.getOrDefault(emptyList())

    fun liveUrl(streamId: Int): String {
        val ext = if (liveFormat() == "m3u8") "m3u8" else "ts"
        return "$base/live/$u/$pw/$streamId.$ext"
    }

    fun epgUrl(): String = "$base/xmltv.php?username=$u&password=$pw"

    /** Catch-up / timeshift URL for a past programme. */
    fun catchupUrl(streamId: Int, start: Long, stop: Long): String {
        val minutes = ((stop - start) / 60000L).coerceAtLeast(1)
        val fmt = SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US)
        if (serverTimeZone.isNotBlank()) fmt.timeZone = TimeZone.getTimeZone(serverTimeZone)
        return "$base/timeshift/$u/$pw/$minutes/${fmt.format(Date(start))}/$streamId.ts"
    }

    suspend fun movieInfo(movie: Movie): MovieInfo {
        val text = Net.getText(api("get_vod_info", "&vod_id=${movie.streamId}"), p.userAgent)
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return MovieInfo(movie)
        val info = root["info"] as? JsonObject ?: return MovieInfo(movie)
        return MovieInfo(
            movie = movie,
            plot = info.str("plot").ifBlank { info.str("description") },
            cast = info.str("cast").ifBlank { info.str("actors") },
            director = info.str("director"),
            genre = info.str("genre"),
            duration = info.str("duration"),
            backdrop = info.firstOf("backdrop_path").ifBlank { info.str("cover_big") }.ifBlank { info.str("movie_image") },
            trailerYoutubeId = info.str("youtube_trailer"),
            rating = info.str("rating").toDoubleOrNull() ?: movie.rating,
            releaseDate = info.str("releasedate").ifBlank { info.str("release_date") },
        )
    }

    suspend fun seriesInfo(series: Series): SeriesInfo {
        val text = Net.getText(api("get_series_info", "&series_id=${series.seriesId}"), p.userAgent)
        val root = json.parseToJsonElement(text) as? JsonObject ?: throw IOException("No series info")
        val info = root["info"] as? JsonObject
        val episodesEl = root["episodes"]
        val map = sortedMapOf<Int, MutableList<Episode>>()
        fun addEp(seasonKey: Int, e: JsonObject) {
            val epInfo = e["info"] as? JsonObject
            val id = e.str("id")
            val ext = e.str("container_extension").ifBlank { "mp4" }
            val season = e.str("season").toIntOrNull() ?: seasonKey
            map.getOrPut(season) { mutableListOf() } += Episode(
                id = id,
                title = e.str("title"),
                season = season,
                episode = e.str("episode_num").toIntOrNull() ?: 0,
                plot = epInfo?.str("plot") ?: "",
                image = epInfo?.str("movie_image") ?: "",
                durationSecs = epInfo?.str("duration_secs")?.toIntOrNull() ?: 0,
                url = "$base/series/$u/$pw/$id.$ext",
            )
        }
        when (episodesEl) {
            is JsonObject -> episodesEl.forEach { (k, v) ->
                (v as? JsonArray)?.forEach { (it as? JsonObject)?.let { e -> addEp(k.toIntOrNull() ?: 0, e) } }
            }
            is JsonArray -> episodesEl.forEach { arr ->
                (arr as? JsonArray)?.forEach { (it as? JsonObject)?.let { e -> addEp(0, e) } }
            }
            else -> {}
        }
        map.values.forEach { list -> list.sortBy { it.episode } }
        return SeriesInfo(
            series = series,
            backdrop = info?.firstOf("backdrop_path")?.ifBlank { null } ?: series.backdrop.ifBlank { series.poster },
            plot = info?.str("plot")?.ifBlank { null } ?: series.plot,
            cast = info?.str("cast") ?: "",
            genre = info?.str("genre")?.ifBlank { null } ?: series.genre,
            seasons = map.keys.toList(),
            episodes = map,
        )
    }

    /** Short EPG for a single channel (used when XMLTV has nothing for it). */
    suspend fun shortEpg(channel: Channel, limit: Int = 6): List<Programme> = runCatching {
        val text = Net.getText(api("get_short_epg", "&stream_id=${channel.streamId}&limit=$limit"), p.userAgent)
        val root = json.parseToJsonElement(text) as? JsonObject ?: return emptyList()
        val list = root["epg_listings"] as? JsonArray ?: return emptyList()
        list.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val start = (o.str("start_timestamp").toLongOrNull() ?: return@mapNotNull null) * 1000
            val stop = (o.str("stop_timestamp").toLongOrNull() ?: return@mapNotNull null) * 1000
            Programme(channel.epgId.ifBlank { "id:" + channel.id }, start, stop, b64(o.str("title")), b64(o.str("description")))
        }
    }.getOrDefault(emptyList())

    companion object {
        fun normalizeServer(raw: String): String {
            var s = raw.trim().trimEnd('/')
            if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "http://$s"
            s = s.removeSuffix("/player_api.php").removeSuffix("/get.php").trimEnd('/')
            return s
        }

        private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")

        fun b64(s: String): String = runCatching {
            if (s.isBlank()) "" else String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8)
        }.getOrDefault(s)

        /** Parses a full Xtream "get.php" M3U link into login details, if it is one. */
        fun fromM3uLink(url: String): Triple<String, String, String>? {
            val m = Regex("^(https?://[^/]+)/get\\.php\\?(.*)$", RegexOption.IGNORE_CASE).find(url.trim()) ?: return null
            val params = m.groupValues[2].split('&').mapNotNull {
                val kv = it.split('=', limit = 2); if (kv.size == 2) kv[0].lowercase() to java.net.URLDecoder.decode(kv[1], "UTF-8") else null
            }.toMap()
            val user = params["username"] ?: return null
            val pass = params["password"] ?: return null
            return Triple(m.groupValues[1], user, pass)
        }
    }
}

internal fun JsonObject.str(key: String): String = when (val v = this[key]) {
    is JsonPrimitive -> v.contentOrNull ?: ""
    else -> ""
}

internal fun JsonObject.firstOf(key: String): String = when (val v: JsonElement? = this[key]) {
    is JsonArray -> (v.firstOrNull() as? JsonPrimitive)?.contentOrNull ?: ""
    is JsonPrimitive -> v.contentOrNull ?: ""
    else -> ""
}
