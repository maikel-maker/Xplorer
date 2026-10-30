package be.maikel.xplorer

import be.maikel.xplorer.domain.Arena
import be.maikel.xplorer.domain.Candle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class ArenaTest {
    private val D = 86_400_000L
    private fun series(n: Int, f: (Int) -> Double) = (0 until n).map { i -> val c = f(i); Candle(i * D, c, c, c, c, 1.0) }

    private val targets = mapOf("BTC" to 0.6, "ETH" to 0.3, "LINK" to 0.1)
    private val maxW = mapOf("BTC" to 1.0, "ETH" to 1.0, "LINK" to 1.0)

    @Test fun sixAgentsAlwaysVote() {
        val d = Arena.debate("BTC", series(400) { 100.0 + it })
        assertEquals(6, d.votes.size)
        assertTrue(d.score >= -1.0 && d.score <= 1.0)
    }

    @Test fun steadyRiseMakesBullsWin() {
        // Hausse régulière et modérée : tendance haussière sans surchauffe extrême.
        val d = Arena.debate("BTC", series(400) { 100.0 * Math.pow(1.002, it.toDouble()) + 3 * sin(it / 3.0) })
        assertTrue("score ${d.score}", d.score > 0)
        assertEquals(Arena.Camp.BOEUF, d.votes.first { it.agent == Arena.BRUTUS }.camp)
    }

    @Test fun longCrashMakesBearsTrendVote() {
        val d = Arena.debate("BTC", series(400) { 1000.0 * Math.pow(0.996, it.toDouble()) })
        assertEquals(Arena.Camp.OURS, d.votes.first { it.agent == Arena.BRUTUS }.camp)
        // Roxane voit des soldes dans la chute.
        assertEquals(Arena.Camp.BOEUF, d.votes.first { it.agent == Arena.ROXANE }.camp)
    }

    @Test fun shortHistoryAgentsAbstain() {
        val d = Arena.debate("GEOD", series(40) { 0.2 })
        assertTrue(d.votes.first { it.agent == Arena.BRUTUS }.abstain)
        assertTrue(d.votes.first { it.agent == Arena.OCTAVE }.abstain)
        assertTrue(!d.votes.first { it.agent == Arena.TORTELLA }.abstain)
        val empty = Arena.debate("XXX", emptyList())
        assertEquals(0.0, empty.score, 0.0)
        assertEquals(1.0, empty.multiplier, 0.0)
    }

    @Test fun multiplierIsBounded() {
        val d = Arena.debate("BTC", series(400) { 100.0 * Math.pow(1.01, it.toDouble()) })
        assertTrue(d.multiplier <= 1.5 && d.multiplier >= 0.5)
    }

    @Test fun planSpendsExactlyTheBudgetInWholeEuros() {
        val debates = mapOf(
            "BTC" to Arena.debate("BTC", series(400) { 100.0 + it }),
            "ETH" to Arena.debate("ETH", series(400) { 500.0 - it }),
            "LINK" to Arena.debate("LINK", series(400) { 10.0 + sin(it / 5.0) }),
        )
        val p = Arena.plan(225.0, debates, targets, emptyMap(), maxW, 0.0)
        assertEquals(225.0, p.total, 0.0)
        p.lines.forEach { l ->
            assertEquals(l.eur, Math.floor(l.eur), 0.0)
            assertTrue(l.eur == 0.0 || l.eur >= Arena.MIN_ORDER)
        }
    }

    @Test fun planWithoutDebatesFollowsTargets() {
        val p = Arena.plan(225.0, emptyMap(), targets, emptyMap(), maxW, 0.0)
        assertEquals(225.0, p.total, 0.0)
        assertEquals(135.0, p.lines.first { it.symbol == "BTC" }.eur, 1.0)
        assertEquals(68.0, p.lines.first { it.symbol == "ETH" }.eur, 1.0)
    }

    @Test fun planSkipsCryptoAboveItsLimit() {
        val values = mapOf("BTC" to 900.0, "ETH" to 50.0, "LINK" to 50.0)
        val p = Arena.plan(225.0, emptyMap(), targets, values, mapOf("BTC" to 0.5, "ETH" to 1.0, "LINK" to 1.0), 1000.0)
        assertEquals(0.0, p.lines.first { it.symbol == "BTC" }.eur, 0.0)
        assertEquals(225.0, p.total, 0.0)
    }

    @Test fun roundingKeepsTheTotal() {
        val r = Arena.roundTo(225.0, mapOf("A" to 1.0 / 3, "B" to 1.0 / 3, "C" to 1.0 / 3))
        assertEquals(225.0, r.values.sum(), 0.0)
    }

    @Test fun backtestUsesNoFutureData() {
        val btc = series(900) { 100.0 + 30 * sin(it / 40.0) + it * 0.1 }
        val eth = series(900) { 50.0 + 20 * sin(it / 25.0) }
        val full = Arena.backtest(mapOf("BTC" to btc, "ETH" to eth), targets, 225.0)
        assertNotNull(full)
        // Le débat d'un jour donné ne change pas si on supprime les jours suivants.
        val x = Arena.Inputs(btc.map { it.close }.toDoubleArray())
        val cut = Arena.Inputs(btc.take(500).map { it.close }.toDoubleArray())
        assertEquals(Arena.debateAt("BTC", x, 499).score, Arena.debateAt("BTC", cut, 499).score, 1e-9)
        assertEquals(full!!.invested, full.weeks * 225.0, 1e-6)
        assertTrue(full.valueArena > 0 && full.valueFixed > 0)
    }

    @Test fun backtestNeedsEnoughHistory() {
        assertEquals(null, Arena.backtest(mapOf("BTC" to series(100) { 1.0 }), targets, 225.0))
    }
}
