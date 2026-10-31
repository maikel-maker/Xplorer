package be.maikel.xplorer

import be.maikel.xplorer.core.mondaysInMonth
import be.maikel.xplorer.core.weeklyFromMonthly
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class BudgetTest {
    private fun day(y: Int, m: Int, d: Int) = Calendar.getInstance().apply { clear(); set(y, m - 1, d, 12, 0) }.timeInMillis

    @Test fun monthWithFourMondays() {
        assertEquals(4, mondaysInMonth(day(2026, 10, 31)))   // octobre 2026 : 5, 12, 19, 26
        assertEquals(250.0, weeklyFromMonthly(1000.0, day(2026, 10, 31)), 0.0)
    }

    @Test fun monthWithFiveMondays() {
        assertEquals(5, mondaysInMonth(day(2026, 11, 15)))   // novembre 2026 : 2, 9, 16, 23, 30
        assertEquals(200.0, weeklyFromMonthly(1000.0, day(2026, 11, 15)), 0.0)
    }

    @Test fun neverMoreThanTheMonthlyBudget() {
        for (m in 1..12) {
            val t = day(2027, m, 10)
            assertEquals(true, weeklyFromMonthly(1000.0, t) * mondaysInMonth(t) <= 1000.0)
        }
    }
}
