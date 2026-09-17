package com.motion.browser.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Secret storage for Motion Browser (spec §63: never log or persist keys/tokens/passwords/cookies
 * outside this store; never log the VALUES stored here).
 *
 * Primary storage: EncryptedSharedPreferences backed by the Android Keystore
 * (MasterKey AES256-GCM, pref keys AES256-SIV, pref values AES256-GCM), file "motion_secrets".
 *
 * Honest degradation (spec §77): if Keystore/EncryptedSharedPreferences initialization fails
 * (rare: corrupted keystore, locked device profile), we fall back to PLAIN SharedPreferences
 * under a different file name so the app remains functional, and we log an explicit SECURITY
 * warning so the degradation is visible. We never silently pretend data is encrypted.
 *
 * Contract (ARCHITECTURE.md §3.5) — exact signatures:
 *   class SecretStore(context: Context) { put(key, value); get(key): String?; delete(key); keys(): List<String> }
 */
class SecretStore(context: Context) {

    /** True when running on the unencrypted fallback (UI may surface this to the user). */
    val usingFallback: Boolean
        get() = fallback

    private val prefs: SharedPreferences
    private var fallback: Boolean = false

    init {
        val appContext = context.applicationContext
        prefs = try {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appContext,
                FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (t: Throwable) {
            // SECURITY: honest degradation. Log only the failure class + message of the Keystore
            // layer (no secret material is involved in this message). Values are stored in plain
            // SharedPreferences until the user clears data — surfaced via [usingFallback].
            fallback = true
            Log.w(
                TAG,
                "SECURITY: EncryptedSharedPreferences init failed " +
                    "(${t.javaClass.simpleName}: ${t.message}). Falling back to PLAIN " +
                    "SharedPreferences ('${FALLBACK_FILE_NAME}') — secrets are NOT encrypted " +
                    "on this device. Motion stays functional but the user should be informed."
            )
            appContext.getSharedPreferences(FALLBACK_FILE_NAME, Context.MODE_PRIVATE)
        }
    }

    /** Store a secret. NEVER call Log with [value]. */
    fun put(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    /** Read a secret or null. NEVER call Log with the returned value. */
    fun get(key: String): String? = prefs.getString(key, null)

    /** Delete a secret. */
    fun delete(key: String) {
        prefs.edit().remove(key).apply()
    }

    /** List stored secret key names (key names are not secret material; values are never returned here). */
    fun keys(): List<String> = prefs.all.keys.toList()

    companion object {
        private const val TAG = "MotionSecurity"
        const val FILE_NAME = "motion_secrets"
        const val FALLBACK_FILE_NAME = "motion_secrets_plain"
    }
}
