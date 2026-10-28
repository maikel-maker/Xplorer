package be.maikel.xplorer.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * Authentification Coinbase (clé CDP, algorithme ECDSA, JWT ES256).
 * La clé ne quitte jamais le téléphone : elle sert uniquement à signer localement chaque requête GET.
 */
object CoinbaseAuth {
    const val HOST = "api.coinbase.com"

    data class Key(val name: String, val pem: String)

    class KeyError(msg: String) : Exception(msg)

    /** Accepte le fichier JSON téléchargé chez Coinbase, ou le nom et la clé privée séparés. */
    fun parse(nameInput: String, keyInput: String): Key {
        var name = nameInput.trim()
        var pem = keyInput.trim()
        val json = tryJson(pem) ?: tryJson(name)
        if (json != null) {
            json["name"]?.jsonPrimitive?.content?.let { name = it }
            json["privateKey"]?.jsonPrimitive?.content?.let { pem = it }
        }
        pem = pem.replace("\\n", "\n").trim()
        if (!pem.contains("PRIVATE KEY")) throw KeyError("La clé privée doit commencer par -----BEGIN EC PRIVATE KEY-----.")
        if (!name.contains("apiKeys/")) throw KeyError("Le nom de la clé doit ressembler à organizations/…/apiKeys/….")
        privateKey(pem) // vérifie que la clé est lisible
        return Key(name, pem)
    }

    private fun tryJson(s: String): JsonObject? = try {
        if (s.startsWith("{")) Json.parseToJsonElement(s) as? JsonObject else null
    } catch (e: Exception) { null }

    fun privateKey(pem: String): PrivateKey {
        val body = pem.lines().filter { !it.startsWith("-----") }.joinToString("").replace("\\s".toRegex(), "")
        val der = try { Base64.getDecoder().decode(body) } catch (e: Exception) { throw KeyError("Clé privée illisible (base64 invalide).") }
        val pkcs8 = when {
            pem.contains("BEGIN EC PRIVATE KEY") -> sec1ToPkcs8(der)
            pem.contains("BEGIN PRIVATE KEY") -> der
            else -> throw KeyError("Format de clé non reconnu. Crée la clé Coinbase avec l'algorithme ECDSA.")
        }
        return try {
            KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pkcs8))
        } catch (e: Exception) {
            throw KeyError("Clé refusée : ce n'est pas une clé ECDSA P-256. Ed25519 n'est pas accepté par Coinbase App.")
        }
    }

    /** Enveloppe une clé SEC1 (« EC PRIVATE KEY ») dans une structure PKCS#8 pour P-256. */
    fun sec1ToPkcs8(sec1: ByteArray): ByteArray {
        val algId = byteArrayOf(
            0x30, 0x13,
            0x06, 0x07, 0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x02, 0x01,
            0x06, 0x08, 0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x03, 0x01, 0x07,
        )
        val version = byteArrayOf(0x02, 0x01, 0x00)
        val octet = der(0x04, sec1)
        return der(0x30, version + algId + octet)
    }

    private fun der(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val n = content.size
        when {
            n < 0x80 -> out.write(n)
            n < 0x100 -> { out.write(0x81); out.write(n) }
            else -> { out.write(0x82); out.write(n shr 8); out.write(n and 0xFF) }
        }
        out.write(content)
        return out.toByteArray()
    }

    private val b64 = Base64.getUrlEncoder().withoutPadding()

    fun jwt(key: Key, method: String, path: String, nowSec: Long = System.currentTimeMillis() / 1000, random: SecureRandom = SecureRandom()): String {
        val nonce = ByteArray(16).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val header = """{"alg":"ES256","kid":${q(key.name)},"nonce":"$nonce","typ":"JWT"}"""
        val payload = """{"sub":${q(key.name)},"iss":"cdp","nbf":$nowSec,"exp":${nowSec + 120},"uri":${q("$method $HOST$path")}}"""
        val input = b64.encodeToString(header.toByteArray()) + "." + b64.encodeToString(payload.toByteArray())
        val sig = Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey(key.pem))
            update(input.toByteArray())
            sign()
        }
        return input + "." + b64.encodeToString(derToRaw(sig))
    }

    private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** Signature ECDSA DER → format brut r||s (64 octets) exigé par JWT ES256. */
    fun derToRaw(der: ByteArray): ByteArray {
        var i = 2
        if (der[1].toInt() and 0x80 != 0) i = 2 + (der[1].toInt() and 0x7F)
        fun readInt(): ByteArray {
            require(der[i].toInt() == 0x02)
            val len = der[i + 1].toInt() and 0xFF
            val v = der.copyOfRange(i + 2, i + 2 + len)
            i += 2 + len
            val trimmed = v.dropWhile { it.toInt() == 0 }.toByteArray()
            return ByteArray(32 - trimmed.size) + trimmed
        }
        val r = readInt(); val s = readInt()
        return r + s
    }
}
