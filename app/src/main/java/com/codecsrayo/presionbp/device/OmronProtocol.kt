package com.codecsrayo.presionbp.device

import java.util.UUID

/**
 * Protocolo propietario de los tensiómetros Omron con Bluetooth (verificado con capturas HCI del HEM-716BT2).
 *
 * Paquete = [longitud total][tipo (2 bytes)][dirección EEPROM (2 bytes, big endian)][n bytes de datos][datos...][0x00][xor].
 * El XOR de todos los bytes del paquete, incluido el último, debe dar 0.
 */
object OmronProtocol {
    /** El HEM-716BT2 expone las características bajo 0xFE4A; modelos más antiguos bajo ecbe3980. */
    val SERVICES: List<UUID> = listOf(
        UUID.fromString("0000fe4a-0000-1000-8000-00805f9b34fb"),
        UUID.fromString("ecbe3980-c9a2-11e1-b1bd-0002a5d5c51b"),
    )
    val RX: UUID = UUID.fromString("49123040-aee8-11e1-a74d-0002a5d5c51b")
    val TX: UUID = UUID.fromString("db5b55e0-aee7-11e1-965e-0002a5d5c51b")
    val UNLOCK: UUID = UUID.fromString("b305b680-aee7-11e1-a730-0002a5d5c51b")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val NAME_PREFIX = "BLESmart_"

    const val RESP_START = 0x8000
    const val RESP_READ = 0x8100
    const val RESP_WRITE = 0x81C0
    const val RESP_END = 0x8F00

    val START_TRANSMISSION = hex("0800000000100018")
    val END_TRANSMISSION = hex("080f000000000007")

    fun readCommand(address: Int, size: Int): ByteArray {
        require(size in 1..0x38) { "bloque demasiado grande: $size" }
        val body = byteArrayOf(0x08, 0x01, 0x00, (address shr 8).toByte(), address.toByte(), size.toByte(), 0x00)
        return body + xor(body)
    }

    fun writeCommand(address: Int, data: ByteArray): ByteArray {
        require(data.size in 1..0x38)
        val body = byteArrayOf((data.size + 8).toByte(), 0x01, 0xC0.toByte(), (address shr 8).toByte(), address.toByte(), data.size.toByte()) +
            data + byteArrayOf(0x00)
        return body + xor(body)
    }

    /**
     * Saludo en la característica UNLOCK: 0x11 + 4 bytes aleatorios, relleno a 20 bytes.
     * El tensiómetro responde 0x91 0x00 + los mismos 4 bytes. Requiere el enlace ya vinculado (cifrado).
     */
    fun handshake(nonce: ByteArray): ByteArray {
        require(nonce.size == 4)
        return byteArrayOf(0x11) + nonce + ByteArray(15)
    }

    fun isHandshakeOk(response: ByteArray, nonce: ByteArray): Boolean =
        response.size >= 6 && response[0] == 0x91.toByte() && response[1] == 0.toByte() &&
            response.copyOfRange(2, 6).contentEquals(nonce)

    fun xor(bytes: ByteArray): Byte = bytes.fold(0) { acc, b -> acc xor (b.toInt() and 0xFF) }.toByte()

    fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

/** Respuesta ya reensamblada y validada. */
class OmronPacket(val type: Int, val address: Int, val data: ByteArray) {
    companion object {
        fun parse(raw: ByteArray): OmronPacket {
            require(raw.size >= 8) { "paquete corto: ${raw.toHex()}" }
            require(OmronProtocol.xor(raw) == 0.toByte()) { "CRC inválido: ${raw.toHex()}" }
            val type = ((raw[1].toInt() and 0xFF) shl 8) or (raw[2].toInt() and 0xFF)
            val address = ((raw[3].toInt() and 0xFF) shl 8) or (raw[4].toInt() and 0xFF)
            val n = raw[5].toInt() and 0xFF
            val data = if (type == OmronProtocol.RESP_END) raw.copyOfRange(6, 7) else raw.copyOfRange(6, minOf(6 + n, raw.size - 2))
            return OmronPacket(type, address, data)
        }
    }
}

/** Junta notificaciones parciales hasta completar un paquete (el primer byte indica la longitud total). */
class PacketAssembler {
    private var buffer = ByteArray(0)

    /** Devuelve el paquete completo, o null si aún faltan fragmentos. */
    fun add(chunk: ByteArray): ByteArray? {
        buffer += chunk
        val expected = buffer[0].toInt() and 0xFF
        if (expected == 0 || buffer.size < expected) return null
        val packet = buffer.copyOfRange(0, expected)
        buffer = ByteArray(0)
        return packet
    }

    fun reset() {
        buffer = ByteArray(0)
    }
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
