package com.codecsrayo.presionbp.device

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom
import java.time.LocalDateTime
import kotlin.coroutines.resume

private const val TAG = "OmronClient"

/** Paso actual, para mostrar progreso en la UI. */
fun interface Progress {
    fun report(message: String)
}

@SuppressLint("MissingPermission")
class OmronClient(private val context: Context) {

    class OmronException(msg: String) : Exception(msg)

    private val adapter = context.getSystemService(BluetoothManager::class.java).adapter

    val isBluetoothOn: Boolean get() = adapter?.isEnabled == true

    fun deviceByAddress(address: String): BluetoothDevice = adapter.getRemoteDevice(address)

    /** Busca un tensiómetro Omron que esté anunciándose (tras medir, o en modo "P"); [address] limita a uno concreto. */
    suspend fun scan(address: String? = null, name: String? = null, timeoutMs: Long = 30_000): BluetoothDevice? {
        val scanner = adapter?.bluetoothLeScanner ?: throw OmronException("Bluetooth apagado")
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val cb = object : ScanCallback() {
                    override fun onScanResult(callbackType: Int, result: ScanResult) {
                        val name = result.scanRecord?.deviceName ?: result.device.name ?: return
                        val matches = if (address != null) result.device.address.equals(address, ignoreCase = true)
                        else name.startsWith(OmronProtocol.NAME_PREFIX)
                        if (matches && cont.isActive) {
                            Log.d(TAG, "encontrado $name ${result.device.address}")
                            scanner.stopScan(this)
                            cont.resume(result.device)
                        }
                    }

                    override fun onScanFailed(errorCode: Int) {
                        if (cont.isActive) cont.resume(null)
                    }
                }
                val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
                // Con filtro, Android entrega resultados también con la pantalla apagada.
                val filters = name?.let { listOf(ScanFilter.Builder().setDeviceName(it).build()) }
                scanner.startScan(filters, settings, cb)
                cont.invokeOnCancellation { runCatching { scanner.stopScan(cb) } }
            }
        }
    }

    /** Vincula (bond) con el sistema. El tensiómetro debe estar en modo "P". Si ya está vinculado, no hace nada. */
    suspend fun ensureBonded(device: BluetoothDevice, progress: Progress) {
        if (device.bondState == BluetoothDevice.BOND_BONDED) return
        progress.report("Vinculando… acepta el aviso de Android si aparece")
        if (device.bondState == BluetoothDevice.BOND_NONE && !device.createBond()) {
            throw OmronException("Android no pudo iniciar la vinculación")
        }
        val ok = withTimeoutOrNull(45_000) {
            while (device.bondState != BluetoothDevice.BOND_BONDED) {
                delay(300)
                if (device.bondState == BluetoothDevice.BOND_NONE) throw OmronException("Vinculación rechazada o fallida")
            }
            true
        }
        if (ok != true) throw OmronException("Tiempo agotado vinculando")
    }

    /**
     * Lee las 30 ranuras de memoria y, si [syncClock], pone en hora el tensiómetro.
     * No borra nada ni toca los contadores de "pendientes" que usa la app de Omron.
     */
    suspend fun readMemory(device: BluetoothDevice, syncClock: Boolean, progress: Progress): List<Hem716Layout.Raw> {
        progress.report("Conectando…")
        GattSession.open(context, device).use { s ->
            s.enableNotifications(OmronProtocol.RX)
            s.enableNotifications(OmronProtocol.UNLOCK)

            progress.report("Desbloqueando…")
            val nonce = ByteArray(4).also { SecureRandom().nextBytes(it) }
            s.drainNotifications()
            s.write(OmronProtocol.UNLOCK, OmronProtocol.handshake(nonce), withResponse = false)
            val hs = s.awaitNotification(OmronProtocol.UNLOCK, 5_000)
            if (!OmronProtocol.isHandshakeOk(hs, nonce)) throw OmronException("Saludo rechazado: ${hs.toHex()}")

            val assembler = PacketAssembler()
            suspend fun request(cmd: ByteArray, expectType: Int): OmronPacket {
                var last: Throwable? = null
                repeat(3) {
                    try {
                        assembler.reset()
                        s.write(OmronProtocol.TX, cmd, withResponse = false)
                        var raw: ByteArray? = null
                        while (raw == null) raw = assembler.add(s.awaitNotification(OmronProtocol.RX, 3_000))
                        val p = OmronPacket.parse(raw)
                        if (p.type != expectType) throw OmronException("respuesta inesperada ${raw.toHex()}")
                        return p
                    } catch (e: Exception) {
                        if (e is GattSession.GattException) throw e
                        Log.w(TAG, "reintento: ${e.message}")
                        last = e
                    }
                }
                throw OmronException("Sin respuesta del tensiómetro: ${last?.message}")
            }

            request(OmronProtocol.START_TRANSMISSION, OmronProtocol.RESP_START)

            val total = Hem716Layout.RECORD_SIZE * Hem716Layout.RECORD_COUNT
            val memory = ByteArray(total)
            var offset = 0
            while (offset < total) {
                progress.report("Leyendo memoria… ${offset * 100 / total}%")
                val size = minOf(Hem716Layout.READ_BLOCK, total - offset)
                val addr = Hem716Layout.RECORDS_ADDRESS + offset
                val p = request(OmronProtocol.readCommand(addr, size), OmronProtocol.RESP_READ)
                if (p.address != addr || p.data.size != size) throw OmronException("bloque inválido en 0x%04x".format(addr))
                p.data.copyInto(memory, offset)
                offset += size
            }

            if (syncClock) {
                progress.report("Poniendo en hora…")
                val cur = request(
                    OmronProtocol.readCommand(Hem716Layout.CLOCK_READ_ADDRESS, Hem716Layout.CLOCK_BLOCK_SIZE),
                    OmronProtocol.RESP_READ,
                ).data
                val block = Hem716Layout.clockBlock(cur, LocalDateTime.now())
                request(OmronProtocol.writeCommand(Hem716Layout.CLOCK_WRITE_ADDRESS, block), OmronProtocol.RESP_WRITE)
            }

            val end = request(OmronProtocol.END_TRANSMISSION, OmronProtocol.RESP_END)
            if (end.data.firstOrNull()?.toInt() != 0) throw OmronException("El tensiómetro terminó con error ${end.data.toHex()}")

            return Hem716Layout.parseAll(memory)
        }
    }
}
