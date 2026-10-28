package com.luojuly.watchbms

internal const val CHARGE_CONFIRM_MS = 10_000L
internal const val GPS_SPEED_FRESH_MS = 5_000L

/** A GPS speed is usable only while the fix is still fresh. Null means the speed check is skipped. */
internal fun freshGpsSpeedKmh(speedKmh: Int?, ageMs: Long, freshMs: Long = GPS_SPEED_FRESH_MS): Int? {
    if (speedKmh == null || ageMs < 0L || ageMs > freshMs) return null
    return speedKmh
}

/**
 * Starts or keeps a hold while the board reports charging and GPS speed is 0 or unavailable.
 * A moving GPS fix clears the hold so regenerative braking does not count.
 */
internal fun nextChargeHold(
    boardCharging: Boolean,
    gpsSpeedKmh: Int?,
    holdStartElapsed: Long,
    nowElapsed: Long
): Long {
    val moving = gpsSpeedKmh != null && gpsSpeedKmh != 0
    if (!boardCharging || moving) return 0L
    return if (holdStartElapsed == 0L) nowElapsed else holdStartElapsed
}

/** Charging is confirmed only after the hold has lasted more than [confirmMs]. */
internal fun chargeConfirmed(holdStartElapsed: Long, nowElapsed: Long, confirmMs: Long = CHARGE_CONFIRM_MS): Boolean {
    return holdStartElapsed != 0L && nowElapsed - holdStartElapsed > confirmMs
}
