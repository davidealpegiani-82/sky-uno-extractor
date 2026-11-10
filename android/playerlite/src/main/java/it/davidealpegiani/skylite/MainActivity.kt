package it.davidealpegiani.skylite

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Toast
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultLivePlaybackSpeedControl
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.ui.PlayerView
import org.json.JSONArray
import org.json.JSONObject

private data class CloudChannel(val name: String, val url: String, val logo: String = "")

@UnstableApi
class MainActivity : Activity() {
    private var player: ExoPlayer? = null
    private var resumeAfterBackground = false
    private lateinit var playerView: PlayerView
    private lateinit var titleView: TextView
    private lateinit var statusView: TextView
    private lateinit var diagnosticsView: TextView
    private lateinit var fullscreenButton: TextView
    private var isFullscreen = false
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
    private lateinit var endpointInput: EditText
    private lateinit var pairButton: Button
    private lateinit var cloudStatusView: TextView
    private lateinit var channelsList: LinearLayout
    private val cloudHandler = Handler(Looper.getMainLooper())
    private val cloudExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val cloudChannels = mutableListOf<CloudChannel>()
    private var pairingPollId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildScreen()
        loadCachedChannels()
        initializePlayer()
        handlePlayIntent(intent)
        syncCloudAtStartup()
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
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
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

        endpointInput = EditText(this).apply {
            hint = "Indirizzo Sky Cloud Sync (HTTPS)"
            setSingleLine(true)
            textSize = 13f
            setText(PlayerCloudSync.baseUrl(this@MainActivity))
            setPadding(16, 4, 16, 4)
        }
        root.addView(endpointInput, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 48
        ).apply { setMargins(12, 4, 12, 4) })
        pairButton = Button(this).apply {
            text = "Collega questo Player"
            isAllCaps = false
            isFocusable = true
            setOnClickListener { beginCloudPairing() }
        }
        root.addView(pairButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 48
        ).apply { setMargins(12, 0, 12, 4) })
        cloudStatusView = TextView(this).apply {
            text = "Sky Cloud Sync: non ancora associato."
            setTextColor(Color.rgb(112, 196, 220))
            textSize = 11f
            setPadding(16, 2, 16, 6)
        }
        root.addView(cloudStatusView)

        val playerContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
            setBackgroundColor(Color.BLACK)
        }
        playerContainer.addView(playerView)
        fullscreenButton = TextView(this).apply {
            text = "⛶"
            contentDescription = "Attiva schermo intero"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(190, 8, 13, 22))
            setPadding(14, 6, 14, 10)
            isClickable = true
            isFocusable = true
            setOnClickListener { toggleFullscreen() }
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.TOP
            ).apply { setMargins(0, 16, 16, 0) }
        }
        playerContainer.addView(fullscreenButton)
        root.addView(playerContainer)
        val channelScroll = ScrollView(this).apply {
            isFillViewport = true
            isFocusable = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 150
            )
            setBackgroundColor(Color.rgb(8, 13, 22))
        }
        channelsList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10, 6, 10, 10)
        }
        channelScroll.addView(channelsList)
        root.addView(channelScroll)
        setContentView(root)
        if (PlayerCloudSync.hasToken(this)) {
            endpointInput.visibility = View.GONE
            pairButton.visibility = View.GONE
            cloudStatusView.text = "Sky Cloud Sync: Player associato · controllo aggiornamenti all'avvio."
        }
    }

    private fun loadCachedChannels() {
        cloudChannels.clear()
        val config = PlayerCloudSync.cachedConfig(this)
        val array = config?.optJSONArray("channels") ?: JSONArray()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val name = item.optString("name").trim()
            val url = item.optString("url").trim()
            if (name.isNotBlank() && (url.startsWith("http://", true) || url.startsWith("https://", true))) {
                cloudChannels.add(CloudChannel(name, url, item.optString("logo")))
            }
        }
        renderCloudChannels()
    }

    private fun renderCloudChannels() {
        if (!::channelsList.isInitialized) return
        channelsList.removeAllViews()
        if (cloudChannels.isEmpty()) {
            val empty = TextView(this).apply {
                text = "Nessuna lista cloud disponibile. Associa il Player oppure importa un canale da Sky Player HTML."
                setTextColor(Color.rgb(164, 181, 202))
                textSize = 12f
                setPadding(12, 12, 12, 12)
            }
            channelsList.addView(empty)
            return
        }
        cloudChannels.forEach { channel ->
            val item = Button(this).apply {
                text = "▶  ${channel.name}"
                isAllCaps = false
                isFocusable = true
                textSize = 13f
                setTextColor(Color.WHITE)
                setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(28, 45, 66)))
                setOnClickListener { playCloudChannel(channel) }
            }
            channelsList.addView(item, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 46
            ).apply { bottomMargin = 5 })
        }
    }

    private fun beginCloudPairing() {
        try {
            PlayerCloudSync.saveBaseUrl(this, endpointInput.text.toString())
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: "Indirizzo non valido.", Toast.LENGTH_LONG).show()
            return
        }
        cloudStatusView.text = "Richiesta di associazione…"
        pairButton.isEnabled = false
        cloudExecutor.execute {
            try {
                val result = PlayerCloudSync.startPairing(this)
                val id = result.getString("pairingId")
                val code = result.getString("code")
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    pairingPollId = id
                    cloudStatusView.text = "Codice di autorizzazione: $code · Inseriscilo in Sky Extractor. Valido 10 minuti."
                    pairButton.text = "In attesa di autorizzazione…"
                    schedulePairingPoll(id)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    pairButton.isEnabled = true
                    cloudStatusView.text = e.message ?: "Impossibile avviare l'associazione."
                }
            }
        }
    }

    private fun schedulePairingPoll(pairingId: String) {
        cloudHandler.removeCallbacksAndMessages(null)
        cloudHandler.postDelayed({
            cloudExecutor.execute {
                val state = runCatching { PlayerCloudSync.pairingStatus(this, pairingId) }
                    .getOrElse { "error" }
                runOnUiThread {
                    if (isFinishing || isDestroyed || pairingPollId != pairingId) return@runOnUiThread
                    when (state) {
                        "approved" -> {
                            pairingPollId = null
                            endpointInput.visibility = View.GONE
                            pairButton.visibility = View.GONE
                            cloudStatusView.text = "Player autorizzato. Scaricamento della configurazione…"
                            syncCloudAtStartup()
                        }
                        "pending" -> schedulePairingPoll(pairingId)
                        "expired" -> {
                            pairingPollId = null
                            pairButton.isEnabled = true
                            pairButton.text = "Riprova associazione"
                            cloudStatusView.text = "Codice scaduto. Avvia una nuova associazione."
                        }
                        else -> {
                            pairButton.isEnabled = true
                            pairButton.text = "Riprova associazione"
                            cloudStatusView.text = "Controllo associazione non riuscito. Verifica la connessione."
                        }
                    }
                }
            }
        }, 3000L)
    }

    private fun syncCloudAtStartup() {
        if (!PlayerCloudSync.hasToken(this)) {
            cloudStatusView.text = "Sky Cloud Sync: associa questo Player per ricevere la lista da remoto."
            return
        }
        cloudStatusView.text = "Controllo aggiornamenti Sky Cloud Sync…"
        cloudExecutor.execute {
            try {
                val result = PlayerCloudSync.sync(this)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    loadCachedChannels()
                    cloudStatusView.text = if (result.optBoolean("unchanged", false)) {
                        "Lista già aggiornata · versione ${result.optInt("version")} · ${cloudChannels.size} canali."
                    } else {
                        "Lista aggiornata · versione ${result.optInt("version")} · ${result.optInt("channelCount")} canali."
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    cloudStatusView.text = "Sincronizzazione non riuscita: ${e.message ?: "offline"}. Mantengo la lista salvata."
                    loadCachedChannels()
                }
            }
        }
    }

    private fun playCloudChannel(channel: CloudChannel) {
        val parsed = runCatching { Uri.parse(channel.url) }.getOrNull()
        if (parsed == null || parsed.scheme !in listOf("http", "https")) {
            statusView.text = "Indirizzo del flusso non valido."
            return
        }
        titleView.text = channel.name
        statusView.text = "Connessione al flusso…"
        val item = MediaItem.Builder()
            .setUri(parsed)
            .setMimeType(MimeTypes.APPLICATION_M3U8)
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
        retryTask?.let { retryHandler.removeCallbacks(it) }
        retryCount = 0
        player?.apply {
            stop()
            clearMediaItems()
            setMediaItem(item)
            prepare()
            playWhenReady = true
        }
    }

    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        val decor = window.decorView
        if (isFullscreen) {
            titleView.visibility = View.GONE
            statusView.visibility = View.GONE
            diagnosticsView.visibility = View.GONE
            decor.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )
            fullscreenButton.text = "✕"
            fullscreenButton.contentDescription = "Esci da schermo intero"
        } else {
            decor.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            titleView.visibility = View.VISIBLE
            statusView.visibility = View.VISIBLE
            diagnosticsView.visibility = View.VISIBLE
            fullscreenButton.text = "⛶"
            fullscreenButton.contentDescription = "Attiva schermo intero"
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (isFullscreen) toggleFullscreen() else super.onBackPressed()
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
            .setMimeType(MimeTypes.APPLICATION_M3U8)
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
        // Usa la stessa MediaSourceFactory HLS configurata in initializePlayer().
        // Il MIME esplicito evita ambiguità con flussi HLS senza estensione .m3u8.
        retryTask?.let { retryHandler.removeCallbacks(it) }
        retryCount = 0
        player?.apply {
            stop()
            clearMediaItems()
            setMediaItem(mediaItem)
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
        cloudHandler.removeCallbacksAndMessages(null)
        cloudExecutor.shutdownNow()
        playerView.player = null
        player?.release()
        player = null
        super.onDestroy()
    }
}
