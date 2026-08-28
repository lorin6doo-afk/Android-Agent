package si.sopotnik

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("sopotnik", Context.MODE_PRIVATE)

    var backendUrl: String
        get() = sp.getString("backend_url", "") ?: ""
        set(v) = sp.edit().putString("backend_url", v.trim()).apply()

    /** Žeton za backend — šifriran z AES-GCM ključem iz Android Keystore. */
    var token: String
        get() = sp.getString("token_enc", null)?.let { runCatching { decrypt(it) }.getOrDefault("") } ?: ""
        set(v) = sp.edit().putString("token_enc", encrypt(v.trim())).apply()

    var homeAddress: String
        get() = sp.getString("home_address", "") ?: ""
        set(v) = sp.edit().putString("home_address", v.trim()).apply()

    var workAddress: String
        get() = sp.getString("work_address", "") ?: ""
        set(v) = sp.edit().putString("work_address", v.trim()).apply()

    /** Rumena potrditev za klice (privzeto vklopljena). */
    var confirmCalls: Boolean
        get() = sp.getBoolean("confirm_calls", true)
        set(v) = sp.edit().putBoolean("confirm_calls", v).apply()

    /** Po odgovoru samodejno odpri kratko okno poslušanja. */
    var followUp: Boolean
        get() = sp.getBoolean("follow_up", true)
        set(v) = sp.edit().putBoolean("follow_up", v).apply()

    // ---- Keystore šifriranje ----

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return kg.generateKey()
    }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val (ivB64, ctB64) = stored.split(":", limit = 2)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(ivB64, Base64.NO_WRAP)))
        return String(c.doFinal(Base64.decode(ctB64, Base64.NO_WRAP)), Charsets.UTF_8)
    }

    private companion object {
        const val ALIAS = "sopotnik-prefs"
    }
}
