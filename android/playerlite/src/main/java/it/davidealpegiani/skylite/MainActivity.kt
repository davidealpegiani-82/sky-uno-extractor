package it.davidealpegiani.skylite

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.content.res.Configuration
import android.os.Build
import android.net.Uri
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
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
import androidx.media3.session.MediaSession
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultLivePlaybackSpeedControl
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import java.io.IOException
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import org.json.JSONArray
import org.json.JSONObject


@UnstableApi
class MainActivity : Activity() {
    private val PICK_LOCAL_PLAYLIST = 4201
    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var resumeAfterBackground = false
    private lateinit var playerView: PlayerView
    private lateinit var titleView: TextView
    private lateinit var statusView: TextView
    private lateinit var diagnosticsView: TextView
    private lateinit var fullscreenButton: TextView
    private lateinit var updatesButton: TextView
    private lateinit var fullscreenChannelsButton: TextView
    private lateinit var fullscreenChannelDrawer: LinearLayout
    private lateinit var fullscreenChannelList: LinearLayout
    private var isChannelDrawerOpen = false
    private var localMode = false
    private lateinit var importPlaylistButton: TextView
    private var isFullscreen = false
    private var fullscreenOverlayVisible = false
    private var focusBeforeFullscreen: View? = null
    private val fullscreenOverlayHideTask = Runnable { hideFullscreenOverlay() }
    private val retryHandler = Handler(Looper.getMainLooper())
    private val diagnosticsTask = object : Runnable {
        override fun run() {
            val current = player
            if (current == null || !::diagnosticsView.isInitialized || isFinishing || isDestroyed) return
            if (!PlayerCloudSync.hasValidLease(this@MainActivity) && !localMode && current.mediaItemCount > 0) {
                PlayerCloudSync.clearAuthorization(this@MainActivity)
                stopPlaybackForAuthorization()
                cloudChannels.clear()
                renderCloudChannels()
                cloudStatusView.text = "Licenza offline scaduta. Riconnetti e attiva nuovamente il dispositivo."
                return
            }
            // Non mostrare lo stato interno di ExoPlayer come se fosse un flusso attivo
            // quando l'utente non ha ancora selezionato un canale.
            if (current.mediaItemCount == 0 || selectedChannelForRecovery == null) {
                val leaseState = if (PlayerCloudSync.hasValidLease(this@MainActivity)) "licenza valida" else "licenza non attiva"
                diagnosticsView.text = "PLAYER: IN ATTESA · nessun canale selezionato\n" +
                    "$leaseState · pronto per la riproduzione"
                lastReportedPlaybackState = Player.STATE_IDLE
                retryHandler.postDelayed(this, 1000L)
                return
            }
            val now = System.currentTimeMillis()
            if (PlayerCloudSync.hasValidLease(this@MainActivity) && now - lastAuthorizationCheckAt >= 6 * 60 * 60 * 1000L) {
                lastAuthorizationCheckAt = now
                syncCloudAtStartup()
            }
            val aheadMs = (current.bufferedPosition - current.currentPosition).coerceAtLeast(0L)
            val state = when (current.playbackState) {
                Player.STATE_BUFFERING -> "BUFFERING"
                Player.STATE_READY -> "READY"
                Player.STATE_IDLE -> "IDLE"
                Player.STATE_ENDED -> "ENDED"
                else -> "?"
            }
            if (current.playbackState == Player.STATE_BUFFERING && lastReportedPlaybackState == Player.STATE_READY) {
                rebufferCount++
            }
            lastReportedPlaybackState = current.playbackState
            val liveOffsetMs = current.currentLiveOffset
            val liveOffset = if (liveOffsetMs == C.TIME_UNSET) "n/d" else "${liveOffsetMs / 1000}s"
            val lease = if (PlayerCloudSync.hasValidLease(this@MainActivity)) "valida" else "scaduta/non attiva"
            diagnosticsView.text = "HLS: $state · buffer ${aheadMs / 1000}s · live $liveOffset\n" +
                "rebuffer $rebufferCount · retry $retryCount · licenza $lease\n" +
                "${if (current.isLoading) "download attivo" else "download in attesa"} · $lastHlsLoad · errore $lastPlayerError"
            monitorPlaybackHealth(current, now, aheadMs)
            retryHandler.postDelayed(this, 1000L)
        }
    }
    // Ultimo caricamento HLS completato: aiuta a distinguere segmenti lenti da pause normali.
    @Volatile private var lastHlsLoad = "segmenti: in attesa"
    private var retryCount = 0
    private var retryTask: Runnable? = null
    // Invalida in modo atomico i retry appartenenti a un canale o a una sessione precedente.
    private var playbackGeneration = 0L
    private var rebufferCount = 0
    private var lastReportedPlaybackState = Player.STATE_IDLE
    private var lastPlayerError = "nessuno"
    private var lastHttpStatusCode: Int? = null
    private var lastServerRetryAfterMs = 0L
    private lateinit var endpointInput: EditText
    private lateinit var activationCodeInput: EditText
    private lateinit var pairButton: Button
    private lateinit var cloudStatusView: TextView
    private lateinit var epgStatusView: TextView
    private lateinit var channelsList: LinearLayout
    private lateinit var channelScroll: ScrollView
    private lateinit var channelBrowser: LinearLayout
    private lateinit var playerContainer: FrameLayout
    private lateinit var controlsPanel: LinearLayout
    private lateinit var topBar: LinearLayout
    private lateinit var cloudHeaderView: TextView
    private lateinit var channelsHeaderView: TextView
    private lateinit var fullscreenDrawerHeader: TextView
    private val cloudHandler = Handler(Looper.getMainLooper())
    private val cloudExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val cloudChannels = mutableListOf<CloudChannel>()
    private val epgGuides = mutableMapOf<String, EpgGuide>()
    private var epgRefreshInProgress = false
    private val cloudCategories = mutableListOf<String>()
    // Le cartelle restano chiuse all’apertura; l’espansione è condivisa tra lista e drawer fullscreen.
    private val expandedCategoryPaths = mutableSetOf<String>()
    private val favoriteUrls = mutableSetOf<String>()
    private val playerPrefs by lazy { getSharedPreferences("davix_player_preferences", MODE_PRIVATE) }
    private val selectedSourceByGroup = mutableMapOf<String, String>()
    private var lastAuthorizationCheckAt = 0L
    private var lastChannelClickName: String? = null
    private var lastChannelClickAt = 0L
    private lateinit var hlsHttpDataSourceFactory: DefaultHttpDataSource.Factory
    private var defaultHlsUserAgent: String = ""
    private var selectedChannelForRecovery: CloudChannel? = null
    private var bufferingStartedAt = 0L
    private var lastPlaybackProgressAt = 0L
    private var lastObservedPositionMs = C.TIME_UNSET
    private var lastKnownNetworkAvailable: Boolean? = null
    // Impedisce al watchdog STATE_IDLE di riavviare errori permanenti (404, 401, parsing/decoder).
    private var terminalPlaybackFailure = false
    // Cronologia circolare locale: conserva solo eventi tecnici, mai URL o token.
    private val playbackDiagnosticEvents = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildScreen()
        loadCachedChannels()
        initializePlayer()
        handlePlayIntent(intent)
        syncCloudAtStartup()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun buildScreen() {
        val tvMode = isTvDevice()
        val wideLayout = tvMode || (
            resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                resources.configuration.screenWidthDp >= 600
            )
        val sidebarWidthDp = if (tvMode) 184 else minOf(300, maxOf(240, (resources.configuration.screenWidthDp * 0.38f).toInt()))
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(5, 10, 22), Color.rgb(12, 20, 38), Color.rgb(5, 9, 18)))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        if (tvMode) {
            topBar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(30), dp(10), dp(30), dp(10))
                background = android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(Color.rgb(12, 25, 46), Color.rgb(7, 14, 28))
                ).apply { setStroke(dp(1), Color.rgb(35, 69, 101)) }
            }
            val brandStack = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
            val brand = TextView(this).apply {
                text = android.text.SpannableString("DAVIX  PLAYER").apply {
                    setSpan(android.text.style.ForegroundColorSpan(Color.rgb(69, 220, 255)), 0, 3, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                setTextColor(Color.WHITE)
                textSize = 23f
                setTypeface(null, android.graphics.Typeface.BOLD)
                letterSpacing = 0.12f
            }
            val strapline = TextView(this).apply {
                text = "ENTERTAINMENT  •  LIVE STREAMING"
                setTextColor(Color.rgb(122, 164, 194))
                textSize = 10f
                letterSpacing = 0.18f
            }
            brandStack.addView(brand)
            brandStack.addView(strapline)
            topBar.addView(brandStack, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            val livePill = TextView(this).apply {
                text = "●  LIVE"
                setTextColor(Color.rgb(112, 245, 202))
                textSize = 13f
                setTypeface(null, android.graphics.Typeface.BOLD)
                letterSpacing = 0.10f
                gravity = Gravity.CENTER
                setPadding(dp(20), dp(11), dp(20), dp(11))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.rgb(13, 54, 57))
                    cornerRadius = dp(24).toFloat()
                    setStroke(dp(1), Color.rgb(42, 145, 135))
                }
            }
            topBar.addView(livePill, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(46)))
            root.addView(topBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(82)))
        }
        val tvContent = if (wideLayout) LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(5, 10, 22), Color.rgb(9, 18, 34))
            )
        } else root
        if (wideLayout) root.addView(tvContent, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val controls = if (wideLayout) LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(if (tvMode) 12 else 26), dp(if (tvMode) 12 else 24), dp(if (tvMode) 12 else 26), dp(if (tvMode) 12 else 24))
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.rgb(17, 29, 52), Color.rgb(9, 17, 32))).apply { setStroke(dp(1), Color.rgb(38, 65, 98)) }
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        } else root
        controlsPanel = controls
        if (wideLayout && !tvMode) {
            tvContent.addView(controls, LinearLayout.LayoutParams(dp(sidebarWidthDp), ViewGroup.LayoutParams.MATCH_PARENT))
        }
        titleView = TextView(this).apply {
            text = android.text.SpannableString("DAVIX  PLAYER").apply {
                setSpan(android.text.style.ForegroundColorSpan(Color.rgb(69, 220, 255)), 0, 3, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(android.text.style.RelativeSizeSpan(1.08f), 0, 3, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            setTextColor(Color.WHITE)
            textSize = if (tvMode) 19f else 21f
            setTypeface(null, android.graphics.Typeface.BOLD)
            letterSpacing = 0.10f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(if (tvMode) 8 else 26), dp(8), dp(6))
        }
        statusView = TextView(this).apply {
            text = if (tvMode) "LIVE TELEVISION  •  BIG SCREEN EXPERIENCE" else "Apri un canale dal DAVIX Player."
            setTextColor(Color.rgb(135, 177, 205))
            textSize = if (tvMode) 11f else 13f
            letterSpacing = if (tvMode) 0.16f else 0f
            setPadding(dp(8), 0, dp(8), dp(if (tvMode) 8 else 22))
        }
        playerView = PlayerView(this).apply {
            isFocusable = tvMode
            isFocusableInTouchMode = !tvMode
            // I controlli non devono riapparire a ogni cambio di focus del PlayerView.
            // Si mostrano solo dopo un input esplicito (telecomando/click) e si nascondono
            // da soli dopo il timeout standard.
            setOnClickListener {
                if (isFullscreen) showFullscreenOverlay(autoHide = true, requestFocus = false)
                else showController()
            }
            setOnTouchListener { _, event ->
                if (!isTvDevice() && isFullscreen && event.action == android.view.MotionEvent.ACTION_DOWN) {
                    // Qualunque tocco sul video richiama i comandi, anche dopo il timeout.
                    showFullscreenOverlay(autoHide = true, requestFocus = false)
                }
                false
            }
            useController = true
            controllerAutoShow = false
            controllerShowTimeoutMs = 5_000
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            keepScreenOn = true
            setBackgroundColor(Color.BLACK)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        controls.addView(titleView)
        controls.addView(statusView)
        diagnosticsView = TextView(this).apply {
            text = "HLS: in attesa · buffer 0s"
            setTextColor(Color.rgb(112, 196, 220))
            textSize = if (tvMode) 10f else 11f
            setPadding(dp(8), dp(7), dp(8), dp(7))
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.rgb(15, 46, 65), Color.rgb(18, 32, 54))).apply { cornerRadius = dp(8).toFloat(); setStroke(dp(1), Color.rgb(36, 111, 145)) }
        }
        controls.addView(diagnosticsView)
        diagnosticsView.setOnLongClickListener {
            val report = synchronized(playbackDiagnosticEvents) {
                if (playbackDiagnosticEvents.isEmpty()) "Nessun evento registrato." else playbackDiagnosticEvents.joinToString("\n")
            }
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Diagnostica DAVIX Player")
                .setMessage(report)
                .setPositiveButton("Chiudi", null)
                .show()
            true
        }
        diagnosticsView.visibility = if (PlayerCloudSync.hasValidLease(this)) View.VISIBLE else View.GONE

        updatesButton = TextView(this).apply {
            text = "⬇  AGGIORNAMENTI DAVIX"
            textSize = if (tvMode) 10f else 13f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            isClickable = true
            isFocusable = true
            setBackground(channelActionBackground())
            setOnFocusChangeListener { _, focused -> background = channelActionBackground(focused) }
            setOnClickListener { (application as DavixUpdaterApplication).showDownloads(this@MainActivity) }
        }
        controls.addView(updatesButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dp(12), dp(8), dp(12), dp(4)) })

        importPlaylistButton = TextView(this).apply {
            text = "＋  IMPORTA PLAYLIST M3U"
            contentDescription = "Importa una playlist locale senza account o sincronizzazione cloud"
            textSize = if (tvMode) 10f else 13f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(dp(8), dp(11), dp(8), dp(11))
            isClickable = true
            isFocusable = true
            setBackground(channelActionBackground())
            setOnFocusChangeListener { _, focused -> background = channelActionBackground(focused) }
            setOnClickListener { openLocalPlaylistPicker() }
        }
        controls.addView(importPlaylistButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dp(12), dp(4), dp(12), dp(4)) })

        cloudHeaderView = TextView(this).apply {
            text = "☁  SINCRONIZZAZIONE CLOUD"
            setTextColor(Color.rgb(112, 196, 220))
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(16, 12, 16, 4)
        }
        // La configurazione Cloud resta interna: non va mostrata nell'interfaccia del Player.

        endpointInput = EditText(this).apply {
            hint = "Indirizzo DAVIX Cloud Sync (HTTPS)"
            setSingleLine(true)
            textSize = 13f
            setText(PlayerCloudSync.baseUrl(this@MainActivity))
            setPadding(16, 4, 16, 4)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(164, 181, 202))
        }
        endpointInput.visibility = View.GONE
        activationCodeInput = EditText(this).apply {
            hint = "Codice monouso generato da Admin"
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            setSingleLine(true)
            textSize = 16f
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            filters = arrayOf(android.text.InputFilter.LengthFilter(10))
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(164, 181, 202))
            setPadding(dp(16), dp(6), dp(16), dp(6))
            setOnEditorActionListener { _, actionId, event ->
                val enterPressed = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE || enterPressed) {
                    beginCloudPairing()
                    true
                } else false
            }
        }
        controls.addView(activationCodeInput, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(54)
        ).apply { setMargins(dp(12), dp(4), dp(12), dp(6)) })
        pairButton = Button(this).apply {
            text = "ATTIVA QUESTO DISPOSITIVO"
            isAllCaps = false
            isFocusable = true
            isSingleLine = true
            maxLines = 1
            includeFontPadding = true
            gravity = Gravity.CENTER
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.rgb(8, 13, 22))
            setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(255, 204, 64)))
            elevation = dp(4).toFloat()
            minHeight = dp(64)
            setPadding(dp(18), dp(14), dp(18), dp(14))
            setLineSpacing(dp(2).toFloat(), 1.0f)
            setOnFocusChangeListener { view, focused -> view.background = primaryActionBackground(focused) }
            background = primaryActionBackground(false)
            setOnClickListener { beginCloudPairing() }
        }
        // L'attivazione si conferma dal campo codice (Invio/Fatto), senza pulsante dedicato.
        cloudStatusView = TextView(this).apply {
            text = "Modalità locale gratuita · importa una playlist M3U. Nessun account necessario."
            setTextColor(Color.rgb(164, 202, 220))
            textSize = if (tvMode) 10f else 12f
            setPadding(dp(8), dp(4), dp(8), dp(6))
        }
        controls.addView(cloudStatusView)
        activationCodeInput.visibility = View.GONE
        cloudStatusView.isClickable = true
        cloudStatusView.isFocusable = true
        cloudStatusView.setOnClickListener {
            activationCodeInput.visibility = if (activationCodeInput.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            if (activationCodeInput.visibility == View.VISIBLE) activationCodeInput.requestFocus()
        }
        epgStatusView = TextView(this).apply {
            text = "EPG: in attesa di caricamento"
            setTextColor(Color.rgb(126, 190, 218))
            textSize = if (tvMode) 9f else 11f
            setPadding(dp(8), dp(2), dp(8), dp(5))
            contentDescription = "Stato guida programmi EPG"
        }
        controls.addView(epgStatusView)

        channelsHeaderView = TextView(this).apply {
            text = "I TUOI CANALI"
            setTextColor(Color.rgb(255, 204, 64))
            textSize = if (tvMode) 18f else 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            letterSpacing = 0.08f
            setPadding(dp(16), dp(10), dp(12), dp(10))
            contentDescription = "Elenco canali. Usa le frecce per scegliere e OK per riprodurre."
        }
        channelsHeaderView.visibility = if (PlayerCloudSync.hasValidLease(this)) View.VISIBLE else View.GONE

        playerContainer = FrameLayout(this).apply {
            layoutParams = if (wideLayout) {
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            } else {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            }
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(0, 0, 0), Color.rgb(7, 18, 34))).apply { setStroke(dp(2), Color.rgb(28, 76, 112)) }
        }
        playerContainer.addView(playerView)
        fullscreenButton = TextView(this).apply {
            text = "⛶  SCHERMO INTERO"
            contentDescription = "Attiva schermo intero"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackground(channelActionBackground())
            setPadding(dp(18), dp(10), dp(18), dp(10))
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = !tvMode
            setOnFocusChangeListener { _, focused -> background = channelActionBackground(focused) }
            setOnClickListener { toggleFullscreen() }
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.TOP
            ).apply { setMargins(0, 16, 16, 0) }
        }
        playerContainer.addView(fullscreenButton)

        fullscreenChannelsButton = TextView(this).apply {
            text = "☷  GUIDA CANALI"
            contentDescription = "Apri elenco canali"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackground(channelActionBackground())
            setPadding(dp(18), dp(12), dp(18), dp(12))
            isClickable = true
            isFocusable = true
            visibility = View.GONE
            setOnFocusChangeListener { _, focused -> background = channelActionBackground(focused) }
            setOnClickListener { toggleFullscreenChannelDrawer() }
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(54), Gravity.START or Gravity.TOP
            ).apply { setMargins(dp(16), dp(16), 0, 0) }
        }
        playerContainer.addView(fullscreenChannelsButton)
        // Su TV l'anteprima resta collassata finché non viene scelto un canale.
        // In questo modo il catalogo recupera subito tutto lo spazio disponibile.
        playerContainer.visibility = if (wideLayout && !tvMode) View.VISIBLE else View.GONE

        fullscreenChannelDrawer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.rgb(13, 26, 48), Color.rgb(7, 14, 27))).apply { setStroke(dp(1), Color.rgb(47, 102, 139)) }
            elevation = dp(24).toFloat()
            visibility = View.GONE
            isFocusable = true
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            layoutParams = FrameLayout.LayoutParams(dp(420), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START)
        }
        fullscreenDrawerHeader = TextView(this).apply {
            text = "  CANALI  ·  SELEZIONA"
            contentDescription = "Chiudi elenco canali"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22), dp(20), dp(16), dp(20))
            isFocusable = true
            isClickable = true
            setBackground(channelActionBackground())
            setOnFocusChangeListener { _, focused -> background = channelActionBackground(focused) }
            setOnClickListener { closeFullscreenChannelDrawer() }
        }
        fullscreenChannelDrawer.addView(fullscreenDrawerHeader, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(68)))
        val drawerScroll = ScrollView(this).apply {
            isFillViewport = true
            isFocusable = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        fullscreenChannelList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(20))
        }
        drawerScroll.addView(fullscreenChannelList)
        fullscreenChannelDrawer.addView(drawerScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        playerContainer.addView(fullscreenChannelDrawer)
        renderFullscreenChannelList()
        channelScroll = ScrollView(this).apply {
            isFillViewport = true
            isFocusable = false
            layoutParams = if (tvMode) {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            } else {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            }
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.rgb(8, 17, 32), Color.rgb(5, 11, 22)))
        }
        channelsList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(12))
        }
        channelScroll.addView(channelsList)
        if (wideLayout) {
            channelBrowser = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.rgb(8, 17, 32), Color.rgb(5, 11, 22)))
                setPadding(dp(if (tvMode) 10 else 12), 0, dp(if (tvMode) 10 else 12), dp(8))
            }
            channelBrowser.addView(channelsHeaderView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            channelBrowser.addView(channelScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            if (tvMode) {
                tvContent.addView(controls, LinearLayout.LayoutParams(dp(sidebarWidthDp), ViewGroup.LayoutParams.MATCH_PARENT))
                tvContent.addView(channelBrowser, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.5f))
                tvContent.addView(playerContainer, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.05f))
            } else {
                controls.addView(channelBrowser, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
                tvContent.addView(playerContainer)
            }
        } else {
            root.addView(channelsHeaderView)
            root.addView(channelScroll)
            root.addView(playerContainer)
        }
        setContentView(root)
        if (PlayerCloudSync.hasValidLease(this)) {
            endpointInput.visibility = View.GONE
            activationCodeInput.visibility = View.GONE
            pairButton.visibility = View.GONE
            cloudStatusView.text = "Dispositivo autorizzato · licenza offline valida."
            channelsHeaderView.visibility = View.VISIBLE
            diagnosticsView.visibility = View.VISIBLE
        }
        if (tvMode && cloudChannels.isEmpty()) importPlaylistButton.post { importPlaylistButton.requestFocus() }
    }

    private fun isTvDevice(): Boolean {
        val features = packageManager
        val televisionUiMode =
            (resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
                Configuration.UI_MODE_TYPE_TELEVISION
        val leanback = features.hasSystemFeature("android.software.leanback")
        val televisionHardware = features.hasSystemFeature("android.hardware.type.television")
        val fireTv = features.hasSystemFeature("amazon.hardware.fire_tv") ||
            (Build.MANUFACTURER.equals("Amazon", ignoreCase = true) &&
                (Build.MODEL.contains("AFT", ignoreCase = true) ||
                    Build.MODEL.contains("Fire TV", ignoreCase = true)))
        val largeNonTouchDisplay =
            !features.hasSystemFeature("android.hardware.touchscreen") &&
                resources.configuration.screenWidthDp >= 720
        return televisionUiMode || leanback || televisionHardware || fireTv || largeNonTouchDisplay
    }

    private fun loadCachedChannels() {
        cloudChannels.clear()
        if (!PlayerCloudSync.hasValidLease(this)) {
            loadLocalPlaylist()
            return
        }
        localMode = false
        val config = PlayerCloudSync.cachedConfig(this)
        cloudCategories.clear()
        val categoryArray = config?.optJSONArray("categories") ?: JSONArray()
        for (i in 0 until categoryArray.length()) categoryArray.optString(i).trim().takeIf { it.isNotBlank() }?.let { cloudCategories.add(it) }
        favoriteUrls.clear()
        favoriteUrls.addAll(playerPrefs.getStringSet("favorite_urls", emptySet()).orEmpty())
        selectedSourceByGroup.clear()
        runCatching { JSONObject(playerPrefs.getString("selected_source_by_group", "{}") ?: "{}") }
            .getOrNull()?.let { saved -> saved.keys().forEach { key -> saved.optString(key).takeIf { it.isNotBlank() }?.let { selectedSourceByGroup[key] = it } } }
        val array = config?.optJSONArray("channels") ?: JSONArray()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val name = item.optString("name").trim()
            val url = item.optString("url").trim()
            if (name.isNotBlank() && (url.startsWith("http://", true) || url.startsWith("https://", true))) {
                cloudChannels.add(CloudChannel(name, url, item.optString("logo"), item.optString("category").ifBlank { "Non classificati" }, item.optString("tvgId"), item.optString("tvgName")))
            }
        }
        renderCloudChannels()
        refreshEpgIfNeeded()
    }

    private fun loadLocalPlaylist() {
        val saved = runCatching { LocalPlaylistStore.load(this) }.getOrElse { error ->
            cloudStatusView.text = "Archivio locale non leggibile: ${error.message ?: "errore"}"
            emptyList()
        }
        cloudChannels.clear()
        cloudChannels.addAll(saved)
        localMode = saved.isNotEmpty()
        cloudCategories.clear()
        cloudCategories.addAll(saved.map { it.category.trim() }
            .filter { it.isNotBlank() && !it.equals("Non classificati", true) }.distinct())
        favoriteUrls.clear()
        favoriteUrls.addAll(playerPrefs.getStringSet("favorite_urls", emptySet()).orEmpty())
        selectedSourceByGroup.clear()
        runCatching { JSONObject(playerPrefs.getString("selected_source_by_group", "{}") ?: "{}") }
            .getOrNull()?.let { savedSources ->
                savedSources.keys().forEach { key ->
                    savedSources.optString(key).takeIf { it.isNotBlank() }?.let { selectedSourceByGroup[key] = it }
                }
            }
        renderCloudChannels()
        channelsHeaderView.visibility = if (saved.isNotEmpty()) View.VISIBLE else View.GONE
        diagnosticsView.visibility = if (saved.isNotEmpty()) View.VISIBLE else View.GONE
        importPlaylistButton.text = if (saved.isEmpty()) "＋  IMPORTA PLAYLIST M3U" else "↻  SOSTITUISCI PLAYLIST M3U"
        if (saved.isNotEmpty()) {
            cloudStatusView.text = "Modalità locale gratuita · ${saved.size} canali sul dispositivo. Tocca qui per opzioni cloud facoltative."
            refreshEpgIfNeeded()
        } else {
            cloudStatusView.text = "Modalità locale gratuita · importa una playlist M3U. Nessun account necessario."
        }
    }

    private fun renderCloudChannels() {
        if (!::channelsList.isInitialized) return
        channelsList.removeAllViews()
        updateStickyCategoryHeader()
        if (cloudChannels.isEmpty()) {
            if (isTvDevice()) importPlaylistButton.post { importPlaylistButton.requestFocus() }
            val empty = TextView(this).apply {
                text = "Nessuna playlist caricata. Importa un file M3U dal dispositivo. Non serve un account né una licenza."
                setTextColor(Color.rgb(164, 181, 202))
                textSize = if (isTvDevice()) 16f else 14f
                includeFontPadding = true
                setPadding(dp(16), dp(16), dp(16), dp(16))
            }
            channelsList.addView(empty)
            return
        }
        addGroupedChannelSections(channelsList, isCompact = false)
        renderFullscreenChannelList()
        if (isTvDevice()) {
            channelsList.post { channelsList.getChildAt(0)?.requestFocus() }
        }
    }

    private fun addSectionHeading(target: LinearLayout, title: String, compact: Boolean) {
        target.addView(TextView(this).apply {
            text = title.uppercase()
            setTextColor(Color.rgb(0, 190, 220))
            textSize = if (isTvDevice()) 13f else 11f
            setTypeface(null, android.graphics.Typeface.BOLD)
            letterSpacing = 0.10f
            setPadding(dp(8), dp(if (compact) 16 else 13), dp(8), dp(7))
            isFocusable = false
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun addGroupedChannelSections(target: LinearLayout, isCompact: Boolean) {
        // La schermata iniziale mostra esclusivamente cartelle. I canali compaiono
        // soltanto dopo l'apertura della relativa tipologia.
        val paths = (cloudCategories + cloudChannels.map { it.category })
            .map { it.split(">").map(String::trim).filter(String::isNotBlank).joinToString(" > ") }
            .filter { it.isNotBlank() && !it.equals("Non classificati", true) }
            .distinct()
            .filter { path -> cloudChannels.any { it.category.trim().equals(path, true) } }
            .sortedBy { it.lowercase() }
        val rendered = mutableSetOf<String>()

        val favoriteGroups = groupChannels(cloudChannels).filter { group -> group.sources.any { it.url in favoriteUrls } }
        if (favoriteGroups.isNotEmpty()) {
            val favoriteSources = favoriteGroups.flatMap { it.sources }
            addCategoryFolder(target, "★ Preferiti", "special:favorites", favoriteSources, isCompact)
            rendered.addAll(favoriteSources.map { it.url })
        }

        paths.forEach { path ->
            val items = cloudChannels.filter { it.category.trim().equals(path, true) && it.url !in rendered }
            if (items.isNotEmpty()) {
                addCategoryFolder(target, path, "category:$path", items, isCompact)
                rendered.addAll(items.map { it.url })
            }
        }

        val uncategorized = cloudChannels.filter { it.url !in rendered }
        if (uncategorized.isNotEmpty()) {
            addCategoryFolder(target, "Da classificare", "special:uncategorized", uncategorized, isCompact)
        }
    }

    private fun addCategoryFolder(
        target: LinearLayout,
        title: String,
        key: String,
        items: List<CloudChannel>,
        isCompact: Boolean
    ) {
        val expanded = key in expandedCategoryPaths
        val folder = TextView(this).apply {
            text = (if (expanded) "▾  " else "▸  ") + title.uppercase() + "    ·  " + items.size
            setTextColor(Color.rgb(226, 241, 252))
            textSize = if (isTvDevice()) 14f else 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            letterSpacing = 0.035f
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(if (isTvDevice()) 66 else 54)
            setPadding(dp(16), dp(10), dp(14), dp(10))
            isFocusable = true
            isFocusableInTouchMode = !isTvDevice()
            isClickable = true
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(if (expanded) Color.rgb(17, 54, 72) else Color.rgb(15, 32, 51))
                cornerRadius = dp(13).toFloat()
                setStroke(dp(if (hasFocus()) 2 else 1), if (hasFocus()) Color.rgb(0, 212, 255) else Color.rgb(43, 76, 103))
            }
            contentDescription = "${if (expanded) "Chiudi" else "Apri"} cartella $title, ${items.size} canali"
            setOnFocusChangeListener { view, focused ->
                view.background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(if (expanded) Color.rgb(17, 54, 72) else Color.rgb(15, 32, 51))
                    cornerRadius = dp(13).toFloat()
                    setStroke(dp(if (focused) 3 else 1), if (focused) Color.rgb(0, 212, 255) else Color.rgb(43, 76, 103))
                }
            }
            setOnClickListener {
                if (key in expandedCategoryPaths) {
                    expandedCategoryPaths.remove(key)
                } else {
                    // Una sola cartella aperta alla volta: il titolo fisso identifica sempre
                    // la lista di canali che si sta scorrendo.
                    expandedCategoryPaths.clear()
                    expandedCategoryPaths.add(key)
                }
                updateStickyCategoryHeader()
                renderCloudChannels()
                if (isTvDevice()) {
                    val container = if (isCompact) fullscreenChannelList else channelsList
                    container.post { container.findViewWithTag<View>("folder:$key")?.requestFocus() }
                }
            }
            tag = "folder:$key"
        }
        target.addView(folder, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(if (isCompact) 5 else 7)
            bottomMargin = dp(4)
        })
        if (expanded) {
            groupChannels(items).forEach { group -> addChannelItem(target, group, isCompact) }
        }
    }

    private fun updateStickyCategoryHeader() {
        val activeKey = expandedCategoryPaths.lastOrNull()
        val (title, count) = when {
            activeKey == null -> "I TUOI CANALI" to cloudChannels.size
            activeKey == "special:favorites" -> "★ PREFERITI" to cloudChannels.count { it.url in favoriteUrls }
            activeKey == "special:uncategorized" -> {
                val categorized = cloudChannels.count {
                    it.url !in favoriteUrls && it.category.isNotBlank() && !it.category.equals("Non classificati", true)
                }
                "DA CLASSIFICARE" to (cloudChannels.size - favoriteUrls.count { url -> cloudChannels.any { it.url == url } } - categorized).coerceAtLeast(0)
            }
            activeKey.startsWith("category:") -> {
                val category = activeKey.removePrefix("category:")
                category.uppercase() to cloudChannels.count { it.category.trim().equals(category, true) }
            }
            else -> "I TUOI CANALI" to cloudChannels.size
        }
        val stickyLabel = "$title    ·  $count"
        if (::channelsHeaderView.isInitialized) channelsHeaderView.text = stickyLabel
        if (::fullscreenDrawerHeader.isInitialized) {
            fullscreenDrawerHeader.text = "  $stickyLabel  ·  SELEZIONA"
            fullscreenDrawerHeader.contentDescription = "Elenco $title, $count canali. Premi per chiudere"
        }
    }

    private fun addChannelItem(target: LinearLayout, group: ChannelSourceGroup, isCompact: Boolean) {
        val channel = preferredSource(group)
        target.addView(
            buildChannelRow(channel, isCompact, group.sources.size, group.sources.map { it.url }) {
                if (group.sources.size > 1) showSourcePicker(group) else onCloudChannelClicked(channel)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = if (isTvDevice()) dp(8) else dp(6)
            }
        )
    }

    /**
     * Raggruppa solo canali chiaramente riconducibili allo stesso servizio.
     * Categoria e tipo restano parte della chiave; LIVE e VOD non vengono mai
     * accorpati. tvg-id prevale sul nome, mentre il nome viene normalizzato
     * rimuovendo esclusivamente etichette tecniche di qualità.
     */
    private fun groupChannels(items: List<CloudChannel>): List<ChannelSourceGroup> =
        ChannelSourceGrouper.group(items)

    private fun preferredSource(group: ChannelSourceGroup): CloudChannel {
        val savedUrl = selectedSourceByGroup[group.key]
        group.sources.firstOrNull { it.url == savedUrl }?.let { return it }
        return group.sources.maxWithOrNull(
            compareBy<CloudChannel> { sourceQualityScore(it) }
                .thenBy { if (it.tvgId.isNotBlank()) 1 else 0 }
                .thenBy { if (it.logo.isNotBlank()) 1 else 0 }
                .thenByDescending { -group.sources.indexOf(it) }
        ) ?: group.sources.first()
    }

    private fun sourceQualityScore(channel: CloudChannel): Int {
        val value = (channel.name + " " + channel.url).lowercase(java.util.Locale.ROOT)
        return when {
            Regex("\\b(4k|uhd|2160p)\\b").containsMatchIn(value) -> 4
            Regex("\\b(fhd|full\\s*hd|1080p)\\b").containsMatchIn(value) -> 3
            Regex("\\bhd\\b|720p").containsMatchIn(value) -> 2
            Regex("\\bsd\\b|576p|480p").containsMatchIn(value) -> 1
            else -> 0
        }
    }

    private fun sourceLabel(channel: CloudChannel, index: Int): String {
        val value = (channel.name + " " + channel.url).lowercase(java.util.Locale.ROOT)
        val quality = when {
            Regex("\\b(4k|uhd|2160p)\\b").containsMatchIn(value) -> "4K / UHD"
            Regex("\\b(fhd|full\\s*hd|1080p)\\b").containsMatchIn(value) -> "Full HD"
            Regex("\\bhd\\b|720p").containsMatchIn(value) -> "HD"
            Regex("\\bsd\\b|576p|480p").containsMatchIn(value) -> "SD"
            else -> "Qualità n/d"
        }
        val originalName = channel.name.trim().ifBlank { "Sorgente " + (index + 1) }
        val host = runCatching { Uri.parse(channel.url).host }.getOrNull().orEmpty()
        return listOf(originalName, quality, host).filter { it.isNotBlank() }.joinToString(" · ")
    }
    private fun showSourcePicker(group: ChannelSourceGroup) {
        val current = preferredSource(group)
        val labels = group.sources.mapIndexed { index, source ->
            (if (source.url == current.url) "✓  " else "") + sourceLabel(source, index)
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(group.name + " · " + group.sources.size + " sorgenti")
            .setSingleChoiceItems(labels, group.sources.indexOfFirst { it.url == current.url }) { dialog, which ->
                val chosen = group.sources.getOrNull(which) ?: return@setSingleChoiceItems
                selectedSourceByGroup[group.key] = chosen.url
                val saved = JSONObject()
                selectedSourceByGroup.forEach { (key, url) -> saved.put(key, url) }
                playerPrefs.edit().putString("selected_source_by_group", saved.toString()).apply()
                dialog.dismiss()
                renderCloudChannels()
                onCloudChannelClicked(chosen)
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun toggleFavorite(channel: CloudChannel) {
        val group = groupChannels(cloudChannels).firstOrNull { candidate -> candidate.sources.any { it.url == channel.url } }
        val urls = group?.sources?.map { it.url }.orEmpty().ifEmpty { listOf(channel.url) }
        val remove = urls.any { it in favoriteUrls }
        if (remove) favoriteUrls.removeAll(urls.toSet()) else favoriteUrls.addAll(urls)
        playerPrefs.edit().putStringSet("favorite_urls", favoriteUrls.toSet()).apply()
        renderCloudChannels()
        if (isTvDevice()) {
            val container = if (isChannelDrawerOpen) fullscreenChannelList else channelsList
            container.post { container.findViewWithTag<View>("favorite:${channel.url}")?.requestFocus() }
        }
    }

    /**
     * Riga canale condivisa tra schermata principale e drawer TV.
     * Il placeholder usa l'iniziale del canale: nessuna richiesta di rete aggiuntiva
     * e nessun logo remoto può rallentare lo scorrimento con il telecomando.
     */
    private fun buildChannelRow(
        channel: CloudChannel,
        isCompact: Boolean,
        sourceCount: Int = 1,
        sourceUrls: List<String> = listOf(channel.url),
        onClick: () -> Unit
    ): View {
        val selected = selectedChannelForRecovery?.url == channel.url
        val row = LinearLayout(this).apply {
            tag = channel.url
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isFocusableInTouchMode = !isTvDevice()
            isClickable = true
            minimumHeight = dp(if (isTvDevice()) 82 else 62)
            setPadding(dp(if (isCompact) 14 else 16), dp(8), dp(14), dp(8))
            background = channelFocusBackground(false, selected)
            clipToPadding = false
        }
        val accent = View(this).apply {
            setBackgroundColor(if (selected) Color.rgb(0, 212, 255) else Color.TRANSPARENT)
        }
        row.addView(accent, LinearLayout.LayoutParams(dp(4), ViewGroup.LayoutParams.MATCH_PARENT).apply {
            rightMargin = dp(12)
        })
        // Lista essenziale: niente logo/iniziale e nessuna etichetta LIVE.
        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val name = TextView(this).apply {
            text = channel.name
            setTextColor(Color.rgb(241, 245, 249))
            textSize = if (isTvDevice()) 19f else 15f
            setTypeface(null, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        labels.addView(name)
        if (sourceCount > 1) labels.addView(TextView(this).apply {
            text = "$sourceCount SORGENTI DISPONIBILI · APRI PER SCEGLIERE"
            setTextColor(Color.rgb(0, 190, 220))
            textSize = if (isTvDevice()) 9f else 8f
            setTypeface(null, android.graphics.Typeface.BOLD)
            letterSpacing = 0.06f
            setPadding(0, dp(2), 0, 0)
        })
        val sharedGuide = sourceUrls.firstNotNullOfOrNull { epgGuides[it] }
        sharedGuide?.let { guide ->
            val lines = mutableListOf<String>()
            if (guide.nowTitle.isNotBlank()) {
                val times = if (guide.nowStartMs > 0L && guide.nowStopMs > 0L) EpgGuideManager.formatTime(guide.nowStartMs) + "–" + EpgGuideManager.formatTime(guide.nowStopMs) else ""
                lines.add("ORA" + (if (times.isBlank()) "" else "  " + times) + "  ·  " + guide.nowTitle)
            }
            if (guide.nextTitle.isNotBlank()) lines.add("DOPO  ·  " + guide.nextTitle)
            if (lines.isNotEmpty()) labels.addView(TextView(this).apply {
                text = lines.joinToString("\n")
                setTextColor(Color.rgb(126, 174, 199))
                textSize = if (isTvDevice()) 11f else 10f
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(3), 0, 0)
            })
        }
        row.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // Lo stato di riproduzione è visibile nel video, non nella lista dei canali.
        val favorite = TextView(this).apply {
            tag = "favorite:${channel.url}"
            text = if (channel.url in favoriteUrls) "★" else "☆"
            contentDescription = if (channel.url in favoriteUrls) "Rimuovi dai preferiti" else "Aggiungi ai preferiti"
            gravity = Gravity.CENTER
            textSize = if (isTvDevice()) 25f else 22f
            setTextColor(if (channel.url in favoriteUrls) Color.rgb(255, 204, 64) else Color.rgb(160, 181, 202))
            isFocusable = true
            isClickable = true
            setPadding(dp(12), dp(4), dp(8), dp(4))
            setBackground(channelActionBackground())
            setOnFocusChangeListener { view, focused -> view.background = channelActionBackground(focused) }
            setOnClickListener { toggleFavorite(channel) }
        }
        row.addView(favorite, LinearLayout.LayoutParams(dp(52), dp(52)))
        row.setOnFocusChangeListener { view, focused ->
            view.background = channelFocusBackground(focused, selected)
            view.animate().scaleX(if (focused) 1.025f else 1f).scaleY(if (focused) 1.025f else 1f)
                .translationZ(if (focused) dp(8).toFloat() else 0f)
                .setDuration(150L).start()
            accent.setBackgroundColor(if (focused || selected) Color.rgb(0, 212, 255) else Color.TRANSPARENT)
        }
        row.setOnClickListener { onClick() }
        return row
    }

    private fun primaryActionBackground(focused: Boolean): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.rgb(255, 204, 64))
            cornerRadius = dp(10).toFloat()
            if (focused) setStroke(dp(4), Color.WHITE) else setStroke(dp(1), Color.rgb(255, 226, 130))
        }

    private fun channelActionBackground(focused: Boolean = false): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(if (focused) Color.rgb(18, 79, 105) else Color.rgb(20, 37, 61))
            cornerRadius = dp(16).toFloat()
            if (focused) setStroke(dp(3), Color.rgb(69, 220, 255)) else setStroke(dp(1), Color.rgb(48, 86, 121))
        }

    private fun renderFullscreenChannelList() {
        if (!::fullscreenChannelList.isInitialized) return
        fullscreenChannelList.removeAllViews()
        if (cloudChannels.isEmpty()) {
            fullscreenChannelList.addView(TextView(this).apply {
                text = "Nessun canale disponibile nell'elenco locale. Associa il Player a DAVIX Cloud Sync per ricevere la programmazione."
                setTextColor(Color.rgb(210, 220, 232))
                textSize = 17f
                setPadding(dp(16), dp(20), dp(16), dp(20))
            })
            return
        }
        addGroupedChannelSections(fullscreenChannelList, isCompact = true)
    }

    private fun toggleFullscreenChannelDrawer() {
        if (!isFullscreen) return
        if (isChannelDrawerOpen) closeFullscreenChannelDrawer() else {
            renderFullscreenChannelList()
            retryHandler.removeCallbacks(fullscreenOverlayHideTask)
            isChannelDrawerOpen = true
            fullscreenChannelDrawer.visibility = View.VISIBLE
            fullscreenChannelsButton.visibility = View.GONE
            fullscreenChannelDrawer.post {
                fullscreenChannelList.post {
                    fullscreenChannelList.getChildAt(0)?.requestFocus()
                }
            }
        }
    }

    private fun closeFullscreenChannelDrawer() {
        if (!::fullscreenChannelDrawer.isInitialized) return
        isChannelDrawerOpen = false
        fullscreenChannelDrawer.visibility = View.GONE
        fullscreenChannelsButton.visibility = if (isFullscreen) View.VISIBLE else View.GONE
        if (isFullscreen) showFullscreenOverlay(autoHide = true, requestFocus = true)
    }

    private fun channelFocusBackground(
        focused: Boolean,
        selected: Boolean = false
    ): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(when {
                focused -> Color.rgb(20, 61, 78)
                selected -> Color.rgb(18, 43, 58)
                else -> Color.rgb(21, 27, 35)
            })
            cornerRadius = dp(14).toFloat()
            when {
                focused -> setStroke(dp(3), Color.rgb(0, 212, 255))
                selected -> setStroke(dp(1), Color.rgb(0, 137, 166))
                else -> setStroke(dp(1), Color.rgb(43, 58, 75))
            }
        }

    private fun openLocalPlaylistPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "application/x-mpegURL",
                "application/vnd.apple.mpegurl",
                "audio/x-mpegurl",
                "text/plain",
                "application/octet-stream"
            ))
        }
        startActivityForResult(intent, PICK_LOCAL_PLAYLIST)
    }

    private fun importLocalPlaylist(uri: Uri) {
        importPlaylistButton.isEnabled = false
        cloudStatusView.text = "Importazione playlist locale in corso…"
        cloudExecutor.execute {
            try {
                val input = contentResolver.openInputStream(uri)
                    ?: throw IOException("Impossibile aprire il file selezionato.")
                val text = input.use { stream ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(16 * 1024)
                    var total = 0L
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > 32L * 1024L * 1024L) throw IOException("La playlist supera il limite di 32 MB.")
                        output.write(buffer, 0, count)
                    }
                    String(output.toByteArray(), Charsets.UTF_8)
                }
                val imported = LocalPlaylistParser.parse(text)
                LocalPlaylistStore.save(this, imported)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    localMode = true
                    cloudChannels.clear()
                    cloudChannels.addAll(imported)
                    cloudCategories.clear()
                    cloudCategories.addAll(imported.map { it.category.trim() }
                        .filter { it.isNotBlank() && !it.equals("Non classificati", true) }.distinct())
                    favoriteUrls.clear()
                    favoriteUrls.addAll(playerPrefs.getStringSet("favorite_urls", emptySet()).orEmpty())
                    selectedSourceByGroup.clear()
                    renderCloudChannels()
                    channelsHeaderView.visibility = View.VISIBLE
                    diagnosticsView.visibility = View.VISIBLE
                    importPlaylistButton.text = "↻  SOSTITUISCI PLAYLIST M3U"
                    cloudStatusView.text = "Playlist locale caricata · ${imported.size} canali · nessun account richiesto."
                    importPlaylistButton.isEnabled = true
                    refreshEpgIfNeeded(force = true)
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        cloudStatusView.text = "Importazione non riuscita: ${error.message ?: "file non valido"}"
                    }
                    importPlaylistButton.isEnabled = true
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_LOCAL_PLAYLIST && resultCode == RESULT_OK) {
            data?.data?.let(::importLocalPlaylist)
        }
    }

    private fun beginCloudPairing() {
        val code = activationCodeInput.text.toString().trim().uppercase()
        if (!code.matches(Regex("[A-HJ-NP-Z2-9]{10}"))) {
            activationCodeInput.error = "Inserisci il codice monouso di 10 caratteri"
            return
        }
        cloudStatusView.text = "Attivazione del dispositivo in corso…"
        pairButton.isEnabled = false
        cloudExecutor.execute {
            try {
                PlayerCloudSync.activate(this, code)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    activationCodeInput.visibility = View.GONE
                    endpointInput.visibility = View.GONE
                    pairButton.visibility = View.GONE
                    cloudStatusView.text = "Dispositivo attivato. Scaricamento configurazione…"
                    syncCloudAtStartup()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    pairButton.isEnabled = true
                    cloudStatusView.text = e.message ?: "Attivazione non riuscita."
                }
            }
        }
    }

    private fun syncCloudAtStartup() {
        lastAuthorizationCheckAt = System.currentTimeMillis()
        if (!PlayerCloudSync.hasValidLease(this)) {
            PlayerCloudSync.clearAuthorization(this)
            if (!localMode) {
                loadLocalPlaylist()
                if (!localMode) stopPlaybackForAuthorization()
            }
            endpointInput.visibility = View.GONE
            activationCodeInput.visibility = View.GONE
            pairButton.visibility = View.GONE
            channelsHeaderView.visibility = if (localMode) View.VISIBLE else View.GONE
            diagnosticsView.visibility = if (localMode) View.VISIBLE else View.GONE
            cloudStatusView.text = if (localMode) {
                "Modalità locale gratuita · sincronizzazione cloud facoltativa. Tocca qui per inserire un codice."
            } else {
                "Modalità locale gratuita · importa una playlist M3U. Tocca qui solo se vuoi configurare il cloud."
            }
            return
        }
        cloudStatusView.text = "Controllo aggiornamenti DAVIX Cloud Sync…"
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
                    if (e.message?.contains("device_not_authorized") == true) {
                        PlayerCloudSync.clearAuthorization(this)
                        localMode = false
                        loadLocalPlaylist()
                        stopPlaybackForAuthorization()
                        activationCodeInput.visibility = View.GONE
                        cloudStatusView.text = if (localMode) {
                            "Cloud non disponibile: continuo con la playlist locale gratuita."
                        } else {
                            "Cloud non disponibile: importa una playlist locale gratuita."
                        }
                    } else {
                        loadCachedChannels()
                        cloudStatusView.text = if (cloudChannels.isEmpty()) {
                            "Nessuna configurazione disponibile offline."
                        } else {
                            "Offline: uso la lista autorizzata fino alla scadenza della licenza."
                        }
                    }
                }
            }
        }
    }

    private fun refreshEpgIfNeeded(force: Boolean = false) {
        if (cloudChannels.isEmpty() || epgRefreshInProgress) return
        epgGuides.clear()
        epgGuides.putAll(EpgGuideManager.readCached(this, cloudChannels.map { EpgChannelInput(it.name, it.url, it.tvgId, it.tvgName) }))
        val cachedNowCount = epgGuides.values.count { it.nowTitle.isNotBlank() }
        if (cachedNowCount > 0) {
            epgStatusView.text = "EPG disponibile · programmi in onda su $cachedNowCount canali"
            renderCloudChannels()
        } else {
            epgStatusView.text = "EPG: nessun programma in cache · controllo delle fonti…"
        }
        val snapshot = cloudChannels.map { EpgChannelInput(it.name, it.url, it.tvgId, it.tvgName) }
        val customEpgSource = PlayerCloudSync.customEpgSource(this)
        if (!force && !EpgGuideManager.shouldRefresh(this, snapshot, customEpgSource)) {
            epgStatusView.text = if (epgGuides.isNotEmpty()) {
                "EPG aggiornato · $cachedNowCount programmi in onda disponibili"
            } else {
                "EPG non disponibile per questa lista · prossimo controllo automatico tra qualche ora"
            }
            return
        }
        epgRefreshInProgress = true
        epgStatusView.text = "EPG: recupero palinsesti e associazione automatica dei canali…"
        cloudExecutor.execute {
            val result = runCatching { EpgGuideManager.refresh(this@MainActivity, snapshot, customEpgSource) }
            runOnUiThread {
                epgRefreshInProgress = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess { refreshed ->
                    epgGuides.clear()
                    epgGuides.putAll(refreshed.guides)
                    renderCloudChannels()
                    val nowCount = refreshed.guides.values.count { it.nowTitle.isNotBlank() }
                    epgStatusView.text = if (refreshed.matchedCount > 0) {
                        "EPG aggiornato · ${refreshed.matchedCount}/${snapshot.size} canali associati · $nowCount programmi in onda · ${refreshed.sourceLabel}"
                    } else {
                        "EPG caricato, ma nessun canale della lista è stato associato · ${refreshed.sourceLabel}"
                    }
                }.onFailure { error ->
                    if (epgGuides.isNotEmpty()) renderCloudChannels()
                    epgStatusView.text = "EPG non aggiornato: " + (error.message ?: "fonte non raggiungibile") + ". Riproverò al prossimo aggiornamento."
                }
            }
        }
    }

    private fun recordPlaybackDiagnostic(message: String) {
        val timestamp = android.text.format.DateFormat.format("HH:mm:ss", System.currentTimeMillis())
        synchronized(playbackDiagnosticEvents) {
            playbackDiagnosticEvents.add("$timestamp · $message")
            if (playbackDiagnosticEvents.size > 40) playbackDiagnosticEvents.removeAt(0)
        }
    }

    private fun cancelPendingRecovery(invalidateGeneration: Boolean = true) {
        if (invalidateGeneration) playbackGeneration++
        retryTask?.let { retryHandler.removeCallbacks(it) }
        retryTask = null
    }

    private fun stopPlaybackForAuthorization() {
        cancelPendingRecovery()
        selectedChannelForRecovery = null
        bufferingStartedAt = 0L
        player?.apply { stop(); clearMediaItems() }
        titleView.text = "DAVIX PLAYER"
        statusView.text = "Attivazione richiesta"
        diagnosticsView.visibility = View.GONE
        channelsHeaderView.visibility = View.GONE
    }

    // Un secondo click/OK sullo stesso canale entro 450 ms apre il video a schermo intero.
    // Il primo click avvia subito la riproduzione; il secondo non riavvia il flusso.
    private fun onCloudChannelClicked(channel: CloudChannel) {
        val now = System.currentTimeMillis()
        val isDoubleClick = lastChannelClickName == channel.name &&
            now - lastChannelClickAt in 0..450L
        lastChannelClickName = null
        lastChannelClickAt = 0L
        if (isDoubleClick) {
            if (!isFullscreen) toggleFullscreen()
            return
        }
        lastChannelClickName = channel.name
        lastChannelClickAt = now
        playCloudChannel(channel)
    }

    // I metadati HTTP ricevuti da un deep link esterno valgono solo per quel flusso.
    // Prima di riprodurre un canale Cloud ripristina il profilo neutro, evitando che
    // Origin, Referer o User-Agent del canale precedente vengano riutilizzati.
    private fun resetHlsRequestContext() {
        hlsHttpDataSourceFactory.setUserAgent(defaultHlsUserAgent)
        hlsHttpDataSourceFactory.setDefaultRequestProperties(mapOf("Accept" to "*/*"))
    }

    private fun playCloudChannel(channel: CloudChannel) {
        if (!PlayerCloudSync.hasValidLease(this)) {
            stopPlaybackForAuthorization()
            cloudStatusView.text = "Accesso bloccato: attiva prima questo dispositivo."
            return
        }
        val parsed = runCatching { Uri.parse(channel.url) }.getOrNull()
        if (parsed == null || parsed.scheme !in listOf("http", "https")) {
            statusView.text = "Indirizzo del flusso non valido."
            return
        }
        cancelPendingRecovery()
        resetHlsRequestContext()
        titleView.text = channel.name
        statusView.text = "Connessione al flusso…"
        selectedChannelForRecovery = channel
        terminalPlaybackFailure = false
        recordPlaybackDiagnostic("Selezionato canale: ${channel.name}")
        bufferingStartedAt = System.currentTimeMillis()
        lastPlaybackProgressAt = bufferingStartedAt
        lastObservedPositionMs = C.TIME_UNSET
        retryCount = 0
        lastPlayerError = "nessuno"
        lastHttpStatusCode = null
        lastServerRetryAfterMs = 0L
        lastHlsLoad = "in attesa del primo segmento"
        rebufferCount = 0
        lastReportedPlaybackState = Player.STATE_IDLE
        renderCloudChannels()
        playerContainer.visibility = View.VISIBLE
        player?.apply {
            stop()
            clearMediaItems()
            setMediaItem(createLiveMediaItem(parsed))
            prepare()
            playWhenReady = true
        }
        if (!isFullscreen) toggleFullscreen()
        if (isTvDevice()) playerView.post { playerView.requestFocus() }
        if (isFullscreen) showFullscreenOverlay(autoHide = true, requestFocus = false)
    }

    private fun isVisibleInHierarchy(view: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current.visibility != View.VISIBLE) return false
            current = current.parent as? View
        }
        return true
    }

    private fun showFullscreenOverlay(autoHide: Boolean = true, requestFocus: Boolean = false) {
        if (!isFullscreen || isChannelDrawerOpen) return
        fullscreenOverlayVisible = true
        fullscreenButton.visibility = View.VISIBLE
        fullscreenChannelsButton.visibility = View.VISIBLE
        if (requestFocus && isTvDevice()) {
            fullscreenChannelsButton.post {
                if (isFullscreen && !isChannelDrawerOpen && fullscreenChannelsButton.visibility == View.VISIBLE) {
                    fullscreenChannelsButton.requestFocus()
                }
            }
        }
        retryHandler.removeCallbacks(fullscreenOverlayHideTask)
        if (autoHide) retryHandler.postDelayed(fullscreenOverlayHideTask, 5_000L)
    }

    private fun hideFullscreenOverlay() {
        if (!isFullscreen || isChannelDrawerOpen) return
        fullscreenOverlayVisible = false
        val hadFocus = fullscreenButton.hasFocus() || fullscreenChannelsButton.hasFocus()
        fullscreenButton.visibility = View.GONE
        fullscreenChannelsButton.visibility = View.GONE
        if (hadFocus && isTvDevice()) playerView.requestFocus()
    }

    private fun toggleFullscreen() {
        val enteringFullscreen = !isFullscreen
        if (enteringFullscreen && isTvDevice()) focusBeforeFullscreen = currentFocus
        isFullscreen = enteringFullscreen
        val decor = window.decorView
        val playerParams = playerContainer.layoutParams as LinearLayout.LayoutParams
        if (isFullscreen) {
            // Nasconde tutta l'interfaccia attorno al video e assegna al player
            // l'intera superficie disponibile della finestra.
            if (isTvDevice()) { controlsPanel.visibility = View.GONE; topBar.visibility = View.GONE }
            titleView.visibility = View.GONE
            statusView.visibility = View.GONE
            diagnosticsView.visibility = View.GONE
            endpointInput.visibility = View.GONE
            pairButton.visibility = View.GONE
            cloudStatusView.visibility = View.GONE
            epgStatusView.visibility = View.GONE
            updatesButton.visibility = View.GONE
            importPlaylistButton.visibility = View.GONE
            channelScroll.visibility = View.GONE
            if (::channelBrowser.isInitialized) channelBrowser.visibility = View.GONE
            cloudHeaderView.visibility = View.GONE
            channelsHeaderView.visibility = View.GONE
            if (isTvDevice()) playerParams.width = ViewGroup.LayoutParams.MATCH_PARENT
            playerParams.height = ViewGroup.LayoutParams.MATCH_PARENT
            playerParams.weight = 0f
            playerContainer.layoutParams = playerParams
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
            isChannelDrawerOpen = false
            fullscreenChannelDrawer.visibility = View.GONE
            showFullscreenOverlay(autoHide = true, requestFocus = isTvDevice())
        } else {
            retryHandler.removeCallbacks(fullscreenOverlayHideTask)
            fullscreenOverlayVisible = false
            isChannelDrawerOpen = false
            fullscreenChannelDrawer.visibility = View.GONE
            fullscreenChannelsButton.visibility = View.GONE
            decor.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            if (isTvDevice()) { controlsPanel.visibility = View.VISIBLE; topBar.visibility = View.VISIBLE }
            titleView.visibility = View.VISIBLE
            statusView.visibility = View.VISIBLE
            diagnosticsView.visibility = if (PlayerCloudSync.hasValidLease(this)) View.VISIBLE else View.GONE
            // Ripristina i controlli Cloud solo se il Player non è già associato.
            if (!PlayerCloudSync.hasToken(this)) {
                endpointInput.visibility = View.GONE
                pairButton.visibility = View.GONE
            }
            cloudStatusView.visibility = View.VISIBLE
            epgStatusView.visibility = View.VISIBLE
            updatesButton.visibility = View.VISIBLE
            importPlaylistButton.visibility = View.VISIBLE
            channelScroll.visibility = View.VISIBLE
            if (::channelBrowser.isInitialized) channelBrowser.visibility = View.VISIBLE
            cloudHeaderView.visibility = View.GONE
            channelsHeaderView.visibility = if (PlayerCloudSync.hasValidLease(this)) View.VISIBLE else View.GONE
            // Uscire dal fullscreen non deve interrompere il flusso: il video
            // continua nella finestra di anteprima.
            playerContainer.visibility = if (isTvDevice() && selectedChannelForRecovery != null) View.VISIBLE else View.GONE
            if (isTvDevice()) {
                playerParams.width = 0
                playerParams.height = ViewGroup.LayoutParams.MATCH_PARENT
                playerParams.weight = 1.05f
            } else {
                playerParams.height = 0
                playerParams.weight = 1f
            }
            playerContainer.layoutParams = playerParams
            fullscreenButton.text = "⛶"
            fullscreenButton.contentDescription = "Attiva schermo intero"
            fullscreenButton.visibility = View.VISIBLE
            if (isTvDevice()) {
                val restore = focusBeforeFullscreen
                focusBeforeFullscreen = null
                val target = restore?.takeIf { it.isFocusable && isVisibleInHierarchy(it) } ?: playerView
                target.post { target.requestFocus() }
            }
        }
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (isTvDevice() && event.action == android.view.KeyEvent.ACTION_DOWN) {
            if (!isFullscreen && event.keyCode in setOf(
                    android.view.KeyEvent.KEYCODE_DPAD_UP,
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN,
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT,
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
                    android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                    android.view.KeyEvent.KEYCODE_ENTER,
                    android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                    android.view.KeyEvent.KEYCODE_MEDIA_PLAY,
                    android.view.KeyEvent.KEYCODE_MEDIA_PAUSE
                )) {
                playerView.showController()
            }
            when (event.keyCode) {
                android.view.KeyEvent.KEYCODE_MENU,
                android.view.KeyEvent.KEYCODE_GUIDE -> {
                    if (isFullscreen) {
                        toggleFullscreenChannelDrawer()
                    } else {
                        playerView.showController()
                    }
                    return true
                }
                android.view.KeyEvent.KEYCODE_BACK,
                android.view.KeyEvent.KEYCODE_ESCAPE -> {
                    if (isChannelDrawerOpen) {
                        closeFullscreenChannelDrawer()
                        return true
                    }
                    if (isFullscreen) {
                        toggleFullscreen()
                        return true
                    }
                }
            }

            if (isFullscreen && !isChannelDrawerOpen) {
                val overlayWasVisible = fullscreenOverlayVisible
                showFullscreenOverlay(autoHide = true, requestFocus = !overlayWasVisible)
                when (event.keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                    android.view.KeyEvent.KEYCODE_ENTER -> {
                        if (currentFocus === playerView) {
                            player?.let { if (it.isPlaying) it.pause() else it.play() }
                            return true
                        }
                    }
                    android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                        player?.let { if (it.isPlaying) it.pause() else it.play() }
                        return true
                    }
                    android.view.KeyEvent.KEYCODE_MEDIA_PLAY -> { player?.play(); return true }
                    android.view.KeyEvent.KEYCODE_MEDIA_PAUSE -> { player?.pause(); return true }
                }
                // Il primo tasto risveglia i comandi: non deve anche attivare
                // accidentalmente il pulsante che riceve il focus.
                if (!overlayWasVisible) return true
            } else {
                when (event.keyCode) {
                    android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                        player?.let { if (it.isPlaying) it.pause() else it.play() }
                        return true
                    }
                    android.view.KeyEvent.KEYCODE_MEDIA_PLAY -> { player?.play(); return true }
                    android.view.KeyEvent.KEYCODE_MEDIA_PAUSE -> { player?.pause(); return true }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (isChannelDrawerOpen) {
            closeFullscreenChannelDrawer()
        } else {
            if (isFullscreen) toggleFullscreen()
            else super.onBackPressed()
        }
    }

    private fun createLiveMediaItem(uri: Uri): MediaItem {
        val builder = MediaItem.Builder().setUri(uri)
        val path = uri.path.orEmpty().lowercase(java.util.Locale.ROOT)
        when {
            path.endsWith(".m3u8") -> builder.setMimeType(MimeTypes.APPLICATION_M3U8)
            path.endsWith(".mpd") -> builder.setMimeType(MimeTypes.APPLICATION_MPD)
            path.endsWith(".ism") || path.endsWith(".isml") -> builder.setMimeType(MimeTypes.APPLICATION_SS)
            // Per URL senza estensione non imporre HLS: Media3 usa il rilevamento automatico.
        }
        return builder.setLiveConfiguration(
          MediaItem.LiveConfiguration.Builder()
              .setTargetOffsetMs(120_000)
              .setMinOffsetMs(75_000)
              .setMaxOffsetMs(300_000)
              .setMinPlaybackSpeed(0.96f)
              .setMaxPlaybackSpeed(1.03f)
              .build()
      )
            .build()
    }

    /**
     * Controllo di salute indipendente dagli eventi ExoPlayer: intercetta anche
     * i casi in cui la riproduzione resta apparentemente pronta ma il tempo non avanza.
     */
    private fun isInternetAvailable(): Boolean {
        val connectivity = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun monitorPlaybackHealth(current: ExoPlayer, now: Long, bufferedAheadMs: Long) {
        if (!current.playWhenReady || current.mediaItemCount == 0 ||
            selectedChannelForRecovery == null || isFinishing || isDestroyed || terminalPlaybackFailure) {
            bufferingStartedAt = 0L
            lastPlaybackProgressAt = now
            lastObservedPositionMs = current.currentPosition
            return
        }

        val networkAvailable = isInternetAvailable()
        if (!networkAvailable) {
            lastKnownNetworkAvailable = false
            if (retryTask != null) cancelPendingRecovery()
            bufferingStartedAt = 0L
            lastPlaybackProgressAt = now
            lastObservedPositionMs = current.currentPosition
            statusView.text = "Connessione Internet assente · attendo il ripristino…"
            recordPlaybackDiagnostic("Connettività assente: recupero sospeso")
            return
        }
        val networkJustReturned = lastKnownNetworkAvailable == false
        lastKnownNetworkAvailable = true
        if (networkJustReturned) {
            recordPlaybackDiagnostic("Connettività ripristinata: avvio recupero")
            bufferingStartedAt = now
            lastPlaybackProgressAt = now
            lastObservedPositionMs = current.currentPosition
            schedulePlaybackRecovery("Connessione Internet ripristinata", immediate = true)
        }

        val position = current.currentPosition
        if (lastObservedPositionMs == C.TIME_UNSET ||
            position > lastObservedPositionMs + 250L ||
            position < lastObservedPositionMs - 250L) {
            lastObservedPositionMs = position
            lastPlaybackProgressAt = now
            if (retryTask != null) cancelPendingRecovery()
        }

        when (current.playbackState) {
            Player.STATE_BUFFERING -> {
                if (bufferingStartedAt == 0L) bufferingStartedAt = now
                // Lascia terminare i timeout HTTP e i retry interni di Media3 prima
                // di riavviare la playlist dall'ultimo live edge disponibile.
                if (now - bufferingStartedAt >= 70_000L && bufferedAheadMs < 1_500L) {
                    schedulePlaybackRecovery("Buffer esaurito: ripristino automatico")
                }
            }
            Player.STATE_READY -> {
                bufferingStartedAt = 0L
                if (now - lastPlaybackProgressAt >= 15_000L) {
                    schedulePlaybackRecovery("Riproduzione senza avanzamento: riaggancio automatico")
                }
            }
            Player.STATE_ENDED -> {
                // Un live che termina realmente non va riavviato all'infinito.
                statusView.text = "Il canale ha terminato la trasmissione."
                cancelPendingRecovery()
            }
            Player.STATE_IDLE -> {
                if (now - lastPlaybackProgressAt >= 5_000L) {
                    schedulePlaybackRecovery("Player inattivo: riconnessione automatica")
                }
            }
        }
    }

    /**
     * Retry persistente con backoff limitato: non abbandona il canale dopo N errori.
     * Il nuovo prepare parte dal live edge, evitando segmenti HLS ormai scaduti.
     */
    private fun schedulePlaybackRecovery(reason: String, immediate: Boolean = false) {
        if (retryTask != null || !PlayerCloudSync.hasValidLease(this) || !isInternetAvailable()) return
        val channel = selectedChannelForRecovery ?: return
        val current = player ?: return
        if (!current.playWhenReady || isFinishing || isDestroyed) return
        val scheduledGeneration = playbackGeneration

        val delays = longArrayOf(2_000L, 4_000L, 8_000L, 15_000L, 30_000L, 45_000L, 60_000L)
        val localDelay = delays[retryCount.coerceAtMost(delays.lastIndex)]
        val delay = if (immediate) 1_000L else maxOf(localDelay, lastServerRetryAfterMs).coerceAtMost(300_000L)
        lastServerRetryAfterMs = 0L
        if (!immediate) retryCount = (retryCount + 1).coerceAtMost(10_000)
        statusView.text = "$reason · tentativo automatico tra ${delay / 1000}s"
        recordPlaybackDiagnostic("$reason; nuovo tentativo tra ${delay / 1000}s")

        val task = Runnable {
            retryTask = null
            val activePlayer = player ?: return@Runnable
            if (!activePlayer.playWhenReady || !PlayerCloudSync.hasValidLease(this) || !isInternetAvailable() ||
                scheduledGeneration != playbackGeneration || selectedChannelForRecovery?.url != channel.url) return@Runnable

            bufferingStartedAt = System.currentTimeMillis()
            lastPlaybackProgressAt = bufferingStartedAt
            lastObservedPositionMs = C.TIME_UNSET
            statusView.text = "Riconnessione a ${channel.name}…"
            recordPlaybackDiagnostic("Riavvio controllato del flusso: ${channel.name}")
            activePlayer.stop()
            activePlayer.clearMediaItems()
            activePlayer.setMediaItem(createLiveMediaItem(Uri.parse(channel.url)))
            activePlayer.prepare()
            activePlayer.playWhenReady = true
        }
        retryTask = task
        retryHandler.postDelayed(task, delay)
    }

    private fun describeHttpStatus(code: Int): String = when (code) {
        400 -> "richiesta non valida"
        401 -> "autenticazione richiesta"
        403 -> "accesso negato dal server"
        404 -> "flusso o segmento non trovato"
        408 -> "timeout della richiesta"
        410 -> "flusso non più disponibile"
        425 -> "richiesta temporaneamente non accettata"
        429 -> "troppe richieste · attesa necessaria"
        in 500..599 -> "server temporaneamente in errore"
        else -> "risposta HTTP non valida"
    }

    // Evita retry continui su credenziali errate o richieste malformate.
    // 403/406 restano recuperabili: CDN e protezioni anti-hotlink possono essere transitorie.
    private fun isRetryableHttpStatus(code: Int): Boolean =
        code == 403 || code == 406 || code == 408 || code == 425 || code == 429 || code in 500..599

    private fun retryAfterDelayMs(response: HttpDataSource.InvalidResponseCodeException): Long {
        val seconds = response.headerFields.entries
            .firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }
            ?.value?.firstOrNull()?.trim()?.toLongOrNull()
            ?: return 0L
        return (seconds * 1_000L).coerceIn(0L, 300_000L)
    }

    private fun isRetryablePlaybackError(error: PlaybackException): Boolean {
        val name = error.errorCodeName
        return name.startsWith("ERROR_CODE_IO_") ||
            name.contains("TIMEOUT") ||
            name.contains("BEHIND_LIVE_WINDOW")
    }

    /**
     * Scarta gli eventi Analytics appartenenti a una timeline/canale precedente.
     * Media3 può consegnare callback di caricamento in ritardo dopo un cambio canale.
     */
    private fun isCurrentAnalyticsEvent(
        eventTime: AnalyticsListener.EventTime,
        current: ExoPlayer
    ): Boolean = current.mediaItemCount > 0 &&
        eventTime.windowIndex == current.currentMediaItemIndex &&
        eventTime.timeline == current.currentTimeline

    private fun initializePlayer() {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                60_000, // min: mantiene il download attivo
                240_000, // max: riserva ampia
                6_000, // avvio
                30_000 // dopo rebuffer
            )
            // Limita la memoria occupata dai segmenti: la durata del buffer non deve
            // crescere senza controllo su TV con memoria ridotta.
            .setTargetBufferBytes(128 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        // Allinea le richieste HLS al profilo HTTP del browser Android:
        // alcuni server rifiutano (403) User-Agent applicativi non riconosciuti.
        // WebSettings fornisce lo User-Agent effettivo del WebView installato,
        // senza inventare una versione di Chrome. Il fallback mantiene la compatibilità.
        defaultHlsUserAgent = runCatching {
            android.webkit.WebSettings.getDefaultUserAgent(this)
        }.getOrDefault("Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 (KHTML, like Gecko) Chrome Safari/537.36")

        hlsHttpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(defaultHlsUserAgent)
            .setDefaultRequestProperties(mapOf("Accept" to "*/*"))
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(45_000)
            .setAllowCrossProtocolRedirects(true)

        val liveSpeedControl = DefaultLivePlaybackSpeedControl.Builder()
            .setFallbackMinPlaybackSpeed(0.97f)
            .setFallbackMaxPlaybackSpeed(1.03f)
            .setTargetLiveOffsetIncrementOnRebufferMs(40_000)
            .build()

        // Come nell'Extractor, lascia che Media3 riconosca il contenuto: forzare
        // ogni URL a HLS produce schermo nero con TS/MP4/DASH o endpoint senza manifest HLS.
        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(hlsHttpDataSourceFactory)
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(8))

        player = ExoPlayer.Builder(this)
            .setRenderersFactory(androidx.media3.exoplayer.DefaultRenderersFactory(this).setEnableDecoderFallback(true))
            .setLoadControl(loadControl)
            .setLivePlaybackSpeedControl(liveSpeedControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .also { exo ->
                playerView.player = exo
                // Misura tempi e dimensioni dei segmenti senza registrare URL o token.
                exo.addAnalyticsListener(object : AnalyticsListener {
                    override fun onLoadCompleted(
                        eventTime: AnalyticsListener.EventTime,
                        loadEventInfo: LoadEventInfo,
                        mediaLoadData: MediaLoadData
                    ) {
                        if (isCurrentAnalyticsEvent(eventTime, exo) && mediaLoadData.dataType == C.DATA_TYPE_MEDIA) {
                            val duration = loadEventInfo.loadDurationMs
                            val bytes = loadEventInfo.bytesLoaded
                            lastHlsLoad = "segmento ${duration}ms · ${bytes / 1024}KB"
                        } else if (isCurrentAnalyticsEvent(eventTime, exo) && mediaLoadData.dataType == C.DATA_TYPE_MANIFEST) {
                            lastHlsLoad = "playlist aggiornata"
                        }
                    }

                    override fun onLoadError(
                        eventTime: AnalyticsListener.EventTime,
                        loadEventInfo: LoadEventInfo,
                        mediaLoadData: MediaLoadData,
                        error: IOException,
                        wasCanceled: Boolean
                    ) {
                        if (!wasCanceled && isCurrentAnalyticsEvent(eventTime, exo)) {
                            val kind = if (mediaLoadData.dataType == C.DATA_TYPE_MANIFEST) "playlist" else "segmento"
                            var cause: Throwable? = error
                            var httpCode: Int? = null
                            while (cause != null) {
                                if (cause is HttpDataSource.InvalidResponseCodeException) {
                                    httpCode = cause.responseCode
                                    break
                                }
                                cause = cause.cause
                            }
                            if (httpCode != null) {
                                recordPlaybackDiagnostic("Errore caricamento $kind HTTP $httpCode")
                                lastHttpStatusCode = httpCode
                                lastHlsLoad = "errore $kind HTTP $httpCode · ${describeHttpStatus(httpCode)}"
                            } else {
                                lastHlsLoad = "errore $kind: ${error.javaClass.simpleName}"
                            }
                        }
                    }
                })
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
                        var cause: Throwable? = error
                        var httpCode: Int? = null
                        while (cause != null) {
                            if (cause is HttpDataSource.InvalidResponseCodeException) {
                                httpCode = cause.responseCode
                                lastServerRetryAfterMs = retryAfterDelayMs(cause)
                                break
                            }
                            cause = cause.cause
                        }

                        val retryable = if (httpCode != null) {
                            lastHttpStatusCode = httpCode
                            lastPlayerError = "HTTP $httpCode · ${describeHttpStatus(httpCode)}"
                            isRetryableHttpStatus(httpCode)
                        } else {
                            lastPlayerError = error.errorCodeName
                            // Recuperiamo gli errori di rete e la perdita della live window.
                            // Errori di parsing/decoder/configurazione non si risolvono riavviando
                            // all'infinito lo stesso flusso.
                            isRetryablePlaybackError(error)
                        }

                        recordPlaybackDiagnostic("Errore player ${error.errorCodeName}${httpCode?.let { " · HTTP $it" } ?: ""}")
                        if (retryable) {
                            terminalPlaybackFailure = false
                            bufferingStartedAt = System.currentTimeMillis()
                            val reason = if (httpCode != null) {
                                "HTTP $httpCode · ${describeHttpStatus(httpCode)}: nuovo tentativo"
                            } else {
                                "Errore ${error.errorCodeName}: ripristino automatico"
                            }
                            schedulePlaybackRecovery(reason)
                        } else {
                            terminalPlaybackFailure = true
                            recordPlaybackDiagnostic("Errore non recuperabile: ${httpCode?.let { "HTTP $it" } ?: error.errorCodeName}")
                            val detail = if (httpCode != null) "HTTP $httpCode · ${describeHttpStatus(httpCode)}" else error.errorCodeName
                            statusView.text = "Riproduzione non recuperabile: $detail. Verifica la sorgente o prova un altro canale."
                            cancelPendingRecovery()
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (isPlaying) {
                            recordPlaybackDiagnostic("Riproduzione effettiva avviata")
                            lastPlaybackProgressAt = System.currentTimeMillis()
                            bufferingStartedAt = 0L
                            // Un reset di stabilità appartenente al canale precedente non deve
                            // azzerare il backoff dopo un cambio rapido di canale.
                            val stableGeneration = playbackGeneration
                            retryHandler.postDelayed({
                                if (playbackGeneration == stableGeneration &&
                                    player === exo && exo.isPlaying) {
                                    retryCount = 0
                                }
                            }, 60_000L)
                        } else if (player?.playWhenReady == true && bufferingStartedAt == 0L) {
                            bufferingStartedAt = System.currentTimeMillis()
                        }
                    }
                })
            }
        mediaSession?.release()
        mediaSession = player?.let { MediaSession.Builder(this, it).build() }
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
            statusView.text = "Apri un canale dal DAVIX Player."
            return
        }

        val parsed = runCatching { Uri.parse(streamUrl) }.getOrNull()
        if (parsed == null || parsed.scheme !in listOf("http", "https")) {
            statusView.text = "Indirizzo del flusso non valido."
            return
        }

        // Se il canale viene aperto dal player HTML, riusa il contesto HTTP
        // del browser: il solo User-Agent del WebView non coincide necessariamente
        // con quello del browser che ha caricato la pagina.
        if (!PlayerCloudSync.hasValidLease(this)) localMode = true
        val requestHeaders = mutableMapOf("Accept" to "*/*")
        val browserUa = uri?.getQueryParameter("ua")
            ?.takeIf { it.isNotBlank() && it.length <= 512 && !it.contains('\r') && !it.contains('\n') }
        val requestOrigin = uri?.getQueryParameter("origin")
            ?.takeIf { value ->
                value == "null" || runCatching {
                    val parsedOrigin = Uri.parse(value)
                    parsedOrigin.scheme in listOf("http", "https") && !parsedOrigin.host.isNullOrBlank() &&
                        (parsedOrigin.path.isNullOrEmpty() || parsedOrigin.path == "/") &&
                        !value.contains('\r') && !value.contains('\n')
                }.getOrDefault(false)
            }
        val requestReferer = uri?.getQueryParameter("referer")
            ?.takeIf { value ->
                runCatching {
                    val parsedReferer = Uri.parse(value)
                    parsedReferer.scheme in listOf("http", "https") && !parsedReferer.host.isNullOrBlank() &&
                        value.length <= 2048 && !value.contains('\r') && !value.contains('\n')
                }.getOrDefault(false)
            }
        if (requestOrigin != null) requestHeaders["Origin"] = requestOrigin
        if (requestReferer != null) requestHeaders["Referer"] = requestReferer
        hlsHttpDataSourceFactory.setUserAgent(browserUa ?: defaultHlsUserAgent)
        hlsHttpDataSourceFactory.setDefaultRequestProperties(requestHeaders)

        titleView.text = channelName
        statusView.text = "Connessione al flusso…"
        // Usa la stessa MediaSourceFactory HLS configurata in initializePlayer().
        // Il MIME esplicito evita ambiguità con flussi HLS senza estensione .m3u8.
        cancelPendingRecovery()
        selectedChannelForRecovery = CloudChannel(channelName, streamUrl)
        terminalPlaybackFailure = false
        lastPlayerError = "nessuno"
        lastHttpStatusCode = null
        lastServerRetryAfterMs = 0L
        lastHlsLoad = "in attesa del primo segmento"
        rebufferCount = 0
        lastReportedPlaybackState = Player.STATE_IDLE
        bufferingStartedAt = System.currentTimeMillis()
        lastPlaybackProgressAt = bufferingStartedAt
        lastObservedPositionMs = C.TIME_UNSET
        retryTask?.let { retryHandler.removeCallbacks(it) }
        retryTask = null
        retryCount = 0
        playerContainer.visibility = View.VISIBLE
        player?.apply {
            stop()
            clearMediaItems()
            setMediaItem(createLiveMediaItem(parsed))
            prepare()
            playWhenReady = true
        }
        if (!isFullscreen) toggleFullscreen()
    }

    override fun onStop() {
        retryHandler.removeCallbacks(diagnosticsTask)
        cancelPendingRecovery()
        resumeAfterBackground = player?.playWhenReady == true && (player?.mediaItemCount ?: 0) > 0
        player?.pause()
        // Il tempo trascorso in background non è buffering: evita riconnessioni
        // immediate e falsi allarmi al ritorno in primo piano.
        bufferingStartedAt = 0L
        lastPlaybackProgressAt = System.currentTimeMillis()
        lastObservedPositionMs = C.TIME_UNSET
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        retryHandler.removeCallbacks(diagnosticsTask)
        retryHandler.post(diagnosticsTask)
        if (resumeAfterBackground && (player?.mediaItemCount ?: 0) > 0 && (PlayerCloudSync.hasValidLease(this) || localMode)) {
            bufferingStartedAt = 0L
            lastPlaybackProgressAt = System.currentTimeMillis()
            lastObservedPositionMs = C.TIME_UNSET
            player?.playWhenReady = true
        } else if (!PlayerCloudSync.hasValidLease(this) && !localMode) {
            stopPlaybackForAuthorization()
        }
        resumeAfterBackground = false
    }

    override fun onDestroy() {
        retryHandler.removeCallbacks(diagnosticsTask)
        cancelPendingRecovery()
        retryHandler.removeCallbacksAndMessages(null)
        cloudHandler.removeCallbacksAndMessages(null)
        cloudExecutor.shutdownNow()
        mediaSession?.release()
        mediaSession = null
        playerView.player = null
        player?.release()
        player = null
        super.onDestroy()
    }
}