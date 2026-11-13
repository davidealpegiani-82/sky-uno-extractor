package it.davidealpegiani.skylite

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.net.Uri
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
    private lateinit var fullscreenChannelsButton: TextView
    private lateinit var fullscreenChannelDrawer: LinearLayout
    private lateinit var fullscreenChannelList: LinearLayout
    private var isChannelDrawerOpen = false
    private var isFullscreen = false
    private var fullscreenOverlayVisible = false
    private var focusBeforeFullscreen: View? = null
    private val fullscreenOverlayHideTask = Runnable { hideFullscreenOverlay() }
    private val retryHandler = Handler(Looper.getMainLooper())
    private val diagnosticsTask = object : Runnable {
        override fun run() {
            val current = player
            if (current == null || !::diagnosticsView.isInitialized || isFinishing || isDestroyed) return
            if (!PlayerCloudSync.hasValidLease(this@MainActivity) && current.mediaItemCount > 0) {
                PlayerCloudSync.clearAuthorization(this@MainActivity)
                stopPlaybackForAuthorization()
                cloudChannels.clear()
                renderCloudChannels()
                cloudStatusView.text = "Licenza offline scaduta. Riconnetti e attiva nuovamente il dispositivo."
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
    private lateinit var endpointInput: EditText
    private lateinit var activationCodeInput: EditText
    private lateinit var pairButton: Button
    private lateinit var cloudStatusView: TextView
    private lateinit var channelsList: LinearLayout
    private lateinit var channelScroll: ScrollView
    private lateinit var playerContainer: FrameLayout
    private lateinit var controlsPanel: LinearLayout
    private lateinit var topBar: LinearLayout
    private lateinit var cloudHeaderView: TextView
    private lateinit var channelsHeaderView: TextView
    private val cloudHandler = Handler(Looper.getMainLooper())
    private val cloudExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val cloudChannels = mutableListOf<CloudChannel>()
    private var lastAuthorizationCheckAt = 0L
    private var lastChannelClickName: String? = null
    private var lastChannelClickAt = 0L
    private lateinit var hlsHttpDataSourceFactory: DefaultHttpDataSource.Factory
    private var defaultHlsUserAgent: String = ""
    private var selectedChannelForRecovery: CloudChannel? = null
    private var bufferingStartedAt = 0L
    private var lastPlaybackProgressAt = 0L
    private var lastObservedPositionMs = C.TIME_UNSET

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
        val tvContent = if (tvMode) LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(5, 10, 22), Color.rgb(9, 18, 34))
            )
        } else root
        if (tvMode) root.addView(tvContent, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val controls = if (tvMode) LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(26), dp(24), dp(26), dp(24))
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.rgb(17, 29, 52), Color.rgb(9, 17, 32))).apply { setStroke(dp(1), Color.rgb(38, 65, 98)) }
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        } else root
        controlsPanel = controls
        if (tvMode) {
            tvContent.addView(controls, LinearLayout.LayoutParams(dp(420), ViewGroup.LayoutParams.MATCH_PARENT))
        }
        titleView = TextView(this).apply {
            text = android.text.SpannableString("DAVIX  PLAYER").apply {
                setSpan(android.text.style.ForegroundColorSpan(Color.rgb(69, 220, 255)), 0, 3, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(android.text.style.RelativeSizeSpan(1.08f), 0, 3, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            setTextColor(Color.WHITE)
            textSize = if (tvMode) 31f else 21f
            setTypeface(null, android.graphics.Typeface.BOLD)
            letterSpacing = 0.10f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(26), dp(12), dp(10))
        }
        statusView = TextView(this).apply {
            text = if (tvMode) "LIVE TELEVISION  •  BIG SCREEN EXPERIENCE" else "Apri un canale dal DAVIX Player."
            setTextColor(Color.rgb(135, 177, 205))
            textSize = if (tvMode) 11f else 13f
            letterSpacing = if (tvMode) 0.16f else 0f
            setPadding(dp(12), 0, dp(12), dp(22))
        }
        playerView = PlayerView(this).apply {
            isFocusable = tvMode
            isFocusableInTouchMode = !tvMode
            // I controlli non devono riapparire a ogni cambio di focus del PlayerView.
            // Si mostrano solo dopo un input esplicito (telecomando/click) e si nascondono
            // da soli dopo il timeout standard.
            setOnClickListener { showController() }
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
            textSize = if (tvMode) 13f else 11f
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.rgb(15, 46, 65), Color.rgb(18, 32, 54))).apply { cornerRadius = dp(14).toFloat(); setStroke(dp(1), Color.rgb(36, 111, 145)) }
        }
        controls.addView(diagnosticsView)
        diagnosticsView.visibility = if (PlayerCloudSync.hasValidLease(this)) View.VISIBLE else View.GONE

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
            text = "Accesso bloccato: inserisci un codice monouso generato da Admin"
            setTextColor(Color.rgb(164, 202, 220))
            textSize = 12f
            setPadding(16, 4, 16, 10)
        }
        controls.addView(cloudStatusView)

        channelsHeaderView = TextView(this).apply {
            text = "I TUOI CANALI"
            setTextColor(Color.rgb(255, 204, 64))
            textSize = if (tvMode) 16f else 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            letterSpacing = 0.08f
            setPadding(dp(12), dp(26), dp(12), dp(14))
            contentDescription = "Elenco canali. Usa le frecce per scegliere e OK per riprodurre."
        }
        controls.addView(channelsHeaderView)
        channelsHeaderView.visibility = if (PlayerCloudSync.hasValidLease(this)) View.VISIBLE else View.GONE

        playerContainer = FrameLayout(this).apply {
            layoutParams = if (tvMode) {
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

        fullscreenChannelDrawer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.rgb(13, 26, 48), Color.rgb(7, 14, 27))).apply { setStroke(dp(1), Color.rgb(47, 102, 139)) }
            elevation = dp(24).toFloat()
            visibility = View.GONE
            isFocusable = true
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            layoutParams = FrameLayout.LayoutParams(dp(420), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START)
        }
        val drawerHeader = TextView(this).apply {
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
        fullscreenChannelDrawer.addView(drawerHeader, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(68)))
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
        if (tvMode) tvContent.addView(playerContainer) else root.addView(playerContainer)
        channelScroll = ScrollView(this).apply {
            isFillViewport = true
            isFocusable = false
            layoutParams = if (tvMode) {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            } else {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220))
            }
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.rgb(8, 17, 32), Color.rgb(5, 11, 22)))
        }
        channelsList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(12))
        }
        channelScroll.addView(channelsList)
        if (tvMode) controls.addView(channelScroll) else root.addView(channelScroll)
        setContentView(root)
        if (PlayerCloudSync.hasValidLease(this)) {
            endpointInput.visibility = View.GONE
            activationCodeInput.visibility = View.GONE
            pairButton.visibility = View.GONE
            cloudStatusView.text = "Dispositivo autorizzato · licenza offline valida."
            channelsHeaderView.visibility = View.VISIBLE
            diagnosticsView.visibility = View.VISIBLE
        }
        if (tvMode && cloudChannels.isEmpty() && !PlayerCloudSync.hasValidLease(this)) activationCodeInput.post { activationCodeInput.requestFocus() }
    }

    private fun isTvDevice(): Boolean =
        packageManager.hasSystemFeature("android.software.leanback") ||
            packageManager.hasSystemFeature("android.hardware.type.television")

    private fun loadCachedChannels() {
        cloudChannels.clear()
        if (!PlayerCloudSync.hasValidLease(this)) {
            renderCloudChannels()
            return
        }
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
            if (isTvDevice() && !PlayerCloudSync.hasValidLease(this)) activationCodeInput.post { activationCodeInput.requestFocus() }
            val empty = TextView(this).apply {
                text = "Nessun canale visibile. Inserisci il codice monouso fornito da Admin per attivare il dispositivo."
                setTextColor(Color.rgb(164, 181, 202))
                textSize = if (isTvDevice()) 16f else 14f
                includeFontPadding = true
                setPadding(dp(16), dp(16), dp(16), dp(16))
            }
            channelsList.addView(empty)
            return
        }
        cloudChannels.forEach { channel ->
            channelsList.addView(
                buildChannelRow(channel, isCompact = false) { onCloudChannelClicked(channel) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = if (isTvDevice()) dp(8) else dp(6)
                }
            )
        }
        renderFullscreenChannelList()
        if (isTvDevice()) {
            channelsList.post { channelsList.getChildAt(0)?.requestFocus() }
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
        onClick: () -> Unit
    ): View {
        val selected = selectedChannelForRecovery?.url == channel.url
        val row = LinearLayout(this).apply {
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
        val logo = TextView(this).apply {
            text = channel.name.trim().firstOrNull()?.uppercase() ?: "▶"
            gravity = Gravity.CENTER
            textSize = if (isTvDevice()) 18f else 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(if (selected) Color.rgb(0, 212, 255) else Color.rgb(205, 225, 238))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(if (selected) Color.rgb(14, 56, 73) else Color.rgb(29, 43, 61))
                setStroke(dp(1), if (selected) Color.rgb(0, 160, 195) else Color.rgb(57, 82, 108))
            }
        }
        row.addView(logo, LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(14) })

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
        val subtitle = TextView(this).apply {
            text = if (selected) "IN RIPRODUZIONE" else "CANALE LIVE"
            setTextColor(if (selected) Color.rgb(0, 190, 220) else Color.rgb(132, 153, 174))
            textSize = 10f
            letterSpacing = 0.10f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, dp(3), 0, 0)
        }
        labels.addView(name)
        labels.addView(subtitle)
        row.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val live = TextView(this).apply {
            text = if (selected) "ON AIR" else "LIVE"
            gravity = Gravity.CENTER
            setTextColor(if (selected) Color.rgb(4, 18, 26) else Color.rgb(135, 205, 221))
            textSize = 10f
            setTypeface(null, android.graphics.Typeface.BOLD)
            letterSpacing = 0.06f
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(if (selected) Color.rgb(0, 212, 255) else Color.rgb(22, 54, 69))
                setStroke(dp(1), if (selected) Color.rgb(0, 212, 255) else Color.rgb(45, 105, 128))
            }
        }
        row.addView(live, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
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
                text = "Nessun canale disponibile nell'elenco locale.\n\nPer visualizzare qui la playlist completa, associa il Player a DAVIX Cloud Sync oppure aggiorna la lista sincronizzata."
                setTextColor(Color.rgb(210, 220, 232))
                textSize = 17f
                setPadding(dp(16), dp(20), dp(16), dp(20))
            })
            return
        }
        cloudChannels.forEach { channel ->
            fullscreenChannelList.addView(
                buildChannelRow(channel, isCompact = true) {
                    closeFullscreenChannelDrawer()
                    playCloudChannel(channel)
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(8)
                }
            )
        }
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
                val firstChannel = fullscreenChannelList.getChildAt(0)
                (firstChannel ?: fullscreenChannelDrawer.getChildAt(0))?.requestFocus()
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
            cloudChannels.clear()
            renderCloudChannels()
            stopPlaybackForAuthorization()
            endpointInput.visibility = View.GONE
            activationCodeInput.visibility = View.VISIBLE
            pairButton.visibility = View.GONE
            diagnosticsView.visibility = View.GONE
            channelsHeaderView.visibility = View.GONE
            pairButton.isEnabled = true
            pairButton.text = "ATTIVA QUESTO DISPOSITIVO"
            cloudStatusView.text = "Accesso bloccato: inserisci un codice monouso generato da Admin"
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
                        cloudChannels.clear()
                        renderCloudChannels()
                        stopPlaybackForAuthorization()
                        endpointInput.visibility = View.GONE
                        activationCodeInput.visibility = View.VISIBLE
                        pairButton.visibility = View.GONE
                        diagnosticsView.visibility = View.GONE
                        channelsHeaderView.visibility = View.GONE
                        pairButton.isEnabled = true
                        pairButton.text = "ATTIVA QUESTO DISPOSITIVO"
                        cloudStatusView.text = "Autorizzazione revocata. Inserisci un nuovo codice generato da Admin."
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
        titleView.text = channel.name
        statusView.text = "Connessione al flusso…"
        selectedChannelForRecovery = channel
        bufferingStartedAt = System.currentTimeMillis()
        lastPlaybackProgressAt = bufferingStartedAt
        lastObservedPositionMs = C.TIME_UNSET
        retryCount = 0
        lastPlayerError = "nessuno"
        rebufferCount = 0
        lastReportedPlaybackState = Player.STATE_IDLE
        player?.apply {
            stop()
            clearMediaItems()
            setMediaItem(createLiveMediaItem(parsed))
            prepare()
            playWhenReady = true
        }
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
            channelScroll.visibility = View.GONE
            cloudHeaderView.visibility = View.GONE
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
            channelScroll.visibility = View.VISIBLE
            cloudHeaderView.visibility = View.GONE
            if (isTvDevice()) playerParams.width = 0
            playerParams.height = if (isTvDevice()) ViewGroup.LayoutParams.MATCH_PARENT else 0
            playerParams.weight = 1f
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

    private fun createLiveMediaItem(uri: Uri): MediaItem =
        MediaItem.Builder()
            .setUri(uri)
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setTargetOffsetMs(60_000)
                    .setMinOffsetMs(45_000)
                    .setMaxOffsetMs(120_000)
                    .setMinPlaybackSpeed(0.97f)
                    .setMaxPlaybackSpeed(1.04f)
                    .build()
            )
            .build()

    /**
     * Controllo di salute indipendente dagli eventi ExoPlayer: intercetta anche
     * i casi in cui la riproduzione resta apparentemente pronta ma il tempo non avanza.
     */
    private fun monitorPlaybackHealth(current: ExoPlayer, now: Long, bufferedAheadMs: Long) {
        if (!current.playWhenReady || current.mediaItemCount == 0 ||
            selectedChannelForRecovery == null || isFinishing || isDestroyed) {
            bufferingStartedAt = 0L
            lastPlaybackProgressAt = now
            lastObservedPositionMs = current.currentPosition
            return
        }

        val position = current.currentPosition
        if (lastObservedPositionMs == C.TIME_UNSET || position > lastObservedPositionMs + 250L) {
            lastObservedPositionMs = position
            lastPlaybackProgressAt = now
        }

        when (current.playbackState) {
            Player.STATE_BUFFERING -> {
                if (bufferingStartedAt == 0L) bufferingStartedAt = now
                // Lascia terminare i timeout HTTP e i retry interni di Media3 prima
                // di riavviare la playlist dall'ultimo live edge disponibile.
                if (now - bufferingStartedAt >= 65_000L && bufferedAheadMs < 2_000L) {
                    schedulePlaybackRecovery("Buffer esaurito: ripristino automatico")
                }
            }
            Player.STATE_READY -> {
                bufferingStartedAt = 0L
                if (bufferedAheadMs < 2_000L && now - lastPlaybackProgressAt >= 20_000L) {
                    schedulePlaybackRecovery("Flusso fermo: riaggancio automatico")
                }
            }
            Player.STATE_ENDED, Player.STATE_IDLE -> {
                if (now - lastPlaybackProgressAt >= 5_000L) {
                    schedulePlaybackRecovery("Flusso terminato o inattivo: riconnessione")
                }
            }
        }
    }

    /**
     * Retry persistente con backoff limitato: non abbandona il canale dopo N errori.
     * Il nuovo prepare parte dal live edge, evitando segmenti HLS ormai scaduti.
     */
    private fun schedulePlaybackRecovery(reason: String) {
        if (retryTask != null || !PlayerCloudSync.hasValidLease(this)) return
        val channel = selectedChannelForRecovery ?: return
        val current = player ?: return
        if (!current.playWhenReady || isFinishing || isDestroyed) return
        val scheduledGeneration = playbackGeneration

        val delays = longArrayOf(2_000L, 4_000L, 8_000L, 15_000L, 30_000L, 45_000L, 60_000L)
        val delay = delays[retryCount.coerceAtMost(delays.lastIndex)]
        retryCount = (retryCount + 1).coerceAtMost(10_000)
        statusView.text = "$reason · tentativo automatico tra ${delay / 1000}s"

        val task = Runnable {
            retryTask = null
            val activePlayer = player ?: return@Runnable
            if (!activePlayer.playWhenReady || !PlayerCloudSync.hasValidLease(this) ||
                scheduledGeneration != playbackGeneration || selectedChannelForRecovery?.url != channel.url) return@Runnable

            bufferingStartedAt = System.currentTimeMillis()
            lastPlaybackProgressAt = bufferingStartedAt
            lastObservedPositionMs = C.TIME_UNSET
            statusView.text = "Riconnessione a ${channel.name}…"
            activePlayer.stop()
            activePlayer.clearMediaItems()
            activePlayer.setMediaItem(createLiveMediaItem(Uri.parse(channel.url)))
            activePlayer.prepare()
            activePlayer.playWhenReady = true
        }
        retryTask = task
        retryHandler.postDelayed(task, delay)
    }

    private fun initializePlayer() {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                60_000,
                180_000,
                15_000,
                45_000
            )
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
            .setTargetLiveOffsetIncrementOnRebufferMs(15_000)
            .build()

        val mediaSourceFactory = HlsMediaSource.Factory(hlsHttpDataSourceFactory)
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(8))

        player = ExoPlayer.Builder(this)
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
                        if (mediaLoadData.dataType == C.DATA_TYPE_MEDIA) {
                            val duration = loadEventInfo.loadDurationMs
                            val bytes = loadEventInfo.bytesLoaded
                            lastHlsLoad = "segmento ${duration}ms · ${bytes / 1024}KB"
                        } else if (mediaLoadData.dataType == C.DATA_TYPE_MANIFEST) {
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
                        if (!wasCanceled) {
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
                            lastHlsLoad = httpCode?.let { "errore $kind HTTP $it" }
                                ?: "errore $kind: ${error.javaClass.simpleName}"
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
                        lastPlayerError = error.errorCodeName
                        val retryable = error.errorCodeName.contains("IO_") ||
                            error.errorCodeName.contains("TIMEOUT") ||
                            error.errorCodeName.contains("BEHIND_LIVE_WINDOW")
                        if (retryable) {
                            bufferingStartedAt = System.currentTimeMillis()
                            schedulePlaybackRecovery("Errore ${error.errorCodeName}: ripristino")
                        } else {
                            statusView.text = "Errore non recuperabile: ${error.errorCodeName}"
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (isPlaying) {
                            lastPlaybackProgressAt = System.currentTimeMillis()
                            bufferingStartedAt = 0L
                            retryHandler.postDelayed({
                                if (player?.isPlaying == true) retryCount = 0
                            }, 60_000L)
                        } else if (player?.playWhenReady == true && bufferingStartedAt == 0L) {
                            bufferingStartedAt = System.currentTimeMillis()
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
        if (!PlayerCloudSync.hasValidLease(this)) {
            stopPlaybackForAuthorization()
            cloudStatusView.text = "Accesso bloccato: attiva prima il dispositivo con un codice monouso."
            return
        }
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
        lastPlayerError = "nessuno"
        rebufferCount = 0
        lastReportedPlaybackState = Player.STATE_IDLE
        bufferingStartedAt = System.currentTimeMillis()
        lastPlaybackProgressAt = bufferingStartedAt
        lastObservedPositionMs = C.TIME_UNSET
        retryTask?.let { retryHandler.removeCallbacks(it) }
        retryTask = null
        retryCount = 0
        player?.apply {
            stop()
            clearMediaItems()
            setMediaItem(createLiveMediaItem(parsed))
            prepare()
            playWhenReady = true
        }
    }

    override fun onStop() {
        retryHandler.removeCallbacks(diagnosticsTask)
        cancelPendingRecovery()
        resumeAfterBackground = player?.playWhenReady == true && (player?.mediaItemCount ?: 0) > 0
        player?.pause()
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        retryHandler.removeCallbacks(diagnosticsTask)
        retryHandler.post(diagnosticsTask)
        if (resumeAfterBackground && (player?.mediaItemCount ?: 0) > 0 && PlayerCloudSync.hasValidLease(this)) {
            player?.playWhenReady = true
        } else if (!PlayerCloudSync.hasValidLease(this)) {
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
        playerView.player = null
        player?.release()
        player = null
        super.onDestroy()
    }
}
