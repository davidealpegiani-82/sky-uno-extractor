package it.davidealpegiani.skylite

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal object PlayerCloudSync {
    private const val PREFS = "sky_player_cloud_sync"
    private const val SECURE_PREFS = "sky_player_cloud_sync_secure"
    private const val KEY_ALIAS = "sky_player_device_token_v1"
    private const val BASE_URL = "base_url"
    private const val INSTALL_ID = "install_id"
    private const val TOKEN = "device_token"
    private const val VERSION = "config_version"
    private const val CONFIG = "config_json"
    private const val LEASE_EXPIRY = "lease_expires_at"

    fun baseUrl(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(BASE_URL, "https://sky-cloud-sync.davide-alpegiani.workers.dev").orEmpty().trim().trimEnd('/')

    fun saveBaseUrl(context: Context, value: String) {
        val normalized = value.trim().trimEnd('/')
        require(normalized.startsWith("https://", true)) { "Inserisci un indirizzo HTTPS valido." }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(BASE_URL, normalized).apply()
    }

    fun hasToken(context: Context): Boolean = !readToken(context).isNullOrBlank()

    fun hasValidLease(context: Context): Boolean =
        hasToken(context) && context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(LEASE_EXPIRY, 0L) > System.currentTimeMillis()

    fun clearAuthorization(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(CONFIG).remove(VERSION).remove(LEASE_EXPIRY).apply()
        context.getSharedPreferences(SECURE_PREFS, Context.MODE_PRIVATE).edit().remove(TOKEN).apply()
    }

    fun currentVersion(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(VERSION, 0)

    fun cachedConfig(context: Context): JSONObject? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(CONFIG, null)?.let { runCatching { JSONObject(it) }.getOrNull() }

    fun activate(context: Context, codeValue: String): JSONObject {
        val code = codeValue.uppercase().filter { it.isLetterOrDigit() }
        require(code.matches(Regex("[A-HJ-NP-Z2-9]{10}"))) { "Inserisci il codice monouso di 10 caratteri." }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var installId = prefs.getString(INSTALL_ID, null)
        if (installId.isNullOrBlank()) {
            installId = java.util.UUID.randomUUID().toString()
            prefs.edit().putString(INSTALL_ID, installId).apply()
        }
        val token = getOrCreateToken(context)
        val body = JSONObject()
            .put("code", code)
            .put("deviceSecret", token)
            .put("installId", installId)
            .put("deviceName", "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(100))
            .put("platform", "android")
        val result = request(context, "POST", "/v2/activate", body, authenticated = false)
        val lease = result.optLong("leaseExpiresAt", 0L)
        require(result.optBoolean("activated") && lease > System.currentTimeMillis()) {
            "Il server non ha restituito una licenza valida."
        }
        prefs.edit().putLong(LEASE_EXPIRY, lease).apply()
        return result
    }

    fun sync(context: Context): JSONObject {
        val version = currentVersion(context)
        val result = request(context, "GET", "/v1/config?sinceVersion=$version", null, authenticated = true)
        val lease = result.optLong("leaseExpiresAt", 0L)
        require(lease > System.currentTimeMillis()) { "Licenza offline scaduta. Riconnetti il Player." }
        if (result.optBoolean("unchanged", false)) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(LEASE_EXPIRY, lease).apply()
            return JSONObject().put("unchanged", true).put("version", result.optInt("version", version))
        }
        val config = result.optJSONObject("config")
            ?: throw IllegalStateException("La configurazione ricevuta non è valida.")
        val channels = config.optJSONArray("channels")
            ?: throw IllegalStateException("La lista dei canali non è presente.")
        require(channels.length() <= 1000) { "La lista ricevuta supera il limite previsto." }
        val valid = (0 until channels.length()).count { index ->
            val channel = channels.optJSONObject(index) ?: return@count false
            val name = channel.optString("name")
            val stream = channel.optString("url")
            name.isNotBlank() && (stream.startsWith("https://", true) || stream.startsWith("http://", true))
        }
        require(valid == channels.length()) { "La configurazione contiene canali non validi." }

        // Salvataggio atomico: la versione avanza solo dopo aver validato l'intera lista.
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(CONFIG, config.toString())
            .putInt(VERSION, result.optInt("version", version))
            .putLong(LEASE_EXPIRY, lease)
            .apply()
        return JSONObject()
            .put("unchanged", false)
            .put("version", result.optInt("version"))
            .put("channelCount", channels.length())
    }

    private fun request(
        context: Context,
        method: String,
        path: String,
        body: JSONObject?,
        authenticated: Boolean
    ): JSONObject {
        val base = baseUrl(context)
        require(base.startsWith("https://", true)) { "Configura prima l'indirizzo HTTPS di Sky Cloud Sync." }
        val token = if (authenticated) readToken(context) else null
        if (authenticated) require(!token.isNullOrBlank()) { "Questo Player non è ancora associato." }
        val connection = (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 12_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
            if (authenticated) setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) {
                connection.outputStream.use {
                    it.write(body.toString().toByteArray(StandardCharsets.UTF_8))
                }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            val result = runCatching { JSONObject(raw) }.getOrElse {
                JSONObject().put("error", "Risposta non valida dal servizio (HTTP $code).")
            }
            if (code !in 200..299) {
                throw IllegalStateException(result.optString("error").ifBlank { "Errore HTTP $code" })
            }
            return result
        } finally {
            connection.disconnect()
        }
    }

    private fun getOrCreateToken(context: Context): String {
        readToken(context)?.let { return it }
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val token = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        saveToken(context, token)
        return token
    }

    private fun readToken(context: Context): String? {
        val packedValue = context.getSharedPreferences(SECURE_PREFS, Context.MODE_PRIVATE)
            .getString(TOKEN, null) ?: return null
        return runCatching {
            val packed = Base64.decode(packedValue, Base64.NO_WRAP)
            require(packed.size > 12)
            val iv = packed.copyOfRange(0, 12)
            val encrypted = packed.copyOfRange(12, packed.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), StandardCharsets.UTF_8)
        }.getOrNull()
    }

    private fun saveToken(context: Context, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        val packed = cipher.iv + encrypted
        context.getSharedPreferences(SECURE_PREFS, Context.MODE_PRIVATE).edit()
            .putString(TOKEN, Base64.encodeToString(packed, Base64.NO_WRAP))
            .apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
