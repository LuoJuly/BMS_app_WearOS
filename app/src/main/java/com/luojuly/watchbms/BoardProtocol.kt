package com.luojuly.watchbms

import java.util.Locale

/** One GATT pair. A null write or notify uuid means "any characteristic with that property". */
internal data class GattEndpoint(
    val service: String,
    val write: String?,
    val notify: String?,
    val anyWrite: Boolean = false,
    val anyNotify: Boolean = false
)

/**
 * Protection-board family. JK keeps the existing passworded client.
 * DL, ANT, JBD and 彦阳 are read-only and chosen per board.
 */
internal enum class BoardProtocol(val wire: String, val labelRes: Int) {
    JK("jk", R.string.protocol_jk),
    DL("dl", R.string.protocol_dl),
    ANT("ant", R.string.protocol_ant),
    JBD("jbd", R.string.protocol_jbd),
    YY("yy", R.string.protocol_yy);

    fun next(): BoardProtocol = entries[(ordinal + 1) % entries.size]

    fun endpoints(): List<GattEndpoint> = when (this) {
        JK -> emptyList()
        DL -> listOf(GattEndpoint(FFE0, FFE1, FFE2))
        ANT -> listOf(GattEndpoint(FFE0, FFE1, FFE1))
        JBD -> listOf(GattEndpoint(FF00, FF02, FF01))
        YY -> listOf(
            GattEndpoint(FFE0, null, null, anyWrite = true, anyNotify = true),
            GattEndpoint(FF00, null, null, anyWrite = true, anyNotify = true),
            GattEndpoint(NUS, NUS_WRITE, NUS_NOTIFY)
        )
    }

    companion object {
        const val FFE0 = "0000ffe0-0000-1000-8000-00805f9b34fb"
        const val FFE1 = "0000ffe1-0000-1000-8000-00805f9b34fb"
        const val FFE2 = "0000ffe2-0000-1000-8000-00805f9b34fb"
        const val FF00 = "0000ff00-0000-1000-8000-00805f9b34fb"
        const val FF01 = "0000ff01-0000-1000-8000-00805f9b34fb"
        const val FF02 = "0000ff02-0000-1000-8000-00805f9b34fb"
        const val NUS = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
        const val NUS_WRITE = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
        const val NUS_NOTIFY = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"

        fun fromWire(value: String?): BoardProtocol = entries.firstOrNull { it.wire == value } ?: JK

        /** Name first, then advertised services. Null when the advertisement gives nothing away. */
        fun detect(name: String?, services: Collection<String>): BoardProtocol? {
            detectName(name)?.let { return it }
            return detectServices(services)
        }

        private fun detectName(name: String?): BoardProtocol? {
            val label = name?.trim().orEmpty()
            if (label.isEmpty()) return null
            val lower = label.lowercase(Locale.US)
            if (label.uppercase(Locale.US).startsWith("DL-BMS")) return DL
            if (lower.contains("ant@") || lower.startsWith("ant")) return ANT
            if (lower.contains("jbd") || lower.contains("xiaoxiang")) return JBD
            if (lower.startsWith("jk")) return JK
            if (lower.contains("yanyang") || lower.contains("yybms") || lower.contains("yy-bms") ||
                lower.contains("hlk-b40") || lower.contains("b40")
            ) return YY
            return null
        }

        private fun detectServices(services: Collection<String>): BoardProtocol? {
            val ids = services.map { it.lowercase(Locale.US) }
            if (ids.any { it == FF00 }) return JBD
            if (ids.any { it == NUS }) return YY
            if (ids.any { it == FFE0 } && ids.any { it == FFE2 }) return DL
            return null
        }
    }
}
