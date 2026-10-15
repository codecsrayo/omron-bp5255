package com.codecsrayo.presionbp.data

import android.content.Context
import com.codecsrayo.presionbp.analysis.Sample
import com.codecsrayo.presionbp.device.Hem716Layout
import com.codecsrayo.presionbp.device.toHex
import java.time.Instant
import java.time.ZoneId

class Repository(context: Context) {
    private val dao = AppDatabase.get(context).readings()
    private val prefs = context.getSharedPreferences("presion", Context.MODE_PRIVATE)

    val readings = dao.observeAll()

    var deviceAddress: String?
        get() = prefs.getString("device", null)
        set(v) = prefs.edit().putString("device", v).apply()

    /** Nombre que anuncia el tensiómetro (BLESmart_…), para el filtro del escaneo en segundo plano. */
    var deviceName: String?
        get() = prefs.getString("device_name", null)
        set(v) = prefs.edit().putString("device_name", v).apply()

    var autoSync: Boolean
        get() = prefs.getBoolean("auto_sync", true)
        set(v) = prefs.edit().putBoolean("auto_sync", v).apply()

    var lastSyncEpoch: Long
        get() = prefs.getLong("last_sync", 0)
        set(v) = prefs.edit().putLong("last_sync", v).apply()

    var syncClock: Boolean
        get() = prefs.getBoolean("sync_clock", true)
        set(v) = prefs.edit().putBoolean("sync_clock", v).apply()

    var healthConnectAuto: Boolean
        get() = prefs.getBoolean("hc_auto", false)
        set(v) = prefs.edit().putBoolean("hc_auto", v).apply()

    /** Último aviso de un hallazgo de tendencia: (epoch, rank). */
    fun lastNotified(key: String): Pair<Long, Int>? =
        prefs.getString("notified_$key", null)?.split(":")?.let { it[0].toLong() to it[1].toInt() }

    fun setNotified(key: String, epoch: Long, rank: Int) =
        prefs.edit().putString("notified_$key", "$epoch:$rank").apply()

    /** Guarda y devuelve solo las mediciones nuevas. */
    suspend fun store(address: String, raws: List<Hem716Layout.Raw>): List<Reading> {
        val now = Instant.now().epochSecond
        val zone = ZoneId.systemDefault()
        val rows = raws.map {
            Reading(
                deviceAddress = address,
                sequence = it.sequence,
                systolic = it.systolic,
                diastolic = it.diastolic,
                pulse = it.pulse,
                measuredAtEpoch = it.measuredAt.atZone(zone).toEpochSecond(),
                clockUnset = it.clockUnset,
                irregularHeartbeat = it.irregularHeartbeat,
                bodyMovement = it.bodyMovement,
                rawHex = it.bytes.toHex(),
                importedAtEpoch = now,
            )
        }
        val ids = dao.insertAll(rows).filter { it > 0 }
        return if (ids.isEmpty()) emptyList() else dao.byIds(ids)
    }

    suspend fun all() = dao.all()
    suspend fun delete(id: Long) = dao.delete(id)
    suspend fun deleteAll() = dao.deleteAll()
    suspend fun pendingHealthConnect() = dao.pendingHealthConnect()
    suspend fun markInHealthConnect(ids: List<Long>) = dao.markInHealthConnect(ids)
}

fun Reading.toSample() = Sample(id, systolic, diastolic, pulse, measuredAt, !clockUnset, irregularHeartbeat)
