package com.offgrid.android

import org.junit.Assert.*
import org.junit.Test

class LocalToolsTest {
    @Test fun arithmeticHonorsPrecedenceAndPercentages() {
        assertEquals("30", LocalTools.calculate("(120 + 80) * 15%"))
        assertEquals("14", LocalTools.calculate("2 + 3 * 4"))
        assertEquals("0.3", LocalTools.calculate("0.1 + 0.2"))
        assertEquals("-6", LocalTools.calculate("-2 * (1 + 2)"))
    }
    @Test fun invalidArithmeticIsRejected() {
        listOf("1/0", "2 +", "(2+3", "1..2", "alert(1)", "2 3", "").forEach { expression ->
            assertTrue(expression, runCatching { LocalTools.calculate(expression) }.isFailure)
        }
    }
    @Test fun convertsTravelUnitsAndTemperatures() {
        assertEquals("1.609344 km", LocalTools.convert("1", "mi", "km"))
        assertEquals("0 C", LocalTools.convert("32", "F", "C"))
        assertEquals("273.15 K", LocalTools.convert("0", "C", "K"))
        assertEquals("3.785411784 l", LocalTools.convert("1", "gal (US)", "l"))
        assertTrue(runCatching { LocalTools.convert("1", "kg", "km") }.isFailure)
        assertTrue(runCatching { LocalTools.convert("-1", "K", "C") }.isFailure)
        assertTrue(runCatching { LocalTools.convert("NaN", "km", "mi") }.isFailure)
    }
    @Test fun dateDifferencesHandleLeapYearsAndOrder() {
        assertEquals("2 days", LocalTools.daysBetween("2024-02-28", "2024-03-01"))
        assertEquals("-2 days", LocalTools.daysBetween("2024-03-01", "2024-02-28"))
        assertTrue(runCatching { LocalTools.daysBetween("2024-02-30", "2024-03-01") }.isFailure)
    }
}
