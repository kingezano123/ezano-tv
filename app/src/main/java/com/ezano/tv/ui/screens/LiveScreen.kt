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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key as composeKey
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ezano.tv.data.Channel
import com.ezano.tv.data.ContentType
import com.ezano.tv.data.EpgData
import com.ezano.tv.data.EpgState
import com.ezano.tv.data.LibraryIndex
import com.ezano.tv.data.LoadState
import com.ezano.tv.data.Programme
import com.ezano.tv.data.Repo
import com.ezano.tv.player.Playback
import com.ezano.tv.ui.Layout
import com.ezano.tv.ui.LocalNavigator
import com.ezano.tv.ui.Screen
import com.ezano.tv.ui.components.Badge
import com.ezano.tv.ui.components.ChannelLogo
import com.ezano.tv.ui.components.EmptyView
import com.ezano.tv.ui.components.EzTextField
import com.ezano.tv.ui.components.FocusSurface
import com.ezano.tv.ui.components.Fmt
import com.ezano.tv.ui.components.LiveBadge
import com.ezano.tv.ui.components.LoadingView
import com.ezano.tv.ui.components.PillButton
import com.ezano.tv.ui.components.ProgressLine
import com.ezano.tv.ui.theme.EzColors
import com.ezano.tv.ui.theme.EzType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val LV_FAV = "__fav"
private const val LV_ALL = "__all"
private val LvDark = Color(0xFF0B0B10)

/** Remembers the last browsed category/channel across visits to the Live tab (and returns from the player). */
private object LvMemory {
    var category: String? = null
    var channelId: String? = null
}

/** Channels loaded for a category (the category travels with the list so the UI knows what it shows). */
private class LvChannels(val category: String, val list: List<Channel>)

private fun lvToast(ctx: Context, msg: String) {
    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
}

private fun lvToggleFavorite(ctx: Context, c: Channel) {
    val added = Repo.store.toggleFavorite("live:${c.id}")
    lvToast(ctx, if (added) "Added “${c.name}” to Favorites" else "Removed “${c.name}” from Favorites")
}

/** Premium 3-pane live TV browser: categories · channels · preview. */
@Composable
fun LiveScreen() {
    val idx by Repo.index.collectAsState()
    val loadState by Repo.loadState.collectAsState()
    val index = idx
    Box(Modifier.fillMaxSize().background(EzColors.Bg)) {
        if (index == null) {
            val ls = loadState
            if (ls is LoadState.Error) {
                EmptyView("Couldn't load your channels", ls.message, Icons.Rounded.LiveTv)
            } else {
                LoadingView((ls as? LoadState.Loading)?.message ?: "Loading channels…")
            }
        } else if (index.lib.channels.isEmpty()) {
            EmptyView("No live channels", "This playlist doesn't include any live TV channels.", Icons.Rounded.LiveTv)
        } else {
            LvContent(index)
        }
    }
}

@Composable
private fun LvContent(index: LibraryIndex) {
    val ctx = LocalContext.current
    val nav = LocalNavigator.current
    val epg by Repo.epg.collectAsState()
    val epgState by Repo.epgState.collectAsState()
    val tick by Repo.clock.collectAsState()
    val favs by Repo.store.favorites.collectAsState()
    val settings by Repo.store.settings.collectAsState()
    val now = remember(tick) { System.currentTimeMillis() }
    val use24h = settings.use24h

    val categories = remember(index, settings) { Repo.visibleCategories(ContentType.LIVE).distinctBy { it.id } }
    val favIds = remember(index, favs) {
        Repo.store.favoriteKeys().filter { it.startsWith("live:") }.mapTo(HashSet()) { it.removePrefix("live:") }
    }
    val favCount = remember(index, favIds) { favIds.count { index.channelById.containsKey(it) } }
    val allCount = remember(index, categories) {
        categories.sumOf { index.channelsByCat[it.id]?.size ?: 0 } + (index.channelsByCat[""]?.size ?: 0)
    }

    val initialCat = remember {
        val mem = LvMemory.category
        if (mem != null && (mem == LV_FAV || mem == LV_ALL || categories.any { it.id == mem })) mem
        else if (favCount > 0) LV_FAV
        else categories.firstOrNull()?.id ?: LV_ALL
    }
    var selectedCat by remember { mutableStateOf(initialCat) }
    var focusedCat by remember { mutableStateOf(initialCat) }
    // Focusing a category loads it after a short pause, so fast D-pad scrolling stays smooth.
    LaunchedEffect(focusedCat) {
        if (focusedCat != selectedCat) {
            delay(250)
            selectedCat = focusedCat
        }
    }
    LaunchedEffect(selectedCat) { LvMemory.category = selectedCat }

    val favKey: Any? = if (selectedCat == LV_FAV) favIds else null
    val loaded by produceState<LvChannels?>(null, selectedCat, index, favKey, settings) {
        val cat = selectedCat
        val list = withContext(Dispatchers.Default) {
            val raw = when (cat) {
                LV_FAV -> Repo.favoriteChannels()
                LV_ALL -> Repo.channels(null)
                else -> Repo.channels(cat)
            }
            raw.distinctBy { it.id }
        }
        value = LvChannels(cat, list)
    }

    var focusedChannel by remember { mutableStateOf(LvMemory.channelId?.let { Repo.channel(it) }) }
    var eventEditing by remember { mutableStateOf(false) }
    var eventQuery by remember { mutableStateOf("") }

    val channelFocus = remember { FocusRequester() }
    val catFocus = remember { FocusRequester() }
    val pillFocus = remember { FocusRequester() }
    var initialFocusDone by remember { mutableStateOf(false) }
    val restoreId = remember { LvMemory.channelId }

    val data = loaded
    LaunchedEffect(data) {
        if (data != null && !initialFocusDone) {
            delay(80)
            val ok = data.list.isNotEmpty() && runCatching { channelFocus.requestFocus() }.isSuccess
            if (!ok) runCatching { pillFocus.requestFocus() }
            initialFocusDone = true
        }
    }

    val submitEvent: () -> Unit = {
        val q = eventQuery.trim()
        if (q.isNotEmpty()) {
            Repo.store.addRecentSearch(q)
            nav.navigate(Screen.EventSearch(q))
        }
    }

    Column(Modifier.fillMaxSize().padding(top = 22.dp)) {
        // ---------------------------------------------------------------- top bar
        Row(
            Modifier.fillMaxWidth().padding(start = Layout.ContentStart, end = 32.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("Live TV", style = EzType.H1)
                LvEpgStatus(epgState)
            }
            Spacer(Modifier.width(24.dp))
            Box(Modifier.weight(1f)) {
                if (!eventEditing) {
                    LvFindEventPill(onClick = { eventEditing = true }, modifier = Modifier.focusRequester(pillFocus))
                } else {
                    EzTextField(
                        value = eventQuery,
                        onValueChange = { eventQuery = it },
                        label = "",
                        placeholder = "Find a game, match or show… e.g. Lakers vs Celtics",
                        leadingIcon = Icons.Rounded.Search,
                        imeAction = ImeAction.Search,
                        onSubmit = submitEvent,
                        autoEdit = true,
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            PillButton("TV Guide", onClick = { nav.navigate(Screen.Guide) }, icon = Icons.Rounded.CalendarMonth)
        }

        Spacer(Modifier.height(16.dp))

        // ---------------------------------------------------------------- 3 panes
        Row(Modifier.fillMaxSize().padding(start = Layout.ContentStart - 6.dp, end = 24.dp, bottom = 14.dp)) {
            // Left: categories
            val catInitialIndex = remember {
                when (initialCat) {
                    LV_FAV -> 0
                    LV_ALL -> 1
                    else -> (categories.indexOfFirst { it.id == initialCat } + 2).coerceAtLeast(0)
                }
            }
            val catState = rememberLazyListState((catInitialIndex - 3).coerceAtLeast(0))
            LazyColumn(
                state = catState,
                modifier = Modifier.width(196.dp).fillMaxHeight(),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                item(key = LV_FAV) {
                    LvCategoryItem(
                        name = "★ Favorites", count = favCount, selected = selectedCat == LV_FAV,
                        modifier = if (selectedCat == LV_FAV) Modifier.focusRequester(catFocus) else Modifier,
                        onClick = { focusedCat = LV_FAV; selectedCat = LV_FAV },
                        onFocused = { focusedCat = LV_FAV },
                    )
                }
                item(key = LV_ALL) {
                    LvCategoryItem(
                        name = "All channels", count = allCount, selected = selectedCat == LV_ALL,
                        modifier = if (selectedCat == LV_ALL) Modifier.focusRequester(catFocus) else Modifier,
                        onClick = { focusedCat = LV_ALL; selectedCat = LV_ALL },
                        onFocused = { focusedCat = LV_ALL },
                    )
                }
                items(categories, key = { "c:" + it.id }) { cat ->
                    LvCategoryItem(
                        name = cat.name, count = index.channelsByCat[cat.id]?.size ?: 0, selected = selectedCat == cat.id,
                        modifier = if (selectedCat == cat.id) Modifier.focusRequester(catFocus) else Modifier,
                        onClick = { focusedCat = cat.id; selectedCat = cat.id },
                        onFocused = { focusedCat = cat.id },
                    )
                }
            }

            Spacer(Modifier.width(10.dp))

            // Middle: channels. D-pad Left returns to the *selected* category (not merely the nearest one).
            Box(
                Modifier.weight(1f).fillMaxHeight().onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.composeKey == Key.DirectionLeft) {
                        runCatching { catFocus.requestFocus() }.isSuccess
                    } else {
                        false
                    }
                },
            ) {
                if (data == null) {
                    LoadingView("Loading channels…")
                } else if (data.list.isEmpty()) {
                    if (data.category == LV_FAV) {
                        EmptyView("No favorites yet", "Hold OK on any channel to add it to your favorites.", Icons.Rounded.StarBorder)
                    } else {
                        EmptyView("No channels here", "This category is empty.", Icons.Rounded.LiveTv)
                    }
                } else {
                    key(data.category) {
                        val startIndex = if (!initialFocusDone) data.list.indexOfFirst { it.id == restoreId }.coerceAtLeast(0) else 0
                        val focusId = if (!initialFocusDone) data.list.getOrNull(startIndex)?.id else null
                        val listState = rememberLazyListState((startIndex - 2).coerceAtLeast(0))
                        val list = data.list
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            items(list, key = { it.id }) { c ->
                                LvChannelRow(
                                    c = c, epg = epg, now = now, isFav = c.id in favIds,
                                    modifier = if (c.id == focusId) Modifier.focusRequester(channelFocus) else Modifier,
                                    onClick = { Playback.playChannel(ctx, c, list) },
                                    onLongClick = { lvToggleFavorite(ctx, c) },
                                    onFocused = {
                                        focusedChannel = c
                                        LvMemory.channelId = c.id
                                    },
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.width(12.dp))

            // Right: preview
            val preview = focusedChannel ?: data?.list?.firstOrNull()
            LvPreview(
                channel = preview,
                epg = epg,
                now = now,
                use24h = use24h,
                isFav = preview != null && preview.id in favIds,
                onWatch = {
                    if (preview != null) {
                        val zap = data?.list?.takeIf { l -> l.any { it.id == preview.id } } ?: Repo.channels(preview.categoryId)
                        Playback.playChannel(ctx, preview, zap)
                    }
                },
                onFavorite = { if (preview != null) lvToggleFavorite(ctx, preview) },
                modifier = Modifier.width(300.dp).fillMaxHeight().padding(vertical = 6.dp),
            )
        }
    }
}

// -------------------------------------------------------------------- pieces

@Composable
private fun LvEpgStatus(state: EpgState) {
    val text: String? = when (state) {
        is EpgState.Loading -> "Updating guide…"
        is EpgState.Failed -> state.message
        else -> null
    }
    if (text != null) {
        Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state is EpgState.Loading) {
                CircularProgressIndicator(color = EzColors.Accent2, strokeWidth = 1.5.dp, modifier = Modifier.size(10.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(text, style = EzType.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
        }
    }
}

@Composable
private fun LvFindEventPill(onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(50),
        focusedScale = 1.03f,
        color = Color.White.copy(alpha = 0.07f),
        focusedColor = Color.White.copy(alpha = 0.16f),
    ) { _ ->
        Row(Modifier.fillMaxSize().padding(horizontal = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(EzColors.AccentGradient),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Search, contentDescription = null, tint = Color.White, modifier = Modifier.size(19.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Find an event", style = EzType.Title.copy(fontWeight = FontWeight.Bold), maxLines = 1)
                Text(
                    "Find a game, match or show… e.g. Lakers vs Celtics",
                    style = EzType.Caption,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun LvCategoryItem(
    name: String,
    count: Int,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    onFocused: () -> Unit,
) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(46.dp),
        shape = RoundedCornerShape(12.dp),
        focusedScale = 1.03f,
        color = if (selected) Color.White.copy(alpha = 0.10f) else Color.Transparent,
        focusedColor = Color.White,
        showBorder = false,
        glow = false,
        onFocus = { if (it) onFocused() },
    ) { focused ->
        Row(Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selected) {
                Box(Modifier.width(3.dp).height(18.dp).clip(RoundedCornerShape(50)).background(EzColors.AccentGradient))
                Spacer(Modifier.width(9.dp))
            }
            Text(
                name,
                style = EzType.Title.copy(
                    fontSize = 14.sp,
                    color = if (focused) LvDark else if (selected) EzColors.TextPrimary else EzColors.TextSecondary,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                count.toString(),
                style = EzType.Caption.copy(color = if (focused) LvDark.copy(alpha = 0.6f) else EzColors.TextTertiary),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun LvChannelRow(
    c: Channel,
    epg: EpgData,
    now: Long,
    isFav: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onFocused: () -> Unit,
) {
    // Looked up lazily, only for rows that are actually composed (visible).
    val prog = remember(c.id, epg, now) { epg.now(c, now) }
    FocusSurface(
        onClick = onClick,
        onLongClick = onLongClick,
        onFocus = { if (it) onFocused() },
        modifier = modifier.fillMaxWidth().height(66.dp),
        shape = RoundedCornerShape(12.dp),
        focusedScale = 1.03f,
        color = Color.Transparent,
        focusedColor = EzColors.SurfaceFocus,
        glow = false,
    ) { focused ->
        Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (c.number > 0) c.number.toString() else "",
                style = EzType.Label.copy(color = if (focused) EzColors.TextPrimary else EzColors.TextTertiary),
                maxLines = 1,
                modifier = Modifier.width(34.dp),
            )
            ChannelLogo(c.logo, c.name, size = 38.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        c.name,
                        style = EzType.Title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (isFav) {
                        Spacer(Modifier.width(5.dp))
                        Icon(Icons.Rounded.Star, contentDescription = "Favorite", tint = EzColors.Warning, modifier = Modifier.size(14.dp))
                    }
                }
                if (prog != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            prog.title,
                            style = EzType.BodySmall.copy(color = if (focused) EzColors.TextPrimary else EzColors.TextSecondary),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(Fmt.minutesLeft(prog.stop, now), style = EzType.Caption, maxLines = 1)
                    }
                    Spacer(Modifier.height(4.dp))
                    ProgressLine(prog.progress(now), height = 2.dp)
                } else {
                    Text("No programme information", style = EzType.Caption, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun LvPreview(
    channel: Channel?,
    epg: EpgData,
    now: Long,
    use24h: Boolean,
    isFav: Boolean,
    onWatch: () -> Unit,
    onFavorite: () -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.verticalGradient(listOf(EzColors.SurfaceHi, EzColors.Surface, EzColors.Bg2)))
            .padding(16.dp),
    ) {
        if (channel == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Pick a channel to see what's on", style = EzType.Body)
            }
        } else {
            val quick = remember(channel.id, epg) { epg.programmes(channel) }
            // When the XMLTV guide has nothing for this channel, fall back to the provider's short EPG.
            val fetched by produceState<Pair<String, List<Programme>>?>(null, channel.id, quick.isEmpty()) {
                if (quick.isEmpty()) {
                    delay(350)
                    val list = withContext(Dispatchers.IO) {
                        runCatching { Repo.programmesFor(channel) }.getOrDefault(emptyList())
                    }
                    value = channel.id to list
                }
            }
            val progs: List<Programme> = if (quick.isNotEmpty()) quick else fetched?.takeIf { it.first == channel.id }?.second ?: emptyList()
            val nowP = remember(progs, now) { EpgData.findAt(progs, now) }
            val nextP = remember(progs, now, nowP) { progs.firstOrNull { it.start >= (nowP?.stop ?: now) } }

            Column(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ChannelLogo(channel.logo, channel.name, size = 58.dp)
                    Spacer(Modifier.weight(1f))
                    if (nowP != null) LiveBadge()
                }
                Spacer(Modifier.height(12.dp))
                Text(channel.name, style = EzType.H3, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (channel.number > 0) {
                        Text("Ch ${channel.number}", style = EzType.Caption)
                        Spacer(Modifier.width(8.dp))
                    }
                    if (channel.catchupDays > 0) {
                        Icon(Icons.Rounded.History, contentDescription = null, tint = EzColors.Accent2, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(3.dp))
                        Badge("CATCH-UP ${channel.catchupDays}D", color = EzColors.Accent.copy(alpha = 0.75f))
                    }
                }
                Spacer(Modifier.height(14.dp))
                if (nowP != null) {
                    Text("NOW", style = EzType.Label.copy(color = EzColors.Live, fontWeight = FontWeight.ExtraBold))
                    Spacer(Modifier.height(2.dp))
                    Text(nowP.title.ifBlank { "Untitled" }, style = EzType.Title.copy(fontSize = 16.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${Fmt.time(nowP.start, use24h)} – ${Fmt.time(nowP.stop, use24h)}  ·  ${Fmt.minutesLeft(nowP.stop, now)}",
                        style = EzType.Caption,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(6.dp))
                    ProgressLine(nowP.progress(now), height = 3.dp)
                    if (nowP.desc.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(nowP.desc, style = EzType.BodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                } else {
                    Text("No programme information", style = EzType.Title.copy(color = EzColors.TextSecondary))
                    Text("Press OK to watch this channel live.", style = EzType.Caption)
                }
                if (nextP != null) {
                    Spacer(Modifier.height(12.dp))
                    Text("NEXT", style = EzType.Label.copy(color = EzColors.TextTertiary, fontWeight = FontWeight.ExtraBold))
                    Spacer(Modifier.height(2.dp))
                    Text(nextP.title.ifBlank { "Untitled" }, style = EzType.Title.copy(color = EzColors.TextSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${Fmt.time(nextP.start, use24h)} – ${Fmt.time(nextP.stop, use24h)}",
                        style = EzType.Caption,
                        maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                PillButton("Watch", onClick = onWatch, icon = Icons.Rounded.PlayArrow, primary = true, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(10.dp))
                PillButton(
                    if (isFav) "Saved" else "Favorite",
                    onClick = onFavorite,
                    icon = if (isFav) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                )
            }
        }
    }
}
