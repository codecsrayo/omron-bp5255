package com.codecsrayo.presionbp.device

import java.time.LocalDateTime

/**
 * Mapa de memoria y formato de registro del HEM-716BT2 / BP5255 (misma familia que HEM-716DT2).
 * Todos los campos de varios bytes son little endian.
 */
object Hem716Layout {
    const val RECORDS_ADDRESS = 0x02E8
    const val RECORD_SIZE = 14
    const val RECORD_COUNT = 30
    const val READ_BLOCK = 0x38 // 56 bytes de datos + 8 de cabecera = trama de 64 (MTU 67)

    /** Bloque de ajustes con el reloj: se lee en 0x028C y se escribe en 0x02D0 (16 bytes). */
    const val CLOCK_READ_ADDRESS = 0x028C
    const val CLOCK_WRITE_ADDRESS = 0x02D0
    const val CLOCK_BLOCK_SIZE = 16

    /** Fechas anteriores a esto vienen de un reloj sin configurar (el aparato arranca en 2023-01-01). */
    const val MIN_TRUSTED_YEAR = 2024

    /**
     * Construye el bloque de reloj: conserva los bytes 0-7 y 15 leídos, pone año-2000, mes, día, hora, min, seg
     * en 8-13 y en 14 la suma de los bytes 0-13 (8 bits bajos). Formato tomado de la propia app de Omron.
     */
    fun clockBlock(current: ByteArray, now: LocalDateTime): ByteArray {
        require(current.size == CLOCK_BLOCK_SIZE)
        val b = current.copyOf()
        b[8] = (now.year - 2000).toByte()
        b[9] = now.monthValue.toByte()
        b[10] = now.dayOfMonth.toByte()
        b[11] = now.hour.toByte()
        b[12] = now.minute.toByte()
        b[13] = now.second.toByte()
        b[14] = (0 until 14).sumOf { b[it].toInt() and 0xFF }.toByte()
        return b
    }

    data class Raw(
        val systolic: Int,
        val diastolic: Int,
        val pulse: Int,
        val measuredAt: LocalDateTime,
        val irregularHeartbeat: Boolean,
        val bodyMovement: Boolean,
        val sequence: Int,
        val bytes: ByteArray,
    ) {
        val clockUnset: Boolean get() = measuredAt.year < MIN_TRUSTED_YEAR
    }

    /** Devuelve null para ranuras vacías (todo 0xFF) o con datos imposibles. */
    fun parse(r: ByteArray): Raw? {
        require(r.size == RECORD_SIZE)
        if (r.all { it == 0xFF.toByte() } || r.all { it == 0.toByte() }) return null
        fun u8(i: Int) = r[i].toInt() and 0xFF
        fun u16(i: Int) = u8(i) or (u8(i + 1) shl 8)
        fun bits(v: Int, start: Int, size: Int) = (v shr start) and ((1 shl size) - 1)

        val dt = u16(4)
        val tm = u16(6)
        val year = 2000 + bits(u8(3), 0, 6)
        val month = bits(dt, 10, 4)
        val day = bits(dt, 5, 5)
        val hour = bits(dt, 0, 5)
        val minute = bits(tm, 6, 6)
        val second = minOf(bits(tm, 0, 6), 59)
        val at = runCatching { LocalDateTime.of(year, month, day, hour, minute, second) }.getOrNull() ?: return null

        val sys = u8(0) + 25
        val dia = u8(1)
        val pulse = u8(2)
        if (sys !in 50..280 || dia !in 20..200 || pulse !in 20..250) return null

        return Raw(
            systolic = sys,
            diastolic = dia,
            pulse = pulse,
            measuredAt = at,
            irregularHeartbeat = bits(dt, 14, 1) == 1,
            bodyMovement = bits(dt, 15, 1) == 1,
            sequence = u16(10),
            bytes = r.copyOf(),
        )
    }

    fun parseAll(memory: ByteArray): List<Raw> =
        (0 until memory.size / RECORD_SIZE).mapNotNull { i ->
            parse(memory.copyOfRange(i * RECORD_SIZE, (i + 1) * RECORD_SIZE))
        }
}
