package it.davidealpegiani.skylite

import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultLivePlaybackSpeedControl
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.ui.PlayerView

@UnstableApi
class MainActivity : Activity() {
    private var player: ExoPlayer? = null
    private var resumeAfterBackground = false
    private lateinit var playerView: PlayerView
    private lateinit var titleView: TextView
    private lateinit var statusView: TextView
    private lateinit var diagnosticsView: TextView
    private val retryHandler = Handler(Looper.getMainLooper())
    private val diagnosticsTask = object : Runnable {
        override fun run() {
            val current = player
            if (current == null || !::diagnosticsView.isInitialized || isFinishing || isDestroyed) return
            val aheadMs = (current.bufferedPosition - current.currentPosition).coerceAtLeast(0L)
            val state = when (current.playbackState) {
                Player.STATE_BUFFERING -> "BUFFERING"
                Player.STATE_READY -> "READY"
                Player.STATE_IDLE -> "IDLE"
                Player.STATE_ENDED -> "ENDED"
                else -> "?"
            }
            diagnosticsView.text = "HLS: $state · buffer ${aheadMs / 1000}s · " +
                if (current.isLoading) "download attivo" else "download in attesa"
            retryHandler.postDelayed(this, 1000L)
        }
    }
    private var retryCount = 0
    private var retryTask: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildScreen()
        initializePlayer()
        handlePlayIntent(intent)
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(8, 13, 22))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        titleView = TextView(this).apply {
            text = "SKY PLAYER LITE"
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(20, 18, 20, 8)
        }
        statusView = TextView(this).apply {
            text = "Apri un canale da Sky Player HTML."
            setTextColor(Color.rgb(164, 181, 202))
            textSize = 13f
            setPadding(20, 0, 20, 12)
        }
        playerView = PlayerView(this).apply {
            useController = true
            controllerAutoShow = true
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            keepScreenOn = true
            setBackgroundColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        root.addView(titleView)
        root.addView(statusView)
        diagnosticsView = TextView(this).apply {
            text = "HLS: in attesa · buffer 0s"
            setTextColor(Color.rgb(112, 196, 220))
            textSize = 11f
            setPadding(20, 0, 20, 8)
        }
        root.addView(diagnosticsView)
        root.addView(playerView)
        setContentView(root)
    }

    private fun initializePlayer() {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                30_000,
                90_000,
                6_000,
                15_000
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("SkyPlayerLite/1.0 (Android)")
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(45_000)
            .setAllowCrossProtocolRedirects(true)

        val liveSpeedControl = DefaultLivePlaybackSpeedControl.Builder()
            .setFallbackMinPlaybackSpeed(0.97f)
            .setFallbackMaxPlaybackSpeed(1.03f)
            .setTargetLiveOffsetIncrementOnRebufferMs(10_000)
            .build()

        val mediaSourceFactory = HlsMediaSource.Factory(httpFactory)
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(6))

        player = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setLivePlaybackSpeedControl(liveSpeedControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .also { exo ->
                playerView.player = exo
                exo.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        statusView.text = when (playbackState) {
                            Player.STATE_BUFFERING -> "Caricamento / buffering…"
                            Player.STATE_READY -> "Riproduzione pronta"
                            Player.STATE_ENDED -> "Flusso terminato"
                            else -> statusView.text
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        val retryable = error.errorCodeName.contains("IO_") ||
                            error.errorCodeName.contains("BEHIND_LIVE_WINDOW")
                        val delays = longArrayOf(2_000L, 4_000L, 8_000L, 12_000L, 15_000L)
                        if (retryable && retryCount < delays.size) {
                            val delay = delays[retryCount++]
                            statusView.text = "Connessione instabile. Nuovo tentativo tra ${delay / 1000} s…"
                            retryTask?.let { retryHandler.removeCallbacks(it) }
                            val task = Runnable {
                                val current = player ?: return@Runnable
                                if (error.errorCodeName.contains("BEHIND_LIVE_WINDOW")) {
                                    current.seekToDefaultPosition()
                                }
                                current.prepare()
                                current.playWhenReady = true
                            }
                            retryTask = task
                            retryHandler.postDelayed(task, delay)
                        } else {
                            statusView.text = "Errore di riproduzione: ${error.errorCodeName}. Riprova il canale."
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (isPlaying) {
                            retryHandler.postDelayed({
                                if (player?.isPlaying == true) retryCount = 0
                            }, 30_000L)
                        }
                    }
                })
            }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePlayIntent(intent)
    }

    private fun handlePlayIntent(intent: android.content.Intent?) {
        val uri = intent?.data
        val streamUrl = uri?.getQueryParameter("url")
            ?: intent?.getStringExtra("url")
        val channelName = uri?.getQueryParameter("name")
            ?: intent?.getStringExtra("name")
            ?: "Canale live"

        if (streamUrl.isNullOrBlank()) {
            statusView.text = "Apri un canale da Sky Player HTML."
            return
        }

        val parsed = runCatching { Uri.parse(streamUrl) }.getOrNull()
        if (parsed == null || parsed.scheme !in listOf("http", "https")) {
            statusView.text = "Indirizzo del flusso non valido."
            return
        }

        titleView.text = channelName
        statusView.text = "Connessione al flusso…"
        val mediaItem = MediaItem.Builder()
            .setUri(parsed)
            .setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setTargetOffsetMs(45_000)
                    .setMinOffsetMs(30_000)
                    .setMaxOffsetMs(90_000)
                    .setMinPlaybackSpeed(0.97f)
                    .setMaxPlaybackSpeed(1.03f)
                    .build()
            )
            .build()
        val source = HlsMediaSource.Factory(
            DefaultHttpDataSource.Factory()
                .setUserAgent("SkyPlayerLite/1.0 (Android)")
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(45_000)
                .setAllowCrossProtocolRedirects(true)
        ).setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(6)).createMediaSource(mediaItem)

        retryTask?.let { retryHandler.removeCallbacks(it) }
        retryCount = 0
        player?.apply {
            stop()
            clearMediaItems()
            setMediaSource(source)
            prepare()
            playWhenReady = true
        }
    }

    override fun onStop() {
        retryHandler.removeCallbacks(diagnosticsTask)
        resumeAfterBackground = player?.playWhenReady == true && (player?.mediaItemCount ?: 0) > 0
        player?.pause()
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        retryHandler.removeCallbacks(diagnosticsTask)
        retryHandler.post(diagnosticsTask)
        if (resumeAfterBackground && (player?.mediaItemCount ?: 0) > 0) {
            player?.playWhenReady = true
        }
        resumeAfterBackground = false
    }

    override fun onDestroy() {
        retryHandler.removeCallbacks(diagnosticsTask)
        retryTask?.let { retryHandler.removeCallbacks(it) }
        retryHandler.removeCallbacksAndMessages(null)
        playerView.player = null
        player?.release()
        player = null
        super.onDestroy()
    }
}
