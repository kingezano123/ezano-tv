package com.ezano.tv.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.ezano.tv.ui.theme.EzColors
import com.ezano.tv.ui.theme.EzType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The single building block for every focusable thing in the app.
 * Handles D-pad focus, OK/Enter clicks, long-press, a smooth scale-up, a bright border and glow.
 * [content] receives whether the item is currently focused.
 */
@Composable
fun FocusSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(14.dp),
    focusedScale: Float = 1.06f,
    color: Color = EzColors.Surface,
    focusedColor: Color = EzColors.SurfaceFocus,
    borderWidth: Dp = 2.dp,
    showBorder: Boolean = true,
    glow: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onFocus: ((Boolean) -> Unit)? = null,
    enabled: Boolean = true,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) focusedScale else 1f, tween(160), label = "scale")
    val bg by animateColorAsState(if (focused) focusedColor else color, tween(160), label = "bg")
    Box(
        modifier = modifier
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .then(if (focused && glow) Modifier.shadow(18.dp, shape, ambientColor = EzColors.Accent, spotColor = EzColors.Accent) else Modifier)
            .clip(shape)
            .background(bg, shape)
            .then(if (focused && showBorder) Modifier.border(BorderStroke(borderWidth, Color.White.copy(alpha = 0.92f)), shape) else Modifier)
            .onFocusChanged { onFocus?.invoke(it.isFocused) }
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        content(focused)
    }
}

/** Rounded pill button (Play, Trailer, My List…). Primary = white like Disney+/Netflix. */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = false,
    onFocus: ((Boolean) -> Unit)? = null,
) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier.height(44.dp),
        shape = RoundedCornerShape(50),
        focusedScale = 1.05f,
        color = if (primary) Color.White.copy(alpha = 0.92f) else Color.White.copy(alpha = 0.10f),
        focusedColor = if (primary) Color.White else Color.White.copy(alpha = 0.95f),
        showBorder = false,
        onFocus = onFocus,
    ) { focused ->
        val fg = if (primary || focused) Color(0xFF0B0B10) else EzColors.TextPrimary
        Row(
            Modifier.fillMaxSize().padding(horizontal = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text, style = EzType.Title.copy(color = fg, fontWeight = FontWeight.Bold))
        }
    }
}

/** Round icon-only button. */
@Composable
fun IconPill(icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier, contentDescription: String? = null, active: Boolean = false) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier.size(44.dp),
        shape = CircleShape,
        color = if (active) EzColors.Accent.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.10f),
        focusedColor = Color.White,
        showBorder = false,
    ) { focused ->
        Icon(icon, contentDescription, tint = if (focused) Color(0xFF0B0B10) else EzColors.TextPrimary, modifier = Modifier.align(Alignment.Center).size(22.dp))
    }
}

/** Selectable chip (categories, seasons, filters). */
@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, onFocus: ((Boolean) -> Unit)? = null) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier.height(36.dp),
        shape = RoundedCornerShape(50),
        focusedScale = 1.06f,
        color = if (selected) Color.White.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.06f),
        focusedColor = Color.White,
        showBorder = false,
        glow = false,
        onFocus = onFocus,
    ) { focused ->
        Text(
            text,
            style = EzType.Label.copy(color = if (focused) Color(0xFF0B0B10) else if (selected) EzColors.TextPrimary else EzColors.TextSecondary, fontSize = 13.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 16.dp),
        )
    }
}

/** Network image with crossfade + subtle placeholder. */
@Composable
fun AsyncImg(
    url: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    contentDescription: String? = null,
    alpha: Float = 1f,
) {
    val ctx = LocalContext.current
    if (url.isNullOrBlank()) { Box(modifier); return }
    AsyncImage(
        model = remember(url) { ImageRequest.Builder(ctx).data(url).crossfade(220).build() },
        contentDescription = contentDescription,
        contentScale = contentScale,
        alpha = alpha,
        modifier = modifier,
    )
}

/** Vertical 2:3 poster for movies & series. */
@Composable
fun PosterCard(
    title: String,
    image: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 140.dp,
    subtitle: String? = null,
    progress: Float? = null,
    badge: String? = null,
    onFocus: ((Boolean) -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    Column(modifier.width(width)) {
        FocusSurface(
            onClick = onClick,
            onFocus = onFocus,
            onLongClick = onLongClick,
            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f),
            shape = RoundedCornerShape(12.dp),
        ) { _ ->
            PlaceholderTitle(title)
            AsyncImg(image, Modifier.fillMaxSize())
            if (badge != null) Badge(badge, Modifier.align(Alignment.TopEnd).padding(6.dp))
            if (progress != null && progress > 0f) ProgressLine(progress, Modifier.align(Alignment.BottomCenter).padding(8.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(title, style = EzType.BodySmall.copy(color = EzColors.TextPrimary), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (subtitle != null) Text(subtitle, style = EzType.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Horizontal 16:9 card (continue watching, episodes, live channels with art). */
@Composable
fun WideCard(
    title: String,
    image: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 260.dp,
    subtitle: String? = null,
    progress: Float? = null,
    live: Boolean = false,
    imageFit: Boolean = false,
    onFocus: ((Boolean) -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    Column(modifier.width(width)) {
        FocusSurface(
            onClick = onClick, onFocus = onFocus, onLongClick = onLongClick,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            shape = RoundedCornerShape(12.dp),
        ) { _ ->
            PlaceholderTitle(title)
            AsyncImg(
                image,
                if (imageFit) Modifier.fillMaxSize().padding(22.dp) else Modifier.fillMaxSize(),
                contentScale = if (imageFit) ContentScale.Fit else ContentScale.Crop,
            )
            if (live) LiveBadge(Modifier.align(Alignment.TopStart).padding(8.dp))
            if (progress != null && progress > 0f) ProgressLine(progress, Modifier.align(Alignment.BottomCenter).padding(10.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(title, style = EzType.Title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (subtitle != null) Text(subtitle, style = EzType.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun BoxScope.PlaceholderTitle(title: String) {
    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(EzColors.SurfaceHi, EzColors.Surface))))
    Text(
        title, style = EzType.Label.copy(color = EzColors.TextTertiary), textAlign = TextAlign.Center, maxLines = 3,
        overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.Center).padding(10.dp),
    )
}

/** Channel logo on a soft tile with initials fallback. */
@Composable
fun ChannelLogo(url: String, name: String, modifier: Modifier = Modifier, size: Dp = 56.dp) {
    Box(
        modifier.size(width = size * 1.4f, height = size).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.07f)),
        contentAlignment = Alignment.Center,
    ) {
        val initials = name.split(' ', '-', ':', '|').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() }
        Text(initials, style = EzType.Title.copy(color = EzColors.TextTertiary))
        AsyncImg(url, Modifier.fillMaxSize().padding(6.dp), contentScale = ContentScale.Fit)
    }
}

@Composable
fun LiveBadge(modifier: Modifier = Modifier, text: String = "LIVE") {
    Row(
        modifier.clip(RoundedCornerShape(6.dp)).background(EzColors.Live).padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(Color.White))
        Spacer(Modifier.width(5.dp))
        Text(text, style = EzType.Caption.copy(color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 10.sp))
    }
}

@Composable
fun Badge(text: String, modifier: Modifier = Modifier, color: Color = Color.Black.copy(alpha = 0.65f)) {
    Text(
        text,
        style = EzType.Caption.copy(color = Color.White, fontWeight = FontWeight.Bold, fontSize = 10.sp),
        modifier = modifier.clip(RoundedCornerShape(6.dp)).background(color).padding(horizontal = 6.dp, vertical = 3.dp),
    )
}

/** Thin rounded progress bar with accent gradient. */
@Composable
fun ProgressLine(progress: Float, modifier: Modifier = Modifier, height: Dp = 4.dp, track: Color = Color.White.copy(alpha = 0.25f)) {
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(50)).background(track)) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(height).clip(RoundedCornerShape(50)).background(EzColors.AccentGradientH))
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(modifier.padding(bottom = 12.dp), verticalAlignment = Alignment.Bottom) {
        Text(text, style = EzType.H3)
        if (trailing != null) {
            Spacer(Modifier.width(10.dp))
            Text(trailing, style = EzType.Caption, modifier = Modifier.padding(bottom = 2.dp))
        }
    }
}

/** A titled horizontal row of items, like every streaming app's home shelves. */
fun <T> LazyListScope.contentRow(
    title: String,
    items: List<T>,
    key: (T) -> Any,
    startPadding: Dp = 48.dp,
    trailing: String? = null,
    itemContent: @Composable (T) -> Unit,
) {
    if (items.isEmpty()) return
    item(key = "row-$title") {
        Column(Modifier.padding(bottom = 26.dp)) {
            SectionTitle(title, Modifier.padding(start = startPadding), trailing)
            LazyRow(
                contentPadding = PaddingValues(start = startPadding, end = 48.dp, top = 6.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(items, key = key) { itemContent(it) }
            }
        }
    }
}

@Composable
fun LoadingView(message: String = "Loading…", modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(color = EzColors.Accent, strokeWidth = 3.dp, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(18.dp))
        Text(message, style = EzType.Body)
    }
}

@Composable
fun EmptyView(title: String, message: String = "", icon: ImageVector = Icons.Rounded.Tv, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(40.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(EzColors.Surface), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = EzColors.TextTertiary, modifier = Modifier.size(34.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = EzType.H3, textAlign = TextAlign.Center)
        if (message.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(message, style = EzType.Body, textAlign = TextAlign.Center, modifier = Modifier.width(420.dp))
        }
    }
}

/** Full-bleed backdrop with cinematic scrims, used behind heroes and detail pages. */
@Composable
fun Backdrop(url: String?, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize()) {
        AsyncImg(url, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(EzColors.LeftScrim))
        Box(Modifier.fillMaxSize().background(EzColors.BottomScrim))
    }
}

/** Little meta line pieces: "2024 • 2h 11m • Drama" */
@Composable
fun MetaRow(parts: List<String>, modifier: Modifier = Modifier, rating: Double = 0.0) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (rating > 0) {
            Badge("★ ${"%.1f".format(Locale.US, rating)}", color = EzColors.Accent.copy(alpha = 0.85f))
            Spacer(Modifier.width(10.dp))
        }
        Text(parts.filter { it.isNotBlank() }.joinToString("  •  "), style = EzType.BodySmall.copy(color = EzColors.TextSecondary), maxLines = 1)
    }
}

object Fmt {
    fun time(ms: Long, use24h: Boolean = false): String =
        SimpleDateFormat(if (use24h) "HH:mm" else "h:mm a", Locale.getDefault()).format(Date(ms))

    fun dayTime(ms: Long, use24h: Boolean = false): String {
        val now = System.currentTimeMillis()
        val dayFmt = SimpleDateFormat("yyyyMMdd", Locale.US)
        val d = dayFmt.format(Date(ms))
        val label = when (d) {
            dayFmt.format(Date(now)) -> "Today"
            dayFmt.format(Date(now + 86_400_000L)) -> "Tomorrow"
            dayFmt.format(Date(now - 86_400_000L)) -> "Yesterday"
            else -> SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(ms))
        }
        return "$label ${time(ms, use24h)}"
    }

    fun duration(ms: Long): String {
        val totalMin = (ms / 60000L).toInt()
        val h = totalMin / 60; val m = totalMin % 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }

    fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    fun minutesLeft(stop: Long, now: Long = System.currentTimeMillis()): String {
        val m = ((stop - now) / 60000L).coerceAtLeast(0)
        return if (m >= 60) "${m / 60}h ${m % 60}m left" else "${m}m left"
    }
}
