package com.ezano.tv.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.io.File
import java.text.Normalizer
import java.util.UUID

sealed interface LoadState {
    data object Idle : LoadState
    data class Loading(val message: String) : LoadState
    data class Error(val message: String) : LoadState
    data object Ready : LoadState
}

sealed interface EpgState {
    data object Idle : EpgState
    data class Loading(val message: String) : EpgState
    data class Ready(val programmes: Int) : EpgState
    data class Failed(val message: String) : EpgState
}

/** Pre-computed lookups for a loaded library. */
class LibraryIndex(val lib: Library) {
    val channelById: Map<String, Channel> = lib.channels.associateBy { it.id }
    val movieById: Map<String, Movie> = lib.movies.associateBy { it.id }
    val seriesById: Map<String, Series> = lib.series.associateBy { it.id }
    val channelsByCat: Map<String, List<Channel>> = lib.channels.groupBy { it.categoryId }
    val moviesByCat: Map<String, List<Movie>> = lib.movies.groupBy { it.categoryId }
    val seriesByCat: Map<String, List<Series>> = lib.series.groupBy { it.categoryId }
    val categoryById: Map<String, Category> =
        (lib.liveCategories + lib.movieCategories + lib.seriesCategories).associateBy { "${it.type}:${it.id}" }
    val recentMovies: List<Movie> by lazy { lib.movies.sortedByDescending { it.added }.take(60) }
    val recentSeries: List<Series> by lazy { lib.series.sortedByDescending { it.added }.take(60) }
    val topMovies: List<Movie> by lazy { lib.movies.filter { it.rating in 7.0..10.0 }.sortedByDescending { it.rating }.take(60) }
    val topSeries: List<Series> by lazy { lib.series.filter { it.rating in 7.0..10.0 }.sortedByDescending { it.rating }.take(60) }

    // normalized names for fast search
    val channelNames: Array<String> by lazy { Array(lib.channels.size) { Repo.normalize(lib.channels[it].name) } }
    val movieNames: Array<String> by lazy { Array(lib.movies.size) { Repo.normalize(lib.movies[it].name) } }
    val seriesNames: Array<String> by lazy { Array(lib.series.size) { Repo.normalize(lib.series[it].name) } }
}

data class SearchResults(
    val channels: List<Channel> = emptyList(),
    val movies: List<Movie> = emptyList(),
    val series: List<Series> = emptyList(),
) {
    val isEmpty get() = channels.isEmpty() && movies.isEmpty() && series.isEmpty()
}

@OptIn(ExperimentalSerializationApi::class)
object Repo {
    lateinit var store: Store
        private set
    private lateinit var filesDir: File
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private val _index = MutableStateFlow<LibraryIndex?>(null)
    val index: StateFlow<LibraryIndex?> = _index.asStateFlow()

    private val _loadState = MutableStateFlow<LoadState>(LoadState.Idle)
    val loadState: StateFlow<LoadState> = _loadState.asStateFlow()

    private val _epg = MutableStateFlow(EpgData.EMPTY)
    val epg: StateFlow<EpgData> = _epg.asStateFlow()

    private val _epgState = MutableStateFlow<EpgState>(EpgState.Idle)
    val epgState: StateFlow<EpgState> = _epgState.asStateFlow()

    /** Ticks every 30s so "now playing" UI refreshes. */
    private val _clock = MutableStateFlow(System.currentTimeMillis())
    val clock: StateFlow<Long> = _clock.asStateFlow()

    private var xtream: XtreamApi? = null
    private var loadJob: Job? = null
    private var epgJob: Job? = null

    val library: Library? get() = _index.value?.lib
    val settings: AppSettings get() = store.settings.value

    fun init(context: Context) {
        store = Store(context)
        filesDir = context.filesDir
        Net.init(context.cacheDir)
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(30_000)
                _clock.value = System.currentTimeMillis()
            }
        }
    }

    // ------------------------------------------------------------------ login & loading

    /** Validates credentials, loads everything, and saves the playlist on success. */
    suspend fun login(
        name: String,
        type: SourceType,
        server: String = "",
        username: String = "",
        password: String = "",
        m3uUrl: String = "",
        epgUrl: String = "",
        onProgress: (String) -> Unit = {},
    ): Result<Playlist> = withContext(Dispatchers.IO) {
        runCatching {
            var t = type; var srv = server; var user = username; var pass = password
            // If someone pastes an Xtream get.php link as M3U, use the faster Xtream API instead.
            if (t == SourceType.M3U) XtreamApi.fromM3uLink(m3uUrl)?.let { (s, u, p) -> t = SourceType.XTREAM; srv = s; user = u; pass = p }
            val playlist = Playlist(
                id = UUID.randomUUID().toString().take(8),
                name = name.ifBlank { if (t == SourceType.XTREAM) hostOf(srv) else hostOf(m3uUrl) },
                type = t,
                server = if (t == SourceType.XTREAM) XtreamApi.normalizeServer(srv) else "",
                username = user.trim(), password = pass.trim(),
                m3uUrl = m3uUrl.trim(), epgUrl = epgUrl.trim(),
            )
            val lib = fetchLibrary(playlist, onProgress)
            if (lib.channels.isEmpty() && lib.movies.isEmpty() && lib.series.isEmpty()) error("The playlist loaded but contains no channels or videos.")
            store.savePlaylist(playlist, makeActive = true)
            saveCache(lib)
            setLibrary(playlist, lib)
            _loadState.value = LoadState.Ready
            refreshEpg(force = true)
            playlist
        }.recoverCatching { e -> throw Exception(friendly(e), e) }
    }

    /** Called at app start / after switching playlists. Instant from cache, refreshes in background if stale. */
    fun loadActive(forceRefresh: Boolean = false) {
        val p = store.activePlaylist ?: run { _loadState.value = LoadState.Idle; return }
        loadJob?.cancel()
        loadJob = scope.launch(Dispatchers.IO) {
            val cached = if (_index.value?.lib?.playlistId == p.id && !forceRefresh) _index.value?.lib else loadCache(p.id)
            if (cached != null) {
                setLibrary(p, cached)
                _loadState.value = LoadState.Ready
                if (_epg.value.isEmpty) loadEpgCache(p.id)
            } else {
                _loadState.value = LoadState.Loading("Loading your playlist…")
            }
            val stale = cached == null || forceRefresh || System.currentTimeMillis() - cached.loadedAt > 12 * 3600_000L
            if (stale) {
                try {
                    val lib = fetchLibrary(p) { msg -> if (cached == null) _loadState.value = LoadState.Loading(msg) }
                    saveCache(lib)
                    setLibrary(p, lib)
                    _loadState.value = LoadState.Ready
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    if (cached == null) _loadState.value = LoadState.Error(friendly(e))
                }
            } else {
                setupApi(p)
            }
            val epgAge = System.currentTimeMillis() - _epg.value.loadedAt
            if (_epg.value.isEmpty || epgAge > 8 * 3600_000L || forceRefresh) refreshEpg(force = true)
        }
    }

    fun switchPlaylist(id: String) {
        store.setActive(id)
        _index.value = null
        _epg.value = EpgData.EMPTY
        _epgState.value = EpgState.Idle
        loadActive()
    }

    fun logout(id: String) {
        store.removePlaylist(id)
        File(filesDir, "lib_$id.json").delete()
        File(filesDir, "epg_$id.bin").delete()
        _index.value = null
        _epg.value = EpgData.EMPTY
        if (store.activePlaylist != null) loadActive() else _loadState.value = LoadState.Idle
    }

    private fun setupApi(p: Playlist) {
        xtream = if (p.type == SourceType.XTREAM) XtreamApi(p) { store.settings.value.liveFormat } else null
    }

    private suspend fun fetchLibrary(p: Playlist, onProgress: (String) -> Unit): Library {
        setupApi(p)
        return when (p.type) {
            SourceType.XTREAM -> xtream!!.loadLibrary(onProgress)
            SourceType.M3U -> {
                onProgress("Downloading playlist…")
                val r = Net.open(p.m3uUrl, p.userAgent) { M3uParser.parse(p.id, it) }
                r.library.copy(discoveredEpgUrl = r.epgUrl)
            }
        }
    }

    private fun setLibrary(p: Playlist, lib: Library) {
        if (xtream == null && p.type == SourceType.XTREAM) setupApi(p)
        // Re-generate live URLs if the user changed live format after caching.
        val fixed = if (p.type == SourceType.XTREAM && xtream != null) {
            val wantM3u8 = store.settings.value.liveFormat == "m3u8"
            val first = lib.channels.firstOrNull()
            if (first != null && first.url.endsWith(".m3u8") != wantM3u8) lib.copy(channels = lib.channels.map { it.copy(url = xtream!!.liveUrl(it.streamId)) }) else lib
        } else lib
        _index.value = LibraryIndex(fixed)
    }

    fun reapplyLiveFormat() {
        val p = store.activePlaylist ?: return
        val lib = library ?: return
        scope.launch { setLibrary(p, lib) }
    }

    private fun saveCache(lib: Library) = runCatching {
        File(filesDir, "lib_${lib.playlistId}.json").outputStream().buffered(256 * 1024).use { json.encodeToStream(Library.serializer(), lib, it) }
    }

    private fun loadCache(id: String): Library? = runCatching {
        val f = File(filesDir, "lib_$id.json")
        if (!f.exists()) null else f.inputStream().buffered(256 * 1024).use { json.decodeFromStream(Library.serializer(), it) }
    }.getOrNull()

    // ------------------------------------------------------------------ EPG

    fun refreshEpg(force: Boolean = false) {
        val p = store.activePlaylist ?: return
        if (epgJob?.isActive == true && !force) return
        epgJob?.cancel()
        epgJob = scope.launch(Dispatchers.IO) {
            val lib = library ?: return@launch
            val url = when {
                p.epgUrl.isNotBlank() -> p.epgUrl
                p.type == SourceType.XTREAM -> xtream?.epgUrl() ?: XtreamApi(p) { "ts" }.epgUrl()
                lib.discoveredEpgUrl.isNotBlank() -> lib.discoveredEpgUrl
                else -> ""
            }
            if (url.isBlank()) { _epgState.value = EpgState.Failed("No TV guide (EPG) source for this playlist"); return@launch }
            _epgState.value = EpgState.Loading("Updating TV guide…")
            try {
                val s = store.settings.value
                val now = System.currentTimeMillis()
                val maxCatchup = lib.channels.maxOfOrNull { it.catchupDays }?.coerceAtMost(7) ?: 0
                val from = now - maxOf(6L * 3600_000L, maxCatchup * 24L * 3600_000L)
                val to = now + s.epgHoursAhead * 3600_000L
                val keys = lib.channels.mapNotNullTo(HashSet()) { c -> c.epgId.takeIf { it.isNotBlank() }?.let(EpgParser::norm) }
                val names = lib.channels.mapTo(HashSet()) { EpgParser.normName(it.name) }
                val data = Net.open(url, p.userAgent) {
                    EpgParser.parse(it, from, to, keys, names, s.epgTimeShiftMinutes * 60_000L)
                }
                _epg.value = data
                _epgState.value = EpgState.Ready(data.programmeCount)
                EpgParser.save(File(filesDir, "epg_${p.id}.bin"), data)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _epgState.value = if (_epg.value.isEmpty) EpgState.Failed("TV guide couldn't load: ${friendly(e)}") else EpgState.Ready(_epg.value.programmeCount)
            }
        }
    }

    private fun loadEpgCache(id: String) {
        EpgParser.load(File(filesDir, "epg_$id.bin"))?.let {
            _epg.value = it
            _epgState.value = EpgState.Ready(it.programmeCount)
        }
    }

    /** Programmes for a channel; falls back to Xtream short EPG when XMLTV has nothing. */
    suspend fun programmesFor(channel: Channel): List<Programme> {
        val list = _epg.value.programmes(channel)
        if (list.isNotEmpty()) return list
        return xtream?.shortEpg(channel) ?: emptyList()
    }

    // ------------------------------------------------------------------ lookups

    fun isAdult(c: Category) = c.name.lowercase().let { n -> listOf("adult", "xxx", "18+", "porn", "for adults").any { it in n } }

    fun visibleCategories(type: ContentType): List<Category> {
        val lib = library ?: return emptyList()
        val s = store.settings.value
        val list = when (type) {
            ContentType.LIVE -> lib.liveCategories
            ContentType.MOVIE -> lib.movieCategories
            ContentType.SERIES -> lib.seriesCategories
        }
        return list.filter { c -> (s.showAdult || !isAdult(c)) && "${c.type}:${c.id}" !in s.hiddenCategories }
    }

    private fun allowedCatIds(type: ContentType): Set<String> = visibleCategories(type).mapTo(HashSet()) { it.id }

    fun channels(categoryId: String? = null): List<Channel> {
        val idx = _index.value ?: return emptyList()
        if (categoryId != null) return idx.channelsByCat[categoryId] ?: emptyList()
        val ok = allowedCatIds(ContentType.LIVE)
        return idx.lib.channels.filter { it.categoryId in ok || it.categoryId.isBlank() }
    }

    fun movies(categoryId: String? = null): List<Movie> {
        val idx = _index.value ?: return emptyList()
        if (categoryId != null) return idx.moviesByCat[categoryId] ?: emptyList()
        val ok = allowedCatIds(ContentType.MOVIE)
        return idx.lib.movies.filter { it.categoryId in ok || it.categoryId.isBlank() }
    }

    fun series(categoryId: String? = null): List<Series> {
        val idx = _index.value ?: return emptyList()
        if (categoryId != null) return idx.seriesByCat[categoryId] ?: emptyList()
        val ok = allowedCatIds(ContentType.SERIES)
        return idx.lib.series.filter { it.categoryId in ok || it.categoryId.isBlank() }
    }

    fun channel(id: String) = _index.value?.channelById?.get(id)
    fun movie(id: String) = _index.value?.movieById?.get(id)
    fun seriesById(id: String) = _index.value?.seriesById?.get(id)
    fun category(type: ContentType, id: String) = _index.value?.categoryById?.get("$type:$id")

    suspend fun movieInfo(m: Movie): MovieInfo = withContext(Dispatchers.IO) {
        xtream?.let { runCatching { it.movieInfo(m) }.getOrNull() } ?: MovieInfo(m)
    }

    suspend fun seriesInfo(s: Series): SeriesInfo = withContext(Dispatchers.IO) {
        val api = xtream
        if (api != null) api.seriesInfo(s) else {
            val eps = library?.m3uEpisodes?.get(s.id) ?: emptyList()
            val bySeason = eps.groupBy { it.season }.toSortedMap()
            SeriesInfo(s, s.backdrop.ifBlank { s.poster }, s.plot, "", s.genre, bySeason.keys.toList(), bySeason)
        }
    }

    /** Catch-up URL for a past programme on an archive-enabled channel, or null. */
    fun catchupUrl(channel: Channel, p: Programme): String? {
        if (channel.catchupDays <= 0) return null
        if (p.start > System.currentTimeMillis()) return null
        val api = xtream
        if (api != null && channel.streamId > 0) return api.catchupUrl(channel.streamId, p.start, p.stop)
        // M3U "append"/"default" catchup convention
        val utc = p.start / 1000; val end = p.stop / 1000
        return channel.url + (if ('?' in channel.url) "&" else "?") + "utc=$utc&lutc=$end"
    }

    // ------------------------------------------------------------------ search

    fun normalize(s: String): String {
        val n = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        return n.replace(Regex("[^a-z0-9]+"), " ").trim()
    }

    fun search(query: String, limit: Int = 60): SearchResults {
        val idx = _index.value ?: return SearchResults()
        val q = normalize(query)
        if (q.isBlank()) return SearchResults()
        val tokens = q.split(' ').filter { it.isNotBlank() }
        fun score(name: String): Int {
            if (!tokens.all { name.contains(it) }) return -1
            return when {
                name == q -> 0
                name.startsWith(q) -> 1
                name.contains(" $q") -> 2
                else -> 3
            }
        }
        val s = store.settings.value
        val okLive = allowedCatIds(ContentType.LIVE); val okMov = allowedCatIds(ContentType.MOVIE); val okSer = allowedCatIds(ContentType.SERIES)
        val ch = idx.lib.channels.indices.mapNotNull { i -> val sc = score(idx.channelNames[i]); if (sc >= 0) sc to idx.lib.channels[i] else null }
            .filter { s.showAdult || it.second.categoryId in okLive || it.second.categoryId.isBlank() }
            .sortedBy { it.first }.take(limit).map { it.second }
        val mv = idx.lib.movies.indices.mapNotNull { i -> val sc = score(idx.movieNames[i]); if (sc >= 0) sc to idx.lib.movies[i] else null }
            .filter { it.second.categoryId in okMov || it.second.categoryId.isBlank() }
            .sortedBy { it.first }.take(limit).map { it.second }
        val se = idx.lib.series.indices.mapNotNull { i -> val sc = score(idx.seriesNames[i]); if (sc >= 0) sc to idx.lib.series[i] else null }
            .filter { it.second.categoryId in okSer || it.second.categoryId.isBlank() }
            .sortedBy { it.first }.take(limit).map { it.second }
        return SearchResults(ch, mv, se)
    }

    // ------------------------------------------------------------------ favorites helpers

    fun favoriteChannels(): List<Channel> = store.favoriteKeys().filter { it.startsWith("live:") }.mapNotNull { channel(it.removePrefix("live:")) }
    fun favoriteMovies(): List<Movie> = store.favoriteKeys().filter { it.startsWith("movie:") }.mapNotNull { movie(it.removePrefix("movie:")) }
    fun favoriteSeries(): List<Series> = store.favoriteKeys().filter { it.startsWith("series:") }.mapNotNull { seriesById(it.removePrefix("series:")) }

    // ------------------------------------------------------------------ utils

    private fun hostOf(url: String) = runCatching { java.net.URI(XtreamApi.normalizeServer(url)).host ?: "My IPTV" }.getOrDefault("My IPTV")

    fun friendly(e: Throwable): String {
        val m = e.message ?: e.javaClass.simpleName
        return when {
            e is java.net.UnknownHostException -> "Can't reach the server. Check the URL and your internet connection."
            e is java.net.SocketTimeoutException -> "The server took too long to respond. Try again."
            e is java.net.ConnectException -> "Couldn't connect to the server."
            m.contains("HTTP 401") || m.contains("HTTP 403") -> "Access denied by the server. Check your login details."
            m.contains("HTTP 404") -> "Not found (404). Check the URL."
            else -> m
        }
    }
}
