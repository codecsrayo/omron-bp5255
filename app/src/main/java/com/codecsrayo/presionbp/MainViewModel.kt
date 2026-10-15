package com.codecsrayo.presionbp

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.codecsrayo.presionbp.analysis.Analyzer
import com.codecsrayo.presionbp.analysis.Finding
import com.codecsrayo.presionbp.data.Reading
import com.codecsrayo.presionbp.data.Repository
import com.codecsrayo.presionbp.data.toSample
import com.codecsrayo.presionbp.device.OmronClient
import com.codecsrayo.presionbp.export.CsvExporter
import com.codecsrayo.presionbp.export.HealthConnectExporter
import com.codecsrayo.presionbp.sync.AutoSync
import com.codecsrayo.presionbp.sync.SyncEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.time.Duration
import java.time.LocalDateTime

data class UiState(
    val busy: Boolean = false,
    val status: String? = null,
    val error: String? = null,
    val deviceAddress: String? = null,
    val syncClock: Boolean = true,
    val autoSync: Boolean = true,
    val healthConnectAuto: Boolean = false,
    val healthConnectAvailable: Boolean = false,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)
    private val client = OmronClient(app)
    private val engine = SyncEngine(app)
    val healthConnect = HealthConnectExporter(app)

    init {
        // Emparejado con una versión anterior: recuperar el nombre del vínculo del sistema.
        repo.deviceAddress?.let { addr ->
            if (repo.deviceName == null) runCatching { repo.deviceName = client.deviceByAddress(addr).name }
        }
        AutoSync.register(app)
    }

    private val _ui = MutableStateFlow(
        UiState(
            deviceAddress = repo.deviceAddress,
            syncClock = repo.syncClock,
            autoSync = repo.autoSync,
            healthConnectAuto = repo.healthConnectAuto,
            healthConnectAvailable = healthConnect.isAvailable(),
        ),
    )
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    val readings: StateFlow<List<Reading>> =
        repo.readings.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Hallazgos vigentes: últimos 7 días por medición + tendencias. */
    val findings: StateFlow<List<Finding>> = repo.readings.map { list ->
        val samples = list.map { it.toSample() }
        val now = LocalDateTime.now()
        val recent = samples.filter { it.trustedTime && Duration.between(it.at, now).toDays() < 7 }
        Analyzer.forHistory(samples, now) + recent.flatMap { Analyzer.forReading(it) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private fun status(msg: String) = _ui.update { it.copy(status = msg) }

    private fun run(block: suspend () -> Unit) {
        if (_ui.value.busy) return
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, error = null) }
            try {
                block()
            } catch (e: Exception) {
                Log.e("MainViewModel", "fallo", e)
                _ui.update { it.copy(error = e.message ?: e.javaClass.simpleName, status = null) }
            } finally {
                _ui.update { it.copy(busy = false) }
            }
        }
    }

    /** Primer uso: tensiómetro en modo "P". Lo busca, lo vincula y lo guarda. */
    fun pair() = run {
        if (!client.isBluetoothOn) error("Activa el Bluetooth")
        status("Buscando tensiómetro en modo P…")
        val device = client.scan() ?: error("No se encontró ningún tensiómetro. ¿Está parpadeando la \"P\"?")
        client.ensureBonded(device) { status(it) }
        repo.deviceAddress = device.address
        repo.deviceName = device.name
        _ui.update { it.copy(deviceAddress = device.address) }
        doSync(device.address, direct = true)
        AutoSync.register(getApplication())
    }

    /** Uso normal: tras medir, el tensiómetro se anuncia unos segundos. */
    fun sync() = run {
        val address = repo.deviceAddress ?: error("Primero empareja el tensiómetro")
        doSync(address, direct = false)
    }

    private suspend fun doSync(address: String, direct: Boolean) {
        val r = engine.sync(address, direct, automatic = false) { status(it) }
        val hc = r.healthConnect?.let { " · $it enviadas a Health Connect" }.orEmpty()
        status("Sincronizado: ${r.inMemory} en memoria, ${r.fresh} nuevas$hc")
    }

    fun setAutoSync(v: Boolean) {
        repo.autoSync = v
        _ui.update { it.copy(autoSync = v) }
        if (v) AutoSync.register(getApplication()) else AutoSync.unregister(getApplication())
    }

    private suspend fun pushHealthConnect(): String {
        val pending = repo.pendingHealthConnect()
        val n = healthConnect.write(pending)
        repo.markInHealthConnect(pending.map { it.id })
        return "$n enviadas a Health Connect"
    }

    fun exportHealthConnect() = run { status(pushHealthConnect()) }

    fun exportCsv(onReady: (File) -> Unit) = run {
        val file = CsvExporter.write(getApplication(), repo.all())
        onReady(file)
        status("CSV listo: ${file.name}")
    }

    fun setSyncClock(v: Boolean) {
        repo.syncClock = v
        _ui.update { it.copy(syncClock = v) }
    }

    fun setHealthConnectAuto(v: Boolean) {
        repo.healthConnectAuto = v
        _ui.update { it.copy(healthConnectAuto = v) }
    }

    fun forgetDevice() {
        AutoSync.unregister(getApplication())
        repo.deviceAddress = null
        repo.deviceName = null
        _ui.update { it.copy(deviceAddress = null, status = null) }
    }

    fun dismissError() = _ui.update { it.copy(error = null) }

    fun deleteReading(id: Long) = viewModelScope.launch { repo.delete(id) }

    fun deleteAll() = viewModelScope.launch {
        repo.deleteAll()
        status("Historial borrado")
    }
}
