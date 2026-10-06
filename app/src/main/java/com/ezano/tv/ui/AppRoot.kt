package com.ezano.tv.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ezano.tv.R
import com.ezano.tv.data.LoadState
import com.ezano.tv.data.Reminder
import com.ezano.tv.data.Repo
import com.ezano.tv.data.Updater
import com.ezano.tv.player.Playback
import com.ezano.tv.ui.components.FocusSurface
import com.ezano.tv.ui.components.LoadingView
import com.ezano.tv.ui.components.PillButton
import com.ezano.tv.ui.components.ProgressLine
import com.ezano.tv.ui.screens.EventSearchScreen
import com.ezano.tv.ui.screens.FavoritesScreen
import com.ezano.tv.ui.screens.GuideScreen
import com.ezano.tv.ui.screens.HomeScreen
import com.ezano.tv.ui.screens.LiveScreen
import com.ezano.tv.ui.screens.LoginScreen
import com.ezano.tv.ui.screens.MovieDetailScreen
import com.ezano.tv.ui.screens.MoviesScreen
import com.ezano.tv.ui.screens.SearchScreen
import com.ezano.tv.ui.screens.SeriesDetailScreen
import com.ezano.tv.ui.screens.SettingsScreen
import com.ezano.tv.ui.screens.ShowsScreen
import com.ezano.tv.ui.theme.EzColors
import com.ezano.tv.ui.theme.EzType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Login gate → splash → main shell. Nothing works until the user signs in to a playlist. */
@Composable
fun AppRoot(onExit: () -> Unit) {
    val playlists by Repo.store.playlists.collectAsState()
    val index by Repo.index.collectAsState()
    val loadState by Repo.loadState.collectAsState()

    Box(Modifier.fillMaxSize().background(EzColors.Bg)) {
        when {
            playlists.isEmpty() -> LoginScreen(canCancel = false, onDone = {}, onCancel = {})
            index == null -> when (val s = loadState) {
                is LoadState.Error -> StartupError(s.message)
                is LoadState.Loading -> Splash(s.message)
                else -> Splash("Loading…")
            }
            else -> MainShell(onExit)
        }
    }
}

@Composable
private fun Splash(message: String) {
    Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0xFF1A1440), EzColors.Bg), radius = 1400f))) {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Image(painterResource(R.drawable.ezano_mark), null, Modifier.size(84.dp))
            Spacer(Modifier.height(18.dp))
            Text("EZANO TV", style = EzType.H1.copy(letterSpacing = 6.sp))
            Spacer(Modifier.height(36.dp))
            Box(Modifier.height(80.dp)) { LoadingView(message) }
        }
    }
}

@Composable
private fun StartupError(message: String) {
    val fr = remember { FocusRequester() }
    var adding by remember { mutableStateOf(false) }
    if (adding) {
        LoginScreen(canCancel = true, onDone = { adding = false }, onCancel = { adding = false })
        return
    }
    LaunchedEffect(Unit) { delay(80); runCatching { fr.requestFocus() } }
    Column(Modifier.fillMaxSize().padding(60.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Couldn't load your playlist", style = EzType.H1)
        Spacer(Modifier.height(10.dp))
        Text(message, style = EzType.Body, modifier = Modifier.width(560.dp))
        Spacer(Modifier.height(28.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            PillButton("Try again", { Repo.loadActive(forceRefresh = true) }, Modifier.focusRequester(fr), primary = true)
            PillButton("Use a different login", { adding = true })
            PillButton("Sign out", { Repo.store.activePlaylist?.let { Repo.logout(it.id) } })
        }
    }
}

// ---------------------------------------------------------------------------------------- shell

private data class RailItem(val screen: Screen, val label: String, val icon: ImageVector)

private val railItems = listOf(
    RailItem(Screen.Search, "Search", Icons.Rounded.Search),
    RailItem(Screen.Home, "Home", Icons.Rounded.Home),
    RailItem(Screen.Live, "Live TV", Icons.Rounded.LiveTv),
    RailItem(Screen.Guide, "TV Guide", Icons.Rounded.CalendarMonth),
    RailItem(Screen.Movies, "Movies", Icons.Rounded.Movie),
    RailItem(Screen.Shows, "Series", Icons.Rounded.VideoLibrary),
    RailItem(Screen.Favorites, "My List", Icons.Rounded.Star),
    RailItem(Screen.Settings, "Settings", Icons.Rounded.Settings),
)

@Composable
private fun MainShell(onExit: () -> Unit) {
    val nav = remember { Navigator() }
    val ctx = LocalContext.current
    var lastBack by remember { mutableLongStateOf(0L) }

    BackHandler {
        if (!nav.back()) {
            val now = System.currentTimeMillis()
            if (now - lastBack < 2000) onExit() else {
                lastBack = now
                Toast.makeText(ctx, "Press back again to exit", Toast.LENGTH_SHORT).show()
            }
        }
    }

    CompositionLocalProvider(LocalNavigator provides nav) {
        val current = nav.current
        Box(Modifier.fillMaxSize()) {
            Crossfade(targetState = current, animationSpec = tween(220), label = "screen") { screen ->
                Box(Modifier.fillMaxSize()) {
                    when (screen) {
                        Screen.Home -> HomeScreen()
                        Screen.Live -> LiveScreen()
                        Screen.Guide -> GuideScreen()
                        Screen.Movies -> MoviesScreen()
                        Screen.Shows -> ShowsScreen()
                        Screen.Search -> SearchScreen()
                        Screen.Favorites -> FavoritesScreen()
                        Screen.Settings -> SettingsScreen()
                        is Screen.MovieDetail -> MovieDetailScreen(screen.movieId)
                        is Screen.SeriesDetail -> SeriesDetailScreen(screen.seriesId)
                        is Screen.EventSearch -> EventSearchScreen(screen.query)
                        Screen.AddPlaylist -> LoginScreen(canCancel = true, onDone = { nav.back() }, onCancel = { nav.back() })
                    }
                }
            }
            if (current.topLevel) NavRail(current, onSelect = { nav.navigate(it) })
            ReminderWatcher()
            UpdatePrompt()
        }
    }
}

@Composable
private fun NavRail(current: Screen, onSelect: (Screen) -> Unit) {
    var railFocused by remember { mutableStateOf(false) }
    val width by animateDpAsState(if (railFocused) 230.dp else Layout.RailWidth, tween(200), label = "rail")
    val requesters = remember { railItems.associate { it.screen to FocusRequester() } }
    val selectedRequester = requesters[current] ?: requesters.getValue(Screen.Home)

    Box(
        Modifier
            .fillMaxHeight()
            .width(width)
            .background(
                if (railFocused) Brush.horizontalGradient(listOf(Color(0xF507080C), Color(0xE607080C), Color(0x0007080C)))
                else Brush.horizontalGradient(listOf(Color(0xB307080C), Color(0x0007080C))),
            )
            .onFocusChanged { railFocused = it.hasFocus }
            .focusProperties { enter = { selectedRequester } }
            .focusGroup(),
    ) {
        Column(
            Modifier.fillMaxHeight().padding(vertical = 26.dp, horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(Modifier.height(52.dp).padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.ezano_mark), null, Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)))
                AnimatedVisibility(railFocused, enter = fadeIn(), exit = fadeOut()) {
                    Text("  EZANO", style = EzType.H3.copy(letterSpacing = 3.sp), maxLines = 1)
                }
            }
            Spacer(Modifier.height(18.dp))
            railItems.forEach { item ->
                val selected = item.screen == current
                FocusSurface(
                    onClick = { onSelect(item.screen) },
                    modifier = Modifier.fillMaxWidth().height(46.dp).focusRequester(requesters.getValue(item.screen)),
                    shape = RoundedCornerShape(12.dp),
                    focusedScale = 1.03f,
                    color = Color.Transparent,
                    focusedColor = Color.White,
                    showBorder = false,
                    glow = false,
                ) { focused ->
                    val tint = when {
                        focused -> Color(0xFF0B0B10)
                        selected -> EzColors.TextPrimary
                        else -> EzColors.TextTertiary
                    }
                    Row(Modifier.fillMaxSize().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(item.icon, item.label, tint = tint, modifier = Modifier.size(24.dp))
                        if (railFocused) {
                            Spacer(Modifier.width(16.dp))
                            Text(item.label, style = EzType.Title.copy(color = tint, fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold), maxLines = 1)
                        }
                    }
                    if (selected && !focused) {
                        Box(Modifier.align(Alignment.CenterStart).width(3.dp).height(20.dp).clip(RoundedCornerShape(50)).background(EzColors.AccentGradient))
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------- reminders

@Composable
private fun ReminderWatcher() {
    val ctx = LocalContext.current
    var active by remember { mutableStateOf<Reminder?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        while (true) {
            val now = System.currentTimeMillis()
            val list = Repo.store.reminders.value
            // Drop stale reminders (>30 min past start)
            list.filter { it.start < now - 30 * 60_000L }.forEach { Repo.store.removeReminder(it) }
            val due = list.firstOrNull { it.start in (now - 10 * 60_000L)..(now + 60_000L) }
            if (due != null && active == null) {
                active = due
                Repo.store.removeReminder(due)
            }
            delay(15_000)
        }
    }
    val r = active
    LaunchedEffect(r) {
        if (r != null) { delay(150); runCatching { focus.requestFocus() }; delay(30_000); active = null }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
        AnimatedVisibility(r != null, enter = slideInHorizontally { it } + fadeIn(), exit = slideOutHorizontally { it } + fadeOut()) {
            val rem = r ?: return@AnimatedVisibility
            Column(
                Modifier.padding(28.dp).width(380.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xF0151824)).padding(20.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.NotificationsActive, null, tint = EzColors.Accent2, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("STARTING NOW", style = EzType.Label.copy(color = EzColors.Accent2))
                }
                Spacer(Modifier.height(8.dp))
                Text(rem.title, style = EzType.H3, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("on ${rem.channelName}", style = EzType.Body)
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton("Watch now", {
                        Repo.channel(rem.channelId)?.let { Playback.playChannel(ctx, it) }
                        active = null
                    }, Modifier.focusRequester(focus), primary = true)
                    PillButton("Dismiss", { active = null })
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------- updates

@Composable
private fun UpdatePrompt() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var update by remember { mutableStateOf<Updater.Update?>(null) }
    var dismissed by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(-1f) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(4000)
        update = Updater.check()
    }
    val u = update
    if (u == null || dismissed) return
    LaunchedEffect(u) { delay(100); runCatching { focus.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Color(0xB3000000)), contentAlignment = Alignment.Center) {
        Column(Modifier.width(460.dp).clip(RoundedCornerShape(22.dp)).background(EzColors.Surface).padding(28.dp)) {
            Icon(Icons.Rounded.SystemUpdate, null, tint = EzColors.Accent2, modifier = Modifier.size(34.dp))
            Spacer(Modifier.height(12.dp))
            Text("Update available", style = EzType.H2)
            Text("Ezano TV ${u.versionName} is ready (you have ${Updater.currentVersion}).", style = EzType.Body)
            Spacer(Modifier.height(20.dp))
            if (progress >= 0f) {
                ProgressLine(progress, height = 6.dp)
                Spacer(Modifier.height(8.dp))
                Text("Downloading… ${(progress * 100).toInt()}%", style = EzType.Caption)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PillButton("Update now", {
                        progress = 0f
                        scope.launch {
                            runCatching { Updater.downloadAndInstall(ctx, u) { p -> progress = p } }
                                .onFailure { Toast.makeText(ctx, "Update failed: ${it.message}", Toast.LENGTH_LONG).show() }
                            progress = -1f
                            dismissed = true
                        }
                    }, Modifier.focusRequester(focus), primary = true)
                    PillButton("Later", { dismissed = true })
                }
            }
        }
    }
}
