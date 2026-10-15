package com.codecsrayo.presionbp

import com.codecsrayo.presionbp.analysis.Analyzer
import com.codecsrayo.presionbp.analysis.Category
import com.codecsrayo.presionbp.analysis.Level
import com.codecsrayo.presionbp.analysis.Sample
import com.codecsrayo.presionbp.analysis.Stats
import com.codecsrayo.presionbp.analysis.Throttle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class StatsTest {
    private val base = LocalDateTime.of(2026, 10, 14, 8, 0)
    private fun s(sys: Int, dia: Int, at: LocalDateTime = base, pulse: Int = 70, ihb: Boolean = false) =
        Sample(0, sys, dia, pulse, at, trustedTime = true, irregularHeartbeat = ihb)

    @Test
    fun summary() {
        val r = Stats.summary(listOf(s(120, 80), s(140, 90), s(100, 70)))!!
        assertEquals(120, r.avgSys)
        assertEquals(80, r.avgDia)
        assertEquals(100, r.minSys)
        assertEquals(140, r.maxSys)
        assertEquals(16, r.sdSys)
        assertEquals(40, r.pulsePressure)
        assertEquals(33, r.inTargetPct)
    }

    @Test
    fun categoriesAndDayParts() {
        val list = listOf(s(115, 75), s(150, 95, base.withHour(20)), s(118, 76, base.withHour(21)))
        val cats = Stats.categories(list).toMap()
        assertEquals(2, cats[Category.NORMAL])
        assertEquals(1, cats[Category.STAGE2])
        val parts = Stats.byDayPart(list).associateBy { it.label }
        assertEquals(1, parts["Mañana"]!!.count)
        assertEquals(134, parts["Noche"]!!.avgSys)
    }

    @Test
    fun weeklyGroupsByMonday() {
        // 2026-10-14 es miércoles; 2026-10-19 es lunes de la semana siguiente.
        val list = listOf(s(120, 80, base), s(130, 80, base.plusDays(1)), s(140, 90, LocalDateTime.of(2026, 10, 19, 9, 0)))
        val w = Stats.weekly(list)
        assertEquals(2, w.size)
        assertEquals(125, w[0].second.avgSys)
        assertEquals("12/10", w[0].second.label)
    }

    @Test
    fun trendNotificationsAreThrottled() {
        val t0 = 1_000_000L
        assertTrue(Throttle.shouldNotify(null, t0, 2))                            // nunca avisado
        assertEquals(false, Throttle.shouldNotify(t0 to 2, t0 + 3600, 2))         // mismo nivel, 1 h después
        assertTrue(Throttle.shouldNotify(t0 to 2, t0 + 3600, 3))                  // empeora: avisa ya
        assertEquals(false, Throttle.shouldNotify(t0 to 3, t0 + 3600, 2))         // mejora: no insiste
        assertTrue(Throttle.shouldNotify(t0 to 2, t0 + Throttle.INTERVAL_SECONDS, 2)) // pasó un día
    }

    @Test
    fun trendFindingsHaveKeys() {
        val now = LocalDateTime.of(2026, 10, 20, 9, 0)
        val week = (1..4).map { s(150, 95, now.minusDays(it.toLong())) }
        val f = Analyzer.forHistory(week, now)
        assertEquals("avg7", f.single().key)
        assertEquals(Category.STAGE2.severity, f.single().rank)
    }

    @Test
    fun crisisIsUrgent() {
        val f = Analyzer.forReading(s(190, 110, ihb = true))
        assertEquals(Level.URGENT, f.first().level)
        assertTrue(f.any { it.title.contains("irregular") })
    }
}
