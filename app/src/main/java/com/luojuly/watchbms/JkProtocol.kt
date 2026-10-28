package com.luojuly.watchbms

import java.util.Locale

/**
 * JK02 32S cell-info frame (300 bytes). Offsets match the public BLE map used by
 * current JK boards: cell voltages at byte 6, cell resistances at byte 80,
 * pack voltage at byte 150. This client only builds the read commands 0x96 / 0x97.
 */
object JkProtocol {
    const val FRAME_SIZE = 300
    const val COMMAND_SIZE = 20
    const val COMMAND_CELL_INFO = 0x96
    const val COMMAND_DEVICE_INFO = 0x97

    private const val TYPE_CELL = 0x02
    private const val TYPE_DEVICE = 0x03

    private const val OFF_CELLS = 6
    private const val OFF_MASK = 70
    private const val OFF_RESISTANCE = 80
    private const val OFF_MOS_TEMP = 144
    private const val OFF_PACK = 150
    private const val OFF_CURRENT = 158
    private const val OFF_TEMP_1 = 162
    private const val OFF_TEMP_2 = 164
    private const val OFF_ERRORS = 166
    private const val OFF_SOC = 173
    private const val OFF_REMAIN = 174
    private const val OFF_NOMINAL = 178
    private const val OFF_CYCLES = 182
    private const val OFF_CHARGE_MOS = 198
    private const val OFF_DISCHARGE_MOS = 199
    private const val OFF_BALANCE = 201
    private const val OFF_HEAT = 215
    private const val OFF_NAME = 46
    private const val OFF_PASSWORD = 62

    private val TEMP_OFFSETS = intArrayOf(OFF_TEMP_1, OFF_TEMP_2, 254, 256, 258)

    private val ALARMS = arrayOf(
        "线阻", "管过温", "串数异常", "电流传感器",
        "单体过压", "总压过高", "充电过流", "充电短路",
        "充电过温", "充电低温", "内部通信", "单体欠压",
        "总压过低", "放电过流", "放电短路", "放电过温",
        "充电管", "放电管"
    )

    fun buildCommand(command: Int, counter: Int): ByteArray {
        val frame = ByteArray(COMMAND_SIZE)
        frame[0] = 0xAA.toByte()
        frame[1] = 0x55.toByte()
        frame[2] = 0x90.toByte()
        frame[3] = 0xEB.toByte()
        frame[4] = (command and 0xFF).toByte()
        frame[16] = (counter and 0xFF).toByte()
        frame[19] = checksum(frame, 19)
        return frame
    }

    fun parseDeviceInfo(frame: ByteArray): DeviceInfo? {
        if (!isFrame(frame, TYPE_DEVICE)) return null
        return DeviceInfo(
            name = cString(frame, OFF_NAME, 16),
            password = cString(frame, OFF_PASSWORD, 16)
        )
    }

    fun parseCellInfo(frame: ByteArray): BmsSnapshot? {
        if (!isFrame(frame, TYPE_CELL)) return null
        val volts = FloatArray(32) { u16(frame, OFF_CELLS + it * 2) / 1000f }
        val ohms = FloatArray(32) { u16(frame, OFF_RESISTANCE + it * 2) / 1000f }
        val mask = u32(frame, OFF_MASK)
        val enabled = (0 until 32).filter { index ->
            if (mask != 0L) ((mask shr index) and 1L) == 1L else volts[index] > 0.05f
        }
        if (enabled.isEmpty()) return null

        var minV = Float.MAX_VALUE
        var maxV = -Float.MAX_VALUE
        var minI = enabled.first()
        var maxI = enabled.first()
        var sumV = 0f
        var sumR = 0f
        enabled.forEach { index ->
            val voltage = volts[index]
            sumV += voltage
            sumR += ohms[index]
            if (voltage < minV) {
                minV = voltage
                minI = index
            }
            if (voltage > maxV) {
                maxV = voltage
                maxI = index
            }
        }
        val cells = enabled.map { index ->
            CellReading(
                index = index + 1,
                voltage = volts[index],
                resistanceOhm = ohms[index]
            )
        }
        val packVoltage = u32(frame, OFF_PACK) / 1000f
        val current = i32(frame, OFF_CURRENT) / 1000f
        val temps = TEMP_OFFSETS.map { i16(frame, it) / 10f }.filter { it in -40f..125f }
        val mos = i16(frame, OFF_MOS_TEMP) / 10f
        val batteryTemp = temps.maxOrNull()
        val alarms = alarmLabels(u32(frame, OFF_ERRORS))
        return BmsSnapshot(
            totalVoltage = packVoltage,
            current = current,
            power = packVoltage * current,
            soc = frame[OFF_SOC].toInt() and 0xFF,
            batteryTempC = batteryTemp,
            mosTempC = if (mos in -40f..125f) mos else null,
            remainingAh = u32(frame, OFF_REMAIN) / 1000f,
            nominalAh = u32(frame, OFF_NOMINAL) / 1000f,
            cycleCount = cycleCount(u32(frame, OFF_CYCLES)),
            deltaMv = kotlin.math.round(kotlin.math.abs(maxV - minV) * 1000f).toInt(),
            minCell = minI + 1,
            maxCell = maxI + 1,
            averageVoltage = sumV / enabled.size,
            averageResistanceOhm = sumR / enabled.size,
            chargingMos = (frame[OFF_CHARGE_MOS].toInt() and 0xFF) == 1,
            dischargingMos = (frame[OFF_DISCHARGE_MOS].toInt() and 0xFF) == 1,
            balancing = (frame[OFF_BALANCE].toInt() and 0xFF) == 1,
            heating = (frame[OFF_HEAT].toInt() and 0xFF) == 1,
            alarmLabels = alarms,
            cells = cells,
            chargeState = when {
                current > 0.3f -> "充电"
                current < -0.3f -> "放电"
                else -> "静置"
            }
        )
    }

    fun checksum(data: ByteArray, length: Int): Byte {
        var sum = 0
        val n = length.coerceAtMost(data.size)
        for (i in 0 until n) sum = (sum + (data[i].toInt() and 0xFF)) and 0xFF
        return sum.toByte()
    }

    private fun isFrame(frame: ByteArray, type: Int): Boolean {
        if (frame.size < FRAME_SIZE) return false
        if (frame[0] != 0x55.toByte() || frame[1] != 0xAA.toByte() ||
            frame[2] != 0xEB.toByte() || frame[3] != 0x90.toByte()
        ) return false
        if ((frame[4].toInt() and 0xFF) != type) return false
        return frame[FRAME_SIZE - 1] == checksum(frame, FRAME_SIZE - 1)
    }

    private fun cycleCount(raw: Long): Int? {
        if (raw > 200_000L) return null
        return raw.toInt()
    }

    private fun alarmLabels(mask: Long): List<String> {
        if (mask == 0L) return emptyList()
        val labels = ArrayList<String>(2)
        var bits = 0
        var pending = mask
        var index = 0
        while (pending != 0L && index < 32) {
            if ((pending and 1L) == 1L) {
                bits++
                if (labels.size < 2) labels.add(ALARMS.getOrNull(index) ?: "告警")
            }
            pending = pending ushr 1
            index++
        }
        if (labels.isEmpty()) return listOf("告警")
        val extra = bits - labels.size
        if (extra > 0) labels[labels.lastIndex] = labels.last() + " +$extra"
        return labels
    }

    private fun cString(data: ByteArray, start: Int, length: Int): String {
        val end = (start + length).coerceAtMost(data.size)
        var stop = start
        while (stop < end && data[stop] != 0.toByte()) stop++
        return data.copyOfRange(start, stop).toString(Charsets.UTF_8).trim()
    }

    private fun u16(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun u32(data: ByteArray, offset: Int): Long {
        return (u16(data, offset).toLong() and 0xFFFFL) or
            ((u16(data, offset + 2).toLong() and 0xFFFFL) shl 16)
    }

    private fun i16(data: ByteArray, offset: Int): Int {
        val value = u16(data, offset)
        return if (value and 0x8000 != 0) value - 0x10000 else value
    }

    private fun i32(data: ByteArray, offset: Int): Int = u32(data, offset).toInt()
}

class FrameAssembler {
    private val buffer = ArrayList<Byte>(360)

    fun clear() = buffer.clear()

    fun push(chunk: ByteArray): List<ByteArray> {
        chunk.forEach { buffer.add(it) }
        val frames = ArrayList<ByteArray>(1)
        while (true) {
            val start = indexOfHeader()
            if (start < 0) {
                if (buffer.size > 3) {
                    val keep = buffer.takeLast(3)
                    buffer.clear()
                    buffer.addAll(keep)
                }
                break
            }
            if (start > 0) repeat(start) { buffer.removeAt(0) }
            if (buffer.size < JkProtocol.FRAME_SIZE) break
            frames.add(ByteArray(JkProtocol.FRAME_SIZE) { buffer[it] })
            repeat(JkProtocol.FRAME_SIZE) { buffer.removeAt(0) }
        }
        return frames
    }

    private fun indexOfHeader(): Int {
        if (buffer.size < 4) return -1
        for (i in 0..buffer.size - 4) {
            if (buffer[i] == 0x55.toByte() &&
                buffer[i + 1] == 0xAA.toByte() &&
                buffer[i + 2] == 0xEB.toByte() &&
                buffer[i + 3] == 0x90.toByte()
            ) return i
        }
        return -1
    }
}

data class DeviceInfo(val name: String, val password: String)

data class CellReading(
    val index: Int,
    val voltage: Float,
    val resistanceOhm: Float
)

data class BmsSnapshot(
    val totalVoltage: Float,
    val current: Float,
    val power: Float,
    val soc: Int,
    val batteryTempC: Float?,
    val mosTempC: Float?,
    val remainingAh: Float,
    val nominalAh: Float,
    val cycleCount: Int?,
    val deltaMv: Int,
    val minCell: Int,
    val maxCell: Int,
    val averageVoltage: Float,
    val averageResistanceOhm: Float,
    val chargingMos: Boolean,
    val dischargingMos: Boolean,
    val balancing: Boolean,
    val heating: Boolean,
    val alarmLabels: List<String>,
    val cells: List<CellReading>,
    val chargeState: String,
    val hasResistance: Boolean = true
)

internal fun demoSnapshot(series: Int): BmsSnapshot {
    val count = when (series) {
        8, 24 -> series
        else -> 16
    }
    val cells = (1..count).map { index ->
        val milliVolt = when (index) {
            1 -> 3276
            count -> 3294
            else -> 3282 + (index % 7)
        }
        CellReading(
            index = index,
            voltage = milliVolt / 1000f,
            resistanceOhm = (70 + (index % 11)) / 1000f
        )
    }
    val min = cells.minBy { it.voltage }
    val max = cells.maxBy { it.voltage }
    val pack = cells.sumOf { it.voltage.toDouble() }.toFloat()
    val current = -12.4f
    return BmsSnapshot(
        totalVoltage = pack,
        current = current,
        power = pack * current,
        soc = 86,
        batteryTempC = 28.4f,
        mosTempC = 31f,
        remainingAh = 42.1f,
        nominalAh = 0f,
        cycleCount = null,
        deltaMv = kotlin.math.round((max.voltage - min.voltage) * 1000f).toInt(),
        minCell = min.index,
        maxCell = max.index,
        averageVoltage = pack / count,
        averageResistanceOhm = cells.sumOf { it.resistanceOhm.toDouble() }.toFloat() / count,
        chargingMos = true,
        dischargingMos = true,
        balancing = true,
        heating = false,
        alarmLabels = emptyList(),
        cells = cells,
        chargeState = "放电"
    )
}

/** Minutes until full. Null when nominal capacity or charge current is missing. */
internal fun chargeRemainingMinutes(nominalAh: Float, remainingAh: Float, currentA: Float): Int? {
    if (currentA <= 0.3f || nominalAh <= 0f) return null
    val missing = nominalAh - remainingAh
    if (missing <= 0f) return 0
    val minutes = missing / currentA * 60.0
    if (minutes > 99 * 60.0) return null
    return kotlin.math.round(minutes).toInt()
}

internal fun formatChargeRemain(minutes: Int?): String {
    if (minutes == null) return "—"
    val hours = minutes / 60
    val rest = minutes % 60
    return String.format(Locale.US, "%02d:%02d", hours, rest)
}

fun formatMilliOhm(ohm: Float): String {
    val milli = ohm * 1000f
    return if (milli >= 10f) String.format(Locale.US, "%.0f", milli)
    else String.format(Locale.US, "%.1f", milli)
}

internal const val DEFAULT_BOARD_PASSWORD = "1234"

data class SavedBoard(
    val name: String,
    val address: String,
    val password: String = "",
    val protocol: String = BoardProtocol.JK.wire
)

/**
 * JK02 cell and device frames do not include vehicle speed or remaining range.
 * Speed and the 128 km figure are only the disconnected demo. A connected board uses
 * [remainingRangeKm] once that board has its own 200 km record.
 */
internal object DemoRide {
    const val RANGE_KM = 128
    const val SPEED_KMH = 36
}

internal fun rememberBoards(
    existing: List<SavedBoard>,
    name: String,
    address: String,
    limit: Int = 10,
    protocol: String = ""
): List<SavedBoard> {
    val previous = existing.firstOrNull { it.address == address }
    val kept = existing.filter { it.address != address }
    val board = SavedBoard(
        name.ifBlank { "保护板" },
        address,
        previous?.password.orEmpty(),
        protocol.ifBlank { previous?.protocol ?: BoardProtocol.JK.wire }
    )
    return (listOf(board) + kept).take(limit)
}
