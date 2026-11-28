package it.davidealpegiani.skyunoextractor

import android.content.Context
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

internal data class VegetaImportResult(
    val serversTried: Int,
    val serversOk: Int,
    val channelsImported: Int,
    val errors: List<String>,
    val source: String = "catalog"
)

/**
 * Importa i canali Vegeta IT.
 * Priorità: catalogo aggregato su GitHub (affidabile, già filtrato).
 * Fallback: M3U live dai server del catalogo server.
 */
internal object VegetaItImporter {
    private const val CHANNELS_CATALOG_URL =
        "https://raw.githubusercontent.com/davidealpegiani-82/Davix-extractor/main/catalogs/it/vegeta-it-channels.json"
    private const val MAX_CATALOG_BYTES = 8 * 1024 * 1024
    private const val MAX_M3U_BYTES = 80 * 1024 * 1024

    fun importAll(
        context: Context,
        progress: (String) -> Unit = {}
    ): VegetaImportResult {
        // 1) Catalogo canali pre-aggregato (stesso usato dal Web)
        try {
            progress("Scarico catalogo canali Vegeta IT…")
            val body = request(CHANNELS_CATALOG_URL, MAX_CATALOG_BYTES, "application/json")
            val root = JSONObject(body)
            if (root.optInt("schemaVersion") != 1) {
                throw IOException("Schema catalogo canali non supportato.")
            }
            val array = root.optJSONArray("channels")
                ?: throw IOException("Campo channels assente.")
            val channels = ArrayList<VegetaChannel>(array.length())
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val name = item.optString("name").trim()
                val url = item.optString("url").trim()
                if (name.isBlank()) continue
                if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) continue
                channels.add(
                    VegetaChannel(
                        name = name,
                        url = url,
                        logo = item.optString("logo"),
                        category = item.optString("category").ifBlank { "Vegeta IT" },
                        serverId = item.optString("serverId"),
                        serverName = item.optString("serverName")
                    )
                )
            }
            if (channels.isEmpty()) {
                throw IOException("Catalogo canali vuoto.")
            }
            val unique = dedupe(channels)
            VegetaItPlaylistStore.save(context, unique, root.optString("updatedAt").takeIf { it.isNotBlank() })
            progress("Catalogo Vegeta: ${unique.size} canali.")
            return VegetaImportResult(
                serversTried = root.optInt("serversTried"),
                serversOk = root.optInt("serversOk"),
                channelsImported = unique.size,
                errors = emptyList(),
                source = "catalog"
            )
        } catch (catalogError: Exception) {
            progress("Catalogo non disponibile, provo i server live…")
            // 2) Fallback: M3U live
            return importFromLiveServers(context, progress, catalogError.message)
        }
    }

    private fun importFromLiveServers(
        context: Context,
        progress: (String) -> Unit,
        catalogError: String?
    ): VegetaImportResult {
        val catalog = VegetaItCatalogClient.download(progress)
        val upServers = catalog.servers.filter { it.status == "up" }
        if (upServers.isEmpty()) {
            throw IOException(
                "Nessun server Vegeta IT online." +
                    (catalogError?.let { " (catalogo: $it)" } ?: "")
            )
        }

        val allChannels = ArrayList<VegetaChannel>()
        val errors = ArrayList<String>()
        if (!catalogError.isNullOrBlank()) {
            errors.add("catalogo: $catalogError")
        }
        var ok = 0

        upServers.forEachIndexed { index, server ->
            progress("Server ${index + 1}/${upServers.size}: ${server.name}…")
            try {
                val m3u = downloadM3u(server.url)
                val parsed = parseM3u(m3u, server)
                if (parsed.isEmpty()) {
                    errors.add(server.name + ": playlist vuota")
                    progress(server.name + ": playlist vuota")
                } else {
                    allChannels.addAll(parsed)
                    ok++
                    progress("${server.name}: ${parsed.size} canali")
                }
            } catch (error: Exception) {
                val msg = server.name + ": " + (error.message ?: "errore sconosciuto")
                errors.add(msg)
                progress(msg)
            }
        }

        val unique = dedupe(allChannels)
        if (unique.isEmpty()) {
            throw IOException(
                "Nessun canale recuperato dai server live." +
                    if (errors.isNotEmpty()) " Dettagli: " + errors.take(3).joinToString(" · ") else ""
            )
        }
        VegetaItPlaylistStore.save(context, unique, catalog.updatedAt)
        progress("Importazione live: ${unique.size} canali unici.")
        return VegetaImportResult(
            serversTried = upServers.size,
            serversOk = ok,
            channelsImported = unique.size,
            errors = errors,
            source = "live"
        )
    }

    private fun dedupe(channels: List<VegetaChannel>): List<VegetaChannel> {
        val unique = LinkedHashMap<String, VegetaChannel>()
        for (ch in channels) {
            val key = ch.url.trim().lowercase()
            if (key.isNotEmpty() && !unique.containsKey(key)) {
                unique[key] = ch
            }
        }
        return unique.values.toList()
    }

    private fun downloadM3u(address: String): String =
        request(address, MAX_M3U_BYTES, "*/*")

    private fun request(address: String, maxBytes: Int, accept: String): String {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", "DAVIX-Extractor/1.0")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IOException("HTTP $code")
            }
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > maxBytes) throw IOException("Risposta troppo grande")
                    output.write(buffer, 0, read)
                }
            }
            return String(output.toByteArray(), StandardCharsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseM3u(content: String, server: VegetaItServer): List<VegetaChannel> {
        val result = ArrayList<VegetaChannel>()
        var pendingName = "Canale"
        var pendingLogo = ""
        var pendingGroup = "Vegeta IT"
        for (raw in content.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#EXTINF", ignoreCase = true)) {
                pendingName = extractName(line)
                pendingLogo = extractAttr(line, "tvg-logo")
                pendingGroup = extractAttr(line, "group-title").ifBlank { "Vegeta IT" }
            } else if (!line.startsWith("#") &&
                (line.startsWith("http://", true) || line.startsWith("https://", true))
            ) {
                result.add(
                    VegetaChannel(
                        name = pendingName.ifBlank { "Canale" },
                        url = line,
                        logo = pendingLogo,
                        category = pendingGroup,
                        serverId = server.id,
                        serverName = server.name
                    )
                )
                pendingName = "Canale"
                pendingLogo = ""
                pendingGroup = "Vegeta IT"
            }
        }
        return result
    }

    private fun extractName(extinf: String): String {
        val comma = extinf.lastIndexOf(',')
        if (comma >= 0 && comma < extinf.length - 1) {
            return extinf.substring(comma + 1).trim().ifBlank { "Canale" }
        }
        return "Canale"
    }

    private fun extractAttr(line: String, key: String): String {
        val regex = Regex("""$key=\"([^\"]*)\"""", RegexOption.IGNORE_CASE)
        return regex.find(line)?.groupValues?.getOrNull(1)?.trim().orEmpty()
    }
}
