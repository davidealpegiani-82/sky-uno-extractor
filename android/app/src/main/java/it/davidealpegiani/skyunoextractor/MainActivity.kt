package it.davidealpegiani.skyunoextractor

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.net.Uri
import android.provider.OpenableColumns
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Xml
import android.util.AtomicFile
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.mediarouter.app.MediaRouteButton
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.MediaRouteButtonFactory
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultLivePlaybackSpeedControl
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.ui.PlayerView
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.Executors

private data class ChannelArchiveEntry(
    val name: String,
    val url: String,
    val logo: String,
    val selected: Boolean,
    val category: String,
    val tvgId: String,
    val tvgName: String
)

data class TvChannel(
    val name: String,
    val url: String,
    val logo: String = "",
    var selected: Boolean = false,
    var category: String = "Non classificati",
    var tvgId: String = "",
    var tvgName: String = ""
)

@OptIn(UnstableApi::class)
class MainActivity : FragmentActivity() {
    private val channels = mutableListOf<TvChannel>()
    // Un solo writer seriale: impedisce che snapshot più vecchi finiscano su disco dopo quelli recenti.
    private val archiveExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "DAVIX-Archive").apply { isDaemon = true }
    }
    // Accorpa le modifiche ravvicinate: evita copie integrali ripetute sul thread UI.
    private val archiveDebounceHandler = Handler(Looper.getMainLooper())
    private var pendingArchiveWrite: Runnable? = null
    private val categories = mutableListOf("Intrattenimento", "Sport", "Cinema", "Documentari", "Musica", "XXX", "Non classificati")
    private val categoryPrefs by lazy { getSharedPreferences("davix_extractor_categories", MODE_PRIVATE) }
    // Selezione multipla separata dalla selezione di esportazione.
    private var bulkSelectionMode = false
    private val bulkSelectedIndices = mutableSetOf<Int>()
    // Limita le View create contemporaneamente per ogni cartella: le playlist IPTV possono contenere migliaia di righe.
    private val folderPageSizes = mutableMapOf<String, Int>()
    // Limiti globali di rendering: una playlist con migliaia di gruppi non deve bloccare il thread UI.
    private val folderRenderLimits = mutableMapOf<String, Int>()
    private val channelRenderLimits = mutableMapOf<String, Int>()
    // Le cartelle sono chiuse per impostazione predefinita: niente liste chilometriche.
    private val expandedCategoryPaths = mutableSetOf<String>()
    // null = conserva i group-title originali della playlist.
    private var pendingImportCategory: String? = null
    private lateinit var mainContainer: LinearLayout
    private lateinit var root: LinearLayout
    private lateinit var selectedList: LinearLayout
    private lateinit var savedPlaylistsList: LinearLayout
    private lateinit var otherList: LinearLayout
    private lateinit var selectedHeading: TextView
    private lateinit var otherHeading: TextView
    private lateinit var playerDock: LinearLayout
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var status: TextView
    private lateinit var playbackInfo: TextView
    private lateinit var currentChannelLabel: TextView
    private lateinit var importProgressPanel: LinearLayout
    private lateinit var importProgressLabel: TextView
    private lateinit var importProgressBar: ProgressBar
    private var currentPlayingChannelUrl: String? = null
    private var playingChannelButton: Button? = null
    private var importInProgress = false
    @Volatile private var streamScanCancelled = false
    // Evita che onStop salvi una lista vuota mentre il ripristino asincrono è ancora in corso.
    private var channelLibraryRestorePending = false
    private lateinit var count: TextView
    private lateinit var playerView: PlayerView
    private lateinit var localPlayer: ExoPlayer
    private lateinit var player: Player
    private var castPlayer: CastPlayer? = null
    private var castInitWarning: String? = null
    private lateinit var scroll: ScrollView
    private var pendingHtml = ""
    private var fullscreen = false
    private val retryHandler = Handler(Looper.getMainLooper())
    private var playbackRetryCount = 0
    private var playbackRetryTask: Runnable? = null
    private var stablePlaybackTask: Runnable? = null
    // Invalida i tentativi appartenenti al canale precedente.
    private var playbackGeneration = 0
    // Watchdog del buffer live: distingue il normale caricamento da un player
    // rimasto senza segmenti e senza avanzamento della finestra HLS.
    private var emptyBufferSinceMs = 0L
    private var lastEmptyBufferRecoveryMs = 0L

    // Diagnostica visibile: misura il buffer realmente disponibile durante i blocchi.
    private val playbackInfoTask = object : Runnable {
        override fun run() {
            if (!::player.isInitialized || !::playbackInfo.isInitialized || isFinishing || isDestroyed) return
            val state = when (player.playbackState) {
                Player.STATE_IDLE -> "IDLE"
                Player.STATE_BUFFERING -> "BUFFERING"
                Player.STATE_READY -> "READY"
                Player.STATE_ENDED -> "ENDED"
                else -> "?"
            }
            val aheadMs = (player.bufferedPosition - player.currentPosition).coerceAtLeast(0L)
            val liveOffsetMs = player.currentLiveOffset
            val liveText = if (liveOffsetMs == C.TIME_UNSET) "n/d" else "${liveOffsetMs / 1000}s"
            val loading = player.isLoading
            playbackInfo.text = "HLS: $state · buffer ${aheadMs / 1000}s · live $liveText · " +
                if (loading) "download attivo" else "download fermo"

            // Non intervenire sul normale riempimento: se il buffer è vuoto e
            // Media3 resta in BUFFERING senza caricare, riaggancia la live window.
            // Se una richiesta è ancora in corso, rispetta prima il timeout HTTP.
            val now = android.os.SystemClock.elapsedRealtime()
            if (player.playWhenReady && player.playbackState == Player.STATE_BUFFERING &&
                aheadMs <= 1_500L && currentPlayingChannelUrl != null &&
                (castPlayer == null || player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_LOCAL)
            ) {
                if (emptyBufferSinceMs == 0L) emptyBufferSinceMs = now
                val stalledFor = now - emptyBufferSinceMs
                val threshold = if (loading) 55_000L else 18_000L
                if (stalledFor >= threshold && now - lastEmptyBufferRecoveryMs >= 45_000L) {
                    lastEmptyBufferRecoveryMs = now
                    emptyBufferSinceMs = now
                    Log.w(
                        "DAVIX-HLS",
                        "buffer live esaurito senza refill; loading=$loading stalledMs=$stalledFor liveOffsetMs=${player.currentLiveOffset}"
                    )
                    // Il seek al default position forza Media3 a usare la finestra
                    // live corrente, poi prepare riavvia il caricamento dei segmenti.
                    if (player.currentLiveOffset != C.TIME_UNSET) player.seekToDefaultPosition()
                    player.prepare()
                    player.playWhenReady = true
                    if (::status.isInitialized) status.text = "Buffer esaurito: recupero automatico del flusso…"
                }
            } else {
                emptyBufferSinceMs = 0L
            }
            retryHandler.postDelayed(this, 1000L)
        }
    }

    companion object {
        private const val PICK_FILE = 4101
        private const val SAVE_HTML = 4102
        private const val CYAN = 0xFF39D7FF.toInt()
        private const val BG = 0xFF080B12.toInt()
        private const val PANEL = 0xFF121A29.toInt()
        private const val MUTED = 0xFF9EACC0.toInt()
        private const val CHANNELS_PER_FOLDER_PAGE = 100
        private const val FOLDERS_PER_RENDER_PAGE = 80
        private const val CHANNELS_PER_RENDER_PAGE = 120
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadCategorySettings()
        window.statusBarColor = BG
        window.navigationBarColor = BG
        window.decorView.systemUiVisibility = 0
        // Profilo più leggero per dispositivi TV/Android con memoria limitata.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                60_000, // buffer minimo: non lasciare scaricare a zero
                240_000, // max: riserva ampia
                6_000, // avvio
                30_000 // dopo rebuffer
            )
            .setTargetBufferBytes(128 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        // Dopo ogni rebuffer Media3 aumenta il margine dal bordo live, così il
        // player smette di inseguire segmenti pubblicati in ritardo dal server.
        val liveSpeedControl = DefaultLivePlaybackSpeedControl.Builder()
            .setFallbackMinPlaybackSpeed(0.97f)
            .setFallbackMaxPlaybackSpeed(1.04f)
            .setTargetLiveOffsetIncrementOnRebufferMs(40_000)
            .build()

        // Usa uno User-Agent Android/browser invece di identificare le richieste
        // come DAVIX: alcuni provider accettano Wiseplay/browser e rifiutano UA custom.
        val defaultUserAgent = runCatching {
            android.webkit.WebSettings.getDefaultUserAgent(this)
        }.getOrDefault("Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome Safari/537.36")
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(defaultUserAgent)
            .setDefaultRequestProperties(mapOf("Accept" to "*/*"))
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(45_000)
            .setAllowCrossProtocolRedirects(true)

        // La factory standard riconosce HLS, DASH e flussi progressivi:
        // non forziamo più ogni URL a essere interpretato come HLS.
        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(httpDataSourceFactory)
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(8))

        localPlayer = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setLivePlaybackSpeedControl(liveSpeedControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()

        // Diagnostica HLS: registra manifest/segmenti e problemi HTTP senza
        // esporre URL completi o token di accesso nei log.
        localPlayer.addAnalyticsListener(object : AnalyticsListener {
            override fun onLoadStarted(
                eventTime: AnalyticsListener.EventTime,
                loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData
            ) {
                val kind = when (mediaLoadData.dataType) {
                    C.DATA_TYPE_MANIFEST -> "manifest"
                    C.DATA_TYPE_MEDIA -> "segment"
                    else -> "type-" + mediaLoadData.dataType
                }
                Log.d(
                    "DAVIX-HLS",
                    "load start=" + kind + " host=" + (loadEventInfo.dataSpec.uri.host ?: "?")
                )
            }

            override fun onLoadCompleted(
                eventTime: AnalyticsListener.EventTime,
                loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData
            ) {
                val kind = when (mediaLoadData.dataType) {
                    C.DATA_TYPE_MANIFEST -> "manifest"
                    C.DATA_TYPE_MEDIA -> "segment"
                    else -> "type-" + mediaLoadData.dataType
                }
                Log.d(
                    "DAVIX-HLS",
                    "load=" + kind + " host=" + (loadEventInfo.dataSpec.uri.host ?: "?") +
                        " bytes=" + loadEventInfo.bytesLoaded +
                        " durationMs=" + loadEventInfo.loadDurationMs
                )
            }

            override fun onLoadError(
                eventTime: AnalyticsListener.EventTime,
                loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData,
                error: IOException,
                wasCanceled: Boolean
            ) {
                Log.w(
                    "DAVIX-HLS",
                    "load error host=" + (loadEventInfo.dataSpec.uri.host ?: "?") +
                        " canceled=" + wasCanceled + " " +
                        error.javaClass.simpleName + ": " +
                        (error.message ?: "").replace(Regex("https?://\\S+"), "[indirizzo omesso]")
                )
            }
        })
        // Google Cast è opzionale: un problema del framework Cast non deve
        // impedire l'avvio dell'estrattore o la riproduzione locale.
        player = try {
            CastPlayer.Builder(this).setLocalPlayer(localPlayer).build().also { castPlayer = it }
        } catch (e: Exception) {
            castInitWarning = e.message ?: e.javaClass.simpleName
            localPlayer
        } catch (e: LinkageError) {
            castInitWarning = e.message ?: e.javaClass.simpleName
            localPlayer
        }
        player.playWhenReady = false
        buildInterface()
        restoreChannelLibraryAsync()
        castInitWarning?.let { status.text = "Google Cast non disponibile; riproduzione locale attiva." }
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val detail = generateSequence(error as Throwable?) { it.cause }
                    .take(4)
                    .mapNotNull { it.message?.takeIf { message -> message.isNotBlank() } }
                    .map { it.replace(Regex("https?://\\S+"), "[indirizzo omesso]") }
                    .joinToString(" → ")
                Log.e(
                    "DAVIX-HLS",
                    "player error code=" + error.errorCodeName +
                        " positionMs=" + player.currentPosition +
                        " bufferedMs=" + player.totalBufferedDuration +
                        " liveOffsetMs=" + player.currentLiveOffset
                )
                val httpStatus = generateSequence(error as Throwable?) { it.cause }
                    .mapNotNull { (it as? HttpDataSource.InvalidResponseCodeException)?.responseCode }
                    .firstOrNull()
                val retryable = if (httpStatus != null) {
                    httpStatus == 408 || httpStatus == 425 || httpStatus == 429 || httpStatus in 500..599
                } else {
                    error.errorCodeName.contains("IO_") ||
                        error.errorCodeName.contains("BEHIND_LIVE_WINDOW")
                }
                if (retryable) {
                    val delays = longArrayOf(2_000L, 4_000L, 8_000L, 12_000L, 15_000L, 30_000L)
                    val delay = delays[playbackRetryCount.coerceAtMost(delays.lastIndex)]
                    playbackRetryCount = (playbackRetryCount + 1).coerceAtMost(10_000)
                    val failedGeneration = playbackGeneration
                    status.text = "Connessione instabile. Nuovo tentativo tra " + (delay / 1000) + " s…"
                    playbackRetryTask?.let { retryHandler.removeCallbacks(it) }
                    val task = Runnable {
                        if (!isFinishing && !isDestroyed && ::player.isInitialized &&
                            failedGeneration == playbackGeneration
                        ) {
                            // Un seek è necessario solo se ExoPlayer segnala esplicitamente
                            // BEHIND_LIVE_WINDOW: mai durante un normale buffering.
                            if (error.errorCodeName.contains("BEHIND_LIVE_WINDOW")) {
                                Log.w("DAVIX-HLS", "live window persa: riallineamento al punto live predefinito")
                                player.seekToDefaultPosition()
                            }
                            player.prepare()
                            player.playWhenReady = true
                        }
                    }
                    playbackRetryTask = task
                    retryHandler.postDelayed(task, delay)
                } else {
                    status.text = if (httpStatus != null) {
                        "HTTP $httpStatus: " + when (httpStatus) {
                            401 -> "autenticazione richiesta"
                            403 -> "accesso negato dal server"
                            404 -> "flusso o segmento non trovato"
                            410 -> "flusso non più disponibile"
                            429 -> "troppe richieste"
                            in 500..599 -> "server temporaneamente in errore"
                            else -> "risposta non valida"
                        } + ". Verifica la sorgente o seleziona un altro flusso."
                    } else {
                        "Errore ${error.errorCodeName} (${error.errorCode}): " +
                            detail.ifBlank { "flusso interrotto o non compatibile" }
                    }
                }
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_BUFFERING) status.text = "Caricamento del flusso…"
                if (playbackState == Player.STATE_READY) status.text = "Video pronto."
                updateKeepScreenOn()
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                updateKeepScreenOn()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updateKeepScreenOn()
                stablePlaybackTask?.let { retryHandler.removeCallbacks(it) }
                stablePlaybackTask = null
                if (isPlaying) {
                    val task = Runnable {
                        if (::player.isInitialized && player.isPlaying) playbackRetryCount = 0
                    }
                    stablePlaybackTask = task
                    retryHandler.postDelayed(task, 30_000L)
                }
            }
        })
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(source: Intent?) {
        val data = source?.data ?: return
        if (data.scheme != "skyunoextractor" || data.host != "play") return
        val streamUrl = data.getQueryParameter("url")?.takeIf {
            it.startsWith("https://", true) || it.startsWith("http://", true)
        } ?: run {
            status.text = "Collegamento non valido: indirizzo del flusso mancante."
            return
        }
        val channelName = data.getQueryParameter("name")?.takeIf { it.isNotBlank() } ?: "Canale selezionato"
        status.text = "Apertura da player web: $channelName"
        currentPlayingChannelUrl = streamUrl
        if (::currentChannelLabel.isInitialized) currentChannelLabel.text = "▶  IN RIPRODUZIONE · $channelName"
        playbackGeneration++
        try {
            playbackRetryTask?.let { retryHandler.removeCallbacks(it) }
            playbackRetryTask = null
            playbackRetryCount = 0
            emptyBufferSinceMs = 0L
            lastEmptyBufferRecoveryMs = 0L
            val mediaItemBuilder = MediaItem.Builder()
                .setUri(streamUrl)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(channelName).build())
            streamMimeType(streamUrl)?.let { mediaItemBuilder.setMimeType(it) }
            val mediaItem = mediaItemBuilder
                .setLiveConfiguration(
          MediaItem.LiveConfiguration.Builder()
              .setTargetOffsetMs(120_000)
              .setMinOffsetMs(75_000)
              .setMaxOffsetMs(300_000)
              .setMinPlaybackSpeed(0.96f)
              .setMaxPlaybackSpeed(1.03f)
              .build()
      )
                .build()
            player.setMediaItem(mediaItem)
            player.prepare()
            player.playWhenReady = true
        } catch (e: Exception) {
            status.text = "Impossibile avviare il canale: ${e.message}"
        }
    }

    // Mantiene acceso lo schermo durante la riproduzione locale, evitando
    // che il timeout di standby mandi l'Activity in onStop() e metta in pausa il video.
    // Durante Google Cast o quando la riproduzione è in pausa il comportamento resta normale.
    private fun updateKeepScreenOn() {
        val localPlayback = ::player.isInitialized &&
            player.playWhenReady &&
            player.playbackState != Player.STATE_IDLE &&
            player.playbackState != Player.STATE_ENDED &&
            (castPlayer == null || player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_LOCAL)
        if (localPlayback) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun buildInterface() {
        fullscreenContainer = FrameLayout(this)
        mainContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }
        scroll = ScrollView(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(18))
            setBackgroundColor(BG)
        }
        scroll.addView(root)
        mainContainer.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        // Player dock: fixed at the bottom while the channel list scrolls.
        playerDock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(7), dp(10), dp(8))
            setBackgroundColor(PANEL)
            elevation = dp(10).toFloat()
        }
        val dockHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val dockTitle = TextView(this).apply {
            text = "▶  PLAYER"
            textSize = 12f
            setTextColor(CYAN)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        dockHeader.addView(dockTitle, LinearLayout.LayoutParams(0, dp(28), 1f))
        // Non creare la View Cast se il framework non è disponibile:
        // alcuni dispositivi/temi possono fallire già nel costruttore MediaRouteButton.
        if (castPlayer != null) {
            try {
                val castButton = MediaRouteButton(this).apply {
                    contentDescription = "Trasmetti a Google Cast"
                    setBackgroundColor(Color.TRANSPARENT)
                }
                dockHeader.addView(castButton, LinearLayout.LayoutParams(dp(48), dp(42)))
                MediaRouteButtonFactory.setUpMediaRouteButton(this, castButton)
            } catch (e: Exception) {
                castInitWarning = e.message ?: e.javaClass.simpleName
                castPlayer = null
            } catch (e: LinkageError) {
                castInitWarning = e.message ?: e.javaClass.simpleName
                castPlayer = null
            }
        }
        val fullButton = buttonView("⛶ Schermo intero") { toggleFullscreen() }
        dockHeader.addView(fullButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)))
        playerDock.addView(dockHeader)
        currentChannelLabel = TextView(this).apply {
            text = "NESSUN CANALE IN RIPRODUZIONE"
            textSize = 12f
            setTextColor(CYAN)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(dp(4), dp(5), dp(4), dp(7))
            maxLines = 2
        }
        playerDock.addView(currentChannelLabel)
        playerView = PlayerView(this).apply {
            player = this@MainActivity.player
            useController = true
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            setBackgroundColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (resources.configuration.smallestScreenWidthDp < 600) 128 else 176))
        }
        playerDock.addView(playerView)
        playbackInfo = TextView(this).apply {
            text = "HLS: in attesa del flusso"
            textSize = 10f
            setTextColor(MUTED)
            setPadding(dp(3), dp(3), dp(3), dp(2))
        }
        playerDock.addView(playbackInfo)
        mainContainer.addView(playerDock, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        setContentView(mainContainer)

        val title = TextView(this).apply {
            text = "DAVIX EXTRACTOR"
            textSize = 23f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        root.addView(title)
        label("Estrattore playlist e riproduttore HLS nativo", MUTED, 13f)

        section("1 · CARICA LA PLAYLIST")
        label("Importa da un file oppure collega direttamente la tua playlist IPTV e la guida XMLTV tramite URL.", MUTED)
        button("🌐  Importa playlist M3U da URL", true) { importPlaylistFromUrl() }
        button("🤖  Importa report Xtream · automatico", true) { showXtreamReportImportDialog() }
        button("🇮🇹  Importa server Vegeta IT", true) { importVegetaItServers() }
        button("🔗  Configura URL M3U ed EPG", false) { showIptvSourceSettings() }
        button("📂  Importa da file", false) { showImportDestinationDialog() }
        status = label("Nessun file caricato.", MUTED)
        importProgressPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setBackgroundColor(PANEL)
            visibility = View.GONE
        }
        importProgressLabel = TextView(this).apply {
            text = "Preparazione importazione…"
            textSize = 12f
            setTextColor(Color.WHITE)
        }
        importProgressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = true
            progressTintList = android.content.res.ColorStateList.valueOf(CYAN)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0xFF34445A.toInt())
        }
        importProgressPanel.addView(importProgressLabel)
        importProgressPanel.addView(importProgressBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)).apply { topMargin = dp(7) })
        root.addView(importProgressPanel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })

        section("2 · PLAYLIST SALVATE")
        label("Le playlist salvate restano sul dispositivo anche dopo la chiusura e il riavvio. Si eliminano solo su tua richiesta.", MUTED, 12f)
        savedPlaylistsList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(savedPlaylistsList)
        button("🗑  Svuota archivio e lista canali", false) { confirmClearSavedPlaylists() }
        renderSavedPlaylists()

        section("3 · CANALI E SELEZIONE")
        count = label("0 canali importati", CYAN)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(buttonView("Seleziona tutti") { channels.forEach { it.selected = true }; persistChannelLibrary(); renderChannels() }, LinearLayout.LayoutParams(0, dp(46), 1f))
        row.addView(space(8), LinearLayout.LayoutParams(dp(8), 1))
        row.addView(buttonView("Deseleziona") { channels.forEach { it.selected = false }; persistChannelLibrary(); renderChannels() }, LinearLayout.LayoutParams(0, dp(46), 1f))
        root.addView(row)
        button("▦  Gestisci categorie e sottocartelle", false) { showCategoryManager() }
        button("🔎  Verifica disponibilità flussi · sequenziale", false) { scanImportedStreams() }
        label("Usa il formato Sezione > Sottosezione, ad esempio Intrattenimento > Cinema.", MUTED, 12f)
        val bulkActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bulkActions.addView(buttonView("☑  Selezione multipla") {
            bulkSelectionMode = !bulkSelectionMode
            if (!bulkSelectionMode) bulkSelectedIndices.clear()
            renderChannels()
        }, LinearLayout.LayoutParams(0, dp(46), 1f))
        bulkActions.addView(space(8), LinearLayout.LayoutParams(dp(8), 1))
        bulkActions.addView(buttonView("Sposta selezionati") { moveBulkSelectedChannels() }, LinearLayout.LayoutParams(0, dp(46), 1f))
        root.addView(bulkActions)

        selectedHeading = TextView(this).apply {
            text = "✓  SELEZIONATI PER ESPORTAZIONE"
            textSize = 12f
            setTextColor(CYAN)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, dp(16), 0, dp(6))
        }
        root.addView(selectedHeading)
        selectedList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(selectedList)
        otherHeading = TextView(this).apply {
            text = "ALTRI CANALI"
            textSize = 12f
            setTextColor(MUTED)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, dp(14), 0, dp(6))
        }
        root.addView(otherHeading)
        otherList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(otherList)
        button("⬇  Esporta player HTML", true) { requestHtmlExport() }
        label("Suggerimento: tocca il nome di un canale per riprodurlo; usa la casella per includerlo o escluderlo dall'esportazione.", MUTED, 12f)

        section("4 · DAVIX CLOUD SYNC")
        label("Pubblica la configurazione selezionata per renderla disponibile ai soli Player che autorizzi. Il servizio trasferisce la lista, mai il video.", MUTED, 12f)
        button("⚙  Configura DAVIX Cloud Sync", false) { showCloudSyncSettings() }
        button("☁  Pubblica aggiornamento remoto", true) { publishRemoteConfiguration() }
        val adminRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        adminRow.addView(buttonView("Genera codice") { approveCloudPlayer() }, LinearLayout.LayoutParams(0, dp(46), 1f))
        adminRow.addView(space(8), LinearLayout.LayoutParams(dp(8), 1))
        adminRow.addView(buttonView("Gestisci dispositivi") { showCloudDevices() }, LinearLayout.LayoutParams(0, dp(46), 1f))
        root.addView(adminRow)
        button("⬇  Aggiornamenti DAVIX", false) {
            (application as DavixUpdaterApplication).showDownloads(this@MainActivity)
        }
        // Mostra subito l'archivio ripristinato, senza richiedere una nuova importazione.
        renderChannels()
    }

    private fun updateImportProgress(message: String, percent: Int? = null) {
        val update = Runnable {
            if (!::importProgressPanel.isInitialized || isFinishing || isDestroyed) return@Runnable
            importProgressPanel.visibility = View.VISIBLE
            importProgressLabel.text = message
            importProgressBar.isIndeterminate = percent == null
            if (percent != null) importProgressBar.progress = percent.coerceIn(0, 100)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) update.run() else runOnUiThread(update)
    }

    private fun finishImportProgress() {
        if (!::importProgressPanel.isInitialized) return
        importProgressPanel.visibility = View.GONE
        importProgressBar.isIndeterminate = false
        importProgressBar.progress = 0
    }

    private fun showIptvSourceSettings() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }
        val playlistInput = EditText(this).apply {
            hint = "http://server/get.php?...type=m3u_plus"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
            setText(IptvSourceSettings.playlistUrl(this@MainActivity))
        }
        val epgInput = EditText(this).apply {
            hint = "http://server/xmltv.php?username=...&password=..."
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
            setText(IptvSourceSettings.epgUrl(this@MainActivity))
        }
        content.addView(labelForDialog("URL PLAYLIST M3U · credenziali nascoste"))
        content.addView(playlistInput)
        content.addView(labelForDialog("URL GUIDA XMLTV · credenziali nascoste"))
        content.addView(epgInput)
        AlertDialog.Builder(this)
            .setTitle("Sorgenti IPTV / EPG")
            .setView(content)
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Salva in modo protetto", null)
            .create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        try {
                            IptvSourceSettings.save(this, playlistInput.text.toString(), epgInput.text.toString())
                            dialog.dismiss()
                            toast("Sorgenti salvate in modo protetto.")
                        } catch (e: Exception) {
                            toast(e.message ?: "Indirizzo non valido.")
                        }
                    }
                }
                dialog.show()
            }
    }
    private fun importPlaylistFromUrl() {
        importCatalogFromUrl(null, "Playlist URL")
    }

    private fun importCatalogFromUrl(addressOverride: String?, sourceLabel: String) {
        if (importInProgress) { toast("È già in corso un'importazione."); return }
        val address = addressOverride ?: IptvSourceSettings.playlistUrl(this)
        if (address.isBlank()) {
            toast("Configura prima l'URL della playlist M3U.")
            showIptvSourceSettings()
            return
        }
        importInProgress = true
        status.text = "Download catalogo $sourceLabel in corso…"
        updateImportProgress("Connessione alla sorgente $sourceLabel…", null)
        Thread({
            try {
                val connection = (URL(address).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15_000
                    readTimeout = 60_000
                    instanceFollowRedirects = true
                    setRequestProperty("Accept", "audio/x-mpegurl,application/vnd.apple.mpegurl,text/plain,*/*")
                    setRequestProperty("Accept-Encoding", "identity")
                    setRequestProperty("User-Agent", "DAVIX-Extractor/1.0")
                }
                val bytes = try {
                    val code = connection.responseCode
                    if (code !in 200..299) throw IOException("Il server IPTV ha risposto HTTP $code.")
                    val expectedBytes = connection.getHeaderFieldLong("Content-Length", -1L)
                    val output = ByteArrayOutputStream()
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(32 * 1024)
                        var total = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > 64L * 1024L * 1024L) throw IOException("La playlist supera il limite di 64 MiB.")
                            output.write(buffer, 0, count)
                            val percent = if (expectedBytes > 0L) ((total * 30L / expectedBytes).toInt()).coerceIn(1, 30) else null
                            updateImportProgress("Download playlist · ${total / (1024 * 1024)} MB${if (expectedBytes > 0L) " / ${expectedBytes / (1024 * 1024)} MB" else " ricevuti"}", percent)
                        }
                    }
                    output.toByteArray()
                } finally {
                    connection.disconnect()
                }
                updateImportProgress("Download completato · analisi dei canali…", 32)
                val text = String(bytes, StandardCharsets.UTF_8).removePrefix("\uFEFF")
                val found = parseChannels(text) { parsed ->
                    updateImportProgress("Analisi playlist · $parsed%", 32 + (parsed * 0.48).toInt())
                }
                if (found.isEmpty()) throw IllegalStateException("Il collegamento non ha restituito una playlist M3U valida.")
                updateImportProgress("Analisi completata · classificazione e controllo duplicati…", 85)
                savePlaylistCopy("playlist-remota.m3u", bytes)

                // La classificazione URL e il controllo duplicati sono CPU-bound:
                // eseguirli sul thread UI causava ANR con playlist molto grandi.
                runOnUiThread {
                    if (isFinishing || isDestroyed) {
                        importInProgress = false
                        return@runOnUiThread
                    }
                    val existingCategoriesByKey = channels.associate {
                        (it.name.lowercase(Locale.ROOT) + "|" + it.url) to it.category
                    }
                    val knownKeysSnapshot = existingCategoriesByKey.keys.toHashSet()
                    val savedMappingJson = categoryPrefs.getString("channel_categories_json", "{}") ?: "{}"
                    val knownCategoriesSnapshot = categories.toHashSet()

                    Thread({
                        try {
                            data class PreparedChannel(
                                val channel: TvChannel,
                                val key: String,
                                val category: String,
                                val duplicate: Boolean
                            )
                            val savedCategoryMapping = runCatching { JSONObject(savedMappingJson) }.getOrDefault(JSONObject())
                            val knownChannelKeys = knownKeysSnapshot.toMutableSet()
                            val prepared = ArrayList<PreparedChannel>(found.size)
                            val knownCategoryPaths = knownCategoriesSnapshot.toMutableSet()

                            found.forEachIndexed { index, channel ->
                                val sourceGroup = channel.category
                                val savedCategory = normalizeCategoryPath(savedCategoryForUrl(savedCategoryMapping, channel.url))
                                val inferred = inferUrlCategoryPath(channel.copy(category = sourceGroup))
                                val savedPath = normalizeCategoryPath(savedCategory)
                                val sourcePath = normalizeCategoryPath(sourceGroup)
                                val isLegacyProviderGroup = savedPath.isNotBlank() &&
                                    savedPath.equals(sourcePath, ignoreCase = true) &&
                                    !savedPath.contains(" > ")
                                val preserveManualCategory = savedCategory.isNotBlank() &&
                                    !isAutomaticallyClassifiedUrlCategory(savedCategory)
                                val resolvedCategory = if (preserveManualCategory && !isLegacyProviderGroup) savedCategory else inferred
                                channel.category = resolvedCategory
                                knownCategoryPaths.add(resolvedCategory)
                                val key = channel.name.lowercase(Locale.ROOT) + "|" + channel.url
                                val duplicate = !knownChannelKeys.add(key)
                                prepared.add(PreparedChannel(channel, key, resolvedCategory, duplicate))
                                if (index % 500 == 0) {
                                    updateImportProgress("Classificazione playlist · $index / ${found.size}", 85 + (index * 10 / found.size.coerceAtLeast(1)))
                                }
                            }

                            runOnUiThread {
                                if (isFinishing || isDestroyed) {
                                    importInProgress = false
                                    return@runOnUiThread
                                }
                                val existingChannelsByKey = channels.associateBy {
                                    it.name.lowercase(Locale.ROOT) + "|" + it.url
                                }.toMutableMap()
                                var added = 0
                                var duplicates = 0
                                var reorganized = 0
                                var processed = 0
                                val batchSize = 120
                                val importHandler = Handler(Looper.getMainLooper())
                                val importBatch = object : Runnable {
                                    override fun run() {
                                        if (isFinishing || isDestroyed) {
                                            importInProgress = false
                                            return
                                        }
                                        val batchStartedAt = android.os.SystemClock.uptimeMillis()
                                        var inBatch = 0
                                        while (processed < prepared.size && inBatch < batchSize &&
                                            android.os.SystemClock.uptimeMillis() - batchStartedAt < 12L
                                        ) {
                                            val item = prepared[processed]
                                            val channel = item.channel
                                            if (item.duplicate) {
                                                duplicates++
                                                val existing = existingChannelsByKey[item.key]
                                                val oldPath = normalizeCategoryPath(existing?.category.orEmpty())
                                                if (existing != null &&
                                                    isAutomaticallyClassifiedUrlCategory(oldPath) &&
                                                    existing.category != channel.category
                                                ) {
                                                    existing.category = channel.category
                                                    reorganized++
                                                }
                                            } else {
                                                channel.selected = false
                                                channels.add(channel)
                                                existingChannelsByKey[item.key] = channel
                                                added++
                                            }
                                            processed++
                                            inBatch++
                                        }

                                        if (processed < prepared.size) {
                                            updateImportProgress(
                                                "Inserimento canali · $processed / ${prepared.size}",
                                                95 + (processed * 4 / prepared.size.coerceAtLeast(1))
                                            )
                                            importHandler.postDelayed(this, 8L)
                                        } else {
                                            // Rimuove solo le cartelle automatiche ormai vuote.
                                            categories.removeAll { category ->
                                                isAutomaticallyClassifiedUrlCategory(category) &&
                                                    channels.none { it.category == category }
                                            }
                                            renderChannels()
                                            saveCategorySettings()
                                            persistChannelLibrary()
                                            renderSavedPlaylists()
                                            importInProgress = false
                                            finishImportProgress()
                                            status.text = "Catalogo $sourceLabel analizzato · ${found.size} elementi letti · $added nuovi · $reorganized riclassificati · $duplicates già presenti."
                                        }
                                    }
                                }
                                importHandler.post(importBatch)
                            }
                        } catch (error: Exception) {
                            runOnUiThread {
                                importInProgress = false
                                finishImportProgress()
                                if (!isFinishing && !isDestroyed) {
                                    status.text = "Analisi playlist non riuscita: " + (error.message ?: "errore inatteso")
                                    toast(status.text.toString())
                                }
                            }
                        }
                    }, "DAVIX-Playlist-Analysis").apply { isDaemon = true }.start()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    importInProgress = false
                    finishImportProgress()
                    if (!isFinishing && !isDestroyed) {
                        status.text = "Download $sourceLabel non riuscito: " + (error.message ?: "errore di rete")
                        toast(status.text.toString())
                    }
                }
            }
        }, "DAVIX-Playlist-Download").apply { isDaemon = true }.start()
    }

    private fun showXtreamReportImportDialog() {
        if (importInProgress) {
            toast("È già in corso un'importazione.")
            return
        }
        val reportInput = EditText(this).apply {
            hint = "Incolla qui il report Xtream completo (server, username, password e categorie)…"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            minLines = 7
            maxLines = 14
            gravity = Gravity.TOP or Gravity.START
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setTextColor(Color.WHITE)
            setHintTextColor(MUTED)
            setBackgroundColor(PANEL)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(TextView(this@MainActivity).apply {
                text = "DAVIX legge il report sul dispositivo. Le credenziali vengono salvate cifrate con Android Keystore e non inviate ad alcun servizio AI."
                textSize = 12f
                setTextColor(MUTED)
                setPadding(0, 0, 0, dp(10))
            })
            addView(reportInput, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            addView(TextView(this@MainActivity).apply {
                text = "Vengono recuperati esclusivamente i canali Live italiani. Film, VOD, serie ed episodi non vengono richiesti né importati. La verifica non apre tutti i flussi: evita di consumare le connessioni contemporanee del tuo abbonamento."
                textSize = 11f
                setTextColor(MUTED)
                setPadding(0, dp(10), 0, 0)
            })
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Importazione automatica Xtream")
            .setView(content)
            .setNegativeButton("Annulla", null)
            .setNeutralButton("Aggiorna sorgente salvata", null)
            .setPositiveButton("Analizza e importa", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val savedSource = IptvSourceSettings.xtreamSource(this)
                if (savedSource == null) {
                    toast("Non è ancora presente una sorgente Xtream salvata.")
                    return@setOnClickListener
                }
                dialog.dismiss()
                startXtreamAutomaticImport(savedSource)
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val source = try {
                    XtreamReportParser.parse(reportInput.text.toString())
                } catch (error: Exception) {
                    reportInput.error = error.message ?: "Report non valido."
                    return@setOnClickListener
                }
                try {
                    IptvSourceSettings.saveXtreamSource(this, source)
                } catch (error: Exception) {
                    toast(error.message ?: "Impossibile salvare la sorgente in modo protetto.")
                    return@setOnClickListener
                }
                dialog.dismiss()
                startXtreamAutomaticImport(source)
            }
        }
        dialog.show()
    }

    private fun startXtreamAutomaticImport(source: XtreamReportSource) {
        if (importInProgress) {
            toast("È già in corso un'importazione.")
            return
        }
        importInProgress = true
        status.text = "Connessione alla sorgente Xtream…"
        updateImportProgress("Verifica account e preparazione catalogo…", null)
        Thread({
            var temporaryCatalog: java.io.File? = null
            try {
                val result = XtreamCatalogClient(source) { message ->
                    updateImportProgress(message, null)
                }.downloadCatalog()
                cacheDir.listFiles()
                    ?.filter { it.isFile && it.name.startsWith("davix-xtream-") && it.name.endsWith(".m3u") }
                    ?.forEach { it.delete() }
                val bytes = result.m3u.toByteArray(StandardCharsets.UTF_8)
                val importedFile = java.io.File(cacheDir, "davix-xtream-${System.currentTimeMillis()}.m3u")
                importedFile.writeBytes(bytes)
                temporaryCatalog = importedFile
                runOnUiThread {
                    if (isFinishing || isDestroyed) {
                        importInProgress = false
                        return@runOnUiThread
                    }
                    importInProgress = false
                    status.text = "Canali Live italiani recuperati: ${result.liveCount} · esclusi non italiani: ${result.excludedNonItalianCount}. Inserimento senza sovrascrivere l'archivio…"
                    loadSavedPlaylist(importedFile, deleteAfterImport = true)
                }
            } catch (error: Exception) {
                temporaryCatalog?.delete()
                runOnUiThread {
                    importInProgress = false
                    finishImportProgress()
                    if (!isFinishing && !isDestroyed) {
                        status.text = "Importazione Xtream non riuscita: ${error.message ?: "errore di rete"}"
                        toast(status.text.toString())
                    }
                }
            }
        }, "DAVIX-Xtream-AutoImport").apply { isDaemon = true }.start()
    }

    private fun importVegetaItServers() {
        if (importInProgress) {
            toast("È già in corso un'importazione.")
            return
        }
        importInProgress = true
        status.text = "Connessione al catalogo Vegeta IT…"
        updateImportProgress("Scarico elenco server italiani Vegeta…", null)
        Thread({
            var temporaryCatalog: java.io.File? = null
            try {
                val result = VegetaItImporter.importAll(this) { message ->
                    updateImportProgress(message, null)
                }
                val vegetaChannels = VegetaItPlaylistStore.load(this)
                val m3u = StringBuilder("#EXTM3U\n")
                for (ch in vegetaChannels) {
                    m3u.append("#EXTINF:-1")
                    if (ch.logo.startsWith("http://", true) || ch.logo.startsWith("https://", true)) {
                        m3u.append(" tvg-logo=\"").append(ch.logo.replace("\"", "&quot;")).append('"')
                    }
                    val group = ch.category.ifBlank { "Vegeta IT" }
                    m3u.append(" group-title=\"").append(group.replace("\"", "&quot;")).append("\",")
                        .append(ch.name.replace('\n', ' ').replace('\r', ' '))
                        .append('\n').append(ch.url).append('\n')
                }
                cacheDir.listFiles()
                    ?.filter { it.isFile && it.name.startsWith("davix-vegeta-") && it.name.endsWith(".m3u") }
                    ?.forEach { it.delete() }
                val importedFile = java.io.File(cacheDir, "davix-vegeta-${System.currentTimeMillis()}.m3u")
                importedFile.writeBytes(m3u.toString().toByteArray(StandardCharsets.UTF_8))
                temporaryCatalog = importedFile
                runOnUiThread {
                    if (isFinishing || isDestroyed) {
                        importInProgress = false
                        return@runOnUiThread
                    }
                    importInProgress = false
                    val errHint = if (result.errors.isEmpty()) "" else " · avvisi: ${result.errors.size}"
                    status.text = "Vegeta IT: ${result.channelsImported} canali da ${result.serversOk}/${result.serversTried} server$errHint. Inserimento in sezione separata…"
                    pendingImportCategory = "Vegeta IT"
                    loadSavedPlaylist(importedFile, deleteAfterImport = true)
                }
            } catch (error: Exception) {
                temporaryCatalog?.delete()
                runOnUiThread {
                    importInProgress = false
                    finishImportProgress()
                    if (!isFinishing && !isDestroyed) {
                        status.text = "Importazione Vegeta IT non riuscita: ${error.message ?: "errore di rete"}"
                        toast(status.text.toString())
                    }
                }
            }
        }, "DAVIX-Vegeta-Import").apply { isDaemon = true }.start()
    }

    private fun scanImportedStreams() {
        if (importInProgress) {
            toast("Attendi la fine dell'operazione in corso.")
            return
        }
        if (::player.isInitialized && player.playWhenReady &&
            player.playbackState != Player.STATE_IDLE && player.playbackState != Player.STATE_ENDED
        ) {
            toast("Ferma prima la riproduzione: la verifica usa una connessione alla volta.")
            return
        }
        val snapshot = channels.map { it.copy() }
        if (snapshot.isEmpty()) {
            toast("Importa prima una playlist o un catalogo.")
            return
        }
        streamScanCancelled = false
        importInProgress = true
        updateImportProgress("Preparazione verifica sequenziale di ${snapshot.size} flussi…", 0)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Verifica disponibilità flussi")
            .setMessage("Controllo uno stream alla volta. Ogni richiesta viene chiusa prima della successiva, per rispettare gli account con una sola connessione.")
            .setNegativeButton("Interrompi", null)
            .setCancelable(false)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { button ->
                streamScanCancelled = true
                button.isEnabled = false
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).text = "Interruzione…"
            }
        }
        dialog.show()

        Thread({
            val counts = mutableMapOf<StreamCheckState, Int>()
            val unavailable = ArrayList<String>()
            val prefs = getSharedPreferences("davix_stream_scan_v1", MODE_PRIVATE)
            val pendingCache = LinkedHashMap<String, String>()
            var problemCount = 0
            var checked = 0
            for (index in snapshot.indices) {
                if (streamScanCancelled) break
                val channel = snapshot[index]
                val result = XtreamStreamScanner.check(channel.url)
                counts[result.state] = (counts[result.state] ?: 0) + 1
                val cacheValue = result.state.name + "|" + System.currentTimeMillis()
                pendingCache[XtreamStreamScanner.cacheKey(channel.url)] = cacheValue
                if (pendingCache.size >= 50) {
                    val editor = prefs.edit()
                    pendingCache.forEach { (key, value) -> editor.putString(key, value) }
                    editor.apply()
                    pendingCache.clear()
                }
                if (result.state != StreamCheckState.REACHABLE &&
                    result.state != StreamCheckState.PLAYLIST_AND_SEGMENT
                ) {
                    problemCount++
                    if (unavailable.size < 60) unavailable.add("• ${channel.name}: ${result.state.label}")
                }
                checked++
                if (checked % 5 == 0 || checked == snapshot.size) {
                    val current = checked
                    val summary = "Verificati $current / ${snapshot.size} · raggiungibili ${(counts[StreamCheckState.REACHABLE] ?: 0) + (counts[StreamCheckState.PLAYLIST_AND_SEGMENT] ?: 0)} · problemi $problemCount"
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            dialog.setMessage(summary + if (streamScanCancelled) "\nInterruzione richiesta…" else "\nUn solo flusso alla volta · nessuna riproduzione simultanea.")
                            updateImportProgress(summary, current * 100 / snapshot.size.coerceAtLeast(1))
                        }
                    }
                }
            }
            if (pendingCache.isNotEmpty()) {
                val editor = prefs.edit()
                pendingCache.forEach { (key, value) -> editor.putString(key, value) }
                editor.apply()
            }
            runOnUiThread {
                importInProgress = false
                finishImportProgress()
                if (isFinishing || isDestroyed) return@runOnUiThread
                dialog.dismiss()
                val reachable = (counts[StreamCheckState.REACHABLE] ?: 0) +
                    (counts[StreamCheckState.PLAYLIST_AND_SEGMENT] ?: 0)
                val interrupted = streamScanCancelled
                status.text = "Verifica completata: $checked / ${snapshot.size} · raggiungibili $reachable · problemi ${checked - reachable}" +
                    if (interrupted) " · interrotta su richiesta" else ""
                val report = StringBuilder()
                    .append("Controllati: $checked / ${snapshot.size}\n")
                    .append("Raggiungibili: $reachable\n")
                    .append("Con problemi: ${checked - reachable}\n")
                if (interrupted) report.append("Verifica interrotta su richiesta.\n")
                counts.entries.sortedBy { it.key.ordinal }.forEach { (state, amount) ->
                    report.append("\n").append(state.label).append(": ").append(amount)
                }
                if (unavailable.isNotEmpty()) {
                    report.append("\n\nPrimi flussi da controllare:\n")
                    unavailable.forEach { report.append(it).append('\n') }
                    if (checked - reachable > unavailable.size) {
                        report.append("… altri ").append(checked - reachable - unavailable.size).append(" elementi.")
                    }
                }
                AlertDialog.Builder(this)
                    .setTitle("Risultato verifica DAVIX")
                    .setMessage(report.toString())
                    .setPositiveButton("OK", null)
                    .show()
                streamScanCancelled = false
            }
        }, "DAVIX-Stream-Scan").apply { isDaemon = true }.start()
    }

    private fun showCloudSyncSettings() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val endpoint = EditText(this).apply {
            hint = "https://nome-account.workers.dev"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setText(CloudSyncAdmin.baseUrl(this@MainActivity))
        }
        val adminKey = EditText(this).apply {
            hint = "Chiave amministratore (almeno 32 caratteri)"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        content.addView(labelForDialog("Indirizzo del servizio HTTPS"))
        content.addView(endpoint)
        content.addView(labelForDialog("Chiave amministratore · viene salvata cifrata nel dispositivo"))
        content.addView(adminKey)
        AlertDialog.Builder(this)
            .setTitle("Configurazione DAVIX Cloud Sync")
            .setView(content)
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Salva", null)
            .create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        try {
                            CloudSyncAdmin.saveSettings(
                                this,
                                endpoint.text.toString(),
                                adminKey.text.toString().ifBlank { null }
                            )
                            dialog.dismiss()
                            toast("Configurazione Cloud Sync salvata in modo protetto.")
                        } catch (e: Exception) {
                            toast(e.message ?: "Configurazione non valida.")
                        }
                    }
                }
                dialog.show()
            }
    }

    private fun labelForDialog(value: String): TextView = TextView(this).apply {
        text = value
        textSize = 12f
        setTextColor(MUTED)
        setPadding(0, dp(10), 0, dp(2))
    }

    private fun cloudSyncErrorMessage(error: Exception): String = when (error) {
        is java.net.SocketTimeoutException ->
            "Timeout: il Worker DAVIX Cloud Sync non ha risposto in tempo. Verifica la connessione e riprova."
        is IOException ->
            "Worker DAVIX Cloud Sync non raggiungibile. Controlla rete, DNS e indirizzo HTTPS del servizio."
        else -> error.message?.takeIf { it.isNotBlank() } ?: "Errore imprevisto di DAVIX Cloud Sync."
    }

    private fun runCloudAdminTask(task: () -> JSONObject, onSuccess: (JSONObject) -> Unit) {
        status.text = "Connessione a DAVIX Cloud Sync…"
        Thread {
            try {
                val result = task()
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) onSuccess(result)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        status.text = "Cloud Sync: ${cloudSyncErrorMessage(e)}"
                        toast(status.text.toString())
                    }
                }
            }
        }.start()
    }

    private fun publishRemoteConfiguration() {
        val chosen = selectedChannels()
        if (chosen.isEmpty()) {
            toast("Seleziona almeno un canale da pubblicare.")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Pubblicare ${chosen.size} canali?")
            .setMessage("La nuova configurazione diventerà disponibile ai Player autorizzati al loro prossimo avvio. La lista attualmente salvata sui Player resterà disponibile in caso di errore.")
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Pubblica") { _, _ ->
                val payload = JSONArray()
                chosen.forEachIndexed { index, channel ->
                    payload.put(
                        JSONObject()
                            .put("id", "channel-$index")
                            .put("name", channel.name)
                            .put("url", IptvSourceSettings.protectLocalValue(channel.url))
                            .put("logo", channel.logo)
                            .put("category", channel.category)
                            .put("tvgId", channel.tvgId)
                            .put("tvgName", channel.tvgName)
                    )
                }
                runCloudAdminTask(
                    task = {
                        CloudSyncAdmin.publish(
                            this,
                            payload,
                            JSONArray(categories),
                            IptvSourceSettings.epgUrl(this).takeIf { it.isNotBlank() }
                        )
                    },
                    onSuccess = { result ->
                        status.text = "Pubblicazione completata · versione ${result.optInt("version")} · ${result.optInt("channelCount")} canali."
                        toast(status.text.toString())
                    }
                )
            }
            .show()
    }

    private fun approveCloudPlayer() {
        runCloudAdminTask(
            task = { CloudSyncAdmin.createActivationCode(this) },
            onSuccess = { result ->
                val code = result.optString("code")
                val content = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(24), dp(12), dp(24), dp(8))
                    addView(TextView(this@MainActivity).apply {
                        text = code
                        textSize = 30f
                        letterSpacing = 0.16f
                        setTextColor(CYAN)
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        gravity = Gravity.CENTER
                    })
                    addView(labelForDialog("Codice monouso · valido 10 minuti · utilizzabile su un solo dispositivo"))
                }
                AlertDialog.Builder(this)
                    .setTitle("Nuovo codice di attivazione")
                    .setView(content)
                    .setPositiveButton("Copia codice") { _, _ ->
                        val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Codice DAVIX Player", code))
                        toast("Codice copiato. Comunicalo al dispositivo da attivare.")
                    }
                    .setNegativeButton("Chiudi", null)
                    .show()
            }
        )
    }

    private fun showCloudDevices() {
        status.text = "Caricamento dispositivi…"
        Thread {
            try {
                val result = CloudSyncAdmin.devices(this)
                val authorized = result.optJSONArray("authorizedDevices") ?: JSONArray()
                val revoked = result.optJSONArray("revokedDevices") ?: JSONArray()
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    val panel = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(20), dp(8), dp(20), dp(4))
                    }
                    val activeButton = Button(this).apply {
                        text = "AUTORIZZATI  ·  ${authorized.length()}"
                        isAllCaps = false
                        setOnClickListener { showDeviceGroup("Dispositivi autorizzati", authorized, true) }
                    }
                    val revokedButton = Button(this).apply {
                        text = "REVOCATI  ·  ${revoked.length()}"
                        isAllCaps = false
                        setOnClickListener { showDeviceGroup("Dispositivi revocati", revoked, false) }
                    }
                    panel.addView(activeButton)
                    panel.addView(revokedButton)
                    AlertDialog.Builder(this)
                        .setTitle("Gestione dispositivi")
                        .setView(panel)
                        .setNegativeButton("Chiudi", null)
                        .show()
                }
            } catch (e: Exception) {
                runOnUiThread { if (!isFinishing && !isDestroyed) toast(cloudSyncErrorMessage(e)) }
            }
        }.start()
    }

    private fun showDeviceGroup(title: String, devices: JSONArray, canRevoke: Boolean) {
        if (devices.length() == 0) {
            toast("Nessun dispositivo in questo elenco.")
            return
        }
        val labels = Array(devices.length()) { index ->
            val item = devices.getJSONObject(index)
            val name = item.optString("displayName").ifBlank { item.optString("deviceName") }
            val lastSeen = item.optLong("lastSeenAt", 0L).let {
                if (it > 0) java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it)) else "mai collegato"
            }
            "$name · ${item.optString("platform")}\nUltimo accesso: $lastSeen"
        }
        AlertDialog.Builder(this)
            .setTitle("$title  ·  ${devices.length()}")
            .setItems(labels) { _, index ->
                val item = devices.getJSONObject(index)
                val deviceId = item.optString("deviceId")
                val currentName = item.optString("displayName").ifBlank { item.optString("deviceName") }
                val nameInput = EditText(this).apply {
                    setText(currentName)
                    setSingleLine(true)
                    hint = "Nome dispositivo (max 60 caratteri)"
                    filters = arrayOf(android.text.InputFilter.LengthFilter(60))
                }
                val actions = AlertDialog.Builder(this)
                    .setTitle(currentName)
                    .setMessage("Piattaforma: ${item.optString("platform")}\nStato: ${item.optString("status")}")
                    .setView(nameInput)
                    .setNeutralButton("Rinomina", null)
                if (canRevoke) actions.setPositiveButton("Revoca accesso") { _, _ ->
                    runCloudAdminTask(
                        task = { CloudSyncAdmin.revoke(this, deviceId) },
                        onSuccess = { toast("Dispositivo spostato nell'elenco revocati."); showCloudDevices() }
                    )
                } else actions.setPositiveButton("Chiudi", null)
                actions.setNegativeButton("Annulla", null)
                actions.create().also { dialog ->
                    dialog.setOnShowListener {
                        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                            val newName = nameInput.text.toString().trim()
                            if (newName.isBlank()) {
                                nameInput.error = "Inserisci un nome riconoscibile"
                            } else {
                                runCloudAdminTask(
                                    task = { CloudSyncAdmin.rename(this, deviceId, newName) },
                                    onSuccess = { toast("Nome dispositivo aggiornato."); showCloudDevices(); dialog.dismiss() }
                                )
                            }
                        }
                    }
                    dialog.show()
                }
            }
            .setNegativeButton("Indietro", null)
            .show()
    }

    private fun section(text: String) {
        val v = TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(CYAN)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, dp(22), 0, dp(8))
        }
        root.addView(v)
    }

    private fun label(text: String, color: Int = Color.WHITE, size: Float = 14f): TextView {
        val v = TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            setPadding(0, dp(5), 0, dp(5))
        }
        root.addView(v)
        return v
    }

    private fun space(width: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(width), 1) }

    private fun buttonView(text: String, action: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            textSize = 12f
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF26364D.toInt()))
            setOnClickListener { action() }
        }

    private fun button(text: String, primary: Boolean, action: () -> Unit) {
        val b = buttonView(text, action).apply {
            textSize = 14f
            setBackgroundTintList(android.content.res.ColorStateList.valueOf(if (primary) 0xFF176D98.toInt() else 0xFF26364D.toInt()))
        }
        root.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply {
            topMargin = dp(8)
        })
    }

    private fun openPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/html", "text/plain", "application/json", "audio/x-mpegurl", "application/vnd.apple.mpegurl"))
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(intent, PICK_FILE)
    }

    @Deprecated("Legacy result API retained for broad Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) {
            if (requestCode == PICK_FILE) pendingImportCategory = null
            return
        }
        when (requestCode) {
            PICK_FILE -> {
                val uris = mutableListOf<Uri>()
                data.clipData?.let { clip -> for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri) }
                data.data?.let { if (uris.isEmpty()) uris.add(it) }
                importUris(uris)
            }
            SAVE_HTML -> {
                data.data?.let { uri ->
                    try {
                        contentResolver.openOutputStream(uri)?.use { it.write(pendingHtml.toByteArray(StandardCharsets.UTF_8)) }
                        toast("Player HTML esportato.")
                    } catch (e: Exception) { toast("Salvataggio non riuscito: ${e.message}") }
                }
            }
        }
    }

    private fun showImportDestinationDialog() {
        // Dialog personalizzato: setMessage() + setItems() sui dialog standard
        // può comprimere/nascondere l'elenco su alcuni temi e dispositivi Android.
        // La lista viene costruita esplicitamente e resta sempre scorrevole.
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(4))
        }
        val description = TextView(this).apply {
            text = "Scegli la sezione in cui inserire i canali. Puoi conservare i gruppi originali della playlist oppure assegnare tutti i canali a una destinazione."
            textSize = 13f
            setTextColor(MUTED)
            setPadding(0, 0, 0, dp(10))
        }
        content.addView(description)

        val choices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scrollChoices = ScrollView(this).apply {
            isFillViewport = true
            addView(choices)
        }
        content.addView(scrollChoices, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(320)
        ))

        val dialog = AlertDialog.Builder(this)
            .setTitle("Destinazione dell'importazione")
            .setView(content)
            .setNegativeButton("Annulla", null)
            .create()

        fun addDestination(label: String, detail: String? = null, action: () -> Unit) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setBackgroundColor(PANEL)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    dialog.dismiss()
                    action()
                }
            }
            val title = TextView(this).apply {
                text = label
                textSize = 14f
                setTextColor(Color.WHITE)
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            item.addView(title)
            if (!detail.isNullOrBlank()) {
                item.addView(TextView(this).apply {
                    text = detail
                    textSize = 11f
                    setTextColor(MUTED)
                })
            }
            choices.addView(item, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) })
        }

        addDestination("Classifica automaticamente per tipologia", "Usa nome canale e group-title per creare cartelle e sottocartelle tematiche.") {
            pendingImportCategory = null
            openPicker()
        }

        val available = categories
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it })
        if (available.isEmpty()) {
            addDestination("Nessuna sezione disponibile", "Crea una nuova sezione per continuare.") { promptNewCategoryForImport() }
        } else {
            available.forEach { category ->
                val depth = category.split(">").size - 1
                val shown = if (depth > 0) ("   ".repeat(depth)) + "↳ " + category.substringAfterLast(">") .trim() else category
                addDestination(shown, if (depth > 0) "Sottocartella · $category" else "Sezione") {
                    pendingImportCategory = category
                    openPicker()
                }
            }
        }

        addDestination("＋  Crea nuova sezione o sottocartella", "Esempio: Intrattenimento > Cinema") {
            promptNewCategoryForImport()
        }
        dialog.show()
    }

    private fun promptNewCategoryForImport() {
        val input = EditText(this).apply {
            hint = "Intrattenimento > Cinema"
            setSingleLine(true)
            filters = arrayOf(android.text.InputFilter.LengthFilter(80))
        }
        AlertDialog.Builder(this)
            .setTitle("Nuova sezione / sottocartella")
            .setMessage("Per creare una sottocartella usa il formato Sezione > Sottosezione.")
            .setView(input)
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Continua") { _, _ ->
                val path = normalizeCategoryPath(input.text.toString())
                if (path.isBlank()) { toast("Inserisci un nome valido."); return@setPositiveButton }
                if (categories.none { it.equals(path, true) }) categories.add(path)
                pendingImportCategory = path
                openPicker()
            }.show()
    }


    /**
     * Trasforma group-title e nome canale in un percorso gerarchico coerente.
     * Le regole sono volutamente conservative: ciò che non è riconoscibile
     * finisce in "Da classificare", senza essere assegnato a una categoria casuale.
     */
    /**
     * Classificazione dedicata alle playlist remote: prima identifica il tipo
     * di contenuto (LIVE / VOD-FILM / VOD-SERIE), poi assegna una categoria
     * semantica compatta. Il group-title è un indizio, non viene copiato alla
     * cieca come cartella: spesso i provider generano decine di varianti.
     */
    /**
     * Categorie automatiche DAVIX per le playlist URL.
     *
     * Primo livello: LIVE oppure VOD.
     * Secondo livello VOD: FILM oppure SERIE.
     * Le categorie di genere sono volutamente accorpate: non creiamo cartelle
     * per nazionalità, singoli sottogeneri o etichette commerciali del provider.
     */
    private fun inferUrlCategoryPath(channel: TvChannel): String {
        val group = channel.category.lowercase(Locale.ROOT)
            .replace('_', ' ').replace('-', ' ').replace('|', ' ')
            .replace(Regex("\\s+"), " ").trim()
        val name = channel.name.lowercase(Locale.ROOT)
            .replace('_', ' ').replace('-', ' ').replace('.', ' ')
            .replace(Regex("\\s+"), " ").trim()
        val url = channel.url.lowercase(Locale.ROOT)
        val path = runCatching { Uri.parse(channel.url).path.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")
        // Le sorgenti IPTV spesso hanno nomi commerciali poco descrittivi:
        // group-title, tvg-name, tvg-id e percorso URL sono indizi complementari.
        val tvgName = channel.tvgName.lowercase(Locale.ROOT)
            .replace('_', ' ').replace('-', ' ').replace(Regex("\\s+"), " ").trim()
        val tvgId = channel.tvgId.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), " ").trim()
        val urlHints = (path + " " + url.substringBefore('?').substringAfterLast('/'))
            .replace(Regex("[._-]+"), " ").replace(Regex("\\s+"), " ").trim()
        val source = "$group $name $tvgName $tvgId $urlHints"
        fun hasIn(value: String, vararg terms: String) = terms.any { value.contains(it) }
        fun has(vararg terms: String) = hasIn(source, *terms)
        fun tokenIn(value: String, vararg terms: String) = terms.any {
            Regex("(^|[^a-z0-9])" + Regex.escape(it) + "([^a-z0-9]|$)").containsMatchIn(value)
        }
        fun token(vararg terms: String) = tokenIn(source, *terms)

        // Classificazione a evidenza graduata: gli indizi episodici/serie e i
        // percorsi VOD prevalgono sempre sulle etichette generiche "live".
        val episodePattern = Regex(
            "\\b(?:s[0-9]{1,2}\\s*e[0-9]{1,3}|[0-9]{1,2}x[0-9]{1,3}|season\\s*[0-9]{1,2}\\s*episode\\s*[0-9]{1,3}|stagione\\s*[0-9]{1,2}\\s*episodio\\s*[0-9]{1,3})\\b",
            RegexOption.IGNORE_CASE
        )
        val episodeEvidence = episodePattern.containsMatchIn(name) ||
            episodePattern.containsMatchIn(url) ||
            has("episodio", "episodi", "episode", "episodes", "stagione", "season", "serie tv", "tv series", "tv show", "series")
        val seriesPath = path.contains("/series/") || path.contains("/serie/")
        val moviePath = path.contains("/movie/") || path.contains("/movies/") || path.contains("/film/")
        val vodPath = path.contains("/vod/") || seriesPath || moviePath
        val vodExtension = Regex("\\.(mp4|mkv|avi|mov|m4v|wmv|webm)(?:$|[?&])").containsMatchIn(url)
        val seriesEvidence = seriesPath || episodeEvidence ||
            has("serie tv", "serie complete", "stagioni complete", "tv series", "tv show", "tv shows", "series")
        val movieEvidence = moviePath || has("film", "films", "movie", "movies")
        val strongVod = vodPath || seriesEvidence || movieEvidence ||
            has("video on demand", "on demand") || token("vod") || vodExtension
        val explicitLiveLabel = path.contains("/live/") || has("live", "canali tv", "tv channels", "linear", "broadcast")
        val liveStreamExtension = Regex("\\.(ts|m3u8)(?:$|[?&])").containsMatchIn(url)

        // Anche se il provider mette un VOD sotto una cartella chiamata live,
        // la presenza di prove episodiche o di un percorso VOD deve prevalere.
        val isVod = strongVod || (liveStreamExtension && (seriesEvidence || movieEvidence))
        if (isVod) {
            val isSeries = seriesEvidence && !moviePath
            val genre = if (isSeries) {
                when {
                    has("crime", "poliziesco", "investig", "detective", "gangster", "thriller") -> "Crime e Thriller"
                    has("commedia", "comedy", "sitcom", "comico") -> "Commedia"
                    has("horror", "terror") -> "Horror"
                    has("fantascienza", "sci fi", "sci-fi", "science fiction", "fantasy") -> "Fantascienza e Fantasy"
                    has("azione", "action", "adventure", "avventura") -> "Azione e Avventura"
                    has("animation", "animazione", "anime", "cartoon", "cartoni") -> "Anime e Animazione"
                    has("documentary", "documentario", "documentari", "docu", "reality", "talent") -> "Documentari e Reality"
                    has("drama", "drammatico", "dramatic", "fiction", "romance", "romantico") -> "Drammatiche"
                    else -> "Altre serie"
                }
            } else {
                when {
                    has("crime", "poliziesco", "investig", "detective", "gangster", "thriller", "suspense", "mystery", "mistero") -> "Crime e Thriller"
                    has("commedia", "comedy", "comico") -> "Commedia"
                    has("horror", "terror") -> "Horror"
                    has("fantascienza", "sci fi", "sci-fi", "science fiction", "fantasy") -> "Fantascienza e Fantasy"
                    has("azione", "action", "adventure", "avventura", "martial arts") -> "Azione e Avventura"
                    has("animation", "animazione", "anime", "cartoon", "cartoni", "family", "famiglia", "kids", "bambini") -> "Animazione e Famiglia"
                    has("documentary", "documentario", "documentari", "docu", "music", "musical", "concert") -> "Documentari e Musical"
                    has("drama", "drammatico", "dramatic", "fiction", "romance", "romantico", "war", "guerra", "history", "storico", "western") -> "Drammatici e Storici"
                    else -> "Altri film"
                }
            }
            return if (isSeries) "VOD > SERIE > $genre" else "VOD > FILM > $genre"
        }

        // Un'etichetta live è considerata valida solo in assenza di segnali VOD.
        // L'estensione .m3u8/.ts da sola non basta: alcuni provider la usano per VOD.
        val liveCategory = when {
            has("xxx", "adult", "adulti", "erotic") -> "Adult"
            has("sky cinema", "sky primafila", "cinema collection", "cinema stories", "cinema family", "cinema action", "cinema suspense", "cinema comedy", "cinema romance", "cinema drama", "paramount network", "film channel", "movie channel") -> "Cinema"
            has("sky sport", "dazn", "eurosport", "sportitalia", "sport tv", "espn", "bein sports", "calcio", "football", "soccer", "serie a", "serie b", "champions league", "europa league", "conference league", "premier league", "bundesliga", "ligue 1", "coppa italia", "formula 1", "formula1", "motogp", "moto gp", "superbike", "wrc", "rally", "nascar", "motori", "tennis", "atp", "wta", "basket", "basketball", "nba", "euroleague", "golf", "rugby", "volley", "pallavolo", "ciclismo", "olimpiadi") -> {
                when {
                    has("calcio", "football", "soccer", "serie a", "serie b", "champions league", "europa league", "conference league", "premier league", "bundesliga", "ligue 1", "coppa italia") -> "Sport > Calcio"
                    has("formula 1", "formula1", "motogp", "moto gp", "superbike", "wrc", "rally", "nascar", "motori") -> "Sport > Motori"
                    has("tennis", "atp", "wta") -> "Sport > Tennis"
                    has("basket", "basketball", "nba", "euroleague") -> "Sport > Basket"
                    else -> "Sport > Altri sport"
                }
            }
            has("news", "notizie", "rai news", "sky tg", "tgcom", "bbc", "cnn", "al jazeera", "france 24", "euronews", "sky news") -> "News"
            has("documentar", "discovery", "national geographic", "nat geo", "history channel", "history", "wildlife", "natura", "focus", "sky documentaries", "sky nature") -> "Documentari"
            has("cartoon", "cartoni", "boing", "k2", "nickelodeon", "cartoonito", "disney junior", "kids", "bambini", "baby tv") -> "Bambini"
            has("music", "musica", "mtv", "concert", "radio", "vevo") -> "Musica"
            has("regional", "regionale", "local tv", "telelombardia", "telecity", "telenova") -> "Regionali"
            has("sky uno", "sky atlantic", "sky serie", "sky investigation", "sky crime", "sky arte", "sky reality", "sky family", "sky show", "sky witness", "sky living", "sky comedy", "sky max", "sky showcase", "sky replay", "rai 1", "rai 2", "rai 3", "canale 5", "italia 1", "rete 4", "la7", "nove", "tv8", "real time", "dmax", "general", "entertainment", "intrattenimento", "reality", "masterchef", "x factor") -> "Intrattenimento"
            has("international", "internazional", "world", "estero", "foreign") -> "Internazionali"
            else -> "Altri canali"
        }
        return "LIVE > $liveCategory"
    }
    /**
     * Riconosce le sole cartelle generate automaticamente da DAVIX.
     * Le categorie personalizzate, anche se create sotto LIVE o VOD,
     * non vengono sovrascritte durante una nuova importazione.
     */
    private fun isAutomaticallyClassifiedUrlCategory(raw: String): Boolean {
        val path = normalizeCategoryPath(raw)
        val generated = setOf(
            "LIVE > Adult", "LIVE > Sport > Calcio", "LIVE > Sport > Altri sport",
            "LIVE > News", "LIVE > Documentari", "LIVE > Bambini", "LIVE > Musica",
            "LIVE > Regionali", "LIVE > Intrattenimento", "LIVE > Cinema",
            "LIVE > Internazionali", "LIVE > Altri canali",
            // Percorsi LIVE generati dalle versioni precedenti.
            "LIVE > Sport > Motori", "LIVE > Sport > Tennis", "LIVE > Sport > Basket",
            // Generi VOD della precedente classificazione, da ricondurre al nuovo schema.
            "VOD > FILM > Azione e Avventura", "VOD > FILM > Commedia",
            "VOD > FILM > Crime e Thriller", "VOD > FILM > Horror",
            "VOD > FILM > Fantascienza e Fantasy", "VOD > FILM > Animazione e Famiglia",
            "VOD > FILM > Documentari e Musical", "VOD > FILM > Drammatici e Storici",
            "VOD > FILM > Altri film",
            "VOD > FILM > Romantici", "VOD > FILM > Documentari", "VOD > FILM > Famiglia e Bambini",
            "VOD > FILM > Storici e Guerra", "VOD > FILM > Western", "VOD > FILM > Musicali",
            "VOD > FILM > Reality e Show", "VOD > FILM > Produzioni Italiane", "VOD > FILM > Internazionali",
            "VOD > SERIE > Crime e Thriller", "VOD > SERIE > Commedia",
            "VOD > SERIE > Horror", "VOD > SERIE > Fantascienza e Fantasy",
            "VOD > SERIE > Azione e Avventura", "VOD > SERIE > Anime e Animazione",
            "VOD > SERIE > Documentari e Reality", "VOD > SERIE > Drammatiche",
            "VOD > SERIE > Altre serie",
            "VOD > SERIE > Romantici", "VOD > SERIE > Documentari", "VOD > SERIE > Famiglia e Bambini",
            "VOD > SERIE > Storici e Guerra", "VOD > SERIE > Western", "VOD > SERIE > Musicali",
            "VOD > SERIE > Reality e Show", "VOD > SERIE > Produzioni Italiane", "VOD > SERIE > Internazionali"
        )
        val legacy = listOf(
            "Sport", "Cinema", "Serie TV", "Documentari", "Bambini",
            "News", "Musica", "Intrattenimento", "XXX", "Da classificare", "Non classificati"
        )
        return path in generated || legacy.any { path == it || path.startsWith("$it > ") }
    }

    // Un'unica classificazione semantica per URL, file locali e playlist salvate.
    // Evita che lo stesso canale finisca in cartelle diverse a seconda dell'import.
    private fun inferCategoryPath(channel: TvChannel): String =
        inferUrlCategoryPath(channel)

    private fun importUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (importInProgress) { toast("È già in corso un'importazione."); return }
        importInProgress = true
        val destination = pendingImportCategory
        updateImportProgress("Preparazione importazione di ${uris.size} file…", null)
        Thread({
            try {
                val parsedFiles = mutableListOf<List<TvChannel>>()
                var invalid = 0
                uris.forEachIndexed { index, uri ->
                    try {
                        updateImportProgress("Lettura file ${index + 1}/${uris.size}…", null)
                        val bytes = readImportBytes(uri) { total ->
                            updateImportProgress("Lettura file ${index + 1}/${uris.size} · ${total / (1024 * 1024)} MB", null)
                        }
                        val displayName = queryDisplayName(uri)
                        updateImportProgress("Analisi $displayName · file ${index + 1}/${uris.size}", (index * 100 / uris.size).coerceIn(0, 99))
                        val found = parseChannels(String(bytes, StandardCharsets.UTF_8)) { parsed ->
                            val overall = ((index * 100L + parsed) / uris.size).toInt()
                            updateImportProgress("Analisi $displayName · $parsed%", overall.coerceIn(0, 99))
                        }
                        if (found.isEmpty()) invalid++ else {
                            savePlaylistCopy(displayName, bytes)
                            parsedFiles.add(found)
                        }
                    } catch (error: Exception) {
                        invalid++
                        Log.w("DAVIX-IMPORT", "Importazione file non riuscita: " + error.javaClass.simpleName)
                    }
                }

                runOnUiThread {
                    if (isFinishing || isDestroyed) {
                        importInProgress = false
                        return@runOnUiThread
                    }
                    val knownKeysSnapshot = channels.map {
                        it.name.lowercase(Locale.ROOT) + "|" + it.url
                    }.toHashSet()
                    val knownCategoriesSnapshot = categories.toHashSet()
                    val savedMappingJson = categoryPrefs.getString("channel_categories_json", "{}") ?: "{}"

                    // Parsing, inferenza categorie e deduplicazione restano fuori dal thread UI.
                    Thread({
                        try {
                            data class PreparedFileChannel(val channel: TvChannel, val duplicate: Boolean)
                            val mapping = runCatching { JSONObject(savedMappingJson) }.getOrDefault(JSONObject())
                            val knownKeys = knownKeysSnapshot.toMutableSet()
                            val knownCategories = knownCategoriesSnapshot.toMutableSet()
                            val prepared = ArrayList<PreparedFileChannel>(parsedFiles.sumOf { it.size })

                            parsedFiles.forEach { fileChannels ->
                                fileChannels.forEach { channel ->
                                    val savedCategory = savedCategoryForUrl(mapping, channel.url)
                                    if (savedCategory.isNotBlank()) channel.category = normalizeCategoryPath(savedCategory)
                                    channel.category = if (destination != null) {
                                        destination
                                    } else {
                                        val savedManualPath = normalizeCategoryPath(channel.category)
                                        val inferred = inferCategoryPath(channel)
                                        val preserveManualPath = savedManualPath.isNotBlank() &&
                                            !isAutomaticallyClassifiedUrlCategory(savedManualPath) &&
                                            (savedManualPath in knownCategories || savedManualPath.contains(" > "))
                                        if (preserveManualPath) savedManualPath else inferred
                                    }
                                    knownCategories.add(channel.category)
                                    val key = channel.name.lowercase(Locale.ROOT) + "|" + channel.url
                                    prepared.add(PreparedFileChannel(channel, !knownKeys.add(key)))
                                }
                            }

                            runOnUiThread {
                                if (isFinishing || isDestroyed) {
                                    importInProgress = false
                                    return@runOnUiThread
                                }
                                var added = 0
                                var duplicates = 0
                                var processed = 0
                                val totalChannels = prepared.size
                                val importHandler = Handler(Looper.getMainLooper())
                                val importBatch = object : Runnable {
                                    override fun run() {
                                        if (isFinishing || isDestroyed) {
                                            importInProgress = false
                                            return
                                        }
                                        val batchStartedAt = android.os.SystemClock.uptimeMillis()
                                        var inBatch = 0
                                        while (processed < prepared.size && inBatch < 120 &&
                                            android.os.SystemClock.uptimeMillis() - batchStartedAt < 12L
                                        ) {
                                            val item = prepared[processed]
                                            if (item.duplicate) {
                                                duplicates++
                                            } else {
                                                item.channel.selected = false
                                                channels.add(item.channel)
                                                if (item.channel.category !in categories) categories.add(item.channel.category)
                                                added++
                                            }
                                            processed++
                                            inBatch++
                                        }
                                        if (processed < prepared.size) {
                                            updateImportProgress(
                                                "Inserimento canali · $processed / $totalChannels",
                                                (processed * 99 / totalChannels.coerceAtLeast(1)).coerceIn(1, 99)
                                            )
                                            importHandler.postDelayed(this, 8L)
                                        } else {
                                            if (destination != null && destination !in categories) categories.add(destination)
                                            pendingImportCategory = null
                                            importInProgress = false
                                            finishImportProgress()
                                            renderChannels()
                                            saveCategorySettings()
                                            persistChannelLibrary()
                                            renderSavedPlaylists()
                                            status.text = "File analizzati: ${uris.size} · Nuovi canali: $added · Duplicati ignorati: $duplicates · File senza canali validi: $invalid"
                                        }
                                    }
                                }
                                importHandler.post(importBatch)
                            }
                        } catch (error: Exception) {
                            runOnUiThread {
                                importInProgress = false
                                finishImportProgress()
                                if (!isFinishing && !isDestroyed) {
                                    status.text = "Analisi file non riuscita: ${error.message}"
                                    toast(status.text.toString())
                                }
                            }
                        }
                    }, "DAVIX-File-Classification").apply { isDaemon = true }.start()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    importInProgress = false
                    finishImportProgress()
                    if (!isFinishing && !isDestroyed) {
                        status.text = "Importazione file non riuscita: ${error.message}"
                        toast(status.text.toString())
                    }
                }
            }
        }, "DAVIX-File-Import").apply { isDaemon = true }.start()
    }

    private fun queryDisplayName(uri: Uri): String {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "playlist"
        } catch (_: Exception) {
            uri.lastPathSegment?.substringAfterLast('/') ?: "playlist"
        }
    }

    private fun savedPlaylistDirectory(): java.io.File =
        java.io.File(filesDir, "saved_playlists").apply { mkdirs() }

    private fun savePlaylistCopy(originalName: String, bytes: ByteArray) {
        val safeName = originalName.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
            .ifBlank { "playlist.txt" }.take(80)
        val target = java.io.File(savedPlaylistDirectory(), "${System.currentTimeMillis()}_$safeName")
        target.writeBytes(bytes)
    }

    private fun renderSavedPlaylists() {
        if (!::savedPlaylistsList.isInitialized) return
        savedPlaylistsList.removeAllViews()
        val files = savedPlaylistDirectory().listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }.orEmpty()
        if (files.isEmpty()) {
            val empty = TextView(this).apply {
                text = "Nessuna playlist salvata."
                textSize = 12f
                setTextColor(MUTED)
                setPadding(0, dp(4), 0, dp(8))
            }
            savedPlaylistsList.addView(empty)
            return
        }
        files.forEach { file ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setBackgroundColor(PANEL)
            }
            val name = TextView(this).apply {
                text = file.name.substringAfter('_')
                textSize = 12f
                setTextColor(Color.WHITE)
                maxLines = 2
            }
            row.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(buttonView("▶  APRI PLAYLIST") { loadSavedPlaylist(file) })
            savedPlaylistsList.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(5)
            })
        }
    }

    private fun loadSavedPlaylist(file: java.io.File, deleteAfterImport: Boolean = false) {
        if (importInProgress) { toast("È già in corso un'importazione."); return }
        if (!file.exists()) {
            toast("La playlist salvata non è più disponibile.")
            renderSavedPlaylists()
            return
        }
        importInProgress = true
        updateImportProgress("Caricamento playlist salvata · lettura file…", null)
        Thread({
            try {
                val found = try {
                    parseChannels(file.readText(StandardCharsets.UTF_8)) { parsed ->
                        updateImportProgress("Analisi playlist salvata · $parsed%", parsed)
                    }
                } finally {
                    if (deleteAfterImport) file.delete()
                }
                if (found.isEmpty()) {
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            status.text = "Nessun canale valido trovato nel file salvato."
                            toast(status.text.toString())
                        }
                        importInProgress = false
                        finishImportProgress()
                    }
                    return@Thread
                }

                runOnUiThread {
                    if (isFinishing || isDestroyed) {
                        importInProgress = false
                        return@runOnUiThread
                    }
                    val savedMappingJson = categoryPrefs.getString("channel_categories_json", "{}") ?: "{}"
                    val knownKeysSnapshot = channels.map {
                        it.name.lowercase(Locale.ROOT) + "|" + it.url
                    }.toHashSet()

                    // Anche la normalizzazione delle categorie avviene fuori dal thread UI.
                    Thread({
                        try {
                            val mapping = runCatching { JSONObject(savedMappingJson) }.getOrDefault(JSONObject())
                            found.forEach { channel ->
                                val savedCategory = normalizeCategoryPath(savedCategoryForUrl(mapping, channel.url))
                                channel.category = when {
                                    savedCategory.isBlank() -> inferUrlCategoryPath(channel)
                                    isAutomaticallyClassifiedUrlCategory(savedCategory) -> inferUrlCategoryPath(channel)
                                    else -> savedCategory // Le scelte manuali dell'utente hanno sempre la precedenza.
                                }
                            }
                            runOnUiThread {
                                if (isFinishing || isDestroyed) {
                                    importInProgress = false
                                    return@runOnUiThread
                                }
                                var added = 0
                                var duplicates = 0
                                var processed = 0
                                val importHandler = Handler(Looper.getMainLooper())
                                val importBatch = object : Runnable {
                                    override fun run() {
                                        if (isFinishing || isDestroyed) {
                                            importInProgress = false
                                            return
                                        }
                                        val batchStartedAt = android.os.SystemClock.uptimeMillis()
                                        var inBatch = 0
                                        while (processed < found.size && inBatch < 120 &&
                                            android.os.SystemClock.uptimeMillis() - batchStartedAt < 12L
                                        ) {
                                            val channel = found[processed]
                                            val key = channel.name.lowercase(Locale.ROOT) + "|" + channel.url
                                            if (!knownKeysSnapshot.add(key)) {
                                                duplicates++
                                            } else {
                                                channel.selected = false
                                                channels.add(channel)
                                                if (channel.category !in categories) categories.add(channel.category)
                                                added++
                                            }
                                            processed++
                                            inBatch++
                                        }
                                        if (processed < found.size) {
                                            updateImportProgress(
                                                "Inserimento canali · $processed / ${found.size}",
                                                90 + processed * 10 / found.size.coerceAtLeast(1)
                                            )
                                            importHandler.postDelayed(this, 8L)
                                        } else {
                                            status.text = "Playlist caricata: ${file.name.substringAfter('_')} · Nuovi canali: $added · Duplicati ignorati: $duplicates"
                                            renderChannels()
                                            persistChannelLibrary()
                                            saveCategorySettings()
                                            importInProgress = false
                                            finishImportProgress()
                                        }
                                    }
                                }
                                importHandler.post(importBatch)
                            }
                        } catch (error: Exception) {
                            runOnUiThread {
                                importInProgress = false
                                finishImportProgress()
                                if (!isFinishing && !isDestroyed) {
                                    status.text = "Impossibile classificare la playlist: ${error.message}"
                                    toast(status.text.toString())
                                }
                            }
                        }
                    }, "DAVIX-Saved-Playlist").apply { isDaemon = true }.start()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    importInProgress = false
                    finishImportProgress()
                    if (!isFinishing && !isDestroyed) {
                        status.text = "Impossibile caricare la playlist: ${error.message}"
                        toast(status.text.toString())
                    }
                }
            }
        }, "DAVIX-Saved-Playlist-Read").apply { isDaemon = true }.start()
    }

    private fun confirmClearSavedPlaylists() {
        AlertDialog.Builder(this)
            .setTitle("Svuotare l'archivio?")
            .setMessage("Verranno eliminate tutte le playlist salvate sul dispositivo e rimossi i canali attualmente caricati. Questa operazione non può essere annullata.")
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Svuota") { _, _ ->
                savedPlaylistDirectory().listFiles()?.forEach { it.delete() }
                channels.clear()
                categories.clear()
                categories.addAll(listOf("Intrattenimento", "Sport", "Cinema", "Documentari", "Musica", "XXX", "Non classificati"))
                if (::player.isInitialized) {
                    player.stop()
                    player.clearMediaItems()
                }
                currentPlayingChannelUrl = null
                if (::currentChannelLabel.isInitialized) currentChannelLabel.text = "NESSUN CANALE IN RIPRODUZIONE"
                renderChannels()
                renderSavedPlaylists()
                status.text = "Archivio svuotato. Puoi caricare una nuova playlist."
                toast("Archivio e lista canali svuotati.")
            }
            .show()
    }

    // Evita di caricare file arbitrariamente grandi in memoria durante l'importazione.
    private fun readImportBytes(uri: Uri, onProgress: ((Long) -> Unit)? = null): ByteArray {
        val maxBytes = 64L * 1024L * 1024L
        val input = contentResolver.openInputStream(uri)
            ?: throw IOException("Impossibile aprire il file selezionato.")
        input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0L
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                total += count
                if (total > maxBytes) {
                    throw IOException("Il file supera il limite di importazione di 64 MB.")
                }
                output.write(buffer, 0, count)
                if (total % (512 * 1024) < count) onProgress?.invoke(total)
            }
            return output.toByteArray()
        }
    }

    private fun parseChannels(text: String, onProgress: ((Int) -> Unit)? = null): List<TvChannel> {
        val found = mutableListOf<TvChannel>()
        val seen = mutableSetOf<String>()
        fun add(nameRaw: String?, urlRaw: String?, logoRaw: String? = "", categoryRaw: String? = null, tvgIdRaw: String? = null, tvgNameRaw: String? = null) {
            val name = nameRaw?.trim()?.takeIf { it.isNotBlank() } ?: return
            val url = urlRaw?.trim()?.replace("&amp;", "&")?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) } ?: return
            val key = name.lowercase(Locale.ROOT) + "|" + url
            if (seen.add(key)) found.add(TvChannel(
                name, url, logoRaw?.trim().orEmpty(),
                category = categoryRaw?.trim()?.takeIf { it.isNotBlank() } ?: "Non classificati",
                tvgId = tvgIdRaw?.trim().orEmpty(), tvgName = tvgNameRaw?.trim().orEmpty()
            ))
        }

        // Scansione lineare: cercare la URL con lines.drop(i + 1) per ogni
        // #EXTINF rendeva il parser quadratico sulle playlist IPTV molto grandi.
        val totalLines = (text.count { it == '\n' } + 1).coerceAtLeast(1)
        var processedLines = 0
        val lines = text.lineSequence()
        var pendingName: String? = null
        var pendingLogo = ""
        var pendingGroup: String? = null
        var pendingTvgId = ""
        var pendingTvgName = ""
        for (rawLine in lines) {
            processedLines++
            if (processedLines % 250 == 0) onProgress?.invoke((processedLines * 100 / totalLines).coerceIn(0, 99))
            val line = rawLine.trim()
            if (line.startsWith("#EXTINF", true)) {
                pendingName = line.substringAfterLast(',').trim()
                pendingLogo = Regex("tvg-logo=[\"']([^\"']+)").find(line)?.groupValues?.getOrNull(1).orEmpty()
                pendingGroup = Regex("group-title=[\"']([^\"']+)").find(line)?.groupValues?.getOrNull(1)
                pendingTvgId = Regex("tvg-id=[\"']([^\"']*)").find(line)?.groupValues?.getOrNull(1).orEmpty()
                pendingTvgName = Regex("tvg-name=[\"']([^\"']*)").find(line)?.groupValues?.getOrNull(1).orEmpty()
            } else if (pendingName != null && line.isNotEmpty() && !line.startsWith("#")) {
                add(pendingName, line, pendingLogo, pendingGroup, pendingTvgId, pendingTvgName)
                pendingName = null
                pendingLogo = ""
                pendingGroup = null
                pendingTvgId = ""
                pendingTvgName = ""
            }
        }

        fun field(obj: String, keys: List<String>): String? {
            for (key in keys) {
                val pattern = Regex("[\"']" + Regex.escape(key) + "[\"']\\s*:\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
                pattern.find(obj)?.groupValues?.getOrNull(1)?.let { return it }
            }
            return null
        }
        // Parser HTML essenziale per link diretti a flussi: mantiene il testo del link come nome.
        val htmlLinks = Regex("(?is)<a\\b[^>]*href\\s*=\\s*[\"'](https?://[^\"']+)[\"'][^>]*>(.*?)</a>")
        htmlLinks.findAll(text).forEach { match ->
            val label = android.text.Html.fromHtml(
                match.groupValues[2].replace(Regex("<[^>]+>"), " "),
                android.text.Html.FROM_HTML_MODE_LEGACY
            ).toString().trim()
            add(label, match.groupValues[1])
        }

        val objects = Regex("\\{[^{}]{0,4000}\\}").findAll(text)
        objects.forEach { m ->
            val obj = m.value
            add(
                field(obj, listOf("name", "title", "channel_name", "tvg-name")),
                field(obj, listOf("url", "stream", "src", "link")),
                field(obj, listOf("logo", "tvg-logo", "image", "icon")),
                field(obj, listOf("category", "group", "group-title")),
                field(obj, listOf("tvg-id", "tvgId", "epg_id")),
                field(obj, listOf("tvg-name", "tvgName"))
            )
        }

        try {
            val trimmed = text.trim()
            val rootObject = if (trimmed.startsWith("{")) JSONObject(trimmed) else null
            fun readArray(array: JSONArray, inheritedCategory: String = "") {
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    add(
                        o.optString("name", o.optString("title", o.optString("channel_name"))),
                        o.optString("url", o.optString("stream", o.optString("stream_url", o.optString("src", o.optString("link"))))),
                        o.optString("logo", o.optString("thumbnail", o.optString("tvg-logo"))),
                        o.optString("category", o.optString("group", o.optString("group-title", inheritedCategory))),
                        o.optString("tvg-id", o.optString("tvgId", o.optString("epg_id"))),
                        o.optString("tvg-name", o.optString("tvgName"))
                    )
                }
            }
            if (trimmed.startsWith("[")) {
                readArray(JSONArray(trimmed))
            } else if (rootObject != null) {
                rootObject.optJSONArray("channels")?.let { readArray(it) }
                rootObject.optJSONArray("stations")?.let { readArray(it) }
                rootObject.optJSONArray("items")?.let { readArray(it) }
                rootObject.optJSONArray("data")?.let { readArray(it) }
                rootObject.optJSONArray("groups")?.let { groups ->
                    for (i in 0 until groups.length()) {
                        val group = groups.optJSONObject(i) ?: continue
                        val category = group.optString("name", group.optString("title"))
                        group.optJSONArray("stations")?.let { readArray(it, category) }
                        group.optJSONArray("channels")?.let { readArray(it, category) }
                    }
                }
            }
        } catch (_: Exception) { }
        onProgress?.invoke(100)
        return found
    }

    private fun renderChannels() {
        // Il rendering non deve serializzare migliaia di URL su disco ad ogni
        // click/espansione: il salvataggio avviene nei punti di modifica e onStop.
        selectedList.removeAllViews()
        otherList.removeAllViews()
        playingChannelButton = null
        val selected = channels.withIndex().filter { it.value.selected }
        val others = channels.withIndex().filter { !it.value.selected }
        // Costruisce una sola mappa degli indici per cartella e antenati.
        // Evita la precedente scansione completa di channels per ogni singola cartella.
        val bulkIndicesByPath = mutableMapOf<String, MutableList<Int>>()
        val selectedCountsByPath = mutableMapOf<String, Int>()
        // Indici completi per cartella: la selezione di pubblicazione deve includere
        // tutti i discendenti, anche quelli mostrati nell'altra sezione (selezionati/altri).
        channels.forEachIndexed { index, channel ->
            val parts = normalizeCategoryPath(channel.category).split(" > ").filter { it.isNotBlank() }
            var path = ""
            parts.forEach { part ->
                path = if (path.isBlank()) part else "$path > $part"
                bulkIndicesByPath.getOrPut(path) { mutableListOf() }.add(index)
                if (channel.selected) selectedCountsByPath[path] = (selectedCountsByPath[path] ?: 0) + 1
            }
        }
        selectedHeading.visibility = if (selected.isEmpty()) View.GONE else View.VISIBLE
        otherHeading.visibility = if (others.isEmpty()) View.GONE else View.VISIBLE

        fun addChannelRow(index: Int, channel: TvChannel, target: LinearLayout, variantIndices: List<Int> = listOf(index), displayName: String = channel.name) {
            val isPlayingChannel = currentPlayingChannelUrl == channel.url
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(5), dp(8), dp(5))
                setBackgroundColor(if (isPlayingChannel) 0xFF173A4D.toInt() else PANEL)
            }
            val check = CheckBox(this).apply {
                isChecked = channel.selected
                buttonTintList = android.content.res.ColorStateList.valueOf(CYAN)
                setOnCheckedChangeListener { _, checked ->
                    channel.selected = checked
                    persistChannelLibrary()
                    renderChannels()
                }
            }
            row.addView(check)
            if (bulkSelectionMode) {
                val bulkCheck = CheckBox(this).apply {
                    isChecked = index in bulkSelectedIndices
                    contentDescription = "Seleziona canale per lo spostamento"
                    buttonTintList = android.content.res.ColorStateList.valueOf(0xFFFFC857.toInt())
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) bulkSelectedIndices.add(index) else bulkSelectedIndices.remove(index)
                        updateCount()
                    }
                }
                row.addView(bulkCheck)
            }
            val variantCount = variantIndices.size
            val label = if (variantCount > 1) "$displayName · $variantCount versioni" else displayName
            val isGroupPlaying = variantIndices.any { channels.getOrNull(it)?.url == currentPlayingChannelUrl }
            val play = Button(this).apply {
                tag = displayName
                text = label
                isAllCaps = false
                textSize = 13f
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                minWidth = 0
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                setPadding(dp(5), 0, dp(5), 0)
                setTextColor(if (isGroupPlaying || isPlayingChannel) CYAN else Color.WHITE)
                setBackgroundColor(Color.TRANSPARENT)
                contentDescription = if (variantCount > 1) "Scegli versione di $displayName" else if (isPlayingChannel) "$displayName, in riproduzione" else "Riproduci $displayName"
                if (isGroupPlaying || isPlayingChannel) playingChannelButton = this
                setOnClickListener {
                    if (variantCount <= 1) playChannel(index, this)
                    else ChannelVariantUi.show(this@MainActivity, displayName, channels, variantIndices) { playChannel(it, this) }
                }
            }
            row.addView(play, LinearLayout.LayoutParams(0, dp(48), 1f))
            val editButton = buttonView("✎") { showCategoryPicker(channel) }.apply {
                textSize = 19f
                minWidth = dp(42)
                contentDescription = "Modifica categoria o sposta ${channel.name}"
                setPadding(dp(8), 0, dp(8), 0)
            }
            row.addView(editButton, LinearLayout.LayoutParams(dp(44), dp(40)))
            target.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(5)
            })
        }
        fun addFolderGroups(items: List<IndexedValue<TvChannel>>, target: LinearLayout, prefix: String) {
            // Il numero di View create in un singolo frame resta limitato anche con archivi enormi.
            val folderLimit = folderRenderLimits[prefix] ?: FOLDERS_PER_RENDER_PAGE
            val channelLimit = channelRenderLimits[prefix] ?: CHANNELS_PER_RENDER_PAGE
            var renderedFolders = 0
            var renderedChannelRows = 0
            var hasMoreFolders = false
            var hasMoreChannelRows = false
            data class FolderNode(
                val name: String,
                val path: String,
                val entries: MutableList<IndexedValue<TvChannel>> = mutableListOf(),
                val children: MutableMap<String, FolderNode> = linkedMapOf(),
                var totalCount: Int = 0
            )
            val rootNode = FolderNode("", "")
            items.forEach { entry ->
                val parts = normalizeCategoryPath(entry.value.category)
                    .ifBlank { "Da classificare" }.split(" > ").filter { it.isNotBlank() }
                var node = rootNode
                parts.forEach { part ->
                    val path = if (node.path.isBlank()) part else "${node.path} > $part"
                    node = node.children.getOrPut(part) { FolderNode(part, path) }
                    node.totalCount++
                }
                node.entries.add(entry)
            }

            fun renderFolderNode(node: FolderNode, depth: Int) {
                if (renderedFolders >= folderLimit) {
                    hasMoreFolders = true
                    return
                }
                renderedFolders++
                val key = "$prefix:${node.path}"
                val expanded = key in expandedCategoryPaths
                val total = node.totalCount
                // Header a due righe: su smartphone il nome non deve competere
                // con i comandi di selezione, altrimenti viene compresso lettera per lettera.
                val header = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(10 + depth * 10), dp(8), dp(10), dp(8))
                    setBackgroundColor(if (depth == 0) 0xFF1B2A40.toInt() else 0xFF162235.toInt())
                    isClickable = true
                    isFocusable = true
                    contentDescription = "${if (expanded) "Chiudi" else "Apri"} cartella ${node.path}, $total canali"
                    setOnClickListener {
                        if (expanded) expandedCategoryPaths.remove(key) else expandedCategoryPaths.add(key)
                        renderChannels()
                    }
                }
                val titleRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                titleRow.addView(TextView(this).apply {
                    text = if (expanded) "📂" else "📁"
                    textSize = 18f
                    gravity = Gravity.CENTER
                }, LinearLayout.LayoutParams(dp(28), dp(36)))
                titleRow.addView(TextView(this).apply {
                    text = node.name
                    textSize = if (depth == 0) 15f else 14f
                    setTextColor(Color.WHITE)
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dp(5), 0, dp(5), 0)
                }, LinearLayout.LayoutParams(0, dp(36), 1f))
                titleRow.addView(TextView(this).apply {
                    text = total.toString()
                    textSize = 12f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(CYAN)
                    gravity = Gravity.CENTER
                    setPadding(dp(7), 0, dp(5), 0)
                })
                titleRow.addView(TextView(this).apply {
                    // Chevron down = apri; chevron up = chiudi.
                    text = if (expanded) "⌃" else "⌄"
                    textSize = 22f
                    setTextColor(CYAN)
                    gravity = Gravity.CENTER
                    contentDescription = if (expanded) "Chiudi cartella" else "Apri cartella"
                }, LinearLayout.LayoutParams(dp(30), dp(36)))
                header.addView(titleRow, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(36)
                ))
                val actionRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.END or Gravity.CENTER_VERTICAL
                    setPadding(0, dp(5), 0, 0)
                }
                val folderIndices = bulkIndicesByPath[node.path].orEmpty()
                val selectedInFolder = selectedCountsByPath[node.path] ?: 0
                val allFolderSelected = folderIndices.isNotEmpty() && selectedInFolder == folderIndices.size
                val folderSelectionLabel = when {
                    allFolderSelected -> "☑  Deseleziona"
                    selectedInFolder > 0 -> "◩  Completa selezione"
                    else -> "□  Seleziona"
                }
                val selectionButton = buttonView(folderSelectionLabel) {
                    val selectFolder = !allFolderSelected
                    folderIndices.forEach { index -> channels.getOrNull(index)?.selected = selectFolder }
                    persistChannelLibrary()
                    renderChannels()
                }
                selectionButton.textSize = 12f
                actionRow.addView(selectionButton, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)
                ))
                if (bulkSelectionMode) {
                    val allBulkSelected = folderIndices.isNotEmpty() &&
                        folderIndices.all { it in bulkSelectedIndices }
                    val bulkButton = buttonView(
                        if (allBulkSelected) "☑  Deseleziona spostamento" else "□  Seleziona per spostare"
                    ) {
                        if (allBulkSelected) bulkSelectedIndices.removeAll(folderIndices.toSet())
                        else bulkSelectedIndices.addAll(folderIndices)
                        renderChannels()
                    }
                    bulkButton.textSize = 11f
                    actionRow.addView(bulkButton, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)
                    ).apply { leftMargin = dp(6) })
                }
                header.addView(actionRow, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ))
                target.addView(header, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(4)
                    topMargin = dp(if (depth == 0) 5 else 2)
                })

                if (expanded) {
                    val visibleLimit = (folderPageSizes[key] ?: CHANNELS_PER_FOLDER_PAGE)
                        .coerceAtLeast(CHANNELS_PER_FOLDER_PAGE)
                    val remainingChannelSlots = (channelLimit - renderedChannelRows).coerceAtLeast(0)
                    val rowsToRender = minOf(node.entries.size, visibleLimit, remainingChannelSlots)
                    val grouped = ChannelGrouping.groupEntries(node.entries)
                    val groupsToRender = grouped.take(rowsToRender)
                    groupsToRender.forEach { group ->
                        renderedChannelRows++
                        val primaryIndex = group.indices.first()
                        val primary = channels[primaryIndex]
                        val holder = LinearLayout(this).apply {
                            setPadding(dp(10 + (depth + 1) * 14), 0, 0, 0)
                        }
                        addChannelRow(primaryIndex, primary, holder, group.indices, group.displayName)
                        target.addView(holder, LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                        ))
                    }
                    if (node.entries.size > rowsToRender && renderedChannelRows >= channelLimit) {
                        hasMoreChannelRows = true
                    }
                    if (node.entries.size > visibleLimit) {
                        val remaining = node.entries.size - visibleLimit
                        target.addView(buttonView(
                            "Mostra altri ${minOf(CHANNELS_PER_FOLDER_PAGE, remaining)} · $remaining rimanenti"
                        ) {
                            folderPageSizes[key] = visibleLimit + CHANNELS_PER_FOLDER_PAGE
                            renderChannels()
                        }, LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, dp(42)
                        ).apply { bottomMargin = dp(6) })
                    }
                    for (child in node.children.values.sortedBy { it.name.lowercase(Locale.ROOT) }) {
                        if (renderedFolders >= folderLimit) {
                            hasMoreFolders = true
                            break
                        }
                        renderFolderNode(child, depth + 1)
                    }
                }
            }

            for (node in rootNode.children.values.sortedBy { it.name.lowercase(Locale.ROOT) }) {
                if (renderedFolders >= folderLimit) {
                    hasMoreFolders = true
                    break
                }
                renderFolderNode(node, 0)
            }
            if (hasMoreFolders) {
                target.addView(buttonView("Mostra altre $FOLDERS_PER_RENDER_PAGE cartelle") {
                    folderRenderLimits[prefix] = folderLimit + FOLDERS_PER_RENDER_PAGE
                    renderChannels()
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { bottomMargin = dp(6) })
            }
            if (hasMoreChannelRows) {
                target.addView(buttonView("Mostra altri $CHANNELS_PER_RENDER_PAGE canali") {
                    channelRenderLimits[prefix] = channelLimit + CHANNELS_PER_RENDER_PAGE
                    renderChannels()
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { bottomMargin = dp(6) })
            }
        }
        addFolderGroups(selected, selectedList, "selected")
        addFolderGroups(others, otherList, "other")
        updateCount()
    }

    private fun moveBulkSelectedChannels() {
        if (bulkSelectedIndices.isEmpty()) {
            toast("Seleziona prima i canali da spostare.")
            return
        }
        val options = categories.filter { it != "Non classificati" } + "＋  Crea nuova sezione / sottocartella…"
        AlertDialog.Builder(this)
            .setTitle("Sposta ${bulkSelectedIndices.size} canali in")
            .setItems(options.toTypedArray()) { _, which ->
                if (which == options.lastIndex) {
                    val input = EditText(this).apply {
                        hint = "Intrattenimento > Cinema"
                        setSingleLine(true)
                        filters = arrayOf(android.text.InputFilter.LengthFilter(80))
                    }
                    AlertDialog.Builder(this).setTitle("Destinazione personalizzata").setView(input)
                        .setNegativeButton("Annulla", null)
                        .setPositiveButton("Sposta") { _, _ ->
                            val path = normalizeCategoryPath(input.text.toString())
                            if (path.isBlank()) { toast("Percorso non valido."); return@setPositiveButton }
                            applyBulkCategory(path)
                        }.show()
                } else applyBulkCategory(options[which])
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun applyBulkCategory(path: String) {
        if (path !in categories) categories.add(path)
        bulkSelectedIndices.toList().forEach { index -> channels.getOrNull(index)?.category = path }
        val moved = bulkSelectedIndices.size
        bulkSelectedIndices.clear()
        bulkSelectionMode = false
        renderChannels()
        toast("$moved canali spostati in $path")
    }

    private fun normalizeCategoryPath(raw: String): String =
        raw.split(">").map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.isNotBlank() }.joinToString(" > ").take(80)

    private fun showCategoryManager() {
        val options = listOf("＋  Nuova sezione / sottocartella") + categories.filter { it != "Non classificati" }
        AlertDialog.Builder(this).setTitle("Gestisci categorie").setItems(options.toTypedArray()) { _, index ->
            if (index == 0) promptNewCategory() else showCategoryActions(options[index])
        }.setNegativeButton("Chiudi", null).show()
    }

    private fun promptNewCategory(assignTo: TvChannel? = null) {
        val input = EditText(this).apply { hint = "Intrattenimento > Cinema"; setSingleLine(true); filters = arrayOf(android.text.InputFilter.LengthFilter(80)) }
        AlertDialog.Builder(this).setTitle("Nuova categoria").setView(input).setNegativeButton("Annulla", null)
            .setPositiveButton("Crea") { _, _ ->
                val name = normalizeCategoryPath(input.text.toString())
                if (name.isBlank() || categories.any { it.equals(name, true) }) { toast("Nome vuoto o categoria già esistente."); return@setPositiveButton }
                categories.add(name); assignTo?.category = name; renderChannels(); toast("Categoria creata: $name")
            }.show()
    }

    private fun showCategoryPicker(channel: TvChannel) {
        val options = categories.toList() + "＋  Crea nuova categoria…"
        AlertDialog.Builder(this).setTitle("Categoria · ${channel.name}").setItems(options.toTypedArray()) { _, index ->
            if (index == options.lastIndex) promptNewCategory(channel) else { channel.category = options[index]; renderChannels() }
        }.setNegativeButton("Annulla", null).show()
    }

    private fun showCategoryActions(category: String) {
        AlertDialog.Builder(this).setTitle(category).setItems(arrayOf("Rinomina", "Elimina")) { _, action ->
            val prefix = "$category > "
            if (action == 0) {
                val input = EditText(this).apply {
                    setText(category)
                    setSingleLine(true)
                    filters = arrayOf(android.text.InputFilter.LengthFilter(80))
                }
                AlertDialog.Builder(this).setTitle("Rinomina sezione / sottocartella").setView(input)
                    .setNegativeButton("Annulla", null)
                    .setPositiveButton("Salva") { _, _ ->
                        val renamed = normalizeCategoryPath(input.text.toString())
                        val conflict = categories.any { it != category && it.equals(renamed, true) }
                        if (renamed.isBlank() || conflict) {
                            toast("Nome vuoto o già utilizzato.")
                        } else {
                            categories.indices.forEach { i ->
                                val current = categories[i]
                                if (current == category) categories[i] = renamed
                                else if (current.startsWith(prefix, true)) categories[i] = renamed + current.substring(category.length)
                            }
                            channels.forEach { channel ->
                                if (channel.category == category) channel.category = renamed
                                else if (channel.category.startsWith(prefix, true)) channel.category = renamed + channel.category.substring(category.length)
                            }
                            renderChannels()
                        }
                    }.show()
            } else {
                AlertDialog.Builder(this).setTitle("Eliminare $category?")
                    .setMessage("I canali della sezione e delle relative sottocartelle verranno spostati in Non classificati.")
                    .setNegativeButton("Annulla", null)
                    .setPositiveButton("Elimina") { _, _ ->
                        categories.removeAll { it == category || it.startsWith(prefix, true) }
                        channels.filter { it.category == category || it.category.startsWith(prefix, true) }
                            .forEach { it.category = "Non classificati" }
                        renderChannels()
                    }.show()
            }
        }.setNegativeButton("Indietro", null).show()
    }

    private fun channelLibraryFile(): java.io.File =
        java.io.File(filesDir, "channel_library_v1.json")

    private fun channelLibraryAtomicFile(): AtomicFile =
        AtomicFile(channelLibraryFile())

    /**
     * Cattura uno snapshot immutabile sul main thread e delega serializzazione e I/O
     * al writer dedicato. La coda FIFO mantiene l'ordine degli snapshot.
     */
    private fun persistChannelLibrary(immediate: Boolean = false) {
        pendingArchiveWrite?.let { archiveDebounceHandler.removeCallbacks(it) }
        val writeSnapshot = Runnable {
            pendingArchiveWrite = null
            val channelSnapshot = channels.map { channel ->
                ChannelArchiveEntry(
                    channel.name, channel.url, channel.logo, channel.selected,
                    channel.category, channel.tvgId, channel.tvgName
                )
            }
            val expandedSnapshot = expandedCategoryPaths.toList()
            val savedAt = System.currentTimeMillis()
    
            runCatching {
                archiveExecutor.execute {
                    runCatching {
                        val savedChannels = JSONArray()
                        channelSnapshot.forEach { channel ->
                            savedChannels.put(JSONObject().apply {
                                put("name", channel.name)
                                put("url", channel.url)
                                put("logo", channel.logo)
                                put("selected", channel.selected)
                                put("category", channel.category)
                                put("tvgId", channel.tvgId)
                                put("tvgName", channel.tvgName)
                            })
                        }
                        val expanded = JSONArray().apply { expandedSnapshot.forEach { put(it) } }
                        val state = JSONObject().apply {
                            // Versione 2: conserva in modo esplicito la selezione di pubblicazione.
                            put("version", 3)
                            put("channels", savedChannels)
                            put("expandedCategories", expanded)
                            put("savedAt", savedAt)
                        }
                        val atomicFile = channelLibraryAtomicFile()
                        val output = atomicFile.startWrite()
                        try {
                            output.write(state.toString().toByteArray(StandardCharsets.UTF_8))
                            atomicFile.finishWrite(output)
                        } catch (error: Throwable) {
                            atomicFile.failWrite(output)
                            throw error
                        }
                    }.onFailure {
                        Log.w("DAVIX-ARCHIVE", "Salvataggio archivio canali non riuscito; la copia precedente viene conservata", it)
                    }
                }
            }.onFailure {
                Log.w("DAVIX-ARCHIVE", "Impossibile accodare il salvataggio dell’archivio canali", it)
            }
        }
        if (immediate) {
            writeSnapshot.run()
        } else {
            pendingArchiveWrite = writeSnapshot
            archiveDebounceHandler.postDelayed(writeSnapshot, 350L)
        }
    }

    /**
     * Ripristina l'archivio fuori dal thread UI: le playlist possono contenere
     * migliaia di canali e il parsing JSON non deve ritardare la prima schermata.
     * Le strutture condivise vengono aggiornate soltanto sul main thread.
     */
    private fun restoreChannelLibraryAsync() {
        val file = channelLibraryFile()
        val interruptedWriteBackup = java.io.File(file.path + ".bak")
        if (!file.isFile && !interruptedWriteBackup.isFile) {
            restoreMostRecentSavedPlaylist()
            return
        }
        channelLibraryRestorePending = true

        val restoreExecutor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "DAVIX-Restore").apply { isDaemon = true }
        }
        restoreExecutor.execute {
            var legacySelectionReset = false
            var archiveNeedsEncryption = false
            val restored = runCatching {
                val state = channelLibraryAtomicFile().openRead().bufferedReader(StandardCharsets.UTF_8).use {
                    JSONObject(it.readText())
                }
                legacySelectionReset = state.optInt("version", 1) < 2
                val savedChannels = state.optJSONArray("channels") ?: JSONArray()
                val restoredChannels = ArrayList<TvChannel>(savedChannels.length())
                val restoredCategories = LinkedHashSet<String>()
                for (i in 0 until savedChannels.length()) {
                    val item = savedChannels.optJSONObject(i) ?: continue
                    val name = item.optString("name").trim()
                    val storedUrl = item.optString("url").trim()
                    if (!storedUrl.startsWith("enc:v1:")) archiveNeedsEncryption = true
                    val url = IptvSourceSettings.unprotectLocalValue(storedUrl)?.trim().orEmpty()
                    if (name.isBlank() || !(url.startsWith("https://", true) || url.startsWith("http://", true))) continue
                    val category = normalizeCategoryPath(item.optString("category", "Non classificati"))
                        .ifBlank { "Non classificati" }
                    restoredChannels.add(
                        TvChannel(name, url, item.optString("logo"), if (legacySelectionReset) false else item.optBoolean("selected", false),
                            category, item.optString("tvgId"), item.optString("tvgName"))
                    )
                    restoredCategories.add(category)
                }
                val expanded = state.optJSONArray("expandedCategories") ?: JSONArray()
                val restoredExpanded = buildSet {
                    for (i in 0 until expanded.length()) {
                        expanded.optString(i).takeIf { it.isNotBlank() }?.let { add(it) }
                    }
                }
                Triple(restoredChannels, restoredCategories, restoredExpanded)
            }

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                channelLibraryRestorePending = false
                restored.onSuccess { (restoredChannels, restoredCategories, restoredExpanded) ->
                    channels.clear()
                    channels.addAll(restoredChannels)
                    restoredCategories.forEach { if (it !in categories) categories.add(it) }
                    expandedCategoryPaths.clear()
                    expandedCategoryPaths.addAll(restoredExpanded)
                    Log.i("DAVIX-ARCHIVE", "Ripristinati " + channels.size + " canali dall’archivio locale")
                    renderChannels()
                    renderSavedPlaylists()
                    // Rewrites legacy URL-keyed category preferences using non-reversible URL fingerprints.
                    saveCategorySettings()
                    // Migrazione una tantum: gli archivi precedenti partivano tutti selezionati.
                    // Dalla versione 2 in poi lo stato scelto dall'utente viene mantenuto.
                    if (legacySelectionReset || archiveNeedsEncryption) persistChannelLibrary()
                    if (channels.isEmpty()) restoreMostRecentSavedPlaylist()
                }.onFailure {
                    // Non cancellare o sovrascrivere un archivio illeggibile: serve per diagnosi e recupero.
                    Log.e("DAVIX-ARCHIVE", "Archivio canali non leggibile; file conservato per diagnosi", it)
                    restoreMostRecentSavedPlaylist()
                }
                restoreExecutor.shutdown()
            }
        }
    }

    private fun restoreMostRecentSavedPlaylist() {
        if (importInProgress || channels.isNotEmpty()) return
        val latest = savedPlaylistDirectory().listFiles()
            ?.filter { it.isFile }
            ?.maxByOrNull { it.lastModified() }
            ?: return
        Log.i("DAVIX-ARCHIVE", "Ripristino automatico playlist salvata: ${latest.name}")
        loadSavedPlaylist(latest)
    }

    private fun loadCategorySettings() {
        val saved = categoryPrefs.getString("categories_json", null)
        if (!saved.isNullOrBlank()) runCatching {
            val array = JSONArray(saved)
            val loaded = (0 until array.length()).map { normalizeCategoryPath(array.optString(it)) }.filter { it.isNotBlank() && it.length <= 80 }.distinct()
            if (loaded.isNotEmpty()) { categories.clear(); categories.addAll(loaded) }
        }
        if ("Non classificati" !in categories) categories.add("Non classificati")
    }

    private fun categoryMappingKey(url: String): String =
        "urlsha256:" + XtreamStreamScanner.cacheKey(url)

    private fun savedCategoryForUrl(mapping: JSONObject, url: String): String =
        mapping.optString(categoryMappingKey(url)).ifBlank { mapping.optString(url) }

    private fun restoreChannelCategories(items: List<TvChannel>) {
        val mapping = runCatching { JSONObject(categoryPrefs.getString("channel_categories_json", "{}") ?: "{}") }.getOrNull() ?: return
        items.forEach { channel ->
            val savedCategory = savedCategoryForUrl(mapping, channel.url)
            if (savedCategory.isNotBlank()) channel.category = normalizeCategoryPath(savedCategory)
            if (channel.category !in categories) categories.add(channel.category)
        }
    }

    private fun saveCategorySettings() {
        // Snapshot leggero sul main thread; JSON e aggiornamento preferenze sono serializzati sul writer.
        val categorySnapshot = categories.toList()
        val channelCategorySnapshot = channels.map { categoryMappingKey(it.url) to it.category }
        runCatching {
            archiveExecutor.execute {
                runCatching {
                    val mapping = JSONObject()
                    channelCategorySnapshot.forEach { (url, category) -> mapping.put(url, category) }
                    categoryPrefs.edit()
                        .putString("categories_json", JSONArray(categorySnapshot).toString())
                        .putString("channel_categories_json", mapping.toString())
                        .apply()
                }.onFailure {
                    Log.w("DAVIX-ARCHIVE", "Salvataggio impostazioni categorie non riuscito", it)
                }
            }
        }.onFailure {
            Log.w("DAVIX-ARCHIVE", "Impossibile accodare il salvataggio delle categorie", it)
        }
    }

    private fun updateCount() {
        count.text = "${channels.size} canali importati · ${channels.count { it.selected }} selezionati per esportazione"
    }

    private fun streamMimeType(rawUrl: String): String? {
        val path = runCatching { Uri.parse(rawUrl.substringBefore('|')).lastPathSegment.orEmpty().lowercase(Locale.ROOT) }
            .getOrDefault("")
        return when {
            path.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
            path.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
            path.endsWith(".ism") || path.endsWith(".isml") -> MimeTypes.APPLICATION_SS
            else -> null
        }
    }

    private fun playChannel(index: Int, clickedButton: Button? = null) {
        val channel = channels.getOrNull(index) ?: return
        // Non ricostruire migliaia di righe ad ogni cambio canale: aggiorna solo
        // il pulsante precedente e quello appena premuto, evitando blocchi UI.
        playingChannelButton?.let { previous ->
            val previousName = previous.tag as? String ?: "Canale"
            previous.text = "▶  $previousName"
            previous.setTextColor(Color.WHITE)
        }
        currentPlayingChannelUrl = channel.url
        clickedButton?.apply {
            tag = channel.name
            text = "▶  IN ONDA · ${channel.name}"
            setTextColor(CYAN)
        }
        playingChannelButton = clickedButton
        if (::currentChannelLabel.isInitialized) currentChannelLabel.text = "▶  IN RIPRODUZIONE · ${channel.name}"
        playbackGeneration++
        status.text = "Avvio: ${channel.name}"
        try {
            playbackRetryTask?.let { retryHandler.removeCallbacks(it) }
            playbackRetryTask = null
            playbackRetryCount = 0
            // Imposta il MIME solo quando il formato è riconoscibile dall'URL.
            // Per gli altri flussi lascia decidere a Media3 (TS, MP4, ecc.).
            val mediaItemBuilder = MediaItem.Builder()
                .setUri(channel.url)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(channel.name).build())
            streamMimeType(channel.url)?.let { mediaItemBuilder.setMimeType(it) }
            val mediaItem = mediaItemBuilder
                .setLiveConfiguration(
          MediaItem.LiveConfiguration.Builder()
              .setTargetOffsetMs(120_000)
              .setMinOffsetMs(75_000)
              .setMaxOffsetMs(300_000)
              .setMinPlaybackSpeed(0.96f)
              .setMaxPlaybackSpeed(1.03f)
              .build()
      )
                .build()
            player.setMediaItem(mediaItem)
            player.prepare()
            player.playWhenReady = true
        } catch (e: Exception) {
            status.text = "Impossibile avviare il canale: ${e.message}"
        }
    }

    private fun selectedChannels() = channels.filter { it.selected }

    private fun requestHtmlExport() {
        val chosen = selectedChannels()
        if (chosen.isEmpty()) { toast("Seleziona almeno un canale."); return }
        AlertDialog.Builder(this)
            .setTitle("Preparare il Player HTML?")
            .setMessage("Per proteggere i canali, il file HTML non conterrà indirizzi o nomi della playlist. La selezione verrà pubblicata su DAVIX Cloud Sync e sarà visibile solo dopo l'attivazione con un codice monouso.")
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Pubblica ed esporta") { _, _ ->
                val payload = JSONArray()
                chosen.forEachIndexed { index, channel ->
                    payload.put(
                        JSONObject()
                            .put("id", "channel-$index")
                            .put("name", channel.name)
                            .put("url", channel.url)
                            .put("logo", channel.logo)
                            .put("category", channel.category)
                            .put("tvgId", channel.tvgId)
                            .put("tvgName", channel.tvgName)
                    )
                }
                runCloudAdminTask(
                    task = { CloudSyncAdmin.publish(this, payload, JSONArray(categories)) },
                    onSuccess = { result ->
                        pendingHtml = makePlayerHtml(chosen)
                        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "text/html"
                            putExtra(Intent.EXTRA_TITLE, "davix-player.html")
                        }
                        status.text = "Playlist pubblicata · ${result.optInt("channelCount")} canali. Scegli dove salvare il file HTML."
                        startActivityForResult(intent, SAVE_HTML)
                    }
                )
            }
            .show()
    }

    private fun makePlayerHtml(chosen: List<TvChannel>): String {
        // I canali non vengono incorporati nell'HTML distribuito: sono consegnati
        // dal Cloud Sync solo dopo l'attivazione monouso del singolo browser.
        val template = assets.open("player-template.html")
            .bufferedReader(StandardCharsets.UTF_8)
            .use { it.readText() }
        check(!template.contains("__CHANNELS__") && template.contains("__CLOUD_SYNC_URL__")) {
            "Modello DAVIX Player non valido: i canali non devono essere incorporati e il segnaposto Cloud Sync è obbligatorio."
        }
        return template.replace("__CLOUD_SYNC_URL__", JSONObject.quote(CloudSyncAdmin.baseUrl(this)))
    }

    private fun toggleFullscreen() {
        if (!fullscreen) {
            // Contenitore dedicato: la PlayerView non diventa direttamente la root.
            (playerView.parent as? ViewGroup)?.removeView(playerView)
            fullscreenContainer.removeAllViews()
            fullscreenContainer.setBackgroundColor(Color.BLACK)
            fullscreenContainer.addView(playerView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            val exitButton = Button(this).apply {
                text = "✕"
                textSize = 18f
                isAllCaps = false
                setTextColor(Color.WHITE)
                setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xCC26364D.toInt()))
                setOnClickListener { toggleFullscreen() }
            }
            fullscreenContainer.addView(exitButton, FrameLayout.LayoutParams(dp(52), dp(48), Gravity.TOP or Gravity.END).apply { topMargin = dp(12); marginEnd = dp(12) })
            fullscreen = true
            setContentView(fullscreenContainer)
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            fullscreen = false
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            window.decorView.systemUiVisibility = 0
            (playerView.parent as? ViewGroup)?.removeView(playerView)
            fullscreenContainer.removeAllViews()
            setContentView(mainContainer)
            playerDock.addView(playerView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(176)))
        }
    }

    @Deprecated("Legacy back callback retained for broad Android compatibility")
    override fun onBackPressed() {
        if (fullscreen) toggleFullscreen() else super.onBackPressed()
    }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onStart() {
        super.onStart()
        retryHandler.removeCallbacks(playbackInfoTask)
        retryHandler.post(playbackInfoTask)
    }

    override fun onStop() {
        if (!channelLibraryRestorePending) persistChannelLibrary(immediate = true)
        saveCategorySettings()
        retryHandler.removeCallbacks(playbackInfoTask)
        // Non lasciare il flusso attivo quando l'app passa davvero in background.
        // Il cambio orientamento è gestito da configChanges nel manifest.
        playbackRetryTask?.let { retryHandler.removeCallbacks(it) }
        playbackRetryTask = null
        stablePlaybackTask?.let { retryHandler.removeCallbacks(it) }
        stablePlaybackTask = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (::player.isInitialized && (castPlayer == null || player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_LOCAL)) player.pause()
        super.onStop()
    }

    override fun onDestroy() {
        playbackRetryTask?.let { retryHandler.removeCallbacks(it) }
        retryHandler.removeCallbacksAndMessages(null)
        stablePlaybackTask = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (::player.isInitialized) player.release()
        // Esegue anche gli snapshot già accodati prima di terminare il writer.
        archiveExecutor.shutdown()
        super.onDestroy()
    }
}