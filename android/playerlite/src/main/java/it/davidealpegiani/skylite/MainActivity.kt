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
    private var focusBeforeFullscreen: View? = null
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
            diagnosticsView.text = "HLS: $state · buffer ${aheadMs / 1000}s · " +
                (if (current.isLoading) "download attivo" else "download in attesa") +
                " · $lastHlsLoad"
            retryHandler.postDelayed(this, 1000L)
        }
    }
    // Ultimo caricamento HLS completato: aiuta a distinguere segmenti lenti da pause normali.
    @Volatile private var lastHlsLoad = "segmenti: in attesa"
    private var retryCount = 0
    private var retryTask: Runnable? = null
    private lateinit var endpointInput: EditText
    private lateinit var activationCodeInput: EditText
    private lateinit var pairButton: Button
    private lateinit var cloudStatusView: TextView
    private lateinit var channelsList: LinearLayout
    private lateinit var channelScroll: ScrollView
    private lateinit var playerContainer: FrameLayout
    private lateinit var controlsPanel: LinearLayout
    private lateinit var cloudHeaderView: TextView
    private lateinit var channelsHeaderView: TextView
    private val cloudHandler = Handler(Looper.getMainLooper())
    private val cloudExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val cloudChannels = mutableListOf<CloudChannel>()
    private var lastAuthorizationCheckAt = 0L

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
            orientation = if (tvMode) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(8, 13, 22))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        val controls = if (tvMode) LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(Color.rgb(12, 20, 32))
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        } else root
        controlsPanel = controls
        if (tvMode) {
            root.addView(controls, LinearLayout.LayoutParams(dp(340), ViewGroup.LayoutParams.MATCH_PARENT))
        }
        titleView = TextView(this).apply {
            text = "SKY PLAYER LITE"
            setTextColor(Color.WHITE)
            textSize = if (tvMode) 23f else 18f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(18), dp(12), dp(10))
        }
        statusView = TextView(this).apply {
            text = "Apri un canale da Sky Player HTML."
            setTextColor(Color.rgb(164, 181, 202))
            textSize = if (tvMode) 15f else 13f
            setPadding(dp(12), 0, dp(12), dp(12))
        }
        playerView = PlayerView(this).apply {
            isFocusable = tvMode
            isFocusableInTouchMode = !tvMode
            setOnFocusChangeListener { _, focused -> if (focused) showController() }
            setOnClickListener { showController() }
            useController = true
            controllerAutoShow = true
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
            textSize = if (tvMode) 12f else 11f
            setPadding(dp(12), 0, dp(12), dp(8))
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
            hint = "Indirizzo Sky Cloud Sync (HTTPS)"
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
            text = "ELENCO CANALI  ·  OK PER RIPRODURRE"
            setTextColor(Color.WHITE)
            textSize = if (tvMode) 14f else 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(dp(16), dp(12), dp(12), dp(8))
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
            setBackgroundColor(Color.BLACK)
        }
        playerContainer.addView(playerView)
        fullscreenButton = TextView(this).apply {
            text = "⛶"
            contentDescription = "Attiva schermo intero"
            textSize = 28f
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
            text = "☰  CANALI"
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
            setBackgroundColor(Color.rgb(10, 18, 30))
            elevation = dp(18).toFloat()
            visibility = View.GONE
            isFocusable = true
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            layoutParams = FrameLayout.LayoutParams(dp(360), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START)
        }
        val drawerHeader = TextView(this).apply {
            text = "SELEZIONA CANALE     ✕"
            contentDescription = "Chiudi elenco canali"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(18), dp(16), dp(18))
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
        root.addView(playerContainer)
        channelScroll = ScrollView(this).apply {
            isFillViewport = true
            isFocusable = false
            layoutParams = if (tvMode) {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            } else {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220))
            }
            setBackgroundColor(Color.rgb(8, 13, 22))
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
            val item = TextView(this).apply {
                text = "▶  ${channel.name}"
                isFocusable = true
                isClickable = true
                isSingleLine = false
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL
                textSize = if (isTvDevice()) 18f else 15f
                includeFontPadding = true
                setLineSpacing(dp(2).toFloat(), 1.0f)
                setTextColor(Color.WHITE)
                setPadding(dp(18), if (isTvDevice()) dp(18) else dp(12), dp(18), if (isTvDevice()) dp(18) else dp(12))
                isFocusable = true
                isFocusableInTouchMode = !isTvDevice()
                background = channelFocusBackground(false)
                setOnFocusChangeListener { _, focused -> background = channelFocusBackground(focused) }
                setOnClickListener { playCloudChannel(channel) }
            }
            channelsList.addView(item, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                bottomMargin = dp(8)
            })
        }
        renderFullscreenChannelList()
        if (isTvDevice()) {
            channelsList.post { channelsList.getChildAt(0)?.requestFocus() }
        }
    }

    private fun primaryActionBackground(focused: Boolean): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.rgb(255, 204, 64))
            cornerRadius = dp(10).toFloat()
            if (focused) setStroke(dp(4), Color.WHITE) else setStroke(dp(1), Color.rgb(255, 226, 130))
        }

    private fun channelActionBackground(focused: Boolean = false): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(if (focused) Color.rgb(48, 76, 108) else Color.rgb(28, 45, 66))
            cornerRadius = dp(10).toFloat()
            if (focused) setStroke(dp(3), Color.rgb(255, 204, 64))
        }

    private fun renderFullscreenChannelList() {
        if (!::fullscreenChannelList.isInitialized) return
        fullscreenChannelList.removeAllViews()
        if (cloudChannels.isEmpty()) {
            fullscreenChannelList.addView(TextView(this).apply {
                text = "Nessun canale disponibile nell'elenco locale.\n\nPer visualizzare qui la playlist completa, associa il Player a Sky Cloud Sync oppure aggiorna la lista sincronizzata."
                setTextColor(Color.rgb(210, 220, 232))
                textSize = 17f
                setPadding(dp(16), dp(20), dp(16), dp(20))
            })
            return
        }
        cloudChannels.forEach { channel ->
            val row = TextView(this).apply {
                text = "▶  ${channel.name}"
                textSize = 18f
                maxLines = 2
                gravity = Gravity.CENTER_VERTICAL
                setTextColor(Color.WHITE)
                setPadding(dp(18), dp(18), dp(18), dp(18))
                isFocusable = true
                isClickable = true
                background = channelActionBackground()
                setOnFocusChangeListener { _, focused -> background = channelActionBackground(focused) }
                setOnClickListener {
                    closeFullscreenChannelDrawer()
                    playCloudChannel(channel)
                }
            }
            fullscreenChannelList.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) })
        }
    }

    private fun toggleFullscreenChannelDrawer() {
        if (!isFullscreen) return
        if (isChannelDrawerOpen) closeFullscreenChannelDrawer() else {
            renderFullscreenChannelList()
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
        if (isFullscreen) fullscreenChannelsButton.requestFocus()
    }

    private fun channelFocusBackground(focused: Boolean): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(if (focused) Color.rgb(42, 65, 91) else Color.rgb(28, 45, 66))
            cornerRadius = dp(10).toFloat()
            if (focused) setStroke(dp(3), Color.rgb(255, 204, 64))
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

    private fun stopPlaybackForAuthorization() {
        retryTask?.let { retryHandler.removeCallbacks(it) }
        player?.apply { stop(); clearMediaItems() }
        titleView.text = "SKY PLAYER LITE"
        statusView.text = "Attivazione richiesta"
        diagnosticsView.visibility = View.GONE
        channelsHeaderView.visibility = View.GONE
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
        if (isTvDevice()) playerView.post { playerView.requestFocus() }
    }

    private fun isVisibleInHierarchy(view: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current.visibility != View.VISIBLE) return false
            current = current.parent as? View
        }
        return true
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
            if (isTvDevice()) controlsPanel.visibility = View.GONE
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
            fullscreenChannelsButton.visibility = View.VISIBLE
            isChannelDrawerOpen = false
            fullscreenChannelDrawer.visibility = View.GONE
            if (isTvDevice()) fullscreenChannelsButton.post { fullscreenChannelsButton.requestFocus() }
        } else {
            isChannelDrawerOpen = false
            fullscreenChannelDrawer.visibility = View.GONE
            fullscreenChannelsButton.visibility = View.GONE
            decor.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            if (isTvDevice()) controlsPanel.visibility = View.VISIBLE
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
            when (event.keyCode) {
                android.view.KeyEvent.KEYCODE_MENU,
                android.view.KeyEvent.KEYCODE_GUIDE -> {
                    if (isFullscreen) {
                        toggleFullscreenChannelDrawer()
                        return true
                    }
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
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    player?.let { if (it.isPlaying) it.pause() else it.play() }
                    return true
                }
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY -> { player?.play(); return true }
                android.view.KeyEvent.KEYCODE_MEDIA_PAUSE -> { player?.pause(); return true }
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
                            lastHlsLoad = "errore $kind: ${error.javaClass.simpleName}"
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
        if (resumeAfterBackground && (player?.mediaItemCount ?: 0) > 0 && PlayerCloudSync.hasValidLease(this)) {
            player?.playWhenReady = true
        } else if (!PlayerCloudSync.hasValidLease(this)) {
            stopPlaybackForAuthorization()
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
