package com.ezano.tv.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import com.ezano.tv.data.Channel
import com.ezano.tv.data.ContentType
import com.ezano.tv.data.Episode
import com.ezano.tv.data.Programme
import com.ezano.tv.data.Repo
import com.ezano.tv.data.Series
import com.ezano.tv.ui.components.AsyncImg
import com.ezano.tv.ui.components.Badge
import com.ezano.tv.ui.components.ChannelLogo
import com.ezano.tv.ui.components.FocusSurface
import com.ezano.tv.ui.components.Fmt
import com.ezano.tv.ui.components.LiveBadge
import com.ezano.tv.ui.components.ProgressLine
import com.ezano.tv.ui.theme.EzColors
import com.ezano.tv.ui.theme.EzType
import kotlinx.coroutines.delay
import kotlin.math.abs

/** Hosts the side panels: channel list on the left, everything else on the right. */
@Composable
internal fun BoxScope.PanelHost(c: PlayerController) {
    val panel = c.panel
    val leftOpen = panel == Panel.Channels
    val rightPanel = panel?.takeIf { it != Panel.Channels }
    val lastRight = rememberLast(rightPanel)

    AnimatedVisibility(visible = panel != null, enter = fadeIn(), exit = fadeOut()) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
    }
    AnimatedVisibility(
        visible = leftOpen,
        modifier = Modifier.align(Alignment.CenterStart),
        enter = slideInHorizontally(initialOffsetX = { -it }) + fadeIn(),
        exit = slideOutHorizontally(targetOffsetX = { -it }) + fadeOut(),
    ) {
        ChannelsPanel(c)
    }
    AnimatedVisibility(
        visible = rightPanel != null,
        modifier = Modifier.align(Alignment.CenterEnd),
        enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
        exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
    ) {
        when (lastRight) {
            Panel.AudioSubs -> AudioSubsPanel(c)
            Panel.Speed -> SpeedPanel(c)
            Panel.Aspect -> AspectPanel(c)
            Panel.Episodes -> EpisodesPanel(c)
            Panel.Programmes -> ProgrammesPanel(c)
            else -> Unit
        }
    }
}

// ====================================================================== building blocks

@Composable
internal fun GlassPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier
            .fillMaxHeight()
            .padding(16.dp)
            .shadow(30.dp, shape)
            .clip(shape)
            .background(GlassBrush)
            .border(1.dp, Color.White.copy(alpha = 0.08f), shape)
            .padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 12.dp),
        content = content,
    )
}

@Composable
private fun PanelHeader(title: String, subtitle: String? = null) {
    Column(Modifier.padding(start = 4.dp, bottom = 14.dp)) {
        Text(title, style = EzType.H2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!subtitle.isNullOrBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = EzType.BodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = EzType.Label.copy(color = EzColors.TextTertiary, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp),
        modifier = Modifier.padding(start = 6.dp, bottom = 8.dp),
    )
}

@Composable
private fun EmptyNote(text: String) {
    Text(text, style = EzType.BodySmall.copy(color = EzColors.TextTertiary), modifier = Modifier.padding(6.dp))
}

/** Row with a check mark for the selected option. White when focused. */
@Composable
private fun OptionRow(
    title: String,
    detail: String?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(if (detail.isNullOrBlank()) 48.dp else 58.dp),
        shape = RoundedCornerShape(12.dp),
        focusedScale = 1.02f,
        color = if (selected) Color.White.copy(alpha = 0.08f) else Color.Transparent,
        focusedColor = Color.White,
        showBorder = false,
        glow = false,
    ) { focused ->
        val fg = if (focused) Ink else EzColors.TextPrimary
        val sub = if (focused) Ink.copy(alpha = 0.65f) else EzColors.TextTertiary
        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                if (selected) {
                    Icon(Icons.Rounded.Check, contentDescription = null, tint = if (focused) Ink else EzColors.Accent2, modifier = Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = EzType.Title.copy(color = fg), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!detail.isNullOrBlank()) {
                    Text(detail, style = EzType.Caption.copy(color = sub), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// ====================================================================== audio & subtitles

@Composable
private fun AudioSubsPanel(c: PlayerController) {
    val tracks = c.tracks
    val audio = remember(tracks) { TrackOptions.audio(tracks) }
    val text = remember(tracks) { TrackOptions.text(tracks) }
    val focus = remember { FocusRequester() }
    // Initial focus: the selected audio track, else the selected subtitle option.
    val audioFocusIdx = if (audio.isEmpty()) -1 else audio.indexOfFirst { it.selected }.coerceAtLeast(0)
    val textFocusIdx = if (audioFocusIdx >= 0) -1 else text.indexOfFirst { it.selected }.coerceAtLeast(0)
    AutoFocus(focus)

    GlassPanel(Modifier.width(620.dp)) {
        PanelHeader("Audio & Subtitles")
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f)) {
                SectionLabel("Audio")
                if (audio.isEmpty()) {
                    EmptyNote(if (c.player == null || tracks.isEmpty()) "Loading tracks…" else "Only one audio track")
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                        itemsIndexed(audio, key = { i, _ -> "a$i" }) { i, opt ->
                            OptionRow(
                                opt.title, opt.detail, opt.selected,
                                onClick = { c.selectTrack(C.TRACK_TYPE_AUDIO, opt) },
                                modifier = if (i == audioFocusIdx) Modifier.focusRequester(focus) else Modifier,
                            )
                        }
                    }
                }
            }
            Column(Modifier.weight(1f)) {
                SectionLabel("Subtitles")
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                    itemsIndexed(text, key = { i, _ -> "t$i" }) { i, opt ->
                        OptionRow(
                            opt.title, opt.detail, opt.selected,
                            onClick = { c.selectTrack(C.TRACK_TYPE_TEXT, opt) },
                            modifier = if (i == textFocusIdx) Modifier.focusRequester(focus) else Modifier,
                        )
                    }
                }
                if (text.size <= 1) EmptyNote("No subtitles in this stream")
            }
        }
    }
}

// ====================================================================== speed & aspect

private val Speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

@Composable
private fun SpeedPanel(c: PlayerController) {
    val focus = remember { FocusRequester() }
    val selectedIdx = Speeds.indexOfFirst { abs(it - c.speed) < 0.01f }.let { if (it < 0) 2 else it }
    AutoFocus(focus)
    GlassPanel(Modifier.width(340.dp)) {
        PanelHeader("Playback speed")
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Speeds.forEachIndexed { i, s ->
                OptionRow(
                    title = if (s == 1f) "1× (Normal)" else "${if (s % 1f == 0f) s.toInt().toString() else s.toString()}×",
                    detail = null,
                    selected = i == selectedIdx,
                    onClick = { c.setPlaybackSpeed(s) },
                    modifier = if (i == selectedIdx) Modifier.focusRequester(focus) else Modifier,
                )
            }
        }
    }
}

private data class AspectOption(val mode: Int, val title: String, val detail: String)

private val AspectOptions = listOf(
    AspectOption(0, "Fit", "Show the whole picture"),
    AspectOption(1, "Fill", "Stretch to fill the screen"),
    AspectOption(2, "Zoom", "Crop to fill the screen"),
)

@Composable
private fun AspectPanel(c: PlayerController) {
    val focus = remember { FocusRequester() }
    AutoFocus(focus)
    GlassPanel(Modifier.width(340.dp)) {
        PanelHeader("Aspect ratio")
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AspectOptions.forEach { o ->
                val selected = c.aspect == o.mode
                OptionRow(
                    title = o.title,
                    detail = o.detail,
                    selected = selected,
                    onClick = { c.setAspectMode(o.mode) },
                    modifier = if (selected) Modifier.focusRequester(focus) else Modifier,
                )
            }
        }
    }
}

// ====================================================================== episodes

@Composable
private fun EpisodesPanel(c: PlayerController) {
    val ep = c.item as? PlayItem.Ep ?: return
    val list = ep.playlist
    val currentIdx = list.indexOfFirst { it.id == ep.episode.id }.coerceAtLeast(0)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (currentIdx - 1).coerceAtLeast(0))
    val focus = remember { FocusRequester() }
    AutoFocus(focus)
    val history by Repo.store.history.collectAsState()
    val progress = remember(history) { Repo.store.historyForActive().associate { it.key to it.progress } }
    val seasons = remember(list) { list.map { it.season }.distinct().size }

    GlassPanel(Modifier.width(560.dp)) {
        PanelHeader(ep.series.name, if (seasons > 1) "${list.size} episodes · $seasons seasons" else "${list.size} episodes")
        LazyColumn(
            state = state,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 6.dp, horizontal = 4.dp),
        ) {
            itemsIndexed(list, key = { i, e -> "$i:${e.id}" }) { i, e ->
                EpisodeRow(
                    e = e,
                    series = ep.series,
                    current = i == currentIdx,
                    progress = progress["ep:${ep.series.id}:${e.id}"] ?: 0f,
                    onClick = { c.playEpisode(e) },
                    modifier = if (i == currentIdx) Modifier.focusRequester(focus) else Modifier,
                )
            }
        }
    }
}

@Composable
private fun EpisodeRow(
    e: Episode,
    series: Series,
    current: Boolean,
    progress: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(88.dp),
        shape = RoundedCornerShape(14.dp),
        focusedScale = 1.03f,
        color = if (current) EzColors.Accent.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.04f),
        focusedColor = EzColors.SurfaceFocus,
    ) { _ ->
        Row(Modifier.fillMaxSize().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.width(128.dp).fillMaxHeight().clip(RoundedCornerShape(10.dp)).background(EzColors.SurfaceHi),
            ) {
                AsyncImg(e.image.ifBlank { series.poster }, Modifier.fillMaxSize())
                if (progress > 0f && !current) {
                    ProgressLine(progress, Modifier.align(Alignment.BottomCenter).padding(6.dp), height = 3.dp)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (e.season > 0) "S${e.season}  E${e.episode}" else "E${e.episode}",
                        style = EzType.Label.copy(color = EzColors.Accent2, fontWeight = FontWeight.ExtraBold),
                    )
                    if (e.durationSecs > 0) {
                        Spacer(Modifier.width(8.dp))
                        Text(Fmt.duration(e.durationSecs * 1000L), style = EzType.Caption)
                    }
                    if (current) {
                        Spacer(Modifier.width(8.dp))
                        Badge("NOW PLAYING", color = EzColors.Accent.copy(alpha = 0.85f))
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(e.title.ifBlank { "Episode ${e.episode}" }, style = EzType.Title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (e.plot.isNotBlank()) {
                    Text(e.plot, style = EzType.Caption, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// ====================================================================== live: channel list

@Composable
private fun ChannelsPanel(c: PlayerController) {
    val live = c.item as? PlayItem.Live ?: return
    val list = live.zapList
    val currentIdx = list.indexOfFirst { it.id == live.channel.id }.coerceAtLeast(0)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (currentIdx - 3).coerceAtLeast(0))
    val focus = remember { FocusRequester() }
    AutoFocus(focus)
    val epg by Repo.epg.collectAsState()
    val now = rememberNow(30_000L)
    val categoryName = remember(live.channel.categoryId) { Repo.category(ContentType.LIVE, live.channel.categoryId)?.name }

    GlassPanel(Modifier.width(420.dp)) {
        PanelHeader(categoryName ?: "Channels", "${list.size} channels")
        LazyColumn(
            state = state,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(vertical = 4.dp, horizontal = 2.dp),
        ) {
            itemsIndexed(list, key = { i, ch -> "$i:${ch.id}" }) { i, ch ->
                ChannelRow(
                    ch = ch,
                    programme = epg.now(ch, now),
                    now = now,
                    current = i == currentIdx,
                    onClick = { c.pickChannel(ch, list) },
                    modifier = if (i == currentIdx) Modifier.focusRequester(focus) else Modifier,
                )
            }
        }
    }
}

@Composable
private fun ChannelRow(
    ch: Channel,
    programme: Programme?,
    now: Long,
    current: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(66.dp),
        shape = RoundedCornerShape(12.dp),
        focusedScale = 1.03f,
        color = if (current) EzColors.Accent.copy(alpha = 0.16f) else Color.Transparent,
        focusedColor = EzColors.SurfaceFocus,
        glow = false,
    ) { _ ->
        Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (ch.number > 0) ch.number.toString() else "",
                style = EzType.Label.copy(color = if (current) EzColors.Accent2 else EzColors.TextTertiary, fontWeight = FontWeight.Bold),
                maxLines = 1,
                modifier = Modifier.width(38.dp),
            )
            ChannelLogo(ch.logo, ch.name, size = 36.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        ch.name,
                        style = EzType.Title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (current) {
                        Spacer(Modifier.width(6.dp))
                        Box(Modifier.size(7.dp).clip(CircleShape).background(EzColors.Live))
                    }
                }
                if (programme != null) {
                    Text(programme.title, style = EzType.Caption.copy(color = EzColors.TextSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    ProgressLine(programme.progress(now), height = 2.dp)
                }
            }
        }
    }
}

// ====================================================================== live/catch-up: programme guide

@Composable
private fun ProgrammesPanel(c: PlayerController) {
    val ch = c.currentChannel ?: return
    val epg by Repo.epg.collectAsState()
    val loaded by produceState<List<Programme>?>(null, ch.id, epg) { value = loadProgrammes(ch) }
    val now = rememberNow(30_000L)
    val playing = (c.item as? PlayItem.Catchup)?.programme
    val visible = remember(loaded, ch.id) {
        val t = System.currentTimeMillis()
        val back = if (ch.catchupDays > 0) ch.catchupDays * 86_400_000L else 3 * 3_600_000L
        loaded.orEmpty().filter { it.stop > t - back && it.start < t + 24 * 3_600_000L }
    }
    val target = remember(visible, playing) {
        val idx = if (playing != null) visible.indexOfFirst { it.start == playing.start } else -1
        if (idx >= 0) idx else visible.indexOfFirst { it.isLive(System.currentTimeMillis()) }.let { if (it < 0) 0 else it }
    }
    val state = rememberLazyListState()
    val focus = remember { FocusRequester() }
    LaunchedEffect(visible) {
        if (visible.isEmpty()) return@LaunchedEffect
        runCatching { state.scrollToItem((target - 2).coerceAtLeast(0)) }
        delay(80)
        runCatching { focus.requestFocus() }
    }

    GlassPanel(Modifier.width(480.dp)) {
        PanelHeader(
            ch.name,
            if (ch.catchupDays > 0) "Guide · catch-up for ${ch.catchupDays} day${if (ch.catchupDays == 1) "" else "s"}" else "Guide",
        )
        when {
            loaded == null -> EmptyNote("Loading guide…")
            visible.isEmpty() -> EmptyNote("No programme information for this channel")
            else -> LazyColumn(
                state = state,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(vertical = 4.dp, horizontal = 2.dp),
            ) {
                itemsIndexed(visible, key = { i, p -> "$i:${p.start}" }) { i, p ->
                    val isLive = p.isLive(now)
                    val canPlay = c.canCatchup(ch, p, now)
                    val isPlaying = playing != null && playing.start == p.start
                    ProgrammeRow(
                        p = p,
                        now = now,
                        isLive = isLive,
                        canCatchup = canPlay && !isLive,
                        isPlaying = isPlaying || (playing == null && isLive),
                        onClick = {
                            when {
                                isLive && c.item is PlayItem.Catchup -> c.watchLive(ch)
                                isLive -> c.closePanel()
                                canPlay -> c.playCatchup(ch, p)
                                p.start > System.currentTimeMillis() -> c.toast("This programme hasn't started yet")
                                else -> c.toast("Catch-up isn't available for this programme")
                            }
                        },
                        modifier = if (i == target) Modifier.focusRequester(focus) else Modifier,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProgrammeRow(
    p: Programme,
    now: Long,
    isLive: Boolean,
    canCatchup: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val past = p.stop <= now
    FocusSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(64.dp),
        shape = RoundedCornerShape(12.dp),
        focusedScale = 1.03f,
        color = if (isPlaying) EzColors.Accent.copy(alpha = 0.16f) else Color.Transparent,
        focusedColor = EzColors.SurfaceFocus,
        glow = false,
    ) { _ ->
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(92.dp)) {
                Text(timeOf(p.start), style = EzType.Title.copy(color = if (past && !canCatchup) EzColors.TextTertiary else EzColors.TextPrimary))
                Text(Fmt.dayTime(p.start, Repo.settings.use24h).substringBefore(' '), style = EzType.Caption)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    p.title.ifBlank { "Untitled programme" },
                    style = EzType.Title.copy(color = if (past && !canCatchup) EzColors.TextTertiary else EzColors.TextPrimary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isLive) {
                    Spacer(Modifier.height(5.dp))
                    ProgressLine(p.progress(now), height = 3.dp)
                } else if (p.desc.isNotBlank()) {
                    Text(p.desc, style = EzType.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(10.dp))
            when {
                isLive -> LiveBadge()
                canCatchup -> Row(
                    Modifier.clip(RoundedCornerShape(6.dp)).background(EzColors.Accent.copy(alpha = 0.8f)).padding(horizontal = 7.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Replay, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("CATCH-UP", style = EzType.Caption.copy(color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 10.sp))
                }
                else -> Unit
            }
        }
    }
}
