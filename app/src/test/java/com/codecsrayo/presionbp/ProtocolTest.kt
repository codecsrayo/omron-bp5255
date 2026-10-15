package com.codecsrayo.presionbp

import com.codecsrayo.presionbp.analysis.Category
import com.codecsrayo.presionbp.analysis.classify
import com.codecsrayo.presionbp.device.Hem716Layout
import com.codecsrayo.presionbp.device.OmronPacket
import com.codecsrayo.presionbp.device.OmronProtocol
import com.codecsrayo.presionbp.device.OmronProtocol.hex
import com.codecsrayo.presionbp.device.PacketAssembler
import com.codecsrayo.presionbp.device.toHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** Valores tomados de una captura HCI real entre OMRON connect y un BP5255 (HEM-716BT2). */
class ProtocolTest {

    @Test
    fun readCommandMatchesCapture() {
        assertEquals("08010002602c0047", OmronProtocol.readCommand(0x0260, 0x2c).toHex())
        assertEquals("0801000374380046", OmronProtocol.readCommand(0x0374, 0x38).toHex())
        assertEquals("08010003581c004e", OmronProtocol.readCommand(0x0358, 0x1c).toHex())
    }

    @Test
    fun writeCommandMatchesCapture() {
        val data = hex("40a10000000200001a0a0e121e195ec4")
        assertEquals("1801c002d01040a10000000200001a0a0e121e195ec40069", OmronProtocol.writeCommand(0x02d0, data).toHex())
    }

    @Test
    fun handshake() {
        val nonce = hex("611ce40d")
        assertEquals("11611ce40d000000000000000000000000000000", OmronProtocol.handshake(nonce).toHex())
        assertTrue(OmronProtocol.isHandshakeOk(hex("9100611ce40d0000"), nonce))
        assertFalse(OmronProtocol.isHandshakeOk(hex("9100611ce40e0000"), nonce))
    }

    @Test
    fun parsesReadResponse() {
        val p = OmronPacket.parse(hex("16810003740e4445531ad2290e1a80002900c23d001f"))
        assertEquals(OmronProtocol.RESP_READ, p.type)
        assertEquals(0x0374, p.address)
        assertEquals("4445531ad2290e1a80002900c23d", p.data.toHex())
    }

    @Test
    fun parsesEndResponse() {
        val p = OmronPacket.parse(hex("088f000000000087"))
        assertEquals(OmronProtocol.RESP_END, p.type)
        assertEquals(0, p.data[0].toInt())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsBadCrc() {
        OmronPacket.parse(hex("088f000000000088"))
    }

    @Test
    fun assemblesFragments() {
        val a = PacketAssembler()
        val full = hex("16810003740e4445531ad2290e1a80002900c23d001f")
        assertNull(a.add(full.copyOfRange(0, 10)))
        assertEquals(full.toHex(), a.add(full.copyOfRange(10, full.size))!!.toHex())
    }

    @Test
    fun decodesRealRecord() {
        val r = Hem716Layout.parse(hex("4445531ad2290e1a80002900c23d"))!!
        assertEquals(93, r.systolic)
        assertEquals(69, r.diastolic)
        assertEquals(83, r.pulse)
        assertEquals(LocalDateTime.of(2026, 10, 14, 18, 40, 14), r.measuredAt)
        assertEquals(41, r.sequence)
        assertFalse(r.clockUnset)
        assertFalse(r.irregularHeartbeat)
    }

    @Test
    fun recordWithUnsetClockIsFlagged() {
        val r = Hem716Layout.parse(hex("4f3e4f1720043f1080010b00f20d"))!!
        assertEquals(104, r.systolic)
        assertEquals(2023, r.measuredAt.year)
        assertEquals(11, r.sequence)
        assertTrue(r.clockUnset)
    }

    @Test
    fun emptySlotIsIgnored() {
        assertNull(Hem716Layout.parse(ByteArray(14) { 0xFF.toByte() }))
    }

    @Test
    fun clockBlockMatchesCapture() {
        val read = hex("40a100000002000017010100003f3bc4")
        val written = Hem716Layout.clockBlock(read, LocalDateTime.of(2026, 10, 14, 18, 30, 25))
        assertEquals("40a10000000200001a0a0e121e195ec4", written.toHex())
    }

    @Test
    fun classification() {
        assertEquals(Category.NORMAL, classify(115, 75))
        assertEquals(Category.ELEVATED, classify(125, 75))
        assertEquals(Category.STAGE1, classify(132, 78))
        assertEquals(Category.STAGE2, classify(145, 85))
        assertEquals(Category.CRISIS, classify(185, 100))
        assertEquals(Category.LOW, classify(88, 58))
    }
}
