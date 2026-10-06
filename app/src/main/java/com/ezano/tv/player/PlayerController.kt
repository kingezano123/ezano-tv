package com.ezano.tv.player

import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.UnrecognizedInputFormatException
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import com.ezano.tv.data.Channel
import com.ezano.tv.data.ContentType
import com.ezano.tv.data.Episode
import com.ezano.tv.data.HistoryEntry
import com.ezano.tv.data.Net
import com.ezano.tv.data.Programme
import com.ezano.tv.data.Repo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Side panels the player can show. [Channels] slides in from the left, the rest from the right. */
enum class Panel { AudioSubs, Speed, Aspect, Episodes, Channels, Programmes }

enum class NextMode {
    /** Shown during the last seconds of an episode (credits). */
    Credits,
    /** Episode ended, next one starts automatically when the countdown reaches zero. */
    Countdown,
    /** Episode ended, autoplay is off. */
    Ended,
}

data class NextUp(val episode: Episode, val mode: NextMode)

/**
 * Owns the ExoPlayer instance and all player UI state. All methods run on the main thread
 * (ExoPlayer callbacks and lifecycleScope both use the main looper).
 */
class PlayerController(
    private val activity: ComponentActivity,
    initial: PlayItem,
    private val scope: CoroutineScope,
) : Player.Listener {

    val rootFocus = FocusRequester()

    // ------------------------------------------------------------------ what is playing
    var item: PlayItem by mutableStateOf(initial)
        private set
    var player: ExoPlayer? by mutableStateOf(null)
        private set

    // ------------------------------------------------------------------ playback mirrors
    var isPlaying by mutableStateOf(false)
        private set
    var playWhenReady by mutableStateOf(true)
        private set
    var playbackState by mutableIntStateOf(Player.STATE_IDLE)
        private set
    var positionMs by mutableLongStateOf(0L)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set
    var bufferedMs by mutableLongStateOf(0L)
        private set
    var tracks: Tracks by mutableStateOf(Tracks.EMPTY)
        private set
    var videoWidth by mutableIntStateOf(0)
        private set
    var videoHeight by mutableIntStateOf(0)
        private set
    var errorMessage: String? by mutableStateOf(null)
        private set
    var reconnectInfo: String? by mutableStateOf(null)
        private set

    // ------------------------------------------------------------------ user settings for this session
    var speed by mutableFloatStateOf(1f)
        private set
    /** 0 fit, 1 fill, 2 zoom. */
    var aspect by mutableIntStateOf(Repo.settings.defaultAspect.coerceIn(0, 2))
        private set

    // ------------------------------------------------------------------ overlay state
    var overlayVisible by mutableStateOf(false)
        private set
    var panel: Panel? by mutableStateOf(null)
        private set
    /** Seekbar scrub position while the seekbar is focused (null = not scrubbing). */
    var scrubMs: Long? by mutableStateOf(null)
        private set
    /** Pending target of quick seeks (D-pad while overlay hidden, FF/RW keys). */
    var seekPreviewMs: Long? by mutableStateOf(null)
        private set
    var seekOriginMs by mutableLongStateOf(0L)
        private set
    var seekHud by mutableStateOf(false)
        private set

    // live
    var bannerVisible by mutableStateOf(false)
        private set
    var liveActions by mutableStateOf(false)
        private set
    /** Channel shown in the banner while zapping (before the debounced switch happens). */
    var zapChannel: Channel? by mutableStateOf(null)
        private set
    var numberEntry by mutableStateOf("")
        private set
    var previousChannel: Channel? by mutableStateOf(null)
        private set

    // episodes
    var nextUp: NextUp? by mutableStateOf(null)
        private set
    var countdown by mutableIntStateOf(10)
        private set

    // ------------------------------------------------------------------ internals
    private var startMs: Long = when (initial) {
        is PlayItem.Vod -> initial.startMs
        is PlayItem.Ep -> initial.startMs
        else -> 0L
    }
    private var hasStarted = false
    private var currentUrl = ""
    private var forceHls = false
    private var formatRetried = false
    private var retryAttempts = 0
    private var creditsDismissed = false
    /** The live channel we came from when watching catch-up inside the player. */
    private var liveOrigin: PlayItem.Live? = null
    private var savedParams: TrackSelectionParameters? = null

    private var tickerJob: Job? = null
    private var retryJob: Job? = null
    private var stableJob: Job? = null
    private var autoHideJob: Job? = null
    private var seekJob: Job? = null
    private var zapJob: Job? = null
    private var numberJob: Job? = null
    private var countdownJob: Job? = null

    private val consumedKeys = HashSet<Int>()
    private var centerPending = false
    private var centerLong = false

    // ------------------------------------------------------------------ derived
    val isLive: Boolean get() = item is PlayItem.Live
    val isSeekable: Boolean get() = item !is PlayItem.Live

    /** Channel of a live or catch-up item. */
    val currentChannel: Channel?
        get() = when (val it = item) {
            is PlayItem.Live -> it.channel
            is PlayItem.Catchup -> it.channel
            else -> null
        }

    /** Channel shown in the live banner (the zap target while zapping). */
    val bannerChannel: Channel? get() = zapChannel ?: (item as? PlayItem.Live)?.channel

    /** True when Compose controls should receive D-pad keys instead of the player shortcuts. */
    val isInteractive: Boolean
        get() = errorMessage != null || panel != null || nextUp != null ||
            (!isLive && overlayVisible) || (isLive && liveActions)

    val title: String
        get() = when (val it = item) {
            is PlayItem.Live -> it.channel.name
            is PlayItem.Vod -> it.movie.name
            is PlayItem.Ep -> it.series.name
            is PlayItem.Catchup -> it.programme.title.ifBlank { it.channel.name }
        }

    val subtitle: String
        get() = when (val it = item) {
            is PlayItem.Live -> ""
            is PlayItem.Vod -> it.movie.year
            is PlayItem.Ep -> episodeLabel(it.episode)
            is PlayItem.Catchup -> it.channel.name
        }

    fun nextEpisode(): Episode? {
        val ep = item as? PlayItem.Ep ?: return null
        val idx = ep.playlist.indexOfFirst { it.id == ep.episode.id }
        if (idx < 0) return null
        return ep.playlist.getOrNull(idx + 1)
    }

    // ------------------------------------------------------------------ lifecycle

    fun start() {
        if (player != null) return
        val p = buildPlayer()
        savedParams?.let { params -> runCatching { p.trackSelectionParameters = params } }
        p.addListener(this)
        p.setPlaybackSpeed(if (isSeekable) speed else 1f)
        player = p
        prepare(startMs)
        startTicker()
    }

    fun stop() {
        val p = player ?: return
        saveProgress()
        if (hasStarted && isSeekable) startMs = p.currentPosition
        savedParams = p.trackSelectionParameters
        tickerJob?.cancel()
        retryJob?.cancel()
        stableJob?.cancel()
        seekJob?.cancel()
        countdownJob?.cancel()
        if (nextUp?.mode == NextMode.Countdown) nextUp = null
        seekPreviewMs = null
        seekHud = false
        reconnectInfo = null
        p.removeListener(this)
        p.release()
        player = null
        isPlaying = false
        playbackState = Player.STATE_IDLE
    }

    fun destroy() {
        stop()
        listOf(autoHideJob, zapJob, numberJob, countdownJob, seekJob, retryJob, stableJob, tickerJob).forEach { it?.cancel() }
    }

    private fun buildPlayer(): ExoPlayer {
        val s = Repo.settings
        val ua = Repo.store.activePlaylist?.userAgent?.takeIf { it.isNotBlank() } ?: Net.DEFAULT_UA
        // Share Net's connection pool, but never push video segments through the API disk cache.
        val client = Net.client.newBuilder().cache(null).build()
        val dataSourceFactory = OkHttpDataSource.Factory(client).setUserAgent(ua)
        val extractors = DefaultExtractorsFactory()
            .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory, extractors)
        val renderersFactory = DefaultRenderersFactory(activity)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setEnableDecoderFallback(true)
        val maxBufferMs = maxOf(s.bufferSeconds * 1000, 30_000)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, maxBufferMs, 1_500, 3_000)
            .build()
        val trackSelector = DefaultTrackSelector(activity)
        trackSelector.setParameters(
            trackSelector.buildUponParameters()
                .setPreferredAudioLanguage(s.preferredAudioLang.ifBlank { null })
                .setPreferredTextLanguage(s.preferredSubtitleLang.ifBlank { null })
                .setSelectUndeterminedTextLanguage(s.subtitlesEnabled)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !s.subtitlesEnabled)
                .build()
        )
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        return ExoPlayer.Builder(activity, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
    }

    // ------------------------------------------------------------------ loading

    private fun urlOf(i: PlayItem): String = when (i) {
        is PlayItem.Live -> i.channel.url
        is PlayItem.Vod -> i.movie.url
        is PlayItem.Ep -> i.episode.url
        is PlayItem.Catchup -> i.url
    }

    private fun prepare(position: Long) {
        val p = player ?: return
        retryJob?.cancel()
        val url = urlOf(item)
        if (url != currentUrl) {
            currentUrl = url
            forceHls = false
            formatRetried = false
        }
        errorMessage = null
        hasStarted = false
        val mime = if (forceHls) MimeTypes.APPLICATION_M3U8 else guessMime(url)
        val builder = MediaItem.Builder().setUri(url)
        if (mime != null) builder.setMimeType(mime)
        val mediaItem = builder.build()
        if (isSeekable && position > 0) p.setMediaItem(mediaItem, position) else p.setMediaItem(mediaItem)
        p.prepare()
        p.playWhenReady = true
    }

    /** Switch to another item inside the same player instance. */
    private fun playItem(newItem: PlayItem, start: Long) {
        saveProgress()
        item = newItem
        startMs = start
        retryAttempts = 0
        reconnectInfo = null
        countdownJob?.cancel()
        nextUp = null
        creditsDismissed = false
        scrubMs = null
        seekPreviewMs = null
        seekHud = false
        tracks = Tracks.EMPTY
        videoWidth = 0
        videoHeight = 0
        durationMs = 0L
        positionMs = start
        player?.let { p ->
            p.setPlaybackSpeed(if (isSeekable) speed else 1f)
            // Overrides point at the old stream's track groups; keep the language preferences only.
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().clearOverrides().build()
        }
        prepare(start)
    }

    private fun resumePosition(): Long {
        if (!isSeekable) return 0L
        val p = player
        return if (hasStarted && p != null) p.currentPosition else startMs
    }

    fun retry() {
        errorMessage = null
        retryAttempts = 0
        formatRetried = false
        forceHls = false
        val pos = resumePosition()
        startMs = pos
        prepare(pos)
    }

    // ------------------------------------------------------------------ Player.Listener

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        this.isPlaying = isPlaying
        stableJob?.cancel()
        if (isPlaying) {
            reconnectInfo = null
            // Reset the reconnect backoff once playback has been stable for a while.
            stableJob = scope.launch {
                delay(8_000)
                retryAttempts = 0
            }
            if (overlayVisible) scheduleAutoHide()
        }
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        this.playWhenReady = playWhenReady
        if (!playWhenReady) saveProgress()
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        this.playbackState = playbackState
        val p = player ?: return
        when (playbackState) {
            Player.STATE_READY -> {
                val d = p.duration
                durationMs = if (d > 0) d else 0L
                if (!hasStarted) {
                    hasStarted = true
                    errorMessage = null
                    reconnectInfo = null
                    (item as? PlayItem.Live)?.let { recordLive(it.channel) }
                }
            }
            Player.STATE_ENDED -> onEnded()
            else -> Unit
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        val p = player ?: return
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            p.seekToDefaultPosition()
            p.prepare()
            return
        }
        val pos = resumePosition()
        startMs = pos
        // Many panels serve HLS behind URLs without ".m3u8" – retry once forcing HLS.
        if (!formatRetried && !forceHls && guessMime(currentUrl) == null && isContainerError(error)) {
            formatRetried = true
            forceHls = true
            prepare(pos)
            return
        }
        // Forced HLS was wrong too: go back to letting ExoPlayer sniff the container.
        if (forceHls && (error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ||
                error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED)
        ) {
            forceHls = false
        }
        val retriable = isLive || isTransientNetworkError(error)
        if (!retriable || !tryReconnect()) showError(friendlyError(error))
    }

    override fun onTracksChanged(tracks: Tracks) {
        this.tracks = tracks
    }

    override fun onVideoSizeChanged(videoSize: VideoSize) {
        videoWidth = videoSize.width
        videoHeight = videoSize.height
    }

    // ------------------------------------------------------------------ errors & reconnect

    /** Schedules a retry with exponential backoff. Returns false when out of attempts. */
    private fun tryReconnect(): Boolean {
        val max = if (isLive) 5 else 3
        if (retryAttempts >= max) return false
        val wait = 1_000L shl retryAttempts
        retryAttempts++
        reconnectInfo = "Reconnecting… ($retryAttempts/$max)"
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(wait)
            prepare(startMs)
        }
        return true
    }

    private fun showError(message: String) {
        retryJob?.cancel()
        errorMessage = message
        reconnectInfo = null
        overlayVisible = false
        panel = null
        liveActions = false
        bannerVisible = false
        scrubMs = null
        countdownJob?.cancel()
        nextUp = null
    }

    private fun onEnded() {
        when (val it = item) {
            is PlayItem.Live -> if (!tryReconnect()) showError("The channel stopped streaming.")
            is PlayItem.Vod -> {
                if (!hasStarted) { showError("This video couldn't be played."); return }
                saveProgress(ended = true)
                activity.finish()
            }
            is PlayItem.Ep -> {
                if (!hasStarted) { showError("This episode couldn't be played."); return }
                saveProgress(ended = true)
                val next = nextEpisode()
                if (next == null) activity.finish() else showEndedNextUp(next)
            }
            is PlayItem.Catchup -> {
                if (!hasStarted) { showError("This recording couldn't be played."); return }
                if (liveOrigin != null) watchLive(it.channel) else activity.finish()
            }
        }
    }

    // ------------------------------------------------------------------ ticker & history

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            var n = 0
            while (isActive) {
                val p = player
                if (p != null) {
                    positionMs = p.currentPosition
                    bufferedMs = p.bufferedPosition
                    val d = p.duration
                    durationMs = if (d > 0) d else 0L
                    n++
                    if (n % 20 == 0) saveProgress() // every ~10s
                    checkCredits()
                }
                delay(500)
            }
        }
    }

    private fun saveProgress(ended: Boolean = false) {
        val p = player ?: return
        if (!hasStarted) return
        val d = p.duration.let { if (it > 0) it else 0L }
        val pos = if (ended && d > 0) d else p.currentPosition.coerceAtLeast(0L)
        when (val it = item) {
            is PlayItem.Vod -> Repo.store.record(
                HistoryEntry(
                    key = "movie:${it.movie.id}",
                    type = ContentType.MOVIE,
                    title = it.movie.name,
                    subtitle = it.movie.year,
                    image = it.movie.poster,
                    url = it.movie.url,
                    positionMs = pos,
                    durationMs = d,
                    refId = it.movie.id,
                )
            )
            is PlayItem.Ep -> Repo.store.record(
                HistoryEntry(
                    key = "ep:${it.series.id}:${it.episode.id}",
                    type = ContentType.SERIES,
                    title = it.series.name,
                    subtitle = episodeLabel(it.episode),
                    image = it.episode.image.ifBlank { it.series.poster },
                    url = it.episode.url,
                    positionMs = pos,
                    durationMs = d,
                    refId = it.series.id,
                )
            )
            else -> Unit
        }
    }

    private fun recordLive(ch: Channel) {
        val now = Repo.epg.value.now(ch)
        Repo.store.record(
            HistoryEntry(
                key = "live:${ch.id}",
                type = ContentType.LIVE,
                title = ch.name,
                subtitle = now?.title ?: "",
                image = ch.logo,
                url = ch.url,
                positionMs = 0L,
                durationMs = 0L,
                refId = ch.id,
            )
        )
    }

    // ------------------------------------------------------------------ transport

    fun togglePlay() {
        val p = player ?: return
        when {
            errorMessage != null -> retry()
            p.playbackState == Player.STATE_ENDED -> {
                p.seekTo(0L)
                p.playWhenReady = true
            }
            else -> p.playWhenReady = !p.playWhenReady
        }
    }

    private fun maxSeek(): Long = if (durationMs > 0) (durationMs - 1_000L).coerceAtLeast(0L) else Long.MAX_VALUE

    fun seekTo(ms: Long) {
        val p = player ?: return
        if (!isSeekable) return
        val target = ms.coerceIn(0L, maxSeek())
        p.seekTo(target)
        positionMs = target
        poke()
    }

    fun seekBy(deltaMs: Long) {
        val base = player?.currentPosition ?: positionMs
        seekTo(base + deltaMs)
    }

    /** Seek step grows while a key is held. */
    private fun seekStep(repeat: Int): Long = when {
        repeat < 4 -> 10_000L
        repeat < 16 -> 20_000L
        repeat < 40 -> 30_000L
        else -> 60_000L
    }

    /** Quick seek (D-pad with hidden overlay, FF/RW keys): accumulate, then commit once the keys stop. */
    fun quickSeek(forward: Boolean, repeat: Int) {
        val p = player ?: return
        if (!isSeekable) return
        val current = p.currentPosition
        if (seekPreviewMs == null) seekOriginMs = current
        val base = seekPreviewMs ?: current
        val step = seekStep(repeat)
        seekPreviewMs = (base + if (forward) step else -step).coerceIn(0L, maxSeek())
        seekHud = true
        seekJob?.cancel()
        seekJob = scope.launch {
            delay(650)
            seekPreviewMs?.let { seekTo(it) }
            seekPreviewMs = null
            delay(1_500)
            seekHud = false
        }
    }

    /** Key handling for the focused seekbar: LEFT/RIGHT scrub, OK commits. */
    fun onSeekBarKey(e: KeyEvent): Boolean {
        if (!isSeekable) return false
        val code = e.keyCode
        val lr = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
        val ok = isOkKey(code)
        if (!lr && !ok) return false
        if (e.action == KeyEvent.ACTION_UP) return true
        if (e.action != KeyEvent.ACTION_DOWN) return false
        poke()
        if (lr) {
            val base = scrubMs ?: seekPreviewMs ?: positionMs
            val step = seekStep(e.repeatCount)
            scrubMs = (base + if (code == KeyEvent.KEYCODE_DPAD_RIGHT) step else -step).coerceIn(0L, maxSeek())
            return true
        }
        if (e.repeatCount > 0) return true
        val target = scrubMs
        if (target != null) {
            scrubMs = null
            seekTo(target)
        } else {
            togglePlay()
        }
        return true
    }

    fun cancelScrub() {
        scrubMs = null
    }

    fun setPlaybackSpeed(value: Float) {
        speed = value
        if (isSeekable) player?.setPlaybackSpeed(value)
    }

    fun setAspectMode(mode: Int) {
        aspect = mode.coerceIn(0, 2)
    }

    // ------------------------------------------------------------------ tracks

    fun selectTrack(type: Int, option: TrackOption) {
        val p = player ?: return
        val group = option.group
        val builder = p.trackSelectionParameters.buildUpon()
        if (group == null) {
            builder.clearOverridesOfType(type).setTrackTypeDisabled(type, true)
        } else {
            builder.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, option.index))
                .setTrackTypeDisabled(type, false)
            // Carry the choice over to the next episode / channel.
            val lang = group.getTrackFormat(option.index).language
            if (lang != null) {
                if (type == C.TRACK_TYPE_AUDIO) builder.setPreferredAudioLanguage(lang)
                else if (type == C.TRACK_TYPE_TEXT) builder.setPreferredTextLanguage(lang)
            }
        }
        p.trackSelectionParameters = builder.build()
        poke()
    }

    // ------------------------------------------------------------------ overlay & panels

    /** Any user interaction: keeps visible overlays alive. */
    fun poke() {
        if (overlayVisible || bannerVisible) scheduleAutoHide()
    }

    private fun scheduleAutoHide() {
        autoHideJob?.cancel()
        val wait = when {
            isLive && liveActions -> 10_000L
            isLive -> 5_000L
            else -> 4_000L
        }
        autoHideJob = scope.launch {
            delay(wait)
            autoHide()
        }
    }

    private fun autoHide() {
        if (panel != null || errorMessage != null) return
        if (isLive) {
            if (zapJob?.isActive == true || numberEntry.isNotEmpty()) {
                scheduleAutoHide()
                return
            }
            bannerVisible = false
            liveActions = false
        } else if (overlayVisible && isPlaying && scrubMs == null) {
            overlayVisible = false
        }
    }

    fun showOverlay() {
        if (isLive) {
            showBanner()
        } else {
            overlayVisible = true
            scheduleAutoHide()
        }
    }

    fun hideOverlay() {
        overlayVisible = false
        scrubMs = null
    }

    fun showBanner() {
        bannerVisible = true
        scheduleAutoHide()
    }

    fun openLiveActions() {
        if (!isLive) return
        panel = null
        bannerVisible = true
        liveActions = true
        scheduleAutoHide()
    }

    fun openPanel(p: Panel) {
        // Finish a pending zap first so the panel shows the channel the user landed on.
        val pendingZap = zapChannel
        if (zapJob?.isActive == true && pendingZap != null) switchChannel(pendingZap)
        autoHideJob?.cancel()
        panel = p
        overlayVisible = false
        liveActions = false
        bannerVisible = false
        scrubMs = null
    }

    fun closePanel() {
        panel = null
        if (isLive) showBanner() else showOverlay()
    }

    fun onBack() {
        val up = nextUp
        when {
            numberEntry.isNotEmpty() -> {
                numberJob?.cancel()
                numberEntry = ""
            }
            panel != null -> closePanel()
            up != null -> if (up.mode == NextMode.Credits) {
                creditsDismissed = true
                nextUp = null
            } else {
                countdownJob?.cancel()
                nextUp = null
                activity.finish()
            }
            errorMessage != null -> exit()
            scrubMs != null -> scrubMs = null
            !isLive && overlayVisible -> hideOverlay()
            isLive && (liveActions || bannerVisible) -> {
                liveActions = false
                bannerVisible = false
            }
            else -> exit()
        }
    }

    /** Leave the player – or, for catch-up started from a live channel, go back to live. */
    fun exit() {
        val catchup = item as? PlayItem.Catchup
        if (catchup != null && liveOrigin != null) watchLive(catchup.channel) else activity.finish()
    }

    // ------------------------------------------------------------------ episodes

    private fun showEndedNextUp(next: Episode) {
        overlayVisible = false
        panel = null
        countdownJob?.cancel()
        val auto = Repo.settings.autoplayNext
        nextUp = NextUp(next, if (auto) NextMode.Countdown else NextMode.Ended)
        if (auto) {
            countdown = 10
            countdownJob = scope.launch {
                while (countdown > 0) {
                    delay(1_000)
                    countdown -= 1
                }
                playNext()
            }
        }
    }

    private fun checkCredits() {
        if (item !is PlayItem.Ep) return
        if (nextUp != null || creditsDismissed || !isPlaying || errorMessage != null) return
        val d = durationMs
        if (d < 120_000L) return
        val left = d - positionMs
        if (left in 1L..40_000L) {
            val next = nextEpisode() ?: return
            nextUp = NextUp(next, NextMode.Credits)
        }
    }

    fun playNext() {
        val next = nextEpisode() ?: return
        playEpisode(next)
    }

    fun cancelNextUp() {
        val up = nextUp ?: return
        countdownJob?.cancel()
        nextUp = null
        if (up.mode == NextMode.Credits) creditsDismissed = true else showOverlay()
    }

    fun playEpisode(ep: Episode) {
        val cur = item as? PlayItem.Ep ?: return
        panel = null
        val start = if (ep.id == cur.episode.id) resumePosition() else Repo.store.resumePosition("ep:${cur.series.id}:${ep.id}")
        playItem(cur.copy(episode = ep, startMs = start), start)
        showOverlay()
    }

    // ------------------------------------------------------------------ live

    fun zap(delta: Int) {
        val live = item as? PlayItem.Live ?: return
        val list = live.zapList
        if (list.isEmpty()) return
        val from = zapChannel ?: live.channel
        val idx = list.indexOfFirst { it.id == from.id }.coerceAtLeast(0)
        val target = list[(idx + delta).mod(list.size)]
        zapChannel = target
        showBanner()
        zapJob?.cancel()
        zapJob = scope.launch {
            delay(350)
            switchChannel(target)
        }
    }

    fun switchChannel(ch: Channel, list: List<Channel>? = null) {
        zapJob?.cancel()
        val cur = item
        val zapList = list
            ?: (cur as? PlayItem.Live)?.zapList?.takeIf { l -> l.any { it.id == ch.id } }
            ?: liveOrigin?.zapList?.takeIf { l -> l.any { it.id == ch.id } }
            ?: Repo.channels(ch.categoryId).ifEmpty { listOf(ch) }
        zapChannel = null
        if (cur is PlayItem.Live && cur.channel.id == ch.id) {
            showBanner()
            return
        }
        if (cur is PlayItem.Live) previousChannel = cur.channel
        liveOrigin = null
        overlayVisible = false
        playItem(PlayItem.Live(ch, zapList), 0L)
        showBanner()
    }

    /** Selection from the channel list panel. */
    fun pickChannel(ch: Channel, list: List<Channel>) {
        panel = null
        switchChannel(ch, list)
    }

    fun recall() {
        val prev = previousChannel
        if (prev == null) {
            toast("No previous channel yet")
            return
        }
        switchChannel(prev)
    }

    fun typeDigit(d: Int) {
        if (numberEntry.length >= 5) numberEntry = ""
        numberEntry += d.toString()
        numberJob?.cancel()
        numberJob = scope.launch {
            delay(1_500)
            commitNumber()
        }
    }

    private fun commitNumber() {
        numberJob?.cancel()
        val n = numberEntry.toIntOrNull()
        numberEntry = ""
        if (n == null) return
        val ch = findChannelByNumber(n)
        if (ch == null) {
            toast("Channel $n not found")
            return
        }
        switchChannel(ch)
    }

    fun findChannelByNumber(n: Int): Channel? =
        Repo.channels().firstOrNull { it.number == n }
            ?: (item as? PlayItem.Live)?.zapList?.firstOrNull { it.number == n }

    fun toggleFavorite() {
        val ch = currentChannel ?: return
        val added = Repo.store.toggleFavorite("live:${ch.id}")
        toast(if (added) "Added to Favorites" else "Removed from Favorites")
        poke()
    }

    fun canCatchup(ch: Channel, p: Programme, now: Long = System.currentTimeMillis()): Boolean =
        ch.catchupDays > 0 && p.start < now && p.start >= now - ch.catchupDays * 86_400_000L

    fun playCatchup(ch: Channel, p: Programme) {
        val url = Repo.catchupUrl(ch, p)
        if (url == null) {
            toast("Catch-up isn't available for this programme")
            return
        }
        val cur = item
        if (cur is PlayItem.Live) liveOrigin = cur
        panel = null
        liveActions = false
        bannerVisible = false
        zapChannel = null
        playItem(PlayItem.Catchup(ch, p, url), 0L)
        showOverlay()
    }

    /** From catch-up back to the live stream of [ch]. */
    fun watchLive(ch: Channel) {
        val origin = liveOrigin
        liveOrigin = null
        overlayVisible = false
        panel = null
        val list = origin?.zapList?.takeIf { l -> l.any { it.id == ch.id } }
            ?: Repo.channels(ch.categoryId).ifEmpty { listOf(ch) }
        playItem(PlayItem.Live(ch, list), 0L)
        showBanner()
    }

    // ------------------------------------------------------------------ keys

    /**
     * Called from Activity.dispatchKeyEvent before Compose sees the key.
     * Returns true when the key was used as a player shortcut.
     */
    fun handleKey(e: KeyEvent): Boolean {
        val code = e.keyCode
        if (code == KeyEvent.KEYCODE_BACK) return false
        return when (e.action) {
            KeyEvent.ACTION_DOWN -> {
                poke()
                val repeatOfConsumed = e.repeatCount > 0 && code in consumedKeys
                val handled = onKeyDown(code, e) || repeatOfConsumed
                if (handled) consumedKeys.add(code) else consumedKeys.remove(code)
                handled
            }
            KeyEvent.ACTION_UP -> {
                if (!consumedKeys.remove(code)) {
                    false
                } else {
                    onConsumedKeyUp(code)
                    true
                }
            }
            else -> false
        }
    }

    private fun onKeyDown(code: Int, e: KeyEvent): Boolean {
        val rep = e.repeatCount
        when (code) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> {
                if (rep == 0) {
                    togglePlay()
                    if (!isLive && !isInteractive) showOverlay()
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                if (rep == 0 && (!playWhenReady || errorMessage != null)) togglePlay()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (rep == 0 && playWhenReady) player?.playWhenReady = false
                if (!isLive && !isInteractive) showOverlay()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_STOP -> {
                if (rep == 0) activity.finish()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                if (isSeekable) quickSeek(code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, rep)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                if (rep == 0) {
                    if (isLive) zap(1) else if (nextEpisode() != null) playNext()
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                if (isLive) {
                    zap(-1)
                    return true
                }
                return false
            }
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> {
                if (isLive) {
                    zap(1)
                    return true
                }
                return false
            }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                if (isLive) {
                    zap(-1)
                    return true
                }
                return false
            }
            KeyEvent.KEYCODE_LAST_CHANNEL -> {
                if (isLive) {
                    if (rep == 0) recall()
                    return true
                }
                return false
            }
        }
        if (isLive && code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) {
            if (rep == 0) typeDigit(code - KeyEvent.KEYCODE_0)
            return true
        }
        if (errorMessage != null) {
            // The live error card promises ▲ ▼ switches channel; everything else goes to its buttons.
            if (isLive && panel == null && (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN)) {
                zap(if (code == KeyEvent.KEYCODE_DPAD_UP) 1 else -1)
                return true
            }
            return false
        }
        return if (isLive) liveKey(code, e) else vodKey(code, e)
    }

    private fun liveKey(code: Int, e: KeyEvent): Boolean {
        if (panel != null || liveActions) return false
        val rep = e.repeatCount
        return when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> { zap(1); true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { zap(-1); true }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (rep == 0) openPanel(Panel.Channels)
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (rep == 0) showBanner()
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (rep == 0) {
                    centerPending = true
                    centerLong = false
                } else if (centerPending && !centerLong) {
                    // Long-press OK = action row.
                    centerLong = true
                    centerPending = false
                    openLiveActions()
                }
                true
            }
            KeyEvent.KEYCODE_MENU -> {
                if (rep == 0) openLiveActions()
                true
            }
            KeyEvent.KEYCODE_INFO -> {
                if (rep == 0) showBanner()
                true
            }
            KeyEvent.KEYCODE_GUIDE -> {
                if (rep == 0) openPanel(Panel.Programmes)
                true
            }
            else -> false
        }
    }

    private fun vodKey(code: Int, e: KeyEvent): Boolean {
        if (panel != null || overlayVisible || nextUp != null) return false
        val rep = e.repeatCount
        return when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                quickSeek(code == KeyEvent.KEYCODE_DPAD_RIGHT, rep)
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (rep == 0) {
                    togglePlay()
                    showOverlay()
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> {
                if (rep == 0) showOverlay()
                true
            }
            else -> false
        }
    }

    private fun onConsumedKeyUp(code: Int) {
        if (isOkKey(code) && centerPending) {
            centerPending = false
            if (numberEntry.isNotEmpty()) commitNumber() else openPanel(Panel.Channels)
        }
    }

    private fun isOkKey(code: Int) =
        code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER

    fun toast(message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        fun guessMime(url: String): String? {
            val u = url.lowercase()
            return when {
                ".m3u8" in u -> MimeTypes.APPLICATION_M3U8
                ".mpd" in u -> MimeTypes.APPLICATION_MPD
                else -> null // progressive: MPEG-TS, MP4, MKV… are sniffed by the extractors
            }
        }

        fun episodeLabel(e: Episode): String {
            val head = if (e.season > 0) "S${e.season} E${e.episode}" else "E${e.episode}"
            return if (e.title.isBlank()) head else "$head · ${e.title}"
        }

        private fun isContainerError(error: PlaybackException): Boolean {
            if (error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
            ) return true
            return findCause(error) { it is UnrecognizedInputFormatException } != null
        }

        private fun isTransientNetworkError(error: PlaybackException): Boolean {
            val http = findCause(error) { it is HttpDataSource.InvalidResponseCodeException } as? HttpDataSource.InvalidResponseCodeException
            if (http != null) return http.responseCode >= 500
            return error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
                error.errorCode == PlaybackException.ERROR_CODE_TIMEOUT
        }

        private fun findCause(error: Throwable, predicate: (Throwable) -> Boolean): Throwable? {
            var t: Throwable? = error
            var depth = 0
            while (t != null && depth < 12) {
                if (predicate(t)) return t
                t = t.cause
                depth++
            }
            return null
        }

        fun friendlyError(error: PlaybackException): String {
            val http = findCause(error) { it is HttpDataSource.InvalidResponseCodeException } as? HttpDataSource.InvalidResponseCodeException
            if (http != null) {
                return when (http.responseCode) {
                    401, 403 -> "Access denied by the server (HTTP ${http.responseCode}). Your subscription may have expired or too many devices are watching."
                    404 -> "This stream wasn't found on the server (HTTP 404)."
                    in 500..599 -> "The server is having problems (HTTP ${http.responseCode}). Try again in a moment."
                    else -> "The server refused the stream (HTTP ${http.responseCode})."
                }
            }
            return when (error.errorCode) {
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
                    "Can't connect to the stream. Check your internet connection."
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, PlaybackException.ERROR_CODE_TIMEOUT ->
                    "The stream took too long to respond."
                PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED ->
                    "This stream uses an insecure connection that isn't allowed."
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
                PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ->
                    "This stream's format isn't supported or the stream is offline."
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
                PlaybackException.ERROR_CODE_DECODING_FAILED,
                PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
                    "This device can't decode this stream (unsupported video or audio format)."
                PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED ->
                    "The audio output couldn't start. Try another audio track."
                else -> "Something went wrong while playing (${PlaybackException.getErrorCodeName(error.errorCode)})."
            }
        }
    }
}
