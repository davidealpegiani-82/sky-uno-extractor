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
import android.widget.FrameLayout
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
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
    private lateinit var playerDock: LinearLayout
    private lateinit var fullscreenContainer: FrameLayout
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
        // Buffer live equilibrato: avvio ragionevolmente rapido, ma più margine
        // per assorbire oscillazioni di rete senza accumulare minuti di ritardo.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                20_000, // buffer minimo
                60_000, // buffer massimo
                3_000,  // avvio iniziale
                6_000   // ripresa dopo un rebuffer
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        // Timeout espliciti e retry progressivi gestiti da Media3.
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(25_000)
            .setAllowCrossProtocolRedirects(true)

        val hlsMediaSourceFactory = HlsMediaSource.Factory(httpDataSourceFactory)
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(5))

        player = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(hlsMediaSourceFactory)
            .build()
        player.playWhenReady = false
        buildInterface()
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val detail = generateSequence(error as Throwable?) { it.cause }
                    .take(4)
                    .mapNotNull { it.message?.takeIf { message -> message.isNotBlank() } }
                    .joinToString(" → ")
                status.text = "Errore ${error.errorCodeName} (${error.errorCode}): " + detail.ifBlank { "flusso interrotto o non compatibile" }
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
            val mediaItem = MediaItem.Builder().setUri(streamUrl).setMimeType(MimeTypes.APPLICATION_M3U8).build()
            player.setMediaItem(mediaItem)
            player.prepare()
            player.playWhenReady = true
        } catch (e: Exception) {
            status.text = "Impossibile avviare il canale: ${e.message}"
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
        label("Suggerimento: tocca il nome di un canale per riprodurlo; usa la casella per includerlo o escluderlo dall'esportazione.", MUTED, 12f)
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

    private fun updateCount() {
        count.text = "${channels.size} canali importati · ${channels.count { it.selected }} selezionati per esportazione"
    }

    private fun playChannel(index: Int) {
        val channel = channels.getOrNull(index) ?: return
        status.text = "Avvio: ${channel.name}"
        try {
            // Dichiarazione HLS esplicita anche per URL con token o senza estensione .m3u8.
            val mediaItem = MediaItem.Builder().setUri(channel.url).setMimeType(MimeTypes.APPLICATION_M3U8).build()
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
        return """
<!doctype html>
<html lang="it">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="theme-color" content="#080b12">
<title>Sky Player</title>
<style>
*{box-sizing:border-box}
body{margin:0;background:#080b12;color:#f4f7fb;font:15px system-ui}
.app{height:100dvh;display:flex;flex-direction:column}
.top{padding:14px;background:#111a29;font-weight:800}
.video{height:46dvh;min-height:230px;background:#000}
video{width:100%;height:100%;object-fit:contain}
.list{overflow:auto;padding:8px;flex:1}
.channel{display:flex;width:100%;padding:14px;margin:4px 0;background:#121d2d;border:1px solid #293b52;border-radius:12px;color:white;text-align:left;font-weight:700}
.channel.active{border-color:#39d7ff;color:#39d7ff}
.status{padding:8px 12px;color:#9eacc0;font-size:12px}
</style>
<script src="https://cdn.jsdelivr.net/npm/hls.js@1.7.4/dist/hls.min.js"></script>
</head>
<body>
<div class="app">
<div class="top">SKY PLAYER</div>
<div class="video"><video id="v" controls playsinline webkit-playsinline preload="auto"></video></div>
<div class="status" id="status">Scegli un canale</div>
<div class="list" id="list"></div>
</div>
<script>
const C=__CHANNELS__;
const v=document.getElementById('v');
const status=document.getElementById('status');
const isAndroid=/Android/i.test(navigator.userAgent);
let h=null,active=-1,retryTimer=null,watchdog=null,retries=0,lastTime=0,lastProgress=Date.now(),recovering=false;
const retryDelays=[1000,2000,4000,8000,12000,15000];
function openNative(i){
 const c=C[i],q=new URLSearchParams({url:c.url,name:c.name});
 location.href='intent://play?'+q.toString()+'#Intent;scheme=skyunoextractor;package=it.davidealpegiani.skyunoextractor;end';
}
function stopEngine(){
 if(retryTimer){clearTimeout(retryTimer);retryTimer=null}
 if(watchdog){clearInterval(watchdog);watchdog=null}
 if(h){try{h.destroy()}catch(e){}h=null}
 v.pause();v.removeAttribute('src');v.load();recovering=false;
}
function updateStatus(message){status.textContent=message}
function scheduleRecovery(reason){
 if(retryTimer||active<0)return;
 if(retries>=retryDelays.length){updateStatus('Il flusso non risponde. Riprova selezionando nuovamente il canale.');return}
 const delay=retryDelays[retries++];
 recovering=true;
 updateStatus(reason+' · tentativo automatico '+retries+'/'+retryDelays.length);
 retryTimer=setTimeout(function(){
  retryTimer=null;
  if(active>=0)startChannel(active,true);
 },delay);
}
function startWatchdog(){
 if(watchdog)clearInterval(watchdog);
 lastTime=v.currentTime||0;lastProgress=Date.now();
 watchdog=setInterval(function(){
  if(v.paused||v.ended||active<0)return;
  const now=v.currentTime||0;
  if(Math.abs(now-lastTime)>=0.20){
   lastTime=now;lastProgress=Date.now();
   if(!recovering){retries=0}
  }else if(Date.now()-lastProgress>15000){
   scheduleRecovery('Riproduzione bloccata');
  }
 },4000);
}
function startChannel(i,isRetry){
 const c=C[i];if(!c)return;
 active=i;
 if(retryTimer){clearTimeout(retryTimer);retryTimer=null}
 if(h){try{h.destroy()}catch(e){}h=null}
 v.pause();v.removeAttribute('src');v.load();
 document.querySelectorAll('.channel').forEach((b,n)=>b.classList.toggle('active',n===i));
 updateStatus((isRetry?'Ripristino: ':'Caricamento: ')+c.name);
 if(v.canPlayType('application/vnd.apple.mpegurl')){
  v.src=c.url;
  v.play().catch(()=>updateStatus('Premi Play per avviare '+c.name));
 }else if(window.Hls&&Hls.isSupported()){
  h=new Hls({
   enableWorker:true,lowLatencyMode:false,
   liveSyncDurationCount:3,liveMaxLatencyDurationCount:6,
   maxBufferLength:30,maxMaxBufferLength:60,backBufferLength:30,
   fragLoadingTimeOut:20000,manifestLoadingTimeOut:20000,
   manifestLoadingMaxRetry:4,levelLoadingMaxRetry:4,fragLoadingMaxRetry:6,
   debug:false
  });
  h.on(Hls.Events.MANIFEST_PARSED,function(){
   v.play().catch(()=>updateStatus('Premi Play per avviare '+c.name));
  });
  h.on(Hls.Events.ERROR,function(_,e){
   if(!e||!e.fatal)return;
   scheduleRecovery(e.type===Hls.ErrorTypes.NETWORK_ERROR?'Errore di rete':'Errore del flusso');
  });
  h.loadSource(c.url);h.attachMedia(v);
 }else{
  updateStatus('Questo browser non supporta HLS. Su Android puoi provare Apri nell’app.');
 }
 startWatchdog();
}
v.addEventListener('playing',function(){lastProgress=Date.now();lastTime=v.currentTime||0;updateStatus('In riproduzione: '+(C[active]?.name||''));});
v.addEventListener('timeupdate',function(){if(Math.abs((v.currentTime||0)-lastTime)>=0.2){lastTime=v.currentTime||0;lastProgress=Date.now();}});
v.addEventListener('waiting',function(){if(!v.paused)updateStatus('Buffering…');});
v.addEventListener('error',function(){if(active>=0)scheduleRecovery('Errore di riproduzione');});
C.forEach(function(c,i){
 const row=document.createElement('div');row.style.display='flex';row.style.gap='6px';
 const b=document.createElement('button');b.className='channel';b.style.flex='1';b.textContent=c.name;b.onclick=function(){retries=0;startChannel(i,false)};row.appendChild(b);
 if(isAndroid){const n=document.createElement('button');n.className='channel';n.textContent='Apri nell’app';n.onclick=function(){openNative(i)};row.appendChild(n)}
 document.getElementById('list').appendChild(row);
});
if(C.length)startChannel(0,false);
</script>
</body>
</html>
""".replace("__CHANNELS__", data)
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

    override fun onStop() {
        // Non lasciare il flusso attivo quando l'app passa davvero in background.
        // Il cambio orientamento è gestito da configChanges nel manifest.
        if (::player.isInitialized) player.pause()
        super.onStop()
    }

    override fun onDestroy() {
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }
}
