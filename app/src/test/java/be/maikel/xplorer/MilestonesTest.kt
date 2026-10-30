package be.maikel.xplorer

import be.maikel.xplorer.domain.Milestones
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MilestonesTest {

    @Test fun ethLadderProgress() {
        val m = Milestones.compute(listOf("ETH"), mapOf("ETH" to 0.4), mapOf("ETH" to 3000.0), 1200.0, mapOf("ETH" to 60.0), 225.0)
        val eth = m.coins.single()
        assertEquals(3, eth.reached)            // 0,05 · 0,1 · 0,25
        assertEquals(0.5, eth.next!!, 0.0)
        assertEquals(0.6, eth.progress, 1e-9)    // de 0,25 à 0,5 : 0,4 est à 60 %
        assertEquals(5, eth.weeksToNext)         // 0,1 ETH manquant, 0,02 ETH/semaine
    }

    @Test fun wealthTrack() {
        val m = Milestones.compute(emptyList(), emptyMap(), emptyMap(), 686.0, emptyMap(), 225.0)
        assertEquals(2, m.wealth.reached)        // 250 € et 500 €
        assertEquals(1000.0, m.wealth.next!!, 0.0)
        assertEquals(2, m.wealth.weeksToNext)    // 314 € manquants à 225 €/semaine
    }

    @Test fun unknownCoinUsesEuros() {
        val m = Milestones.compute(listOf("XYZ"), mapOf("XYZ" to 10.0), mapOf("XYZ" to 12.0), 120.0, emptyMap(), 225.0)
        val t = m.coins.single()
        assertTrue(t.inEuros)
        assertEquals(120.0, t.current, 1e-9)
        assertNull(t.weeksToNext)                // pas d'achat prévu : pas d'estimation
    }

    @Test fun endOfLadder() {
        val m = Milestones.compute(listOf("BTC"), mapOf("BTC" to 2.0), mapOf("BTC" to 60000.0), 120000.0, emptyMap(), 225.0)
        assertTrue(m.coins.single().done)
        assertEquals(1.0, m.coins.single().progress, 0.0)
    }
}
