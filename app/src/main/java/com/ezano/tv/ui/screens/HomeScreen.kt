package com.ezano.tv.ui.screens

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.ezano.tv.data.Channel
import com.ezano.tv.data.ContentType
import com.ezano.tv.data.Episode
import com.ezano.tv.data.HistoryEntry
import com.ezano.tv.data.LibraryIndex
import com.ezano.tv.data.LoadState
import com.ezano.tv.data.Movie
import com.ezano.tv.data.MovieInfo
import com.ezano.tv.data.Repo
import com.ezano.tv.data.Series
import com.ezano.tv.data.SeriesInfo
import com.ezano.tv.player.Playback
import com.ezano.tv.ui.Layout
import com.ezano.tv.ui.LocalNavigator
import com.ezano.tv.ui.Screen
import com.ezano.tv.ui.components.AsyncImg
import com.ezano.tv.ui.components.EmptyView
import com.ezano.tv.ui.components.Fmt
import com.ezano.tv.ui.components.LiveBadge
import com.ezano.tv.ui.components.LoadingView
import com.ezano.tv.ui.components.MetaRow
import com.ezano.tv.ui.components.PillButton
import com.ezano.tv.ui.components.PosterCard
import com.ezano.tv.ui.components.WideCard
import com.ezano.tv.ui.components.contentRow
import com.ezano.tv.ui.theme.EzColors
import com.ezano.tv.ui.theme.EzType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

// ============================================================================ model

/** Everything the hero / spotlight area needs to show one item. */
private data class HomeSpot(
    val id: String,
    val type: ContentType,
    val label: String,
    val title: String,
    /** A real 16:9 backdrop (if known). */
    val backdrop: String = "",
    /** Portrait poster (movies/series) or logo (channels). */
    val poster: String = "",
    val meta: List<String> = emptyList(),
    val rating: Double = 0.0,
    val plot: String = "",
    val movie: Movie? = null,
    val series: Series? = null,
    val channel: Channel? = null,
)

/** What to paint behind the hero. */
private data class HomeArt(val url: String, val mode: Int)

private const val HOME_ART_NONE = 0
private const val HOME_ART_WIDE = 1
private const val HOME_ART_POSTER = 2
private const val HOME_ART_LOGO = 3

private class HomeRow(
    val title: String,
    val entries: List<HistoryEntry> = emptyList(),
    val channels: List<Channel> = emptyList(),
    val posters: List<Any> = emptyList(),
    /** Zap through this row's channels in the player (favorites) instead of the channel's category. */
    val zapRow: Boolean = false,
) {
    /** Same key contentRow() uses for its LazyColumn item. */
    val key: String get() = "row-$title"
}

private class HomeModel(val vod: Boolean, val featured: List<HomeSpot>, val rows: List<HomeRow>)

/** Movie details fetched for the hero (backdrop, plot…), keyed by stream url. Survives leaving Home. */
private object HomeInfoCache {
    val infos = mutableStateMapOf<String, MovieInfo>()
}

// ============================================================================ screen

@Composable
fun HomeScreen() {
    val idx by Repo.index.collectAsState()
    val history by Repo.store.history.collectAsState()
    val favs by Repo.store.favorites.collectAsState()
    val settings by Repo.store.settings.collectAsState()
    val loadState by Repo.loadState.collectAsState()

    val model by produceState<HomeModel?>(null, idx, history, favs, settings) {
        val i = idx
        value = if (i == null) null else withContext(Dispatchers.Default) {
            runCatching { buildHomeModel(i) }.getOrElse { HomeModel(false, emptyList(), emptyList()) }
        }
    }

    val m = model
    val ls = loadState
    Box(Modifier.fillMaxSize().background(EzColors.Bg)) {
        when {
            m != null && (m.featured.isNotEmpty() || m.rows.isNotEmpty()) -> HomeContent(m)
            m != null -> EmptyView(
                "Nothing to show yet",
                "Your playlist loaded, but there are no channels, movies or series to show here.",
            )
            ls is LoadState.Error -> EmptyView("Couldn't load your playlist", ls.message)
            else -> LoadingView(if (ls is LoadState.Loading) ls.message else "Loading your library…")
        }
    }
}

@Composable
private fun HomeContent(model: HomeModel) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val playFr = remember { FocusRequester() }
    val firstCardFr = remember { FocusRequester() }
    val featured = model.featured
    val hasHero = featured.isNotEmpty()
    val firstRowKey = model.rows.firstOrNull()?.key

    var heroIndex by remember { mutableIntStateOf(0) }
    /** true while focus is on the hero buttons; false while browsing the shelves. */
    var heroMode by remember { mutableStateOf(true) }
    /** Item focused in the shelves (immediate) and the debounced one actually displayed. */
    var spotlight by remember { mutableStateOf<HomeSpot?>(null) }
    var shownSpot by remember { mutableStateOf<HomeSpot?>(null) }
    var focusedRowKey by remember { mutableStateOf<String?>(null) }
    var interactions by remember { mutableIntStateOf(0) }

    val heroPos = if (hasHero) heroIndex % featured.size else 0
    val heroItem = if (hasHero) featured[heroPos] else null
    val baseSpot = if (heroMode) heroItem else (shownSpot ?: heroItem)
    val infos = HomeInfoCache.infos
    val baseInfo = baseSpot?.movie?.let { infos[it.url] }
    val display = if (baseSpot != null && baseInfo != null) baseSpot.enrich(baseInfo) else baseSpot

    // Debounce spotlight changes so fast D-pad scrolling doesn't thrash the backdrop.
    LaunchedEffect(spotlight) {
        val s = spotlight
        if (s != null) delay(170)
        shownSpot = s
    }
    // Lazily fetch real backdrop/plot for the movie currently on display.
    LaunchedEffect(baseSpot?.id) {
        val mv = baseSpot?.movie ?: return@LaunchedEffect
        if (infos.containsKey(mv.url)) return@LaunchedEffect
        delay(450)
        homeFetchInfo(mv)
    }
    // Prefetch details for the featured movies so their real backdrops are ready when they rotate in.
    LaunchedEffect(featured) {
        featured.forEach { s -> s.movie?.let { homeFetchInfo(it) } }
    }
    // Auto-advance the carousel every 8s while the hero is shown and the user isn't moving around.
    LaunchedEffect(heroMode, heroIndex, featured.size, interactions) {
        if (!heroMode || featured.size < 2) return@LaunchedEffect
        delay(8_000)
        heroIndex = (heroIndex + 1) % featured.size
    }
    // Keep the focused shelf pinned right under the spotlight area.
    LaunchedEffect(focusedRowKey) {
        val k = focusedRowKey ?: return@LaunchedEffect
        val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == k } ?: return@LaunchedEffect
        listState.animateScrollToItem(item.index)
    }
    LaunchedEffect(heroMode) {
        if (heroMode && (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0)) {
            listState.animateScrollToItem(0)
        }
    }
    // Initial focus: hero Play button (or the first card when there is no hero).
    LaunchedEffect(hasHero) {
        delay(60)
        if (hasHero) runCatching { playFr.requestFocus() } else runCatching { firstCardFr.requestFocus() }
    }

    fun goHero() {
        heroMode = true
        spotlight = null
        focusedRowKey = null
        scope.launch {
            delay(60)
            runCatching { playFr.requestFocus() }
        }
    }

    val onHeroFocus: (Boolean) -> Unit = { f ->
        if (f) {
            heroMode = true
            spotlight = null
            focusedRowKey = null
            interactions++
        }
    }
    val onCardFocus: (String, HomeSpot) -> Unit = { rowKey, spot ->
        heroMode = false
        spotlight = spot
        focusedRowKey = rowKey
    }

    BackHandler(enabled = hasHero && !heroMode) { goHero() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val screenH = maxHeight
        val expanded = heroMode && hasHero
        val panelH by animateDpAsState(
            if (expanded) screenH * 0.58f else screenH * 0.40f,
            tween(420),
            label = "homePanel",
        )
        val art = homeArtOf(display)

        HomeArtLayer(art, Modifier.fillMaxWidth().height(screenH * 0.86f))

        Column(Modifier.fillMaxSize()) {
            HomeHeroPanel(
                spot = display,
                expanded = expanded,
                art = art,
                heroItem = heroItem,
                heroIndex = heroPos,
                heroCount = featured.size,
                playFr = playFr,
                onHeroFocus = onHeroFocus,
                modifier = Modifier.fillMaxWidth().height(panelH),
            )
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .onPreviewKeyEvent { ev ->
                        if (hasHero && ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionUp &&
                            focusedRowKey != null && focusedRowKey == firstRowKey
                        ) {
                            goHero()
                            true
                        } else {
                            false
                        }
                    },
                contentPadding = PaddingValues(top = 6.dp, bottom = 72.dp),
            ) {
                model.rows.forEachIndexed { ri, row ->
                    homeRow(row, ri == 0, firstCardFr, onCardFocus)
                }
            }
        }
    }
}

// ============================================================================ hero

@Composable
private fun HomeArtLayer(art: HomeArt, modifier: Modifier) {
    Box(modifier) {
        Crossfade(targetState = art, animationSpec = tween(700), label = "homeArt") { a ->
            Box(Modifier.fillMaxSize()) {
                when (a.mode) {
                    HOME_ART_WIDE -> AsyncImg(a.url, Modifier.fillMaxSize())
                    HOME_ART_POSTER -> AsyncImg(a.url, Modifier.fillMaxSize().blur(40.dp), alpha = 0.5f)
                    HOME_ART_LOGO -> AsyncImg(a.url, Modifier.fillMaxSize().blur(72.dp), alpha = 0.22f)
                    else -> {}
                }
                if (a.mode != HOME_ART_WIDE) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .fillMaxWidth(0.7f)
                            .fillMaxHeight(0.9f)
                            .background(Brush.radialGradient(listOf(EzColors.Accent.copy(alpha = 0.20f), Color.Transparent))),
                    )
                }
            }
        }
        Box(Modifier.fillMaxSize().background(EzColors.LeftScrim))
        Box(Modifier.fillMaxSize().background(EzColors.BottomScrim))
    }
}

@Composable
private fun HomeHeroPanel(
    spot: HomeSpot?,
    expanded: Boolean,
    art: HomeArt,
    heroItem: HomeSpot?,
    heroIndex: Int,
    heroCount: Int,
    playFr: FocusRequester,
    onHeroFocus: (Boolean) -> Unit,
    modifier: Modifier,
) {
    Box(modifier) {
        HomeForegroundArt(art, Modifier.align(Alignment.CenterEnd).padding(end = 56.dp, top = 30.dp, bottom = 18.dp))
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = Layout.ContentStart, end = 48.dp, bottom = 14.dp),
        ) {
            Crossfade(targetState = spot, animationSpec = tween(320), label = "homeText") { s ->
                HomeSpotText(s, expanded)
            }
            AnimatedVisibility(
                visible = expanded && heroItem != null,
                enter = fadeIn(tween(260)) + expandVertically(tween(300)),
                exit = fadeOut(tween(140)) + shrinkVertically(tween(260)),
            ) {
                Column {
                    Spacer(Modifier.height(18.dp))
                    if (heroItem != null) HomeHeroButtons(heroItem, playFr, onHeroFocus)
                    if (heroCount > 1) {
                        Spacer(Modifier.height(16.dp))
                        HomeDots(heroIndex, heroCount)
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeForegroundArt(art: HomeArt, modifier: Modifier) {
    Crossfade(targetState = art, modifier = modifier, animationSpec = tween(600), label = "homeFg") { a ->
        when (a.mode) {
            HOME_ART_POSTER -> Box(
                Modifier
                    .fillMaxHeight()
                    .aspectRatio(2f / 3f, matchHeightConstraintsFirst = true)
                    .shadow(24.dp, RoundedCornerShape(14.dp))
                    .clip(RoundedCornerShape(14.dp))
                    .background(EzColors.Surface),
            ) {
                AsyncImg(a.url, Modifier.fillMaxSize())
            }
            HOME_ART_LOGO -> Box(
                Modifier
                    .size(width = 250.dp, height = 140.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color.White.copy(alpha = 0.07f))
                    .border(1.dp, EzColors.Stroke, RoundedCornerShape(22.dp)),
                contentAlignment = Alignment.Center,
            ) {
                AsyncImg(a.url, Modifier.fillMaxSize().padding(24.dp), contentScale = ContentScale.Fit)
            }
            else -> Spacer(Modifier.size(1.dp))
        }
    }
}

@Composable
private fun HomeSpotText(s: HomeSpot?, expanded: Boolean) {
    Column(Modifier.widthIn(max = 560.dp)) {
        if (s == null) {
            HomeLabel("Welcome", live = false)
            Spacer(Modifier.height(8.dp))
            Text("Ezano TV", style = if (expanded) EzType.Display else EzType.H1, maxLines = 1)
            Spacer(Modifier.height(10.dp))
            Text(
                "Your channels, movies and series — all in one place.",
                style = EzType.Body.copy(color = EzColors.TextPrimary.copy(alpha = 0.82f)),
                maxLines = 2,
            )
        } else {
            HomeLabel(s.label, live = s.type == ContentType.LIVE)
            Spacer(Modifier.height(8.dp))
            Text(
                s.title,
                style = if (expanded) EzType.Display else EzType.H1,
                maxLines = if (expanded) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = s.meta.filter { it.isNotBlank() }
            if (meta.isNotEmpty() || s.rating > 0) {
                Spacer(Modifier.height(10.dp))
                MetaRow(meta, rating = s.rating)
            }
            val ch = s.channel
            val plot = if (ch != null) rememberHomeChannelPlot(ch) else s.plot
            if (plot.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    plot,
                    style = EzType.Body.copy(color = EzColors.TextPrimary.copy(alpha = 0.82f)),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun HomeLabel(text: String, live: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (live) {
            LiveBadge()
            Spacer(Modifier.width(10.dp))
        }
        Text(
            text.uppercase(),
            style = EzType.Label.copy(color = EzColors.Accent2, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.18.em),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun rememberHomeChannelPlot(c: Channel): String {
    val epg by Repo.epg.collectAsState()
    val clock by Repo.clock.collectAsState()
    val settings by Repo.store.settings.collectAsState()
    val use24h = settings.use24h
    return remember(epg, clock, c.id, use24h) {
        val now = epg.now(c, clock)
        val next = epg.next(c, clock)
        buildString {
            if (now != null) append("Now: ${now.title}  ·  ${Fmt.minutesLeft(now.stop, clock)}")
            if (next != null) {
                if (isNotEmpty()) append("\n")
                append("Next at ${Fmt.time(next.start, use24h)}: ${next.title}")
            }
            if (isEmpty()) append("Watch live — no programme information available for this channel.")
        }
    }
}

@Composable
private fun HomeHeroButtons(item: HomeSpot, playFr: FocusRequester, onHeroFocus: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    val nav = LocalNavigator.current
    val favs by Repo.store.favorites.collectAsState()
    val history by Repo.store.history.collectAsState()
    val movie = item.movie
    val series = item.series
    val channel = item.channel
    val favKey = when {
        movie != null -> "movie:${movie.id}"
        series != null -> "series:${series.id}"
        channel != null -> "live:${channel.id}"
        else -> ""
    }
    val isFav = remember(favs, favKey) { favKey.isNotEmpty() && Repo.store.isFavorite(favKey) }
    val resumeMs = remember(history, movie?.id) { if (movie != null) Repo.store.resumePosition("movie:${movie.id}") else 0L }
    val primaryText = when {
        movie != null -> if (resumeMs > 0) "Resume" else "Play"
        channel != null -> "Watch live"
        else -> "Watch now"
    }

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        PillButton(
            text = primaryText,
            onClick = {
                when {
                    movie != null -> Playback.playMovie(ctx, movie)
                    channel != null -> Playback.playChannel(ctx, channel)
                    series != null -> nav.navigate(Screen.SeriesDetail(series.id))
                    else -> {}
                }
            },
            modifier = Modifier.focusRequester(playFr),
            icon = Icons.Rounded.PlayArrow,
            primary = true,
            onFocus = onHeroFocus,
        )
        PillButton(
            text = if (channel != null) "TV Guide" else "More info",
            onClick = {
                when {
                    movie != null -> nav.navigate(Screen.MovieDetail(movie.id))
                    series != null -> nav.navigate(Screen.SeriesDetail(series.id))
                    channel != null -> nav.navigate(Screen.Guide)
                    else -> {}
                }
            },
            icon = if (channel != null) Icons.Rounded.LiveTv else Icons.Rounded.Info,
            onFocus = onHeroFocus,
        )
        PillButton(
            text = "My List",
            onClick = { if (favKey.isNotEmpty()) homeToggleFav(ctx, favKey) },
            icon = if (isFav) Icons.Rounded.Check else Icons.Rounded.Add,
            onFocus = onHeroFocus,
        )
    }
}

@Composable
private fun HomeDots(current: Int, count: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { i ->
            val active = i == current
            val w by animateDpAsState(if (active) 22.dp else 6.dp, tween(300), label = "homeDot")
            Box(
                Modifier
                    .width(w)
                    .height(6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (active) Color.White else Color.White.copy(alpha = 0.32f)),
            )
        }
    }
}

// ============================================================================ shelves

private fun homeFirstMod(isFirst: Boolean, fr: FocusRequester): Modifier =
    if (isFirst) Modifier.focusRequester(fr) else Modifier

private fun LazyListScope.homeRow(
    row: HomeRow,
    first: Boolean,
    firstFr: FocusRequester,
    onFocus: (String, HomeSpot) -> Unit,
) {
    val rk = row.key
    when {
        row.entries.isNotEmpty() -> contentRow(row.title, row.entries, key = { it.key }, startPadding = Layout.ContentStart) { e ->
            HomeContinueCard(e, homeFirstMod(first && e === row.entries.first(), firstFr)) {
                onFocus(rk, homeSpotOfEntry(e))
            }
        }
        row.channels.isNotEmpty() -> contentRow(row.title, row.channels, key = { it.id }, startPadding = Layout.ContentStart) { c ->
            HomeChannelCard(
                c,
                if (row.zapRow) row.channels else null,
                homeFirstMod(first && c === row.channels.first(), firstFr),
            ) {
                onFocus(rk, homeSpotOfChannel(c))
            }
        }
        else -> contentRow(row.title, row.posters, key = { homePosterKey(it) }, startPadding = Layout.ContentStart) { p ->
            HomePosterCard(p, homeFirstMod(first && p === row.posters.first(), firstFr)) {
                onFocus(rk, homeSpotOfPoster(p))
            }
        }
    }
}

@Composable
private fun HomeContinueCard(e: HistoryEntry, modifier: Modifier, onFocused: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val left = (e.durationMs - e.positionMs).coerceAtLeast(0L)
    val sub = listOf(e.subtitle, if (e.durationMs > 0) "${Fmt.duration(left)} left" else "")
        .filter { it.isNotBlank() }
        .joinToString("  ·  ")
    WideCard(
        title = e.title,
        image = e.image,
        onClick = { homeResume(ctx, scope, e) },
        modifier = modifier,
        width = 260.dp,
        subtitle = sub.ifBlank { null },
        progress = e.progress,
        onFocus = { f -> if (f) onFocused() },
        onLongClick = {
            Repo.store.removeHistory(e.key)
            homeToast(ctx, "Removed from Continue watching")
        },
    )
}

@Composable
private fun HomeChannelCard(c: Channel, zap: List<Channel>?, modifier: Modifier, onFocused: () -> Unit) {
    val ctx = LocalContext.current
    val epg by Repo.epg.collectAsState()
    val clock by Repo.clock.collectAsState()
    val now = remember(epg, clock, c.id) { epg.now(c, clock) }
    WideCard(
        title = c.name,
        image = c.logo,
        onClick = {
            if (zap != null) Playback.playChannel(ctx, c, zap) else Playback.playChannel(ctx, c)
        },
        modifier = modifier,
        width = 220.dp,
        subtitle = now?.title?.ifBlank { null } ?: "Live now",
        progress = now?.progress(clock),
        imageFit = true,
        onFocus = { f -> if (f) onFocused() },
        onLongClick = { homeToggleFav(ctx, "live:${c.id}") },
    )
}

@Composable
private fun HomePosterCard(item: Any, modifier: Modifier, onFocused: () -> Unit) {
    val ctx = LocalContext.current
    val nav = LocalNavigator.current
    when (item) {
        is Movie -> PosterCard(
            title = item.name,
            image = item.poster,
            onClick = { nav.navigate(Screen.MovieDetail(item.id)) },
            modifier = modifier,
            width = 132.dp,
            subtitle = item.year.ifBlank { null },
            badge = homeRatingBadge(item.rating),
            onFocus = { f -> if (f) onFocused() },
            onLongClick = { homeToggleFav(ctx, "movie:${item.id}") },
        )
        is Series -> PosterCard(
            title = item.name,
            image = item.poster,
            onClick = { nav.navigate(Screen.SeriesDetail(item.id)) },
            modifier = modifier,
            width = 132.dp,
            subtitle = item.year.ifBlank { null },
            badge = homeRatingBadge(item.rating),
            onFocus = { f -> if (f) onFocused() },
            onLongClick = { homeToggleFav(ctx, "series:${item.id}") },
        )
        else -> {}
    }
}

// ============================================================================ data building (runs on Dispatchers.Default)

private fun buildHomeModel(idx: LibraryIndex): HomeModel {
    val lib = idx.lib
    val okMov = Repo.visibleCategories(ContentType.MOVIE).mapTo(HashSet()) { it.id }
    val okSer = Repo.visibleCategories(ContentType.SERIES).mapTo(HashSet()) { it.id }
    val movieOk = { m: Movie -> m.categoryId.isBlank() || m.categoryId in okMov }
    val seriesOk = { s: Series -> s.categoryId.isBlank() || s.categoryId in okSer }
    val vod = lib.movies.isNotEmpty() || lib.series.isNotEmpty()
    val hist = Repo.store.historyForActive()

    val rows = ArrayList<HomeRow>()
    val titles = HashSet<String>()
    fun add(
        title: String,
        entries: List<HistoryEntry> = emptyList(),
        channels: List<Channel> = emptyList(),
        posters: List<Any> = emptyList(),
        zapRow: Boolean = false,
    ) {
        if (entries.isEmpty() && channels.isEmpty() && posters.isEmpty()) return
        // Row keys are derived from titles, so they must be unique.
        var t = title
        var n = 2
        while (!titles.add(t)) {
            t = "$title ($n)"
            n++
        }
        rows += HomeRow(t, entries, channels, posters, zapRow)
    }

    val continueWatching = hist.asSequence()
        .filter { it.type != ContentType.LIVE && it.progress in 0.02f..0.95f }
        .distinctBy { it.key }
        .take(20)
        .toList()
    val recentChannels = hist.asSequence()
        .filter { it.type == ContentType.LIVE }
        .mapNotNull { e -> Repo.channel(e.key.removePrefix("live:")) ?: Repo.channel(e.refId) }
        .distinctBy { it.id }
        .take(15)
        .toList()
    val favChannels = Repo.favoriteChannels().asReversed().distinctBy { it.id }

    add("Continue watching", entries = continueWatching)
    add("Recently watched channels", channels = recentChannels)
    add("Favorite channels", channels = favChannels, zapRow = true)

    if (vod) {
        val myList: List<Any> = Repo.store.favoriteKeys().asReversed().mapNotNull { k ->
            when {
                k.startsWith("movie:") -> Repo.movie(k.removePrefix("movie:"))
                k.startsWith("series:") -> Repo.seriesById(k.removePrefix("series:"))
                else -> null
            }
        }
        add("My List", posters = myList)
        add("Recently added movies", posters = idx.recentMovies.asSequence().filter(movieOk).distinctBy { it.id }.take(30).toList())
        add("Recently added series", posters = idx.recentSeries.asSequence().filter(seriesOk).distinctBy { it.id }.take(30).toList())
        add("Top rated movies", posters = idx.topMovies.asSequence().filter(movieOk).distinctBy { it.id }.take(30).toList())
        add("Top rated series", posters = idx.topSeries.asSequence().filter(seriesOk).distinctBy { it.id }.take(30).toList())
        var catRows = 0
        for (c in Repo.visibleCategories(ContentType.MOVIE)) {
            if (catRows >= 6) break
            val list = idx.moviesByCat[c.id].orEmpty().asSequence().distinctBy { it.id }.take(20).toList()
            if (list.isEmpty()) continue
            add(c.name, posters = list)
            catRows++
        }
    } else {
        // Live-only playlist: channel-centric shelves.
        var catRows = 0
        for (c in Repo.visibleCategories(ContentType.LIVE)) {
            if (catRows >= 8) break
            val list = idx.channelsByCat[c.id].orEmpty().asSequence().distinctBy { it.id }.take(20).toList()
            if (list.isEmpty()) continue
            add(c.name, channels = list)
            catRows++
        }
    }

    // ---- featured (hero carousel)
    val featured = ArrayList<HomeSpot>()
    val seen = HashSet<String>()
    fun feature(s: HomeSpot) {
        if (featured.size < 6 && seen.add(s.id)) featured += s
    }
    if (vod) {
        val pools = listOf(
            idx.recentSeries.asSequence().filter { seriesOk(it) && it.backdrop.isNotBlank() }.take(4).map { homeSpotOfSeries(it) }.toList(),
            idx.recentMovies.asSequence().filter { movieOk(it) && it.poster.isNotBlank() }.take(4).map { homeSpotOfMovie(it) }.toList(),
            idx.topSeries.asSequence().filter { seriesOk(it) && it.backdrop.isNotBlank() }.take(4).map { homeSpotOfSeries(it) }.toList(),
            idx.topMovies.asSequence().filter { movieOk(it) && it.poster.isNotBlank() }.take(4).map { homeSpotOfMovie(it) }.toList(),
            idx.recentSeries.asSequence().filter { seriesOk(it) && it.poster.isNotBlank() }.take(4).map { homeSpotOfSeries(it) }.toList(),
        )
        for (round in 0 until 4) {
            for (p in pools) p.getOrNull(round)?.let { feature(it) }
        }
    }
    if (featured.isEmpty()) {
        val chans = (favChannels + recentChannels).asSequence() + Repo.channels().asSequence().take(300)
        chans.filter { it.logo.isNotBlank() }.distinctBy { it.id }.take(6).forEach { feature(homeSpotOfChannel(it)) }
    }
    return HomeModel(vod, featured, rows)
}

// ============================================================================ spot builders

private fun homeSpotOfMovie(m: Movie): HomeSpot {
    val cat = Repo.category(ContentType.MOVIE, m.categoryId)?.name.orEmpty()
    return HomeSpot(
        id = "movie:${m.id}",
        type = ContentType.MOVIE,
        label = "Movie",
        title = m.name,
        poster = m.poster,
        meta = listOf(m.year, cat),
        rating = m.rating,
        movie = m,
    )
}

private fun homeSpotOfSeries(s: Series): HomeSpot {
    val cat = Repo.category(ContentType.SERIES, s.categoryId)?.name.orEmpty()
    return HomeSpot(
        id = "series:${s.id}",
        type = ContentType.SERIES,
        label = "Series",
        title = s.name,
        backdrop = if (s.backdrop != s.poster) s.backdrop else "",
        poster = s.poster,
        meta = listOf(s.year, homeGenre(s.genre).ifBlank { cat }),
        rating = s.rating,
        plot = s.plot,
        series = s,
    )
}

private fun homeSpotOfChannel(c: Channel): HomeSpot {
    val cat = Repo.category(ContentType.LIVE, c.categoryId)?.name.orEmpty()
    return HomeSpot(
        id = "live:${c.id}",
        type = ContentType.LIVE,
        label = cat.ifBlank { "Live TV" },
        title = c.name,
        poster = c.logo,
        meta = listOf(
            if (c.number > 0) "Channel ${c.number}" else "",
            if (c.catchupDays > 0) "${c.catchupDays}-day catch-up" else "",
        ),
        channel = c,
    )
}

private fun homeSpotOfPoster(p: Any): HomeSpot = when (p) {
    is Movie -> homeSpotOfMovie(p)
    is Series -> homeSpotOfSeries(p)
    else -> HomeSpot(id = "unknown", type = ContentType.MOVIE, label = "", title = "")
}

private fun homeSpotOfEntry(e: HistoryEntry): HomeSpot {
    val left = (e.durationMs - e.positionMs).coerceAtLeast(0L)
    val leftText = if (e.durationMs > 0) "${Fmt.duration(left)} left" else ""
    return if (e.key.startsWith("ep:")) {
        val sid = e.key.removePrefix("ep:").substringBefore(':')
        val s = Repo.seriesById(sid) ?: Repo.seriesById(e.refId)
        if (s != null) {
            HomeSpot(
                id = "cw:${e.key}",
                type = ContentType.SERIES,
                label = "Continue watching",
                title = s.name,
                backdrop = if (s.backdrop.isNotBlank() && s.backdrop != s.poster) s.backdrop else e.image,
                poster = s.poster,
                meta = listOf(e.subtitle, leftText),
                rating = s.rating,
                plot = s.plot,
                series = s,
            )
        } else {
            HomeSpot(
                id = "cw:${e.key}",
                type = ContentType.SERIES,
                label = "Continue watching",
                title = e.title,
                backdrop = e.image,
                meta = listOf(e.subtitle, leftText),
            )
        }
    } else {
        val m = Repo.movie(e.key.removePrefix("movie:")) ?: Repo.movie(e.refId)
        HomeSpot(
            id = "cw:${e.key}",
            type = ContentType.MOVIE,
            label = "Continue watching",
            title = m?.name ?: e.title,
            poster = m?.poster ?: e.image,
            meta = listOf(m?.year.orEmpty(), leftText),
            rating = m?.rating ?: 0.0,
            movie = m,
        )
    }
}

/** Merge fetched movie details (real backdrop, plot, duration, genre) into a spot. */
private fun HomeSpot.enrich(i: MovieInfo): HomeSpot {
    val mv = movie
    val wide = if (i.backdrop.isNotBlank() && i.backdrop != mv?.poster) i.backdrop else ""
    val newMeta = if (id.startsWith("movie:")) {
        listOf(mv?.year.orEmpty().ifBlank { i.releaseDate.take(4) }, homeDuration(i.duration), homeGenre(i.genre))
    } else {
        meta
    }
    return copy(
        backdrop = backdrop.ifBlank { wide },
        plot = plot.ifBlank { i.plot },
        meta = newMeta,
        rating = if (rating > 0) rating else i.rating,
    )
}

private fun homeArtOf(s: HomeSpot?): HomeArt = when {
    s == null -> HomeArt("", HOME_ART_NONE)
    s.type == ContentType.LIVE -> if (s.poster.isNotBlank()) HomeArt(s.poster, HOME_ART_LOGO) else HomeArt("", HOME_ART_NONE)
    s.backdrop.isNotBlank() -> HomeArt(s.backdrop, HOME_ART_WIDE)
    s.poster.isNotBlank() -> HomeArt(s.poster, HOME_ART_POSTER)
    else -> HomeArt("", HOME_ART_NONE)
}

// ============================================================================ actions & helpers

private fun homeResume(ctx: Context, scope: CoroutineScope, e: HistoryEntry) {
    when {
        e.key.startsWith("movie:") -> {
            val m = Repo.movie(e.key.removePrefix("movie:")) ?: Repo.movie(e.refId)
            if (m != null) Playback.playMovie(ctx, m) else homeToast(ctx, "This movie is no longer available")
        }
        e.key.startsWith("ep:") -> {
            val rest = e.key.removePrefix("ep:")
            val seriesId = rest.substringBefore(':')
            val episodeId = rest.substringAfter(':', "")
            val s = Repo.seriesById(seriesId) ?: Repo.seriesById(e.refId)
            if (s == null) {
                homeToast(ctx, "This series is no longer available")
                return
            }
            scope.launch {
                val info: SeriesInfo? = try {
                    Repo.seriesInfo(s)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (ex: Exception) {
                    null
                }
                val all = if (info != null) homeFlatten(info) else emptyList()
                val ep = all.firstOrNull { it.id == episodeId }
                if (ep != null) {
                    Playback.playEpisode(ctx, s, ep, all)
                } else if (e.url.isNotBlank()) {
                    // Couldn't fetch the episode list (offline?) – play what we remember.
                    val fallback = Episode(
                        id = episodeId,
                        title = e.subtitle.ifBlank { e.title },
                        season = 0,
                        episode = 0,
                        image = e.image,
                        url = e.url,
                    )
                    Playback.playEpisode(ctx, s, fallback, listOf(fallback))
                } else {
                    homeToast(ctx, "Couldn't load this episode")
                }
            }
        }
        else -> homeToast(ctx, "Can't resume this item")
    }
}

private suspend fun homeFetchInfo(m: Movie) {
    if (HomeInfoCache.infos.containsKey(m.url)) return
    val info: MovieInfo? = try {
        Repo.movieInfo(m)
    } catch (ce: CancellationException) {
        throw ce
    } catch (ex: Exception) {
        null
    }
    if (info != null) HomeInfoCache.infos[m.url] = info
}

private fun homeFlatten(info: SeriesInfo): List<Episode> =
    info.episodes.keys.sorted().flatMap { info.episodes[it].orEmpty() }

private fun homeToast(ctx: Context, msg: String) {
    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
}

private fun homeToggleFav(ctx: Context, key: String) {
    val added = Repo.store.toggleFavorite(key)
    homeToast(ctx, if (added) "Added to My List" else "Removed from My List")
}

private fun homeRatingBadge(r: Double): String? =
    if (r > 0) "★ " + String.format(Locale.US, "%.1f", r) else null

private fun homePosterKey(p: Any): Any = when (p) {
    is Movie -> "m:${p.id}"
    is Series -> "s:${p.id}"
    else -> p.hashCode()
}

private fun homeGenre(raw: String): String =
    raw.split(',', '/', '|').map { it.trim() }.filter { it.isNotEmpty() }.take(2).joinToString(" / ")

/** "01:52:03" / "112" / "112 min" → "1h 52m". */
private fun homeDuration(raw: String): String {
    val t = raw.trim()
    if (t.isEmpty()) return ""
    val parts = t.split(':')
    if (parts.size in 2..3 && parts.all { it.trim().toIntOrNull() != null }) {
        val n = parts.map { it.trim().toInt() }
        val minutes = n[0] * 60 + n[1]
        return if (minutes > 0) Fmt.duration(minutes * 60_000L) else ""
    }
    val v = t.toIntOrNull()
    if (v != null) return if (v > 0) Fmt.duration((if (v > 600) v / 60 else v) * 60_000L) else ""
    return t
}
