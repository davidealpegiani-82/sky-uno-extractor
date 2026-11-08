package it.davidealpegiani.skyunoextractor

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale

data class TvChannel(
    val name: String,
    val url: String,
    val logo: String = "",
    var selected: Boolean = true
)

class MainActivity : Activity() {
    private val channels = mutableListOf<TvChannel>()
    private lateinit var mainContainer: LinearLayout
    private lateinit var root: LinearLayout
    private lateinit var selectedList: LinearLayout
    private lateinit var otherList: LinearLayout
    private lateinit var selectedHeading: TextView
    private lateinit var otherHeading: TextView
    private lateinit var status: TextView
    private lateinit var count: TextView
    private lateinit var playerView: PlayerView
    private lateinit var playerDock: LinearLayout
    private lateinit var player: ExoPlayer
    private lateinit var scroll: ScrollView
    private var pendingHtml = ""
    private var fullscreen = false

    companion object {
        private const val PICK_FILE = 4101
        private const val SAVE_HTML = 4102
        private const val WORKER_URL = "https://skyuno-link.davide-alpegiani.workers.dev/api/create"
        private const val CYAN = 0xFF39D7FF.toInt()
        private const val BG = 0xFF080B12.toInt()
        private const val PANEL = 0xFF121A29.toInt()
        private const val MUTED = 0xFF9EACC0.toInt()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        window.decorView.systemUiVisibility = 0
        player = ExoPlayer.Builder(this).build()
        buildInterface()
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                status.text = "Errore di riproduzione: ${error.errorCodeName}. Il flusso potrebbe essere offline o non compatibile."
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_BUFFERING) status.text = "Caricamento del flusso…"
                if (playbackState == Player.STATE_READY) status.text = "Video pronto."
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
        try {
            player.setMediaItem(MediaItem.fromUri(streamUrl))
            player.prepare()
            player.playWhenReady = true
        } catch (e: Exception) {
            status.text = "Impossibile avviare il canale: ${e.message}"
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun buildInterface() {
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

        // Player dock: stays anchored to the bottom while the channel list scrolls.
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
        mainContainer.addView(playerDock, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        setContentView(mainContainer)

        val title = TextView(this).apply {
            text = "SKY EXTRACTOR"
            textSize = 23f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        root.addView(title)
        label("Estrattore playlist e riproduttore HLS nativo", MUTED, 13f)

        section("1 · CARICA LA PLAYLIST")
        label("Seleziona file HTML, HTM, TXT, M3U, M3U8 o JSON. I file vengono letti direttamente sul dispositivo.", MUTED)
        button("📂  Seleziona file", true) { openPicker() }
        status = label("Nessun file caricato.", MUTED)

        section("2 · CANALI E SELEZIONE")
        count = label("0 canali importati", CYAN)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(buttonView("Seleziona tutti") { channels.forEach { it.selected = true }; renderChannels() }, LinearLayout.LayoutParams(0, dp(46), 1f))
        row.addView(space(8), LinearLayout.LayoutParams(dp(8), 1))
        row.addView(buttonView("Deseleziona") { channels.forEach { it.selected = false }; renderChannels() }, LinearLayout.LayoutParams(0, dp(46), 1f))
        root.addView(row)

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
        button("🔗  Crea link HTTPS", false) { createHttpsLink() }
        label("Suggerimento: tocca il nome di un canale per riprodurlo; usa la casella per includerlo o escluderlo dall'esportazione.", MUTED, 12f)
    }
    private fun renderChannels() {
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
            target.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(5)
            })
        }
        selected.forEach { addChannelRow(it.index, it.value, selectedList) }
        others.forEach { addChannelRow(it.index, it.value, otherList) }
        updateCount()
    }

