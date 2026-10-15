package com.codecsrayo.presionbp.device

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.Closeable
import java.util.UUID

private const val TAG = "GattSession"

/** Envoltorio de BluetoothGatt con operaciones suspendidas y en serie (Android 13+). */
@SuppressLint("MissingPermission")
class GattSession private constructor(private val device: BluetoothDevice) : Closeable {

    class GattException(msg: String) : Exception(msg)

    /** Notificaciones recibidas: (característica, valor). */
    val notifications = Channel<Pair<UUID, ByteArray>>(Channel.UNLIMITED)

    private lateinit var gatt: BluetoothGatt
    private val opMutex = Mutex()
    private var pending: CompletableDeferred<Int>? = null
    private val connected = CompletableDeferred<Unit>()
    private var disconnected = false

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "connection state=$newState status=$status")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connected.complete(Unit)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                disconnected = true
                val err = GattException("desconectado (status $status)")
                connected.completeExceptionally(err)
                pending?.completeExceptionally(err)
                notifications.close(err)
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) = finish(status)
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = finish(status)
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) = finish(status)
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) = finish(status)

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            Log.d(TAG, "rx ${c.uuid.toString().take(8)} < ${value.toHex()}")
            notifications.trySend(c.uuid to value)
        }
    }

    private fun finish(status: Int) {
        pending?.complete(status)
    }

    private suspend fun op(name: String, timeoutMs: Long = 10_000, start: () -> Boolean): Int = opMutex.withLock {
        if (disconnected) throw GattException("desconectado")
        val d = CompletableDeferred<Int>()
        pending = d
        try {
            if (!start()) throw GattException("$name: no se pudo iniciar")
            withTimeout(timeoutMs) { d.await() }
        } finally {
            pending = null
        }
    }

    private fun characteristic(uuid: UUID): BluetoothGattCharacteristic =
        OmronProtocol.SERVICES.firstNotNullOfOrNull { gatt.getService(it)?.getCharacteristic(uuid) }
            ?: throw GattException(
                "el dispositivo no tiene la característica $uuid (servicios: ${gatt.services.joinToString { it.uuid.toString().take(8) }})",
            )

    suspend fun enableNotifications(uuid: UUID) {
        val c = characteristic(uuid)
        gatt.setCharacteristicNotification(c, true)
        val cccd = c.getDescriptor(OmronProtocol.CCCD) ?: throw GattException("sin CCCD en $uuid")
        val status = op("notify $uuid", timeoutMs = 30_000) {
            gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothGatt.GATT_SUCCESS
        }
        if (status != BluetoothGatt.GATT_SUCCESS) throw GattException("no se pudieron activar notificaciones ($status)")
    }

    suspend fun write(uuid: UUID, value: ByteArray, withResponse: Boolean) {
        val c = characteristic(uuid)
        val type = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        Log.d(TAG, "tx ${uuid.toString().take(8)} > ${value.toHex()}")
        val status = op("write $uuid") { gatt.writeCharacteristic(c, value, type) == BluetoothGatt.GATT_SUCCESS }
        if (status != BluetoothGatt.GATT_SUCCESS) throw GattException("escritura fallida ($status)")
    }

    /** Espera la siguiente notificación de [uuid], descartando las de otras características. */
    suspend fun awaitNotification(uuid: UUID, timeoutMs: Long): ByteArray = withTimeout(timeoutMs) {
        var result: ByteArray? = null
        while (result == null) {
            val (from, value) = notifications.receive()
            if (from == uuid) result = value
        }
        result
    }

    fun drainNotifications() {
        while (notifications.tryReceive().isSuccess) Unit
    }

    override fun close() {
        runCatching { gatt.disconnect() }
        runCatching { gatt.close() }
    }

    companion object {
        suspend fun open(context: Context, device: BluetoothDevice): GattSession {
            val s = GattSession(device)
            try {
                s.gatt = device.connectGatt(context, false, s.callback, BluetoothDevice.TRANSPORT_LE)
                withTimeout(20_000) { s.connected.await() }
                s.op("mtu") { s.gatt.requestMtu(185) }
                val st = s.op("services", timeoutMs = 15_000) { s.gatt.discoverServices() }
                if (st != BluetoothGatt.GATT_SUCCESS) throw GattException("no se pudieron descubrir servicios ($st)")
            } catch (e: Throwable) {
                s.close()
                throw e
            }
            return s
        }
    }
}
