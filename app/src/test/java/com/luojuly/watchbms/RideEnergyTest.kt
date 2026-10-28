package com.luojuly.watchbms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RideEnergyTest {
    @Test
    fun rangeUsesTheLastTwoHundredKilometresOnly() {
        val window = RideWindow()
        repeat(200) { index -> window.add(1000.0, 10.0 + index) }
        assertEquals(200, window.kmWh.size)
        window.add(1000.0, 999.0)
        assertEquals(200, window.kmWh.size)
        assertEquals(11.0, window.kmWh.first(), 0.001)
        assertEquals(999.0, window.kmWh.last(), 0.001)
        val perKm = window.wattHoursPerKm()!!
        val expected = (11..209).sum() + 999.0
        assertEquals(expected / 200.0, perKm, 0.001)
    }

    @Test
    fun rangeStaysUnknownUntilTwoHundredKilometresAreStored() {
        val window = RideWindow()
        repeat(199) { window.add(1000.0, 20.0) }
        window.add(900.0, 18.0)
        assertNull(remainingRangeKm(window, 10f, 50f))
        window.add(100.0, 2.0)
        assertEquals(25, remainingRangeKm(window, 10f, 50f))
    }

    @Test
    fun deletingTheRecordDropsItOnDecode() {
        val window = RideWindow()
        window.add(1500.0, 30.0)
        val stored = mapOf(rideKey("AA:BB") to encodeRide(window))
        val kept = stored - rideKey("AA:BB")
        assertNull(kept[rideKey("AA:BB")])
        val restored = decodeRide(null)
        assertEquals(0, restored.kmWh.size)
        assertEquals(1, decodeRide(stored[rideKey("AA:BB")]).kmWh.size)
    }

    @Test
    fun aRegeneratingKilometreDoesNotStoreNegativeEnergy() {
        val window = RideWindow()
        window.add(1000.0, -5.0)
        assertEquals(0.0, window.kmWh.single(), 0.001)
    }

    @Test
    fun gpsDriftAndImpossibleJumpsAreIgnored() {
        assertTrue(acceptRideLeg(100.0, 10.0, 8f))
        assertFalse(acceptRideLeg(2.0, 10.0, 8f))
        assertFalse(acceptRideLeg(1000.0, 1.0, 8f))
        assertFalse(acceptRideLeg(100.0, 10.0, 80f))
        assertFalse(acceptRideLeg(500.0, 40.0, 8f))
        val equator = distanceMeters(0.0, 0.0, 0.0, 1.0)
        assertEquals(111_195.0, equator, 400.0)
        assertEquals(36, fixSpeedKmh(10f, true, 8f, 0.0, 0.0, false))
        assertEquals(18, fixSpeedKmh(0f, false, 80f, 50.0, 10.0, true))
        assertNull(fixSpeedKmh(0f, false, 500f, 50.0, 10.0, true))
        assertNull(fixSpeedKmh(40f, true, 8f, 0.0, 0.0, false))
    }
}
