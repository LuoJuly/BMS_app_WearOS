package com.luojuly.watchbms

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.LinearLayout.HORIZONTAL
import android.widget.LinearLayout.VERTICAL
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

enum class Page { WEATHER, HOME, CHARGE, CLUSTER, VOLTAGE, RESISTANCE, SETTINGS }

data class SavedBoardRow(
    val name: String,
    val address: String,
    val status: String,
    val alarm: Boolean,
    val phase: BoardPhase = BoardPhase.IDLE,
    val protocol: String = BoardProtocol.JK.wire
)

data class DashState(
    val page: Page,
    val status: String,
    val statusAlarm: Boolean,
    val scanning: Boolean,
    val devices: List<JkDevice>,
    val saved: List<SavedBoardRow>,
    val connected: Boolean,
    val keepScreenOn: Boolean,
    val snapshot: BmsSnapshot?,
    val password: String,
    val showDemo: Boolean,
    val rangeKm: Int?,
    val speedKmh: Int?,
    val weather: WeatherView,
    val clock: String,
    val statusQuiet: Boolean = false,
    val confirmedCharging: Boolean = false,
    val chosenProtocol: String = BoardProtocol.JK.wire
)

/**
 * Portrait layout for the first-generation Xiaomi Watch: 1.78 in, 368×448, 326 PPI.
 * Finger up/down cycles 天气 → 电量 → 速度 → 电压 → 内阻 and wraps.
 * While charging is confirmed, 充电 is inserted between 电量 and 速度.
 * Swipe left to open settings. Settings stays outside that loop.
 * The settings page lists saved boards and nearby boards. Scan refreshes that list.
 * Swipe right is left to the system, which dismisses the activity.
 * The crown scrolls the active list. Finger drags do not scroll, so a page swipe cannot jump.
 */
class WatchDashboard(context: Context) : FrameLayout(context) {
    interface Host {
        fun onPage(page: Page)
        fun onOpenSettings()
        fun onRefreshScan()
        fun onConnect(address: String, name: String)
        fun onChooseProtocol(wire: String)
        fun onCycleBoardProtocol(address: String)
        fun onRemoveSaved(address: String)
        fun onPassword(value: String)
        fun onToggleKeepScreen()
    }

    var host: Host? = null

    private val statusText = label(context.getString(R.string.status_idle), 12f, MUTED)
    private val levelBar = LevelBar(context)
    private val clusterSoc = label("--", 22f, GREEN).apply { typeface = Typeface.DEFAULT_BOLD }
    private val rangeValue = label("—", 16f, WHITE).apply { typeface = Typeface.DEFAULT_BOLD }
    private val clusterDelta = label("—", 16f, WHITE).apply { typeface = Typeface.DEFAULT_BOLD }
    private val speedValue = label("—", 72f, WHITE).apply { typeface = Typeface.DEFAULT_BOLD }
    private val speedUnit = label("km/h", 12f, MUTED)
    private val clusterPower = label("—", 22f, WHITE).apply { typeface = Typeface.DEFAULT_BOLD }
    private val homeSoc = label("--", 48f, GREEN).apply { typeface = Typeface.DEFAULT_BOLD }
    private val voltageText = metricValue()
    private val currentText = metricValue()
    private val powerText = metricValue()
    private val tempText = metricValue()
    private val remainText = metricValue()
    private val deltaText = metricValue()
    private val flagText = label(context.getString(R.string.waiting_data), 12f, MUTED).apply { maxLines = 2 }
    private val voltTitle = label(context.getString(R.string.page_voltage), 16f, WHITE).apply { typeface = Typeface.DEFAULT_BOLD }
    private val resTitle = label(context.getString(R.string.page_resistance), 16f, WHITE).apply { typeface = Typeface.DEFAULT_BOLD }
    private val voltHeader = label("", 12f, WHITE).apply { maxLines = 2 }
    private val resHeader = label("", 12f, WHITE).apply { maxLines = 2 }
    private val scanButton = button(context.getString(R.string.action_scan))
    private val keepButton = button(context.getString(R.string.keep_off))
    private val protocolButtons = BoardProtocol.entries.associateWith { protocol ->
        button(context.getString(protocol.labelRes)).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setOnClickListener { host?.onChooseProtocol(protocol.wire) }
        }
    }
    private val pageDots = makePageDots()
    private val scanList = LinearLayout(context).apply { orientation = VERTICAL }
    private val voltList = LinearLayout(context).apply { orientation = VERTICAL }
    private val resList = LinearLayout(context).apply { orientation = VERTICAL }
    private val voltBoard = ChipBoard(voltList)
    private val resBoard = ChipBoard(resList)
    private val weatherTitle = label(context.getString(R.string.page_weather), 16f, WHITE).apply { typeface = Typeface.DEFAULT_BOLD }
    private val weatherPlace = label("", 13f, MUTED).apply {
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    private val weatherTemp = label(context.getString(R.string.weather_locating), 22f, MUTED)
    private val weatherPhrase = label("", 18f, CYAN)
    private val weatherFeels = label("—", 16f, WHITE).apply { typeface = Typeface.DEFAULT_BOLD }
    private val weatherHumidity = label("—", 16f, WHITE).apply { typeface = Typeface.DEFAULT_BOLD }
    private val weatherWind = label("—", 16f, WHITE).apply { typeface = Typeface.DEFAULT_BOLD }
    private val weatherFacts = LinearLayout(context)
    private val weatherScroll = scrollOf(weatherPage(), fill = true)
    private val chargeRing = ChargeRing(context)
    private val chargeVoltage = label("—", 16f, GREEN).apply { typeface = Typeface.DEFAULT_BOLD }
    private val chargeCurrent = label("—", 16f, GREEN).apply { typeface = Typeface.DEFAULT_BOLD }
    private val chargeCycles = label("—", 16f, GREEN).apply { typeface = Typeface.DEFAULT_BOLD }
    private val chargeClock = label("—", 15f, GREEN)
    private val chargeScroll = scrollOf(chargePage(), fill = true)
    private val clusterScroll = scrollOf(clusterPage(), fill = true)
    private val homeScroll = scrollOf(homePage(), fill = true)
    private val voltScroll = scrollOf(pageColumn(voltTitle, voltHeader, voltList), fill = true)
    private val resScroll = scrollOf(pageColumn(resTitle, resHeader, resList), fill = true)
    private val scanScroll = scrollOf(scanList)
    private val settingsPanel = settingsPage()
    private val passwordOverlay = passwordPad()
    private val digitLabel: TextView = passwordOverlay.findViewWithTag(TAG_DIGITS)
        ?: error("缺少密码显示")
    private val typed = StringBuilder()
    private var shownPassword = DEFAULT_BOARD_PASSWORD
    private var pendingAddress: String? = null
    private var pendingName: String? = null
    private var page = Page.CLUSTER
    private var returnPage = Page.CLUSTER
    private var charging = false
    private var snapshotKey = ""
    private var scanKey = ""
    private var downX = 0f
    private var downY = 0f
    private var swipeArmed = false
    private var dotSwipe = false
    private var pendingSlide = PageSlide.NONE
    private var sliding = false
    private var slideGen = 0
    private var dotsHeld = true
    private var dotHideArmed = false
    private val stage = FrameLayout(context)
    private var burnStep = burnOriginStep()
    private var burnDir = 1
    private val burnShift: Runnable = Runnable { shiftBurn() }
    private val hideDots = Runnable {
        dotsHeld = false
        if (activePages().indexOf(page) >= 0) pageDots.visibility = INVISIBLE
    }

    init {
        setBackgroundColor(0xFF000000.toInt())
        clipToPadding = false
        applyCornerInset()
        statusText.visibility = GONE
        val column = LinearLayout(context).apply { orientation = VERTICAL }
        addView(stage, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        stage.addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        column.addView(statusText, matchWrap())
        val middle = FrameLayout(context)
        column.addView(middle, LinearLayout.LayoutParams(MATCH, 0, 1f))
        middle.addView(weatherScroll, matchMatch())
        middle.addView(chargeScroll, matchMatch())
        middle.addView(clusterScroll, matchMatch())
        middle.addView(homeScroll, matchMatch())
        middle.addView(voltScroll, matchMatch())
        middle.addView(resScroll, matchMatch())
        middle.addView(settingsPanel, matchMatch())
        column.addView(pageDots, matchWrap().apply { topMargin = dp(2f); bottomMargin = dp(2f) })
        stage.addView(passwordOverlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        scanButton.setOnClickListener { host?.onRefreshScan() }
        keepButton.setOnClickListener { host?.onToggleKeepScreen() }
    }

    fun rotaryTarget(): ScrollView {
        return when (page) {
            Page.WEATHER -> weatherScroll
            Page.CHARGE -> chargeScroll
            Page.CLUSTER -> clusterScroll
            Page.HOME -> homeScroll
            Page.VOLTAGE -> voltScroll
            Page.RESISTANCE -> resScroll
            Page.SETTINGS -> scanScroll
        }
    }

    fun render(state: DashState) {
        val from = page
        val pageChanged = from != state.page
        page = state.page
        shownPassword = state.password
        val showStatus = state.page == Page.SETTINGS && !state.statusQuiet
        statusText.visibility = if (showStatus) VISIBLE else GONE
        statusText.text = state.status
        statusText.setTextColor(
            when {
                state.statusAlarm -> RED
                state.connected -> CYAN
                else -> MUTED
            }
        )
        charging = state.confirmedCharging
        syncDots()
        val slide = pendingSlide
        pendingSlide = PageSlide.NONE
        if (pageChanged && slide != PageSlide.NONE) {
            slidePages(from, state.page, slide)
        } else if (!sliding) {
            placePages(state.page)
        }
        styleDots(state.page)
        armDotHide(pageChanged)
        bindSnapshot(state.snapshot, state.showDemo, state.rangeKm, state.speedKmh, state.confirmedCharging)
        bindWeather(state.weather)
        bindCharge(state)
        bindSettings(state)
        if (pageChanged) rotaryTarget().requestFocus()
    }

    private fun bindSnapshot(
        snapshot: BmsSnapshot?,
        demo: Boolean,
        rangeKm: Int?,
        speedKmh: Int?,
        confirmedCharging: Boolean
    ) {
        val key = snapshotKey(snapshot, demo) + "|" + (rangeKm ?: "-") + "|" + (speedKmh ?: "-") + "|" + confirmedCharging
        if (key == snapshotKey) return
        snapshotKey = key
        if (snapshot == null) {
            clusterSoc.text = "--"
            clusterSoc.setTextColor(MUTED)
            levelBar.setLevel(0, MUTED)
            rangeValue.text = "—"
            rangeValue.setTextColor(MUTED)
            clusterDelta.text = "—"
            clusterDelta.setTextColor(MUTED)
            speedValue.text = "—"
            speedValue.setTextColor(MUTED)
            homeSoc.text = "--"
            homeSoc.setTextColor(MUTED)
            clusterPower.text = "—"
            clusterPower.setTextColor(MUTED)
            listOf(voltageText, currentText, powerText, tempText, remainText, deltaText).forEach {
                it.text = "--"
                it.setTextColor(MUTED)
            }
            flagText.text = context.getString(R.string.waiting_data)
            flagText.setTextColor(MUTED)
            voltHeader.visibility = GONE
            resHeader.visibility = GONE
            voltBoard.bind(emptyList())
            resBoard.bind(emptyList())
            return
        }
        val socColor = socTextColor(snapshot.soc)
        clusterSoc.text = "${snapshot.soc}%"
        clusterSoc.setTextColor(socColor)
        levelBar.setLevel(snapshot.soc, socColor)
        if (demo) {
            rangeValue.text = "${DemoRide.RANGE_KM}km"
            rangeValue.setTextColor(WHITE)
        } else if (rangeKm != null) {
            rangeValue.text = "${rangeKm}km"
            rangeValue.setTextColor(WHITE)
        } else {
            rangeValue.text = "—"
            rangeValue.setTextColor(MUTED)
        }
        if (speedKmh != null) {
            speedValue.text = speedKmh.toString()
            speedValue.setTextColor(WHITE)
        } else if (demo) {
            speedValue.text = DemoRide.SPEED_KMH.toString()
            speedValue.setTextColor(WHITE)
        } else {
            speedValue.text = "—"
            speedValue.setTextColor(MUTED)
        }
        homeSoc.text = "${snapshot.soc}%"
        homeSoc.setTextColor(socColor)
        clusterDelta.text = "${snapshot.deltaMv}mV"
        clusterDelta.setTextColor(deltaColor(snapshot.deltaMv))
        clusterPower.text = formatPower(snapshot.power)
        clusterPower.setTextColor(powerTextColor(snapshot.power))
        voltageText.text = String.format(Locale.US, "%.2fV", snapshot.totalVoltage)
        voltageText.setTextColor(WHITE)
        currentText.text = String.format(Locale.US, "%+.1fA", snapshot.current)
        currentText.setTextColor(if (snapshot.current >= 0f) GREEN else AMBER)
        powerText.text = formatPower(snapshot.power)
        powerText.setTextColor(powerTextColor(snapshot.power))
        val temp = snapshot.batteryTempC ?: snapshot.mosTempC
        tempText.text = if (temp == null) "--" else String.format(Locale.US, "%.1f°C", temp)
        tempText.setTextColor(WHITE)
        remainText.text = String.format(Locale.US, "%.1fAh", snapshot.remainingAh)
        remainText.setTextColor(WHITE)
        deltaText.text = "${snapshot.deltaMv}mV"
        deltaText.setTextColor(deltaColor(snapshot.deltaMv))
        flagText.text = flagLine(context, snapshot, confirmedCharging)
        flagText.setTextColor(if (snapshot.alarmLabels.isEmpty()) GREEN else RED)
        val cells = snapshot.cells
        voltHeader.text = context.getString(R.string.volt_header, cells.size, snapshot.deltaMv)
        voltHeader.visibility = VISIBLE
        voltHeader.setTextColor(WHITE)
        val resDelta = resistanceDelta(cells)
        resHeader.text = if (snapshot.hasResistance) {
            context.getString(
                R.string.res_header,
                cells.size,
                formatMilliOhm(snapshot.averageResistanceOhm),
                formatMilliOhm(resDelta)
            )
        } else {
            context.getString(R.string.res_header_none, cells.size)
        }
        resHeader.visibility = VISIBLE
        resHeader.setTextColor(WHITE)
        val minVoltage = cells.minOf { it.voltage }
        val maxVoltage = cells.maxOf { it.voltage }
        voltBoard.bind(cells.map { cell ->
            CellChipModel(
                cell.index,
                CellBoardLayout.voltText(cell.voltage),
                cellTone(cell.voltage, minVoltage, maxVoltage, markHigh = false)
            )
        })
        val minResistance = cells.minOf { it.resistanceOhm }
        val maxResistance = cells.maxOf { it.resistanceOhm }
        resBoard.bind(cells.map { cell ->
            if (!snapshot.hasResistance) {
                CellChipModel(cell.index, "—", CellTone.NORMAL)
            } else {
                CellChipModel(
                    cell.index,
                    CellBoardLayout.ohmText(formatMilliOhm(cell.resistanceOhm)),
                    cellTone(cell.resistanceOhm, minResistance, maxResistance, markHigh = true)
                )
            }
        })
    }

    private fun bindSettings(state: DashState) {
        keepButton.text = context.getString(if (state.keepScreenOn) R.string.keep_on else R.string.keep_off)
        keepButton.setTextColor(if (state.keepScreenOn) PURE_WHITE else MUTED)
        styleProtocol(state.chosenProtocol)
        val savedAddresses = state.saved.map { it.address }.toSet()
        val nearby = state.devices.filter { it.address !in savedAddresses }
        val key = buildString {
            append(state.scanning)
            append('|')
            append(state.chosenProtocol)
            append('|')
            state.saved.forEach { append(it.address); append(it.name); append(it.status); append(it.alarm); append(it.protocol); append(';') }
            append('|')
            nearby.forEach { append(it.address); append(it.name); append(it.rssi); append(it.jkService); append(it.hint); append(';') }
        }
        if (key == scanKey) return
        scanKey = key
        scanList.removeAllViews()
        scanList.addView(sectionTitle(context.getString(R.string.section_saved)))
        if (state.saved.isEmpty()) {
            scanList.addView(hint(context.getString(R.string.none_saved)))
        } else {
            state.saved.forEach { board ->
                scanList.addView(savedRow(board))
            }
        }
        scanList.addView(sectionTitle(context.getString(R.string.section_nearby)))
        if (nearby.isEmpty()) {
            scanList.addView(hint(context.getString(if (state.scanning) R.string.scanning else R.string.no_new_boards)))
        } else {
            nearby.forEach { device ->
                val wire = device.hint.ifBlank { state.chosenProtocol }
                scanList.addView(nearbyRow(device, context.getString(BoardProtocol.fromWire(wire).labelRes)))
            }
        }
    }

    fun promptPassword(address: String, name: String, filled: String) {
        if (passwordOverlay.visibility == VISIBLE && pendingAddress == address) return
        shownPassword = filled.ifBlank { DEFAULT_BOARD_PASSWORD }
        openPasswordPad(address, name)
    }

    private fun openPasswordPad(address: String, name: String) {
        pendingAddress = address
        pendingName = name
        typed.clear()
        typed.append(shownPassword)
        digitLabel.text = typed.toString().ifEmpty { " " }
        passwordOverlay.visibility = VISIBLE
    }

    private fun closePasswordPad() {
        pendingAddress = null
        pendingName = null
        passwordOverlay.visibility = GONE
    }

    private fun activePages(): List<Page> = dataPages(charging)

    private fun syncDots() {
        val count = activePages().size
        if (pageDots.childCount == count) return
        pageDots.removeAllViews()
        repeat(count) {
            val dot = View(context)
            dot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(MUTED)
            }
            pageDots.addView(dot, LinearLayout.LayoutParams(dp(6f), dp(6f)).apply {
                marginStart = dp(3f)
                marginEnd = dp(3f)
            })
        }
    }

    private fun chargePage(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            addView(chargeRing, LinearLayout.LayoutParams(dp(148f), dp(148f)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            })
            val metrics = ChargeMetricRow(context).apply {
                addView(iconValue(ChargeIcon.VOLT, chargeVoltage))
                addView(iconValue(ChargeIcon.CURRENT, chargeCurrent))
                addView(iconValue(ChargeIcon.CYCLE, chargeCycles))
            }
            addView(metrics, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8f) })
            addView(iconValue(ChargeIcon.PIN, chargeClock), matchWrap().apply { topMargin = dp(8f) })
        }
    }

    private fun iconValue(kind: ChargeIcon, value: TextView): LinearLayout {
        val mark = when (kind) {
            ChargeIcon.VOLT -> letterMark("V")
            ChargeIcon.CURRENT -> letterMark("A")
            else -> ChargeGlyph(context, kind)
        }
        val markWidth = if (kind == ChargeIcon.VOLT || kind == ChargeIcon.CURRENT) WRAP else dp(14f)
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            addView(mark, LinearLayout.LayoutParams(markWidth, if (markWidth == WRAP) WRAP else dp(14f)).apply {
                marginEnd = dp(3f)
            })
            addView(value, LinearLayout.LayoutParams(WRAP, WRAP))
        }
    }

    private fun letterMark(text: String): TextView {
        return label(text, 15f, GREEN).apply { typeface = Typeface.DEFAULT_BOLD }
    }

    /** Current stays on the screen center. Voltage and cycles sit on either side with the same gap. */
    private inner class ChargeMetricRow(context: Context) : ViewGroup(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = View.MeasureSpec.getSize(widthMeasureSpec)
            val childWidth = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST)
            val childHeight = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            var height = 0
            for (index in 0 until childCount) {
                val child = getChildAt(index)
                child.measure(childWidth, childHeight)
                height = maxOf(height, child.measuredHeight)
            }
            setMeasuredDimension(width, height)
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            if (childCount < 3) return
            val volt = getChildAt(0)
            val current = getChildAt(1)
            val cycles = getChildAt(2)
            val width = right - left
            val height = bottom - top
            val currentLeft = (width - current.measuredWidth) / 2
            val currentRight = currentLeft + current.measuredWidth
            val cycleInset = (dp(14f) * 0.18f).toInt()
            val gap = min(
                currentLeft - volt.measuredWidth,
                width - currentRight - cycles.measuredWidth + cycleInset
            ).coerceAtLeast(0)
            place(volt, currentLeft - gap - volt.measuredWidth, height)
            place(current, currentLeft, height)
            place(cycles, currentRight + gap - cycleInset, height)
        }

        private fun place(child: View, childLeft: Int, height: Int) {
            val childTop = (height - child.measuredHeight) / 2
            child.layout(childLeft, childTop, childLeft + child.measuredWidth, childTop + child.measuredHeight)
        }
    }

    private fun bindCharge(state: DashState) {
        val snap = state.snapshot
        if (state.page != Page.CHARGE || snap == null || !charging) return
        val minutes = chargeRemainingMinutes(snap.nominalAh, snap.remainingAh, snap.current)
        chargeRing.show(snap.soc, formatChargeRemain(minutes), socTextColor(snap.soc))
        chargeVoltage.text = String.format(Locale.US, "%.1f", snap.totalVoltage)
        chargeCurrent.text = String.format(Locale.US, "%.1f", kotlin.math.abs(snap.current))
        chargeCycles.text = snap.cycleCount?.toString() ?: "—"
        chargeClock.text = state.clock
    }

    private fun weatherPage(): LinearLayout {
        weatherFacts.orientation = VERTICAL
        weatherFacts.gravity = android.view.Gravity.CENTER_HORIZONTAL
        weatherFacts.addView(
            metricRow(captionStack(context.getString(R.string.label_feels), weatherFeels), captionStack(context.getString(R.string.label_humidity), weatherHumidity)),
            matchWrap()
        )
        weatherFacts.addView(captionStack(context.getString(R.string.label_wind), weatherWind), matchWrap().apply { topMargin = dp(4f) })
        return LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = android.view.Gravity.CENTER
            addView(weatherTitle, matchWrap())
            addView(weatherPlace, matchWrap().apply { topMargin = dp(2f) })
            addView(weatherTemp, matchWrap().apply { topMargin = dp(2f) })
            addView(weatherPhrase, matchWrap().apply { topMargin = dp(2f) })
            addView(weatherFacts, matchWrap().apply { topMargin = dp(6f) })
        }
    }

    private fun bindWeather(weather: WeatherView) {
        weatherPlace.text = weather.place
        weatherPlace.visibility = if (weather.ready) VISIBLE else GONE
        weatherPhrase.text = weather.phrase
        weatherPhrase.visibility = if (weather.ready) VISIBLE else GONE
        weatherFacts.visibility = if (weather.ready) VISIBLE else GONE
        weatherTemp.text = weather.temperature
        if (weather.ready) {
            weatherTemp.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 52f)
            weatherTemp.setTypeface(weatherTemp.typeface, android.graphics.Typeface.BOLD)
            weatherTemp.setTextColor(WHITE)
            weatherFeels.text = weather.feels
            weatherHumidity.text = weather.humidity
            weatherWind.text = weather.wind
        } else {
            weatherTemp.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 22f)
            weatherTemp.setTypeface(android.graphics.Typeface.DEFAULT)
            weatherTemp.setTextColor(MUTED)
        }
    }

    private fun clusterPage(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            val socRow = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(levelBar, LinearLayout.LayoutParams(0, dp(10f), 1f))
                addView(clusterSoc, LinearLayout.LayoutParams(dp(58f), WRAP).apply { marginStart = dp(6f) })
            }
            addView(socRow, matchWrap().apply { topMargin = dp(2f) })
            addView(metricRow(captionStack(context.getString(R.string.label_range), rangeValue), captionStack(context.getString(R.string.label_delta), clusterDelta)), matchWrap().apply {
                topMargin = dp(6f)
            })
            addView(speedBlock(), matchWrap().apply { topMargin = dp(2f) })
            addView(clusterPower, matchWrap().apply { topMargin = dp(2f) })
        }
    }

    private fun speedBlock(): FrameLayout {
        return object : FrameLayout(context) {
            init {
                addView(speedValue, LayoutParams(WRAP, WRAP, Gravity.CENTER_HORIZONTAL))
                addView(speedUnit, LayoutParams(WRAP, WRAP, Gravity.START))
            }

            override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
                super.onLayout(changed, left, top, right, bottom)
                speedUnit.offsetLeftAndRight(speedValue.right + dp(3f) - speedUnit.left)
                speedUnit.offsetTopAndBottom(
                    speedValue.top + speedValue.baseline - speedUnit.baseline - speedUnit.top
                )
            }
        }
    }

    private fun homePage(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            addView(homeSoc, matchWrap())
            addView(metricRow(voltageText, currentText), matchWrap().apply { topMargin = dp(14f) })
            addView(metricRow(powerText, tempText), matchWrap().apply { topMargin = dp(2f) })
            addView(metricRow(remainText, deltaText), matchWrap().apply { topMargin = dp(2f) })
            addView(flagText, matchWrap().apply { topMargin = dp(4f) })
        }
    }

    private fun protocolRow(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            protocolButtons.values.forEach { chip ->
                addView(chip, LinearLayout.LayoutParams(0, dp(28f), 1f).apply {
                    marginStart = dp(1f)
                    marginEnd = dp(1f)
                })
            }
        }
    }

    private fun styleProtocol(wire: String) {
        val selected = BoardProtocol.fromWire(wire)
        protocolButtons.forEach { (protocol, chip) ->
            chip.setTextColor(if (protocol == selected) PURE_WHITE else MUTED)
        }
    }

    private fun settingsPage(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = GONE
            addView(buttonRow(scanButton, keepButton), matchWrap())
            addView(protocolRow(), matchWrap().apply { topMargin = dp(4f) })
            addView(scanScroll, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(4f) })
        }
    }

    private fun pageColumn(title: TextView, header: TextView, list: LinearLayout): LinearLayout {
        return LinearLayout(context).apply {
            orientation = VERTICAL
            addView(title, matchWrap())
            addView(header, matchWrap().apply { topMargin = dp(1f) })
            addView(list, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(2f) })
        }
    }

    private fun passwordPad(): LinearLayout {
        val digits = label(DEFAULT_BOARD_PASSWORD, 22f, CYAN).apply {
            tag = TAG_DIGITS
            typeface = Typeface.DEFAULT_BOLD
        }
        val cancel = button(context.getString(R.string.action_cancel)).apply {
            setOnClickListener { closePasswordPad() }
        }
        return LinearLayout(context).apply {
            orientation = VERTICAL
            setBackgroundColor(0xFF000000.toInt())
            visibility = GONE
            isClickable = true
            addView(label(context.getString(R.string.password_title), 12f, MUTED), matchWrap())
            addView(digits, matchWrap().apply { topMargin = dp(2f) })
            addView(cancel, LinearLayout.LayoutParams(MATCH, dp(28f)).apply { topMargin = dp(2f); bottomMargin = dp(2f) })
            val keys = listOf(
                listOf("1" to "1", "2" to "2", "3" to "3"),
                listOf("4" to "4", "5" to "5", "6" to "6"),
                listOf("7" to "7", "8" to "8", "9" to "9"),
                listOf("del" to context.getString(R.string.action_delete), "0" to "0", "ok" to context.getString(R.string.action_done))
            )
            keys.forEach { rowKeys ->
                val row = LinearLayout(context).apply { orientation = HORIZONTAL }
                rowKeys.forEach { (action, caption) ->
                    row.addView(keyButton(action, caption), LinearLayout.LayoutParams(0, MATCH, 1f).apply {
                        marginStart = dp(3f)
                        marginEnd = dp(3f)
                        topMargin = dp(2f)
                        bottomMargin = dp(2f)
                    })
                }
                addView(row, LinearLayout.LayoutParams(MATCH, 0, 1f))
            }
        }
    }

    private fun keyButton(action: String, caption: String): TextView {
        return button(caption).apply {
            setOnClickListener {
                when (action) {
                    "del" -> if (typed.isNotEmpty()) typed.deleteCharAt(typed.lastIndex)
                    "ok" -> {
                        val value = typed.toString().trim()
                        val address = pendingAddress
                        val name = pendingName
                        closePasswordPad()
                        if (value.isNotEmpty() && address != null) {
                            host?.onPassword(value)
                            host?.onConnect(address, name ?: BOARD_FALLBACK_NAME)
                        }
                    }
                    else -> if (typed.length < 16) typed.append(action)
                }
                digitLabel.text = typed.toString().ifEmpty { " " }
            }
        }
    }

    private fun captionStack(caption: String, value: TextView): LinearLayout {
        return LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(label(caption, 11f, MUTED), matchWrap())
            addView(value, matchWrap().apply { topMargin = dp(2f) })
        }
    }

    private fun sectionTitle(text: String): TextView {
        return label(text, 11f, MUTED).apply {
            gravity = Gravity.START
            setPadding(0, dp(4f), 0, dp(2f))
        }
    }

    private fun hint(text: String): TextView {
        return label(text, 12f, MUTED).apply {
            gravity = Gravity.START
            maxLines = 2
        }
    }

    private fun savedRow(board: SavedBoardRow): LinearLayout {
        val title = label(boardTitle(context, board.name), 14f, if (board.alarm) RED else WHITE).apply { gravity = Gravity.START }
        val detailColor = when {
            board.alarm -> RED
            board.phase == BoardPhase.CONNECTED -> CYAN
            board.phase == BoardPhase.CONNECTING -> AMBER
            else -> MUTED
        }
        val detail = label(board.status, 11f, detailColor).apply { gravity = Gravity.START }
        val protocolButton = button(context.getString(BoardProtocol.fromWire(board.protocol).labelRes)).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(CYAN)
            setOnClickListener { host?.onCycleBoardProtocol(board.address) }
        }
        val remove = button(context.getString(R.string.action_delete)).apply {
            setTextColor(RED)
            setOnClickListener { host?.onRemoveSaved(board.address) }
        }
        val text = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(title, matchWrap())
            addView(detail, matchWrap())
        }
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4f), 0, dp(4f))
            setOnClickListener { host?.onConnect(board.address, board.name) }
            addView(text, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(protocolButton, LinearLayout.LayoutParams(dp(40f), dp(28f)).apply { marginEnd = dp(3f) })
            addView(remove, LinearLayout.LayoutParams(dp(44f), dp(28f)))
        }
    }

    private fun nearbyRow(device: JkDevice, protocolLabel: String): LinearLayout {
        val title = label(device.name, 14f, if (device.hint.isNotEmpty() || device.jkService) CYAN else WHITE).apply { gravity = Gravity.START }
        val mark = if (device.rssi != 0) "${device.rssi}dBm" else context.getString(R.string.rssi_visible)
        val detail = label("$protocolLabel  ${device.address}  $mark", 10f, MUTED).apply { gravity = Gravity.START }
        return LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(0, dp(4f), 0, dp(4f))
            setOnClickListener { host?.onConnect(device.address, device.name) }
            addView(title, matchWrap())
            addView(detail, matchWrap())
        }
    }

    private fun metricRow(left: View, right: View): LinearLayout {
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            addView(left, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(right, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
    }

    private fun metricValue() = label("--", 16f, WHITE).apply { typeface = Typeface.DEFAULT_BOLD }

    private fun buttonRow(left: TextView, right: TextView): LinearLayout {
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            addView(left, LinearLayout.LayoutParams(0, dp(34f), 1f).apply { marginEnd = dp(3f) })
            addView(right, LinearLayout.LayoutParams(0, dp(34f), 1f).apply { marginStart = dp(3f) })
        }
    }

    private fun button(text: String): TextView {
        return label(text, 13f, WHITE).apply {
            background = pill()
            setPadding(dp(4f), dp(6f), dp(4f), dp(6f))
            isClickable = true
        }
    }

    private fun makePageDots(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            repeat(DATA_PAGE_ORDER.size) {
                val dot = View(context)
                dot.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(MUTED)
                }
                addView(dot, LinearLayout.LayoutParams(dp(6f), dp(6f)).apply {
                    marginStart = dp(3f)
                    marginEnd = dp(3f)
                })
            }
        }
    }

    private fun styleDots(current: Page) {
        val selected = activePages().indexOf(current)
        pageDots.visibility = when {
            selected < 0 -> GONE
            dotsHeld -> VISIBLE
            else -> INVISIBLE
        }
        if (selected < 0) removeCallbacks(hideDots)
        for (index in 0 until pageDots.childCount) {
            val drawable = pageDots.getChildAt(index).background as GradientDrawable
            drawable.setColor(if (index == selected) PURE_WHITE else MUTED)
        }
    }

    private fun holdDots() {
        dotsHeld = true
        removeCallbacks(hideDots)
        if (activePages().indexOf(page) >= 0) pageDots.visibility = VISIBLE
    }

    private fun armDotHide(pageChanged: Boolean) {
        if (activePages().indexOf(page) < 0) {
            dotHideArmed = false
            removeCallbacks(hideDots)
            return
        }
        if (!pageChanged && dotHideArmed) return
        dotHideArmed = true
        scheduleHideDots()
    }

    private fun scheduleHideDots() {
        if (activePages().indexOf(page) < 0) {
            removeCallbacks(hideDots)
            return
        }
        dotsHeld = true
        pageDots.visibility = VISIBLE
        removeCallbacks(hideDots)
        postDelayed(hideDots, 5_000L)
    }

    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        // Lists must not take the page swipe. The crown still scrolls them.
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (passwordOverlay.visibility == VISIBLE) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                swipeArmed = true
                dotSwipe = false
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!swipeArmed) return false
                val dx = event.x - downX
                val dy = event.y - downY
                val vertical = abs(dy) > dp(16f) && abs(dy) > abs(dx) * 1.2f
                val left = dx < -dp(16f) && -dx > abs(dy) * 1.2f
                if (vertical) {
                    dotSwipe = true
                    holdDots()
                }
                return vertical || left
            }
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_MOVE && swipeArmed) {
            val dx = event.x - downX
            val dy = event.y - downY
            if (abs(dy) > dp(16f) && abs(dy) > abs(dx) * 1.2f) {
                dotSwipe = true
                holdDots()
            }
        }
        if (event.actionMasked == MotionEvent.ACTION_UP && swipeArmed) {
            commitSwipe(event.x - downX, event.y - downY)
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (dotSwipe) scheduleHideDots()
            dotSwipe = false
            swipeArmed = false
        }
        return true
    }

    private fun commitSwipe(dx: Float, dy: Float) {
        val distance = dp(48f)
        val left = dx < -distance && -dx > abs(dy)
        val vertical = abs(dy) > distance && abs(dy) > abs(dx)
        if (page == Page.SETTINGS) {
            if (!left) return
            pendingSlide = PageSlide.LEFT
            host?.onPage(returnPage)
            return
        }
        if (left) {
            returnPage = page
            pendingSlide = PageSlide.LEFT
            host?.onOpenSettings()
            return
        }
        if (!vertical) return
        val target = adjacentDataPage(page, forward = dy < 0f, charging) ?: return
        pendingSlide = if (dy < 0f) PageSlide.UP else PageSlide.DOWN
        host?.onPage(target)
    }

    private fun placePages(current: Page) {
        slideGen += 1
        sliding = false
        pageViews().forEach { (candidate, view) ->
            view.animate().cancel()
            view.translationX = 0f
            view.translationY = 0f
            view.visibility = if (candidate == current) VISIBLE else GONE
        }
    }

    private fun slidePages(from: Page, to: Page, slide: PageSlide) {
        val views = pageViews()
        val outView = views.firstOrNull { it.first == from }?.second
        val inView = views.firstOrNull { it.first == to }?.second
        val parent = outView?.parent as? View
        if (outView == null || inView == null || parent == null || parent.width < 1 || parent.height < 1) {
            placePages(to)
            return
        }
        val gen = ++slideGen
        sliding = true
        views.forEach { (_, view) ->
            if (view != outView && view != inView) {
                view.animate().cancel()
                view.translationX = 0f
                view.translationY = 0f
                view.visibility = GONE
            }
        }
        val travelX = parent.width.toFloat()
        val travelY = parent.height.toFloat()
        val (dx, dy) = when (slide) {
            PageSlide.UP -> 0f to -travelY
            PageSlide.DOWN -> 0f to travelY
            PageSlide.LEFT -> -travelX to 0f
            PageSlide.RIGHT, PageSlide.NONE -> travelX to 0f
        }
        outView.animate().cancel()
        inView.animate().cancel()
        outView.translationX = 0f
        outView.translationY = 0f
        outView.visibility = VISIBLE
        if (inView.width != parent.width || inView.height != parent.height) {
            inView.measure(
                View.MeasureSpec.makeMeasureSpec(parent.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(parent.height, View.MeasureSpec.EXACTLY)
            )
            inView.layout(0, 0, parent.width, parent.height)
        }
        inView.translationX = -dx
        inView.translationY = -dy
        inView.visibility = VISIBLE
        val interpolator = DecelerateInterpolator()
        outView.animate()
            .translationX(dx)
            .translationY(dy)
            .setDuration(SLIDE_MS)
            .setInterpolator(interpolator)
            .withEndAction {
                if (gen != slideGen) return@withEndAction
                sliding = false
                if (page != to) {
                    placePages(page)
                    return@withEndAction
                }
                outView.translationX = 0f
                outView.translationY = 0f
                outView.visibility = GONE
                inView.translationX = 0f
                inView.translationY = 0f
            }
            .start()
        inView.animate()
            .translationX(0f)
            .translationY(0f)
            .setDuration(SLIDE_MS)
            .setInterpolator(interpolator)
            .start()
    }

    private fun pageViews(): List<Pair<Page, View>> {
        return listOf(
            Page.WEATHER to weatherScroll,
            Page.HOME to homeScroll,
            Page.CHARGE to chargeScroll,
            Page.CLUSTER to clusterScroll,
            Page.VOLTAGE to voltScroll,
            Page.RESISTANCE to resScroll,
            Page.SETTINGS to settingsPanel
        )
    }

    private fun label(text: String, sp: Float, color: Int): TextView {
        return TextView(context).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTextColor(color)
            includeFontPadding = false
            gravity = Gravity.CENTER
            maxLines = 1
        }
    }

    private fun scrollOf(child: View, fill: Boolean = false): ScrollView {
        return CrownScroll(context).apply {
            isVerticalScrollBarEnabled = false
            isFillViewport = fill
            isFocusable = true
            isFocusableInTouchMode = true
            overScrollMode = OVER_SCROLL_NEVER
            addView(child, FrameLayout.LayoutParams(MATCH, if (fill) MATCH else WRAP))
        }
    }

    private fun pill(): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = dp(16f).toFloat()
            setColor(0xFF141414.toInt())
        }
    }

    private fun matchWrap() = LinearLayout.LayoutParams(MATCH, WRAP)
    private fun matchMatch() = FrameLayout.LayoutParams(MATCH, MATCH)

    override fun onDetachedFromWindow() {
        stopBurnShift()
        removeCallbacks(hideDots)
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        applyCornerInset()
        if (windowVisibility == VISIBLE) startBurnShift()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) startBurnShift() else stopBurnShift()
    }

    private fun startBurnShift() {
        removeCallbacks(burnShift)
        postDelayed(burnShift, BURN_INTERVAL_MS)
    }

    private fun stopBurnShift() {
        removeCallbacks(burnShift)
    }

    private fun shiftBurn() {
        val next = nextBurnStep(burnStep, burnDir)
        burnStep = next.first
        burnDir = next.second
        val offset = burnOffset(burnStep)
        stage.translationX = offset.first.toFloat()
        stage.translationY = offset.second.toFloat()
        postDelayed(burnShift, BURN_INTERVAL_MS)
    }

    /**
     * The corner inset used to be applied in onSizeChanged. That laid the first frame out
     * edge to edge, then a second layout pulled it inside the rounded glass, which looked
     * like the screen zooming in after launch.
     */
    private fun applyCornerInset() {
        val screen = min(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        if (screen <= 0) return
        val inset = (screen * 0.09f).toInt()
        if (paddingLeft != inset || paddingTop != inset || paddingRight != inset || paddingBottom != inset) {
            setPadding(inset, inset, inset, inset)
        }
    }

    private fun dp(value: Float): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }

    private fun snapshotKey(snapshot: BmsSnapshot?, demo: Boolean): String {
        if (snapshot == null) return "none"
        return buildString {
            append(demo)
            append('|')
            append(snapshot.soc)
            append('|')
            append(snapshot.totalVoltage)
            append('|')
            append(snapshot.current)
            append('|')
            append(snapshot.power)
            append('|')
            append(snapshot.deltaMv)
            append('|')
            append(snapshot.remainingAh)
            append('|')
            append(snapshot.batteryTempC)
            append('|')
            append(snapshot.chargeState)
            append('|')
            append(snapshot.hasResistance)
            append('|')
            append(snapshot.balancing)
            append('|')
            append(snapshot.alarmLabels.joinToString(","))
            append('|')
            snapshot.cells.forEach {
                append(it.index)
                append(':')
                append(it.voltage)
                append(':')
                append(it.resistanceOhm)
                append(';')
            }
        }
    }

    private data class CellChipModel(val index: Int, val value: String, val tone: CellTone)

    private inner class LevelBar(context: Context) : FrameLayout(context) {
        private val fill = View(context)
        private val fillPaint = GradientDrawable()
        private var fraction = 0f

        init {
            val track = GradientDrawable().apply {
                cornerRadius = dp(5f).toFloat()
                setColor(0xFF1C1C1C.toInt())
            }
            background = track
            fillPaint.cornerRadius = dp(5f).toFloat()
            fillPaint.setColor(GREEN)
            fill.background = fillPaint
            addView(fill)
        }

        fun setLevel(percent: Int, color: Int) {
            fraction = (percent / 100f).coerceIn(0f, 1f)
            fillPaint.setColor(color)
            requestLayout()
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            val width = right - left
            val height = bottom - top
            val fillWidth = (width * fraction).toInt().coerceAtLeast(if (fraction > 0f) dp(4f) else 0)
            fill.layout(0, 0, fillWidth.coerceAtMost(width), height)
        }
    }

    private inner class CellChip(context: Context) : LinearLayout(context) {
        val badge: TextView
        val valueText: TextView
        private val badgeBg = GradientDrawable()

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            badge = TextView(context).apply {
                gravity = Gravity.CENTER
                includeFontPadding = false
                minWidth = dp(14f)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(0xFFFFFFFF.toInt())
                setPadding(dp(2f), dp(1f), dp(2f), dp(1f))
            }
            badgeBg.cornerRadius = dp(2f).toFloat()
            badge.background = badgeBg
            valueText = TextView(context).apply {
                includeFontPadding = false
                maxLines = 1
                fontFeatureSettings = "tnum"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                setPadding(dp(2f), 0, 0, 0)
                val slot = paint.measureText("0.000V") + paddingLeft + paddingRight
                minWidth = kotlin.math.ceil(slot.toDouble()).toInt()
            }
            addView(badge, LinearLayout.LayoutParams(WRAP, WRAP))
            addView(valueText, LinearLayout.LayoutParams(WRAP, WRAP))
        }

        fun show(model: CellChipModel) {
            badge.text = model.index.toString()
            valueText.text = model.value
            val (badgeColor, textColor) = when (model.tone) {
                CellTone.LOW -> BADGE_BLUE to BLUE
                CellTone.HIGH -> 0xFFE23B4A.toInt() to RED
                CellTone.NORMAL -> 0xFF1FAF5A.toInt() to GREEN
            }
            badgeBg.setColor(badgeColor)
            valueText.setTextColor(textColor)
        }
    }

    private inner class ChipBoard(private val container: LinearLayout) {
        private val chips = ArrayList<CellChip>()

        fun bind(items: List<CellChipModel>) {
            val rows = if (items.isEmpty()) 0 else (items.size + CellBoardLayout.COLUMNS - 1) / CellBoardLayout.COLUMNS
            if (chips.size != items.size || container.childCount != rows) {
                container.removeAllViews()
                chips.clear()
                var index = 0
                repeat(rows) {
                    val row = LinearLayout(context).apply {
                        orientation = HORIZONTAL
                        gravity = Gravity.CENTER
                    }
                    repeat(CellBoardLayout.COLUMNS) {
                        if (index <= items.lastIndex) {
                            val chip = CellChip(context)
                            chips.add(chip)
                            row.addView(chip, LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                                marginStart = dp(1f)
                                marginEnd = dp(1f)
                            })
                            index++
                        } else {
                            row.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
                        }
                    }
                    container.addView(row, LinearLayout.LayoutParams(MATCH, 0, 1f))
                }
            }
            items.forEachIndexed { index, model ->
                chips[index].show(model)
                chips[index].valueText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            }
            container.post { tighten() }
        }

        private fun tighten() {
            if (chips.isEmpty() || container.width <= 0) return
            val columnWidth = container.width / CellBoardLayout.COLUMNS
            chips.forEach { chip ->
                val available = columnWidth - chip.badge.width - dp(6f)
                if (available <= 0) return@forEach
                val measured = chip.valueText.paint.measureText(chip.valueText.text.toString())
                if (measured <= available) return@forEach
                val fitted = (chip.valueText.textSize * available / measured).coerceAtLeast(dp(8f).toFloat())
                if (fitted < chip.valueText.textSize - 0.5f) {
                    chip.valueText.setTextSize(TypedValue.COMPLEX_UNIT_PX, fitted)
                }
            }
        }
    }

    /**
     * Finger drags pass through to the dashboard so a page swipe cannot scroll the list.
     * The crown still calls [ScrollView.scrollBy].
     */
    private class CrownScroll(context: Context) : ScrollView(context) {
        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = false
        override fun onTouchEvent(ev: MotionEvent): Boolean = false
    }

    private inner class ChargeRing(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val arc = RectF()
        private var percent = 0
        private var remain = "—"
        private var accent = GREEN

        fun show(percent: Int, remain: String, color: Int) {
            this.percent = percent.coerceIn(0, 100)
            this.remain = remain
            accent = color
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val stroke = dp(7f).toFloat()
            val pad = stroke / 2f + dp(1f)
            arc.set(pad, pad, width - pad, height - pad)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = stroke
            paint.strokeCap = Paint.Cap.ROUND
            paint.color = 0xFF1C2420.toInt()
            canvas.drawArc(arc, 0f, 360f, false, paint)
            paint.color = accent
            val sweep = 360f * percent / 100f
            if (sweep > 0f) canvas.drawArc(arc, -90f, sweep, false, paint)

            val cx = width / 2f
            val cy = height / 2f
            paint.style = Paint.Style.FILL
            paint.color = accent
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.textSize = dp(26f).toFloat()
            val label = "$percent%"
            val labelWidth = paint.measureText(label)
            val bolt = dp(14f).toFloat()
            val gap = dp(3f).toFloat()
            val rowLeft = cx - (bolt + gap + labelWidth) / 2f
            val rowY = cy - dp(8f)
            drawBolt(canvas, rowLeft, rowY, bolt)
            paint.textAlign = Paint.Align.LEFT
            canvas.drawText(label, rowLeft + bolt + gap, baseline(rowY), paint)

            paint.textSize = dp(13f).toFloat()
            paint.typeface = Typeface.DEFAULT
            paint.color = GREEN
            val timeWidth = paint.measureText(remain)
            val clock = dp(11f).toFloat()
            val timeLeft = cx - (clock + gap + timeWidth) / 2f
            val timeY = cy + dp(16f)
            drawClock(canvas, timeLeft + clock / 2f, timeY, clock / 2f)
            canvas.drawText(remain, timeLeft + clock + gap, baseline(timeY), paint)
        }

        private fun baseline(centerY: Float): Float {
            return centerY - (paint.ascent() + paint.descent()) / 2f
        }

        private fun drawBolt(canvas: Canvas, left: Float, centerY: Float, size: Float) {
            val path = Path()
            val top = centerY - size * 0.55f
            path.moveTo(left + size * 0.55f, top)
            path.lineTo(left + size * 0.18f, centerY + size * 0.02f)
            path.lineTo(left + size * 0.48f, centerY + size * 0.02f)
            path.lineTo(left + size * 0.32f, centerY + size * 0.58f)
            path.lineTo(left + size * 0.92f, centerY - size * 0.08f)
            path.lineTo(left + size * 0.55f, centerY - size * 0.08f)
            path.close()
            val saved = paint.style
            paint.style = Paint.Style.FILL
            canvas.drawPath(path, paint)
            paint.style = saved
        }

        private fun drawClock(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
            val saved = paint.style
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1.3f).toFloat()
            canvas.drawCircle(cx, cy, radius, paint)
            canvas.drawLine(cx, cy, cx, cy - radius * 0.55f, paint)
            canvas.drawLine(cx, cy, cx + radius * 0.45f, cy, paint)
            paint.style = saved
        }
    }

    private enum class PageSlide { NONE, UP, DOWN, LEFT, RIGHT }

    private enum class ChargeIcon { VOLT, CURRENT, CYCLE, PIN }

    private inner class ChargeGlyph(context: Context, private val kind: ChargeIcon) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GREEN }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            when (kind) {
                ChargeIcon.VOLT, ChargeIcon.CURRENT -> Unit
                ChargeIcon.CYCLE -> drawCycle(canvas, w, h)
                ChargeIcon.PIN -> drawPin(canvas, w, h)
            }
        }

        private fun drawCycle(canvas: Canvas, w: Float, h: Float) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1.3f).toFloat()
            paint.strokeCap = Paint.Cap.ROUND
            val oval = RectF(w * 0.18f, h * 0.18f, w * 0.82f, h * 0.82f)
            canvas.drawArc(oval, 40f, 280f, false, paint)
            paint.style = Paint.Style.FILL
            val path = Path()
            path.moveTo(w * 0.78f, h * 0.18f)
            path.lineTo(w * 0.78f, h * 0.42f)
            path.lineTo(w * 0.54f, h * 0.28f)
            path.close()
            canvas.drawPath(path, paint)
        }

        private fun drawPin(canvas: Canvas, w: Float, h: Float) {
            paint.style = Paint.Style.FILL
            val path = Path()
            path.moveTo(w * 0.5f, h * 0.92f)
            path.cubicTo(w * 0.5f, h * 0.62f, w * 0.12f, h * 0.52f, w * 0.12f, h * 0.34f)
            path.cubicTo(w * 0.12f, h * 0.08f, w * 0.88f, h * 0.08f, w * 0.88f, h * 0.34f)
            path.cubicTo(w * 0.88f, h * 0.52f, w * 0.5f, h * 0.62f, w * 0.5f, h * 0.92f)
            path.close()
            canvas.drawPath(path, paint)
            paint.color = 0xFF000000.toInt()
            canvas.drawCircle(w * 0.5f, h * 0.34f, w * 0.1f, paint)
            paint.color = GREEN
        }
    }

    companion object {
        private const val TAG_DIGITS = "digits"
        private const val MATCH = LayoutParams.MATCH_PARENT
        private const val WRAP = LayoutParams.WRAP_CONTENT
        private const val WHITE = 0xFFF4F7FB.toInt()
        private const val PURE_WHITE = 0xFFFFFFFF.toInt()
        private const val SLIDE_MS = 220L
        private const val MUTED = 0xFF8E98A8.toInt()
        private const val CYAN = 0xFF3DDCFF.toInt()
        private const val GREEN = 0xFF3DDC84.toInt()
        private const val AMBER = 0xFFFFC14D.toInt()
        private const val RED = 0xFFFF5C6A.toInt()
        private const val BLUE = 0xFF4DA3FF.toInt()
        private const val BADGE_BLUE = 0xFF1F6FDB.toInt()

        private fun deltaColor(deltaMv: Int): Int {
            return when {
                deltaMv >= 100 -> RED
                deltaMv >= 50 -> AMBER
                else -> WHITE
            }
        }

        private fun resistanceDelta(cells: List<CellReading>): Float {
            if (cells.isEmpty()) return 0f
            return cells.maxOf { it.resistanceOhm } - cells.minOf { it.resistanceOhm }
        }

        internal fun socTextColor(soc: Int): Int {
            return when {
                soc <= 20 -> RED
                soc >= 60 -> GREEN
                else -> AMBER
            }
        }

        internal fun powerTextColor(watts: Float): Int {
            val magnitude = kotlin.math.abs(watts)
            return when {
                magnitude <= 1000f -> GREEN
                magnitude <= 2000f -> AMBER
                else -> RED
            }
        }

        private fun formatPower(watts: Float): String {
            return if (kotlin.math.abs(watts) >= 1000f) {
                String.format(Locale.US, "%+.2fkW", watts / 1000f)
            } else {
                String.format(Locale.US, "%+.0fW", watts)
            }
        }

        private fun flagLine(context: Context, snapshot: BmsSnapshot, confirmedCharging: Boolean): String {
            val parts = ArrayList<String>()
            val state = if (snapshot.chargeState == "充电" && !confirmedCharging) "放电" else snapshot.chargeState
            parts.add(chargeText(context, state))
            if (!snapshot.chargingMos) parts.add(context.getString(R.string.flag_charge_off))
            if (!snapshot.dischargingMos) parts.add(context.getString(R.string.flag_discharge_off))
            if (snapshot.balancing) parts.add(context.getString(R.string.flag_balance))
            if (snapshot.heating) parts.add(context.getString(R.string.flag_heat))
            if (snapshot.alarmLabels.isEmpty()) parts.add(context.getString(R.string.flag_ok))
            else parts.add(snapshot.alarmLabels.joinToString(" ") { alarmText(context, it) })
            return parts.joinToString("  ")
        }
    }
}

internal enum class CellTone { NORMAL, LOW, HIGH }

/** Lowest cells are blue. Highest resistance stays red. Equal cells stay green. */
internal fun cellTone(value: Float, low: Float, high: Float, markHigh: Boolean): CellTone {
    if (low == high) return CellTone.NORMAL
    if (value == low) return CellTone.LOW
    if (markHigh && value == high) return CellTone.HIGH
    return CellTone.NORMAL
}

/** Finger-up order. Settings and scan are not in this loop. */
internal val DATA_PAGE_ORDER = listOf(Page.WEATHER, Page.HOME, Page.CLUSTER, Page.VOLTAGE, Page.RESISTANCE)

internal fun dataPages(charging: Boolean): List<Page> {
    if (!charging) return DATA_PAGE_ORDER
    val pages = ArrayList(DATA_PAGE_ORDER)
    pages.add(pages.indexOf(Page.CLUSTER), Page.CHARGE)
    return pages
}

internal fun adjacentDataPage(page: Page, forward: Boolean, charging: Boolean = false): Page? {
    val order = dataPages(charging)
    val index = order.indexOf(page)
    if (index < 0) return null
    val count = order.size
    val step = if (forward) 1 else count - 1
    return order[(index + step) % count]
}

internal object CellBoardLayout {
    const val COLUMNS = 3

    fun voltText(voltage: Float): String = String.format(Locale.US, "%.3fV", voltage)

    fun ohmText(milliOhm: String): String = "${milliOhm}mΩ"
}
