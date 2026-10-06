package com.ezano.tv.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Movie as MovieIcon
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.ezano.tv.data.ContentType
import com.ezano.tv.data.Episode
import com.ezano.tv.data.HistoryEntry
import com.ezano.tv.data.Movie
import com.ezano.tv.data.MovieInfo
import com.ezano.tv.data.Repo
import com.ezano.tv.data.Series
import com.ezano.tv.data.SeriesInfo
import com.ezano.tv.player.Playback
import com.ezano.tv.ui.LocalNavigator
import com.ezano.tv.ui.Screen
import com.ezano.tv.ui.components.AsyncImg
import com.ezano.tv.ui.components.Backdrop
import com.ezano.tv.ui.components.Chip
import com.ezano.tv.ui.components.EmptyView
import com.ezano.tv.ui.components.Fmt
import com.ezano.tv.ui.components.IconPill
import com.ezano.tv.ui.components.LoadingView
import com.ezano.tv.ui.components.MetaRow
import com.ezano.tv.ui.components.PillButton
import com.ezano.tv.ui.components.PosterCard
import com.ezano.tv.ui.components.ProgressLine
import com.ezano.tv.ui.components.SectionTitle
import com.ezano.tv.ui.components.WideCard
import com.ezano.tv.ui.components.contentRow
import com.ezano.tv.ui.theme.EzColors
import com.ezano.tv.ui.theme.EzType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.util.Locale

/** Detail pages have no nav rail, so content sits closer to the edge. */
private val DetailStart = 56.dp

private val DetailEpCodeRegex = Regex("^.*?\\b[Ss]\\d{1,3}\\s*[Ee]\\d{1,4}\\b\\s*[-–:|.]*\\s*")

private class DetailEpTarget(val ep: Episode, val label: String, val fromStart: Boolean)

// ============================================================================ movie

@Composable
fun MovieDetailScreen(movieId: String) {
    val idx by Repo.index.collectAsState()
    val movie = remember(idx, movieId) { Repo.movie(movieId) }
    when {
        movie != null -> MovieDetailContent(movie)
        idx == null -> DetailLoading()
        else -> DetailMissing("Movie not available", "This title is no longer in your playlist.")
    }
}

@Composable
private fun MovieDetailContent(movie: Movie) {
    val ctx = LocalContext.current
    val nav = LocalNavigator.current
    val history by Repo.store.history.collectAsState()
    val favs by Repo.store.favorites.collectAsState()
    val key = "movie:${movie.id}"
    val isFav = remember(favs, key) { Repo.store.isFavorite(key) }

    var info by remember(movie.id) { mutableStateOf<MovieInfo?>(null) }
    LaunchedEffect(movie.id) {
        info = detailTry { Repo.movieInfo(movie) } ?: MovieInfo(movie)
    }

    val entry = remember(history, key) { Repo.store.historyForActive().firstOrNull { it.key == key } }
    val resumeMs = remember(history, key) { Repo.store.resumePosition(key) }
    val similar = remember(movie.id) {
        Repo.movies(movie.categoryId).asSequence().filter { it.id != movie.id }.distinctBy { it.id }.take(20).toList()
    }
    val catName = remember(movie.id) { Repo.category(ContentType.MOVIE, movie.categoryId)?.name.orEmpty() }

    val i = info
    val wide = if (i != null && i.backdrop.isNotBlank() && i.backdrop != movie.poster) i.backdrop else ""
    val year = movie.year.ifBlank { i?.releaseDate?.take(4).orEmpty() }
    val rating = if (movie.rating > 0) movie.rating else (i?.rating ?: 0.0)
    val trailer = i?.trailerYoutubeId.orEmpty().trim()

    val primaryFr = remember { FocusRequester() }
    val listState = rememberLazyListState()
    LaunchedEffect(movie.id) {
        listState.scrollToItem(0)
        delay(80)
        runCatching { primaryFr.requestFocus() }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(EzColors.Bg)) {
        val screenH = maxHeight
        DetailArt(wide, movie.poster)
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 36.dp)) {
            item(key = "header") {
                DetailHeader(
                    minHeight = if (similar.isEmpty()) screenH else screenH - 96.dp,
                    label = listOf("Movie", catName).filter { it.isNotBlank() }.joinToString("  ·  "),
                    title = movie.name,
                    meta = listOf(year, detailDuration(i?.duration.orEmpty()), detailGenre(i?.genre.orEmpty())),
                    rating = rating,
                    plot = i?.plot.orEmpty(),
                    credits = listOf("Director" to i?.director.orEmpty(), "Cast" to i?.cast.orEmpty()),
                    sidePoster = if (wide.isBlank()) movie.poster else "",
                    onBack = { nav.back() },
                    extra = {
                        if (entry != null && resumeMs > 0) {
                            Spacer(Modifier.height(14.dp))
                            DetailResumeLine(entry)
                        }
                    },
                ) {
                    PillButton(
                        text = if (resumeMs > 0) "Resume from ${Fmt.clock(resumeMs)}" else "Play",
                        onClick = { Playback.playMovie(ctx, movie, fromStart = resumeMs <= 0) },
                        modifier = Modifier.focusRequester(primaryFr),
                        icon = Icons.Rounded.PlayArrow,
                        primary = true,
                    )
                    if (resumeMs > 0) {
                        PillButton(
                            text = "Start over",
                            onClick = { Playback.playMovie(ctx, movie, fromStart = true) },
                            icon = Icons.Rounded.Replay,
                        )
                    }
                    if (trailer.isNotBlank()) {
                        PillButton(
                            text = "Trailer",
                            onClick = { detailOpenTrailer(ctx, trailer) },
                            icon = Icons.Rounded.MovieIcon,
                        )
                    }
                    PillButton(
                        text = "My List",
                        onClick = { detailToggleFav(ctx, key) },
                        icon = if (isFav) Icons.Rounded.Check else Icons.Rounded.Add,
                    )
                }
            }
            contentRow("More like this", similar, key = { it.id }, startPadding = DetailStart) { m ->
                PosterCard(
                    title = m.name,
                    image = m.poster,
                    onClick = { nav.navigate(Screen.MovieDetail(m.id)) },
                    width = 132.dp,
                    subtitle = m.year.ifBlank { null },
                    badge = detailRatingBadge(m.rating),
                    onLongClick = { detailToggleFav(ctx, "movie:${m.id}") },
                )
            }
        }
    }
}

// ============================================================================ series

@Composable
fun SeriesDetailScreen(seriesId: String) {
    val idx by Repo.index.collectAsState()
    val series = remember(idx, seriesId) { Repo.seriesById(seriesId) }
    when {
        series != null -> SeriesDetailContent(series)
        idx == null -> DetailLoading()
        else -> DetailMissing("Series not available", "This series is no longer in your playlist.")
    }
}

@Composable
private fun SeriesDetailContent(series: Series) {
    val ctx = LocalContext.current
    val nav = LocalNavigator.current
    val history by Repo.store.history.collectAsState()
    val favs by Repo.store.favorites.collectAsState()
    val favKey = "series:${series.id}"
    val isFav = remember(favs, favKey) { Repo.store.isFavorite(favKey) }

    var info by remember(series.id) { mutableStateOf<SeriesInfo?>(null) }
    var error by remember(series.id) { mutableStateOf<String?>(null) }
    var attempt by remember(series.id) { mutableIntStateOf(0) }
    LaunchedEffect(series.id, attempt) {
        error = null
        try {
            info = Repo.seriesInfo(series)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            error = Repo.friendly(e)
        }
    }
    fun retry() {
        attempt += 1
    }

    val loaded = info
    val allEpisodes = remember(loaded) { if (loaded != null) detailFlatten(loaded) else emptyList() }
    val seasons = remember(loaded) {
        loaded?.episodes?.filterValues { it.isNotEmpty() }?.keys?.sorted().orEmpty()
    }
    val prefix = "ep:${series.id}:"
    val epHistory = remember(history, prefix) { Repo.store.historyForActive().filter { it.key.startsWith(prefix) } }
    val epById = remember(epHistory) { epHistory.associateBy { it.key.removePrefix(prefix) } }
    val target = remember(allEpisodes, epHistory) { detailTarget(allEpisodes, epHistory, prefix) }

    var season by remember(series.id) { mutableStateOf<Int?>(null) }
    val currentSeason: Int? = season?.takeIf { it in seasons }
        ?: target?.ep?.season?.takeIf { it in seasons }
        ?: seasons.firstOrNull()
    val seasonEps = remember(loaded, currentSeason) {
        if (loaded != null && currentSeason != null) loaded.episodes[currentSeason].orEmpty() else emptyList()
    }
    var focusedEp by remember(series.id) { mutableStateOf<Episode?>(null) }

    val wide = run {
        val b = loaded?.backdrop ?: series.backdrop
        if (b.isNotBlank() && b != series.poster) b else ""
    }
    val epFallbackArt = wide.ifBlank { series.poster }
    val catName = remember(series.id) { Repo.category(ContentType.SERIES, series.categoryId)?.name.orEmpty() }
    val targetEntry = target?.let { t -> epById[t.ep.id] }

    val primaryFr = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val epRowState = rememberLazyListState()
    LaunchedEffect(series.id) {
        listState.scrollToItem(0)
        delay(80)
        runCatching { primaryFr.requestFocus() }
    }
    // Show the episode to continue with when its season is on screen.
    LaunchedEffect(currentSeason, loaded != null) {
        val i = seasonEps.indexOfFirst { it.id == target?.ep?.id }
        epRowState.scrollToItem(if (i > 0) i else 0)
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(EzColors.Bg)) {
        val screenH = maxHeight
        DetailArt(wide, series.poster)
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
            item(key = "header") {
                DetailHeader(
                    minHeight = screenH - 104.dp,
                    label = listOf("Series", catName).filter { it.isNotBlank() }.joinToString("  ·  "),
                    title = series.name,
                    meta = listOf(
                        series.year,
                        when (seasons.size) {
                            0 -> ""
                            1 -> "1 Season"
                            else -> "${seasons.size} Seasons"
                        },
                        detailGenre(loaded?.genre ?: series.genre),
                    ),
                    rating = series.rating,
                    plot = (loaded?.plot ?: series.plot).ifBlank { series.plot },
                    credits = listOf("Cast" to loaded?.cast.orEmpty()),
                    sidePoster = if (wide.isBlank()) series.poster else "",
                    onBack = { nav.back() },
                    plotLines = 4,
                    extra = {
                        if (target != null && targetEntry != null && !target.fromStart && targetEntry.progress in 0.02f..0.95f) {
                            Spacer(Modifier.height(14.dp))
                            DetailResumeLine(targetEntry)
                        }
                    },
                ) {
                    PillButton(
                        text = when {
                            target != null -> target.label
                            error != null -> "Retry"
                            else -> "Loading episodes…"
                        },
                        onClick = {
                            when {
                                target != null -> Playback.playEpisode(ctx, series, target.ep, allEpisodes, fromStart = target.fromStart)
                                error != null -> retry()
                                else -> detailToast(ctx, "Loading episodes…")
                            }
                        },
                        modifier = Modifier.focusRequester(primaryFr),
                        icon = if (target == null && error != null) Icons.Rounded.Refresh else Icons.Rounded.PlayArrow,
                        primary = true,
                    )
                    PillButton(
                        text = "My List",
                        onClick = { detailToggleFav(ctx, favKey) },
                        icon = if (isFav) Icons.Rounded.Check else Icons.Rounded.Add,
                    )
                }
            }

            when {
                loaded == null && error == null -> item(key = "loading") {
                    Box(Modifier.fillMaxWidth().height(170.dp)) {
                        LoadingView("Loading episodes…")
                    }
                }
                loaded == null -> item(key = "error") {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        EmptyView(
                            "Couldn't load episodes",
                            error.orEmpty(),
                            icon = Icons.Rounded.MovieIcon,
                            modifier = Modifier.height(210.dp),
                        )
                        PillButton(text = "Try again", onClick = { retry() }, icon = Icons.Rounded.Refresh)
                    }
                }
                allEpisodes.isEmpty() -> item(key = "noeps") {
                    EmptyView(
                        "No episodes yet",
                        "This series doesn't have any episodes available right now.",
                        icon = Icons.Rounded.MovieIcon,
                        modifier = Modifier.height(210.dp),
                    )
                }
                else -> {
                    item(key = "seasons") {
                        Column(Modifier.padding(top = 8.dp)) {
                            SectionTitle(
                                "Episodes",
                                Modifier.padding(start = DetailStart),
                                trailing = if (currentSeason != null) "${seasonEps.size} in ${detailSeasonName(currentSeason)}" else null,
                            )
                            LazyRow(
                                contentPadding = PaddingValues(start = DetailStart, end = 48.dp, top = 2.dp, bottom = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                items(seasons, key = { it }) { s ->
                                    Chip(
                                        text = detailSeasonName(s),
                                        selected = s == currentSeason,
                                        onClick = { season = s },
                                        onFocus = { f -> if (f) season = s },
                                    )
                                }
                            }
                        }
                    }
                    item(key = "episodes") {
                        Column(Modifier.padding(top = 10.dp)) {
                            LazyRow(
                                state = epRowState,
                                contentPadding = PaddingValues(start = DetailStart, end = 48.dp, top = 6.dp, bottom = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                itemsIndexed(seasonEps, key = { idx, ep -> "${ep.id}#$idx" }) { _, ep ->
                                    val h = epById[ep.id]
                                    WideCard(
                                        title = "E${ep.episode} · ${detailEpTitle(ep)}",
                                        image = ep.image.ifBlank { epFallbackArt },
                                        onClick = { Playback.playEpisode(ctx, series, ep, allEpisodes) },
                                        width = 260.dp,
                                        subtitle = detailEpSubtitle(ep, h).ifBlank { null },
                                        progress = h?.progress?.takeIf { it > 0.01f },
                                        onFocus = { f -> if (f) focusedEp = ep },
                                    )
                                }
                            }
                            val synopsis = focusedEp?.takeIf { fe -> seasonEps.any { it.id == fe.id } }?.plot.orEmpty()
                            Box(
                                Modifier
                                    .padding(start = DetailStart, end = 48.dp, top = 8.dp)
                                    .height(44.dp)
                                    .widthIn(max = 640.dp),
                            ) {
                                if (synopsis.isNotBlank()) {
                                    Text(synopsis, style = EzType.BodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ============================================================================ shared pieces

@Composable
private fun DetailArt(wideUrl: String, posterUrl: String) {
    val target = if (wideUrl.isNotBlank()) wideUrl to true else posterUrl to false
    Crossfade(targetState = target, modifier = Modifier.fillMaxSize(), animationSpec = tween(650), label = "detailArt") { (url, isWide) ->
        Box(Modifier.fillMaxSize()) {
            if (isWide) {
                Backdrop(url, Modifier.fillMaxSize())
            } else {
                if (url.isNotBlank()) AsyncImg(url, Modifier.fillMaxSize().blur(44.dp), alpha = 0.42f)
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .fillMaxWidth(0.7f)
                        .fillMaxHeight(0.9f)
                        .background(Brush.radialGradient(listOf(EzColors.Accent.copy(alpha = 0.18f), Color.Transparent))),
                )
                Box(Modifier.fillMaxSize().background(EzColors.LeftScrim))
                Box(Modifier.fillMaxSize().background(EzColors.BottomScrim))
            }
        }
    }
}

@Composable
private fun DetailHeader(
    minHeight: Dp,
    label: String,
    title: String,
    meta: List<String>,
    rating: Double,
    plot: String,
    credits: List<Pair<String, String>>,
    sidePoster: String,
    onBack: () -> Unit,
    plotLines: Int = 5,
    extra: @Composable ColumnScope.() -> Unit = {},
    buttons: @Composable RowScope.() -> Unit,
) {
    Box(Modifier.fillMaxWidth().heightIn(min = minHeight)) {
        IconPill(
            icon = Icons.AutoMirrored.Rounded.ArrowBack,
            onClick = onBack,
            modifier = Modifier.align(Alignment.TopStart).padding(start = DetailStart, top = 28.dp),
            contentDescription = "Back",
        )
        if (sidePoster.isNotBlank()) {
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 72.dp, top = 24.dp)
                    .width(200.dp)
                    .aspectRatio(2f / 3f)
                    .shadow(28.dp, RoundedCornerShape(16.dp))
                    .clip(RoundedCornerShape(16.dp))
                    .background(EzColors.Surface),
            ) {
                AsyncImg(sidePoster, Modifier.fillMaxSize())
            }
        }
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = DetailStart, end = 48.dp, top = 92.dp, bottom = 26.dp)
                .widthIn(max = 560.dp),
        ) {
            if (label.isNotBlank()) {
                Text(
                    label.uppercase(),
                    style = EzType.Label.copy(color = EzColors.Accent2, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.18.em),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
            }
            Text(title, style = EzType.Display, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val shownMeta = meta.filter { it.isNotBlank() }
            if (shownMeta.isNotEmpty() || rating > 0) {
                Spacer(Modifier.height(12.dp))
                MetaRow(shownMeta, rating = rating)
            }
            if (plot.isNotBlank()) {
                Spacer(Modifier.height(14.dp))
                Text(
                    plot,
                    style = EzType.Body.copy(color = EzColors.TextPrimary.copy(alpha = 0.85f)),
                    maxLines = plotLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val shownCredits = credits.filter { it.second.isNotBlank() }
            if (shownCredits.isNotEmpty()) Spacer(Modifier.height(8.dp))
            shownCredits.forEach { (k, v) ->
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = EzColors.TextSecondary, fontWeight = FontWeight.SemiBold)) { append("$k   ") }
                        append(v)
                    },
                    style = EzType.Caption,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            extra()
            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                buttons()
            }
        }
    }
}

@Composable
private fun DetailResumeLine(entry: HistoryEntry) {
    val left = (entry.durationMs - entry.positionMs).coerceAtLeast(0L)
    Row(verticalAlignment = Alignment.CenterVertically) {
        ProgressLine(entry.progress, Modifier.width(180.dp))
        Spacer(Modifier.width(12.dp))
        val label = listOf(entry.subtitle, if (entry.durationMs > 0) "${Fmt.duration(left)} left" else "")
            .filter { it.isNotBlank() }
            .joinToString("  ·  ")
        Text(label, style = EzType.Caption.copy(color = EzColors.TextSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun DetailLoading() {
    Box(Modifier.fillMaxSize().background(EzColors.Bg)) {
        LoadingView("Loading…")
    }
}

@Composable
private fun DetailMissing(title: String, message: String) {
    val nav = LocalNavigator.current
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { fr.requestFocus() }
    }
    Box(Modifier.fillMaxSize().background(EzColors.Bg)) {
        EmptyView(title, message, icon = Icons.Rounded.MovieIcon)
        IconPill(
            icon = Icons.AutoMirrored.Rounded.ArrowBack,
            onClick = { nav.back() },
            modifier = Modifier.align(Alignment.TopStart).padding(start = DetailStart, top = 28.dp).focusRequester(fr),
            contentDescription = "Back",
        )
    }
}

// ============================================================================ helpers

private suspend fun <T> detailTry(block: suspend () -> T): T? = try {
    block()
} catch (ce: CancellationException) {
    throw ce
} catch (e: Exception) {
    null
}

private fun detailFlatten(info: SeriesInfo): List<Episode> =
    info.episodes.keys.sorted().flatMap { info.episodes[it].orEmpty() }

private fun detailCode(ep: Episode): String = if (ep.episode > 0) "S${ep.season} E${ep.episode}" else "S${ep.season}"

/** Which episode the primary button plays: continue the last one, the next one, or S1 E1. */
private fun detailTarget(all: List<Episode>, hist: List<HistoryEntry>, prefix: String): DetailEpTarget? {
    if (all.isEmpty()) return null
    val first = all.first()
    val last = hist.firstOrNull { h -> val id = h.key.removePrefix(prefix); all.any { it.id == id } }
        ?: return DetailEpTarget(first, "Play ${detailCode(first)}", false)
    val lastId = last.key.removePrefix(prefix)
    val index = all.indexOfFirst { it.id == lastId }
    val ep = all[index]
    return if (last.progress >= 0.95f) {
        val next = all.getOrNull(index + 1)
        if (next != null) DetailEpTarget(next, "Play ${detailCode(next)}", false)
        else DetailEpTarget(ep, "Watch again ${detailCode(ep)}", true)
    } else {
        DetailEpTarget(ep, "Continue ${detailCode(ep)}", false)
    }
}

private fun detailSeasonName(s: Int): String = if (s == 0) "Specials" else "Season $s"

/** "Show Name - S01E03 - The Pilot" → "The Pilot". */
private fun detailEpTitle(ep: Episode): String {
    val cleaned = ep.title.trim().replace(DetailEpCodeRegex, "").trim()
    return cleaned.ifBlank { if (ep.episode > 0) "Episode ${ep.episode}" else ep.title.ifBlank { "Episode" } }
}

private fun detailEpSubtitle(ep: Episode, h: HistoryEntry?): String {
    val parts = ArrayList<String>()
    if (ep.durationSecs > 0) parts += Fmt.duration(ep.durationSecs * 1000L)
    if (h != null) {
        when {
            h.progress >= 0.95f -> parts += "Watched"
            h.progress > 0.01f && h.durationMs > 0 -> parts += "${Fmt.duration((h.durationMs - h.positionMs).coerceAtLeast(0L))} left"
            else -> {}
        }
    }
    return parts.joinToString("  ·  ")
}

private fun detailGenre(raw: String): String =
    raw.split(',', '/', '|').map { it.trim() }.filter { it.isNotEmpty() }.take(2).joinToString(" / ")

/** "01:52:03" / "112" / "112 min" → "1h 52m". */
private fun detailDuration(raw: String): String {
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

private fun detailRatingBadge(r: Double): String? =
    if (r > 0) "★ " + String.format(Locale.US, "%.1f", r) else null

private fun detailToast(ctx: Context, msg: String) {
    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
}

private fun detailToggleFav(ctx: Context, key: String) {
    val added = Repo.store.toggleFavorite(key)
    detailToast(ctx, if (added) "Added to My List" else "Removed from My List")
}

private fun detailOpenTrailer(ctx: Context, id: String) {
    val url = if (id.startsWith("http", ignoreCase = true)) id else "https://www.youtube.com/watch?v=$id"
    val ok = runCatching {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
    if (!ok) detailToast(ctx, "No app available to play the trailer")
}
