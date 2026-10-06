package com.ezano.tv.ui.screens

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Movie as MovieIcon
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ezano.tv.data.Category
import com.ezano.tv.data.ContentType
import com.ezano.tv.data.Movie
import com.ezano.tv.data.Repo
import com.ezano.tv.data.Series
import com.ezano.tv.ui.Layout
import com.ezano.tv.ui.LocalNavigator
import com.ezano.tv.ui.Screen
import com.ezano.tv.ui.components.AsyncImg
import com.ezano.tv.ui.components.Chip
import com.ezano.tv.ui.components.EmptyView
import com.ezano.tv.ui.components.LoadingView
import com.ezano.tv.ui.components.MetaRow
import com.ezano.tv.ui.components.PosterCard
import com.ezano.tv.ui.theme.EzColors
import com.ezano.tv.ui.theme.EzType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

// ============================================================================ model

/** Movie or series flattened into one shape so both browsers share one implementation. */
private class EzVodItem(
    val id: String,
    val title: String,
    val poster: String,
    val backdrop: String,
    val year: String,
    val rating: Double,
    val added: Long,
    val categoryId: String,
    val plot: String,
    val genre: String,
)

private class EzVodResult(val sig: String, val items: List<EzVodItem>)

/** Remembers filter / sort / scroll / focused item per browser while the app is running. */
private object EzVodMemory {
    val filter = HashMap<ContentType, String>()
    val sort = HashMap<ContentType, Int>()
    val gridSig = HashMap<ContentType, String>()
    val gridIndex = HashMap<ContentType, Int>()
    val focusedId = HashMap<ContentType, String>()
}

private val EzVodSorts = listOf("Recently added", "A–Z", "Top rated")
private val EzVodFixedFilters = listOf("all" to "All", "fav" to "★  My List", "recent" to "Recently added")

// ============================================================================ screens

@Composable
fun MoviesScreen() {
    EzVodBrowser(ContentType.MOVIE)
}

@Composable
fun ShowsScreen() {
    EzVodBrowser(ContentType.SERIES)
}

@Composable
private fun EzVodBrowser(kind: ContentType) {
    val isMovies = kind == ContentType.MOVIE
    val nav = LocalNavigator.current
    val ctx = LocalContext.current
    val idx by Repo.index.collectAsState()
    val favs by Repo.store.favorites.collectAsState()
    val settings by Repo.store.settings.collectAsState()

    val categories = remember(idx, settings) { Repo.visibleCategories(kind).distinctBy { it.id } }
    val catNames = remember(categories) { categories.associate { it.id to it.name } }

    var filter by remember { mutableStateOf(EzVodMemory.filter[kind] ?: "all") }
    var sort by remember { mutableIntStateOf(EzVodMemory.sort[kind] ?: 0) }
    var pendingFilter by remember { mutableStateOf<String?>(null) }
    /** Where focus currently is: "init", "chips" or "grid". Focus arriving on a chip from the grid doesn't switch. */
    var zone by remember { mutableStateOf("init") }
    var focusedItem by remember { mutableStateOf<EzVodItem?>(null) }
    var spotItem by remember { mutableStateOf<EzVodItem?>(null) }

    // Debounced "focus a chip → switch the grid".
    LaunchedEffect(pendingFilter) {
        val p = pendingFilter ?: return@LaunchedEffect
        delay(380)
        if (p != filter) filter = p
        pendingFilter = null
    }
    LaunchedEffect(filter, sort) {
        EzVodMemory.filter[kind] = filter
        EzVodMemory.sort[kind] = sort
    }
    // A remembered category may have been hidden or the playlist switched.
    LaunchedEffect(categories) {
        if (filter.startsWith("cat:") && categories.isNotEmpty() && categories.none { "cat:${it.id}" == filter }) filter = "all"
    }
    LaunchedEffect(focusedItem) {
        delay(220)
        spotItem = focusedItem
    }

    val favDep: Any = if (filter == "fav") favs else 0
    val result by produceState<EzVodResult?>(null, idx, filter, sort, settings, favDep) {
        if (idx == null) {
            value = null
            return@produceState
        }
        val f = filter
        val s = sort
        val items = withContext(Dispatchers.Default) { ezVodBuild(kind, f, s) }
        value = EzVodResult("$f|$s", items)
    }
    val res = result
    val sig = res?.sig ?: ""

    // New grid state per filter/sort (starts at the top), restoring the old position when coming back.
    val gridState = remember(sig) {
        val start = if (EzVodMemory.gridSig[kind] == sig) EzVodMemory.gridIndex[kind] ?: 0 else 0
        LazyGridState(firstVisibleItemIndex = start)
    }
    val initialChipIndex = remember { (ezVodChipIndex(filter, categories) - 1).coerceAtLeast(0) }
    val chipState = rememberLazyListState(initialFirstVisibleItemIndex = initialChipIndex)

    val chipFr = remember { FocusRequester() }
    val restoreFr = remember { FocusRequester() }
    val restoreId = remember { EzVodMemory.focusedId[kind] }
    var initialFocusDone by remember { mutableStateOf(false) }
    LaunchedEffect(res != null) {
        if (res == null || initialFocusDone) return@LaunchedEffect
        initialFocusDone = true
        delay(70)
        val restored = restoreId != null && res.items.any { it.id == restoreId } &&
            runCatching { restoreFr.requestFocus() }.isSuccess
        if (!restored) runCatching { chipFr.requestFocus() }
    }

    val selectFilter: (String) -> Unit = { k ->
        pendingFilter = null
        filter = k
    }
    val chipFocused: (String) -> Unit = { k ->
        pendingFilter = if (zone == "chips" && k != filter) k else null
        zone = "chips"
        EzVodMemory.focusedId.remove(kind)
    }

    val filterLabel = when {
        filter == "all" -> "All"
        filter == "fav" -> "My List"
        filter == "recent" -> "Recently added"
        else -> catNames[filter.removePrefix("cat:")] ?: ""
    }
    val n = res?.items?.size
    val countLine = if (n == null) {
        filterLabel
    } else {
        "$filterLabel  ·  ${String.format(Locale.getDefault(), "%,d", n)} ${if (n == 1) "title" else "titles"}"
    }

    Box(Modifier.fillMaxSize().background(EzColors.Bg)) {
        EzVodAmbient(spotItem)
        Column(Modifier.fillMaxSize()) {
            // ---- header: page title + spotlight of the focused title
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = Layout.ContentStart, end = 48.dp, top = 26.dp)
                    .height(86.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(if (isMovies) "Movies" else "Series", style = EzType.H1, maxLines = 1)
                    Spacer(Modifier.height(4.dp))
                    Text(countLine, style = EzType.BodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(24.dp))
                EzVodSpotInfo(spotItem, catNames, Modifier.width(420.dp))
            }

            // ---- category chips
            LazyRow(
                state = chipState,
                contentPadding = PaddingValues(start = Layout.ContentStart, end = 48.dp, top = 6.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                item(key = "sort") {
                    Chip(
                        text = "Sort: ${EzVodSorts[sort]}",
                        selected = false,
                        onClick = { sort = (sort + 1) % EzVodSorts.size },
                        onFocus = { f ->
                            if (f) {
                                zone = "chips"
                                pendingFilter = null
                                EzVodMemory.focusedId.remove(kind)
                            }
                        },
                    )
                }
                item(key = "divider") {
                    Box(
                        Modifier
                            .padding(horizontal = 2.dp)
                            .width(1.dp)
                            .height(22.dp)
                            .background(EzColors.Stroke),
                    )
                }
                items(EzVodFixedFilters, key = { it.first }) { (k, label) ->
                    EzVodFilterChip(
                        label = label,
                        selected = filter == k,
                        modifier = if (k == filter) Modifier.focusRequester(chipFr) else Modifier,
                        onClick = { selectFilter(k) },
                        onFocused = { chipFocused(k) },
                    )
                }
                items(categories, key = { "cat:" + it.id }) { c ->
                    val k = "cat:${c.id}"
                    EzVodFilterChip(
                        label = c.name,
                        selected = filter == k,
                        modifier = if (k == filter) Modifier.focusRequester(chipFr) else Modifier,
                        onClick = { selectFilter(k) },
                        onFocused = { chipFocused(k) },
                    )
                }
            }
            Spacer(Modifier.height(6.dp))

            // ---- grid
            Box(Modifier.fillMaxWidth().weight(1f)) {
                when {
                    res == null -> LoadingView(if (isMovies) "Loading movies…" else "Loading series…")
                    res.items.isEmpty() -> {
                        if (filter == "fav") {
                            EmptyView(
                                "Nothing in My List yet",
                                "Long-press OK on any ${if (isMovies) "movie" else "series"} to add it here.",
                                icon = Icons.Rounded.Star,
                            )
                        } else {
                            EmptyView("Nothing here", "There are no titles in this category.", icon = Icons.Rounded.MovieIcon)
                        }
                    }
                    else -> LazyVerticalGrid(
                        columns = GridCells.Fixed(6),
                        state = gridState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = Layout.ContentStart, end = 48.dp, top = 10.dp, bottom = 48.dp),
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        items(res.items, key = { it.id }) { item ->
                            PosterCard(
                                title = item.title,
                                image = item.poster,
                                onClick = {
                                    nav.navigate(if (isMovies) Screen.MovieDetail(item.id) else Screen.SeriesDetail(item.id))
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(if (item.id == restoreId) Modifier.focusRequester(restoreFr) else Modifier),
                                subtitle = item.year.ifBlank { null },
                                badge = if (item.rating > 0) "★ " + String.format(Locale.US, "%.1f", item.rating) else null,
                                onFocus = { f ->
                                    if (f) {
                                        focusedItem = item
                                        zone = "grid"
                                        pendingFilter = null
                                        EzVodMemory.focusedId[kind] = item.id
                                        EzVodMemory.gridSig[kind] = sig
                                        EzVodMemory.gridIndex[kind] = gridState.firstVisibleItemIndex
                                    }
                                },
                                onLongClick = {
                                    ezVodToggleFav(ctx, if (isMovies) "movie:${item.id}" else "series:${item.id}")
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EzVodFilterChip(
    label: String,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    onFocused: () -> Unit,
) {
    Chip(text = label, selected = selected, onClick = onClick, modifier = modifier, onFocus = { f -> if (f) onFocused() })
}

/** Dim, blurred art of the focused title bleeding in from the top-right. */
@Composable
private fun EzVodAmbient(item: EzVodItem?) {
    val url = if (item == null) "" else item.backdrop.ifBlank { item.poster }
    val wide = item != null && item.backdrop.isNotBlank()
    Box(Modifier.fillMaxWidth().fillMaxHeight(0.62f)) {
        Crossfade(targetState = url to wide, animationSpec = tween(600), label = "vodAmbient") { (u, w) ->
            if (u.isNotBlank()) {
                AsyncImg(
                    u,
                    if (w) Modifier.fillMaxSize() else Modifier.fillMaxSize().blur(40.dp),
                    alpha = if (w) 0.40f else 0.30f,
                )
            }
        }
        Box(Modifier.fillMaxSize().background(EzColors.LeftScrim))
        Box(Modifier.fillMaxSize().background(EzColors.BottomScrim))
    }
}

@Composable
private fun EzVodSpotInfo(item: EzVodItem?, catNames: Map<String, String>, modifier: Modifier) {
    Crossfade(targetState = item, modifier = modifier, animationSpec = tween(260), label = "vodSpot") { v ->
        if (v != null) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                Text(
                    v.title,
                    style = EzType.H3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                )
                Spacer(Modifier.height(4.dp))
                MetaRow(
                    listOf(v.year, ezVodGenre(v.genre).ifBlank { catNames[v.categoryId].orEmpty() }),
                    rating = v.rating,
                )
                if (v.plot.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        v.plot,
                        style = EzType.BodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.End,
                    )
                }
            }
        }
    }
}

// ============================================================================ data (runs on Dispatchers.Default)

private fun ezVodOf(m: Movie) = EzVodItem(
    id = m.id, title = m.name, poster = m.poster, backdrop = "", year = m.year, rating = m.rating,
    added = m.added, categoryId = m.categoryId, plot = "", genre = "",
)

private fun ezVodOf(s: Series) = EzVodItem(
    id = s.id, title = s.name, poster = s.poster, backdrop = if (s.backdrop != s.poster) s.backdrop else "",
    year = s.year, rating = s.rating, added = s.added, categoryId = s.categoryId, plot = s.plot, genre = s.genre,
)

private fun ezVodBuild(kind: ContentType, filter: String, sort: Int): List<EzVodItem> {
    val base: List<EzVodItem> = if (kind == ContentType.MOVIE) {
        val src: List<Movie> = when {
            filter == "fav" -> Repo.favoriteMovies().asReversed()
            filter == "recent" -> Repo.movies().sortedByDescending { it.added }.take(200)
            filter.startsWith("cat:") -> Repo.movies(filter.removePrefix("cat:"))
            else -> Repo.movies()
        }
        src.map { ezVodOf(it) }
    } else {
        val src: List<Series> = when {
            filter == "fav" -> Repo.favoriteSeries().asReversed()
            filter == "recent" -> Repo.series().sortedByDescending { it.added }.take(200)
            filter.startsWith("cat:") -> Repo.series(filter.removePrefix("cat:"))
            else -> Repo.series()
        }
        src.map { ezVodOf(it) }
    }
    // Keys in the grid must be unique.
    val unique = base.distinctBy { it.id }
    return when (sort) {
        1 -> unique.sortedWith(compareBy<EzVodItem, String>(String.CASE_INSENSITIVE_ORDER) { it.title })
        2 -> unique.sortedByDescending { it.rating }
        else -> if (filter == "fav") unique else unique.sortedByDescending { it.added }
    }
}

/** Index of the chip for [filter] in the chip LazyRow (sort, divider, 3 fixed filters, categories). */
private fun ezVodChipIndex(filter: String, categories: List<Category>): Int = when {
    filter == "all" -> 2
    filter == "fav" -> 3
    filter == "recent" -> 4
    filter.startsWith("cat:") -> {
        val i = categories.indexOfFirst { "cat:${it.id}" == filter }
        if (i >= 0) 5 + i else 2
    }
    else -> 2
}

private fun ezVodGenre(raw: String): String =
    raw.split(',', '/', '|').map { it.trim() }.filter { it.isNotEmpty() }.take(2).joinToString(" / ")

private fun ezVodToggleFav(ctx: Context, key: String) {
    val added = Repo.store.toggleFavorite(key)
    Toast.makeText(ctx, if (added) "Added to My List" else "Removed from My List", Toast.LENGTH_SHORT).show()
}
