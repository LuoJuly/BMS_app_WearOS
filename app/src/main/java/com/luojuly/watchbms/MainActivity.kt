package com.luojuly.watchbms

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.InputDevice
import android.view.MotionEvent
import android.view.WindowManager
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity(), JkBleClient.Listener, WatchDashboard.Host {
    private lateinit var dashboard: WatchDashboard
    private lateinit var ble: JkBleClient
    private lateinit var prefs: android.content.SharedPreferences

    private var page = Page.CLUSTER
    private var notice = Notice.IDLE
    private var noticeDetail: String? = null
    private var statusAlarm = false
    private var scanning = false
    private var devices = emptyList<JkDevice>()
    private var boards = emptyList<SavedBoard>()
    private var activeAddress: String? = null
    private var connected = false
    private var keepOn = false
    private var snapshot: BmsSnapshot? = null
    private var hadAlarm = false
    private var wasCharging = false
    private var chargeHoldStart = 0L
    private var pendingPassword: String? = null
    private var attemptPassword = DEFAULT_BOARD_PASSWORD
    private var chosenProtocol = BoardProtocol.JK
    private var linkProtocol = BoardProtocol.JK
    private var ride = RideWindow()
    private var rideAddress: String? = null
    private var lastGps: Location? = null
    private var lastNetwork: Location? = null
    private var gpsSpeed: Int? = null
    private var gpsSpeedAt = 0L
    private var networkSpeed: Int? = null
    private var networkSpeedAt = 0L
    private var tracking = false
    private val weatherExec = Executors.newSingleThreadExecutor()
    private var weatherReport: WeatherReport? = null
    private var weatherAt = 0L
    private var weatherLat = Double.NaN
    private var weatherLon = Double.NaN
    private var weatherLoading = false
    private var weatherFailed = false
    private var weatherRetryAt = 0L
    private var weatherGen = 0
    private val locations by lazy { getSystemService(LOCATION_SERVICE) as LocationManager }
    private val rideFixes = object : LocationListener {
        override fun onLocationChanged(location: Location) = onRideFix(location)

        @Deprecated("Legacy LocationListener callback")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        keepOn = prefs.getBoolean(KEY_KEEP, false)
        boards = loadBoards()
        activeAddress = prefs.getString(KEY_ADDRESS, null)?.ifBlank { null }
        chosenProtocol = BoardProtocol.fromWire(prefs.getString(KEY_PROTOCOL, BoardProtocol.JK.wire))
        activeAddress?.let { address ->
            rideAddress = address
            ride = decodeRide(prefs.getString(rideKey(address), null))
        }
        applyKeep()
        dashboard = WatchDashboard(this)
        dashboard.host = this
        setContentView(dashboard)
        ble = JkBleClient(this, this)
        val active = boards.firstOrNull { it.address == activeAddress }
        ble.remember(activeAddress, BoardProtocol.fromWire(active?.protocol), active?.name.orEmpty())
        ble.updatePassword(passwordFor(activeAddress))
        if (!activeAddress.isNullOrBlank()) applyNotice(Notice.CONNECTING)
        render()
        if (!hasScanPermission()) {
            requestPermissions(scanPermissions(), REQUEST_PERMISSIONS)
        }
    }

    override fun onStart() {
        super.onStart()
        overridePendingTransition(0, 0)
        ble.resume()
        startRideTracking()
    }

    override fun onStop() {
        stopRideTracking()
        ble.pause()
        super.onStop()
    }

    override fun onDestroy() {
        weatherGen += 1
        weatherExec.shutdownNow()
        if (::dashboard.isInitialized) {
            dashboard.removeCallbacks(speedTick)
            dashboard.removeCallbacks(clockTick)
        }
        if (::ble.isInitialized) ble.shutdown()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS) return
        if (!hasScanPermission()) {
            applyNotice(Notice.NEED_LOCATION)
            page = Page.SETTINGS
            render()
        } else {
            startRideTracking()
        }
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_SCROLL &&
            event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)
        ) {
            val delta = -event.getAxisValue(MotionEvent.AXIS_SCROLL)
            dashboard.rotaryTarget().scrollBy(0, (delta * 36f * resources.displayMetrics.density).toInt())
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onPage(page: Page) {
        if (this.page == Page.SETTINGS && page != Page.SETTINGS) ble.stopScan()
        this.page = page
        render()
    }

    override fun onOpenSettings() {
        page = Page.SETTINGS
        render()
    }

    override fun onRefreshScan() {
        if (!ensureBluetooth()) return
        if (!hasScanPermission()) {
            applyNotice(Notice.NEED_LOCATION)
            render()
            requestPermissions(scanPermissions(), REQUEST_PERMISSIONS)
            return
        }
        ble.startScan()
    }

    override fun onConnect(address: String, name: String) {
        if (!ensureBluetooth()) return
        linkProtocol = resolveProtocol(address)
        boards = rememberBoards(boards, name, address, protocol = linkProtocol.wire)
        saveBoards(boards)
        activeAddress = address
        prefs.edit().putString(KEY_ADDRESS, address).apply()
        loadRide(address)
        ble.remember(address, linkProtocol, name)
        connected = false
        snapshot = null
        hadAlarm = false
        page = Page.CLUSTER
        applyNotice(Notice.CONNECTING)
        val entered = pendingPassword
        pendingPassword = null
        val password = if (linkProtocol == BoardProtocol.JK) entered ?: passwordFor(address) else DEFAULT_BOARD_PASSWORD
        attemptPassword = password
        if (entered != null && linkProtocol == BoardProtocol.JK) {
            boards = boards.map { if (it.address == address) it.copy(password = entered) else it }
            saveBoards(boards)
        }
        ble.connect(address, password, linkProtocol, name)
        render()
    }

    override fun onChooseProtocol(wire: String) {
        chosenProtocol = BoardProtocol.fromWire(wire)
        prefs.edit().putString(KEY_PROTOCOL, chosenProtocol.wire).apply()
        render()
    }

    override fun onCycleBoardProtocol(address: String) {
        val current = boards.firstOrNull { it.address == address } ?: return
        val next = BoardProtocol.fromWire(current.protocol).next()
        boards = boards.map { if (it.address == address) it.copy(protocol = next.wire) else it }
        saveBoards(boards)
        render()
        if (activeAddress == address && connected) onConnect(address, current.name)
    }

    override fun onRemoveSaved(address: String) {
        boards = boards.filter { it.address != address }
        saveBoards(boards)
        prefs.edit().remove(rideKey(address)).apply()
        if (rideAddress == address) {
            ride = RideWindow()
            rideAddress = null
            lastGps = null
        }
        if (activeAddress == address) {
            activeAddress = null
            prefs.edit().remove(KEY_ADDRESS).apply()
            connected = false
            snapshot = null
            hadAlarm = false
            applyNotice(Notice.DISCONNECTED)
            ble.disconnect()
        }
        render()
    }

    override fun onPassword(value: String) {
        pendingPassword = value.trim().ifBlank { DEFAULT_BOARD_PASSWORD }
    }

    override fun onToggleKeepScreen() {
        keepOn = !keepOn
        prefs.edit().putBoolean(KEY_KEEP, keepOn).apply()
        applyKeep()
        render()
    }

    override fun onNotice(next: Notice, detail: String?) {
        applyNotice(next, detail)
        render()
    }

    override fun onScanState(scanning: Boolean) {
        this.scanning = scanning
        render()
    }

    override fun onDevices(devices: List<JkDevice>) {
        this.devices = devices
        render()
    }

    override fun onAuthenticated(deviceName: String) {
        val becameConnected = !connected
        connected = true
        val shownName = deviceName.ifBlank { getString(R.string.app_name) }
        applyNotice(Notice.CONNECTED, shownName)
        val address = activeAddress
        if (!address.isNullOrBlank()) {
            boards = rememberBoards(boards, deviceName, address, protocol = linkProtocol.wire)
            if (linkProtocol == BoardProtocol.JK) {
                boards = boards.map { if (it.address == address) it.copy(password = attemptPassword) else it }
            }
            saveBoards(boards)
        }
        if (becameConnected && page == Page.SETTINGS) page = Page.CLUSTER
        startRideTracking()
        render()
    }

    override fun onAuthRejected() {
        connected = false
        applyNotice(Notice.AUTH_REJECTED)
        page = Page.SETTINGS
        lastGps = null
        render()
        val address = activeAddress ?: return
        val name = boards.firstOrNull { it.address == address }?.name ?: BOARD_FALLBACK_NAME
        dashboard.promptPassword(address, name, attemptPassword)
    }

    override fun onSnapshot(snapshot: BmsSnapshot) {
        val alarmNow = snapshot.alarmLabels.isNotEmpty()
        if (alarmNow && !hadAlarm) vibrateOnce()
        hadAlarm = alarmNow
        this.snapshot = snapshot
        connected = true
        render()
    }

    override fun onLinkLost() {
        connected = false
        lastGps = null
        saveRide()
        render()
    }

    private fun loadRide(address: String) {
        if (rideAddress == address) return
        saveRide()
        rideAddress = address
        ride = decodeRide(prefs.getString(rideKey(address), null))
        lastGps = null
    }

    private fun saveRide() {
        val address = rideAddress ?: return
        prefs.edit().putString(rideKey(address), encodeRide(ride)).apply()
    }

    @SuppressLint("MissingPermission")
    private fun startRideTracking() {
        if (tracking) return
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        activeAddress?.let { loadRide(it) }
        var requested = false
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                if (!locations.isProviderEnabled(provider)) continue
                locations.requestLocationUpdates(provider, 1000L, 0f, rideFixes, Looper.getMainLooper())
                locations.getLastKnownLocation(provider)?.let { onRideFix(it) }
                requested = true
            } catch (_: RuntimeException) {
            }
        }
        tracking = requested
    }

    private fun stopRideTracking() {
        if (tracking) {
            locations.removeUpdates(rideFixes)
            tracking = false
        }
        lastGps = null
        lastNetwork = null
        gpsSpeed = null
        networkSpeed = null
        saveRide()
    }

    private fun onRideFix(location: Location) {
        val network = location.provider == LocationManager.NETWORK_PROVIDER
        val previous = if (network) lastNetwork else lastGps
        if (network) lastNetwork = location else lastGps = location
        considerWeather()
        val elapsed = if (previous == null) 0.0 else (location.time - previous.time) / 1000.0
        val meters = if (previous == null) {
            0.0
        } else {
            distanceMeters(previous.latitude, previous.longitude, location.latitude, location.longitude)
        }
        val kmh = fixSpeedKmh(location.speed, location.hasSpeed(), location.accuracy, meters, elapsed, network)
        if (kmh != null) {
            if (network) {
                networkSpeed = kmh
                networkSpeedAt = location.time
            } else {
                gpsSpeed = kmh
                gpsSpeedAt = location.time
            }
            render()
        }
        if (network) return
        val snap = snapshot ?: return
        if (previous == null || rideAddress != activeAddress) return
        val accuracy = maxOf(location.accuracy, previous.accuracy)
        if (!acceptRideLeg(meters, elapsed, accuracy)) return
        val wattHours = -snap.current.toDouble() * snap.totalVoltage.toDouble() * (elapsed / 3600.0)
        if (ride.add(meters, wattHours)) {
            saveRide()
            render()
        }
    }

    private fun currentSpeedKmh(): Int? {
        val now = System.currentTimeMillis()
        if (gpsSpeed != null && now - gpsSpeedAt <= 5_000L) return gpsSpeed
        if (networkSpeed != null && now - networkSpeedAt <= 15_000L) return networkSpeed
        return null
    }

    private fun considerWeather() {
        val now = System.currentTimeMillis()
        val fix = chooseWeatherFix(lastGps?.toGeoFix(false), lastNetwork?.toGeoFix(true), now) ?: return
        if (weatherLoading) return
        if (weatherReport == null && weatherFailed && now < weatherRetryAt) return
        if (!shouldRefreshWeather(weatherReport != null, weatherAt, weatherLat, weatherLon, fix, now)) return
        weatherLoading = true
        val generation = ++weatherGen
        weatherExec.execute {
            try {
                val placeJson = try {
                    httpGet(placeUrl(fix.latitude, fix.longitude, placeLanguage()))
                } catch (_: Exception) {
                    null
                }
                val report = readWeather(httpGet(weatherUrl(fix.latitude, fix.longitude)), placeJson)
                runOnUiThread {
                    if (generation != weatherGen) return@runOnUiThread
                    weatherReport = report
                    weatherAt = System.currentTimeMillis()
                    weatherLat = fix.latitude
                    weatherLon = fix.longitude
                    weatherLoading = false
                    weatherFailed = false
                    render()
                }
            } catch (_: Exception) {
                runOnUiThread {
                    if (generation != weatherGen) return@runOnUiThread
                    weatherLoading = false
                    weatherFailed = true
                    weatherRetryAt = System.currentTimeMillis() + 60_000L
                    render()
                }
            }
        }
    }

    private fun currentWeatherView(): WeatherView {
        weatherReport?.let { report ->
            val view = report.toView()
            val place = if (report.place.isBlank() || report.place == PLACE_HERE) {
                getString(R.string.weather_here)
            } else {
                report.place
            }
            return view.copy(place = place, phrase = weatherPhraseText(this, report.phrase))
        }
        val now = System.currentTimeMillis()
        val fix = chooseWeatherFix(lastGps?.toGeoFix(false), lastNetwork?.toGeoFix(true), now)
        val message = when {
            fix == null -> getString(R.string.weather_locating)
            weatherFailed -> getString(R.string.weather_none)
            else -> getString(R.string.weather_loading)
        }
        return waitingWeather(message)
    }

    private fun Location.toGeoFix(network: Boolean): GeoFix {
        return GeoFix(latitude, longitude, time, network)
    }

    private fun httpGet(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 8_000
        connection.readTimeout = 8_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept", "application/json")
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("HTTP $code")
            return body
        } finally {
            connection.disconnect()
        }
    }

    private fun render() {
        if (!::dashboard.isInitialized) return
        val showDemo = snapshot == null
        val chargingNow = confirmedCharging()
        if (chargingNow && !wasCharging && page != Page.SETTINGS) page = Page.CHARGE
        if (!chargingNow && page == Page.CHARGE) page = Page.CLUSTER
        wasCharging = chargingNow
        dashboard.render(
            DashState(
                page = page,
                status = displayStatus(showDemo),
                statusAlarm = statusAlarm,
                statusQuiet = notice == Notice.IDLE || notice == Notice.DISCONNECTED,
                scanning = scanning,
                devices = devices,
                saved = boards.map { board ->
                    val link = linkStatus(board.address)
                    SavedBoardRow(board.name, board.address, link.text, link.alarm, link.phase, board.protocol)
                },
                connected = connected,
                keepScreenOn = keepOn,
                snapshot = if (showDemo) demoSnapshot(24) else snapshot,
                password = passwordFor(activeAddress),
                showDemo = showDemo,
                rangeKm = if (showDemo) null else snapshot?.let {
                    remainingRangeKm(ride, it.remainingAh, it.totalVoltage)
                },
                speedKmh = currentSpeedKmh(),
                weather = currentWeatherView(),
                clock = clockText(),
                confirmedCharging = chargingNow,
                chosenProtocol = chosenProtocol.wire
            )
        )
        dashboard.removeCallbacks(speedTick)
        val wait = speedRefreshDelay()
        if (wait != null) dashboard.postDelayed(speedTick, wait)
        dashboard.removeCallbacks(clockTick)
        if (page == Page.CHARGE) dashboard.postDelayed(clockTick, 30_000L)
    }

    private val speedTick = Runnable { render() }
    private val clockTick = Runnable { render() }

    private fun clockText(): String {
        val calendar = java.util.Calendar.getInstance()
        return String.format(
            java.util.Locale.US,
            "%02d:%02d",
            calendar.get(java.util.Calendar.HOUR_OF_DAY),
            calendar.get(java.util.Calendar.MINUTE)
        )
    }

    private fun speedRefreshDelay(): Long? {
        val now = System.currentTimeMillis()
        var left = Long.MIN_VALUE
        if (gpsSpeed != null) left = maxOf(left, 5_000L - (now - gpsSpeedAt))
        if (networkSpeed != null) left = maxOf(left, 15_000L - (now - networkSpeedAt))
        if (left == Long.MIN_VALUE) return null
        return left.coerceAtLeast(200L)
    }

    private fun displayStatus(showDemo: Boolean): String {
        val text = noticeText(this, notice, noticeDetail)
        if (!showDemo) return text
        return if (notice == Notice.IDLE || notice == Notice.DISCONNECTED) {
            getString(R.string.status_demo)
        } else {
            text
        }
    }

    private fun placeLanguage(): String {
        val language = resources.configuration.locales.get(0)?.language ?: "en"
        return if (language.startsWith("zh")) "zh" else "en"
    }

    private fun applyNotice(next: Notice, detail: String? = null) {
        notice = next
        noticeDetail = detail
        statusAlarm = next.alarm
    }

    private data class LinkLabel(val text: String, val alarm: Boolean, val phase: BoardPhase)

    private fun linkStatus(address: String): LinkLabel {
        if (address != activeAddress) {
            return LinkLabel(getString(R.string.status_idle), false, BoardPhase.IDLE)
        }
        if (connected) {
            return LinkLabel(getString(R.string.status_connected_short), false, BoardPhase.CONNECTED)
        }
        if (notice.busy) {
            return LinkLabel(getString(R.string.status_connecting), false, BoardPhase.CONNECTING)
        }
        if (statusAlarm) {
            return LinkLabel(noticeText(this, notice, noticeDetail), true, BoardPhase.IDLE)
        }
        return LinkLabel(getString(R.string.status_idle), false, BoardPhase.IDLE)
    }

    private fun loadBoards(): List<SavedBoard> {
        val raw = prefs.getString(KEY_BOARDS, null)
        if (raw.isNullOrBlank()) {
            val legacy = prefs.getString(KEY_ADDRESS, null)
            return if (legacy.isNullOrBlank()) emptyList() else listOf(SavedBoard(BOARD_FALLBACK_NAME, legacy))
        }
        return try {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val address = item.optString("address")
                    if (address.isBlank()) continue
                    add(
                        SavedBoard(
                            item.optString("name").ifBlank { BOARD_FALLBACK_NAME },
                            address,
                            item.optString("password"),
                            item.optString("protocol").ifBlank { BoardProtocol.JK.wire }
                        )
                    )
                }
            }.take(10)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveBoards(boards: List<SavedBoard>) {
        val array = JSONArray()
        boards.take(10).forEach { board ->
            array.put(
                JSONObject()
                    .put("name", board.name)
                    .put("address", board.address)
                    .put("password", board.password)
                    .put("protocol", board.protocol)
            )
        }
        prefs.edit().putString(KEY_BOARDS, array.toString()).apply()
    }

    private fun resolveProtocol(address: String): BoardProtocol {
        boards.firstOrNull { it.address == address }?.let { return BoardProtocol.fromWire(it.protocol) }
        val hint = devices.firstOrNull { it.address == address }?.hint.orEmpty()
        if (hint.isNotEmpty()) return BoardProtocol.fromWire(hint)
        return chosenProtocol
    }

    private fun passwordFor(address: String?): String {
        if (address.isNullOrBlank()) return DEFAULT_BOARD_PASSWORD
        return boards.firstOrNull { it.address == address }?.password?.ifBlank { null } ?: DEFAULT_BOARD_PASSWORD
    }

    private fun confirmedCharging(): Boolean {
        val snap = snapshot
        if (!connected || snap == null) {
            chargeHoldStart = 0L
            return false
        }
        val gps = freshGpsSpeedKmh(gpsSpeed, System.currentTimeMillis() - gpsSpeedAt)
        val now = SystemClock.elapsedRealtime()
        chargeHoldStart = nextChargeHold(snap.chargeState == "充电", gps, chargeHoldStart, now)
        return chargeConfirmed(chargeHoldStart, now)
    }

    private fun applyKeep() {
        if (keepOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun ensureBluetooth(): Boolean {
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter
        if (adapter == null) {
            applyNotice(Notice.NO_ADAPTER)
            render()
            return false
        }
        if (!adapter.isEnabled) {
            try {
                startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } catch (_: Exception) {
                applyNotice(Notice.ENABLE_BLUETOOTH)
                render()
            }
            return false
        }
        return true
    }

    private fun hasScanPermission(): Boolean {
        return scanPermissions().all { permission ->
            checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun scanPermissions(): Array<String> {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= 31) {
            permissions += Manifest.permission.BLUETOOTH_SCAN
            permissions += Manifest.permission.BLUETOOTH_CONNECT
        }
        return permissions.toTypedArray()
    }

    @Suppress("DEPRECATION")
    private fun vibrateOnce() {
        val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        if (!vibrator.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= 26) {
            vibrator.vibrate(VibrationEffect.createOneShot(180, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            vibrator.vibrate(180)
        }
    }

    companion object {
        private const val PREFS = "watch_jk"
        private const val KEY_ADDRESS = "address"
        private const val KEY_PROTOCOL = "protocol"
        private const val KEY_BOARDS = "boards"
        private const val KEY_KEEP = "keep_screen"
        private const val REQUEST_PERMISSIONS = 21
    }
}
