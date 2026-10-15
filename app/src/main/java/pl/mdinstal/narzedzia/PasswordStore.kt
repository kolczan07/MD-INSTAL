package pl.mdinstal.narzedzia

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object PasswordStore {
    private const val FILE = "mdinstal_secure_prefs"
    private var prefs: SharedPreferences? = null

    private fun get(context: Context): SharedPreferences {
        prefs?.let { return it }
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val p = EncryptedSharedPreferences.create(
            context,
            FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        prefs = p
        return p
    }

    fun save(context: Context, key: String, value: String) {
        get(context).edit().putString(key, value).apply()
    }

    fun load(context: Context, key: String): String? {
        return get(context).getString(key, null)
    }

    fun delete(context: Context, key: String) {
        get(context).edit().remove(key).apply()
    }

    fun has(context: Context, key: String): Boolean {
        return get(context).contains(key)
    }
}
