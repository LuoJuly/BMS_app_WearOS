package com.luojuly.watchbms

/** Stable status codes. The visible text comes from string resources. */
enum class Notice(
    val alarm: Boolean = false,
    val busy: Boolean = false,
    val formatted: Boolean = false
) {
    IDLE,
    DISCONNECTED,
    CONNECTED(formatted = true),
    CONNECTING(busy = true),
    READING(busy = true),
    NEED_LOCATION(alarm = true),
    AUTH_REJECTED(alarm = true),
    NO_ADAPTER(alarm = true),
    ENABLE_BLUETOOTH(alarm = true),
    OPEN_WATCH_BLUETOOTH,
    CANNOT_SCAN,
    SCANNING(busy = true),
    SCAN_DONE,
    NO_SCAN_PERMISSION,
    DATA_STALL(alarm = true, busy = true),
    SCAN_FAILED(alarm = true, formatted = true),
    LINK_LOST(alarm = true, busy = true),
    CONNECT_FAILED_RETRY(alarm = true, busy = true, formatted = true),
    NO_SERVICE,
    NOT_JK(alarm = true),
    CHAR_UNAVAILABLE,
    NOTIFY_FAILED(alarm = true),
    CONNECT_FAILED(alarm = true),
    NO_CONNECT_PERMISSION,
    BAD_ADDRESS,
    NO_CCCD,
    SUBSCRIBE_FAILED,
    INFO_CHECK_FAILED(alarm = true),
}

enum class BoardPhase { IDLE, CONNECTING, CONNECTED }
