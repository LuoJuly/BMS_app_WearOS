package com.luojuly.watchbms

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/** Rolling record of watt-hours used in each of the last [RIDE_WINDOW_KM] kilometres. */
internal const val RIDE_WINDOW_KM = 200

private const val METERS_PER_KM = 1000.0

internal class RideWindow {
    val kmWh = ArrayList<Double>()
    var partialMeters: Double = 0.0
    var partialWh: Double = 0.0

    /**
     * Adds one moving leg. [wattHours] is net energy taken from the pack over [meters]
     * (positive when the pack discharged). Returns true when a full kilometre was stored.
     */
    fun add(meters: Double, wattHours: Double): Boolean {
        if (meters <= 0.0) return false
        val perMeter = wattHours / meters
        var left = meters
        var closed = false
        while (left > 0.0) {
            val room = METERS_PER_KM - partialMeters
            if (left < room) {
                partialMeters += left
                partialWh += perMeter * left
                break
            }
            partialWh += perMeter * room
            push(partialWh.coerceAtLeast(0.0))
            partialMeters = 0.0
            partialWh = 0.0
            left -= room
            closed = true
        }
        return closed
    }

    fun wattHoursPerKm(): Double? {
        if (kmWh.size < RIDE_WINDOW_KM) return null
        val sum = kmWh.sum()
        if (sum <= 0.0) return null
        return sum / RIDE_WINDOW_KM
    }

    private fun push(wh: Double) {
        if (kmWh.size >= RIDE_WINDOW_KM) kmWh.removeAt(0)
        kmWh.add(wh)
    }
}

/** Remaining range in whole kilometres, or null until a full 200 km window exists. */
internal fun remainingRangeKm(window: RideWindow, remainingAh: Float, packVolts: Float): Int? {
    val perKm = window.wattHoursPerKm() ?: return null
    val remainingWh = remainingAh.toDouble() * packVolts.toDouble()
    if (remainingWh <= 0.0) return 0
    return round(remainingWh / perKm).toInt().coerceAtLeast(0)
}

internal fun rideKey(address: String) = "ride:$address"

internal fun encodeRide(window: RideWindow): String {
    return window.partialMeters.toString() + ";" + window.partialWh.toString() + ";" + window.kmWh.joinToString(",")
}

internal fun decodeRide(raw: String?): RideWindow {
    if (raw.isNullOrBlank()) return RideWindow()
    val parts = raw.split(';', limit = 3)
    if (parts.size < 3) return RideWindow()
    val meters = parts[0].toDoubleOrNull() ?: return RideWindow()
    val wattHours = parts[1].toDoubleOrNull() ?: return RideWindow()
    val stored = ArrayList<Double>()
    if (parts[2].isNotEmpty()) {
        val values = parts[2].split(',')
        val start = (values.size - RIDE_WINDOW_KM).coerceAtLeast(0)
        for (index in start until values.size) {
            val wh = values[index].toDoubleOrNull() ?: return RideWindow()
            stored.add(wh.coerceAtLeast(0.0))
        }
    }
    return RideWindow().also {
        it.kmWh.addAll(stored)
        it.partialMeters = meters.coerceIn(0.0, 1000.0)
        it.partialWh = wattHours
    }
}

/** Great-circle distance. Coordinates are not stored; only the metres are kept. */
internal fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val radius = 6_371_000.0
    val phi1 = Math.toRadians(lat1)
    val phi2 = Math.toRadians(lat2)
    val dPhi = Math.toRadians(lat2 - lat1)
    val dLambda = Math.toRadians(lon2 - lon1)
    val a = sin(dPhi / 2).pow(2) + cos(phi1) * cos(phi2) * sin(dLambda / 2).pow(2)
    return 2 * radius * asin(sqrt(a))
}

/**
 * Accepts a GPS leg that looks like real movement. Standing drift and impossible jumps are dropped,
 * so they never enter the 200 km record.
 */
internal fun acceptRideLeg(meters: Double, elapsedSeconds: Double, accuracyMeters: Float): Boolean {
    if (meters <= 0.0 || elapsedSeconds <= 0.0 || elapsedSeconds > 30.0) return false
    if (accuracyMeters < 0f || accuracyMeters > 40f) return false
    val kmh = meters / elapsedSeconds * 3.6
    return kmh in 1.0..120.0
}

/**
 * Speed for the cluster, in whole km/h. GPS fixes are trusted more tightly than network fixes.
 * A reported speed is used when the provider has one; otherwise it is the distance over time.
 */
internal fun fixSpeedKmh(
    reportedMps: Float,
    hasSpeed: Boolean,
    accuracyMeters: Float,
    meters: Double,
    elapsedSeconds: Double,
    network: Boolean
): Int? {
    val accuracyLimit = if (network) 200f else 40f
    if (accuracyMeters > accuracyLimit) return null
    if (hasSpeed && reportedMps >= 0f) {
        val kmh = reportedMps * 3.6
        if (kmh <= 120.0) return round(kmh).toInt()
    }
    if (elapsedSeconds !in 0.5..20.0 || meters < 0.0) return null
    val kmh = meters / elapsedSeconds * 3.6
    if (kmh > 120.0) return null
    return round(kmh).toInt()
}
