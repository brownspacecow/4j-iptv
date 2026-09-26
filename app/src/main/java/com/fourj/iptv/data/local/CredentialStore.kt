package com.fourj.iptv.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.fourj.iptv.domain.model.ProviderProfile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Serializable
private data class StoredProfile(
    val baseUrl: String,
    val username: String,
    val password: String,
)

/**
 * Persists the provider profile encrypted at rest.
 *
 * The key is generated inside the Android Keystore and is non-exportable, so the ciphertext in
 * SharedPreferences is useless to anything that reads the file off the device - including a
 * backup, which is why app backup is disabled in the manifest.
 *
 * AES-GCM is used rather than CBC: it authenticates as well as encrypts, so a tampered blob fails
 * to decrypt instead of silently yielding garbage.
 */
class CredentialStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val json = Json { ignoreUnknownKeys = true }

    fun save(profile: ProviderProfile) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, obtainKey())

        val payload = json.encodeToString(
            StoredProfile.serializer(),
            StoredProfile(profile.baseUrl, profile.username, profile.password),
        )
        val ciphertext = cipher.doFinal(payload.toByteArray(Charsets.UTF_8))

        prefs.edit()
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(KEY_DATA, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .apply()
    }

    /** Null when nothing is stored, or when it cannot be decrypted - for example after a restore. */
    fun load(): ProviderProfile? {
        val encodedIv = prefs.getString(KEY_IV, null) ?: return null
        val encodedData = prefs.getString(KEY_DATA, null) ?: return null

        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                obtainKey(),
                GCMParameterSpec(TAG_LENGTH_BITS, Base64.decode(encodedIv, Base64.NO_WRAP)),
            )
            val plaintext = cipher.doFinal(Base64.decode(encodedData, Base64.NO_WRAP))
            val stored = json.decodeFromString(
                StoredProfile.serializer(),
                plaintext.toString(Charsets.UTF_8),
            )
            ProviderProfile(stored.baseUrl, stored.username, stored.password)
        }.getOrNull()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun obtainKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(KEY_SIZE_BITS)
                    // Deliberately not requiring user authentication: a provider that cannot be
                    // reached because the device is locked is worse than the threat this guards.
                    .setUserAuthenticationRequired(false)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val PREFS_NAME = "fourj_credentials"
        const val KEY_IV = "iv"
        const val KEY_DATA = "data"
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "fourj_provider_credentials"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_SIZE_BITS = 256
        const val TAG_LENGTH_BITS = 128
    }
}
