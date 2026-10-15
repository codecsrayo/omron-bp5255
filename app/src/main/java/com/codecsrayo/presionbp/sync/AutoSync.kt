package com.codecsrayo.presionbp.sync

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.codecsrayo.presionbp.data.Repository
import java.util.concurrent.TimeUnit

private const val TAG = "AutoSync"

/**
 * Sincronización automática: un escaneo BLE con PendingIntent que el sistema mantiene aunque la app esté cerrada.
 * Cuando el tensiómetro se anuncia (tras medir), el sistema llama a [ScanReceiver], que lanza [SyncWorker].
 */
object AutoSync {
    /** No volver a sincronizar durante este tiempo tras una sincronización correcta (el aparato se anuncia varias veces). */
    const val COOLDOWN_SECONDS = 90L

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 1,
        Intent(context, ScanReceiver::class.java).setAction("com.codecsrayo.presionbp.SCAN_RESULT"),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    /** Registra (o vuelve a registrar) el escaneo en segundo plano. Idempotente. */
    @SuppressLint("MissingPermission")
    fun register(context: Context) {
        val repo = Repository(context)
        val scanner = context.getSystemService(BluetoothManager::class.java).adapter?.bluetoothLeScanner
        val name = repo.deviceName
        if (scanner == null || !repo.autoSync || repo.deviceAddress == null || name == null) {
            unregister(context)
            return
        }
        runCatching { scanner.stopScan(pendingIntent(context)) }
        val filters = listOf(ScanFilter.Builder().setDeviceName(name).build())
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        val rc = runCatching { scanner.startScan(filters, settings, pendingIntent(context)) }
        Log.i(TAG, "escaneo en segundo plano para $name: ${rc.getOrNull() ?: rc.exceptionOrNull()}")

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "rearm-scan", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<RearmWorker>(1, TimeUnit.HOURS).build(),
        )
    }

    @SuppressLint("MissingPermission")
    fun unregister(context: Context) {
        val scanner = context.getSystemService(BluetoothManager::class.java).adapter?.bluetoothLeScanner
        runCatching { scanner?.stopScan(pendingIntent(context)) }
        WorkManager.getInstance(context).cancelUniqueWork("rearm-scan")
    }

    fun enqueueSync(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            "auto-sync", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build(),
        )
    }
}

/** Recibe los resultados del escaneo del sistema. */
class ScanReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val repo = Repository(context)
        val sinceLast = System.currentTimeMillis() / 1000 - repo.lastSyncEpoch
        if (sinceLast < AutoSync.COOLDOWN_SECONDS) return
        Log.i(TAG, "tensiómetro visto, lanzando sincronización")
        AutoSync.enqueueSync(context)
    }
}

/** Reactiva el escaneo tras reiniciar el móvil o actualizar la app. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            AutoSync.register(context)
        }
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repo = Repository(applicationContext)
        val address = repo.deviceAddress ?: return Result.success()
        return try {
            val r = SyncEngine(applicationContext).sync(address, direct = true, automatic = true) { Log.d(TAG, it) }
            Log.i(TAG, "auto-sync: ${r.inMemory} en memoria, ${r.fresh} nuevas")
            Result.success()
        } catch (e: Exception) {
            // No reintentar: el tensiómetro deja de anunciarse en pocos segundos; el próximo anuncio volverá a lanzarlo.
            Log.w(TAG, "auto-sync falló: ${e.message}")
            Result.success()
        }
    }
}

/** Por si el sistema descarta el escaneo (Bluetooth apagado y encendido, limpieza de memoria…). */
class RearmWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        AutoSync.register(applicationContext)
        return Result.success()
    }
}
