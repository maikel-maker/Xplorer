package be.maikel.xplorer

import be.maikel.xplorer.domain.History
import be.maikel.xplorer.domain.Ledger
import be.maikel.xplorer.domain.Op
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Calculs du portefeuille sur des historiques Coinbase synthétiques (montants vérifiables à la main). */
class LedgerTest {
    private val D = 86_400_000L
    private var n = 0
    private fun op(day: Int, type: String, cur: String, amount: Double, eur: Double, fee: Double = 0.0, feeCur: String = "EUR") =
        Op("op${n++}", day * D + 1000, type, cur, amount, eur, fee, feeCur)

    /** 2 virements de 500 €, 3 achats, une vente partielle, une récompense, une conversion USDC. */
    private fun scenario() = listOf(
        op(0, "fiat_deposit", "EUR", 500.0, 500.0),
        op(0, "advanced_trade_fill", "EUR", -300.0, -300.0),
        op(0, "advanced_trade_fill", "BTC", 0.01, 300.0, fee = 1.8),
        op(1, "buy", "EUR", -200.0, -200.0),
        op(1, "buy", "GEOD", 1000.0, 200.0),
        op(30, "fiat_deposit", "EUR", 500.0, 500.0),
        op(31, "advanced_trade_fill", "EUR", -400.0, -400.0),
        op(31, "advanced_trade_fill", "BTC", 0.01, 400.0, fee = 2.4),
        op(40, "advanced_trade_fill", "BTC", -0.005, -250.0, fee = 1.5),
        op(40, "advanced_trade_fill", "EUR", 248.5, 248.5),
        op(45, "staking_reward", "GEOD", 10.0, 2.0),
        op(46, "trade", "EUR", -50.0, -50.0),
        op(46, "trade", "USDC", 54.0, 50.0),
    )

    @Test fun capitalIsOnlyEuroTransfers() {
        val r = Ledger.compute(scenario(), mapOf("BTC" to 0.015, "GEOD" to 1010.0, "EUR" to 298.5, "USDC" to 54.0), mapOf("BTC" to 50000.0, "GEOD" to 0.2), 0.925)
        assertEquals(1000.0, r.capitalNet, 1e-9)
        assertEquals(0.0, r.externalNetEur, 1e-9)
    }

    @Test fun averageCostAndRealized() {
        val r = Ledger.compute(scenario(), mapOf("BTC" to 0.015, "GEOD" to 1010.0, "EUR" to 298.5, "USDC" to 54.0), mapOf("BTC" to 50000.0, "GEOD" to 0.2), 0.925)
        val btc = r.positions.first { it.symbol == "BTC" }
        // coût : 300 + 1,8 + 400 + 2,4 = 704,2 € pour 0,02 BTC → PRU 35 210 €
        // vente de 0,005 : produit 250 − 1,5 = 248,5 ; coût 176,05 → réalisé 72,45
        assertEquals(72.45, btc.realized, 1e-6)
        assertEquals(35210.0, btc.avgCost, 1e-6)
        assertEquals(0.015 * 35210.0, btc.costBasis, 1e-6)
        assertEquals(0.015 * 50000 - 0.015 * 35210, btc.unrealized!!, 1e-6)
        assertEquals(5.7, btc.fees, 1e-9)
        val geod = r.positions.first { it.symbol == "GEOD" }
        // 10 GEOD de récompense à coût zéro : PRU = 200 / 1010
        assertEquals(200.0 / 1010, geod.avgCost, 1e-9)
        assertEquals(10.0, geod.rewardsQty, 1e-9)
        assertEquals(0.0, geod.unknownQty, 1e-9)
    }

    @Test fun totalAndGain() {
        val r = Ledger.compute(scenario(), mapOf("BTC" to 0.015, "GEOD" to 1010.0, "EUR" to 298.5, "USDC" to 54.0), mapOf("BTC" to 50000.0, "GEOD" to 0.2), 0.925)
        val total = 0.015 * 50000 + 1010 * 0.2 + 298.5 + 54 * 0.925
        assertEquals(total, r.totalEur, 1e-6)
        assertEquals(total - 1000.0, r.gain, 1e-6)
        assertTrue(r.issues.isEmpty())
    }

    @Test fun missingHistoryIsFlaggedNotInvented() {
        // 0,02 BTC détenus mais une seule entrée connue de 0,01 : la moitié a un coût inconnu.
        val ops = listOf(op(0, "fiat_deposit", "EUR", 500.0, 500.0), op(0, "buy", "BTC", 0.01, 400.0))
        val r = Ledger.compute(ops, mapOf("BTC" to 0.02), mapOf("BTC" to 50000.0), 0.92)
        val btc = r.positions.first()
        assertEquals(0.01, btc.unknownQty, 1e-12)
        assertEquals(40000.0, btc.avgCost, 1e-9)
        assertEquals(0.01 * 50000 - 400, btc.unrealized!!, 1e-9) // latent seulement sur la part connue
        assertTrue(r.issues.any { it.contains("sans historique") })
    }

    @Test fun externalTransfersAreNotGains() {
        val ops = listOf(op(0, "send", "ETH", 1.0, 2000.0))
        val r = Ledger.compute(ops, mapOf("ETH" to 1.0), mapOf("ETH" to 2500.0), 0.92)
        assertEquals(2000.0, r.externalNetEur, 1e-9)
        assertEquals(500.0, r.gain, 1e-9)
    }

    @Test fun noPriceMeansNoValue() {
        val r = Ledger.compute(emptyList(), mapOf("XYZ" to 5.0), emptyMap(), 0.92)
        val p = r.positions.first()
        assertTrue(!p.priceKnown)
        assertNull(p.unrealized)
        assertTrue(r.issues.any { it.contains("prix introuvable") })
    }

    @Test fun historyEndsOnRealBalancesAndBenchmarkFollowsDeposits() {
        val ops = scenario()
        val now = 60 * D + 5000
        val btc = (0..60).associate { it.toLong() to 30000.0 + it * 300 }
        val geod = (0..60).associate { it.toLong() to 0.2 }
        val bal = mapOf("BTC" to 0.015, "GEOD" to 1010.0, "EUR" to 298.5, "USDC" to 54.0)
        val prices = mapOf("BTC" to btc[60L]!!, "GEOD" to 0.2)
        val h = History.build(ops, bal, mapOf("BTC" to btc, "GEOD" to geod), emptyMap(), 0.925, prices, now)!!
        val last = h.points.last()
        assertEquals(0.015 * btc[60L]!! + 1010 * 0.2 + 298.5 + 54 * 0.925, last.value, 1e-6)
        assertEquals(1000.0, last.capital, 1e-9)
        // Benchmark : 500 € en BTC le jour 0, 500 € le jour 30.
        val expected = (500 / btc[0L]!! + 500 / btc[30L]!!) * btc[60L]!!
        assertEquals(expected, last.benchmark, 1e-6)
        // Depuis le début, le gain = valeur finale − capital.
        val origin = h.periods.last()
        assertEquals(last.value - 1000.0, origin.gainEur, 1e-6)
    }

    @Test fun startDateResetsCapitalAndPercentagesStayBounded() {
        val ops = scenario()
        val now = 60 * D + 5000
        val btc = (0..60).associate { it.toLong() to 30000.0 + it * 300 }
        val geod = (0..60).associate { it.toLong() to 0.2 }
        val bal = mapOf("BTC" to 0.015, "GEOD" to 1010.0, "EUR" to 298.5, "USDC" to 54.0)
        val prices = mapOf("BTC" to btc[60L]!!, "GEOD" to 0.2)
        val h = History.build(ops, bal, mapOf("BTC" to btc, "GEOD" to geod), emptyMap(), 0.925, prices, now, startMs = 20 * D)!!
        val first = h.points.first()
        assertEquals(20 * D, first.t)
        assertEquals(first.value, h.startValue!!, 1e-9)
        // capital = valeur au départ + le virement de 500 € du jour 30
        assertEquals(first.value + 500.0, h.points.last().capital, 1e-6)
        val origin = h.periods.last()
        assertTrue(origin.label.startsWith("Depuis le"))
        assertEquals(h.points.last().value - first.value - 500.0, origin.gainEur, 1e-6)
        h.periods.forEach { assertTrue("${it.label} ${it.pct}", it.pct.isNaN() || it.pct > -1.0) }
    }
}
