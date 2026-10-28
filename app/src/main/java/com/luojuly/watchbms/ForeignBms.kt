package com.luojuly.watchbms

import java.io.ByteArrayOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

internal class ForeignAccept(
    val snapshot: BmsSnapshot? = null,
    val becameReady: Boolean = false
)

/**
 * Read-only session for DL, ANT, JBD and 彦阳.
 * Current uses this app's sign: positive is charging. Those boards report the opposite.
 */
internal class ForeignLink {
    private var protocol = BoardProtocol.JK
    private val ant = NotifyBuffer(2, ::antHeader, ::antLength, ::antTake)
    private val jbd = NotifyBuffer(1, ::jbdHeader, ::jbdLength, ::jbdTake)
    private val yy = NotifyBuffer(2, ::yyHeader, ::yyLength, ::yyTake)
    private var jbdBasic: PackHold? = null
    private var jbdCells = IntArray(0)
    private val dlIn = ByteArrayOutputStream()
    private var dlKey: ByteArray? = null
    private var dlIv: ByteArray? = null
    private var dlPrivate: ByteArray? = null
    private var dlReady = false
    private var dlSerial = 0

    fun start(next: BoardProtocol) {
        protocol = next
        ant.clear()
        jbd.clear()
        yy.clear()
        jbdBasic = null
        jbdCells = IntArray(0)
        dlIn.reset()
        dlKey = null
        dlIv = null
        dlPrivate = null
        dlReady = false
        dlSerial = 0
    }

    fun usesHandshake(): Boolean = protocol == BoardProtocol.DL

    fun opening(): List<ByteArray> = when (protocol) {
        BoardProtocol.DL -> {
            val secret = Secp256k1.privateKey()
            dlPrivate = secret
            listOf(DlFrames.frame(DlFrames.FC_KEY, Secp256k1.publicKey(secret), ++dlSerial))
        }
        BoardProtocol.ANT -> listOf(ANT_STATUS)
        BoardProtocol.JBD -> listOf(JBD_BASIC)
        BoardProtocol.YY -> listOf(YY_READ)
        BoardProtocol.JK -> emptyList()
    }

    fun poll(now: Long, lastDataAt: Long): List<ByteArray> = when (protocol) {
        BoardProtocol.ANT -> listOf(ANT_STATUS)
        BoardProtocol.JBD -> listOf(JBD_BASIC)
        BoardProtocol.YY -> listOf(YY_READ)
        BoardProtocol.DL -> if (dlReady) listOf(encrypt(DlFrames.frame(DlFrames.FC_DATA, ByteArray(0), ++dlSerial))) else emptyList()
        BoardProtocol.JK -> emptyList()
    }

    fun followUp(): ByteArray? = if (protocol == BoardProtocol.JBD) JBD_CELLS else null

    fun followUpDelayMs(): Long = if (protocol == BoardProtocol.JBD) 120L else 0L

    fun accept(value: ByteArray, now: Long): ForeignAccept {
        if (value.isEmpty()) return ForeignAccept()
        return when (protocol) {
            BoardProtocol.ANT -> ForeignAccept(ant.push(value, now))
            BoardProtocol.JBD -> ForeignAccept(jbd.push(value, now))
            BoardProtocol.YY -> ForeignAccept(yy.push(value, now))
            BoardProtocol.DL -> acceptDl(value)
            BoardProtocol.JK -> ForeignAccept()
        }
    }

    private fun acceptDl(value: ByteArray): ForeignAccept {
        dlIn.write(value)
        val raw = dlIn.toByteArray()
        if (!dlReady) {
            if (raw.size >= 4 && !DlFrames.isResponse(raw)) {
                dlIn.reset()
                return ForeignAccept()
            }
            val response = DlFrames.parse(raw) ?: return ForeignAccept()
            dlIn.reset()
            if (response.fc != DlFrames.FC_KEY || response.content.size != 64) return ForeignAccept()
            val secret = dlPrivate ?: return ForeignAccept()
            val shared = Secp256k1.shared(secret, response.content)
            dlKey = shared.copyOfRange(0, 16)
            dlIv = shared.copyOfRange(16, 32)
            dlReady = true
            return ForeignAccept(becameReady = true)
        }
        if (raw.size % 16 != 0) return ForeignAccept()
        val plain = try {
            DlFrames.decrypt(dlKey ?: return ForeignAccept(), dlIv ?: return ForeignAccept(), raw)
        } catch (_: RuntimeException) {
            dlIn.reset()
            return ForeignAccept()
        }
        val response = DlFrames.parse(plain)
        dlIn.reset()
        if (response == null || response.fc != DlFrames.FC_DATA) return ForeignAccept()
        return ForeignAccept(DlFrames.parseData(response.content))
    }

    private fun encrypt(frame: ByteArray): ByteArray {
        val key = dlKey ?: return frame
        val iv = dlIv ?: return frame
        return DlFrames.encrypt(key, iv, frame)
    }

    private fun antTake(frame: ByteArray, size: Int, now: Long): BmsSnapshot? {
        if ((frame[2].toInt() and 0xFF) != 0x11) return null
        val sensors = frame[8].toInt() and 0xFF
        val count = frame[9].toInt() and 0xFF
        if (sensors > 6 || count !in 1..32) return null
        val dyn = 2 * count + 2 * sensors
        if (112 + dyn > size - 4) return null
        val cells = IntArray(count) { u16(frame, 34 + 2 * it) }
        val temps = if (sensors == 0) intArrayOf(s16(frame, 34 + dyn))
        else IntArray(sensors) { s16(frame, 34 + 2 * count + 2 * it) }
        val volts = u16(frame, 38 + dyn) * 0.01f
        val current = -s16(frame, 40 + dyn) * 0.1f
        val chargeMos = (frame[46 + dyn].toInt() and 0xFF) != 0
        val dischargeMos = (frame[47 + dyn].toInt() and 0xFF) != 0
        return foreignSnapshot(
            cells, volts, current, u16(frame, 42 + dyn),
            u32(frame, 54 + dyn) / 1_000_000f, u32(frame, 50 + dyn) / 1_000_000f,
            null, temps, chargeMos, dischargeMos
        )
    }

    private fun jbdTake(frame: ByteArray, size: Int, now: Long): BmsSnapshot? {
        if ((frame[2].toInt() and 0xFF) != 0) return null
        return when (frame[1].toInt() and 0xFF) {
            0x03 -> jbdBasic(frame, size)
            0x04 -> jbdCells(frame, size)
            else -> null
        }
    }

    private fun jbdBasic(frame: ByteArray, size: Int): BmsSnapshot? {
        val payload = size - 7
        if (payload < 23) return null
        val volts = u16be(frame, 4) * 0.01f
        val current = s16be(frame, 6) * 0.01f
        val remaining = u16be(frame, 8) * 0.01f
        val capacity = u16be(frame, 10) * 0.01f
        if (volts > 1000f || abs(current) > 5000f || capacity > 1_000_000f) return null
        val tempCount = frame[26].toInt() and 0xFF
        val slots = min(min(tempCount, 6), (payload - 23) / 2).coerceAtLeast(0)
        val temps = ArrayList<Int>(slots)
        for (i in 0 until slots) {
            val celsius = (u16be(frame, 27 + 2 * i) - 2731) / 10
            if (celsius in -80..200) temps.add(celsius)
        }
        jbdBasic = PackHold(
            volts, current, remaining, capacity, u16be(frame, 12),
            (frame[23].toInt() and 0xFF).coerceAtMost(100),
            (frame[24].toInt() and 0x01) != 0,
            (frame[24].toInt() and 0x02) != 0,
            temps.toIntArray()
        )
        return publishJbd()
    }

    private fun jbdCells(frame: ByteArray, size: Int): BmsSnapshot? {
        val payload = size - 7
        if (payload < 2 || payload % 2 != 0) return null
        val count = min(payload / 2, 32)
        jbdCells = IntArray(count) { u16be(frame, 4 + 2 * it) }
        return publishJbd()
    }

    private fun publishJbd(): BmsSnapshot? {
        val held = jbdBasic ?: return null
        if (jbdCells.isEmpty()) return null
        return foreignSnapshot(
            jbdCells, held.volts, held.current, held.soc, held.remainingAh, held.nominalAh,
            held.cycles, held.temps, held.chargingMos, held.dischargingMos
        )
    }

    private fun yyTake(frame: ByteArray, size: Int, now: Long): BmsSnapshot? {
        if (size != 189 || frame[0] != 1.toByte() || frame[1] != 3.toByte() || (frame[2].toInt() and 0xFF) != 184) return null
        val count = frame[3].toInt() and 0xFF
        if (count !in 1..32) return null
        val volts = u32(frame, 5) * 0.001f
        val current = -s32(frame, 9) * 0.01f
        val nominal = u16(frame, 3 + 86) * 0.1f
        val remaining = u16(frame, 3 + 88) * 0.1f
        if (volts < 1f || volts > 1000f || abs(current) > 5000f || nominal > 1_000_000f) return null
        val cells = IntArray(count) { u16(frame, 3 + 12 + 2 * it) }
        val temps = intArrayOf((frame[3 + 77].toInt() and 0xFF) - 40, (frame[3 + 76].toInt() and 0xFF) - 40)
        val run = u32(frame, 3 + 154)
        val chargingMos = ((run ushr 28) and 1L) == 0L
        val dischargingMos = ((run ushr 29) and 1L) == 0L
        return foreignSnapshot(
            cells, volts, current, frame[3 + 90].toInt() and 0xFF, remaining, nominal,
            null, temps, chargingMos, dischargingMos
        )
    }

    private class PackHold(
        val volts: Float,
        val current: Float,
        val remainingAh: Float,
        val nominalAh: Float,
        val cycles: Int,
        val soc: Int,
        val chargingMos: Boolean,
        val dischargingMos: Boolean,
        val temps: IntArray
    )
}

private const val NEED_MORE = -1
private const val BAD = -2

private class NotifyBuffer(
    private val headerLength: Int,
    private val atHeader: (ByteArray, Int) -> Boolean,
    private val frameLength: (ByteArray, Int) -> Int,
    private val take: (ByteArray, Int, Long) -> BmsSnapshot?
) {
    private var buffer = ByteArray(512)
    private var length = 0
    private var lastAt = 0L

    fun clear() {
        length = 0
        lastAt = 0L
    }

    fun push(value: ByteArray, now: Long): BmsSnapshot? {
        if (now - lastAt > 1000L) length = 0
        lastAt = now
        if (length + value.size > buffer.size) {
            buffer = buffer.copyOf(maxOf(buffer.size * 2, length + value.size))
        }
        value.copyInto(buffer, length)
        length += value.size
        var out: BmsSnapshot? = null
        while (length >= headerLength) {
            val start = headerIndex()
            if (start < 0) {
                val keep = min(length, (headerLength - 1).coerceAtLeast(0))
                if (keep < length) drop(length - keep)
                break
            }
            if (start > 0) drop(start)
            val size = frameLength(buffer, length)
            if (size == NEED_MORE) break
            if (size == BAD) {
                drop(1)
                continue
            }
            if (size > length) break
            val parsed = take(buffer.copyOf(size), size, now)
            if (parsed != null) out = parsed
            drop(size)
        }
        return out
    }

    private fun headerIndex(): Int {
        for (i in 0..length - headerLength) if (atHeader(buffer, i)) return i
        return -1
    }

    private fun drop(count: Int) {
        buffer.copyInto(buffer, 0, count, length)
        length -= count
    }
}

private fun antHeader(buffer: ByteArray, offset: Int): Boolean {
    return (buffer[offset].toInt() and 0xFF) == 0x7E && (buffer[offset + 1].toInt() and 0xFF) == 0xA1
}

private fun antLength(buffer: ByteArray, length: Int): Int {
    if (length < 6) return NEED_MORE
    val declared = 10 + (buffer[5].toInt() and 0xFF)
    val payload = buffer[5].toInt() and 0xFF
    if ((buffer[2].toInt() and 0xFF) != 0x11) {
        if (length < declared) return NEED_MORE
        if ((buffer[declared - 2].toInt() and 0xFF) != 0xAA || (buffer[declared - 1].toInt() and 0xFF) != 0x55) return BAD
        return if (crc16(buffer, 1, 5 + payload) == u16(buffer, declared - 4)) declared else BAD
    }
    if (length < declared) return NEED_MORE
    val valid = crc16(buffer, 1, 5 + payload) == u16(buffer, declared - 4)
    if ((buffer[declared - 2].toInt() and 0xFF) == 0xAA && (buffer[declared - 1].toInt() and 0xFF) == 0x55) {
        return if (valid) declared else BAD
    }
    val limit = min(length - 2, declared + 16)
    var index = declared
    while (index <= limit) {
        if ((buffer[index].toInt() and 0xFF) == 0xAA && (buffer[index + 1].toInt() and 0xFF) == 0x55) {
            return if (crc16(buffer, 1, 5 + payload) == u16(buffer, index - 2)) index + 2 else BAD
        }
        index++
    }
    return if (length <= declared + 18) NEED_MORE else BAD
}

private fun jbdHeader(buffer: ByteArray, offset: Int): Boolean = (buffer[offset].toInt() and 0xFF) == 0xDD

private fun jbdLength(buffer: ByteArray, length: Int): Int {
    if (length < 4) return NEED_MORE
    val size = (buffer[3].toInt() and 0xFF) + 7
    if (length < size) return NEED_MORE
    if ((buffer[size - 1].toInt() and 0xFF) != 0x77) return BAD
    val stored = ((buffer[size - 3].toInt() and 0xFF) shl 8) or (buffer[size - 2].toInt() and 0xFF)
    if (stored == sum16(buffer, 2, size - 5) || stored == sum16(buffer, 1, size - 4)) return size
    return BAD
}

private fun yyHeader(buffer: ByteArray, offset: Int): Boolean {
    val func = buffer[offset + 1].toInt() and 0xFF
    return (buffer[offset].toInt() and 0xFF) == 1 && (func == 3 || func == 0x83)
}

private fun yyLength(buffer: ByteArray, length: Int): Int {
    if (length < 3) return NEED_MORE
    val size = if ((buffer[1].toInt() and 0xFF) == 0x83) 5 else (buffer[2].toInt() and 0xFF) + 5
    if (length < size) return NEED_MORE
    return if (crc16(buffer, 0, size - 2) == u16(buffer, size - 2)) size else BAD
}

private val ANT_STATUS = antRequest(0x01, 0x0000, 0xBE)
private val JBD_BASIC = jbdRequest(0x03)
private val JBD_CELLS = jbdRequest(0x04)
private val YY_READ = yyRequest()

private fun antRequest(func: Int, address: Int, value: Int): ByteArray {
    val frame = ByteArray(10)
    frame[0] = 0x7E
    frame[1] = 0xA1.toByte()
    frame[2] = func.toByte()
    frame[3] = (address and 0xFF).toByte()
    frame[4] = ((address ushr 8) and 0xFF).toByte()
    frame[5] = value.toByte()
    val crc = crc16(frame, 1, 5)
    frame[6] = (crc and 0xFF).toByte()
    frame[7] = ((crc ushr 8) and 0xFF).toByte()
    frame[8] = 0xAA.toByte()
    frame[9] = 0x55
    return frame
}

private fun jbdRequest(cmd: Int): ByteArray {
    val frame = ByteArray(7)
    frame[0] = 0xDD.toByte()
    frame[1] = 0xA5.toByte()
    frame[2] = cmd.toByte()
    val sum = (-cmd) and 0xFFFF
    frame[4] = (sum ushr 8).toByte()
    frame[5] = sum.toByte()
    frame[6] = 0x77
    return frame
}

private fun yyRequest(): ByteArray {
    val frame = ByteArray(8)
    frame[0] = 1
    frame[1] = 3
    frame[3] = 0x4B
    frame[5] = 0x5C
    val crc = crc16(frame, 0, 6)
    frame[6] = (crc and 0xFF).toByte()
    frame[7] = ((crc ushr 8) and 0xFF).toByte()
    return frame
}

internal object DlFrames {
    const val FC_KEY = 0x00
    const val FC_DATA = 0x17
    private const val HEADER = 13

    class Response(val fc: Int, val content: ByteArray)

    fun frame(fc: Int, content: ByteArray, serial: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0xA5)
        out.write(0x5A)
        out.write(0x00)
        out.write(0x40)
        out.write(fc and 0xFF)
        le16(out, serial)
        le16(out, 1)
        le16(out, 1)
        le16(out, content.size)
        if (content.size <= 4) {
            for (i in content.indices.reversed()) out.write(content[i].toInt())
        } else {
            out.write(content)
        }
        val body = out.toByteArray()
        val sum = checksum(body, body.size)
        out.write(sum and 0xFF)
        out.write((sum ushr 8) and 0xFF)
        return out.toByteArray()
    }

    fun isResponse(buffer: ByteArray): Boolean {
        return buffer.size >= 4 && (buffer[0].toInt() and 0xFF) == 0x5A && (buffer[1].toInt() and 0xFF) == 0xA5 &&
            (buffer[2].toInt() and 0xFF) == 0x00 && (buffer[3].toInt() and 0xFF) == 0x40
    }

    fun parse(buffer: ByteArray): Response? {
        if (!isResponse(buffer) || buffer.size < HEADER + 2) return null
        val contentLength = u16(buffer, 11)
        val size = HEADER + contentLength + 2
        if (size > buffer.size) return null
        if (checksum(buffer, size - 2) != u16(buffer, size - 2)) return null
        return Response(buffer[4].toInt() and 0xFF, buffer.copyOfRange(HEADER, HEADER + contentLength))
    }

    fun parseData(content: ByteArray): BmsSnapshot? {
        val full = content.size >= 142 || (content.size >= 78 && (content[0].toInt() and 0xFF) in 0x20..0x7E)
        return if (full) parseFull(content) else if (content.size >= 46) parseShort(content) else null
    }

    fun encrypt(key: ByteArray, iv: ByteArray, plain: ByteArray): ByteArray {
        val padded = ByteArray((plain.size + 15) / 16 * 16)
        plain.copyInto(padded)
        return cipher(Cipher.ENCRYPT_MODE, key, iv, padded)
    }

    fun decrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        require(data.isNotEmpty() && data.size % 16 == 0)
        return cipher(Cipher.DECRYPT_MODE, key, iv, data)
    }

    private fun parseFull(content: ByteArray): BmsSnapshot? {
        val count = content[33].toInt() and 0xFF
        val cells = readCells(content, 78, count)
        return foreignSnapshot(
            cells,
            u16(content, 42) / 10f,
            -s16(content, 44) / 10f,
            u16(content, 50),
            u32(content, 38) / 10f,
            u32(content, 34) / 10f,
            cycleCount(u32(content, 74)),
            readTemps(content, 52),
            (content[32].toInt() and 0x01) != 0,
            (content[32].toInt() and 0x02) != 0
        )
    }

    private fun parseShort(content: ByteArray): BmsSnapshot? {
        val count = content[1].toInt() and 0xFF
        val cells = readCells(content, 46, count)
        return foreignSnapshot(
            cells,
            u16(content, 10) / 10f,
            -s16(content, 12) / 10f,
            u16(content, 18),
            u32(content, 6) / 10f,
            u32(content, 2) / 10f,
            null,
            readTemps(content, 20),
            (content[0].toInt() and 0x01) != 0,
            (content[0].toInt() and 0x02) != 0
        )
    }

    private fun readTemps(content: ByteArray, countOffset: Int): IntArray {
        val count = min(9, content[countOffset].toInt() and 0xFF)
        if (countOffset + 1 + count > content.size) return IntArray(0)
        return IntArray(count) { content[countOffset + 1 + it].toInt() }
    }

    private fun readCells(content: ByteArray, offset: Int, declared: Int): IntArray {
        val count = min(min(declared, 32), (content.size - offset) / 2)
        if (count <= 0) return IntArray(0)
        return IntArray(count) { index ->
            var mv = u16(content, offset + index * 2)
            if (mv > 10000) mv = (mv / 13.4f).roundToInt()
            mv
        }
    }

    private fun cycleCount(raw: Long): Int? {
        if (raw > 200_000L) return null
        return raw.toInt()
    }

    private fun cipher(mode: Int, key: ByteArray, iv: ByteArray, input: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(input)
    }

    private fun checksum(data: ByteArray, length: Int): Int {
        var sum = 0
        for (i in 0 until length) sum += data[i].toInt() and 0xFF
        return sum and 0xFFFF
    }

    private fun le16(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
    }
}

private fun foreignSnapshot(
    cellMv: IntArray,
    volts: Float,
    current: Float,
    soc: Int,
    remainingAh: Float,
    nominalAh: Float,
    cycleCount: Int?,
    temps: IntArray,
    chargingMos: Boolean,
    dischargingMos: Boolean
): BmsSnapshot? {
    if (cellMv.isEmpty() || volts < 1f || volts > 1000f || abs(current) > 5000f) return null
    val cells = cellMv.mapIndexed { index, mv -> CellReading(index + 1, mv / 1000f, 0f) }
    val span = cells.filter { it.voltage in 0.1f..6.5f }.ifEmpty { cells }
    val min = span.minBy { it.voltage }
    val max = span.maxBy { it.voltage }
    val warm = temps.map { it.toFloat() }.filter { it in -40f..125f }
    return BmsSnapshot(
        totalVoltage = volts,
        current = current,
        power = volts * current,
        soc = soc.coerceIn(0, 100),
        batteryTempC = warm.maxOrNull(),
        mosTempC = null,
        remainingAh = remainingAh,
        nominalAh = nominalAh,
        cycleCount = cycleCount,
        deltaMv = kotlin.math.round(abs(max.voltage - min.voltage) * 1000f).toInt(),
        minCell = min.index,
        maxCell = max.index,
        averageVoltage = span.sumOf { it.voltage.toDouble() }.toFloat() / span.size,
        averageResistanceOhm = 0f,
        chargingMos = chargingMos,
        dischargingMos = dischargingMos,
        balancing = false,
        heating = false,
        alarmLabels = emptyList(),
        cells = cells,
        chargeState = when {
            current > 0.3f -> "充电"
            current < -0.3f -> "放电"
            else -> "静置"
        },
        hasResistance = false
    )
}

internal fun crc16(data: ByteArray, offset: Int, length: Int): Int {
    var crc = 0xFFFF
    for (i in offset until offset + length) {
        crc = crc xor (data[i].toInt() and 0xFF)
        repeat(8) { crc = if ((crc and 1) != 0) (crc ushr 1) xor 0xA001 else crc ushr 1 }
    }
    return crc and 0xFFFF
}

private fun sum16(data: ByteArray, offset: Int, length: Int): Int {
    var sum = 0
    for (i in offset until offset + length) sum = (sum + (data[i].toInt() and 0xFF)) and 0xFFFF
    return (-sum) and 0xFFFF
}

private fun u16(data: ByteArray, offset: Int): Int {
    return (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
}

private fun s16(data: ByteArray, offset: Int): Int = u16(data, offset).toShort().toInt()

private fun u32(data: ByteArray, offset: Int): Long {
    return (u16(data, offset).toLong() or (u16(data, offset + 2).toLong() shl 16)) and 0xFFFFFFFFL
}

private fun s32(data: ByteArray, offset: Int): Int = u32(data, offset).toInt()

private fun u16be(data: ByteArray, offset: Int): Int {
    return ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
}

private fun s16be(data: ByteArray, offset: Int): Int = u16be(data, offset).toShort().toInt()
