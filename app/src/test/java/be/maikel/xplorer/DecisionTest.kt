package be.maikel.xplorer

import be.maikel.xplorer.domain.Action
import be.maikel.xplorer.domain.AssetContext
import be.maikel.xplorer.domain.Candle
import be.maikel.xplorer.domain.DecisionEngine
import be.maikel.xplorer.domain.Journal
import be.maikel.xplorer.domain.Level3
import be.maikel.xplorer.domain.Planner
import be.maikel.xplorer.domain.Rules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class DecisionTest {
    private val D = 86_400_000L
    private val now = 400 * D + 3600_000L

    private fun series(n: Int, f: (Int) -> Double) = (0 until n).map { i ->
        val c = f(i); Candle(i * D, c * 0.995, c * 1.02, c * 0.98, c, 1000.0 + 50 * sin(i / 4.0))
    }
    private fun ctx(sym: String = "BTC", owned: Boolean = true, w: Double = 0.3, t: Double = 0.45, max: Double = 0.5) = AssetContext(sym, owned, w, t, max, Double.NaN)

    @Test fun notEnoughDataMeansNoSignal() {
        val r = DecisionEngine.evaluate("BTC", series(60) { 100.0 + it }, null, 160.0, ctx(), Rules(), now)
        assertEquals(Action.INSUFFISANT, r.action)
        assertTrue(r.cons.first().contains("preuves suffisantes"))
    }

    @Test fun neverBuyAtTheTop() {
        val up = series(400) { 100.0 + it * 0.5 }
        val r = DecisionEngine.evaluate("BTC", up, null, 305.0, ctx(w = 0.1), Rules(), now)
        assertFalse(r.action.isBuy)
        assertTrue(r.percentile1y >= 0.9)
    }

    @Test fun dipInUptrendCanBeBought() {
        // Tendance de fond haussière, puis repli marqué : situation d'accumulation classique.
        val s = series(400) { i -> if (i < 370) 100.0 + i * 0.4 else 248.0 - (i - 370) * 2.5 }
        val price = 175.0
        val r = DecisionEngine.evaluate("BTC", s, null, price, ctx(w = 0.2), Rules(), now)
        assertTrue("score ${r.score}", r.score > 0)
        assertNotNull(r.invalidationPrice)
        assertTrue(r.invalidationPrice < price)
        assertEquals(4, r.components.size)
        assertEquals(1.0, r.components.sumOf { it.weight }, 1e-9)
    }

    @Test fun riskGuardianVetoesOverexposure() {
        val s = series(400) { i -> if (i < 370) 100.0 + i * 0.4 else 248.0 - (i - 370) * 2.5 }
        val r = DecisionEngine.evaluate("BTC", s, null, 175.0, ctx(w = 0.62, max = 0.5), Rules(), now)
        assertFalse(r.action.isBuy)
    }

    @Test fun geodIsNeverSoldBelowTwentyTimes() {
        val top = series(400) { 0.05 + it * 0.0006 }
        val r = DecisionEngine.evaluate("GEOD", top, null, 0.30, ctx("GEOD", w = 0.6, t = 0.2), Rules(geodBase = 0.21, geodMultiple = 20.0), now)
        assertFalse(r.action.isSell)
        val r2 = DecisionEngine.evaluate("GEOD", series(400) { 1.0 + it * 0.01 }, null, 5.0, ctx("GEOD", w = 0.9, t = 0.2), Rules(geodBase = 0.21, geodMultiple = 20.0), now)
        // au-dessus de 4,20 € la vente redevient possible (pas obligatoire)
        assertTrue(r2.veto == null || !r2.veto!!.contains("Règle GEOD"))
    }

    @Test fun scoreIsNotAProbabilityAndConfidenceIsSeparate() {
        val s = series(400) { i -> 100 + 20 * sin(i / 15.0) }
        val r = DecisionEngine.evaluate("ETH", s, null, 100.0, ctx("ETH"), Rules(), now)
        assertTrue(r.score in 0..100)
        assertTrue(r.confidence in Level3.entries)
        assertTrue(r.horizon.isNotEmpty() && r.confirmation.isNotEmpty())
    }

    @Test fun planNeverExceedsBudgetOrLimits() {
        val s = series(400) { i -> if (i < 370) 100.0 + i * 0.4 else 248.0 - (i - 370) * 2.5 }
        val rec = DecisionEngine.evaluate("BTC", s, null, 175.0, ctx(w = 0.2), Rules(), now)
        val forced = rec.copy(action = Action.RENFORCER, score = 80, veto = null)
        val p = Planner.plan(100.0, mapOf("BTC" to forced), mapOf("BTC" to 100.0, "ETH" to 300.0), mapOf("BTC" to 0.45, "ETH" to 0.2), mapOf("BTC" to 0.5, "ETH" to 0.5), 400.0, 1000.0)
        val total = p.lines.sumOf { it.eur }
        assertTrue(total <= 100.0 + 0.5)
        assertEquals(0.0, p.lines.first { it.symbol == "ETH" }.eur, 0.0) // pas d'analyse → rien
        val none = Planner.plan(100.0, emptyMap(), emptyMap(), mapOf("BTC" to 1.0), mapOf("BTC" to 1.0), 0.0, null)
        assertTrue(none.headline.startsWith("Ne rien acheter"))
    }

    @Test fun planWarnsWhenCashIsMissing() {
        val s = series(400) { i -> if (i < 370) 100.0 + i * 0.4 else 248.0 - (i - 370) * 2.5 }
        val rec = DecisionEngine.evaluate("BTC", s, null, 175.0, ctx(w = 0.0, owned = false), Rules(), now).copy(action = Action.ACHETER, score = 75, veto = null)
        val p = Planner.plan(250.0, mapOf("BTC" to rec), emptyMap(), mapOf("BTC" to 1.0), mapOf("BTC" to 1.0), 0.0, 40.0)
        assertNotNull(p.cashWarning)
    }

    @Test fun journalGrading() {
        assertEquals("juste", Journal.grade("RENFORCER", 100.0, 110.0).verdict)
        assertEquals("raté", Journal.grade("ACHETER", 100.0, 90.0).verdict)
        assertEquals("juste", Journal.grade("ALLEGER", 100.0, 90.0).verdict)
        assertEquals("non noté", Journal.grade("ATTENDRE", 100.0, 150.0).verdict)
        assertTrue(Journal.shouldRecord(null, null, Action.CONSERVER, now))
        assertFalse(Journal.shouldRecord("CONSERVER", now - D, Action.CONSERVER, now))
        assertTrue(Journal.shouldRecord("CONSERVER", now - D, Action.RENFORCER, now))
    }
}
