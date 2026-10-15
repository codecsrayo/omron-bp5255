package com.codecsrayo.presionbp.export

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Pressure
import com.codecsrayo.presionbp.analysis.classify
import com.codecsrayo.presionbp.data.Reading
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object CsvExporter {
    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /** CSV con ';' y BOM UTF-8 para que Excel en español lo abra directamente. */
    fun write(context: Context, readings: List<Reading>): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "presion_${System.currentTimeMillis() / 1000}.csv")
        file.bufferedWriter(Charsets.UTF_8).use { w ->
            w.write("﻿")
            w.write("fecha;sistolica;diastolica;pulso;categoria;latido_irregular;movimiento;fecha_fiable;secuencia\n")
            readings.sortedBy { it.measuredAtEpoch }.forEach { r ->
                w.write(
                    listOf(
                        r.measuredAt.format(fmt), r.systolic, r.diastolic, r.pulse,
                        classify(r.systolic, r.diastolic).label,
                        if (r.irregularHeartbeat) "si" else "no",
                        if (r.bodyMovement) "si" else "no",
                        if (r.clockUnset) "no" else "si",
                        r.sequence,
                    ).joinToString(";") + "\n",
                )
            }
        }
        return file
    }

    fun shareIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        return Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            "Exportar mediciones",
        )
    }
}

class HealthConnectExporter(private val context: Context) {

    val permissions = setOf(
        HealthPermission.getWritePermission(BloodPressureRecord::class),
        HealthPermission.getWritePermission(HeartRateRecord::class),
    )

    fun isAvailable(): Boolean =
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    private val client by lazy { HealthConnectClient.getOrCreate(context) }

    suspend fun hasPermissions(): Boolean =
        client.permissionController.getGrantedPermissions().containsAll(permissions)

    /** Escribe mediciones con fecha fiable. clientRecordId evita duplicados si se reenvían. */
    suspend fun write(readings: List<Reading>): Int {
        val valid = readings.filter { !it.clockUnset }
        if (valid.isEmpty()) return 0
        val zone = ZoneId.systemDefault()
        val records = valid.flatMap { r ->
            val t = Instant.ofEpochSecond(r.measuredAtEpoch)
            val offset = zone.rules.getOffset(t)
            val id = "${r.deviceAddress}-${r.sequence}-${r.measuredAtEpoch}"
            listOf(
                BloodPressureRecord(
                    time = t,
                    zoneOffset = offset,
                    systolic = Pressure.millimetersOfMercury(r.systolic.toDouble()),
                    diastolic = Pressure.millimetersOfMercury(r.diastolic.toDouble()),
                    bodyPosition = BloodPressureRecord.BODY_POSITION_SITTING_DOWN,
                    measurementLocation = BloodPressureRecord.MEASUREMENT_LOCATION_LEFT_UPPER_ARM,
                    metadata = Metadata(clientRecordId = "bp-$id", clientRecordVersion = 1),
                ),
                HeartRateRecord(
                    startTime = t,
                    startZoneOffset = offset,
                    endTime = t.plusSeconds(1),
                    endZoneOffset = offset,
                    samples = listOf(HeartRateRecord.Sample(t, r.pulse.toLong())),
                    metadata = Metadata(clientRecordId = "hr-$id", clientRecordVersion = 1),
                ),
            )
        }
        client.insertRecords(records)
        return valid.size
    }
}
