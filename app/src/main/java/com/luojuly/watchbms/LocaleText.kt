package com.luojuly.watchbms

import android.content.Context

internal const val BOARD_FALLBACK_NAME = "保护板"
internal const val PLACE_HERE = "当前位置"

internal fun noticeText(context: Context, notice: Notice, detail: String?): String {
    val id = when (notice) {
        Notice.IDLE -> R.string.status_idle
        Notice.DISCONNECTED -> R.string.status_disconnected
        Notice.CONNECTED -> R.string.status_connected
        Notice.CONNECTING -> R.string.status_connecting
        Notice.READING -> R.string.status_reading
        Notice.NEED_LOCATION -> R.string.status_need_location
        Notice.AUTH_REJECTED -> R.string.status_auth_rejected
        Notice.NO_ADAPTER -> R.string.status_no_adapter
        Notice.ENABLE_BLUETOOTH -> R.string.status_enable_bluetooth
        Notice.OPEN_WATCH_BLUETOOTH -> R.string.status_open_watch_bluetooth
        Notice.CANNOT_SCAN -> R.string.status_cannot_scan
        Notice.SCANNING -> R.string.status_scanning
        Notice.SCAN_DONE -> R.string.status_scan_done
        Notice.NO_SCAN_PERMISSION -> R.string.status_no_scan_permission
        Notice.DATA_STALL -> R.string.status_data_stall
        Notice.SCAN_FAILED -> R.string.status_scan_failed
        Notice.LINK_LOST -> R.string.status_link_lost
        Notice.CONNECT_FAILED_RETRY -> R.string.status_connect_failed_retry
        Notice.NO_SERVICE -> R.string.status_no_service
        Notice.NOT_JK -> R.string.status_not_jk
        Notice.CHAR_UNAVAILABLE -> R.string.status_char_unavailable
        Notice.NOTIFY_FAILED -> R.string.status_notify_failed
        Notice.CONNECT_FAILED -> R.string.status_connect_failed
        Notice.NO_CONNECT_PERMISSION -> R.string.status_no_connect_permission
        Notice.BAD_ADDRESS -> R.string.status_bad_address
        Notice.NO_CCCD -> R.string.status_no_cccd
        Notice.SUBSCRIBE_FAILED -> R.string.status_subscribe_failed
        Notice.INFO_CHECK_FAILED -> R.string.status_info_check_failed
    }
    return if (notice.formatted) context.getString(id, detail.orEmpty()) else context.getString(id)
}

internal fun chargeText(context: Context, state: String): String {
    val id = when (state) {
        "充电" -> R.string.state_charging
        "放电" -> R.string.state_discharging
        else -> R.string.state_rest
    }
    return context.getString(id)
}

internal fun alarmText(context: Context, label: String): String {
    val id = when (label) {
        "线阻" -> R.string.alarm_wire
        "管过温" -> R.string.alarm_mos_temp
        "串数异常" -> R.string.alarm_series
        "电流传感器" -> R.string.alarm_sensor
        "单体过压" -> R.string.alarm_cell_ov
        "总压过高" -> R.string.alarm_pack_ov
        "充电过流" -> R.string.alarm_chg_oc
        "充电短路" -> R.string.alarm_chg_sc
        "充电过温" -> R.string.alarm_chg_ot
        "充电低温" -> R.string.alarm_chg_ut
        "内部通信" -> R.string.alarm_comm
        "单体欠压" -> R.string.alarm_cell_uv
        "总压过低" -> R.string.alarm_pack_uv
        "放电过流" -> R.string.alarm_dsg_oc
        "放电短路" -> R.string.alarm_dsg_sc
        "放电过温" -> R.string.alarm_dsg_ot
        "充电管" -> R.string.alarm_chg_mos
        "放电管" -> R.string.alarm_dsg_mos
        "告警" -> R.string.alarm_generic
        else -> return label
    }
    return context.getString(id)
}

internal fun weatherPhraseText(context: Context, phrase: String): String {
    val id = when (phrase) {
        "晴" -> R.string.weather_clear
        "大部晴" -> R.string.weather_mainly_clear
        "多云" -> R.string.weather_partly_cloudy
        "阴" -> R.string.weather_overcast
        "雾" -> R.string.weather_fog
        "毛毛雨" -> R.string.weather_drizzle
        "小雨" -> R.string.weather_light_rain
        "中雨" -> R.string.weather_rain
        "大雨" -> R.string.weather_heavy_rain
        "冻雨" -> R.string.weather_freezing_rain
        "小雪" -> R.string.weather_light_snow
        "中雪" -> R.string.weather_snow
        "大雪" -> R.string.weather_heavy_snow
        "雪粒" -> R.string.weather_snow_grains
        "雷暴" -> R.string.weather_thunderstorm
        "雷暴冰雹" -> R.string.weather_thunderstorm_hail
        "未知" -> R.string.weather_unknown
        else -> return phrase
    }
    return context.getString(id)
}

internal fun boardTitle(context: Context, name: String): String {
    return if (name.isBlank() || name == BOARD_FALLBACK_NAME) {
        context.getString(R.string.board_fallback)
    } else {
        name
    }
}
