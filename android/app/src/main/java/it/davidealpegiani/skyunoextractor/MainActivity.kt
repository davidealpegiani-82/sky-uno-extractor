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
    private lateinit var root: LinearLayout
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var count: TextView
    private lateinit var playerView: PlayerView
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
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun buildInterface() {
        scroll = ScrollView(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
            setBackgroundColor(BG)
        }
        scroll.addView(root)
        setContentView(scroll)

        val title = TextView(this).apply {
            text = "SKY UNO EXTRACTOR"
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
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)
        button("⬇  Esporta player HTML", true) { requestHtmlExport() }
        button("🔗  Crea link HTTPS", false) { createHttpsLink() }

        section("3 · RIPRODUZIONE INTEGRATA")
        playerView = PlayerView(this).apply {
            player = this@MainActivity.player
            useController = true
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            setBackgroundColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(230))
        }
        root.addView(playerView)
        button("⛶  Schermo intero", false) { toggleFullscreen() }
        label("La riproduzione usa il motore AndroidX Media3/ExoPlayer con supporto HLS.", MUTED, 12f)
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
        if (resultCode != RESULT_OK || data == null) return
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

    private fun importUris(uris: List<Uri>) {
        var added = 0
        var duplicates = 0
        var invalid = 0
        uris.forEach { uri ->
            try {
                val text = contentResolver.openInputStream(uri)?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""
                val name = uri.lastPathSegment?.substringAfterLast('/') ?: "playlist"
                val found = parseChannels(text)
                if (found.isEmpty()) invalid++ else found.forEach { ch ->
                    if (channels.any { it.name.equals(ch.name, true) && it.url == ch.url }) duplicates++
                    else {
                        ch.selected = !(ch.name.contains("sky", true) && (ch.name.contains("plus", true) || ch.name.contains("+1")))
                        channels.add(ch)
                        added++
                    }
                }
            } catch (_: Exception) { invalid++ }
        }
        renderChannels()
        status.text = "File analizzati: ${uris.size} · Nuovi canali: $added · Duplicati ignorati: $duplicates · File senza canali validi: $invalid"
    }

    private fun parseChannels(text: String): List<TvChannel> {
        val found = mutableListOf<TvChannel>()
        val seen = mutableSetOf<String>()
        fun add(nameRaw: String?, urlRaw: String?, logoRaw: String? = "") {
            val name = nameRaw?.trim()?.takeIf { it.isNotBlank() } ?: return
            val url = urlRaw?.trim()?.replace("&amp;", "&")?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) } ?: return
            val key = name.lowercase(Locale.ROOT) + "|" + url
            if (seen.add(key)) found.add(TvChannel(name, url, logoRaw?.trim().orEmpty()))
        }

        val lines = text.lines()
        for (i in lines.indices) {
            val line = lines[i].trim()
            if (line.startsWith("#EXTINF", true)) {
                val name = line.substringAfterLast(',').trim()
                val logo = Regex("tvg-logo=[\"']([^\"']+)").find(line)?.groupValues?.getOrNull(1).orEmpty()
                val url = lines.drop(i + 1).firstOrNull { it.trim().isNotEmpty() && !it.trim().startsWith("#") }?.trim()
                add(name, url, logo)
            }
        }

        fun field(obj: String, keys: List<String>): String? {
            for (key in keys) {
                val pattern = Regex("[\"']" + Regex.escape(key) + "[\"']\\s*:\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
                pattern.find(obj)?.groupValues?.getOrNull(1)?.let { return it }
            }
            return null
        }
        val objects = Regex("\\{[^{}]{0,4000}\\}").findAll(text)
        objects.forEach { m ->
            val obj = m.value
            add(
                field(obj, listOf("name", "title", "channel_name", "tvg-name")),
                field(obj, listOf("url", "stream", "src", "link")),
                field(obj, listOf("logo", "tvg-logo", "image", "icon"))
            )
        }

        try {
            val trimmed = text.trim()
            val array = when {
                trimmed.startsWith("[") -> JSONArray(trimmed)
                trimmed.startsWith("{") -> JSONObject(trimmed).optJSONArray("channels")
                else -> null
            }
            if (array != null) for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                add(
                    o.optString("name", o.optString("title", o.optString("channel_name"))),
                    o.optString("url", o.optString("stream", o.optString("src", o.optString("link")))),
                    o.optString("logo", o.optString("tvg-logo"))
                )
            }
        } catch (_: Exception) { }
        return found
    }

    private fun renderChannels() {
        list.removeAllViews()
        channels.forEachIndexed { index, channel ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(5), dp(8), dp(5))
                setBackgroundColor(PANEL)
            }
            val check = CheckBox(this).apply {
                isChecked = channel.selected
                buttonTintList = android.content.res.ColorStateList.valueOf(CYAN)
                setOnCheckedChangeListener { _, checked -> channel.selected = checked; updateCount() }
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
            list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(5)
            })
        }
        updateCount()
    }

    private fun updateCount() {
        count.text = "${channels.size} canali importati · ${channels.count { it.selected }} selezionati per esportazione"
    }

    private fun playChannel(index: Int) {
        val channel = channels.getOrNull(index) ?: return
        status.text = "Avvio: ${channel.name}"
        try {
            player.setMediaItem(MediaItem.fromUri(channel.url))
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
        pendingHtml = makePlayerHtml(chosen)
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/html"
            putExtra(Intent.EXTRA_TITLE, "sky-uno-player.html")
        }
        startActivityForResult(intent, SAVE_HTML)
    }

    private fun makePlayerHtml(chosen: List<TvChannel>): String {
        val array = JSONArray()
        chosen.forEach { c -> array.put(JSONObject().put("name", c.name).put("url", c.url).put("logo", c.logo)) }
        val data = array.toString().replace("<", "\\u003c")
        return """<!doctype html><html lang="it"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Sky Uno Player</title><style>body{margin:0;background:#080b12;color:white;font:15px system-ui}.top{padding:14px;background:#121a29;font-weight:bold}.video{height:48vh;background:#000}video{width:100%;height:100%;object-fit:contain}.list{padding:10px}.channel{display:block;width:100%;padding:14px;margin:5px 0;background:#172438;border:1px solid #30445e;border-radius:10px;color:white;text-align:left}</style><script src="https://cdn.jsdelivr.net/npm/hls.js@1.7.4/dist/hls.min.js"></script></head><body><div class="top">SKY UNO PLAYER</div><div class="video"><video id="v" controls playsinline></video></div><div class="list" id="list"></div><script>const C=$data;const v=document.getElementById('v');let h=null;function play(i){if(h){h.destroy();h=null}v.pause();v.removeAttribute('src');v.load();let u=C[i].url;if(v.canPlayType('application/vnd.apple.mpegurl')){v.src=u;v.play().catch(()=>{})}else if(window.Hls&&Hls.isSupported()){h=new Hls({enableWorker:true,lowLatencyMode:true,liveSyncDurationCount:10,liveMaxLatencyDurationCount:20,maxBufferLength:60,maxMaxBufferLength:120,backBufferLength:180,fragLoadingTimeOut:10000,manifestLoadingTimeOut:10000,debug:false});h.loadSource(u);h.attachMedia(v);h.on(Hls.Events.MANIFEST_PARSED,()=>v.play().catch(()=>{}))}}C.forEach((c,i)=>{let b=document.createElement('button');b.className='channel';b.textContent=c.name;b.onclick=()=>play(i);document.getElementById('list').append(b)});if(C.length)play(0)</script></body></html>"""
    }

    private fun createHttpsLink() {
        val chosen = selectedChannels()
        if (chosen.isEmpty()) { toast("Seleziona almeno un canale."); return }
        status.text = "Creazione link HTTPS…"
        Thread {
            try {
                val payload = JSONObject().put("channels", JSONArray().apply {
                    chosen.forEach { put(JSONObject().put("name", it.name).put("url", it.url).put("logo", it.logo)) }
                }).toString()
                val connection = (URL(WORKER_URL).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15000
                    readTimeout = 20000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                connection.outputStream.use { it.write(payload.toByteArray(StandardCharsets.UTF_8)) }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val response = stream.bufferedReader().use { it.readText() }
                connection.disconnect()
                val json = JSONObject(response)
                val link = json.optString("link")
                if (code !in 200..299 || !json.optBoolean("success") || link.isBlank()) throw Exception(json.optString("error", "Servizio non disponibile"))
                runOnUiThread {
                    status.text = "Link creato."
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, link)
                    }
                    startActivity(Intent.createChooser(share, "Condividi link HTTPS"))
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Creazione link non riuscita: ${e.message}" }
            }
        }.start()
    }

    private fun toggleFullscreen() {
        fullscreen = !fullscreen
        if (fullscreen) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
            (playerView.parent as? ViewGroup)?.removeView(playerView)
            setContentView(playerView)
            playerView.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            window.decorView.systemUiVisibility = 0
            setContentView(scroll)
            root.addView(playerView, (root.childCount - 2).coerceAtLeast(0), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(230)))
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        player.release()
        super.onDestroy()
    }
}
