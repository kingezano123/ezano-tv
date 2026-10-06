package com.ezano.tv.ui.screens

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ezano.tv.data.Channel
import com.ezano.tv.data.EventFinder
import com.ezano.tv.data.Repo
import com.ezano.tv.data.SearchResults
import com.ezano.tv.player.Playback
import com.ezano.tv.ui.Layout
import com.ezano.tv.ui.LocalNavigator
import com.ezano.tv.ui.Screen
import com.ezano.tv.ui.components.Badge
import com.ezano.tv.ui.components.Chip
import com.ezano.tv.ui.components.EmptyView
import com.ezano.tv.ui.components.EzTextField
import com.ezano.tv.ui.components.FocusSurface
import com.ezano.tv.ui.components.Fmt
import com.ezano.tv.ui.components.LiveBadge
import com.ezano.tv.ui.components.PosterCard
import com.ezano.tv.ui.components.ProgressLine
import com.ezano.tv.ui.components.SectionTitle
import com.ezano.tv.ui.components.WideCard
import com.ezano.tv.ui.components.contentRow
import com.ezano.tv.ui.theme.EzColors
import com.ezano.tv.ui.theme.EzType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

/** Keeps the last query when switching tabs and coming back. */
private object SrMemory {
    var query: String = ""
}

private fun srToast(ctx: Context, msg: String) {
    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
}

private fun srToggleFavorite(ctx: Context, key: String, name: String) {
    val added = Repo.store.toggleFavorite(key)
    srToast(ctx, if (added) "Added “$name” to Favorites" else "Removed “$name” from Favorites")
}

/** Global search tab: channels, movies, series + live events from the TV guide. */
@Composable
fun SearchScreen() {
    val ctx = LocalContext.current
    val nav = LocalNavigator.current
    var q by rememberSaveable { mutableStateOf(SrMemory.query) }
    var debounced by remember { mutableStateOf(q.trim()) }
    LaunchedEffect(q) {
        SrMemory.query = q
        delay(300)
        debounced = q.trim()
    }

    val idx by Repo.index.collectAsState()
    val epg by Repo.epg.collectAsState()
    val tick by Repo.clock.collectAsState()
    val settings by Repo.store.settings.collectAsState()
    val recents by Repo.store.recentSearches.collectAsState()
    val now = remember(tick) { System.currentTimeMillis() }
    val use24h = settings.use24h

    val results by produceState(SearchResults(), debounced, idx, settings) {
        val query = debounced
        value = if (query.isBlank()) {
            SearchResults()
        } else {
            withContext(Dispatchers.Default) {
                runCatching {
                    val r = Repo.search(query)
                    SearchResults(r.channels.distinctBy { it.id }, r.movies.distinctBy { it.id }, r.series.distinctBy { it.id })
                }.getOrDefault(SearchResults())
            }
        }
    }
    val events by produceState(emptyList<EventFinder.EventGroup>(), debounced, idx, epg) {
        val query = debounced
        value = if (query.length < 3) {
            emptyList()
        } else {
            withContext(Dispatchers.Default) {
                runCatching {
                    val r = EventFinder.find(query)
                    (r.live + r.upcoming).sortedByDescending { it.fullMatch }.take(6)
                }.getOrDefault(emptyList())
            }
        }
    }

    val fieldFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { fieldFocus.requestFocus() }
    }

    val openEventSearch: () -> Unit = {
        val query = debounced
        if (query.isNotBlank()) {
            Repo.store.addRecentSearch(query)
            nav.navigate(Screen.EventSearch(query))
        }
    }
    val saveQuery: () -> Unit = { Repo.store.addRecentSearch(debounced) }

    Box(Modifier.fillMaxSize().background(EzColors.Bg)) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 28.dp, bottom = 56.dp)) {
            item(key = "field") {
                Column(Modifier.padding(start = Layout.ContentStart, end = 48.dp, bottom = 22.dp)) {
                    Text("Search", style = EzType.H1)
                    Spacer(Modifier.height(12.dp))
                    EzTextField(
                        value = q,
                        onValueChange = { q = it },
                        label = "",
                        placeholder = "Channels, movies, series, games…",
                        leadingIcon = Icons.Rounded.Search,
                        imeAction = ImeAction.Search,
                        onSubmit = {
                            debounced = q.trim()
                            Repo.store.addRecentSearch(q)
                        },
                        autoEdit = false,
                        modifier = Modifier.focusRequester(fieldFocus),
                    )
                }
            }

            if (debounced.isBlank()) {
                if (recents.isNotEmpty()) {
                    item(key = "recents") {
                        Column(Modifier.padding(bottom = 26.dp)) {
                            Row(Modifier.padding(start = Layout.ContentStart), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.History, contentDescription = null, tint = EzColors.TextTertiary, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                SectionTitle("Recent searches", Modifier.padding(top = 12.dp))
                            }
                            LazyRow(
                                contentPadding = PaddingValues(start = Layout.ContentStart, end = 48.dp, top = 4.dp, bottom = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                items(recents, key = { it }) { r ->
                                    Chip(r, selected = false, onClick = {
                                        q = r
                                        debounced = r
                                    })
                                }
                            }
                        }
                    }
                }
                item(key = "hint") { SrHint() }
            } else {
                // "Search the TV guide" – the event finder entry point
                item(key = "guide-search") {
                    SrGuideSearchButton(query = debounced, onClick = openEventSearch)
                }
                val indexedEvents = events.withIndex().toList()
                contentRow(
                    title = "Events on now",
                    items = indexedEvents,
                    key = { it.index },
                    startPadding = Layout.ContentStart,
                    trailing = "From the TV guide",
                ) { ev ->
                    SrEventCard(g = ev.value, now = now, use24h = use24h, onClick = openEventSearch)
                }
                val chans = results.channels
                contentRow(
                    title = "Channels",
                    items = chans,
                    key = { it.id },
                    startPadding = Layout.ContentStart,
                    trailing = "${chans.size}",
                ) { c ->
                    SrChannelTile(
                        c = c,
                        nowTitle = remember(c.id, epg, now) { epg.now(c, now)?.title },
                        onClick = {
                            saveQuery()
                            Playback.playChannel(ctx, c, chans)
                        },
                        onLongClick = { srToggleFavorite(ctx, "live:${c.id}", c.name) },
                    )
                }
                contentRow(
                    title = "Movies",
                    items = results.movies,
                    key = { it.id },
                    startPadding = Layout.ContentStart,
                    trailing = "${results.movies.size}",
                ) { m ->
                    PosterCard(
                        title = m.name,
                        image = m.poster,
                        onClick = {
                            saveQuery()
                            nav.navigate(Screen.MovieDetail(m.id))
                        },
                        subtitle = m.year.ifBlank { null },
                        badge = if (m.rating > 0) "★ ${String.format(Locale.US, "%.1f", m.rating)}" else null,
                        onLongClick = { srToggleFavorite(ctx, "movie:${m.id}", m.name) },
                    )
                }
                contentRow(
                    title = "Series",
                    items = results.series,
                    key = { it.id },
                    startPadding = Layout.ContentStart,
                    trailing = "${results.series.size}",
                ) { s ->
                    PosterCard(
                        title = s.name,
                        image = s.poster,
                        onClick = {
                            saveQuery()
                            nav.navigate(Screen.SeriesDetail(s.id))
                        },
                        subtitle = s.year.ifBlank { null },
                        badge = if (s.rating > 0) "★ ${String.format(Locale.US, "%.1f", s.rating)}" else null,
                        onLongClick = { srToggleFavorite(ctx, "series:${s.id}", s.name) },
                    )
                }
                if (results.isEmpty && events.isEmpty()) {
                    item(key = "empty") {
                        Box(Modifier.fillMaxWidth().height(300.dp)) {
                            EmptyView(
                                "No results for “$debounced”",
                                "Try a shorter word, or search the TV guide above to find a game, match or show.",
                                Icons.Rounded.Search,
                            )
                        }
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------------- pieces

@Composable
private fun SrHint() {
    Row(
        Modifier
            .padding(start = Layout.ContentStart, end = 48.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(EzColors.Surface)
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(EzColors.AccentGradient),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.LiveTv, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column {
            Text("Looking for a game?", style = EzType.H3)
            Spacer(Modifier.height(2.dp))
            Text(
                "Type a team, match or show – like “Lakers vs Celtics”, “UFC” or “F1” – and we'll find every channel showing it, " +
                    "plus matching channels, movies and series.",
                style = EzType.Body,
            )
        }
    }
}

@Composable
private fun SrGuideSearchButton(query: String, onClick: () -> Unit) {
    FocusSurface(
        onClick = onClick,
        modifier = Modifier.padding(start = Layout.ContentStart, end = 48.dp, bottom = 24.dp).fillMaxWidth().height(64.dp),
        shape = RoundedCornerShape(16.dp),
        focusedScale = 1.02f,
        color = Color.White.copy(alpha = 0.06f),
        focusedColor = EzColors.SurfaceFocus,
    ) { _ ->
        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(38.dp).clip(CircleShape).background(EzColors.AccentGradient),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.LiveTv, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Search the TV guide for “$query”",
                    style = EzType.Title.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "Find every channel showing this game, match or show – live, later and on catch-up",
                    style = EzType.Caption,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(10.dp))
            Icon(Icons.Rounded.Search, contentDescription = null, tint = EzColors.TextSecondary, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun SrEventCard(g: EventFinder.EventGroup, now: Long, use24h: Boolean, onClick: () -> Unit) {
    val live = g.whenType == EventFinder.When.LIVE
    val hasTimes = g.start > 0L && g.stop > g.start
    FocusSurface(
        onClick = onClick,
        modifier = Modifier.width(300.dp).height(112.dp),
        shape = RoundedCornerShape(14.dp),
        color = if (live) Color(0xFF231E40) else EzColors.Surface,
    ) { _ ->
        Column(Modifier.fillMaxSize().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (live) {
                    LiveBadge()
                } else if (hasTimes) {
                    Badge(Fmt.dayTime(g.start, use24h).uppercase(), color = EzColors.Accent.copy(alpha = 0.85f))
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    "On ${g.channels.size} channel${if (g.channels.size == 1) "" else "s"}",
                    style = EzType.Caption,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(g.title.ifBlank { "Untitled" }, style = EzType.Title, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.weight(1f))
            if (live && hasTimes) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProgressLine(
                        ((now - g.start).toFloat() / (g.stop - g.start)).coerceIn(0f, 1f),
                        Modifier.weight(1f),
                        height = 3.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(Fmt.minutesLeft(g.stop, now), style = EzType.Caption, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun SrChannelTile(c: Channel, nowTitle: String?, onClick: () -> Unit, onLongClick: () -> Unit) {
    WideCard(
        title = c.name,
        image = c.logo,
        onClick = onClick,
        width = 200.dp,
        subtitle = nowTitle ?: if (c.number > 0) "Channel ${c.number}" else "Live channel",
        imageFit = true,
        onLongClick = onLongClick,
    )
}
