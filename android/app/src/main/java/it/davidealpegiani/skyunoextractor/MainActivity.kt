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
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
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
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultLivePlaybackSpeedControl
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.ui.PlayerView
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale

data class TvChannel(
    val name: String,
    val url: String,
    val logo: String = "",
    var selected: Boolean = true,
    var category: String = "Non classificati"
)

@OptIn(UnstableApi::class)
class MainActivity : FragmentActivity() {
    private val channels = mutableListOf<TvChannel>()
    private val categories = mutableListOf("Intrattenimento", "Sport", "Cinema", "Documentari", "Musica", "XXX", "Non classificati")
    private val categoryPrefs by lazy { getSharedPreferences("davix_extractor_categories", MODE_PRIVATE) }
    // Selezione multipla separata dalla selezione di esportazione.
    private var bulkSelectionMode = false
    private val bulkSelectedIndices = mutableSetOf<Int>()
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
            playbackInfo.text = "HLS: $state · buffer ${aheadMs / 1000}s · live $liveText · " +
                if (player.isLoading) "download attivo" else "download fermo"
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
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadCategorySettings()
        window.statusBarColor = BG
        window.navigationBarColor = BG
        window.decorView.systemUiVisibility = 0
        // Profilo scelto dopo il confronto delle revisioni:
        // Profilo live allineato al Player Lite: riserva ampia senza inseguire il live edge.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                60_000, // soglia minima di caricamento
                180_000, // tetto temporale per assorbire oscillazioni di rete
                15_000, // avvio iniziale
                45_000 // ripresa dopo esaurimento del buffer
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        // Dopo ogni rebuffer Media3 aumenta il margine dal bordo live, così il
        // player smette di inseguire segmenti pubblicati in ritardo dal server.
        val liveSpeedControl = DefaultLivePlaybackSpeedControl.Builder()
            .setFallbackMinPlaybackSpeed(0.97f)
            .setFallbackMaxPlaybackSpeed(1.04f)
            .setTargetLiveOffsetIncrementOnRebufferMs(15_000)
            .build()

        // Timeout espliciti e retry progressivi gestiti da Media3.
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(45_000)
            .setAllowCrossProtocolRedirects(true)

        val hlsMediaSourceFactory = HlsMediaSource.Factory(httpDataSourceFactory)
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(8))

        localPlayer = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setLivePlaybackSpeedControl(liveSpeedControl)
            .setMediaSourceFactory(hlsMediaSourceFactory)
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
                val retryable = error.errorCodeName.contains("IO_") ||
                    error.errorCodeName.contains("BEHIND_LIVE_WINDOW")
                if (retryable && playbackRetryCount < 5) {
                    val delays = longArrayOf(2_000L, 4_000L, 8_000L, 12_000L, 15_000L)
                    val delay = delays[playbackRetryCount++]
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
                    status.text = "Errore ${error.errorCodeName} (${error.errorCode}): " +
                        detail.ifBlank { "flusso interrotto o non compatibile" }
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
        playbackGeneration++
        try {
            playbackRetryTask?.let { retryHandler.removeCallbacks(it) }
            playbackRetryTask = null
            playbackRetryCount = 0
            val mediaItem = MediaItem.Builder()
                .setUri(streamUrl)
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(channelName).build())
                .setLiveConfiguration(
                    MediaItem.LiveConfiguration.Builder()
                        .setTargetOffsetMs(60_000)
                        .setMinOffsetMs(45_000)
                        .setMaxOffsetMs(120_000)
                        .setMinPlaybackSpeed(0.97f)
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
        playerView = PlayerView(this).apply {
            player = this@MainActivity.player
            useController = true
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            setBackgroundColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(176))
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
        label("Seleziona file HTML, HTM, TXT, M3U, M3U8 o JSON. I file vengono letti direttamente sul dispositivo.", MUTED)
        button("📂  Seleziona file", true) { showImportDestinationDialog() }
        status = label("Nessun file caricato.", MUTED)

        section("2 · PLAYLIST SALVATE")
        label("Le copie salvate restano sul dispositivo anche dopo la chiusura dell'app, finché non le elimini.", MUTED, 12f)
        savedPlaylistsList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(savedPlaylistsList)
        button("🗑  Svuota archivio e lista canali", false) { confirmClearSavedPlaylists() }
        renderSavedPlaylists()

        section("3 · CANALI E SELEZIONE")
        count = label("0 canali importati", CYAN)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(buttonView("Seleziona tutti") { channels.forEach { it.selected = true }; renderChannels() }, LinearLayout.LayoutParams(0, dp(46), 1f))
        row.addView(space(8), LinearLayout.LayoutParams(dp(8), 1))
        row.addView(buttonView("Deseleziona") { channels.forEach { it.selected = false }; renderChannels() }, LinearLayout.LayoutParams(0, dp(46), 1f))
        root.addView(row)
        button("▦  Gestisci categorie e sottocartelle", false) { showCategoryManager() }
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
                            .put("url", channel.url)
                            .put("logo", channel.logo)
                            .put("category", channel.category)
                    )
                }
                runCloudAdminTask(
                    task = { CloudSyncAdmin.publish(this, payload, JSONArray(categories)) },
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
        val options = listOf("Mantieni i gruppi originali della playlist") +
            categories.filter { it != "Non classificati" } +
            "＋  Crea nuova sezione o sottocartella…"
        AlertDialog.Builder(this)
            .setTitle("Destinazione dell'importazione")
            .setMessage("Scegli dove inserire i canali. Puoi mantenere i group-title originali oppure assegnare tutto a una sezione.")
            .setItems(options.toTypedArray()) { _, which ->
                when {
                    which == 0 -> { pendingImportCategory = null; openPicker() }
                    which == options.lastIndex -> promptNewCategoryForImport()
                    else -> { pendingImportCategory = options[which]; openPicker() }
                }
            }
            .setNegativeButton("Annulla", null)
            .show()
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

    private fun importUris(uris: List<Uri>) {
        var added = 0
        var duplicates = 0
        var invalid = 0
        uris.forEach { uri ->
            try {
                val bytes = readImportBytes(uri)
                val text = String(bytes, StandardCharsets.UTF_8)
                val displayName = queryDisplayName(uri)
                val found = parseChannels(text)
                restoreChannelCategories(found)
                pendingImportCategory?.let { destination ->
                    found.forEach { it.category = destination }
                    if (destination !in categories) categories.add(destination)
                }
                if (found.isEmpty()) {
                    invalid++
                } else {
                    savePlaylistCopy(displayName, bytes)
                    found.forEach { ch ->
                        if (channels.any { it.name.equals(ch.name, true) && it.url == ch.url }) duplicates++
                        else {
                            ch.selected = !(ch.name.contains("sky", true) && (ch.name.contains("plus", true) || ch.name.contains("+1")))
                            channels.add(ch)
                            if (ch.category !in categories) categories.add(ch.category)
                            added++
                        }
                    }
                }
            } catch (_: Exception) { invalid++ }
        }
        pendingImportCategory = null
        renderChannels()
        renderSavedPlaylists()
        status.text = "File analizzati: ${uris.size} · Nuovi canali: $added · Duplicati ignorati: $duplicates · File senza canali validi: $invalid"
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
            row.addView(buttonView("Carica") { loadSavedPlaylist(file) })
            savedPlaylistsList.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(5)
            })
        }
    }

    private fun loadSavedPlaylist(file: java.io.File) {
        try {
            if (!file.exists()) {
                toast("La playlist salvata non è più disponibile.")
                renderSavedPlaylists()
                return
            }
            val found = parseChannels(file.readText(StandardCharsets.UTF_8))
            restoreChannelCategories(found)
            if (found.isEmpty()) {
                toast("Nessun canale valido trovato nel file salvato.")
                return
            }
            var added = 0
            var duplicates = 0
            found.forEach { ch ->
                if (channels.any { it.name.equals(ch.name, true) && it.url == ch.url }) duplicates++
                else {
                    ch.selected = !(ch.name.contains("sky", true) && (ch.name.contains("plus", true) || ch.name.contains("+1")))
                    channels.add(ch)
                    added++
                }
            }
            renderChannels()
            status.text = "Playlist caricata: ${file.name.substringAfter('_')} · Nuovi canali: $added · Duplicati ignorati: $duplicates"
        } catch (e: Exception) {
            toast("Impossibile caricare la playlist: ${e.message}")
        }
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
                renderChannels()
                renderSavedPlaylists()
                status.text = "Archivio svuotato. Puoi caricare una nuova playlist."
                toast("Archivio e lista canali svuotati.")
            }
            .show()
    }

    // Evita di caricare file arbitrariamente grandi in memoria durante l'importazione.
    private fun readImportBytes(uri: Uri): ByteArray {
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
            }
            return output.toByteArray()
        }
    }

    private fun parseChannels(text: String): List<TvChannel> {
        val found = mutableListOf<TvChannel>()
        val seen = mutableSetOf<String>()
        fun add(nameRaw: String?, urlRaw: String?, logoRaw: String? = "", categoryRaw: String? = null) {
            val name = nameRaw?.trim()?.takeIf { it.isNotBlank() } ?: return
            val url = urlRaw?.trim()?.replace("&amp;", "&")?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) } ?: return
            val key = name.lowercase(Locale.ROOT) + "|" + url
            if (seen.add(key)) found.add(TvChannel(name, url, logoRaw?.trim().orEmpty(), category = categoryRaw?.trim()?.takeIf { it.isNotBlank() } ?: "Non classificati"))
        }

        val lines = text.lines()
        for (i in lines.indices) {
            val line = lines[i].trim()
            if (line.startsWith("#EXTINF", true)) {
                val name = line.substringAfterLast(',').trim()
                val logo = Regex("tvg-logo=[\"']([^\"']+)").find(line)?.groupValues?.getOrNull(1).orEmpty()
                val group = Regex("group-title=[\"']([^\"']+)").find(line)?.groupValues?.getOrNull(1)
                val url = lines.drop(i + 1).firstOrNull { it.trim().isNotEmpty() && !it.trim().startsWith("#") }?.trim()
                add(name, url, logo, group)
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
                field(obj, listOf("category", "group", "group-title"))
            )
        }

        try {
            val trimmed = text.trim()
            val rootObject = if (trimmed.startsWith("{")) JSONObject(trimmed) else null
            val array = when {
                trimmed.startsWith("[") -> JSONArray(trimmed)
                rootObject != null -> rootObject.optJSONArray("channels")
                    ?: rootObject.optJSONArray("items")
                    ?: rootObject.optJSONArray("data")
                else -> null
            }
            if (array != null) for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                add(
                    o.optString("name", o.optString("title", o.optString("channel_name"))),
                    o.optString("url", o.optString("stream", o.optString("src", o.optString("link")))),
                    o.optString("logo", o.optString("tvg-logo")),
                    o.optString("category", o.optString("group", o.optString("group-title")))
                )
            }
        } catch (_: Exception) { }
        return found
    }

    private fun renderChannels() {
        saveCategorySettings()
        selectedList.removeAllViews()
        otherList.removeAllViews()
        val selected = channels.withIndex().filter { it.value.selected }
        val others = channels.withIndex().filter { !it.value.selected }
        selectedHeading.visibility = if (selected.isEmpty()) View.GONE else View.VISIBLE
        otherHeading.visibility = if (others.isEmpty()) View.GONE else View.VISIBLE

        fun addChannelRow(index: Int, channel: TvChannel, target: LinearLayout) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(5), dp(8), dp(5))
                setBackgroundColor(PANEL)
            }
            val check = CheckBox(this).apply {
                isChecked = channel.selected
                buttonTintList = android.content.res.ColorStateList.valueOf(CYAN)
                setOnCheckedChangeListener { _, checked ->
                    channel.selected = checked
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
            val play = Button(this).apply {
                text = "▶  ${channel.name}"
                isAllCaps = false
                textSize = 13f
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.TRANSPARENT)
                setOnClickListener { playChannel(index) }
            }
            row.addView(play, LinearLayout.LayoutParams(0, dp(48), 1f))
            row.addView(buttonView(channel.category) { showCategoryPicker(channel) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)))
            target.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(5)
            })
        }
        selected.forEach { addChannelRow(it.index, it.value, selectedList) }
        others.forEach { addChannelRow(it.index, it.value, otherList) }
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
        raw.split(">").map { it.trim().replace(Regex("\\\\s+"), " ") }
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
            if (action == 0) {
                val input = EditText(this).apply { setText(category); setSingleLine(true); filters = arrayOf(android.text.InputFilter.LengthFilter(40)) }
                AlertDialog.Builder(this).setTitle("Rinomina categoria").setView(input).setNegativeButton("Annulla", null)
                    .setPositiveButton("Salva") { _, _ ->
                        val renamed = input.text.toString().trim().replace(Regex("\\s+"), " ")
                        if (renamed.isBlank() || categories.any { it != category && it.equals(renamed, true) }) toast("Nome vuoto o già utilizzato.")
                        else { categories[categories.indexOf(category)] = renamed; channels.filter { it.category == category }.forEach { it.category = renamed }; renderChannels() }
                    }.show()
            } else {
                AlertDialog.Builder(this).setTitle("Eliminare $category?").setMessage("I canali verranno spostati in Non classificati.")
                    .setNegativeButton("Annulla", null).setPositiveButton("Elimina") { _, _ ->
                        categories.remove(category); channels.filter { it.category == category }.forEach { it.category = "Non classificati" }; renderChannels()
                    }.show()
            }
        }.setNegativeButton("Indietro", null).show()
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

    private fun restoreChannelCategories(items: List<TvChannel>) {
        val mapping = runCatching { JSONObject(categoryPrefs.getString("channel_categories_json", "{}") ?: "{}") }.getOrNull() ?: return
        items.forEach { channel ->
            val savedCategory = mapping.optString(channel.url)
            if (savedCategory.isNotBlank()) channel.category = normalizeCategoryPath(savedCategory)
            if (channel.category !in categories) categories.add(channel.category)
        }
    }

    private fun saveCategorySettings() {
        val mapping = JSONObject()
        channels.forEach { mapping.put(it.url, it.category) }
        categoryPrefs.edit().putString("categories_json", JSONArray(categories).toString())
            .putString("channel_categories_json", mapping.toString()).apply()
    }

    private fun updateCount() {
        count.text = "${channels.size} canali importati · ${channels.count { it.selected }} selezionati per esportazione"
    }

    private fun playChannel(index: Int) {
        val channel = channels.getOrNull(index) ?: return
        playbackGeneration++
        status.text = "Avvio: ${channel.name}"
        try {
            playbackRetryTask?.let { retryHandler.removeCallbacks(it) }
            playbackRetryTask = null
            playbackRetryCount = 0
            // Dichiarazione HLS esplicita anche per URL con token o senza estensione .m3u8.
            val mediaItem = MediaItem.Builder()
                .setUri(channel.url)
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(channel.name).build())
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
        super.onDestroy()
    }
}
