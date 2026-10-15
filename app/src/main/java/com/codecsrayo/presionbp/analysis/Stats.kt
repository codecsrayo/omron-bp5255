package com.codecsrayo.presionbp.analysis

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.math.sqrt

data class Summary(
    val count: Int,
    val avgSys: Int,
    val avgDia: Int,
    val avgPulse: Int,
    val minSys: Int,
    val maxSys: Int,
    /** Desviación estándar de la sistólica: variabilidad entre mediciones. */
    val sdSys: Int,
    /** Presión de pulso media (sistólica - diastólica). */
    val pulsePressure: Int,
    /** % de mediciones por debajo de 130/80. */
    val inTargetPct: Int,
)

enum class DayPart(val label: String, val hours: IntRange) {
    NIGHT("Madrugada", 0..5), MORNING("Mañana", 6..11), AFTERNOON("Tarde", 12..17), EVENING("Noche", 18..23),
}

data class Bucket(val label: String, val count: Int, val avgSys: Int, val avgDia: Int, val minSys: Int, val maxSys: Int, val avgPulse: Int)

object Stats {
    fun summary(s: List<Sample>): Summary? {
        if (s.isEmpty()) return null
        val sys = s.map { it.systolic }
        val avg = sys.average()
        val sd = sqrt(sys.sumOf { (it - avg) * (it - avg) } / sys.size)
        return Summary(
            count = s.size,
            avgSys = avg.toInt(),
            avgDia = s.map { it.diastolic }.average().toInt(),
            avgPulse = s.map { it.pulse }.average().toInt(),
            minSys = sys.min(),
            maxSys = sys.max(),
            sdSys = sd.toInt(),
            pulsePressure = s.map { it.systolic - it.diastolic }.average().toInt(),
            inTargetPct = s.count { it.systolic < 130 && it.diastolic < 80 } * 100 / s.size,
        )
    }

    /** Cuántas mediciones caen en cada categoría, en orden de gravedad. */
    fun categories(s: List<Sample>): List<Pair<Category, Int>> {
        val counts = s.groupingBy { classify(it.systolic, it.diastolic) }.eachCount()
        return listOf(Category.LOW, Category.NORMAL, Category.ELEVATED, Category.STAGE1, Category.STAGE2, Category.CRISIS)
            .map { it to (counts[it] ?: 0) }
    }

    fun byDayPart(s: List<Sample>): List<Bucket> = DayPart.entries.map { part ->
        bucket(part.label, s.filter { it.at.hour in part.hours })
    }

    /** Promedios por semana (lunes a domingo), de la más antigua a la más reciente. */
    fun weekly(s: List<Sample>): List<Pair<LocalDate, Bucket>> =
        s.groupBy { it.at.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) }
            .toSortedMap()
            .map { (week, items) -> week to bucket("${week.dayOfMonth}/${week.monthValue}", items) }

    private fun bucket(label: String, s: List<Sample>): Bucket =
        if (s.isEmpty()) Bucket(label, 0, 0, 0, 0, 0, 0)
        else Bucket(
            label, s.size,
            s.map { it.systolic }.average().toInt(),
            s.map { it.diastolic }.average().toInt(),
            s.minOf { it.systolic },
            s.maxOf { it.systolic },
            s.map { it.pulse }.average().toInt(),
        )
}
