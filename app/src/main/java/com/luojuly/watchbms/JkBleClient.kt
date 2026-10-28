package com.luojuly.watchbms

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.UUID

data class JkDevice(
    val address: String,
    val name: String,
    val rssi: Int,
    val bonded: Boolean,
    val jkService: Boolean,
    val hint: String = ""
)

class JkBleClient(
    context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onNotice(notice: Notice, detail: String? = null)
        fun onScanState(scanning: Boolean)
        fun onDevices(devices: List<JkDevice>)
        fun onAuthenticated(deviceName: String)
        fun onAuthRejected()
        fun onSnapshot(snapshot: BmsSnapshot)
        fun onLinkLost()
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("jk-ble").apply { start() }
    private val ble = Handler(thread.looper)
    private val adapter = (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val assembler = FrameAssembler()
    private val foreign = ForeignLink()
    private val found = LinkedHashMap<String, JkDevice>()
    private val writeQueue = ArrayDeque<ByteArray>()

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var counter = 0
    private var password = DEFAULT_BOARD_PASSWORD
    private var protocol = BoardProtocol.JK
    private var linkName = ""
    private var address: String? = null
    private var pendingWrite: ByteArray? = null
    private var pendingOffset = 0
    private var writing = false
    private var writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
    private var followGen = 0
    private var authenticated = false
    private var userCancelled = false
    private var authBlocked = false
    private var paused = true
    private var inFlight = false
    private var requestAt = 0L
    private var lastDataAt = 0L
    private var scanning = false
    private var scanGeneration = 0
    private var reconnectPosted = false

    fun updatePassword(value: String) {
        password = value.trim()
    }

    internal fun remember(address: String?, boardProtocol: BoardProtocol = BoardProtocol.JK, name: String = "") {
        ble.post {
            this.address = address
            protocol = boardProtocol
            linkName = name
        }
    }

    fun resume() {
        ble.post {
            paused = false
            emitDevices()
            val target = address
            if (!target.isNullOrBlank() && !userCancelled) connectAddress(target)
        }
    }

    fun pause() {
        ble.post {
            paused = true
            stopScanLocked()
            cancelReconnect()
            closeGatt()
        }
    }

    internal fun connect(target: String, typedPassword: String, boardProtocol: BoardProtocol = BoardProtocol.JK, name: String = "") {
        ble.post {
            password = typedPassword.trim().ifBlank { DEFAULT_BOARD_PASSWORD }
            protocol = boardProtocol
            linkName = name.ifBlank { target }
            address = target
            userCancelled = false
            authBlocked = false
            paused = false
            cancelReconnect()
            connectAddress(target)
        }
    }

    fun disconnect() {
        ble.post {
            userCancelled = true
            address = null
            paused = false
            stopScanLocked()
            cancelReconnect()
            closeGatt()
            ui { listener.onNotice(Notice.DISCONNECTED) }
        }
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        ble.post {
            if (adapter == null || !adapter.isEnabled) {
                ui { listener.onNotice(Notice.OPEN_WATCH_BLUETOOTH) }
                return@post
            }
            val scanner = adapter.bluetoothLeScanner
            if (scanner == null) {
                ui { listener.onNotice(Notice.CANNOT_SCAN) }
                return@post
            }
            stopScanLocked()
            val generation = ++scanGeneration
            scanning = true
            ui { listener.onScanState(true); listener.onNotice(Notice.SCANNING) }
            emitDevices()
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            try {
                scanner.startScan(null, settings, scanCallback)
                ble.postDelayed({
                    if (generation != scanGeneration) return@postDelayed
                    stopScanLocked()
                    val stillIdle = !authenticated && gatt == null
                    ui {
                        listener.onScanState(false)
                        if (stillIdle) listener.onNotice(Notice.SCAN_DONE)
                    }
                }, SCAN_WINDOW_MS)
            } catch (error: SecurityException) {
                scanning = false
                ui { listener.onScanState(false); listener.onNotice(Notice.NO_SCAN_PERMISSION) }
                Log.w(TAG, "scan denied", error)
            }
        }
    }

    fun stopScan() {
        ble.post {
            scanGeneration++
            stopScanLocked()
            ui { listener.onScanState(false) }
        }
    }

    fun shutdown() {
        ble.post {
            paused = true
            userCancelled = true
            stopScanLocked()
            cancelReconnect()
            closeGatt()
        }
        thread.quitSafely()
    }

    private val poll = object : Runnable {
        override fun run() {
            if (authenticated && !paused) {
                if (protocol == BoardProtocol.JK) requestCell() else pollForeign()
            }
            if (!paused) ble.postDelayed(this, if (protocol == BoardProtocol.JK) POLL_MS else FOREIGN_POLL_MS)
        }
    }

    private val watchdog = object : Runnable {
        override fun run() {
            val limit = if (protocol == BoardProtocol.JK) DATA_TIMEOUT_MS else FOREIGN_TIMEOUT_MS
            if (authenticated && !paused && SystemClock.elapsedRealtime() - lastDataAt > limit) {
                ui { listener.onNotice(Notice.DATA_STALL) }
                scheduleReconnect()
            }
            if (!paused) ble.postDelayed(this, WATCHDOG_MS)
        }
    }

    private val writePump = Runnable { drainWrites() }
    private val writeTimeout = Runnable {
        if (!writing) return@Runnable
        writing = false
        pendingWrite = null
        drainWrites()
    }

    private val reconnectRun = Runnable {
        reconnectPosted = false
        val target = address
        if (!target.isNullOrBlank() && !paused && !userCancelled) connectAddress(target)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            ble.post {
                val device = result.device ?: return@post
                val services = result.scanRecord?.serviceUuids?.map { it.uuid.toString() }.orEmpty()
                val advertised = services.any { it.equals(BoardProtocol.FFE0, ignoreCase = true) }
                val advertisedName = result.scanRecord?.deviceName
                val name = safeName(device, advertisedName)
                val hint = BoardProtocol.detect(name, services)?.wire.orEmpty()
                val known = hint.isNotEmpty() || advertised || services.any {
                    val id = it.lowercase()
                    id == BoardProtocol.FF00 || id == BoardProtocol.NUS || id == BoardProtocol.FFE2
                }
                if (name == device.address && !known) return@post
                found[device.address] = JkDevice(
                    address = device.address,
                    name = name,
                    rssi = result.rssi,
                    bonded = false,
                    jkService = hint == BoardProtocol.JK.wire || advertised || name.contains("JK", ignoreCase = true),
                    hint = hint
                )
                emitDevices()
            }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            ui { listener.onScanState(false); listener.onNotice(Notice.SCAN_FAILED, errorCode.toString()) }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            ble.post {
                if (gatt != this@JkBleClient.gatt) return@post
                when {
                    newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS -> {
                        ui { listener.onNotice(Notice.READING) }
                        gatt.discoverServices()
                    }
                    newState == BluetoothProfile.STATE_DISCONNECTED -> {
                        authenticated = false
                        if (!paused && !userCancelled) {
                            ui { listener.onLinkLost(); listener.onNotice(Notice.LINK_LOST) }
                            scheduleReconnect()
                        }
                    }
                    status != BluetoothGatt.GATT_SUCCESS && !paused && !userCancelled -> {
                        ui { listener.onNotice(Notice.CONNECT_FAILED_RETRY, status.toString()) }
                        scheduleReconnect()
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            ble.post {
                if (gatt != this@JkBleClient.gatt) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    ui { listener.onNotice(Notice.NO_SERVICE) }
                    scheduleReconnect()
                    return@post
                }
                val notify = if (protocol == BoardProtocol.JK) attachJk(gatt) else attachForeign(gatt)
                if (notify == null) {
                    ui { listener.onNotice(if (protocol == BoardProtocol.JK) Notice.NOT_JK else Notice.NO_SERVICE) }
                    return@post
                }
                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                enableNotify(gatt, notify)
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            ble.post {
                if (gatt != this@JkBleClient.gatt) return@post
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    assembler.clear()
                    if (protocol == BoardProtocol.JK) {
                        send(COMMAND_DEVICE_INFO)
                    } else {
                        foreign.start(protocol)
                        foreign.opening().forEach { enqueue(it) }
                        armFollowUp()
                        if (!foreign.usesHandshake()) becomeReady()
                    }
                } else {
                    ui { listener.onNotice(Notice.NOTIFY_FAILED) }
                    scheduleReconnect()
                }
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val value = characteristic.value?.copyOf() ?: return
            ble.post { if (gatt == this@JkBleClient.gatt) onBytes(value) }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            val copy = value.copyOf()
            ble.post { if (gatt == this@JkBleClient.gatt) onBytes(copy) }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            ble.post {
                if (gatt != this@JkBleClient.gatt || protocol == BoardProtocol.JK ||
                    writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                ) return@post
                ble.removeCallbacks(writeTimeout)
                writing = false
                ble.postDelayed(writePump, 20L)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectAddress(target: String) {
        if (adapter == null || !adapter.isEnabled) {
            ui { listener.onNotice(Notice.OPEN_WATCH_BLUETOOTH) }
            return
        }
        closeGatt()
        assembler.clear()
        foreign.start(protocol)
        authenticated = false
        inFlight = false
        ui { listener.onNotice(Notice.CONNECTING) }
        try {
            val device = adapter.getRemoteDevice(target)
            gatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            if (gatt == null) ui { listener.onNotice(Notice.CONNECT_FAILED) }
        } catch (error: SecurityException) {
            ui { listener.onNotice(Notice.NO_CONNECT_PERMISSION) }
            Log.w(TAG, "connect denied", error)
        } catch (error: IllegalArgumentException) {
            ui { listener.onNotice(Notice.BAD_ADDRESS) }
            Log.w(TAG, "bad address", error)
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableNotify(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        gatt.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(CCCD_UUID)
        if (descriptor == null) {
            ui { listener.onNotice(Notice.NO_CCCD) }
            return
        }
        val props = characteristic.properties
        val enable = if ((props and BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0 &&
            (props and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
        ) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        @Suppress("DEPRECATION")
        descriptor.value = enable
        @Suppress("DEPRECATION")
        val queued = gatt.writeDescriptor(descriptor)
        if (!queued) ui { listener.onNotice(Notice.SUBSCRIBE_FAILED) }
    }

    private fun attachJk(gatt: BluetoothGatt): BluetoothGattCharacteristic? {
        val service = gatt.services.firstOrNull { it.uuid == SERVICE_UUID } ?: return null
        val chars = service.characteristics.filter { it.uuid == CHAR_UUID }
        if (chars.isEmpty()) return null
        writeChar = chars.firstOrNull { char ->
            val props = char.properties
            (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0 ||
                (props and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
        }
        val notify = chars.firstOrNull { char ->
            val props = char.properties
            (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 ||
                (props and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
        } ?: writeChar
        if (writeChar == null || notify == null) return null
        writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        return notify
    }

    private fun attachForeign(gatt: BluetoothGatt): BluetoothGattCharacteristic? {
        val writeMask = BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
        val notifyMask = BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE
        for (endpoint in protocol.endpoints()) {
            val service = gatt.services.firstOrNull { it.uuid.toString().equals(endpoint.service, ignoreCase = true) } ?: continue
            var write = exact(service, endpoint.write)
            var notify = exact(service, endpoint.notify)
            if (endpoint.anyWrite) write = anyWith(service, writeMask)
            if (endpoint.anyNotify) notify = anyWith(service, notifyMask)
            if (write == null || notify == null) continue
            if ((write.properties and writeMask) == 0 || (notify.properties and notifyMask) == 0) continue
            writeChar = write
            writeType = if ((write.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            }
            return notify
        }
        return null
    }

    private fun exact(service: BluetoothGattService, uuid: String?): BluetoothGattCharacteristic? {
        if (uuid.isNullOrBlank()) return null
        return service.characteristics.firstOrNull { it.uuid.toString().equals(uuid, ignoreCase = true) }
    }

    private fun anyWith(service: BluetoothGattService, mask: Int): BluetoothGattCharacteristic? {
        return service.characteristics.firstOrNull { (it.properties and mask) != 0 }
    }

    private fun becomeReady() {
        if (authenticated) return
        authenticated = true
        userCancelled = false
        lastDataAt = SystemClock.elapsedRealtime()
        ble.removeCallbacks(poll)
        ble.removeCallbacks(watchdog)
        ble.postDelayed(poll, FOREIGN_POLL_MS)
        ble.post(watchdog)
        val name = linkName
        ui { listener.onAuthenticated(name) }
    }

    private fun pollForeign() {
        val now = SystemClock.elapsedRealtime()
        foreign.poll(now, lastDataAt).forEach { enqueue(it) }
        armFollowUp()
    }

    private fun armFollowUp() {
        val extra = foreign.followUp() ?: return
        val generation = ++followGen
        ble.postDelayed({
            if (generation != followGen || !authenticated) return@postDelayed
            enqueue(extra)
        }, foreign.followUpDelayMs())
    }

    @SuppressLint("MissingPermission")
    private fun enqueue(frame: ByteArray) {
        if (frame.isEmpty()) return
        writeQueue.addLast(frame)
        while (writeQueue.size > 4) writeQueue.removeFirst()
        drainWrites()
    }

    @SuppressLint("MissingPermission")
    private fun drainWrites() {
        if (writing || gatt == null || writeChar == null) return
        if (pendingWrite == null) {
            if (writeQueue.isEmpty()) return
            pendingWrite = writeQueue.removeFirst()
            pendingOffset = 0
        }
        val frame = pendingWrite ?: return
        val end = minOf(pendingOffset + 20, frame.size)
        val part = frame.copyOfRange(pendingOffset, end)
        val characteristic = writeChar ?: return
        writing = true
        @Suppress("DEPRECATION")
        characteristic.writeType = writeType
        @Suppress("DEPRECATION")
        characteristic.value = part
        @Suppress("DEPRECATION")
        val queued = gatt?.writeCharacteristic(characteristic) == true
        if (!queued) {
            writing = false
            pendingWrite = null
            return
        }
        pendingOffset = end
        if (pendingOffset >= frame.size) pendingWrite = null
        if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) {
            writing = false
            ble.postDelayed(writePump, 20L)
        } else {
            ble.postDelayed(writeTimeout, 500L)
        }
    }

    @SuppressLint("MissingPermission")
    private fun send(command: Int) {
        val gatt = gatt ?: return
        val characteristic = writeChar ?: return
        val frame = JkProtocol.buildCommand(command, counter)
        counter = (counter + 1) and 0xFF
        @Suppress("DEPRECATION")
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        @Suppress("DEPRECATION")
        characteristic.value = frame
        @Suppress("DEPRECATION")
        gatt.writeCharacteristic(characteristic)
    }

    private fun requestCell() {
        val now = SystemClock.elapsedRealtime()
        if (inFlight && now - requestAt < REQUEST_TIMEOUT_MS) return
        inFlight = true
        requestAt = now
        send(COMMAND_CELL_INFO)
    }

    private fun onBytes(chunk: ByteArray) {
        lastDataAt = SystemClock.elapsedRealtime()
        if (protocol != BoardProtocol.JK) {
            val accepted = foreign.accept(chunk, lastDataAt)
            if (accepted.becameReady) becomeReady()
            accepted.snapshot?.let { ui { listener.onSnapshot(it) } }
            return
        }
        assembler.push(chunk).forEach { frame ->
            inFlight = false
            when (frame[4].toInt() and 0xFF) {
                0x03 -> handleDevice(frame)
                0x02 -> handleCells(frame)
            }
        }
    }

    private fun handleDevice(frame: ByteArray) {
        val info = JkProtocol.parseDeviceInfo(frame)
        if (info == null) {
            ui { listener.onNotice(Notice.INFO_CHECK_FAILED) }
            return
        }
        if (password.isNotEmpty() && password == info.password) {
            authenticated = true
            userCancelled = false
            lastDataAt = SystemClock.elapsedRealtime()
            ble.removeCallbacks(poll)
            ble.removeCallbacks(watchdog)
            ble.post(poll)
            ble.post(watchdog)
            requestCell()
            val label = info.name.ifBlank { address.orEmpty() }
            ui { listener.onAuthenticated(label) }
        } else {
            authenticated = false
            authBlocked = true
            cancelReconnect()
            ui { listener.onAuthRejected() }
        }
    }

    private fun handleCells(frame: ByteArray) {
        if (!authenticated) return
        val snapshot = JkProtocol.parseCellInfo(frame) ?: return
        ui { listener.onSnapshot(snapshot) }
    }

    @SuppressLint("MissingPermission")
    private fun emitDevices() {
        val merged = LinkedHashMap<String, JkDevice>()
        try {
            adapter?.bondedDevices?.forEach { device ->
                val name = safeName(device, null)
                val hint = BoardProtocol.detect(name, emptyList())?.wire.orEmpty()
                merged[device.address] = JkDevice(
                    address = device.address,
                    name = name,
                    rssi = found[device.address]?.rssi ?: 0,
                    bonded = true,
                    jkService = hint == BoardProtocol.JK.wire || name.contains("JK", ignoreCase = true),
                    hint = found[device.address]?.hint?.ifBlank { hint } ?: hint
                )
            }
        } catch (error: SecurityException) {
            Log.w(TAG, "bonded devices denied", error)
        }
        found.values.forEach { device ->
            val previous = merged[device.address]
            merged[device.address] = device.copy(bonded = previous?.bonded == true)
        }
        val list = merged.values.sortedWith(
            compareByDescending<JkDevice> { it.jkService }
                .thenByDescending { it.bonded }
                .thenByDescending { it.rssi }
        )
        ui { listener.onDevices(list) }
    }

    @SuppressLint("MissingPermission")
    private fun safeName(device: BluetoothDevice, advertised: String?): String {
        if (!advertised.isNullOrBlank()) return advertised
        val name = try {
            device.name
        } catch (_: SecurityException) {
            null
        }
        return if (name.isNullOrBlank()) device.address else name
    }

    @SuppressLint("MissingPermission")
    private fun stopScanLocked() {
        if (!scanning) return
        scanning = false
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: SecurityException) {
        }
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        authenticated = false
        inFlight = false
        writeChar = null
        writeQueue.clear()
        pendingWrite = null
        writing = false
        followGen++
        ble.removeCallbacks(writePump)
        ble.removeCallbacks(writeTimeout)
        val current = gatt
        gatt = null
        try {
            current?.disconnect()
            current?.close()
        } catch (_: SecurityException) {
        }
    }

    private fun scheduleReconnect() {
        if (paused || userCancelled || authBlocked || address.isNullOrBlank() || reconnectPosted) return
        reconnectPosted = true
        closeGatt()
        ble.postDelayed(reconnectRun, RECONNECT_MS)
    }

    private fun cancelReconnect() {
        ble.removeCallbacks(reconnectRun)
        reconnectPosted = false
    }

    private fun ui(block: () -> Unit) {
        main.post(block)
    }

    companion object {
        private const val TAG = "JkBle"
        private const val POLL_MS = 500L
        private const val FOREIGN_POLL_MS = 1000L
        private const val FOREIGN_TIMEOUT_MS = 8_000L
        private const val WATCHDOG_MS = 1000L
        private const val DATA_TIMEOUT_MS = 3000L
        private const val REQUEST_TIMEOUT_MS = 1500L
        private const val RECONNECT_MS = 1500L
        private const val SCAN_WINDOW_MS = 8000L
        private val SERVICE_UUID: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
        private val CHAR_UUID: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val COMMAND_CELL_INFO = JkProtocol.COMMAND_CELL_INFO
        private const val COMMAND_DEVICE_INFO = JkProtocol.COMMAND_DEVICE_INFO
    }
}
