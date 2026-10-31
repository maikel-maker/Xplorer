package be.maikel.xplorer

import be.maikel.xplorer.domain.DcaJournal
import be.maikel.xplorer.domain.Trade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DcaJournalTest {
    private val D = 86_400_000L
    // Lundi 5 octobre 2026, 00:00 UTC.
    private val monday = 1_791_158_400_000L

    private fun buy(t: Long, eur: Double, fee: Double = 1.0, sym: String = "BTC") = Trade(t, sym, true, 0.001, eur, eur, fee, "advanced_trade_fill", "r$t", null)

    @Test fun weekStartIsMonday() {
        assertEquals(monday, DcaJournal.weekStart(monday + 3 * D + 5000))
        assertEquals(monday, DcaJournal.weekStart(monday))
    }

    @Test fun weeksDonePartialMissed() {
        val trades = listOf(
            buy(monday + D, 150.0), buy(monday + 2 * D, 100.0, sym = "ETH"),   // semaine 1 : 250 → faite
            buy(monday + 8 * D, 60.0),                                           // semaine 2 : 60 → partielle
        )                                                                         // semaine 3 : rien → manquée
        val j = DcaJournal.compute(trades, monday, monday + 20 * D) { 250.0 }
        assertEquals(3, j.weeks.size)
        val (w3, w2, w1) = j.weeks
        assertTrue(w1.done); assertTrue(w2.partial); assertTrue(!w3.done && !w3.partial)
        assertEquals(310.0, j.totalBought, 1e-9)
        assertEquals(3.0, j.totalFees, 1e-9)
        assertEquals(150.0, w1.bySymbol["BTC"]!!, 1e-9)
    }

    @Test fun salesAreIgnored() {
        val sell = Trade(monday + D, "BTC", false, 0.001, 300.0, 300.0, 1.0, "sell", "s", 10.0)
        val j = DcaJournal.compute(listOf(sell), monday, monday + 2 * D) { 250.0 }
        assertEquals(0.0, j.totalBought, 0.0)
    }
}
