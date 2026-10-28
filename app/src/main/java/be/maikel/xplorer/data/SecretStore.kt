package be.maikel.xplorer.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Coffre-fort de la clé Coinbase : chiffrement AES-256-GCM avec une clé générée et gardée
 * par l'Android Keystore (elle ne peut pas être extraite du téléphone).
 */
class SecretStore(context: Context) {
    private val prefs = context.getSharedPreferences("xp_secure", Context.MODE_PRIVATE)
    private val alias = "xplorer_coinbase_v1"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun save(key: CoinbaseAuth.Key) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val plain = (key.name + "\u0000" + key.pem).toByteArray()
        val ct = c.doFinal(plain)
        prefs.edit()
            .putString("iv", Base64.encodeToString(c.iv, Base64.NO_WRAP))
            .putString("ct", Base64.encodeToString(ct, Base64.NO_WRAP))
            .apply()
    }

    fun load(): CoinbaseAuth.Key? {
        val iv = prefs.getString("iv", null) ?: return null
        val ct = prefs.getString("ct", null) ?: return null
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            val s = String(c.doFinal(Base64.decode(ct, Base64.NO_WRAP)))
            val i = s.indexOf('\u0000')
            CoinbaseAuth.Key(s.substring(0, i), s.substring(i + 1))
        } catch (e: Exception) { null }
    }

    fun has() = prefs.contains("ct")

    fun clear() {
        prefs.edit().clear().apply()
        try { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias) } catch (e: Exception) { }
    }
}
