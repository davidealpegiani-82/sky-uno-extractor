package it.davidealpegiani.skylite

import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
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

        player = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
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
                        statusView.text = "Errore di riproduzione: ${error.errorCodeName}. Riprova il canale."
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
                    .build()
            )
            .build()
        val source = HlsMediaSource.Factory(
            DefaultHttpDataSource.Factory()
                .setUserAgent("SkyPlayerLite/1.0 (Android)")
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(45_000)
                .setAllowCrossProtocolRedirects(true)
        ).createMediaSource(mediaItem)

        player?.apply {
            stop()
            clearMediaItems()
            setMediaSource(source)
            prepare()
            playWhenReady = true
        }
    }

    override fun onStop() {
        resumeAfterBackground = player?.playWhenReady == true && player?.mediaItemCount ?: 0 > 0
        player?.pause()
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        if (resumeAfterBackground && player?.mediaItemCount ?: 0 > 0) {
            player?.playWhenReady = true
        }
        resumeAfterBackground = false
    }

    override fun onDestroy() {
        playerView.player = null
        player?.release()
        player = null
        super.onDestroy()
    }
}
