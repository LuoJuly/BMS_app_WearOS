package com.luojuly.watchbms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JkProtocolTest {
    @Test
    fun cellFrameReadsPackVoltageCurrentAndResistance() {
        val frame = frame(0x02) {
            putU16(6, 3300)
            putU16(8, 3280)
            putU32(70, 0x3)
            putU16(80, 72)
            putU16(82, 80)
            putU16(144, 310)
            putU32(150, 6580)
            putU32(158, (-1500).toLong() and 0xFFFFFFFFL)
            putU16(162, 284)
            putU32(166, 1L shl 4)
            this[173] = 86.toByte()
            putU32(174, 42100)
            this[198] = 1.toByte()
            this[199] = 1.toByte()
            this[201] = 1.toByte()
        }
        val snapshot = JkProtocol.parseCellInfo(frame)!!
        assertEquals(6.58f, snapshot.totalVoltage, 0.001f)
        assertEquals(-1.5f, snapshot.current, 0.001f)
        assertEquals(86, snapshot.soc)
        assertEquals(2, snapshot.cells.size)
        assertEquals(3.300f, snapshot.cells[0].voltage, 0.0001f)
        assertEquals(0.072f, snapshot.cells[0].resistanceOhm, 0.0001f)
        assertEquals(0.080f, snapshot.cells[1].resistanceOhm, 0.0001f)
        assertEquals(20, snapshot.deltaMv)
        assertEquals(42.1f, snapshot.remainingAh, 0.001f)
        assertEquals(28.4f, snapshot.batteryTempC!!, 0.01f)
        assertEquals("放电", snapshot.chargeState)
        assertEquals(listOf("单体过压"), snapshot.alarmLabels)
        assertTrue(snapshot.balancing)
        assertEquals("72", formatMilliOhm(snapshot.cells[0].resistanceOhm))
    }

    @Test
    fun disabledCellIsHiddenEvenIfVoltageIsPresent() {
        val frame = frame(0x02) {
            putU16(6, 3300)
            putU16(12, 3900)
            putU32(70, 0x1)
            putU32(150, 3300)
        }
        val snapshot = JkProtocol.parseCellInfo(frame)!!
        assertEquals(listOf(1), snapshot.cells.map { it.index })
    }

    @Test
    fun deviceInfoReadsNameAndPassword() {
        val frame = frame(0x03) {
            writeString(46, "JK-TEST")
            writeString(62, "123456")
        }
        val info = JkProtocol.parseDeviceInfo(frame)!!
        assertEquals("JK-TEST", info.name)
        assertEquals("123456", info.password)
    }

    @Test
    fun badChecksumIsRejected() {
        val frame = frame(0x02) {
            putU16(6, 3300)
            putU32(70, 0x1)
            putU32(150, 3300)
        }
        frame[299] = (frame[299] + 1).toByte()
        assertNull(JkProtocol.parseCellInfo(frame))
    }

    @Test
    fun commandFrameHasHeaderCounterAndChecksum() {
        val command = JkProtocol.buildCommand(JkProtocol.COMMAND_CELL_INFO, 7)
        assertEquals(0xAA.toByte(), command[0])
        assertEquals(0x55.toByte(), command[1])
        assertEquals(0x90.toByte(), command[2])
        assertEquals(0xEB.toByte(), command[3])
        assertEquals(0x96.toByte(), command[4])
        assertEquals(7.toByte(), command[16])
        assertEquals(JkProtocol.checksum(command, 19), command[19])
    }

    @Test
    fun assemblerReassemblesSplitNotifications() {
        val frame = frame(0x03) { writeString(62, "123456") }
        val assembler = FrameAssembler()
        assertTrue(assembler.push(byteArrayOf(0x00, 0x11)).isEmpty())
        assertTrue(assembler.push(frame.copyOfRange(0, 100)).isEmpty())
        assertTrue(assembler.push(frame.copyOfRange(100, 200)).isEmpty())
        val frames = assembler.push(frame.copyOfRange(200, 300))
        assertEquals(1, frames.size)
        assertEquals("123456", JkProtocol.parseDeviceInfo(frames[0])!!.password)
    }

    @Test
    fun demoSnapshotCoversEightSixteenAndTwentyFour() {
        listOf(8, 16, 24).forEach { series ->
            val snapshot = demoSnapshot(series)
            assertEquals(series, snapshot.cells.size)
            assertEquals(1, snapshot.minCell)
            assertEquals(series, snapshot.maxCell)
            assertEquals(18, snapshot.deltaMv)
            assertEquals("放电", snapshot.chargeState)
            assertEquals(86, snapshot.soc)
        }
        assertEquals(16, demoSnapshot(7).cells.size)
    }

    @Test
    fun lowestCellsAreBlueAndHighestResistanceStaysRed() {
        assertEquals(CellTone.LOW, cellTone(3.276f, 3.276f, 3.294f, false))
        assertEquals(CellTone.NORMAL, cellTone(3.294f, 3.276f, 3.294f, false))
        assertEquals(CellTone.LOW, cellTone(0.070f, 0.070f, 0.080f, true))
        assertEquals(CellTone.HIGH, cellTone(0.080f, 0.070f, 0.080f, true))
        assertEquals(CellTone.NORMAL, cellTone(0.075f, 0.075f, 0.075f, true))
        val demo = demoSnapshot(24)
        val lowResistance = demo.cells.filter { it.resistanceOhm == demo.cells.minOf { cell -> cell.resistanceOhm } }
        assertEquals(listOf(11, 22), lowResistance.map { it.index })
        assertEquals(0xFF3DDC84.toInt(), WatchDashboard.powerTextColor(0f))
        assertEquals(0xFF3DDC84.toInt(), WatchDashboard.powerTextColor(-978f))
        assertEquals(0xFF3DDC84.toInt(), WatchDashboard.powerTextColor(1000f))
        assertEquals(0xFFFFC14D.toInt(), WatchDashboard.powerTextColor(1001f))
        assertEquals(0xFFFFC14D.toInt(), WatchDashboard.powerTextColor(-2000f))
        assertEquals(0xFFFF5C6A.toInt(), WatchDashboard.powerTextColor(2001f))
        assertEquals(0xFFFF5C6A.toInt(), WatchDashboard.socTextColor(20))
        assertEquals(0xFFFF5C6A.toInt(), WatchDashboard.socTextColor(0))
        assertEquals(0xFFFFC14D.toInt(), WatchDashboard.socTextColor(21))
        assertEquals(0xFF3DDC84.toInt(), WatchDashboard.socTextColor(60))
    }

    @Test
    fun dataPagesWrapFromWeatherThroughResistance() {
        assertEquals(Page.HOME, adjacentDataPage(Page.WEATHER, true))
        assertEquals(Page.RESISTANCE, adjacentDataPage(Page.WEATHER, false))
        assertEquals(Page.CLUSTER, adjacentDataPage(Page.HOME, true))
        assertEquals(Page.WEATHER, adjacentDataPage(Page.HOME, false))
        assertEquals(Page.VOLTAGE, adjacentDataPage(Page.CLUSTER, true))
        assertEquals(Page.HOME, adjacentDataPage(Page.CLUSTER, false))
        assertEquals(Page.WEATHER, adjacentDataPage(Page.RESISTANCE, true))
        assertEquals(Page.VOLTAGE, adjacentDataPage(Page.RESISTANCE, false))
        assertNull(adjacentDataPage(Page.SETTINGS, true))
        assertNull(adjacentDataPage(Page.SETTINGS, false))
        assertEquals(Page.CLUSTER, adjacentDataPage(Page.HOME, true))
        assertEquals(Page.CHARGE, adjacentDataPage(Page.HOME, true, charging = true))
        assertEquals(Page.CLUSTER, adjacentDataPage(Page.CHARGE, true, charging = true))
        assertEquals(Page.HOME, adjacentDataPage(Page.CHARGE, false, charging = true))
        assertNull(adjacentDataPage(Page.CHARGE, true))
    }

    @Test
    fun chargeFrameReadsCyclesAndRemainingTime() {
        val frame = frame(0x02) {
            putU16(6, 3300)
            putU32(70, 0x1)
            putU32(150, 80500)
            putU32(158, 10000)
            this[173] = 77.toByte()
            putU32(174, 40000)
            putU32(178, 50000)
            putU32(182, 36)
        }
        val snapshot = JkProtocol.parseCellInfo(frame)!!
        assertEquals("充电", snapshot.chargeState)
        assertEquals(36, snapshot.cycleCount)
        assertEquals(50f, snapshot.nominalAh, 0.01f)
        assertEquals(60, chargeRemainingMinutes(snapshot.nominalAh, snapshot.remainingAh, snapshot.current))
        assertEquals("01:00", formatChargeRemain(60))
        assertEquals("—", formatChargeRemain(null))
        assertNull(chargeRemainingMinutes(0f, 40f, 10f))
    }

    @Test
    fun absurdCycleCountStaysHidden() {
        val frame = frame(0x02) {
            putU16(6, 3300)
            putU32(70, 0x1)
            putU32(150, 3300)
            putU32(182, 0xFFFFFFFFL)
        }
        assertNull(JkProtocol.parseCellInfo(frame)!!.cycleCount)
    }

    @Test
    fun savedBoardsStayAtTenAndPromoteTheActiveOne() {
        val existing = (1..10).map { SavedBoard("B$it", "A$it", if (it == 3) "9999" else "") }
        val updated = rememberBoards(existing, "新板", "A3")
        assertEquals(10, updated.size)
        assertEquals("A3", updated.first().address)
        assertEquals("新板", updated.first().name)
        assertEquals("9999", updated.first().password)
        val added = rememberBoards(existing, "第十一", "A11")
        assertEquals(10, added.size)
        assertEquals("A11", added.first().address)
        assertEquals("A9", added.last().address)
    }

    @Test
    fun cellBoardShowsThreePerRowWithUnits() {
        assertEquals(3, CellBoardLayout.COLUMNS)
        assertEquals("3.300V", CellBoardLayout.voltText(3.3f))
        assertEquals("4.200V", CellBoardLayout.voltText(4.2f))
        assertEquals("72mΩ", CellBoardLayout.ohmText("72"))
    }

    @Test
    fun multipleAlarmsKeepTheFirstTwo() {
        val frame = frame(0x02) {
            putU16(6, 3200)
            putU32(70, 0x1)
            putU32(150, 3200)
            putU32(166, (1L shl 4) or (1L shl 11) or (1L shl 13))
        }
        assertEquals(listOf("单体过压", "单体欠压 +1"), JkProtocol.parseCellInfo(frame)!!.alarmLabels)
    }

    @Test
    fun chargingPageWaitsForAStationaryHold() {
        assertNull(freshGpsSpeedKmh(null, 1_000L))
        assertNull(freshGpsSpeedKmh(12, 6_000L))
        assertEquals(0, freshGpsSpeedKmh(0, 1_000L))

        assertEquals(0L, nextChargeHold(boardCharging = true, gpsSpeedKmh = 18, holdStartElapsed = 0L, nowElapsed = 1_000L))
        assertEquals(5_000L, nextChargeHold(boardCharging = true, gpsSpeedKmh = 0, holdStartElapsed = 0L, nowElapsed = 5_000L))
        assertEquals(5_000L, nextChargeHold(boardCharging = true, gpsSpeedKmh = null, holdStartElapsed = 5_000L, nowElapsed = 9_000L))
        assertEquals(0L, nextChargeHold(boardCharging = false, gpsSpeedKmh = 0, holdStartElapsed = 5_000L, nowElapsed = 9_000L))
        assertEquals(0L, nextChargeHold(boardCharging = true, gpsSpeedKmh = 6, holdStartElapsed = 5_000L, nowElapsed = 9_000L))

        val start = 5_000L
        assertFalse(chargeConfirmed(start, start + CHARGE_CONFIRM_MS))
        assertTrue(chargeConfirmed(start, start + CHARGE_CONFIRM_MS + 1L))
        assertFalse(chargeConfirmed(0L, start + CHARGE_CONFIRM_MS + 1L))
    }

    private fun frame(type: Int, fill: ByteArray.() -> Unit): ByteArray {
        val data = ByteArray(300)
        data[0] = 0x55.toByte()
        data[1] = 0xAA.toByte()
        data[2] = 0xEB.toByte()
        data[3] = 0x90.toByte()
        data[4] = type.toByte()
        data.fill()
        data[299] = JkProtocol.checksum(data, 299)
        return data
    }

    private fun ByteArray.putU16(offset: Int, value: Int) {
        this[offset] = (value and 0xFF).toByte()
        this[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun ByteArray.putU32(offset: Int, value: Long) {
        putU16(offset, (value and 0xFFFF).toInt())
        putU16(offset + 2, ((value shr 16) and 0xFFFF).toInt())
    }

    private fun ByteArray.writeString(offset: Int, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        bytes.forEachIndexed { index, byte -> this[offset + index] = byte }
    }
}
