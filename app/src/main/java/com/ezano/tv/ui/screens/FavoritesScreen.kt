package com.ezano.tv.ui.screens

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ezano.tv.data.Channel
import com.ezano.tv.data.Repo
import com.ezano.tv.player.Playback
import com.ezano.tv.ui.Layout
import com.ezano.tv.ui.LocalNavigator
import com.ezano.tv.ui.Screen
import com.ezano.tv.ui.components.EmptyView
import com.ezano.tv.ui.components.PosterCard
import com.ezano.tv.ui.components.WideCard
import com.ezano.tv.ui.components.contentRow
import com.ezano.tv.ui.theme.EzColors
import com.ezano.tv.ui.theme.EzType
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun FavoritesScreen() {
    val ctx = LocalContext.current
    val nav = LocalNavigator.current
    val idx by Repo.index.collectAsState()
    val favs by Repo.store.favorites.collectAsState()

    // Most recently added first.
    val channels = remember(idx, favs) { Repo.favoriteChannels().asReversed().distinctBy { it.id } }
    val movies = remember(idx, favs) { Repo.favoriteMovies().asReversed().distinctBy { it.id } }
    val series = remember(idx, favs) { Repo.favoriteSeries().asReversed().distinctBy { it.id } }
    val total = channels.size + movies.size + series.size

    val firstFr = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { firstFr.requestFocus() }
    }
    fun firstMod(isFirst: Boolean): Modifier = if (isFirst) Modifier.focusRequester(firstFr) else Modifier

    Box(Modifier.fillMaxSize().background(EzColors.Bg)) {
        // Soft accent glow so the page doesn't feel flat.
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .fillMaxWidth(0.65f)
                .fillMaxHeight(0.7f)
                .background(Brush.radialGradient(listOf(EzColors.Accent.copy(alpha = 0.16f), Color.Transparent))),
        )

        if (total == 0) {
            EmptyView(
                "Your list is empty",
                "Long-press OK on any channel, movie or series to add it to My List. It will show up here and on Home.",
                icon = Icons.Rounded.Star,
            )
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 34.dp, bottom = 56.dp)) {
                item(key = "header") {
                    Column(Modifier.padding(start = Layout.ContentStart, end = 48.dp, bottom = 22.dp)) {
                        Text("My List", style = EzType.H1)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "$total saved  ·  long-press OK to remove",
                            style = EzType.BodySmall,
                        )
                    }
                }
                contentRow(
                    "Channels",
                    channels,
                    key = { it.id },
                    startPadding = Layout.ContentStart,
                    trailing = "${channels.size}",
                ) { c ->
                    FavChannelCard(c, channels, firstMod(c === channels.first()))
                }
                contentRow(
                    "Movies",
                    movies,
                    key = { it.id },
                    startPadding = Layout.ContentStart,
                    trailing = "${movies.size}",
                ) { m ->
                    PosterCard(
                        title = m.name,
                        image = m.poster,
                        onClick = { nav.navigate(Screen.MovieDetail(m.id)) },
                        modifier = firstMod(channels.isEmpty() && m === movies.first()),
                        width = 132.dp,
                        subtitle = m.year.ifBlank { null },
                        badge = favRatingBadge(m.rating),
                        onLongClick = { favToggle(ctx, "movie:${m.id}") },
                    )
                }
                contentRow(
                    "Series",
                    series,
                    key = { it.id },
                    startPadding = Layout.ContentStart,
                    trailing = "${series.size}",
                ) { s ->
                    PosterCard(
                        title = s.name,
                        image = s.poster,
                        onClick = { nav.navigate(Screen.SeriesDetail(s.id)) },
                        modifier = firstMod(channels.isEmpty() && movies.isEmpty() && s === series.first()),
                        width = 132.dp,
                        subtitle = s.year.ifBlank { null },
                        badge = favRatingBadge(s.rating),
                        onLongClick = { favToggle(ctx, "series:${s.id}") },
                    )
                }
            }
        }
    }
}

@Composable
private fun FavChannelCard(c: Channel, zap: List<Channel>, modifier: Modifier) {
    val ctx = LocalContext.current
    val epg by Repo.epg.collectAsState()
    val clock by Repo.clock.collectAsState()
    val now = remember(epg, clock, c.id) { epg.now(c, clock) }
    WideCard(
        title = c.name,
        image = c.logo,
        onClick = { Playback.playChannel(ctx, c, zap) },
        modifier = modifier,
        width = 220.dp,
        subtitle = now?.title?.ifBlank { null } ?: "Live now",
        progress = now?.progress(clock),
        imageFit = true,
        onLongClick = { favToggle(ctx, "live:${c.id}") },
    )
}

private fun favToggle(ctx: Context, key: String) {
    val added = Repo.store.toggleFavorite(key)
    Toast.makeText(ctx, if (added) "Added to My List" else "Removed from My List", Toast.LENGTH_SHORT).show()
}

private fun favRatingBadge(r: Double): String? =
    if (r > 0) "★ " + String.format(Locale.US, "%.1f", r) else null
