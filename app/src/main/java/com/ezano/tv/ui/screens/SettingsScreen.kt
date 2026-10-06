package com.ezano.tv.ui.screens

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ezano.tv.data.AppSettings
import com.ezano.tv.data.ContentType
import com.ezano.tv.data.EpgState
import com.ezano.tv.data.Repo
import com.ezano.tv.data.Updater
import com.ezano.tv.ui.Layout
import com.ezano.tv.ui.LocalNavigator
import com.ezano.tv.ui.Screen
import com.ezano.tv.ui.components.EzTextField
import com.ezano.tv.ui.components.FocusSurface
import com.ezano.tv.ui.components.PillButton
import com.ezano.tv.ui.theme.EzColors
import com.ezano.tv.ui.theme.EzType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class SettingsSection(val label: String, val icon: ImageVector) {
    Playlists("Playlists", Icons.Rounded.PlaylistPlay),
    Playback("Playback", Icons.Rounded.PlayCircle),
    Guide("TV Guide", Icons.Rounded.CalendarMonth),
    Content("Content & Categories", Icons.Rounded.Visibility),
    Parental("Parental Controls", Icons.Rounded.Lock),
    About("About & Updates", Icons.Rounded.Info),
}

@Composable
fun SettingsScreen() {
    var section by remember { mutableStateOf(SettingsSection.Playlists) }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { firstFocus.requestFocus() } }

    Row(Modifier.fillMaxSize().padding(start = Layout.ContentStart, top = 40.dp, end = 48.dp)) {
        Column(Modifier.width(250.dp).fillMaxHeight()) {
            Text("Settings", style = EzType.H1)
            Spacer(Modifier.height(24.dp))
            SettingsSection.entries.forEachIndexed { i, s ->
                FocusSurface(
                    onClick = { section = s },
                    onFocus = { if (it) section = s },
                    modifier = Modifier.fillMaxWidth().height(50.dp).padding(vertical = 3.dp)
                        .then(if (i == 0) Modifier.focusRequester(firstFocus) else Modifier),
                    shape = RoundedCornerShape(12.dp),
                    focusedScale = 1.03f,
                    color = if (section == s) Color.White.copy(alpha = 0.08f) else Color.Transparent,
                    focusedColor = Color.White,
                    showBorder = false, glow = false,
                ) { focused ->
                    val tint = if (focused) Color(0xFF0B0B10) else if (section == s) EzColors.TextPrimary else EzColors.TextSecondary
                    Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(s.icon, null, tint = tint, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(s.label, style = EzType.Title.copy(color = tint), maxLines = 1)
                    }
                }
            }
        }
        Spacer(Modifier.width(36.dp))
        Box(Modifier.weight(1f).fillMaxHeight()) {
            when (section) {
                SettingsSection.Playlists -> PlaylistsSection()
                SettingsSection.Playback -> PlaybackSection()
                SettingsSection.Guide -> GuideSection()
                SettingsSection.Content -> ContentSection()
                SettingsSection.Parental -> ParentalSection()
                SettingsSection.About -> AboutSection()
            }
        }
    }
}

// ------------------------------------------------------------------ building blocks

@Composable
private fun SettingRow(
    title: String,
    subtitle: String = "",
    value: String? = null,
    toggle: Boolean? = null,
    onClick: () -> Unit,
) {
    FocusSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        focusedScale = 1.02f,
        color = EzColors.Surface,
        focusedColor = EzColors.SurfaceFocus,
        glow = false,
    ) { _ ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = EzType.Title)
                if (subtitle.isNotBlank()) Text(subtitle, style = EzType.BodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (value != null) {
                Spacer(Modifier.width(16.dp))
                Text(value, style = EzType.Title.copy(color = EzColors.Accent2))
            }
            if (toggle != null) {
                Spacer(Modifier.width(16.dp))
                Toggle(toggle)
            }
        }
    }
}

@Composable
private fun Toggle(on: Boolean) {
    Box(
        Modifier.width(46.dp).height(26.dp).clip(RoundedCornerShape(50))
            .background(if (on) EzColors.Accent else Color.White.copy(alpha = 0.18f)),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.padding(3.dp).size(20.dp).clip(CircleShape).background(Color.White))
    }
}

@Composable
private fun Header(text: String, caption: String = "") {
    Column(Modifier.padding(top = 4.dp, bottom = 10.dp)) {
        Text(text, style = EzType.H2)
        if (caption.isNotBlank()) Text(caption, style = EzType.BodySmall)
    }
}

private fun update(t: (AppSettings) -> AppSettings) = Repo.store.updateSettings(t)

// ------------------------------------------------------------------ sections

@Composable
private fun PlaylistsSection() {
    val playlists by Repo.store.playlists.collectAsState()
    val activeId by Repo.store.activeId.collectAsState()
    val index by Repo.index.collectAsState()
    val nav = LocalNavigator.current
    val ctx = LocalContext.current
    var confirmRemove by remember { mutableStateOf<String?>(null) }
    val fmt = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }

    LazyColumn(contentPadding = PaddingValues(bottom = 40.dp)) {
        item { Header("Playlists", "Switch between providers or add another login.") }
        items(playlists, key = { it.id }) { p ->
            val active = p.id == (activeId ?: playlists.firstOrNull()?.id)
            val lib = index?.lib?.takeIf { it.playlistId == p.id }
            val sub = buildString {
                append(if (p.type.name == "XTREAM") "Xtream Codes · ${p.server}" else "M3U · ${p.m3uUrl.take(48)}")
                if (lib != null) {
                    append("\n${lib.channels.size} channels · ${lib.movies.size} movies · ${lib.series.size} series")
                    if (lib.accountExpiry > 0) append(" · expires ${fmt.format(Date(lib.accountExpiry))}")
                    if (lib.maxConnections > 0) append(" · ${lib.maxConnections} connections")
                }
            }
            FocusSurface(
                onClick = { if (!active) { Repo.switchPlaylist(p.id); Toast.makeText(ctx, "Switched to ${p.name}", Toast.LENGTH_SHORT).show() } },
                onLongClick = { confirmRemove = p.id },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                shape = RoundedCornerShape(14.dp), focusedScale = 1.02f, glow = false,
            ) { _ ->
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = EzType.H3)
                        Text(sub, style = EzType.BodySmall, maxLines = 3)
                    }
                    if (active) Icon(Icons.Rounded.CheckCircle, "Active", tint = EzColors.Success, modifier = Modifier.size(26.dp))
                }
            }
            if (confirmRemove == p.id) {
                Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Remove ${p.name}?", style = EzType.Title)
                    PillButton("Remove", { confirmRemove = null; Repo.logout(p.id) }, primary = true)
                    PillButton("Cancel", { confirmRemove = null })
                }
            }
        }
        item {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton("Add playlist", { nav.navigate(Screen.AddPlaylist) }, primary = true)
                PillButton("Refresh now", {
                    Repo.loadActive(forceRefresh = true)
                    Toast.makeText(ctx, "Refreshing playlist and guide…", Toast.LENGTH_SHORT).show()
                })
            }
            Spacer(Modifier.height(10.dp))
            Text("Long-press OK on a playlist to remove it.", style = EzType.Caption)
        }
    }
}

@Composable
private fun PlaybackSection() {
    val s by Repo.store.settings.collectAsState()
    LazyColumn(contentPadding = PaddingValues(bottom = 40.dp)) {
        item { Header("Playback") }
        item {
            SettingRow("Live stream format", "MPEG-TS is the most compatible. Try HLS if channels stutter or fail.",
                value = if (s.liveFormat == "m3u8") "HLS (.m3u8)" else "MPEG-TS (.ts)") {
                update { it.copy(liveFormat = if (it.liveFormat == "m3u8") "ts" else "m3u8") }
                Repo.reapplyLiveFormat()
            }
        }
        item {
            val opts = listOf(15, 30, 60, 120)
            SettingRow("Buffer size", "Larger buffers ride out slow connections but start a little slower.", value = "${s.bufferSeconds}s") {
                update { it.copy(bufferSeconds = opts[(opts.indexOf(it.bufferSeconds) + 1).mod(opts.size)]) }
            }
        }
        item {
            val names = listOf("Fit", "Fill / Stretch", "Zoom / Crop")
            SettingRow("Default aspect ratio", value = names[s.defaultAspect.coerceIn(0, 2)]) {
                update { it.copy(defaultAspect = (it.defaultAspect + 1) % 3) }
            }
        }
        item { SettingRow("Autoplay next episode", toggle = s.autoplayNext) { update { it.copy(autoplayNext = !it.autoplayNext) } } }
        item { SettingRow("Subtitles on by default", toggle = s.subtitlesEnabled) { update { it.copy(subtitlesEnabled = !it.subtitlesEnabled) } } }
        item {
            val langs = listOf("" to "Auto", "en" to "English", "es" to "Spanish", "fr" to "French", "de" to "German", "it" to "Italian",
                "pt" to "Portuguese", "ar" to "Arabic", "tr" to "Turkish", "nl" to "Dutch", "pl" to "Polish", "hi" to "Hindi")
            val cur = langs.indexOfFirst { it.first == s.preferredAudioLang }.coerceAtLeast(0)
            SettingRow("Preferred audio language", value = langs[cur].second) {
                update { it.copy(preferredAudioLang = langs[(cur + 1) % langs.size].first) }
            }
            val curSub = langs.indexOfFirst { it.first == s.preferredSubtitleLang }.coerceAtLeast(0)
            SettingRow("Preferred subtitle language", value = langs[curSub].second) {
                update { it.copy(preferredSubtitleLang = langs[(curSub + 1) % langs.size].first) }
            }
        }
        item { SettingRow("Use 24-hour clock", toggle = s.use24h) { update { it.copy(use24h = !it.use24h) } } }
    }
}

@Composable
private fun GuideSection() {
    val s by Repo.store.settings.collectAsState()
    val state by Repo.epgState.collectAsState()
    val ctx = LocalContext.current
    val status = when (val st = state) {
        is EpgState.Loading -> st.message
        is EpgState.Ready -> "${st.programmes} programmes loaded"
        is EpgState.Failed -> st.message
        EpgState.Idle -> "Not loaded yet"
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 40.dp)) {
        item { Header("TV Guide", status) }
        item {
            SettingRow("Refresh TV guide now", "Downloads the latest programme information.") {
                Repo.refreshEpg(force = true)
                Toast.makeText(ctx, "Updating TV guide…", Toast.LENGTH_SHORT).show()
            }
        }
        item {
            val v = s.epgTimeShiftMinutes
            SettingRow("Guide time offset", "Fix guides that show programmes at the wrong time. Press OK to cycle.",
                value = if (v == 0) "None" else "%+.1fh".format(Locale.US, v / 60f)) {
                val next = if (v >= 720) -720 else v + 30
                update { it.copy(epgTimeShiftMinutes = next) }
            }
        }
        item {
            val opts = listOf(24, 48, 72, 120, 168)
            SettingRow("Guide days ahead", "More days use more memory.", value = "${s.epgHoursAhead / 24} days") {
                update { it.copy(epgHoursAhead = opts[(opts.indexOf(it.epgHoursAhead) + 1).mod(opts.size)]) }
            }
        }
        item {
            val p = Repo.store.activePlaylist
            Spacer(Modifier.height(10.dp))
            Text(
                if (!p?.epgUrl.isNullOrBlank()) "Guide source: ${p?.epgUrl}"
                else if (p?.type?.name == "XTREAM") "Guide source: provider (xmltv.php)" else "Guide source: from playlist header",
                style = EzType.Caption,
            )
        }
    }
}

@Composable
private fun ContentSection() {
    val s by Repo.store.settings.collectAsState()
    val index by Repo.index.collectAsState()
    val lib = index?.lib
    var tab by remember { mutableStateOf(ContentType.LIVE) }
    val cats = when (tab) {
        ContentType.LIVE -> lib?.liveCategories
        ContentType.MOVIE -> lib?.movieCategories
        ContentType.SERIES -> lib?.seriesCategories
    } ?: emptyList()
    LazyColumn(contentPadding = PaddingValues(bottom = 40.dp)) {
        item { Header("Content & Categories", "Hide categories you never watch. They disappear everywhere in the app.") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 10.dp)) {
                com.ezano.tv.ui.components.Chip("Live TV", tab == ContentType.LIVE, { tab = ContentType.LIVE })
                com.ezano.tv.ui.components.Chip("Movies", tab == ContentType.MOVIE, { tab = ContentType.MOVIE })
                com.ezano.tv.ui.components.Chip("Series", tab == ContentType.SERIES, { tab = ContentType.SERIES })
            }
        }
        // distinctBy: some panels list the same category id twice, and duplicate LazyColumn keys crash.
        items(cats.filter { s.showAdult || !Repo.isAdult(it) }.distinctBy { "${it.type}:${it.id}" }, key = { "${it.type}:${it.id}" }) { c ->
            val key = "${c.type}:${c.id}"
            val visible = key !in s.hiddenCategories
            SettingRow(c.name, toggle = visible) {
                update { st -> st.copy(hiddenCategories = if (visible) st.hiddenCategories + key else st.hiddenCategories - key) }
            }
        }
        item {
            Spacer(Modifier.height(10.dp))
            SettingRow("Clear watch history", "Removes Continue Watching and recently watched channels.") { Repo.store.clearHistory() }
        }
    }
}

@Composable
private fun ParentalSection() {
    val s by Repo.store.settings.collectAsState()
    val ctx = LocalContext.current
    var pinInput by remember { mutableStateOf("") }
    var asking by remember { mutableStateOf(false) }
    var settingPin by remember { mutableStateOf(false) }
    var newPin by remember { mutableStateOf("") }

    Column {
        Header("Parental Controls", "Adult categories are hidden unless you turn them on. Protect them with a PIN.")
        SettingRow("Show adult categories", if (s.parentalPin.isNotBlank()) "PIN protected" else "No PIN set", toggle = s.showAdult) {
            if (s.showAdult) update { it.copy(showAdult = false) }
            else if (s.parentalPin.isBlank()) update { it.copy(showAdult = true) }
            else { asking = true; pinInput = "" }
        }
        if (asking) {
            Spacer(Modifier.height(8.dp))
            EzTextField(pinInput, { if (it.length <= 8) pinInput = it.filter(Char::isDigit) }, "Enter PIN",
                keyboardType = KeyboardType.NumberPassword, password = true, autoEdit = true, onSubmit = {
                    if (pinInput == s.parentalPin) { update { it.copy(showAdult = true) }; asking = false }
                    else Toast.makeText(ctx, "Wrong PIN", Toast.LENGTH_SHORT).show()
                })
        }
        SettingRow(if (s.parentalPin.isBlank()) "Set a PIN" else "Change PIN") { settingPin = true; newPin = "" }
        if (settingPin) {
            Spacer(Modifier.height(8.dp))
            EzTextField(newPin, { if (it.length <= 8) newPin = it.filter(Char::isDigit) }, "New PIN (4–8 digits)",
                keyboardType = KeyboardType.NumberPassword, password = true, autoEdit = true, onSubmit = {
                    if (newPin.length >= 4) {
                        update { it.copy(parentalPin = newPin, showAdult = false) }
                        settingPin = false
                        Toast.makeText(ctx, "PIN saved", Toast.LENGTH_SHORT).show()
                    } else Toast.makeText(ctx, "PIN must be at least 4 digits", Toast.LENGTH_SHORT).show()
                })
        }
        if (s.parentalPin.isNotBlank()) {
            SettingRow("Remove PIN") { update { it.copy(parentalPin = "", showAdult = false) } }
        }
    }
}

@Composable
private fun AboutSection() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }
    var update by remember { mutableStateOf<Updater.Update?>(null) }
    var progress by remember { mutableStateOf(-1f) }
    Column {
        Header("About Ezano TV", "Version ${Updater.currentVersion}")
        SettingRow("Check for updates", status.ifBlank { "Downloads new versions straight from GitHub." }) {
            status = "Checking…"
            scope.launch {
                val u = Updater.check()
                update = u
                status = if (u == null) "You're on the latest version." else "Version ${u.versionName} is available."
            }
        }
        val u = update
        if (u != null) {
            if (progress >= 0f) {
                Text("Downloading… ${(progress * 100).toInt()}%", style = EzType.Title, modifier = Modifier.padding(12.dp))
            } else {
                Spacer(Modifier.height(6.dp))
                PillButton("Install ${u.versionName}", {
                    progress = 0f
                    scope.launch {
                        runCatching { Updater.downloadAndInstall(ctx, u) { progress = it } }
                            .onFailure { Toast.makeText(ctx, "Update failed: ${it.message}", Toast.LENGTH_LONG).show() }
                        progress = -1f
                    }
                }, primary = true)
            }
        }
        Spacer(Modifier.height(18.dp))
        Text(
            "Ezano TV is a media player. It does not provide or include any channels, movies or other content. " +
                "Use it only with services you are authorized to access.",
            style = EzType.Caption, modifier = Modifier.width(520.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text("Remote tips: long-press OK to add anything to My List · in the player, ▲/▼ change channel, OK opens the channel list, number keys jump to a channel.",
            style = EzType.Caption, modifier = Modifier.width(520.dp))
    }
}
