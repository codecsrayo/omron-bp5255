package com.codecsrayo.presionbp.sync

import android.content.Context
import com.codecsrayo.presionbp.analysis.Analyzer
import com.codecsrayo.presionbp.analysis.Throttle
import com.codecsrayo.presionbp.data.Repository
import com.codecsrayo.presionbp.data.toSample
import com.codecsrayo.presionbp.device.OmronClient
import com.codecsrayo.presionbp.device.Progress
import com.codecsrayo.presionbp.export.HealthConnectExporter
import com.codecsrayo.presionbp.notify.Notifier
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.LocalDateTime

data class SyncResult(val inMemory: Int, val fresh: Int, val healthConnect: Int?)

/** Sincronización completa: leer, guardar, notificar hallazgos y enviar a Health Connect. La usan la UI y el trabajo en segundo plano. */
class SyncEngine(private val context: Context) {
    private val repo = Repository(context)
    private val client = OmronClient(context)
    private val healthConnect = HealthConnectExporter(context)

    /**
     * [direct]: conectar sin escanear antes (cuando el sistema acaba de ver el anuncio del tensiómetro).
     * Si la conexión directa falla, se escanea como respaldo.
     */
    suspend fun sync(address: String, direct: Boolean, automatic: Boolean, progress: Progress): SyncResult = lock.withLock {
        if (!client.isBluetoothOn) throw OmronClient.OmronException("Activa el Bluetooth")

        val raws = try {
            if (!direct) throw OmronClient.OmronException("sin conexión directa")
            client.readMemory(client.deviceByAddress(address), repo.syncClock, progress)
        } catch (e: Exception) {
            if (e is OmronClient.OmronException && e.message?.startsWith("Saludo") == true) throw e
            progress.report("Esperando al tensiómetro… (pulsa su botón de Bluetooth o mide)")
            val device = client.scan(address, repo.deviceName, timeoutMs = if (automatic) 20_000 else 60_000)
                ?: throw OmronClient.OmronException("El tensiómetro no apareció. Pulsa su botón de Bluetooth y reintenta.")
            client.readMemory(device, repo.syncClock, progress)
        }
        val fresh = repo.store(address, raws)
        repo.lastSyncEpoch = System.currentTimeMillis() / 1000

        val now = LocalDateTime.now()
        val newSamples = fresh.map { it.toSample() }.filter { it.trustedTime && Duration.between(it.at, now).toDays() < 2 }
        val all = repo.all().map { it.toSample() }
        val nowEpoch = System.currentTimeMillis() / 1000
        val trends = if (fresh.isEmpty()) emptyList() else Analyzer.forHistory(all, now).filter { f ->
            val key = f.key ?: return@filter true
            Throttle.shouldNotify(repo.lastNotified(key), nowEpoch, f.rank).also { if (it) repo.setNotified(key, nowEpoch, f.rank) }
        }
        Notifier.notify(context, newSamples.flatMap { Analyzer.forReading(it) } + trends)
        if (automatic) newSamples.maxByOrNull { it.at }?.let { Notifier.newReading(context, it.systolic, it.diastolic, it.pulse, newSamples.size) }

        var hc: Int? = null
        if (repo.healthConnectAuto && healthConnect.isAvailable() && healthConnect.hasPermissions()) {
            val pending = repo.pendingHealthConnect()
            hc = healthConnect.write(pending)
            repo.markInHealthConnect(pending.map { it.id })
        }
        SyncResult(raws.size, fresh.size, hc)
    }

    companion object {
        /** Evita dos sincronizaciones simultáneas (UI + segundo plano). */
        private val lock = Mutex()
    }
}
