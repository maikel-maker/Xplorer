package be.maikel.xplorer.data

import be.maikel.xplorer.domain.Candle
import be.maikel.xplorer.domain.Op
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import java.util.concurrent.TimeUnit

class ApiError(msg: String, val code: Int = 0) : Exception(msg)

/**
 * Accès Coinbase en LECTURE SEULE : cette classe ne contient que des requêtes GET.
 * Aucun endpoint d'ordre, de retrait ou de transfert n'est appelé (vérifié aussi en CI).
 */
class CoinbaseApi(private val client: OkHttpClient = defaultClient()) {

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        private const val BASE = "https://api.coinbase.com"
        val json = Json { ignoreUnknownKeys = true }
    }

    private suspend fun get(pathAndQuery: String, key: CoinbaseAuth.Key?): JsonElement = withContext<JsonElement>(Dispatchers.IO) {
        var attempt = 0
        while (true) {
            val path = pathAndQuery.substringBefore('?')
            val rb = Request.Builder().url(BASE + pathAndQuery).header("Accept", "application/json")
            if (key != null) {
                rb.header("Authorization", "Bearer " + CoinbaseAuth.jwt(key, "GET", path))
                if (path.startsWith("/v2/")) rb.header("CB-VERSION", "2024-01-01")
            }
            val resp = try { client.newCall(rb.build()).execute() } catch (e: Exception) {
                throw ApiError("Pas de connexion à Coinbase (${e.javaClass.simpleName}).")
            }
            resp.use { r ->
                val body = r.body?.string() ?: ""
                when {
                    r.isSuccessful -> return@withContext json.parseToJsonElement(body)
                    r.code == 429 && attempt < 3 -> { attempt++; delay(1500L * attempt) }
                    r.code == 401 -> throw ApiError("Coinbase refuse la clé (401). Vérifie qu'elle est en ECDSA, avec la permission View, et que l'heure du téléphone est correcte.", 401)
                    r.code == 403 -> throw ApiError("Accès refusé par Coinbase (403) : la clé n'a pas la permission View sur ce portefeuille.", 403)
                    else -> throw ApiError("Coinbase a répondu ${r.code} sur $path.", r.code)
                }
            }
        }
        @Suppress("UNREACHABLE_CODE")
        throw IllegalStateException("inaccessible")
    }

    // ---------- utilitaires JSON tolérants ----------
    private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
    private fun JsonObject?.s(k: String): String? = (this?.get(k) as? JsonPrimitive)?.contentOrNull
    private fun JsonObject?.d(k: String): Double = s(k)?.toDoubleOrNull() ?: 0.0
    private fun JsonObject?.o(k: String): JsonObject? = this?.get(k) as? JsonObject
    private fun JsonObject?.a(k: String): JsonArray? = this?.get(k) as? JsonArray
    private fun currencyCode(e: JsonElement?): String = when (e) {
        is JsonPrimitive -> e.contentOrNull ?: ""
        is JsonObject -> e.s("code") ?: ""
        else -> ""
    }

    data class AccountV2(val id: String, val currency: String, val balance: Double, val createdAt: Long, val updatedAt: Long)

    suspend fun accounts(key: CoinbaseAuth.Key): List<AccountV2> {
        val out = mutableListOf<AccountV2>()
        var next: String? = "/v2/accounts?limit=100"
        var pages = 0
        while (next != null && pages++ < 50) {
            val j = get(next, key).obj()
            for (a in j.a("data") ?: JsonArray(emptyList())) {
                val o = a.obj() ?: continue
                out += AccountV2(
                    o.s("id") ?: continue,
                    currencyCode(o?.get("currency")).uppercase(),
                    o.o("balance").d("amount"),
                    parseTime(o.s("created_at")),
                    parseTime(o.s("updated_at")),
                )
            }
            next = j.o("pagination").s("next_uri")?.takeIf { it.isNotBlank() }
        }
        return out
    }

    /**
     * Transactions d'un compte, de la plus récente à la plus ancienne.
     * S'arrête dès qu'une transaction déjà connue est rencontrée (synchronisation incrémentale).
     */
    suspend fun transactions(key: CoinbaseAuth.Key, accountId: String, accountCur: String, known: (String) -> Boolean): List<Op> {
        val out = mutableListOf<Op>()
        var next: String? = "/v2/accounts/$accountId/transactions?limit=100&order=desc"
        var pages = 0
        loop@ while (next != null && pages++ < 200) {
            val j = get(next, key).obj()
            for (e in j.a("data") ?: JsonArray(emptyList())) {
                val o = e.obj() ?: continue
                val id = o.s("id") ?: continue
                if (known(id)) break@loop
                val status = o.s("status") ?: "completed"
                if (status != "completed") continue
                parseTx(o, accountId, accountCur)?.let { out += it }
            }
            next = j.o("pagination").s("next_uri")?.takeIf { it.isNotBlank() }
        }
        return out
    }

    private fun parseTx(o: JsonObject, accountId: String, accountCur: String): Op? {
        val amountObj = o.o("amount")
        val amount = amountObj.d("amount")
        if (amount == 0.0) return null
        val cur = currencyCode(amountObj?.get("currency")).uppercase().ifEmpty { accountCur }
        val native = o.o("native_amount")
        var eur = native.d("amount")
        val nativeCur = currencyCode(native?.get("currency")).uppercase()
        if (nativeCur.isNotEmpty() && nativeCur != "EUR") eur = Double.NaN // converti plus tard
        val type = o.s("type") ?: "tx"
        var fee = 0.0; var feeCur = ""; var unit = Double.NaN; var priceCur = ""; var product = ""; var order = ""
        o.o("advanced_trade_fill")?.let { f ->
            product = f.s("product_id") ?: ""
            order = f.s("order_id") ?: ""
            unit = f.d("fill_price").takeIf { it > 0 } ?: Double.NaN
            fee = f.d("commission")
            priceCur = product.substringAfter('-', "")
            feeCur = priceCur
        }
        for (k in listOf("buy", "sell")) o.o(k)?.let { b ->
            b.o("fee")?.let { fee = it.d("amount"); feeCur = currencyCode(it["currency"]).uppercase() }
        }
        val title = o.o("details").s("title") ?: o.s("description") ?: ""
        return Op(
            id = o.s("id")!!, t = parseTime(o.s("created_at")), type = type, cur = cur, amount = amount,
            eur = eur, fee = fee, feeCur = feeCur, unitPrice = unit, priceCur = priceCur,
            productId = product, orderId = order, title = title, accountId = accountId,
        )
    }

    data class Fill(val id: String, val t: Long, val productId: String, val buy: Boolean, val price: Double, val size: Double, val commission: Double, val orderId: String)

    /** Exécutions réelles des ordres Advanced (pas les ordres annulés). */
    suspend fun fills(key: CoinbaseAuth.Key): List<Fill> {
        val out = mutableListOf<Fill>()
        var cursor = ""
        var pages = 0
        while (pages++ < 40) {
            val q = "/api/v3/brokerage/orders/historical/fills?limit=250" + if (cursor.isNotEmpty()) "&cursor=$cursor" else ""
            val j = get(q, key).obj()
            for (e in j.a("fills") ?: JsonArray(emptyList())) {
                val f = e.obj() ?: continue
                val price = f.d("price"); var size = f.d("size")
                if (price <= 0 || size <= 0) continue
                if (f.s("size_in_quote") == "true") size /= price
                out += Fill(f.s("entry_id") ?: f.s("trade_id") ?: continue, parseTime(f.s("trade_time")), f.s("product_id") ?: "",
                    f.s("side") == "BUY", price, size, f.d("commission"), f.s("order_id") ?: "")
            }
            cursor = j.s("cursor") ?: ""
            if (cursor.isEmpty()) break
        }
        return out
    }

    // ---------- données publiques (sans clé) ----------

    /** Prix actuels en euros de toutes les devises Coinbase : 1 / taux. */
    suspend fun eurPrices(): Map<String, Double> {
        val rates = get("/v2/exchange-rates?currency=EUR", null).obj().o("data").o("rates") ?: return emptyMap()
        val out = HashMap<String, Double>()
        for ((k, v) in rates) {
            val r = (v as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: continue
            if (r > 0) out[k.uppercase()] = 1.0 / r
        }
        return out
    }

    data class Product(val id: String, val base: String, val quote: String, val price: Double, val change24: Double?, val volume24: Double, val online: Boolean)

    suspend fun products(): List<Product> {
        val j = get("/api/v3/brokerage/market/products?product_type=SPOT", null).obj()
        return (j.a("products") ?: JsonArray(emptyList())).mapNotNull { e ->
            val p = e.obj() ?: return@mapNotNull null
            val id = p.s("product_id") ?: return@mapNotNull null
            Product(id, (p.s("base_currency_id") ?: id.substringBefore('-')).uppercase(), (p.s("quote_currency_id") ?: id.substringAfter('-')).uppercase(),
                p.d("price"), p.s("price_percentage_change_24h")?.toDoubleOrNull()?.div(100), p.d("volume_24h"),
                p.s("status") == "online" && p.s("trading_disabled") != "true")
        }
    }

    enum class Gran(val api: String, val seconds: Long) {
        M1("ONE_MINUTE", 60), M5("FIVE_MINUTE", 300), M15("FIFTEEN_MINUTE", 900), M30("THIRTY_MINUTE", 1800),
        H1("ONE_HOUR", 3600), H4("FOUR_HOUR", 14400), D1("ONE_DAY", 86400)
    }

    /** Bougies publiques (350 maximum par requête), triées de la plus ancienne à la plus récente. */
    suspend fun candles(productId: String, g: Gran, startSec: Long, endSec: Long): List<Candle> {
        val out = mutableListOf<Candle>()
        var end = endSec
        var guard = 0
        while (end > startSec && guard++ < 12) {
            val start = maxOf(startSec, end - g.seconds * 349)
            val j = get("/api/v3/brokerage/market/products/$productId/candles?granularity=${g.api}&start=$start&end=$end", null).obj()
            val arr = j.a("candles") ?: break
            if (arr.isEmpty()) break
            for (e in arr) {
                val c = e.obj() ?: continue
                out += Candle((c.s("start")?.toLongOrNull() ?: continue) * 1000, c.d("open"), c.d("high"), c.d("low"), c.d("close"), c.d("volume"))
            }
            end = start
        }
        return out.distinctBy { it.t }.sortedBy { it.t }
    }

    private fun parseTime(s: String?): Long = try { if (s == null) 0L else Instant.parse(s).toEpochMilli() } catch (e: Exception) { 0L }
}
