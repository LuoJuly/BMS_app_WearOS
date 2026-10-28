package com.luojuly.watchbms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherTest {
    @Test
    fun gpsBeatsAFreshNetworkFix() {
        val now = 1_000_000L
        val gps = GeoFix(31.2, 121.5, now - 60_000L, false)
        val network = GeoFix(31.0, 121.0, now - 1_000L, true)
        assertEquals(gps, chooseWeatherFix(gps, network, now))
    }

    @Test
    fun networkIsUsedWhenGpsIsStale() {
        val now = 1_000_000L
        val gps = GeoFix(31.2, 121.5, now - 11 * 60_000L, false)
        val network = GeoFix(31.0, 121.0, now - 5 * 60_000L, true)
        assertEquals(network, chooseWeatherFix(gps, network, now))
    }

    @Test
    fun noFixWhenBothAreOlderThanSixHours() {
        val now = 10 * 60 * 60_000L
        val gps = GeoFix(31.2, 121.5, 0L, false)
        assertNull(chooseWeatherFix(gps, null, now))
    }

    @Test
    fun readsOpenMeteoPayload() {
        val weather = """
            {"current_units":{"temperature_2m":"°C","weather_code":"wmo code","wind_speed_10m":"km/h"},
            "current":{"temperature_2m":26.4,"relative_humidity_2m":62,"apparent_temperature":28.2,"weather_code":1,"wind_speed_10m":12.6}}
        """.trimIndent()
        val place = """{"results":[{"name":"浦东新区","admin1":"上海"}]}"""
        val report = readWeather(weather, place)
        assertEquals("浦东新区", report.place)
        assertEquals("大部晴", report.phrase)
        assertEquals(26, report.temperatureC)
        assertEquals(28, report.feelsC)
        assertEquals(62, report.humidity)
        assertEquals(13, report.windKmh)
        assertEquals("26°", report.toView().temperature)
    }

    @Test
    fun placeFailureStillKeepsTheWeather() {
        val weather = """{"current":{"temperature_2m":5,"relative_humidity_2m":40,"apparent_temperature":2,"weather_code":61,"wind_speed_10m":8}}"""
        val report = readWeather(weather, null)
        assertEquals("当前位置", report.place)
        assertEquals("小雨", report.phrase)
    }

    @Test
    fun refreshAfterTwentyMinutesOrThreeKilometres() {
        val fix = GeoFix(31.2, 121.5, 0L, false)
        assertFalse(shouldRefreshWeather(true, 1_000L, 31.2, 121.5, fix, 60_000L))
        assertTrue(shouldRefreshWeather(true, 0L, 31.2, 121.5, fix, 20 * 60_000L))
        assertTrue(shouldRefreshWeather(false, 0L, 31.2, 121.5, fix, 1L))
        val moved = GeoFix(31.24, 121.5, 0L, false)
        assertTrue(shouldRefreshWeather(true, 1_000L, 31.2, 121.5, moved, 2_000L))
    }
}
