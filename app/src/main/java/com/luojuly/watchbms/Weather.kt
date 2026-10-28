package com.luojuly.watchbms

import java.util.Locale
import kotlin.math.round

internal data class GeoFix(
    val latitude: Double,
    val longitude: Double,
    val timeMillis: Long,
    val network: Boolean
)

internal data class WeatherReport(
    val place: String,
    val phrase: String,
    val temperatureC: Int,
    val feelsC: Int,
    val humidity: Int,
    val windKmh: Int
)

data class WeatherView(
    val ready: Boolean,
    val place: String,
    val phrase: String,
    val temperature: String,
    val feels: String,
    val humidity: String,
    val wind: String
)

internal fun waitingWeather(message: String): WeatherView {
    return WeatherView(false, "", "", message, "", "", "")
}

internal fun WeatherReport.toView(): WeatherView {
    return WeatherView(
        ready = true,
        place = place,
        phrase = phrase,
        temperature = "${temperatureC}°",
        feels = "${feelsC}°",
        humidity = "${humidity}%",
        wind = "${windKmh}km/h"
    )
}

/** GPS within 10 minutes, otherwise a network fix within 30 minutes, otherwise the newer fix within 6 hours. */
internal fun chooseWeatherFix(gps: GeoFix?, network: GeoFix?, nowMillis: Long): GeoFix? {
    fun fresh(fix: GeoFix?, maxAgeMillis: Long): Boolean {
        if (fix == null) return false
        val age = nowMillis - fix.timeMillis
        return age in 0..maxAgeMillis
    }
    if (fresh(gps, 10 * 60_000L)) return gps
    if (fresh(network, 30 * 60_000L)) return network
    return listOfNotNull(gps, network)
        .filter { fresh(it, 6 * 60 * 60_000L) }
        .maxByOrNull { it.timeMillis }
}

internal fun weatherPhrase(code: Int): String {
    return when (code) {
        0 -> "晴"
        1 -> "大部晴"
        2 -> "多云"
        3 -> "阴"
        45, 48 -> "雾"
        51, 53, 55, 56, 57 -> "毛毛雨"
        61, 80 -> "小雨"
        63, 81 -> "中雨"
        65, 82 -> "大雨"
        66, 67 -> "冻雨"
        71, 85 -> "小雪"
        73 -> "中雪"
        75, 86 -> "大雪"
        77 -> "雪粒"
        95 -> "雷暴"
        96, 99 -> "雷暴冰雹"
        else -> "未知"
    }
}

internal fun shouldRefreshWeather(
    haveReport: Boolean,
    fetchedAtMillis: Long,
    fetchedLatitude: Double,
    fetchedLongitude: Double,
    fix: GeoFix,
    nowMillis: Long
): Boolean {
    if (!haveReport) return true
    if (nowMillis - fetchedAtMillis >= 20 * 60_000L) return true
    val moved = distanceMeters(fetchedLatitude, fetchedLongitude, fix.latitude, fix.longitude)
    return moved >= 3_000.0
}

internal fun readWeather(weatherJson: String, placeJson: String?): WeatherReport {
    val temperature = jsonNumber(weatherJson, "temperature_2m") ?: error("缺少气温")
    val feels = jsonNumber(weatherJson, "apparent_temperature") ?: temperature
    val humidity = jsonNumber(weatherJson, "relative_humidity_2m") ?: 0.0
    val code = jsonNumber(weatherJson, "weather_code") ?: -1.0
    val wind = jsonNumber(weatherJson, "wind_speed_10m") ?: 0.0
    val place = placeJson?.let { jsonString(it, "name") }?.ifBlank { null } ?: "当前位置"
    return WeatherReport(
        place = place,
        phrase = weatherPhrase(code.toInt()),
        temperatureC = round(temperature).toInt(),
        feelsC = round(feels).toInt(),
        humidity = round(humidity).toInt().coerceIn(0, 100),
        windKmh = round(wind).toInt().coerceAtLeast(0)
    )
}

internal fun weatherUrl(latitude: Double, longitude: Double): String {
    val lat = String.format(Locale.US, "%.4f", latitude)
    val lon = String.format(Locale.US, "%.4f", longitude)
    return "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
        "&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m" +
        "&wind_speed_unit=kmh&forecast_days=1&timezone=auto"
}

internal fun placeUrl(latitude: Double, longitude: Double, language: String): String {
    val lat = String.format(Locale.US, "%.4f", latitude)
    val lon = String.format(Locale.US, "%.4f", longitude)
    return "https://geocoding-api.open-meteo.com/v1/reverse?latitude=$lat&longitude=$lon&language=$language&count=1"
}

internal fun jsonNumber(json: String, key: String): Double? {
    val match = Regex(""""$key"\s*:\s*(-?\d+(?:\.\d+)?)""").find(json) ?: return null
    return match.groupValues[1].toDoubleOrNull()
}

internal fun jsonString(json: String, key: String): String? {
    val match = Regex(""""$key"\s*:\s*"([^"\\]*)"""").find(json) ?: return null
    return match.groupValues[1]
}
