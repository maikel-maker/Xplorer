package be.maikel.xplorer

import be.maikel.xplorer.domain.Candle
import be.maikel.xplorer.domain.SmartDca
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

class SmartDcaTest {
    private val D = 86_400_000L
    private val targets = mapOf("BTC" to 0.5, "ETH" to 0.5)
    private val maxW = mapOf("BTC" to 1.0, "ETH" to 1.0)
    private val values = mapOf("BTC" to 500.0, "ETH" to 500.0)

    private fun series(n: Int, f: (Int) -> Double) = (0 until n).map { i -> val c = f(i); Candle(i * D, c, c, c, c, 1.0) }

    @Test fun bands() {
        assertEquals(2.0, SmartDca.multiplier(0.7), 0.0)
        assertEquals(1.5, SmartDca.multiplier(0.95), 0.0)
        assertEquals(1.0, SmartDca.multiplier(1.47), 0.0)
        assertEquals(0.5, SmartDca.multiplier(2.0), 0.0)
        assertEquals(0.0, SmartDca.multiplier(3.0), 0.0)
        assertEquals(1.0, SmartDca.multiplier(null), 0.0) // pas assez d'historique : DCA normal
    }

    @Test fun mayerNeeds200Days() {
        assertNull(SmartDca.mayer(series(150) { 100.0 }, 100.0, 150 * D))
        val mm = SmartDca.mayer(series(300) { 100.0 }, 150.0, 300 * D)
        assertNotNull(mm)
        assertTrue(mm!! > 1.4 && mm < 1.5)
    }

    private fun conserved(p: SmartDca.Plan) =
        assertEquals(p.weekly + p.fromReserve - p.toReserve, p.total, 1.0)

    @Test fun normalPricesMeanNormalDca() {
        val p = SmartDca.plan(200.0, 0.0, 0, mapOf("BTC" to 1.2, "ETH" to 1.2), targets, values, maxW, 1000.0)
        assertEquals(200.0, p.total, 1.0)
        assertEquals(0.0, p.toReserve, 1.0)
        conserved(p)
    }

    @Test fun expensiveSendsMoneyToReserve() {
        val p = SmartDca.plan(200.0, 0.0, 0, mapOf("BTC" to 2.0, "ETH" to 2.0), targets, values, maxW, 1000.0)
        assertEquals(100.0, p.total, 1.0)
        assertEquals(100.0, p.toReserve, 1.0)
        conserved(p)
        val none = SmartDca.plan(200.0, 0.0, 0, mapOf("BTC" to 3.0, "ETH" to 3.0), targets, values, maxW, 1000.0)
        assertEquals(0.0, none.total, 0.5)
        assertEquals(200.0, none.toReserve, 1.0)
    }

    @Test fun cheapUsesReserveButNeverMoreThanIt() {
        val p = SmartDca.plan(200.0, 500.0, 7, mapOf("BTC" to 0.7, "ETH" to 0.7), targets, values, maxW, 1000.0)
        assertEquals(400.0, p.total, 1.0)
        assertEquals(200.0, p.fromReserve, 1.0)
        conserved(p)
        val short = SmartDca.plan(200.0, 50.0, 7, mapOf("BTC" to 0.7, "ETH" to 0.7), targets, values, maxW, 1000.0)
        assertEquals(250.0, short.total, 1.0)
        conserved(short)
    }

    @Test fun reserveNeverSleepsMoreThan4Weeks() {
        val p = SmartDca.plan(200.0, 300.0, 28, mapOf("BTC" to 2.0, "ETH" to 3.0), targets, values, maxW, 1000.0)
        assertTrue(p.forced)
        assertEquals(500.0, p.total, 1.0)
        assertEquals(0.0, p.reserveEnd, 1.0)
    }

    @Test fun laggingAssetGetsTheMoney() {
        // BTC très au-dessus de sa cible : tout va vers ETH.
        val p = SmartDca.plan(200.0, 0.0, 0, mapOf("BTC" to 1.2, "ETH" to 1.2), targets, mapOf("BTC" to 900.0, "ETH" to 100.0), maxW, 1000.0)
        assertEquals(0.0, p.lines.first { it.symbol == "BTC" }.eur, 0.5)
        assertEquals(200.0, p.lines.first { it.symbol == "ETH" }.eur, 1.0)
    }

    @Test fun flatMarketGivesSameResultAsDca() {
        val b = SmartDca.backtest("X", series(600) { 100.0 }, 100.0)!!
        assertEquals(0.0, b.edge, 1e-9)
    }

    @Test fun bubbleThenCrashFavoursSmartDca() {
        // Bulle (prix bien au-dessus de sa moyenne : on met de côté) puis krach (on rachète plus bas).
        val b = SmartDca.backtest("X", series(700) { i ->
            when {
                i < 300 -> 100.0
                i < 380 -> 100.0 + (i - 300) * 2.5
                i < 400 -> 300.0 - (i - 380) * 12
                else -> 60.0
            }
        }, 100.0)!!
        assertTrue("avance ${b.edge}", b.edge > 0)
        assertTrue(abs(b.invested - b.weeks * 100.0) < 1e-6)
        assertTrue(abs(b.valueSmart - (b.unitsSmart * 60.0 + b.cashLeft)) < 1e-6)
    }

    @Test fun mildCyclesChangeAlmostNothing() {
        // Sans phase nettement chère, la réserve ne se remplit pas : on retombe sur le DCA simple.
        val b = SmartDca.backtest("X", series(1000) { 100.0 * (1 + 0.5 * sin(it / 60.0)) + it * 0.05 }, 100.0)!!
        assertEquals(0.0, b.edge, 1e-9)
    }
}
