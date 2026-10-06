package com.ezano.tv.ui.screens

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ezano.tv.data.Channel
import com.ezano.tv.data.ContentType
import com.ezano.tv.data.EpgData
import com.ezano.tv.data.EpgState
import com.ezano.tv.data.LibraryIndex
import com.ezano.tv.data.LoadState
import com.ezano.tv.data.Programme
import com.ezano.tv.data.Reminder
import com.ezano.tv.data.Repo
import com.ezano.tv.player.Playback
import com.ezano.tv.ui.Layout
import com.ezano.tv.ui.components.Badge
import com.ezano.tv.ui.components.ChannelLogo
import com.ezano.tv.ui.components.Chip
import com.ezano.tv.ui.components.EmptyView
import com.ezano.tv.ui.components.FocusSurface
import com.ezano.tv.ui.components.Fmt
import com.ezano.tv.ui.components.LiveBadge
import com.ezano.tv.ui.components.LoadingView
import com.ezano.tv.ui.components.ProgressLine
import com.ezano.tv.ui.theme.EzColors
import com.ezano.tv.ui.theme.EzType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import androidx.compose.ui.input.key.key as composeKey

private const val GD_FAV = "__fav"
private const val GD_ALL = "__all"
private const val GD_MIN = 60_000L
private const val GD_HALF = 30 * GD_MIN
private const val GD_HOUR = 60 * GD_MIN
private const val GD_DAY = 24 * GD_HOUR
/** Visible guide window: 2.5 hours. */
private const val GD_WINDOW_MIN = 150
private const val GD_WINDOW = GD_WINDOW_MIN * GD_MIN
private val GdChannelCol = 180.dp
private val GdGap = 8.dp
private val GdRowHeight = 60.dp
private val GdDark = Color(0xFF0B0B10)

private object GdMemory {
    var category: String? = null
}

private class GdChannels(val category: String, val list: List<Channel>)

/** "Move focus to the block of [channelId] that covers [time]" – used after the time window shifts. */
private data class GdFocusReq(val channelId: String, val time: Long)

/** What the details strip shows. */
private class GdFocused(val channel: Channel, val programme: Programme?)

/** Floors to the local half hour (works for +5:30 / +5:45 time zones too). */
private fun gdHalfFloor(t: Long): Long {
    val off = TimeZone.getDefault().getOffset(t).toLong()
    return (t + off) / GD_HALF * GD_HALF - off
}

private fun gdDayLabel(ms: Long): String {
    val now = System.currentTimeMillis()
    val f = SimpleDateFormat("yyyyMMdd", Locale.US)
    val d = f.format(Date(ms))
    return when (d) {
        f.format(Date(now)) -> "Today"
        f.format(Date(now + GD_DAY)) -> "Tomorrow"
        f.format(Date(now - GD_DAY)) -> "Yesterday"
        else -> SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(ms))
    }
}

private fun gdCatchupOk(c: Channel, p: Programme, now: Long): Boolean =
    c.catchupDays > 0 && p.stop <= now && p.start >= now - c.catchupDays * GD_DAY

private fun gdToast(ctx: Context, msg: String) {
    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
}

/** OK on a programme block: live → play, past → catch-up, future → toggle reminder. */
private fun gdSelectProgramme(ctx: Context, c: Channel, p: Programme, zap: List<Channel>) {
    val t = System.currentTimeMillis()
    if (p.isLive(t)) {
        Playback.playChannel(ctx, c, zap)
    } else if (p.stop <= t) {
        if (gdCatchupOk(c, p, t)) {
            val ok = Playback.playCatchup(ctx, c, p)
            if (!ok) gdToast(ctx, "Catch-up isn't available for this programme")
        } else if (c.catchupDays > 0) {
            gdToast(ctx, "This programme is outside the ${c.catchupDays}-day catch-up window")
        } else {
            gdToast(ctx, "This programme has ended – ${c.name} doesn't offer catch-up")
        }
    } else {
        val r = Reminder(channelId = c.id, channelName = c.name, title = p.title, start = p.start)
        if (Repo.store.hasReminder(c.id, p.start)) {
            Repo.store.removeReminder(r)
            gdToast(ctx, "Reminder removed")
        } else {
            Repo.store.addReminder(r)
            gdToast(ctx, "Reminder set for “${p.title}” at ${Fmt.time(p.start, Repo.settings.use24h)}")
        }
    }
}

private fun gdToggleFavorite(ctx: Context, c: Channel) {
    val added = Repo.store.toggleFavorite("live:${c.id}")
    gdToast(ctx, if (added) "Added “${c.name}” to Favorites" else "Removed “${c.name}” from Favorites")
}

/** Full EPG grid. */
@Composable
fun GuideScreen() {
    val idx by Repo.index.collectAsState()
    val loadState by Repo.loadState.collectAsState()
    val index = idx
    Box(Modifier.fillMaxSize().background(EzColors.Bg)) {
        if (index == null) {
            val ls = loadState
            if (ls is LoadState.Error) {
                EmptyView("Couldn't load your channels", ls.message, Icons.Rounded.CalendarMonth)
            } else {
                LoadingView((ls as? LoadState.Loading)?.message ?: "Loading the TV guide…")
            }
        } else if (index.lib.channels.isEmpty()) {
            EmptyView("No live channels", "This playlist doesn't include any live TV channels.", Icons.Rounded.LiveTv)
        } else {
            GdContent(index)
        }
    }
}

@Composable
private fun GdContent(index: LibraryIndex) {
    val ctx = LocalContext.current
    val epg by Repo.epg.collectAsState()
    val epgState by Repo.epgState.collectAsState()
    val tick by Repo.clock.collectAsState()
    val favs by Repo.store.favorites.collectAsState()
    val settings by Repo.store.settings.collectAsState()
    val reminders by Repo.store.reminders.collectAsState()
    val now = remember(tick) { System.currentTimeMillis() }
    val use24h = settings.use24h

    val categories = remember(index, settings) { Repo.visibleCategories(ContentType.LIVE).distinctBy { it.id } }
    val favIds = remember(index, favs) {
        Repo.store.favoriteKeys().filter { it.startsWith("live:") }.mapTo(HashSet()) { it.removePrefix("live:") }
    }
    val reminderKeys = remember(reminders) { reminders.mapTo(HashSet()) { "${it.channelId}|${it.start}" } }

    var selectedCat by remember {
        val mem = GdMemory.category
        mutableStateOf(
            if (mem != null && (mem == GD_FAV || mem == GD_ALL || categories.any { it.id == mem })) mem
            else if (favIds.any { index.channelById.containsKey(it) }) GD_FAV
            else GD_ALL,
        )
    }
    LaunchedEffect(selectedCat) { GdMemory.category = selectedCat }

    val favKey: Any? = if (selectedCat == GD_FAV) favIds else null
    val loaded by produceState<GdChannels?>(null, selectedCat, index, favKey, settings) {
        val cat = selectedCat
        val list = withContext(Dispatchers.Default) {
            val raw = when (cat) {
                GD_FAV -> Repo.favoriteChannels()
                GD_ALL -> Repo.channels(null)
                else -> Repo.channels(cat)
            }
            raw.distinctBy { it.id }
        }
        value = GdChannels(cat, list)
    }
    val data = loaded
    val channels = data?.list ?: emptyList()

    // ---- time window (rows are clipped to [viewStart, viewStart + 2.5h]; no horizontal scroll containers)
    var viewStart by remember { mutableLongStateOf(gdHalfFloor(System.currentTimeMillis())) }
    val viewEnd = viewStart + GD_WINDOW
    val nowHalf = gdHalfFloor(now)
    val hasCatchup = remember(channels) { channels.any { it.catchupDays > 0 } }
    val minStart = if (hasCatchup) nowHalf - 6 * GD_HOUR else nowHalf
    val maxStart = nowHalf + settings.epgHoursAhead.coerceAtLeast(3) * GD_HOUR - GD_WINDOW

    var pendingFocus by remember { mutableStateOf<GdFocusReq?>(null) }
    var focusInfo by remember { mutableStateOf<GdFocused?>(null) }
    var initialFocusDone by remember { mutableStateOf(false) }
    val chipFocus = remember { FocusRequester() }

    LaunchedEffect(data) {
        if (data != null && !initialFocusDone) {
            initialFocusDone = true
            val first = data.list.firstOrNull()
            if (first != null) {
                pendingFocus = GdFocusReq(first.id, System.currentTimeMillis())
            } else {
                delay(80)
                runCatching { chipFocus.requestFocus() }
            }
        }
    }

    // D-pad Right on the last visible block of a row: move the window forward.
    val onEdgeRight: (Channel, List<Programme>) -> Boolean = { c, all ->
        val start = viewStart
        val end = start + GD_WINDOW
        val target = EpgData.findAt(all, end) ?: all.firstOrNull { it.start >= end }
        if (target == null || start >= maxStart) {
            true // nothing further – keep focus where it is
        } else {
            var ns = minOf(start + GD_HOUR, maxStart)
            if (target.start >= ns + GD_WINDOW) ns = minOf(gdHalfFloor(target.start), maxStart)
            viewStart = ns
            pendingFocus = GdFocusReq(c.id, maxOf(target.start, ns))
            true
        }
    }
    // D-pad Left on the first visible block: move the window back (into catch-up territory), or
    // let focus continue to the channel column when we're already at the earliest point.
    val onEdgeLeft: (Channel, List<Programme>) -> Boolean = { c, all ->
        val start = viewStart
        if (start <= minStart) {
            false
        } else {
            val target = EpgData.findAt(all, start - 1) ?: all.lastOrNull { it.stop <= start }
            if (target == null) {
                false
            } else {
                var ns = maxOf(start - GD_HOUR, minStart)
                if (target.stop <= ns) ns = maxOf(gdHalfFloor(target.start), minStart)
                viewStart = ns
                pendingFocus = GdFocusReq(c.id, maxOf(target.start, ns))
                true
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(top = 18.dp)) {
        // ---------------------------------------------------------------- header
        Row(
            Modifier.fillMaxWidth().padding(start = Layout.ContentStart, end = 32.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("TV Guide", style = EzType.H1)
            Spacer(Modifier.width(16.dp))
            GdEpgStatus(epgState)
            Spacer(Modifier.weight(1f))
            Text(Fmt.time(now, use24h), style = EzType.H3)
        }
        Spacer(Modifier.height(10.dp))

        // ---------------------------------------------------------------- details strip
        GdDetails(
            info = focusInfo,
            now = now,
            use24h = use24h,
            reminderKeys = reminderKeys,
            modifier = Modifier.padding(start = Layout.ContentStart, end = 32.dp),
        )
        Spacer(Modifier.height(8.dp))

        // ---------------------------------------------------------------- category chips
        LazyRow(
            contentPadding = PaddingValues(start = Layout.ContentStart, end = 32.dp, top = 4.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = GD_FAV) {
                Chip("★ Favorites", selectedCat == GD_FAV, onClick = { selectedCat = GD_FAV }, modifier = Modifier.focusRequester(chipFocus))
            }
            item(key = GD_ALL) {
                Chip("All channels", selectedCat == GD_ALL, onClick = { selectedCat = GD_ALL })
            }
            items(categories, key = { "c:" + it.id }) { cat ->
                Chip(cat.name, selectedCat == cat.id, onClick = { selectedCat = cat.id })
            }
        }
        Spacer(Modifier.height(6.dp))

        // ---------------------------------------------------------------- grid
        BoxWithConstraints(Modifier.fillMaxSize().padding(start = Layout.ContentStart, end = 24.dp)) {
            val gridW = maxWidth - GdChannelCol - GdGap
            val minuteDp = gridW / GD_WINDOW_MIN.toFloat()
            val nowVisible = now in viewStart until viewEnd
            val nowX = minuteDp * ((now - viewStart) / 60_000f)

            Column(Modifier.fillMaxSize()) {
                // Time ruler
                Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.width(GdChannelCol + GdGap), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CalendarMonth, contentDescription = null, tint = EzColors.Accent2, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(gdDayLabel(viewStart), style = EzType.Label.copy(color = EzColors.TextPrimary))
                    }
                    Box(Modifier.width(gridW).fillMaxHeight()) {
                        for (i in 0 until GD_WINDOW_MIN / 30) {
                            val x = minuteDp * (30f * i)
                            Box(
                                Modifier.offset(x = x).width(1.dp).height(12.dp).align(Alignment.BottomStart)
                                    .background(EzColors.TextTertiary.copy(alpha = 0.5f)),
                            )
                            Text(
                                Fmt.time(viewStart + i * GD_HALF, use24h),
                                style = EzType.Label,
                                maxLines = 1,
                                modifier = Modifier.offset(x = x + 6.dp).align(Alignment.CenterStart),
                            )
                        }
                        if (nowVisible) {
                            Box(
                                Modifier.offset(x = nowX - 4.dp).size(8.dp).align(Alignment.BottomStart)
                                    .clip(CircleShape).background(EzColors.Live),
                            )
                        }
                    }
                }

                Box(Modifier.fillMaxSize()) {
                    if (data == null) {
                        LoadingView("Loading channels…")
                    } else if (data.list.isEmpty()) {
                        if (data.category == GD_FAV) {
                            EmptyView("No favorite channels yet", "Hold OK on a channel to add it to your favorites, or pick another category above.", Icons.Rounded.StarBorder)
                        } else {
                            EmptyView("No channels here", "Pick another category above.", Icons.Rounded.LiveTv)
                        }
                    } else {
                        key(data.category) {
                            val listState = rememberLazyListState()
                            val list = data.list
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(top = 2.dp, bottom = 24.dp),
                            ) {
                                items(list, key = { it.id }) { c ->
                                    GdRow(
                                        channel = c,
                                        epg = epg,
                                        viewStart = viewStart,
                                        now = now,
                                        minuteDp = minuteDp,
                                        use24h = use24h,
                                        isFav = c.id in favIds,
                                        reminderKeys = reminderKeys,
                                        focusReq = pendingFocus?.takeIf { it.channelId == c.id },
                                        onFocusConsumed = { pendingFocus = null },
                                        onFocusInfo = { p -> focusInfo = GdFocused(c, p) },
                                        onPlayChannel = { Playback.playChannel(ctx, c, list) },
                                        onToggleFav = { gdToggleFavorite(ctx, c) },
                                        onProgramme = { p -> gdSelectProgramme(ctx, c, p, list) },
                                        onEdgeLeft = { all -> onEdgeLeft(c, all) },
                                        onEdgeRight = { all -> onEdgeRight(c, all) },
                                    )
                                }
                            }
                        }
                        // "Now" line across all rows
                        if (nowVisible) {
                            Box(
                                Modifier.offset(x = GdChannelCol + GdGap + nowX - 1.dp).width(2.dp).fillMaxHeight()
                                    .background(EzColors.Live.copy(alpha = 0.85f)),
                            )
                        }
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------------- rows & blocks

@Composable
private fun GdRow(
    channel: Channel,
    epg: EpgData,
    viewStart: Long,
    now: Long,
    minuteDp: Dp,
    use24h: Boolean,
    isFav: Boolean,
    reminderKeys: Set<String>,
    focusReq: GdFocusReq?,
    onFocusConsumed: () -> Unit,
    onFocusInfo: (Programme?) -> Unit,
    onPlayChannel: () -> Unit,
    onToggleFav: () -> Unit,
    onProgramme: (Programme) -> Unit,
    onEdgeLeft: (List<Programme>) -> Boolean,
    onEdgeRight: (List<Programme>) -> Boolean,
) {
    val viewEnd = viewStart + GD_WINDOW
    val all = remember(channel.id, epg) { epg.programmes(channel) }
    val visible = remember(all, viewStart) { all.filter { it.stop > viewStart && it.start < viewEnd } }
    val target: Programme? = if (focusReq != null) {
        visible.firstOrNull { focusReq.time >= it.start && focusReq.time < it.stop }
            ?: visible.firstOrNull { it.start >= focusReq.time }
            ?: visible.lastOrNull()
    } else {
        null
    }

    Row(Modifier.fillMaxWidth().height(GdRowHeight).padding(vertical = 3.dp)) {
        GdChannelCell(
            channel = channel,
            isFav = isFav,
            onClick = onPlayChannel,
            onLongClick = onToggleFav,
            onFocused = { onFocusInfo(epg.now(channel, System.currentTimeMillis())) },
        )
        Spacer(Modifier.width(GdGap))
        Box(Modifier.weight(1f).fillMaxHeight()) {
            if (visible.isEmpty()) {
                GdBlock(
                    title = "No programme information",
                    subtitle = "Press OK to watch ${channel.name}",
                    modifier = Modifier.fillMaxSize(),
                    live = false, past = false, progress = null, reminder = false, catchup = false, compact = false,
                    requestFocus = focusReq != null,
                    onFocusConsumed = onFocusConsumed,
                    onFocused = { onFocusInfo(null) },
                    onClick = onPlayChannel,
                    onLongClick = onToggleFav,
                    onLeftEdge = null,
                    onRightEdge = null,
                )
            } else {
                val lastIndex = visible.lastIndex
                visible.forEachIndexed { i, p ->
                    key(p.start, p.stop) {
                        val s = maxOf(p.start, viewStart)
                        val e = minOf(p.stop, viewEnd)
                        val x = minuteDp * ((s - viewStart) / 60_000f)
                        val w = (minuteDp * ((e - s) / 60_000f)).coerceAtLeast(6.dp)
                        val live = p.isLive(now)
                        val past = p.stop <= now
                        GdBlock(
                            title = p.title.ifBlank { "Untitled" },
                            subtitle = if (p.start < viewStart) "Started ${Fmt.time(p.start, use24h)}" else Fmt.time(p.start, use24h),
                            modifier = Modifier.offset(x = x).width(w).fillMaxHeight(),
                            live = live,
                            past = past,
                            progress = if (live) p.progress(now) else null,
                            reminder = !past && !live && "${channel.id}|${p.start}" in reminderKeys,
                            catchup = past && gdCatchupOk(channel, p, now),
                            compact = w < 70.dp,
                            requestFocus = target != null && target.start == p.start && target.stop == p.stop,
                            onFocusConsumed = onFocusConsumed,
                            onFocused = { onFocusInfo(p) },
                            onClick = { onProgramme(p) },
                            onLongClick = null,
                            onLeftEdge = if (i == 0) ({ onEdgeLeft(all) }) else null,
                            onRightEdge = if (i == lastIndex) ({ onEdgeRight(all) }) else null,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GdChannelCell(
    channel: Channel,
    isFav: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onFocused: () -> Unit,
) {
    FocusSurface(
        onClick = onClick,
        onLongClick = onLongClick,
        onFocus = { if (it) onFocused() },
        modifier = Modifier.width(GdChannelCol).fillMaxHeight(),
        shape = RoundedCornerShape(10.dp),
        focusedScale = 1.02f,
        color = Color.White.copy(alpha = 0.04f),
        focusedColor = EzColors.SurfaceFocus,
        glow = false,
    ) { _ ->
        Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            ChannelLogo(channel.logo, channel.name, size = 34.dp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                if (channel.number > 0) Text(channel.number.toString(), style = EzType.Caption, maxLines = 1)
                Text(
                    channel.name,
                    style = EzType.Title.copy(fontSize = 13.sp, lineHeight = 16.sp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (isFav) {
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Rounded.Star, contentDescription = "Favorite", tint = EzColors.Warning, modifier = Modifier.size(12.dp))
            }
        }
    }
}

@Composable
private fun GdBlock(
    title: String,
    subtitle: String,
    modifier: Modifier,
    live: Boolean,
    past: Boolean,
    progress: Float?,
    reminder: Boolean,
    catchup: Boolean,
    compact: Boolean,
    requestFocus: Boolean,
    onFocusConsumed: () -> Unit,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    onLeftEdge: (() -> Boolean)?,
    onRightEdge: (() -> Boolean)?,
) {
    val fr = remember { FocusRequester() }
    if (requestFocus) {
        LaunchedEffect(Unit) {
            delay(16)
            runCatching { fr.requestFocus() }
            onFocusConsumed()
        }
    }
    val base = when {
        live -> Color(0xFF2B2452)
        past -> Color.White.copy(alpha = 0.035f)
        else -> Color.White.copy(alpha = 0.075f)
    }
    FocusSurface(
        onClick = onClick,
        onLongClick = onLongClick,
        onFocus = { if (it) onFocused() },
        modifier = modifier
            .padding(end = 3.dp)
            .focusRequester(fr)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) {
                    false
                } else if (e.composeKey == Key.DirectionRight && onRightEdge != null) {
                    onRightEdge()
                } else if (e.composeKey == Key.DirectionLeft && onLeftEdge != null) {
                    onLeftEdge()
                } else {
                    false
                }
            },
        shape = RoundedCornerShape(8.dp),
        focusedScale = 1.02f,
        color = base,
        focusedColor = Color.White,
        showBorder = false,
        glow = false,
    ) { focused ->
        val fg = when {
            focused -> GdDark
            past -> EzColors.TextTertiary
            else -> EzColors.TextPrimary
        }
        val fg2 = if (focused) GdDark.copy(alpha = 0.65f) else EzColors.TextTertiary
        if (live && !focused) {
            Box(Modifier.fillMaxHeight().width(3.dp).background(EzColors.AccentGradient))
        }
        Column(
            Modifier.fillMaxSize().padding(horizontal = if (compact) 6.dp else 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = EzType.Title.copy(color = fg, fontSize = 13.sp, lineHeight = 17.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (reminder) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Rounded.NotificationsActive, contentDescription = "Reminder set",
                        tint = if (focused) GdDark else EzColors.Warning, modifier = Modifier.size(13.dp),
                    )
                }
                if (catchup && !compact) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Rounded.History, contentDescription = "Catch-up",
                        tint = if (focused) GdDark else EzColors.Accent2, modifier = Modifier.size(13.dp),
                    )
                }
            }
            if (!compact) {
                Text(subtitle, style = EzType.Caption.copy(color = fg2), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (progress != null) {
            ProgressLine(
                progress,
                Modifier.align(Alignment.BottomCenter).padding(horizontal = 8.dp, vertical = 4.dp),
                height = 2.dp,
                track = if (focused) GdDark.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.15f),
            )
        }
    }
}

// -------------------------------------------------------------------- header pieces

@Composable
private fun GdEpgStatus(state: EpgState) {
    when (state) {
        is EpgState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(color = EzColors.Accent2, strokeWidth = 1.5.dp, modifier = Modifier.size(11.dp))
            Spacer(Modifier.width(6.dp))
            Text("Updating guide…", style = EzType.Caption)
        }
        is EpgState.Failed -> Text(state.message, style = EzType.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        else -> {}
    }
}

@Composable
private fun GdDetails(
    info: GdFocused?,
    now: Long,
    use24h: Boolean,
    reminderKeys: Set<String>,
    modifier: Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(88.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.horizontalGradient(listOf(EzColors.SurfaceHi, EzColors.Bg2)))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (info == null) {
            Icon(Icons.Rounded.CalendarMonth, contentDescription = null, tint = EzColors.Accent2, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(14.dp))
            Column {
                Text("What's on", style = EzType.H3)
                Text(
                    "OK on a live show to watch · on a past show to catch up · on a future show to set a reminder",
                    style = EzType.BodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            val c = info.channel
            val p = info.programme
            ChannelLogo(c.logo, c.name, size = 44.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        p?.title?.ifBlank { null } ?: c.name,
                        style = EzType.H3,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (p != null) {
                        Spacer(Modifier.width(10.dp))
                        if (p.isLive(now)) {
                            LiveBadge()
                        } else if (p.stop <= now && gdCatchupOk(c, p, now)) {
                            Badge("CATCH-UP", color = EzColors.Accent.copy(alpha = 0.85f))
                        } else if (p.start > now && "${c.id}|${p.start}" in reminderKeys) {
                            Badge("REMINDER SET", color = EzColors.Warning.copy(alpha = 0.85f))
                        }
                    }
                }
                val meta = if (p == null) {
                    "${c.name}  ·  No programme information"
                } else {
                    val times = "${gdDayLabel(p.start)} ${Fmt.time(p.start, use24h)} – ${Fmt.time(p.stop, use24h)}"
                    val status = when {
                        p.isLive(now) -> Fmt.minutesLeft(p.stop, now)
                        p.start > now -> "Starts in ${Fmt.duration(p.start - now)}"
                        else -> "Ended"
                    }
                    "${c.name}  ·  $times  ·  $status"
                }
                Text(meta, style = EzType.BodySmall.copy(color = EzColors.TextSecondary, fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (p != null && p.desc.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(p.desc, style = EzType.Caption, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
