package com.quectel.m2controldemo

import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import java.io.IOException
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var hardware: HardwareAccess
    private lateinit var rootStatus: TextView
    private lateinit var statusDetail: TextView
    private lateinit var resultText: TextView
    private lateinit var resultStatus: TextView
    private lateinit var rootIndicator: View
    private lateinit var rootBadge: TextView
    private lateinit var gpsFixStatus: TextView
    private lateinit var gpsCoordinates: TextView
    private lateinit var gpsMetrics: TextView
    private lateinit var gpsStreamDetail: TextView
    private lateinit var gpsStartButton: MaterialButton
    private lateinit var gpsStopButton: MaterialButton
    private lateinit var gpsOpenMapButton: MaterialButton
    private lateinit var gpsMapContainer: View
    private lateinit var gpsMapPreview: WebView
    private lateinit var gpsMapStatus: TextView
    private lateinit var operationButtons: List<MaterialButton>
    private var operationBusy = false
    private var gpsMonitoring = false
    private var gpsMapVisible = false
    private var gpsMapLoadFailed = false
    private var lastGpsUiUpdateMs = 0L
    private val gpsParser = GpsNmeaParser()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        )
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top + dp(10), bars.right, bars.bottom + dp(16))
            insets
        }
        hardware = HardwareAccess(this)
        rootStatus = findViewById(R.id.root_status)
        statusDetail = findViewById(R.id.status_detail)
        resultText = findViewById(R.id.result_text)
        resultStatus = findViewById(R.id.result_status)
        rootIndicator = findViewById(R.id.root_indicator)
        rootBadge = findViewById(R.id.root_badge)
        gpsFixStatus = findViewById(R.id.gps_fix_status)
        gpsCoordinates = findViewById(R.id.gps_coordinates)
        gpsMetrics = findViewById(R.id.gps_metrics)
        gpsStreamDetail = findViewById(R.id.gps_stream_detail)
        gpsStartButton = findViewById(R.id.gps_start)
        gpsStopButton = findViewById(R.id.gps_stop)
        gpsOpenMapButton = findViewById(R.id.gps_open_map)
        gpsMapContainer = findViewById(R.id.gps_map_container)
        gpsMapPreview = findViewById(R.id.gps_map_preview)
        gpsMapStatus = findViewById(R.id.gps_map_status)

        setupTabs()
        operationButtons = listOf(
            R.id.gpio_read, R.id.gpio_release, R.id.gpio_release_all,
            R.id.gpio_low, R.id.gpio_high, R.id.gpio_all_down, R.id.gpio_all_up,
            R.id.i2c_sensor_read, R.id.uart_send, R.id.uart_receive,
            R.id.gps_start, R.id.gps_stop,
            R.id.oled_show, R.id.oled_demo, R.id.oled_clear
        ).map(::findViewById)
        setupGpio()
        setupI2c()
        setupUart()
        setupGps()
        setupGpsMapPreview()
        setupSpi()
        updateGpsControls()
        checkRoot()
    }

    private fun setupTabs() {
        val tabs = findViewById<TabLayout>(R.id.interface_tabs)
        listOf(
            R.string.gpio_tab, R.string.i2c_tab, R.string.uart_tab,
            R.string.spi_tab, R.string.gps_tab
        )
            .forEach { tabs.addTab(tabs.newTab().setText(it)) }
        val panels = listOf(
            findViewById<View>(R.id.gpio_panel),
            findViewById<View>(R.id.i2c_panel),
            findViewById<View>(R.id.uart_panel),
            findViewById<View>(R.id.spi_panel),
            findViewById<View>(R.id.gps_panel)
        )
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                panels.forEachIndexed { index, panel ->
                    panel.visibility = if (index == tab.position) View.VISIBLE else View.GONE
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
    }

    private fun setupGpio() {
        findViewById<MaterialButton>(R.id.gpio_read).setOnClickListener {
            runOperation {
                val pin = HardwareInput.decimal("Physical pin", textOf(R.id.gpio_pin), 1, 40)
                val (chip, line) = HardwareInput.gpioForPin(pin.toString())
                hardware.readGpio(pin, chip, line)
            }
        }
        findViewById<MaterialButton>(R.id.gpio_high).setOnClickListener {
            setGpioOutput(true)
        }
        findViewById<MaterialButton>(R.id.gpio_all_up).setOnClickListener {
            confirmAllGpio(true)
        }
        findViewById<MaterialButton>(R.id.gpio_all_down).setOnClickListener {
            confirmAllGpio(false)
        }
        findViewById<MaterialButton>(R.id.gpio_low).setOnClickListener {
            setGpioOutput(false)
        }
        findViewById<MaterialButton>(R.id.gpio_release).setOnClickListener {
            runOperation {
                val pin = HardwareInput.decimal("Physical pin", textOf(R.id.gpio_pin), 1, 40)
                val (chip, line) = HardwareInput.gpioForPin(pin.toString())
                hardware.releaseGpio(pin, chip, line)
            }
        }
        findViewById<MaterialButton>(R.id.gpio_release_all).setOnClickListener {
            runOperation { hardware.releaseAllGpio() }
        }
    }

    private fun confirmAllGpio(high: Boolean) {
        MaterialAlertDialogBuilder(this)
            .setTitle(if (high) R.string.all_up_confirm_title else R.string.all_down_confirm_title)
            .setMessage(R.string.all_gpio_confirm_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(if (high) R.string.all_up else R.string.all_down) { _, _ ->
                runOperation { hardware.setAllGpio(high) }
            }
            .show()
    }

    private fun setGpioOutput(high: Boolean) {
        runOperation {
            val pin = HardwareInput.decimal("Physical pin", textOf(R.id.gpio_pin), 1, 40)
            val (chip, line) = HardwareInput.gpioForPin(pin.toString())
            hardware.setGpio(pin, chip, line, high)
        }
    }

    private fun setupI2c() {
        findViewById<MaterialButton>(R.id.i2c_sensor_read).setOnClickListener {
            runOperation { hardware.readEnvironmentSensors() }
        }
    }

    private fun setupUart() {
        findViewById<MaterialButton>(R.id.uart_send).setOnClickListener {
            runOperation {
                val device = HardwareInput.uartDevice(textOf(R.id.uart_device))
                val baud = parseBaudRate()
                val payload = textOf(R.id.uart_message).toByteArray(Charsets.UTF_8)
                require(payload.isNotEmpty()) { "Enter a message to send" }
                hardware.transferUart(device, baud, 1800, HardwareInput.toHex(payload))
            }
        }
        findViewById<MaterialButton>(R.id.uart_receive).setOnClickListener {
            runOperation {
                val device = HardwareInput.uartDevice(textOf(R.id.uart_device))
                val baud = parseBaudRate()
                hardware.transferUart(device, baud, 5000, null)
            }
        }
    }

    private fun setupGps() {
        gpsStartButton.setOnClickListener {
            val device: String
            val baud: Int
            try {
                device = HardwareInput.uartDevice(textOf(R.id.gps_device))
                baud = parseGpsBaudRate()
            } catch (error: IllegalArgumentException) {
                showInputError(error)
                return@setOnClickListener
            }
            gpsParser.reset()
            lastGpsUiUpdateMs = 0L
            renderGpsSnapshot(gpsParser.snapshot)
            runOperation(
                operation = {
                    hardware.startGpsMonitor(device, baud, ::onGpsSentence, ::onGpsFailure)
                },
                onSuccess = {
                    gpsMonitoring = hardware.isGpsMonitorRunning()
                    if (gpsMonitoring) {
                        renderGpsSnapshot(gpsParser.snapshot)
                    } else {
                        gpsFixStatus.text = getString(R.string.gps_stream_error)
                        gpsFixStatus.setTextColor(getColor(R.color.error))
                    }
                    updateGpsControls()
                }
            )
        }
        gpsStopButton.setOnClickListener {
            runOperation(
                operation = { hardware.stopGpsMonitor() },
                onSuccess = {
                    gpsMonitoring = false
                    gpsFixStatus.text = getString(R.string.gps_stopped)
                    gpsFixStatus.setTextColor(getColor(R.color.muted))
                    updateGpsControls()
                }
            )
        }
        gpsOpenMapButton.setOnClickListener { toggleGpsMapPreview() }
    }

    private fun parseGpsBaudRate(): Int {
        val baud = HardwareInput.decimal("GPS baud rate", textOf(R.id.gps_baud), 50, 4_000_000)
        require(baud in HardwareInput.supportedBaudRates) {
            "Unsupported baud rate. Supported rates: ${HardwareInput.supportedBaudRates.sorted().joinToString()}"
        }
        return baud
    }

    private fun onGpsSentence(sentence: String) {
        val snapshot = gpsParser.accept(sentence)
        val now = SystemClock.elapsedRealtime()
        if (lastGpsUiUpdateMs == 0L || now - lastGpsUiUpdateMs >= 1_500L) {
            lastGpsUiUpdateMs = now
            renderGpsSnapshot(snapshot)
        }
    }

    private fun onGpsFailure(message: String) {
        if (isFinishing || isDestroyed) return
        gpsMonitoring = false
        gpsFixStatus.text = getString(R.string.gps_stream_error)
        gpsFixStatus.setTextColor(getColor(R.color.error))
        gpsStreamDetail.text = message
        updateGpsControls()
        resultText.text = "GPS monitor stopped\n$message"
        resultStatus.text = "ERROR"
        resultStatus.setTextColor(getColor(R.color.error))
    }

    private fun renderGpsSnapshot(snapshot: GpsSnapshot) {
        gpsFixStatus.text = when {
            snapshot.hasFix -> "${snapshot.fixQualityLabel()}  •  ${snapshot.satellites ?: "—"} satellites"
            gpsMonitoring -> getString(R.string.gps_searching)
            else -> getString(R.string.gps_idle)
        }
        gpsFixStatus.setTextColor(
            getColor(if (snapshot.hasFix) R.color.success else R.color.primary)
        )
        gpsCoordinates.text = if (snapshot.hasFix) {
            val latitude = snapshot.latitude ?: return
            val longitude = snapshot.longitude ?: return
            String.format(Locale.US, "%.6f, %.6f", latitude, longitude)
        } else {
            getString(R.string.gps_no_position)
        }
        gpsMetrics.text = buildString {
            append("Altitude  ")
            append(snapshot.altitudeMeters?.let { String.format(Locale.US, "%.1f m", it) } ?: "—")
            append("    HDOP  ")
            append(snapshot.hdop?.let { String.format(Locale.US, "%.1f", it) } ?: "—")
            appendLine()
            append("Speed  ")
            append(snapshot.speedKmh?.let { String.format(Locale.US, "%.1f km/h", it) } ?: "—")
            append("    Course  ")
            append(snapshot.courseDegrees?.let { String.format(Locale.US, "%.1f°", it) } ?: "—")
        }
        gpsStreamDetail.text = getString(
            R.string.gps_stream_stats,
            snapshot.validSentenceCount,
            snapshot.invalidSentenceCount,
            snapshot.lastSentenceType ?: "—",
            snapshot.utcTime ?: "—"
        )
        gpsOpenMapButton.isEnabled = snapshot.hasFix || gpsMapVisible
    }

    private fun updateGpsControls() {
        if (!::gpsStartButton.isInitialized) return
        gpsStartButton.isEnabled = !gpsMonitoring && !operationBusy
        gpsStopButton.isEnabled = gpsMonitoring && !operationBusy
    }

    private fun setupGpsMapPreview() {
        gpsMapPreview.settings.javaScriptEnabled = true
        gpsMapPreview.settings.domStorageEnabled = true
        gpsMapPreview.settings.allowFileAccess = false
        gpsMapPreview.settings.allowContentAccess = false
        gpsMapPreview.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                gpsMapLoadFailed = false
                gpsMapStatus.setText(R.string.gps_map_loading)
                gpsMapStatus.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (!gpsMapLoadFailed) gpsMapStatus.visibility = View.GONE
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame) showGpsMapError()
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: android.webkit.WebResourceResponse
            ) {
                if (request.isForMainFrame && errorResponse.statusCode >= 400) {
                    showGpsMapError()
                }
            }
        }
    }

    private fun toggleGpsMapPreview() {
        if (gpsMapVisible) {
            gpsMapVisible = false
            gpsMapContainer.visibility = View.GONE
            gpsOpenMapButton.setText(R.string.gps_open_map)
            gpsOpenMapButton.isEnabled = gpsParser.snapshot.hasFix
            return
        }

        val snapshot = gpsParser.snapshot
        val latitude = snapshot.latitude
        val longitude = snapshot.longitude
        if (!snapshot.hasFix || latitude == null || longitude == null) return

        gpsMapVisible = true
        gpsMapContainer.visibility = View.VISIBLE
        gpsOpenMapButton.setText(R.string.gps_hide_map)
        gpsMapStatus.setText(R.string.gps_map_loading)
        gpsMapStatus.visibility = View.VISIBLE
        val url = String.format(
            Locale.US,
            "https://www.openstreetmap.org/?mlat=%.6f&mlon=%.6f#map=16/%.6f/%.6f",
            latitude,
            longitude,
            latitude,
            longitude
        )
        gpsMapPreview.loadUrl(url)
    }

    private fun showGpsMapError() {
        gpsMapLoadFailed = true
        gpsMapStatus.setText(R.string.gps_map_error)
        gpsMapStatus.visibility = View.VISIBLE
    }

    private fun setupSpi() {
        findViewById<MaterialButton>(R.id.oled_show).setOnClickListener {
            val text = try {
                HardwareInput.oledText(textOf(R.id.oled_text))
            } catch (error: IllegalArgumentException) {
                showInputError(error)
                return@setOnClickListener
            }
            runOperation { hardware.showOledText(text) }
        }
        findViewById<MaterialButton>(R.id.oled_demo).setOnClickListener {
            runOperation { hardware.showOledDemo() }
        }
        findViewById<MaterialButton>(R.id.oled_clear).setOnClickListener {
            runOperation { hardware.clearOled() }
        }
    }

    private fun parseBaudRate(): Int {
        val baud = HardwareInput.decimal("Baud rate", textOf(R.id.uart_baud), 50, 4_000_000)
        require(baud in HardwareInput.supportedBaudRates) {
            "Unsupported baud rate. Supported rates: ${HardwareInput.supportedBaudRates.sorted().joinToString()}"
        }
        return baud
    }

    private fun checkRoot() {
        setRootStatus(getString(R.string.root_checking), getString(R.string.root_hint), Color.GRAY)
        setRootBadge(R.string.badge_checking, R.color.muted)
        hardware.runAsync({ hardware.checkRoot() }) { result ->
            result.onSuccess {
                setRootStatus(
                    getString(R.string.root_ready),
                    getString(R.string.root_ready_detail),
                    getColor(R.color.success)
                )
                setRootBadge(R.string.badge_ready, R.color.success)
            }.onFailure { error ->
                setRootStatus(
                    getString(R.string.root_missing),
                    error.message ?: "Grant root access to the app.",
                    getColor(R.color.error)
                )
                setRootBadge(R.string.badge_offline, R.color.error)
            }
        }
    }

    private fun runOperation(
        onSuccess: ((String) -> Unit)? = null,
        operation: () -> String
    ) {
        if (operationBusy) return
        operationBusy = true
        operationButtons.forEach { it.isEnabled = false }
        resultText.setText(R.string.working)
        resultStatus.text = "RUNNING"
        resultStatus.setTextColor(getColor(R.color.primary))
        statusDetail.setText(R.string.test_in_progress)
        hardware.runAsync(operation) { result ->
            operationBusy = false
            operationButtons.forEach { it.isEnabled = true }
            result.onSuccess { message ->
                resultText.text = message
                resultStatus.text = "COMPLETE"
                resultStatus.setTextColor(getColor(R.color.success))
                statusDetail.setText(R.string.operation_succeeded)
                onSuccess?.invoke(message)
            }.onFailure { error ->
                resultText.text = buildErrorMessage(error)
                resultStatus.text = "ERROR"
                resultStatus.setTextColor(getColor(R.color.error))
                statusDetail.setText(R.string.operation_failed)
                if (error.message?.contains("GPS", ignoreCase = true) == true) {
                    gpsFixStatus.text = getString(R.string.gps_stream_error)
                    gpsFixStatus.setTextColor(getColor(R.color.error))
                    gpsStreamDetail.text = error.message
                }
            }
            updateGpsControls()
        }
    }

    private fun buildErrorMessage(error: Throwable): String {
        val chain = generateSequence(error) { it.cause }.toList()
        val message = error.message?.takeIf { it.isNotBlank() }
            ?: chain.lastOrNull()?.message?.takeIf { it.isNotBlank() }
            ?: "Unknown error"
        return when {
            chain.any { it is IOException } ->
                "Hardware access failed\n$message"
            chain.any { it is IllegalArgumentException } -> "Invalid input\n$message"
            else -> "Operation failed\n$message"
        }
    }

    private fun showInputError(error: IllegalArgumentException) {
        resultText.text = "Invalid input\n${error.message ?: "Check the supplied values."}"
        statusDetail.setText(R.string.operation_failed)
    }

    private fun setRootStatus(title: String, detail: String, color: Int) {
        rootStatus.text = title
        statusDetail.text = detail
        rootIndicator.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
    }

    private fun setRootBadge(text: Int, color: Int) {
        rootBadge.setText(text)
        rootBadge.setTextColor(getColor(color))
    }

    private fun textOf(viewId: Int): String =
        findViewById<EditText>(viewId).text.toString().trim()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onPause() {
        if (::gpsMapPreview.isInitialized) gpsMapPreview.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::gpsMapPreview.isInitialized) gpsMapPreview.onResume()
    }

    override fun onDestroy() {
        if (::gpsMapPreview.isInitialized) {
            gpsMapPreview.stopLoading()
            gpsMapPreview.loadUrl("about:blank")
            gpsMapPreview.clearHistory()
            gpsMapPreview.removeAllViews()
            gpsMapPreview.destroy()
        }
        if (::hardware.isInitialized) hardware.close()
        super.onDestroy()
    }
}
