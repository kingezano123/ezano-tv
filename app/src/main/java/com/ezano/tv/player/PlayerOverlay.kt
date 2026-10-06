package com.ezano.tv.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.VideoLibrary
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import com.ezano.tv.data.Channel
import com.ezano.tv.data.Programme
import com.ezano.tv.data.Repo
import com.ezano.tv.ui.components.AsyncImg
import com.ezano.tv.ui.components.Badge
import com.ezano.tv.ui.components.ChannelLogo
import com.ezano.tv.ui.components.FocusSurface
import com.ezano.tv.ui.components.Fmt
import com.ezano.tv.ui.components.IconPill
import com.ezano.tv.ui.components.LiveBadge
import com.ezano.tv.ui.components.PillButton
import com.ezano.tv.ui.components.ProgressLine
import com.ezano.tv.ui.theme.EzColors
import com.ezano.tv.ui.theme.EzType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** Dark text on white focused surfaces. */
internal val Ink = Color(0xFF0B0B10)

internal val GlassBrush = Brush.verticalGradient(listOf(Color(0xF0161A28), Color(0xF20A0C14)))
private val TopScrim = Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.85f), Color.Black.copy(alpha = 0.45f), Color.Transparent))
private val BottomScrim = Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f), Color.Black.copy(alpha = 0.92f)))

// ====================================================================== small helpers

/** Current time, refreshed every [periodMs]. */
@Composable
internal fun rememberNow(periodMs: Long = 1_000L): Long {
    val state = produceState(System.currentTimeMillis(), periodMs) {
        while (true) {
            value = System.currentTimeMillis()
            delay(periodMs)
        }
    }
    return state.value
}

/** Keeps the last non-null value so exit animations can still render their content. */
@Suppress("UNCHECKED_CAST")
@Composable
internal fun <T : Any> rememberLast(value: T?): T? {
    val holder = remember { arrayOfNulls<Any>(1) }
    if (value != null) holder[0] = value
    return holder[0] as T?
}

/** Request focus once the requester is attached (it may not be on the very first frame). */
@Composable
internal fun AutoFocus(requester: FocusRequester, key: Any? = Unit) {
    LaunchedEffect(key) {
        delay(80)
        runCatching { requester.requestFocus() }
    }
}

internal fun timeOf(ms: Long): String = Fmt.time(ms, Repo.settings.use24h)

/** Programmes for a channel: XMLTV first, Xtream short EPG as fallback. */
internal suspend fun loadProgrammes(ch: Channel): List<Programme> {
    val local = Repo.epg.value.programmes(ch)
    if (local.isNotEmpty()) return local
    return try {
        Repo.programmesFor(ch)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }
}

/** Transparent text button that turns white when focused (Netflix-style bottom row). */
@Composable
internal fun GhostButton(text: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier.height(40.dp),
        shape = RoundedCornerShape(50),
        focusedScale = 1.05f,
        color = Color.Transparent,
        focusedColor = Color.White,
        showBorder = false,
        glow = false,
    ) { focused ->
        val fg = if (focused) Ink else EzColors.TextPrimary
        Row(Modifier.align(Alignment.Center).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, style = EzType.Title.copy(color = fg, fontWeight = FontWeight.Bold), maxLines = 1)
        }
    }
}

/** Compact pill used in cards and the live action row. */
@Composable
internal fun ActionPill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = false,
) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier.height(38.dp),
        shape = RoundedCornerShape(50),
        focusedScale = 1.06f,
        color = if (primary) Color.White.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.12f),
        focusedColor = Color.White,
        showBorder = false,
    ) { focused ->
        val fg = if (primary || focused) Ink else EzColors.TextPrimary
        Row(Modifier.align(Alignment.Center).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(text, style = EzType.Label.copy(color = fg, fontSize = 13.sp, fontWeight = FontWeight.Bold), maxLines = 1)
        }
    }
}

/** Round transport control (play/pause, ⟲10, 10⟳). */
@Composable
private fun RoundControl(
    icon: ImageVector,
    onClick: () -> Unit,
    size: Dp,
    iconSize: Dp,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier.size(size),
        shape = CircleShape,
        focusedScale = 1.12f,
        color = Color.White.copy(alpha = 0.12f),
        focusedColor = Color.White,
        showBorder = false,
    ) { focused ->
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (focused) Ink else Color.White,
            modifier = Modifier.align(Alignment.Center).size(iconSize),
        )
    }
}

// ====================================================================== VOD / episode / catch-up overlay

@Composable
internal fun VodOverlay(c: PlayerController) {
    val playFocus = remember { FocusRequester() }
    AutoFocus(playFocus)
    val item = c.item
    val next = if (item is PlayItem.Ep) c.nextEpisode() else null

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(170.dp).align(Alignment.TopCenter).background(TopScrim))
        Box(Modifier.fillMaxWidth().height(250.dp).align(Alignment.BottomCenter).background(BottomScrim))

        // ---- top: back + title, clock on the right
        Row(
            Modifier.align(Alignment.TopStart).fillMaxWidth().padding(start = 36.dp, end = 44.dp, top = 28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconPill(Icons.AutoMirrored.Rounded.ArrowBack, onClick = { c.exit() }, contentDescription = "Back")
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                if (item is PlayItem.Catchup) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Badge("CATCH-UP", color = EzColors.Accent.copy(alpha = 0.85f))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${Fmt.dayTime(item.programme.start, Repo.settings.use24h)} – ${timeOf(item.programme.stop)}",
                            style = EzType.Caption.copy(color = EzColors.TextSecondary),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                }
                Text(c.title, style = EzType.H2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = c.subtitle
                if (sub.isNotBlank()) {
                    Text(sub, style = EzType.Body.copy(color = EzColors.TextSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(18.dp))
            VodClock(c)
        }

        // ---- center transport
        Row(
            Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundControl(Icons.Rounded.Replay10, onClick = { c.seekBy(-10_000L) }, size = 58.dp, iconSize = 30.dp, contentDescription = "Back 10 seconds")
            val ended = c.playbackState == Player.STATE_ENDED
            RoundControl(
                icon = when {
                    ended -> Icons.Rounded.Replay
                    c.playWhenReady -> Icons.Rounded.Pause
                    else -> Icons.Rounded.PlayArrow
                },
                onClick = { c.togglePlay() },
                size = 84.dp,
                iconSize = 46.dp,
                modifier = Modifier.focusRequester(playFocus),
                contentDescription = if (c.playWhenReady) "Pause" else "Play",
            )
            RoundControl(Icons.Rounded.Forward10, onClick = { c.seekBy(10_000L) }, size = 58.dp, iconSize = 30.dp, contentDescription = "Forward 10 seconds")
        }

        // ---- bottom: seekbar + actions
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 44.dp, end = 44.dp, bottom = 26.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SeekBar(c, Modifier.weight(1f))
                Spacer(Modifier.width(18.dp))
                val shown = c.scrubMs ?: c.seekPreviewMs ?: c.positionMs
                val label = if (c.durationMs > 0) "-" + Fmt.clock((c.durationMs - shown).coerceAtLeast(0L)) else Fmt.clock(shown)
                Text(label, style = EzType.Title.copy(color = EzColors.TextPrimary), modifier = Modifier.widthIn(min = 64.dp), textAlign = TextAlign.End)
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                when (item) {
                    is PlayItem.Ep -> GhostButton("Episodes", Icons.Rounded.VideoLibrary, onClick = { c.openPanel(Panel.Episodes) })
                    is PlayItem.Catchup -> {
                        GhostButton("Watch live", Icons.Rounded.LiveTv, onClick = { c.watchLive(item.channel) })
                        GhostButton("Programmes", Icons.Rounded.Event, onClick = { c.openPanel(Panel.Programmes) })
                    }
                    else -> Unit
                }
                GhostButton("Audio & Subtitles", Icons.Rounded.Subtitles, onClick = { c.openPanel(Panel.AudioSubs) })
                GhostButton(speedLabel(c.speed), Icons.Rounded.Speed, onClick = { c.openPanel(Panel.Speed) })
                GhostButton("Aspect", Icons.Rounded.AspectRatio, onClick = { c.openPanel(Panel.Aspect) })
                if (next != null) {
                    Spacer(Modifier.weight(1f))
                    GhostButton("Next episode", Icons.Rounded.SkipNext, onClick = { c.playNext() })
                }
            }
        }
    }
}

internal fun speedLabel(speed: Float): String =
    if (abs(speed - 1f) < 0.01f) "Speed" else "Speed " + (if (speed % 1f == 0f) "${speed.toInt()}" else "$speed") + "×"

@Composable
private fun VodClock(c: PlayerController) {
    val now = rememberNow(10_000L)
    Column(horizontalAlignment = Alignment.End) {
        Text(timeOf(now), style = EzType.H3)
        val remaining = c.durationMs - c.positionMs
        if (c.durationMs > 0 && remaining > 0) {
            val endsAt = now + (remaining / c.speed.coerceAtLeast(0.25f)).toLong()
            Text("Ends at ${timeOf(endsAt)}", style = EzType.Caption.copy(color = EzColors.TextSecondary))
        }
    }
}

/** Track + buffered + accent progress + thumb. */
@Composable
private fun SeekTrack(fraction: Float, buffered: Float, focused: Boolean, modifier: Modifier = Modifier) {
    val barHeight by animateDpAsState(if (focused) 7.dp else 4.dp, tween(160), label = "bar")
    val thumb by animateDpAsState(if (focused) 10.dp else 6.dp, tween(160), label = "thumb")
    val accent = EzColors.AccentGradientH
    Canvas(modifier.fillMaxWidth().height(24.dp)) {
        val h = barHeight.toPx()
        val y = size.height / 2f
        val r = CornerRadius(h / 2f, h / 2f)
        val top = Offset(0f, y - h / 2f)
        drawRoundRect(color = Color.White.copy(alpha = 0.22f), topLeft = top, size = Size(size.width, h), cornerRadius = r)
        val bw = size.width * buffered.coerceIn(0f, 1f)
        if (bw > 0f) drawRoundRect(color = Color.White.copy(alpha = 0.32f), topLeft = top, size = Size(bw, h), cornerRadius = r)
        val pw = size.width * fraction.coerceIn(0f, 1f)
        if (pw > 0f) drawRoundRect(brush = accent, topLeft = top, size = Size(pw, h), cornerRadius = r)
        val tr = thumb.toPx()
        val center = Offset(pw.coerceIn(tr, (size.width - tr).coerceAtLeast(tr)), y)
        if (focused) drawCircle(color = EzColors.Accent.copy(alpha = 0.35f), radius = tr * 1.9f, center = center)
        drawCircle(color = Color.White, radius = tr, center = center)
        drawCircle(color = EzColors.Accent, radius = tr * 0.45f, center = center)
    }
}

/** Focusable seekbar: LEFT/RIGHT scrub (with time preview), OK commits. */
@Composable
private fun SeekBar(c: PlayerController, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val dur = c.durationMs
    val shown = c.scrubMs ?: c.seekPreviewMs ?: c.positionMs
    val fraction = if (dur > 0) shown.toFloat() / dur else 0f
    val buffered = if (dur > 0) c.bufferedMs.toFloat() / dur else 0f
    BoxWithConstraints(
        modifier
            .height(40.dp)
            .onFocusChanged {
                focused = it.isFocused
                if (!it.isFocused) c.cancelScrub()
            }
            .onKeyEvent { c.onSeekBarKey(it.nativeKeyEvent) }
            .focusable(),
    ) {
        SeekTrack(fraction, buffered, focused, Modifier.align(Alignment.Center))
        val scrub = c.scrubMs
        if (focused && scrub != null) {
            val bubbleWidth = 76.dp
            val x = (maxWidth * fraction.coerceIn(0f, 1f) - bubbleWidth / 2).coerceIn(0.dp, (maxWidth - bubbleWidth).coerceAtLeast(0.dp))
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .offset(x = x, y = (-30).dp)
                    .width(bubbleWidth)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White)
                    .padding(vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(Fmt.clock(scrub), style = EzType.Label.copy(color = Ink, fontWeight = FontWeight.Bold, fontSize = 13.sp))
            }
        }
    }
}

/** Small bar shown when seeking with the overlay hidden. */
@Composable
internal fun SeekHud(c: PlayerController) {
    val shown = c.seekPreviewMs ?: c.positionMs
    val delta = shown - c.seekOriginMs
    val dur = c.durationMs
    Box(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))))
            .padding(start = 44.dp, end = 44.dp, top = 40.dp, bottom = 30.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.14f)).padding(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (delta >= 0) Icons.Rounded.FastForward else Icons.Rounded.FastRewind,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text((if (delta >= 0) "+" else "−") + Fmt.clock(abs(delta)), style = EzType.H3)
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(
                    if (dur > 0) "${Fmt.clock(shown)} / ${Fmt.clock(dur)}" else Fmt.clock(shown),
                    style = EzType.Title.copy(color = EzColors.TextPrimary),
                )
            }
            Spacer(Modifier.height(10.dp))
            SeekTrack(
                fraction = if (dur > 0) shown.toFloat() / dur else 0f,
                buffered = if (dur > 0) c.bufferedMs.toFloat() / dur else 0f,
                focused = true,
            )
        }
    }
}

// ====================================================================== live banner

@Composable
internal fun LiveBanner(c: PlayerController) {
    val ch = rememberLast(c.bannerChannel) ?: return
    val epg by Repo.epg.collectAsState()
    val favorites by Repo.store.favorites.collectAsState()
    val now = rememberNow(15_000L)
    val programmes by produceState(epg.programmes(ch), ch.id, epg) {
        value = epg.programmes(ch)
        if (value.isEmpty()) {
            delay(400) // don't hit the short-EPG API for every channel while zapping fast
            value = loadProgrammes(ch)
        }
    }
    val current = remember(programmes, now) { programmes.firstOrNull { it.start <= now && it.stop > now } }
    val next = remember(programmes, now) { programmes.firstOrNull { it.start >= now } }
    val isFav = remember(favorites, ch.id) { Repo.store.isFavorite("live:${ch.id}") }
    val zapping = c.zapChannel != null
    val resolution = if (zapping) null else resolutionLabel(c.videoWidth, c.videoHeight)

    Column(
        Modifier
            .fillMaxWidth()
            .background(BottomScrim)
            .padding(start = 44.dp, end = 44.dp, top = 60.dp, bottom = 30.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChannelLogo(ch.logo, ch.name, size = 64.dp)
            Spacer(Modifier.width(22.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (ch.number > 0) {
                        Text(
                            ch.number.toString(),
                            style = EzType.H2.copy(color = EzColors.Accent2, fontWeight = FontWeight.ExtraBold),
                        )
                        Spacer(Modifier.width(12.dp))
                    }
                    Text(
                        ch.name,
                        style = EzType.H2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(12.dp))
                    LiveBadge()
                    if (resolution != null) {
                        Spacer(Modifier.width(6.dp))
                        Badge(resolution, color = Color.White.copy(alpha = 0.18f))
                    }
                    if (ch.catchupDays > 0) {
                        Spacer(Modifier.width(6.dp))
                        Badge("CATCH-UP", color = EzColors.Accent.copy(alpha = 0.7f))
                    }
                    if (isFav) {
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.Rounded.Star, contentDescription = "Favorite", tint = EzColors.Warning, modifier = Modifier.size(18.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (current != null) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            current.title.ifBlank { "Untitled programme" },
                            style = EzType.H3,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "${timeOf(current.start)} – ${timeOf(current.stop)}  ·  ${Fmt.minutesLeft(current.stop, now)}",
                            style = EzType.BodySmall.copy(color = EzColors.TextSecondary),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    ProgressLine(current.progress(now), height = 4.dp)
                    if (current.desc.isNotBlank() && c.liveActions) {
                        Spacer(Modifier.height(8.dp))
                        Text(current.desc, style = EzType.BodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                } else {
                    Text("No programme information", style = EzType.Body)
                }
                if (next != null) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("NEXT", style = EzType.Caption.copy(color = EzColors.Accent2, fontWeight = FontWeight.ExtraBold))
                        Spacer(Modifier.width(8.dp))
                        Text(timeOf(next.start), style = EzType.BodySmall.copy(color = EzColors.TextSecondary))
                        Spacer(Modifier.width(8.dp))
                        Text(next.title, style = EzType.BodySmall.copy(color = EzColors.TextPrimary), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        AnimatedVisibility(visible = c.liveActions, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            LiveActionRow(c, isFav)
        }
    }
}

@Composable
private fun LiveActionRow(c: PlayerController, isFav: Boolean) {
    val first = remember { FocusRequester() }
    AutoFocus(first)
    Row(
        Modifier.padding(top = 18.dp, start = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ActionPill(
            if (isFav) "Favorited" else "Favorite",
            onClick = { c.toggleFavorite() },
            icon = if (isFav) Icons.Rounded.Star else Icons.Rounded.StarBorder,
            modifier = Modifier.focusRequester(first),
        )
        ActionPill("Channels", onClick = { c.openPanel(Panel.Channels) }, icon = Icons.Rounded.Tv)
        ActionPill(
            if ((c.currentChannel?.catchupDays ?: 0) > 0) "Guide & Catch-up" else "Guide",
            onClick = { c.openPanel(Panel.Programmes) },
            icon = Icons.Rounded.Event,
        )
        ActionPill("Recall", onClick = { c.recall() }, icon = Icons.Rounded.History)
        ActionPill("Audio & Subs", onClick = { c.openPanel(Panel.AudioSubs) }, icon = Icons.Rounded.Subtitles)
        ActionPill("Aspect", onClick = { c.openPanel(Panel.Aspect) }, icon = Icons.Rounded.AspectRatio)
    }
}

/** Top-right clock for the live banner. */
@Composable
internal fun ClockBadge() {
    val now = rememberNow(5_000L)
    val date = remember(now / 60_000L) { SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(now)) }
    Box(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent)))
            .padding(top = 26.dp, end = 44.dp, bottom = 40.dp),
        contentAlignment = Alignment.TopEnd,
    ) {
        Column(horizontalAlignment = Alignment.End) {
            Text(timeOf(now), style = EzType.H1)
            Text(date, style = EzType.Caption.copy(color = EzColors.TextSecondary))
        }
    }
}

/** Big channel number being typed on the remote. */
@Composable
internal fun NumberEntryBadge(c: PlayerController) {
    val entry = rememberLast(c.numberEntry.ifEmpty { null }) ?: return
    val candidate = remember(entry) { entry.toIntOrNull()?.let { c.findChannelByNumber(it) } }
    Column(
        Modifier
            .padding(top = 28.dp, end = 40.dp)
            .shadow(20.dp, RoundedCornerShape(18.dp))
            .clip(RoundedCornerShape(18.dp))
            .background(GlassBrush)
            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(18.dp))
            .padding(horizontal = 26.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.End,
    ) {
        Text(entry, style = EzType.Display.copy(fontSize = 52.sp, lineHeight = 56.sp))
        Text(
            candidate?.name ?: "No channel",
            style = EzType.Label.copy(color = if (candidate != null) EzColors.TextSecondary else EzColors.Live),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 220.dp),
        )
    }
}

// ====================================================================== buffering, errors, next episode

@Composable
internal fun BufferingIndicator(c: PlayerController, modifier: Modifier = Modifier) {
    val show = c.errorMessage == null && c.player != null &&
        (c.reconnectInfo != null || (c.playbackState == Player.STATE_BUFFERING && c.playWhenReady))
    AnimatedVisibility(
        visible = show,
        modifier = modifier,
        enter = fadeIn(tween(250, delayMillis = 250)),
        exit = fadeOut(tween(200)),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(76.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = EzColors.Accent, strokeWidth = 3.dp, modifier = Modifier.size(44.dp))
            }
            Spacer(Modifier.height(14.dp))
            Text(
                c.reconnectInfo ?: "Buffering…",
                style = EzType.Title.copy(color = EzColors.TextPrimary),
                modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 14.dp, vertical = 5.dp),
            )
        }
    }
}

@Composable
internal fun ErrorCard(c: PlayerController, message: String) {
    val retryFocus = remember { FocusRequester() }
    AutoFocus(retryFocus, message)
    val channel = c.currentChannel
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .width(480.dp)
                .shadow(30.dp, RoundedCornerShape(22.dp))
                .clip(RoundedCornerShape(22.dp))
                .background(GlassBrush)
                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(22.dp))
                .padding(horizontal = 32.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(58.dp).clip(CircleShape).background(EzColors.Live.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = EzColors.Live, modifier = Modifier.size(32.dp))
            }
            Spacer(Modifier.height(16.dp))
            Text(
                if (c.isLive && channel != null) "Can't play ${channel.name}" else "Playback problem",
                style = EzType.H2,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Text(message, style = EzType.Body, textAlign = TextAlign.Center)
            if (c.isLive) {
                Spacer(Modifier.height(6.dp))
                Text("Use ▲ ▼ or CH+/CH− to try another channel.", style = EzType.Caption, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton("Retry", onClick = { c.retry() }, icon = Icons.Rounded.Refresh, primary = true, modifier = Modifier.focusRequester(retryFocus))
                PillButton("Back", onClick = { c.exit() }, icon = Icons.AutoMirrored.Rounded.ArrowBack)
            }
        }
    }
}

@Composable
internal fun NextUpCard(c: PlayerController, up: NextUp) {
    val playFocus = remember { FocusRequester() }
    AutoFocus(playFocus, up)
    val series = (c.item as? PlayItem.Ep)?.series
    val drain by animateFloatAsState(
        targetValue = (c.countdown / 10f).coerceIn(0f, 1f),
        animationSpec = tween(1_000, easing = LinearEasing),
        label = "countdown",
    )
    Row(
        Modifier
            .padding(end = 40.dp, bottom = 40.dp)
            .width(470.dp)
            .shadow(28.dp, RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp))
            .background(GlassBrush)
            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.width(160.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(EzColors.SurfaceHi),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImg(up.episode.image.ifBlank { series?.poster.orEmpty() }, Modifier.fillMaxSize())
            Box(
                Modifier.size(38.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (up.mode == NextMode.Countdown) "Next episode in ${c.countdown}s" else "Next episode",
                style = EzType.Label.copy(color = EzColors.Accent2, fontWeight = FontWeight.ExtraBold),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                PlayerController.episodeLabel(up.episode),
                style = EzType.Title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (up.mode == NextMode.Countdown) {
                Spacer(Modifier.height(8.dp))
                ProgressLine(drain, height = 3.dp)
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionPill(
                    "Play now",
                    onClick = { c.playNext() },
                    icon = Icons.Rounded.PlayArrow,
                    primary = true,
                    modifier = Modifier.focusRequester(playFocus),
                )
                ActionPill(if (up.mode == NextMode.Credits) "Watch credits" else "Cancel", onClick = { c.cancelNextUp() })
            }
        }
    }
}
