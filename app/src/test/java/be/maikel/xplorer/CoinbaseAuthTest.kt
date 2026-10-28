package be.maikel.xplorer

import be.maikel.xplorer.data.CoinbaseAuth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

class CoinbaseAuthTest {
    // Clé jetable générée pour ce test uniquement (openssl ecparam -name prime256v1).
    private val sec1 = """-----BEGIN EC PRIVATE KEY-----
MHcCAQEEIMYF5xjSqDsL52b5lk+1NuBEL5JJDPcqq7foZoKI795FoAoGCCqGSM49
AwEHoUQDQgAEE+QP8q+OtEGfmBa6F/4HL55mOriJ5uRiafT5RbxeNbEajWM638V6
km4S3llkJzmZWLdXhIddaATr3inoY8TbCw==
-----END EC PRIVATE KEY-----"""
    private val pub = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEE+QP8q+OtEGfmBa6F/4HL55mOriJ5uRiafT5RbxeNbEajWM638V6km4S3llkJzmZWLdXhIddaATr3inoY8TbCw=="
    private val name = "organizations/test-org/apiKeys/test-key"

    private fun verify(jwt: String, pk: PublicKey): Boolean {
        val parts = jwt.split(".")
        val raw = Base64.getUrlDecoder().decode(parts[2])
        assertEquals(64, raw.size)
        val r = BigInteger(1, raw.copyOfRange(0, 32)).toByteArray()
        val s = BigInteger(1, raw.copyOfRange(32, 64)).toByteArray()
        fun int(b: ByteArray) = byteArrayOf(0x02, b.size.toByte()) + b
        val body = int(r) + int(s)
        val der = byteArrayOf(0x30, body.size.toByte()) + body
        return Signature.getInstance("SHA256withECDSA").run { initVerify(pk); update("${parts[0]}.${parts[1]}".toByteArray()); verify(der) }
    }

    @Test fun sec1KeySignsValidJwt() {
        val key = CoinbaseAuth.parse(name, sec1)
        val jwt = CoinbaseAuth.jwt(key, "GET", "/v2/accounts", nowSec = 1_700_000_000)
        val payload = String(Base64.getUrlDecoder().decode(jwt.split(".")[1]))
        assertTrue(payload.contains("\"uri\":\"GET api.coinbase.com/v2/accounts\""))
        assertTrue(payload.contains("\"iss\":\"cdp\""))
        val pk = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(pub)))
        repeat(20) { assertTrue(verify(CoinbaseAuth.jwt(key, "GET", "/v2/accounts"), pk)) }
    }

    @Test fun jsonFileIsAccepted() {
        val json = """{"name":"$name","privateKey":"${sec1.replace("\n", "\\n")}"}"""
        val key = CoinbaseAuth.parse("", json)
        assertEquals(name, key.name)
    }

    @Test fun pkcs8KeyWorks() {
        val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(kp.private.encoded) + "\n-----END PRIVATE KEY-----"
        val key = CoinbaseAuth.parse(name, pem)
        assertTrue(verify(CoinbaseAuth.jwt(key, "GET", "/api/v3/brokerage/accounts"), kp.public))
    }

    @Test(expected = CoinbaseAuth.KeyError::class) fun badNameRejected() { CoinbaseAuth.parse("ma-cle", sec1) }
}
