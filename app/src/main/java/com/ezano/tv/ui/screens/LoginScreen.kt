package com.ezano.tv.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ezano.tv.R
import com.ezano.tv.data.Repo
import com.ezano.tv.data.SourceType
import com.ezano.tv.ui.components.Chip
import com.ezano.tv.ui.components.EzTextField
import com.ezano.tv.ui.components.PillButton
import com.ezano.tv.ui.theme.EzColors
import com.ezano.tv.ui.theme.EzType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Sign-in gate. The app does nothing until a working Xtream Codes login or M3U URL is provided.
 */
@Composable
fun LoginScreen(canCancel: Boolean, onDone: () -> Unit, onCancel: () -> Unit) {
    var type by remember { mutableStateOf(SourceType.XTREAM) }
    var name by remember { mutableStateOf("") }
    var server by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var m3u by remember { mutableStateOf("") }
    var epg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val firstField = remember { FocusRequester() }

    LaunchedEffect(Unit) { delay(120); runCatching { firstField.requestFocus() } }
    if (canCancel) BackHandler(enabled = !busy) { onCancel() }

    fun submit() {
        error = null
        val valid = when (type) {
            SourceType.XTREAM -> server.isNotBlank() && user.isNotBlank() && pass.isNotBlank()
            SourceType.M3U -> m3u.isNotBlank()
        }
        if (!valid) {
            error = if (type == SourceType.XTREAM) "Enter your server URL, username and password." else "Enter your M3U playlist URL."
            return
        }
        busy = true
        progress = "Connecting…"
        scope.launch {
            val r = Repo.login(
                name = name, type = type, server = server, username = user, password = pass,
                m3uUrl = m3u, epgUrl = epg, onProgress = { progress = it },
            )
            busy = false
            r.onSuccess { onDone() }.onFailure { error = it.message ?: "Sign-in failed." }
        }
    }

    Box(
        Modifier.fillMaxSize().background(
            Brush.linearGradient(listOf(Color(0xFF0B0A14), Color(0xFF151033), Color(0xFF07080C))),
        ),
    ) {
        // soft glow blobs
        Box(Modifier.size(520.dp).padding(0.dp).background(Brush.radialGradient(listOf(EzColors.Accent.copy(alpha = 0.28f), Color.Transparent))))
        Box(
            Modifier.align(Alignment.BottomEnd).size(460.dp)
                .background(Brush.radialGradient(listOf(EzColors.Accent2.copy(alpha = 0.16f), Color.Transparent))),
        )

        Row(Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 40.dp)) {
            // ---------------- brand side
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.ezano_mark), null, Modifier.size(64.dp))
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text("EZANO TV", style = EzType.H1.copy(letterSpacing = 5.sp))
                        Text("Your TV, beautifully.", style = EzType.Body.copy(color = EzColors.Accent2))
                    }
                }
                Spacer(Modifier.height(36.dp))
                Text("Sign in to start watching", style = EzType.Display.copy(fontSize = 34.sp, lineHeight = 40.sp))
                Spacer(Modifier.height(12.dp))
                Text(
                    "Enter the details from your IPTV provider. Ezano TV doesn't include any channels or content — " +
                        "it plays the service you already have.",
                    style = EzType.Body, modifier = Modifier.width(420.dp),
                )
                Spacer(Modifier.height(28.dp))
                Feature(Icons.Rounded.Search, "Find any game or show across every channel")
                Feature(Icons.Rounded.CalendarMonth, "Full TV guide with catch-up and reminders")
                Feature(Icons.Rounded.Speed, "Instant startup with smart caching")
                Feature(Icons.Rounded.Star, "Movies, series, favorites & continue watching")
            }

            // ---------------- form side
            Column(
                Modifier
                    .width(440.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xCC12141E))
                    .border(1.dp, EzColors.Stroke, RoundedCornerShape(24.dp))
                    .verticalScroll(rememberScrollState())
                    .padding(28.dp),
            ) {
                Text("Add your playlist", style = EzType.H2)
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Chip("Xtream Codes", type == SourceType.XTREAM, { type = SourceType.XTREAM; error = null })
                    Chip("M3U URL", type == SourceType.M3U, { type = SourceType.M3U; error = null })
                }
                Spacer(Modifier.height(18.dp))

                if (type == SourceType.XTREAM) {
                    EzTextField(server, { server = it }, "Server URL", Modifier.focusRequester(firstField),
                        placeholder = "http://example.com:8080", keyboardType = KeyboardType.Uri, leadingIcon = Icons.Rounded.Public)
                    Spacer(Modifier.height(12.dp))
                    EzTextField(user, { user = it }, "Username", placeholder = "Username", leadingIcon = Icons.Rounded.Person)
                    Spacer(Modifier.height(12.dp))
                    EzTextField(pass, { pass = it }, "Password", placeholder = "Password", password = true,
                        leadingIcon = Icons.Rounded.Lock, imeAction = ImeAction.Go, onSubmit = { submit() })
                } else {
                    EzTextField(m3u, { m3u = it }, "M3U playlist URL", Modifier.focusRequester(firstField),
                        placeholder = "http://…/playlist.m3u", keyboardType = KeyboardType.Uri, leadingIcon = Icons.Rounded.Link)
                    Spacer(Modifier.height(12.dp))
                    EzTextField(epg, { epg = it }, "TV guide (EPG) URL — optional", placeholder = "http://…/epg.xml.gz",
                        keyboardType = KeyboardType.Uri, leadingIcon = Icons.Rounded.CalendarMonth)
                }
                Spacer(Modifier.height(12.dp))
                EzTextField(name, { name = it }, "Name — optional", placeholder = "e.g. Home IPTV")

                AnimatedVisibility(error != null) {
                    Row(
                        Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(EzColors.Live.copy(alpha = 0.14f)).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.ErrorOutline, null, tint = EzColors.Live, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(error ?: "", style = EzType.BodySmall.copy(color = EzColors.TextPrimary))
                    }
                }

                Spacer(Modifier.height(20.dp))
                if (busy) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(44.dp)) {
                        CircularProgressIndicator(color = EzColors.Accent2, strokeWidth = 3.dp, modifier = Modifier.size(26.dp))
                        Spacer(Modifier.width(14.dp))
                        Text(progress, style = EzType.Title)
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PillButton("Sign in", { submit() }, primary = true)
                        if (canCancel) PillButton("Cancel", onCancel)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    if (type == SourceType.XTREAM) "Tip: you can also paste a full “get.php?username=…” link in the M3U tab."
                    else "Xtream “get.php” links are detected automatically for faster loading.",
                    style = EzType.Caption,
                )
            }
        }
    }
}

@Composable
private fun Feature(icon: ImageVector, text: String) {
    Row(Modifier.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.07f)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = EzColors.Accent2, modifier = Modifier.size(18.dp)) }
        Spacer(Modifier.width(14.dp))
        Text(text, style = EzType.Title.copy(color = EzColors.TextSecondary))
    }
}
