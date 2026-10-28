package com.luojuly.watchbms

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForeignBmsTest {
    @Test
    fun commandsMatchThePublishedReadFrames() {
        val ant = ForeignLink().also { it.start(BoardProtocol.ANT) }
        val jbd = ForeignLink().also { it.start(BoardProtocol.JBD) }
        val yy = ForeignLink().also { it.start(BoardProtocol.YY) }
        assertArrayEquals(byteArrayOf(0x7E, 0xA1.toByte(), 0x01, 0x00, 0x00, 0xBE.toByte(), 0x18, 0x55, 0xAA.toByte(), 0x55), ant.opening()[0])
        assertArrayEquals(byteArrayOf(0xDD.toByte(), 0xA5.toByte(), 0x03, 0x00, 0xFF.toByte(), 0xFD.toByte(), 0x77), jbd.opening()[0])
        assertArrayEquals(byteArrayOf(0xDD.toByte(), 0xA5.toByte(), 0x04, 0x00, 0xFF.toByte(), 0xFC.toByte(), 0x77), jbd.followUp())
        assertArrayEquals(byteArrayOf(0x01, 0x03, 0x00, 0x4B, 0x00, 0x5C, 0x35, 0xE5.toByte()), yy.opening()[0])
    }

    @Test
    fun antFrameUsesThisAppsDischargeSign() {
        val link = ForeignLink().also { it.start(BoardProtocol.ANT) }
        assertNull(link.accept(ByteArray(37).also { it[0] = 0x7E }, 1100).snapshot)
        val snap = link.accept(antStatus(), 1200).snapshot
        assertNotNull(snap)
        assertEquals(16, snap!!.cells.size)
        assertEquals(52.84f, snap.totalVoltage, 0.001f)
        assertEquals(-0.3f, snap.current, 0.001f)
        assertEquals("静置", snap.chargeState)
        assertEquals(91, snap.soc)
        assertEquals(280f, snap.nominalAh, 0.001f)
        assertEquals(3.3f, snap.cells[0].voltage, 0.001f)
        assertFalse(snap.hasResistance)
        assertEquals(2f, snap.batteryTempC!!, 0.001f)
        val broken = antStatus()
        broken[40] = (broken[40].toInt() xor 1).toByte()
        assertNull(link.accept(broken, 1300).snapshot)
    }

    @Test
    fun jbdBasicThenCells() {
        val link = ForeignLink().also { it.start(BoardProtocol.JBD) }
        val basic = jbdBasic()
        assertNull(link.accept(basic.copyOf(12), 1400).snapshot)
        assertNull(link.accept(basic.copyOfRange(12, basic.size), 1410).snapshot)
        val snap = link.accept(jbdCells(), 1420).snapshot
        assertNotNull(snap)
        assertEquals(52.84f, snap!!.totalVoltage, 0.001f)
        assertEquals(-0.3f, snap.current, 0.001f)
        assertEquals(55, snap.soc)
        assertEquals(42, snap.cycleCount)
        assertEquals(200f, snap.nominalAh, 0.001f)
        assertEquals(100f, snap.remainingAh, 0.001f)
        assertEquals(8, snap.cells.size)
        assertEquals(4100, (snap.cells[0].voltage * 1000f).toInt())
        assertEquals(0, snap.deltaMv)
        assertEquals(30f, snap.batteryTempC!!, 0.001f)
        assertTrue(snap.chargingMos && snap.dischargingMos)
        val bad = basic.clone()
        bad[bad.lastIndex] = (bad[bad.lastIndex].toInt() xor 1).toByte()
        assertNull(link.accept(bad, 1430).snapshot)
    }

    @Test
    fun yyFrameTreatsNegativeWireCurrentAsCharging() {
        val link = ForeignLink().also { it.start(BoardProtocol.YY) }
        val snap = link.accept(yyStatus(), 1800).snapshot
        assertNotNull(snap)
        assertEquals(16, snap!!.cells.size)
        assertEquals(52.84f, snap.totalVoltage, 0.001f)
        assertEquals(3f, snap.current, 0.001f)
        assertEquals("充电", snap.chargeState)
        assertEquals(88, snap.soc)
        assertEquals(280f, snap.nominalAh, 0.001f)
        assertEquals(200f, snap.remainingAh, 0.001f)
        assertEquals(30f, snap.batteryTempC!!, 0.001f)
        assertEquals(3.3f, snap.cells[0].voltage, 0.001f)
        assertTrue(snap.chargingMos && snap.dischargingMos)
        assertFalse(snap.hasResistance)
        val bad = yyStatus()
        bad[100] = (bad[100].toInt() xor 1).toByte()
        assertNull(link.accept(bad, 1900).snapshot)
        assertNotNull(link.accept(yyStatus(), 2000).snapshot)
    }

    @Test
    fun dlShortFrameAndKeyAgreement() {
        val content = ByteArray(50)
        content[0] = 3
        content[1] = 2
        put32(content, 2, 550)
        put32(content, 6, 400)
        put16(content, 10, 500)
        put16(content, 12, -30)
        content[20] = 1
        content[21] = 25
        put16(content, 46, 3300)
        put16(content, 48, 3310)
        val snap = DlFrames.parseData(content)
        assertNotNull(snap)
        assertEquals(50f, snap!!.totalVoltage, 0.001f)
        assertEquals(3f, snap.current, 0.001f)
        assertEquals("充电", snap.chargeState)
        assertEquals(55f, snap.nominalAh, 0.001f)
        assertEquals(40f, snap.remainingAh, 0.001f)
        assertEquals(2, snap.cells.size)
        assertEquals(10, snap.deltaMv)
        val plain = DlFrames.frame(DlFrames.FC_DATA, ByteArray(0), 7)
        val key = ByteArray(16) { (it + 1).toByte() }
        val iv = ByteArray(16) { (it + 3).toByte() }
        val cipher = DlFrames.encrypt(key, iv, plain)
        val back = DlFrames.decrypt(key, iv, cipher)
        assertArrayEquals(plain, back.copyOf(plain.size))
        val left = Secp256k1.privateKey()
        val right = Secp256k1.privateKey()
        assertArrayEquals(Secp256k1.shared(left, Secp256k1.publicKey(right)), Secp256k1.shared(right, Secp256k1.publicKey(left)))
    }

    @Test
    fun advertisementPicksTheProtocol() {
        assertEquals(BoardProtocol.DL, BoardProtocol.detect("DL-BMS", emptyList()))
        assertEquals(BoardProtocol.ANT, BoardProtocol.detect("ANT@24S", emptyList()))
        assertEquals(BoardProtocol.JBD, BoardProtocol.detect("JBD-24S", emptyList()))
        assertEquals(BoardProtocol.JK, BoardProtocol.detect("jk02_32S", emptyList()))
        assertEquals(BoardProtocol.YY, BoardProtocol.detect("HLK-B40", emptyList()))
        assertNull(BoardProtocol.detect("随机设备", emptyList()))
        assertEquals(BoardProtocol.JBD, BoardProtocol.detect("", listOf("0000ff00-0000-1000-8000-00805f9b34fb")))
        assertEquals(BoardProtocol.YY, BoardProtocol.detect(null, listOf("6e400001-b5a3-f393-e0a9-e50e24dcca9e")))
        assertEquals(
            BoardProtocol.DL,
            BoardProtocol.detect(null, listOf(BoardProtocol.FFE0, BoardProtocol.FFE2))
        )
        val kept = rememberBoards(listOf(SavedBoard("旧", "AA", "9999", BoardProtocol.ANT.wire)), "新", "AA")
        assertEquals(BoardProtocol.ANT.wire, kept.first().protocol)
        assertEquals("9999", kept.first().password)
    }

    private fun antStatus(): ByteArray {
        val cells = 16
        val sensors = 2
        val dyn = 2 * cells + 2 * sensors
        val total = 116 + dyn
        val frame = ByteArray(total)
        frame[0] = 0x7E
        frame[1] = 0xA1.toByte()
        frame[2] = 0x11
        frame[5] = (106 + dyn).toByte()
        frame[7] = 3
        frame[8] = sensors.toByte()
        frame[9] = cells.toByte()
        for (i in 0 until cells) put16(frame, 34 + 2 * i, 3300)
        put16(frame, 34 + 2 * cells, 1)
        put16(frame, 36 + 2 * cells, 2)
        put16(frame, 38 + dyn, 5284)
        put16(frame, 40 + dyn, 3)
        put16(frame, 42 + dyn, 91)
        frame[46 + dyn] = 1
        frame[47 + dyn] = 1
        put32(frame, 50 + dyn, 280_000_000L)
        put32(frame, 54 + dyn, 252_602_325L)
        val crc = crc16(frame, 1, 5 + (106 + dyn))
        put16(frame, 112 + dyn, crc)
        frame[114 + dyn] = 0xAA.toByte()
        frame[115 + dyn] = 0x55
        return frame
    }

    private fun jbdBasic(): ByteArray {
        val frame = ByteArray(34)
        frame[0] = 0xDD.toByte()
        frame[1] = 0x03
        frame[3] = 27
        put16be(frame, 4, 5284)
        put16be(frame, 6, -30)
        put16be(frame, 8, 10000)
        put16be(frame, 10, 20000)
        put16be(frame, 12, 42)
        frame[23] = 55
        frame[24] = 3
        frame[25] = 8
        frame[26] = 2
        put16be(frame, 27, 2981)
        put16be(frame, 29, 3031)
        var sum = 0
        for (i in 2..frame.size - 4) sum = (sum + (frame[i].toInt() and 0xFF)) and 0xFFFF
        put16be(frame, 31, -sum)
        frame[33] = 0x77
        return frame
    }

    private fun jbdCells(): ByteArray {
        val frame = ByteArray(7 + 16)
        frame[0] = 0xDD.toByte()
        frame[1] = 0x04
        frame[3] = 16
        for (i in 0 until 8) put16be(frame, 4 + 2 * i, 4100)
        var sum = 0
        for (i in 2..frame.size - 4) sum = (sum + (frame[i].toInt() and 0xFF)) and 0xFFFF
        put16be(frame, frame.size - 3, -sum)
        frame[frame.lastIndex] = 0x77
        return frame
    }

    private fun yyStatus(): ByteArray {
        val frame = ByteArray(189)
        frame[0] = 1
        frame[1] = 3
        frame[2] = 184.toByte()
        frame[3] = 16
        frame[4] = 1
        put32(frame, 5, 52840L)
        put32(frame, 9, -300L)
        for (i in 0 until 16) put16(frame, 15 + 2 * i, 3300)
        frame[3 + 76] = 70
        frame[3 + 77] = 65
        put16(frame, 3 + 86, 2800)
        put16(frame, 3 + 88, 2000)
        frame[3 + 90] = 88
        val crc = crc16(frame, 0, 187)
        put16(frame, 187, crc)
        return frame
    }

    private fun put16(frame: ByteArray, offset: Int, value: Int) {
        frame[offset] = (value and 0xFF).toByte()
        frame[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun put16be(frame: ByteArray, offset: Int, value: Int) {
        frame[offset] = ((value ushr 8) and 0xFF).toByte()
        frame[offset + 1] = (value and 0xFF).toByte()
    }

    private fun put32(frame: ByteArray, offset: Int, value: Long) {
        put16(frame, offset, (value and 0xFFFF).toInt())
        put16(frame, offset + 2, ((value ushr 16) and 0xFFFF).toInt())
    }
}
