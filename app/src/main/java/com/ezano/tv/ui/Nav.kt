package com.ezano.tv.ui

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.staticCompositionLocalOf

/** App destinations. Top-level ones show the side navigation rail. */
sealed interface Screen {
    val topLevel: Boolean get() = false

    data object Home : Screen { override val topLevel = true }
    data object Live : Screen { override val topLevel = true }
    data object Guide : Screen { override val topLevel = true }
    data object Movies : Screen { override val topLevel = true }
    data object Shows : Screen { override val topLevel = true }
    data object Search : Screen { override val topLevel = true }
    data object Favorites : Screen { override val topLevel = true }
    data object Settings : Screen { override val topLevel = true }

    data class MovieDetail(val movieId: String) : Screen
    data class SeriesDetail(val seriesId: String) : Screen
    /** Event finder results page (from Live TV's "Find an event" or Search). */
    data class EventSearch(val query: String) : Screen
    /** Add another playlist from Settings. */
    data object AddPlaylist : Screen
}

class Navigator {
    val stack = mutableStateListOf<Screen>(Screen.Home)
    val current: Screen get() = stack.last()

    fun navigate(screen: Screen) {
        if (screen.topLevel) {
            // Switching tabs: keep stack shallow
            stack.clear(); stack.add(screen)
        } else stack.add(screen)
    }

    /** @return true if handled. */
    fun back(): Boolean {
        if (stack.size > 1) { stack.removeAt(stack.lastIndex); return true }
        if (current != Screen.Home) { stack.clear(); stack.add(Screen.Home); return true }
        return false
    }
}

val LocalNavigator = staticCompositionLocalOf<Navigator> { error("No navigator") }

/** Shared layout metrics. The nav rail floats over the left edge of every top-level screen. */
object Layout {
    /** Start padding for top-level screen content so it clears the collapsed nav rail. */
    val ContentStart = androidx.compose.ui.unit.Dp(104f)
    /** Collapsed rail width. */
    val RailWidth = androidx.compose.ui.unit.Dp(80f)
}
