package it.davidealpegiani.skyunoextractor

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Secure local storage for the Extractor's Cloud Sync administrator credential.
 * The encryption key is generated and retained by Android Keystore.
 */
internal object CloudSyncSecretStore {
    private const val KEY_ALIAS = "sky_cloud_sync_admin_v1"
    private const val PREFS = "sky_cloud_sync_secure"
    private const val VALUE_KEY = "admin_key"

    fun save(context: Context, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        val packed = cipher.iv + encrypted
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(VALUE_KEY, Base64.encodeToString(packed, Base64.NO_WRAP))
            .apply()
    }

    fun read(context: Context): String? {
        val packedValue = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(VALUE_KEY, null) ?: return null
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

internal object CloudSyncAdmin {
    private const val PREFS = "sky_cloud_sync_settings"
    private const val BASE_URL = "base_url"

    fun baseUrl(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(BASE_URL, "https://sky-cloud-sync.davide-alpegiani.workers.dev").orEmpty().trim().trimEnd('/')

    fun saveSettings(context: Context, baseUrl: String, adminKey: String?) {
        val normalized = baseUrl.trim().trimEnd('/')
        require(normalized.startsWith("https://", true)) { "Il servizio Cloud Sync deve usare HTTPS." }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(BASE_URL, normalized)
            .apply()
        if (!adminKey.isNullOrBlank()) {
            require(adminKey.length >= 32) { "La chiave amministratore deve contenere almeno 32 caratteri." }
            CloudSyncSecretStore.save(context, adminKey)
        } else {
            require(!CloudSyncSecretStore.read(context).isNullOrBlank()) {
                "Inserisci la chiave amministratore al primo salvataggio."
            }
        }
    }

    fun publish(context: Context, channels: JSONArray): JSONObject {
        val payload = JSONObject()
            .put("schemaVersion", 1)
            .put("updateId", java.util.UUID.randomUUID().toString())
            .put("channels", channels)
        return adminRequest(context, "POST", "/v1/admin/config/publish", payload)
    }

    fun createActivationCode(context: Context): JSONObject =
        adminRequest(context, "POST", "/v2/admin/activation-codes", JSONObject())

    fun devices(context: Context): JSONObject =
        adminRequest(context, "GET", "/v1/admin/devices", null)

    fun rename(context: Context, deviceId: String, displayName: String): JSONObject =
        adminRequest(context, "POST", "/v2/admin/devices/rename",
            JSONObject().put("deviceId", deviceId).put("displayName", displayName))

    fun revoke(context: Context, deviceId: String): JSONObject =
        adminRequest(context, "POST", "/v1/admin/devices/revoke", JSONObject().put("deviceId", deviceId))

    private fun adminRequest(
        context: Context,
        method: String,
        path: String,
        body: JSONObject?
    ): JSONObject {
        val base = baseUrl(context)
        val key = CloudSyncSecretStore.read(context)
        require(base.startsWith("https://", true)) { "Configura prima l'indirizzo HTTPS di Sky Cloud Sync." }
        require(!key.isNullOrBlank()) { "Configura prima la chiave amministratore di Sky Cloud Sync." }
        val connection = (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-Sky-Admin-Key", key)
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
            val responseText = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            val result = runCatching { JSONObject(responseText) }.getOrElse {
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
}
