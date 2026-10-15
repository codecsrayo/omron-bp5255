package com.codecsrayo.presionbp.analysis

import java.time.Duration
import java.time.LocalDateTime

/** Categorías según la guía ACC/AHA 2017 para adultos. Orientativo: no sustituye a un profesional de salud. */
enum class Category(val label: String, val severity: Int) {
    LOW("Baja", 2),
    NORMAL("Normal", 0),
    ELEVATED("Elevada", 1),
    STAGE1("Hipertensión grado 1", 2),
    STAGE2("Hipertensión grado 2", 3),
    CRISIS("Crisis hipertensiva", 4),
}

fun classify(sys: Int, dia: Int): Category = when {
    sys > 180 || dia > 120 -> Category.CRISIS
    sys >= 140 || dia >= 90 -> Category.STAGE2
    sys >= 130 || dia >= 80 -> Category.STAGE1
    sys < 90 || dia < 60 -> Category.LOW
    sys >= 120 -> Category.ELEVATED
    else -> Category.NORMAL
}

/** Lo mínimo que necesita el análisis; desacoplado de Room para poder probarlo. */
data class Sample(
    val id: Long,
    val systolic: Int,
    val diastolic: Int,
    val pulse: Int,
    val at: LocalDateTime,
    val trustedTime: Boolean,
    val irregularHeartbeat: Boolean,
)

enum class Level { INFO, WARNING, URGENT }

/**
 * [key]: identifica los hallazgos de tendencia (null en los de una medición concreta).
 * [rank]: cuánto de grave es dentro de su tipo; si sube, se vuelve a avisar aunque no haya pasado el intervalo.
 */
data class Finding(
    val level: Level,
    val title: String,
    val detail: String,
    val readingId: Long? = null,
    val key: String? = null,
    val rank: Int = 0,
)

/** Evita repetir el mismo aviso de tendencia en cada sincronización. */
object Throttle {
    const val INTERVAL_SECONDS = 24 * 3600L

    /** [last]: (epoch del último aviso, rank) o null si nunca se avisó. */
    fun shouldNotify(last: Pair<Long, Int>?, nowEpoch: Long, rank: Int): Boolean =
        last == null || rank > last.second || nowEpoch - last.first >= INTERVAL_SECONDS
}

object Analyzer {

    /** Hallazgos de una medición concreta. */
    fun forReading(s: Sample): List<Finding> = buildList {
        val cat = classify(s.systolic, s.diastolic)
        val v = "${s.systolic}/${s.diastolic} mmHg"
        when (cat) {
            Category.CRISIS -> add(
                Finding(
                    Level.URGENT, "Presión muy alta: $v",
                    "Valor en rango de crisis hipertensiva. Repite la medición tras 5 minutos de reposo. " +
                        "Si sigue igual o tienes dolor de pecho, falta de aire, debilidad o cambios en la visión o el habla, busca atención médica urgente.",
                    s.id,
                ),
            )
            Category.STAGE2 -> add(Finding(Level.WARNING, "Presión alta: $v", "En rango de hipertensión grado 2.", s.id))
            Category.LOW -> add(Finding(Level.WARNING, "Presión baja: $v", "Por debajo de 90/60. Coméntalo si hay mareo o desmayo.", s.id))
            else -> Unit
        }
        if (s.pulse > 100) add(Finding(Level.WARNING, "Pulso alto: ${s.pulse} lpm", "Por encima de 100 en reposo.", s.id))
        if (s.pulse < 50) add(Finding(Level.WARNING, "Pulso bajo: ${s.pulse} lpm", "Por debajo de 50 en reposo.", s.id))
        if (s.irregularHeartbeat) add(
            Finding(Level.WARNING, "Latido irregular detectado", "El tensiómetro marcó ritmo irregular en la medición de $v.", s.id),
        )
    }

    /** Hallazgos sobre la serie: tendencias y promedios (solo con fechas fiables). */
    fun forHistory(all: List<Sample>, now: LocalDateTime = LocalDateTime.now()): List<Finding> = buildList {
        val timed = all.filter { it.trustedTime }
        val last7 = timed.filter { Duration.between(it.at, now).toDays() < 7 }
        val prev = timed.filter { Duration.between(it.at, now).toDays() in 7..36 }

        if (last7.size >= 3) {
            val sys = last7.map { it.systolic }.average()
            val dia = last7.map { it.diastolic }.average()
            val cat = classify(sys.toInt(), dia.toInt())
            if (cat.severity >= Category.STAGE1.severity && cat != Category.LOW) add(
                Finding(
                    Level.WARNING, "Promedio de 7 días: ${sys.toInt()}/${dia.toInt()}",
                    "El promedio de la última semana está en rango \"${cat.label}\" (${last7.size} mediciones).",
                    key = "avg7", rank = cat.severity,
                ),
            )
            if (prev.size >= 3) {
                val delta = sys - prev.map { it.systolic }.average()
                if (delta >= 10) add(
                    Finding(
                        Level.WARNING, "Tendencia al alza", "La sistólica media subió ${delta.toInt()} mmHg respecto a las semanas anteriores.",
                        key = "trend_up", rank = delta.toInt() / 5,
                    ),
                )
                if (delta <= -10) add(
                    Finding(
                        Level.INFO, "Tendencia a la baja", "La sistólica media bajó ${(-delta).toInt()} mmHg respecto a las semanas anteriores.",
                        key = "trend_down",
                    ),
                )
            }
        }

        val irregular = last7.count { it.irregularHeartbeat }
        if (irregular >= 2) add(
            Finding(
                Level.WARNING, "Latido irregular repetido", "$irregular mediciones con ritmo irregular en 7 días. Conviene comentarlo con tu médico.",
                key = "ihb_repeat", rank = irregular,
            ),
        )
    }
}
