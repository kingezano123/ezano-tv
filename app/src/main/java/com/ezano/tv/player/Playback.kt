package com.ezano.tv.player

import android.content.Context
import android.content.Intent
import com.ezano.tv.data.Channel
import com.ezano.tv.data.Episode
import com.ezano.tv.data.Movie
import com.ezano.tv.data.Programme
import com.ezano.tv.data.Repo
import com.ezano.tv.data.Series

/** What the player should play. Passed to PlayerActivity through [Playback.pending]. */
sealed interface PlayItem {
    /** Live channel. [zapList] = channels for channel up/down (usually the current category). */
    data class Live(val channel: Channel, val zapList: List<Channel>) : PlayItem
    data class Vod(val movie: Movie, val startMs: Long) : PlayItem
    data class Ep(val series: Series, val episode: Episode, val playlist: List<Episode>, val startMs: Long) : PlayItem
    data class Catchup(val channel: Channel, val programme: Programme, val url: String) : PlayItem
}

object Playback {
    @Volatile var pending: PlayItem? = null

    private fun launch(context: Context, item: PlayItem) {
        pending = item
        context.startActivity(Intent(context, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK.takeIf { context !is android.app.Activity } ?: 0))
    }

    fun playChannel(context: Context, channel: Channel, zapList: List<Channel> = Repo.channels(channel.categoryId)) =
        launch(context, PlayItem.Live(channel, zapList.ifEmpty { listOf(channel) }))

    fun playMovie(context: Context, movie: Movie, fromStart: Boolean = false) =
        launch(context, PlayItem.Vod(movie, if (fromStart) 0L else Repo.store.resumePosition("movie:${movie.id}")))

    fun playEpisode(context: Context, series: Series, episode: Episode, playlist: List<Episode>, fromStart: Boolean = false) =
        launch(context, PlayItem.Ep(series, episode, playlist, if (fromStart) 0L else Repo.store.resumePosition("ep:${series.id}:${episode.id}")))

    fun playCatchup(context: Context, channel: Channel, programme: Programme): Boolean {
        val url = Repo.catchupUrl(channel, programme) ?: return false
        launch(context, PlayItem.Catchup(channel, programme, url))
        return true
    }
}
