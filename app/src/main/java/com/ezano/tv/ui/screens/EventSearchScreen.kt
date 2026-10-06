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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ezano.tv.data.Channel
import com.ezano.tv.data.EpgState
import com.ezano.tv.data.EventFinder
import com.ezano.tv.data.Programme
import com.ezano.tv.data.Reminder
import com.ezano.tv.data.Repo
import com.ezano.tv.player.Playback
import com.ezano.tv.ui.Layout
import com.ezano.tv.ui.LocalNavigator
import com.ezano.tv.ui.Screen
import com.ezano.tv.ui.components.Badge
import com.ezano.tv.ui.components.ChannelLogo
import com.ezano.tv.ui.components.Chip
import com.ezano.tv.ui.components.EzTextField
import com.ezano.tv.ui.components.FocusSurface
import com.ezano.tv.ui.components.Fmt
import com.ezano.tv.ui.components.IconPill
import com.ezano.tv.ui.components.LiveBadge
import com.ezano.tv.ui.components.LoadingView
import com.ezano.tv.ui.components.PillButton
import com.ezano.tv.ui.components.ProgressLine
import com.ezano.tv.ui.components.SectionTitle
import com.ezano.tv.ui.theme.EzColors
import com.ezano.tv.ui.theme.EzType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private typealias EvGroup = EventFinder.EventGroup

private enum class EvSection { LIVE, UPCOMING, EARLIER }

/** Results tagged with the query they were computed for. */
private class EvState(val query: String, val results: EventFinder.Results)

/** Display-ready results: channel lists de-duplicated, catch-up limited to archive channels, unique keys. */
private class EvView(
    val live: List<Pair<String, EvGroup>>,
    val upcoming: List<Pair<String, EvGroup>>,
    val earlier: List<Pair<String, EvGroup>>,
    val related: List<Channel>,
) {
    val hasCards get() = live.isNotEmpty() || upcoming.isNotEmpty() || earlier.isNotEmpty()
    val firstKey: String? get() = (live.firstOrNull() ?: upcoming.firstOrNull() ?: earlier.firstOrNull())?.first
    val isEmpty get() = !hasCards && related.isEmpty()
}

private fun evKeyed(prefix: String, groups: List<EvGroup>): List<Pair<String, EvGroup>> {
    val seen = HashMap<String, Int>()
    return groups.map { g ->
        val base = "$prefix|${g.source}|${g.key}"
        val n = seen[base] ?: 0
        seen[base] = n + 1
        (if (n == 0) base else "$base#$n") to g
    }
}

private fun evBuildView(r: EventFinder.Results): EvView {
    val live = r.live.map { it.copy(channels = it.channels.distinctBy { c -> c.id }) }.filter { it.channels.isNotEmpty() }
    val upcoming = r.upcoming.map { it.copy(channels = it.channels.distinctBy { c -> c.id }) }.filter { it.channels.isNotEmpty() }
    val earlier = r.earlier.mapNotNull { g ->
        val ch = g.channels.filter { it.catchupDays > 0 }.distinctBy { it.id }
        if (ch.isEmpty()) null else g.copy(channels = ch)
    }
    return EvView(
        live = evKeyed("live", live),
        upcoming = evKeyed("up", upcoming),
        earlier = evKeyed("cu", earlier),
        related = r.related.distinctBy { it.id },
    )
}

private fun evToast(ctx: Context, msg: String) {
    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
}

private fun evToggleFavorite(ctx: Context, c: Channel) {
    val added = Repo.store.toggleFavorite("live:${c.id}")
    evToast(ctx, if (added) "Added “${c.name}” to Favorites" else "Removed “${c.name}” from Favorites")
}

private fun evProgrammeFor(g: EvGroup, c: Channel): Programme =
    g.programmes[c.id] ?: Programme(channelKey = c.id, start = g.start, stop = g.stop, title = g.title, desc = g.description)

private fun evPlayCatchup(ctx: Context, g: EvGroup, c: Channel) {
    if (!Playback.playCatchup(ctx, c, evProgrammeFor(g, c))) evToast(ctx, "Catch-up isn't available on ${c.name}")
}

private fun evToggleReminder(ctx: Context, g: EvGroup) {
    val c = g.channels.firstOrNull() ?: return
    val r = Reminder(channelId = c.id, channelName = c.name, title = g.title, start = g.start)
    if (Repo.store.hasReminder(c.id, g.start)) {
        Repo.store.removeReminder(r)
        evToast(ctx, "Reminder removed")
    } else {
        Repo.store.addReminder(r)
        evToast(ctx, "We'll remind you when “${g.title}” starts on ${c.name}")
    }
}

/** The card's main button: Watch (live) · Remind me (upcoming) · Watch from start (catch-up). */
private fun evPrimaryAction(ctx: Context, section: EvSection, g: EvGroup) {
    val first = g.channels.firstOrNull() ?: return
    when (section) {
        EvSection.LIVE -> Playback.playChannel(ctx, first, g.channels)
        EvSection.UPCOMING -> evToggleReminder(ctx, g)
        EvSection.EARLIER -> evPlayCatchup(ctx, g, first)
    }
}

private fun evQualityLabel(name: String): String? = when (val q = EventFinder.quality(name)) {
    4 -> "4K"
    3 -> "FHD"
    2 -> "HD"
    0 -> "SD"
    else -> if (q < 0) "BACKUP" else null
}

/** "Find an event": every channel showing a game/match/show, grouped per event. */
@Composable
fun EventSearchScreen(query: String) {
    val ctx = LocalContext.current
    val nav = LocalNavigator.current
    var text by rememberSaveable(query) { mutableStateOf(query) }
    var active by rememberSaveable(query) { mutableStateOf(query.trim()) }

    val idx by Repo.index.collectAsState()
    val epg by Repo.epg.collectAsState()
    val epgState by Repo.epgState.collectAsState()
    val tick by Repo.clock.collectAsState()
    val settings by Repo.store.settings.collectAsState()
    val reminders by Repo.store.reminders.collectAsState()
    val recents by Repo.store.recentSearches.collectAsState()
    val now = remember(tick) { System.currentTimeMillis() }
    val use24h = settings.use24h
    val bucket = tick / 120_000L // re-rank live/upcoming every couple of minutes

    // Re-runs when the query changes, the playlist changes, or the guide (re)loads.
    val state by produceState<EvState?>(null, active, idx, epg, bucket) {
        val q = active
        val r = withContext(Dispatchers.Default) {
            runCatching { EventFinder.find(q) }.getOrNull() ?: EventFinder.Results(q)
        }
        value = EvState(q, r)
    }
    val st = state
    val results = st?.results
    val view = remember(results) { results?.let { evBuildView(it) } }
    val searching = st == null || st.query != active
    val reminderKeys = remember(reminders) { reminders.mapTo(HashSet()) { "${it.channelId}|${it.start}" } }

    val headerFocus = remember { FocusRequester() }
    val firstFocus = remember { FocusRequester() }
    var focusedFor by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        delay(60)
        runCatching { headerFocus.requestFocus() }
    }
    // Once results for a new query arrive, jump to the best "Watch" button.
    LaunchedEffect(st, view) {
        val s = st ?: return@LaunchedEffect
        if (s.query == active && focusedFor != active) {
            focusedFor = active
            if (view != null && view.hasCards) {
                delay(120)
                runCatching { firstFocus.requestFocus() }
            }
        }
    }

    val submit: () -> Unit = {
        val q = text.trim()
        if (q.isNotEmpty()) {
            Repo.store.addRecentSearch(q)
            active = q
        }
    }
    val runRecent: (String) -> Unit = { q ->
        text = q
        active = q
        Repo.store.addRecentSearch(q)
    }

    val summary: String = when {
        view == null || searching -> "Searching every channel and the TV guide…"
        view.live.isNotEmpty() -> {
            val full = view.live.filter { it.second.fullMatch }.ifEmpty { view.live }
            val n = full.flatMap { it.second.channels }.distinctBy { it.id }.size
            "Live now on $n channel${if (n == 1) "" else "s"}"
        }
        view.upcoming.isNotEmpty() -> {
            val g = view.upcoming.first().second
            "Next: ${Fmt.dayTime(g.start, use24h)} on ${g.channels.size} channel${if (g.channels.size == 1) "" else "s"}"
        }
        view.earlier.isNotEmpty() -> "Available on catch-up"
        view.related.isNotEmpty() -> "${view.related.size} related channels"
        else -> "No matches"
    }

    Box(Modifier.fillMaxSize().background(EzColors.Bg)) {
        // Soft accent glow at the top for a cinematic feel
        Box(
            Modifier.fillMaxWidth().height(260.dp).background(
                Brush.verticalGradient(listOf(EzColors.Accent.copy(alpha = 0.16f), Color.Transparent)),
            ),
        )
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 24.dp, bottom = 56.dp)) {
            // ------------------------------------------------------------ header
            item(key = "header") {
                Column(Modifier.padding(start = Layout.ContentStart, end = 40.dp, bottom = 18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconPill(Icons.AutoMirrored.Rounded.ArrowBack, onClick = { nav.back() }, contentDescription = "Back")
                        Spacer(Modifier.width(16.dp))
                        EzTextField(
                            value = text,
                            onValueChange = { text = it },
                            label = "",
                            placeholder = "Find a game, match or show… e.g. Lakers vs Celtics",
                            leadingIcon = Icons.Rounded.Search,
                            imeAction = ImeAction.Search,
                            onSubmit = submit,
                            modifier = Modifier.weight(1f).focusRequester(headerFocus),
                        )
                        Spacer(Modifier.width(12.dp))
                        PillButton("TV Guide", onClick = { nav.navigate(Screen.Guide) }, icon = Icons.Rounded.CalendarMonth)
                    }
                    Spacer(Modifier.height(18.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.LiveTv, contentDescription = null, tint = EzColors.Accent2, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("FIND AN EVENT", style = EzType.Label.copy(color = EzColors.Accent2, fontWeight = FontWeight.ExtraBold))
                        if (searching) {
                            Spacer(Modifier.width(10.dp))
                            CircularProgressIndicator(color = EzColors.Accent2, strokeWidth = 1.5.dp, modifier = Modifier.size(12.dp))
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            active,
                            style = EzType.H1,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text("·  $summary", style = EzType.H3.copy(color = EzColors.TextSecondary, fontWeight = FontWeight.SemiBold), maxLines = 1, modifier = Modifier.padding(bottom = 4.dp))
                    }
                }
            }

            // ------------------------------------------------------------ guide notices
            if (epgState is EpgState.Loading) {
                item(key = "notice-loading") {
                    EvNotice(text = "Still loading the TV guide – results will improve in a moment", spinner = true)
                }
            } else if (results != null && !results.guideAvailable) {
                item(key = "notice-noguide") {
                    EvNotice(text = "No TV guide is available for this playlist, so only channel names were searched.", spinner = false)
                }
            }

            if (idx == null || view == null) {
                item(key = "loading") {
                    Box(Modifier.fillMaxWidth().height(280.dp)) { LoadingView("Searching every channel…") }
                }
            } else if (view.isEmpty && !searching) {
                item(key = "empty") { EvEmptyState(active, results?.guideAvailable ?: true) }
                if (recents.isNotEmpty()) {
                    item(key = "recents") {
                        Column(Modifier.padding(top = 26.dp)) {
                            SectionTitle("Recent searches", Modifier.padding(start = Layout.ContentStart))
                            LazyRow(
                                contentPadding = PaddingValues(start = Layout.ContentStart, end = 40.dp, top = 4.dp, bottom = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                items(recents, key = { it }) { q -> Chip(q, selected = false, onClick = { runRecent(q) }) }
                            }
                        }
                    }
                }
            } else {
                val firstKey = view.firstKey
                evSection(
                    section = EvSection.LIVE, title = "Live now", groups = view.live, firstKey = firstKey,
                    firstFocus = firstFocus, now = now, use24h = use24h, reminderKeys = reminderKeys, ctx = ctx,
                )
                evSection(
                    section = EvSection.UPCOMING, title = "Coming up", groups = view.upcoming, firstKey = firstKey,
                    firstFocus = firstFocus, now = now, use24h = use24h, reminderKeys = reminderKeys, ctx = ctx,
                )
                evSection(
                    section = EvSection.EARLIER, title = "Catch up", groups = view.earlier, firstKey = firstKey,
                    firstFocus = firstFocus, now = now, use24h = use24h, reminderKeys = reminderKeys, ctx = ctx,
                )
                if (view.related.isNotEmpty()) {
                    item(key = "related") {
                        val related = view.related
                        Column(Modifier.padding(top = 10.dp)) {
                            SectionTitle("Related channels", Modifier.padding(start = Layout.ContentStart), trailing = "From matching categories")
                            LazyRow(
                                contentPadding = PaddingValues(start = Layout.ContentStart, end = 40.dp, top = 6.dp, bottom = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                items(related, key = { it.id }) { c ->
                                    EvChannelCard(
                                        c = c, best = false, caption = null,
                                        onClick = { Playback.playChannel(ctx, c, related) },
                                        onLongClick = { evToggleFavorite(ctx, c) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------------- sections

private fun LazyListScope.evSection(
    section: EvSection,
    title: String,
    groups: List<Pair<String, EvGroup>>,
    firstKey: String?,
    firstFocus: FocusRequester,
    now: Long,
    use24h: Boolean,
    reminderKeys: Set<String>,
    ctx: Context,
) {
    if (groups.isEmpty()) return
    val fullCount = groups.count { it.second.fullMatch }
    val trailing = when (section) {
        EvSection.LIVE -> "$fullCount event${if (fullCount == 1) "" else "s"} on now"
        EvSection.UPCOMING -> "Later today and this week"
        EvSection.EARLIER -> "Watch from the start"
    }
    item(key = "title-$section") {
        SectionTitle(title, Modifier.padding(start = Layout.ContentStart, top = 8.dp), trailing = trailing)
    }
    val firstPartial = groups.indexOfFirst { !it.second.fullMatch }
    groups.forEachIndexed { i, (k, g) ->
        if (i == firstPartial) {
            item(key = "partial-$section") {
                Text(
                    "Related",
                    style = EzType.Label.copy(color = EzColors.TextTertiary),
                    modifier = Modifier.padding(start = Layout.ContentStart, top = 4.dp, bottom = 10.dp),
                )
            }
        }
        item(key = k) {
            val first = g.channels.firstOrNull()
            EvEventCard(
                g = g,
                section = section,
                now = now,
                use24h = use24h,
                hasReminder = first != null && "${first.id}|${g.start}" in reminderKeys,
                primaryModifier = if (k == firstKey) Modifier.focusRequester(firstFocus) else Modifier,
                onPrimary = { evPrimaryAction(ctx, section, g) },
                onChannel = { c ->
                    if (section == EvSection.EARLIER) evPlayCatchup(ctx, g, c) else Playback.playChannel(ctx, c, g.channels)
                },
                onLongChannel = { c -> evToggleFavorite(ctx, c) },
            )
        }
    }
    item(key = "spacer-$section") { Spacer(Modifier.height(14.dp)) }
}

@Composable
private fun EvEventCard(
    g: EvGroup,
    section: EvSection,
    now: Long,
    use24h: Boolean,
    hasReminder: Boolean,
    primaryModifier: Modifier,
    onPrimary: () -> Unit,
    onChannel: (Channel) -> Unit,
    onLongChannel: (Channel) -> Unit,
) {
    val hasTimes = g.start > 0L && g.stop > g.start
    val isEventChannel = g.source == EventFinder.Source.CHANNEL_NAME
    Column(
        Modifier
            .padding(start = Layout.ContentStart, end = 40.dp, bottom = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        if (section == EvSection.LIVE) Color(0xFF231E40) else EzColors.SurfaceHi,
                        EzColors.Surface,
                        EzColors.Bg2,
                    ),
                ),
            )
            .padding(top = 18.dp, bottom = 12.dp),
    ) {
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                // Badges
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when (section) {
                        EvSection.LIVE -> LiveBadge()
                        EvSection.UPCOMING -> Badge(if (hasTimes) Fmt.dayTime(g.start, use24h).uppercase() else "UPCOMING", color = EzColors.Accent.copy(alpha = 0.85f))
                        EvSection.EARLIER -> Badge("CATCH-UP", color = EzColors.Accent2.copy(alpha = 0.55f))
                    }
                    if (isEventChannel) {
                        Spacer(Modifier.width(8.dp))
                        Badge("EVENT CHANNEL", color = Color.White.copy(alpha = 0.16f))
                    }
                    if (!g.fullMatch) {
                        Spacer(Modifier.width(8.dp))
                        Badge("RELATED", color = Color.White.copy(alpha = 0.10f))
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "${g.channels.size} channel${if (g.channels.size == 1) "" else "s"}",
                        style = EzType.Caption,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(g.title.ifBlank { "Untitled" }, style = EzType.H2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                val timeLine = when (section) {
                    EvSection.LIVE ->
                        if (hasTimes) "${Fmt.time(g.start, use24h)} – ${Fmt.time(g.stop, use24h)}  ·  ${Fmt.minutesLeft(g.stop, now)}"
                        else "On now"
                    EvSection.UPCOMING ->
                        if (hasTimes) "Starts ${Fmt.dayTime(g.start, use24h)}  ·  in ${Fmt.duration((g.start - now).coerceAtLeast(0L))}"
                        else "Coming up"
                    EvSection.EARLIER ->
                        if (hasTimes) "Aired ${Fmt.dayTime(g.start, use24h)}  ·  ${Fmt.duration(g.stop - g.start)}"
                        else "Available on catch-up"
                }
                Text(timeLine, style = EzType.BodySmall.copy(color = EzColors.TextSecondary, fontWeight = FontWeight.Medium), maxLines = 1)
                if (section == EvSection.LIVE && hasTimes) {
                    Spacer(Modifier.height(8.dp))
                    ProgressLine(((now - g.start).toFloat() / (g.stop - g.start)).coerceIn(0f, 1f), Modifier.width(280.dp), height = 3.dp)
                }
                if (g.description.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(g.description, style = EzType.Body, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(16.dp))
            when (section) {
                EvSection.LIVE -> PillButton("Watch", onClick = onPrimary, icon = Icons.Rounded.PlayArrow, primary = true, modifier = primaryModifier)
                EvSection.UPCOMING -> PillButton(
                    if (hasReminder) "Reminder set" else "Remind me",
                    onClick = onPrimary,
                    icon = Icons.Rounded.NotificationsActive,
                    primary = !hasReminder,
                    modifier = primaryModifier,
                )
                EvSection.EARLIER -> PillButton("Watch from start", onClick = onPrimary, icon = Icons.Rounded.History, primary = true, modifier = primaryModifier)
            }
        }
        Spacer(Modifier.height(12.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(g.channels, key = { _, c -> c.id }) { i, c ->
                EvChannelCard(
                    c = c,
                    best = i == 0 && g.channels.size > 1,
                    caption = if (section == EvSection.UPCOMING) "Watch channel now" else null,
                    onClick = { onChannel(c) },
                    onLongClick = { onLongChannel(c) },
                )
            }
        }
    }
}

@Composable
private fun EvChannelCard(
    c: Channel,
    best: Boolean,
    caption: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val quality = remember(c.name) { evQualityLabel(c.name) }
    FocusSurface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = Modifier.width(228.dp).height(68.dp),
        shape = RoundedCornerShape(14.dp),
        focusedScale = 1.05f,
        color = Color.White.copy(alpha = 0.06f),
        focusedColor = EzColors.SurfaceFocus,
    ) { _ ->
        Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            ChannelLogo(c.logo, c.name, size = 38.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(c.name, style = EzType.Title.copy(fontSize = 13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (best) {
                        Badge("BEST MATCH", color = EzColors.Accent.copy(alpha = 0.9f))
                        Spacer(Modifier.width(5.dp))
                    }
                    if (quality != null) {
                        Badge(quality, color = Color.White.copy(alpha = 0.14f))
                        Spacer(Modifier.width(5.dp))
                    }
                    if (caption != null && !best) {
                        Text(caption, style = EzType.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    } else if (c.number > 0 && quality == null && !best) {
                        Text("Ch ${c.number}", style = EzType.Caption, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun EvNotice(text: String, spinner: Boolean) {
    Row(
        Modifier
            .padding(start = Layout.ContentStart, end = 40.dp, bottom = 16.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(EzColors.Warning.copy(alpha = 0.10f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (spinner) {
            CircularProgressIndicator(color = EzColors.Warning, strokeWidth = 1.5.dp, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = EzType.BodySmall.copy(color = EzColors.Warning))
    }
}

@Composable
private fun EvEmptyState(query: String, guideAvailable: Boolean) {
    Column(Modifier.padding(start = Layout.ContentStart, end = 40.dp, top = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(56.dp).clip(CircleShape).background(EzColors.Surface),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Search, contentDescription = null, tint = EzColors.TextTertiary, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text("Nothing found for “$query”", style = EzType.H2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (guideAvailable) "We searched every programme in the TV guide and every channel name."
                    else "Without a TV guide we can only search channel names.",
                    style = EzType.Body,
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        Text("TRY", style = EzType.Label.copy(color = EzColors.Accent2, fontWeight = FontWeight.ExtraBold))
        Spacer(Modifier.height(6.dp))
        val tips = listOf(
            "A team nickname – “Celtics” instead of “Boston Celtics”",
            "A league or competition – “NBA”, “Premier League”, “UFC”, “F1”",
            "A channel name – “ESPN”, “Sky Sports”, “DAZN”",
            "Fewer words – “Lakers” finds every Lakers game this week",
        )
        tips.forEach { t ->
            Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(EzColors.AccentGradient))
                Spacer(Modifier.width(10.dp))
                Text(t, style = EzType.Body.copy(color = EzColors.TextPrimary))
            }
        }
    }
}
