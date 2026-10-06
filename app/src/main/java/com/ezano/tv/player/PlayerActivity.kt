package com.ezano.tv.player

import android.os.Bundle
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import com.ezano.tv.ui.theme.EzanoTheme
import kotlinx.coroutines.delay

/**
 * Full-screen player for live channels, movies, episodes and catch-up.
 * What to play comes from [Playback.pending]. The ExoPlayer instance lives only between
 * onStart and onStop so Fire TV never keeps decoding in the background.
 */
class PlayerActivity : ComponentActivity() {

    private var controller: PlayerController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val item = Playback.pending
        if (item == null) {
            finish()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterImmersive()
        val c = PlayerController(this, item, lifecycleScope)
        controller = c
        setContent {
            EzanoTheme {
                PlayerScreen(c)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        controller?.start()
    }

    override fun onStop() {
        controller?.stop()
        super.onStop()
    }

    override fun onDestroy() {
        controller?.destroy()
        controller = null
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersive()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val c = controller
        if (c != null && c.handleKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    private fun enterImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insets = WindowInsetsControllerCompat(window, window.decorView)
        insets.hide(WindowInsetsCompat.Type.systemBars())
        insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}

@Composable
private fun PlayerScreen(c: PlayerController) {
    BackHandler { c.onBack() }

    // When nothing interactive is on screen, the root holds focus so no stale button keeps it.
    val interactive = c.isInteractive
    LaunchedEffect(interactive) {
        if (!interactive) {
            delay(60)
            runCatching { c.rootFocus.requestFocus() }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(c.rootFocus)
            .focusable(),
    ) {
        VideoSurface(c)

        if (c.isLive) {
            AnimatedVisibility(
                visible = c.bannerVisible,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = fadeIn(tween(220)) + slideInVertically(tween(260)) { it / 4 },
                exit = fadeOut(tween(260)) + slideOutVertically(tween(260)) { it / 4 },
            ) {
                LiveBanner(c)
            }
            AnimatedVisibility(
                visible = c.bannerVisible && c.numberEntry.isEmpty(),
                modifier = Modifier.align(Alignment.TopCenter),
                enter = fadeIn(tween(220)),
                exit = fadeOut(tween(260)),
            ) {
                ClockBadge()
            }
            AnimatedVisibility(
                visible = c.numberEntry.isNotEmpty(),
                modifier = Modifier.align(Alignment.TopEnd),
                enter = fadeIn(tween(120)) + slideInHorizontally(tween(160)) { it / 3 },
                exit = fadeOut(tween(200)) + slideOutHorizontally(tween(200)) { it / 3 },
            ) {
                NumberEntryBadge(c)
            }
        } else {
            AnimatedVisibility(
                visible = c.overlayVisible,
                enter = fadeIn(tween(220)),
                exit = fadeOut(tween(280)),
            ) {
                VodOverlay(c)
            }
            AnimatedVisibility(
                visible = c.seekHud && !c.overlayVisible && c.panel == null,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = fadeIn(tween(150)) + slideInVertically(tween(200)) { it / 3 },
                exit = fadeOut(tween(250)),
            ) {
                SeekHud(c)
            }
        }

        BufferingIndicator(c, Modifier.align(Alignment.Center))

        PanelHost(c)

        val nextUp = rememberLast(c.nextUp)
        AnimatedVisibility(
            visible = c.nextUp != null,
            modifier = Modifier.align(Alignment.BottomEnd),
            enter = fadeIn(tween(250)) + slideInHorizontally(tween(300)) { it / 2 },
            exit = fadeOut(tween(200)) + slideOutHorizontally(tween(250)) { it / 2 },
        ) {
            if (nextUp != null) NextUpCard(c, nextUp)
        }

        val error = rememberLast(c.errorMessage)
        AnimatedVisibility(
            visible = c.errorMessage != null,
            enter = fadeIn(tween(220)),
            exit = fadeOut(tween(200)),
        ) {
            if (error != null) ErrorCard(c, error)
        }
    }
}

@Composable
private fun VideoSurface(c: PlayerController) {
    val player = c.player
    val resizeMode = when (c.aspect) {
        1 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        2 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    }
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                setKeepContentOnPlayerReset(true)
                isFocusable = false
                isFocusableInTouchMode = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                subtitleView?.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * 1.1f)
            }
        },
        modifier = Modifier
            .fillMaxSize()
            .focusProperties { canFocus = false },
        update = { view ->
            if (view.player !== player) view.player = player
            if (view.resizeMode != resizeMode) view.resizeMode = resizeMode
        },
        onRelease = { view -> view.player = null },
    )
}
