package com.quectel.m2controldemo

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class HardwareAccess(context: Context) : Closeable {
    private val appContext = context.applicationContext
    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val processes = ConcurrentHashMap.newKeySet<Process>()
    private val gpsReader = Executors.newSingleThreadExecutor()
    private val gpsLock = Any()
    private val helperDirectory = "/data/local/tmp/m2controldemo"
    private val helperNames = listOf("board-io", "sensor-io", "oled-io")
    private val helperPaths = helperNames.associateWith { "$helperDirectory/$it" }

    @Volatile
    private var closed = false
    @Volatile
    private var gpsProcess: Process? = null
    @Volatile
    private var gpsDevice: String? = null
    private var gpsGeneration = 0L
    private var helperInstalled = false
    private val gpioOutputs = ConcurrentHashMap<Pair<Int, Int>, Process>()

    fun runAsync(action: () -> String, onResult: (Result<String>) -> Unit) {
        worker.execute {
            val result = try {
                Result.success(action())
            } catch (error: Exception) {
                Result.failure(error)
            }
            if (!closed) {
                mainHandler.post { if (!closed) onResult(result) }
            }
        }
    }

    fun checkRoot(): String {
        val output = runRoot("id -u", 30_000).trim()
        if (output != "0") throw IOException("The root helper started without root privileges.")
        return "Root access is ready."
    }

    fun readEnvironmentSensors(): String {
        val output = invokeExecutable("sensor-io", emptyList(), 30_000)
        val values = output.lineSequence().mapNotNull { line ->
            val separator = line.indexOf('=')
            if (separator <= 0) null
            else line.substring(0, separator) to line.substring(separator + 1)
        }.toMap()
        val requiredKeys = listOf(
            "pressure", "temperature", "humidity", "lux", "uv", "voc_status", "imu_status"
        )
        val missing = requiredKeys.filterNot(values::containsKey)
        if (missing.isNotEmpty()) {
            throw IOException("Incomplete sensor data: ${missing.joinToString()}")
        }
        return buildString {
            appendLine("Temperature  ${values.getValue("temperature")} °C")
            appendLine("Humidity     ${values.getValue("humidity")} %")
            appendLine("Pressure     ${values.getValue("pressure")} hPa")
            appendLine("Illuminance  ${values.getValue("lux")} lx")
            appendLine("UV           ${values.getValue("uv")}")
            when (values.getValue("voc_status")) {
                "ok" -> appendLine("VOC raw     ${values["voc"] ?: throw IOException("VOC reading is missing.")}")
                "read_failed" -> appendLine("VOC read failed (SGP40 did not respond)")
                else -> throw IOException("Unexpected VOC status: ${values.getValue("voc_status")}")
            }
            when (values.getValue("imu_status")) {
                "ok" -> {
                    val imuKeys = listOf(
                        "roll", "pitch", "yaw", "accel_x", "accel_y", "accel_z",
                        "gyro_x", "gyro_y", "gyro_z", "mag_x", "mag_y", "mag_z"
                    )
                    val missingImu = imuKeys.filterNot(values::containsKey)
                    if (missingImu.isNotEmpty()) {
                        throw IOException("Incomplete IMU data: ${missingImu.joinToString()}")
                    }
                    appendLine("Attitude  R ${values.getValue("roll")}°  P ${values.getValue("pitch")}°  Y ${values.getValue("yaw")}°")
                    appendLine("Accel raw ${values.getValue("accel_x")}, ${values.getValue("accel_y")}, ${values.getValue("accel_z")}")
                    appendLine("Gyro raw  ${values.getValue("gyro_x")}, ${values.getValue("gyro_y")}, ${values.getValue("gyro_z")}")
                    append("Mag raw   ${values.getValue("mag_x")}, ${values.getValue("mag_y")}, ${values.getValue("mag_z")}")
                }
                "mag_unavailable" -> {
                    val imuKeys = listOf(
                        "accel_x", "accel_y", "accel_z", "gyro_x", "gyro_y", "gyro_z"
                    )
                    val missingImu = imuKeys.filterNot(values::containsKey)
                    if (missingImu.isNotEmpty()) {
                        throw IOException("Incomplete IMU data: ${missingImu.joinToString()}")
                    }
                    appendLine("Accel raw ${values.getValue("accel_x")}, ${values.getValue("accel_y")}, ${values.getValue("accel_z")}")
                    appendLine("Gyro raw  ${values.getValue("gyro_x")}, ${values.getValue("gyro_y")}, ${values.getValue("gyro_z")}")
                    append("Magnetometer or attitude unavailable")
                }
                "not_detected" -> append("IMU not detected (no response at 0x68)")
                "initialization_failed" -> append("IMU initialization failed")
                "read_failed" -> append("IMU read failed")
                else -> throw IOException("Unexpected IMU status: ${values.getValue("imu_status")}")
            }
        }
    }

    fun showOledDemo(): String {
        invokeExecutable("oled-io", listOf("demo"))
        return "Demo pattern displayed."
    }

    fun showOledText(text: String): String {
        invokeExecutable("oled-io", listOf("show", text))
        return "Displayed: $text"
    }

    fun clearOled(): String {
        invokeExecutable("oled-io", listOf("clear"))
        return "Display cleared."
    }

    fun readGpio(pin: Int, chip: Int, line: Int): String {
        val output = invokeHelper(listOf("gpio-get", "/dev/gpiochip$chip", line.toString()))
        val value = Regex("VALUE=([01])").find(output)?.groupValues?.get(1)
            ?: throw IOException("Unexpected GPIO helper response: $output")
        return "Pin $pin · GPIO${chip * 32 + line}\nLevel: ${if (value == "1") "HIGH" else "LOW"}"
    }

    fun setGpio(pin: Int, chip: Int, line: Int, high: Boolean): String {
        val key = chip to line
        releaseGpioOutput(key)
        gpioOutputs[key] = startGpioOutput(chip, line, high)
        return "Pin $pin · GPIO${chip * 32 + line} is held ${if (high) "HIGH" else "LOW"}."
    }

    fun setAllGpio(high: Boolean): String {
        releaseAllGpioOutputs()
        val started = mutableListOf<Pair<Int, Int>>()
        try {
            HardwareInput.m2Pins.toSortedMap().values.forEach { gpio ->
                val chip = gpio / 32
                val line = gpio % 32
                val key = chip to line
                gpioOutputs[key] = startGpioOutput(chip, line, high)
                started += key
            }
        } catch (error: Exception) {
            started.forEach { key ->
                try {
                    releaseGpioOutput(key)
                } catch (cleanupError: Exception) {
                    error.addSuppressed(cleanupError)
                }
            }
            throw IOException("Bulk GPIO operation failed; newly claimed lines were released.", error)
        }
        val level = if (high) "HIGH" else "LOW"
        return "ALL ${if (high) "UP" else "DOWN"}: ${started.size} GPIO lines are held $level.\nUse RELEASE ALL to return them to input mode."
    }

    private fun startGpioOutput(chip: Int, line: Int, high: Boolean): Process {
        installHelpers()
        val process = startRootProcess(
            executableCommand(
                helperPaths.getValue("board-io"),
                listOf("gpio-set", "/dev/gpiochip$chip", line.toString(), if (high) "1" else "0")
            )
        )
        val firstLine = CompletableFuture.supplyAsync {
            process.inputStream.bufferedReader().readLine()
        }
        val response = try {
            firstLine.get(12, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            stopProcess(process)
            processes.remove(process)
            throw IOException("Timed out waiting for the GPIO controller.")
        } catch (error: Exception) {
            stopProcess(process)
            processes.remove(process)
            throw IOException("Failed while waiting for the GPIO controller.", error)
        }
        if (response != "READY") {
            stopProcess(process)
            processes.remove(process)
            throw IOException(response ?: "The GPIO controller returned no status.")
        }
        return process
    }

    fun releaseGpio(pin: Int, chip: Int, line: Int): String {
        val process = gpioOutputs[chip to line]
        if (process == null) return "Pin $pin · GPIO${chip * 32 + line} has no held output request."
        releaseGpioOutput(chip to line)
        return "Pin $pin · GPIO${chip * 32 + line} released to input mode."
    }

    fun releaseAllGpio(): String {
        val count = gpioOutputs.size
        releaseAllGpioOutputs()
        return "$count GPIO output requests released."
    }

    fun transferUart(device: String, baud: Int, timeoutMs: Int, hexPayload: String?): String {
        if (synchronized(gpsLock) { gpsDevice == device }) {
            throw IOException("Stop the GPS monitor before using $device for another UART test.")
        }
        val output = invokeHelper(
            listOf("uart", device, baud.toString(), timeoutMs.toString(), hexPayload ?: "-"),
            timeoutMs + 8_000L
        )
        val hex = Regex("RX=([0-9A-Fa-f]*)").find(output)?.groupValues?.get(1)
            ?: throw IOException("Unexpected UART helper response: $output")
        val bytes = HardwareInput.fromHex(hex)
        return buildString {
            appendLine("$device · $baud baud")
            appendLine("Received ${bytes.size} bytes")
            appendLine("HEX: ${if (hex.isEmpty()) "(no data)" else hex.chunked(2).joinToString(" ")}")
            if (bytes.isNotEmpty()) append(HardwareInput.displayBytes(bytes))
        }
    }

    fun startGpsMonitor(
        device: String,
        baud: Int,
        onSentence: (String) -> Unit,
        onFailure: (String) -> Unit
    ): String {
        synchronized(gpsLock) {
            if (gpsProcess != null) throw IOException("The GPS monitor is already running.")
            if (closed) throw IOException("Hardware access has been closed.")
        }
        installHelpers()
        val process = startRootProcess(
            executableCommand(helperPaths.getValue("board-io"), listOf("gps-stream", device, baud.toString()))
        )
        val generation = synchronized(gpsLock) {
            if (closed) {
                null
            } else {
                gpsGeneration += 1
                gpsProcess = process
                gpsDevice = device
                gpsGeneration
            }
        }
        if (generation == null) {
            stopHardwareProcess(process)
            processes.remove(process)
            throw IOException("Hardware access has been closed.")
        }
        val ready = CompletableFuture<Unit>()
        try {
            gpsReader.execute {
                var streamError: String? = null
                try {
                    process.inputStream.bufferedReader(Charsets.US_ASCII).use { reader ->
                        while (true) {
                            val line = reader.readLine() ?: break
                            when {
                                line == "GPS_READY" -> ready.complete(Unit)
                                line.startsWith("$") -> {
                                    ready.complete(Unit)
                                    dispatchGps(generation, process) { onSentence(line) }
                                }
                                line.isNotBlank() -> {
                                    streamError = line
                                    ready.completeExceptionally(IOException(line))
                                }
                            }
                        }
                    }
                    val exitCode = process.waitFor()
                    if (streamError == null && exitCode != 0) {
                        streamError = "GPS helper exited with code $exitCode."
                    }
                    if (streamError == null) streamError = "GPS data stream ended unexpectedly."
                } catch (error: Exception) {
                    streamError = error.message ?: "Failed while reading the GPS data stream."
                    ready.completeExceptionally(error)
                } finally {
                    if (!ready.isDone) {
                        ready.completeExceptionally(
                            IOException(streamError ?: "GPS helper closed before becoming ready.")
                        )
                    }
                    processes.remove(process)
                    val wasActive = synchronized(gpsLock) {
                        if (gpsGeneration == generation && gpsProcess === process) {
                            gpsProcess = null
                            gpsDevice = null
                            true
                        } else {
                            false
                        }
                    }
                    if (wasActive && !closed) {
                        val message = streamError ?: "GPS data stream stopped."
                        mainHandler.post {
                            if (!closed && synchronized(gpsLock) {
                                    gpsGeneration == generation
                                }
                            ) {
                                onFailure(message)
                            }
                        }
                    }
                }
            }
            ready.get(3, TimeUnit.SECONDS)
        } catch (error: Exception) {
            synchronized(gpsLock) {
                if (gpsGeneration == generation && gpsProcess === process) {
                    gpsProcess = null
                    gpsDevice = null
                    gpsGeneration += 1
                }
            }
            try {
                stopHardwareProcess(process)
            } catch (cleanupError: Exception) {
                error.addSuppressed(cleanupError)
            }
            val cause = (error as? java.util.concurrent.ExecutionException)?.cause ?: error
            throw IOException(
                "Unable to start GPS monitoring: ${cause.message ?: "helper did not become ready."}",
                cause
            )
        }
        return "GPS monitoring started on $device at $baud baud."
    }

    fun isGpsMonitorRunning(): Boolean = synchronized(gpsLock) { gpsProcess != null }

    fun stopGpsMonitor(): String {
        val process = synchronized(gpsLock) {
            val active = gpsProcess
            gpsProcess = null
            gpsDevice = null
            gpsGeneration += 1
            active
        } ?: return "GPS monitor is already stopped."
        try {
            stopHardwareProcess(process)
        } finally {
            processes.remove(process)
        }
        return "GPS monitor stopped; UART settings were restored."
    }

    private fun dispatchGps(generation: Long, process: Process, action: () -> Unit) {
        if (closed || synchronized(gpsLock) {
                gpsGeneration != generation || gpsProcess !== process
            }
        ) return
        mainHandler.post {
            if (!closed && synchronized(gpsLock) {
                    gpsGeneration == generation && gpsProcess === process
                }
            ) {
                action()
            }
        }
    }

    private fun stopHardwareProcess(process: Process) {
        try {
            process.outputStream.close()
        } catch (error: IOException) {
            process.destroy()
            try {
                if (!process.waitFor(500, TimeUnit.MILLISECONDS)) process.destroyForcibly()
            } catch (interrupted: InterruptedException) {
                process.destroyForcibly()
                Thread.currentThread().interrupt()
                error.addSuppressed(interrupted)
            }
            throw IOException("Failed to close the GPS helper input stream.", error)
        }
        try {
            if (!process.waitFor(1, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                if (!process.waitFor(500, TimeUnit.MILLISECONDS)) {
                    throw IOException("GPS helper did not stop after its input stream was closed.")
                }
            }
        } catch (error: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while stopping the GPS helper.", error)
        }
    }

    private fun invokeHelper(arguments: List<String>, timeoutMs: Long = 10_000): String {
        return invokeExecutable("board-io", arguments, timeoutMs)
    }

    private fun invokeExecutable(name: String, arguments: List<String>, timeoutMs: Long = 10_000): String {
        installHelpers()
        val path = helperPaths[name] ?: throw IllegalArgumentException("Unknown hardware helper: $name")
        return runRoot(executableCommand(path, arguments), timeoutMs)
    }

    private fun installHelpers() {
        if (helperInstalled) return
        val localFiles = helperNames.associateWith { name ->
            File(appContext.cacheDir, name).also { file ->
                appContext.assets.open(name).use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
        val installCommand = buildString {
            append("mkdir -p ${quote(helperDirectory)}")
            helperNames.forEach { name ->
                val destination = helperPaths.getValue(name)
                val source = localFiles.getValue(name).absolutePath
                append(" && cp ${quote(source)} ${quote(destination)}")
                append(" && chmod 755 ${quote(destination)}")
            }
        }
        runRoot(installCommand, 30_000)
        helperInstalled = true
    }

    private fun executableCommand(path: String, arguments: List<String>): String =
        "exec ${quote(path)} ${arguments.joinToString(" ") { quote(it) }}"

    private fun runRoot(command: String, timeoutMs: Long): String {
        val process = startRootProcess(command)
        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            process.destroy()
            if (!process.waitFor(500, TimeUnit.MILLISECONDS)) process.destroyForcibly()
            processes.remove(process)
            throw IOException("Hardware operation timed out after ${timeoutMs / 1000} seconds.")
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        processes.remove(process)
        if (process.exitValue() != 0) {
            throw IOException(output.ifBlank { "Root command failed with exit code ${process.exitValue()}." })
        }
        return output
    }

    private fun startRootProcess(command: String): Process {
        val process = try {
            ProcessBuilder("m2hw", "0", "sh", "-c", command)
                .redirectErrorStream(true)
                .start()
        } catch (error: IOException) {
            if (error.message?.contains("Permission denied", ignoreCase = true) == true) {
                throw IOException(
                    "Hardware access is not authorized for this app by the m2hw allowlist.",
                    error
                )
            }
            throw error
        }
        processes.add(process)
        return process
    }

    private fun releaseAllGpioOutputs() {
        var failure: Exception? = null
        gpioOutputs.keys.toList().forEach { key ->
            try {
                releaseGpioOutput(key)
            } catch (error: Exception) {
                if (failure == null) failure = error else failure?.addSuppressed(error)
            }
        }
        failure?.let { throw IOException("One or more GPIO output requests could not be released.", it) }
    }

    private fun releaseGpioOutput(key: Pair<Int, Int>) {
        val process = gpioOutputs.remove(key) ?: return
        try {
            stopProcess(process)
        } finally {
            processes.remove(process)
        }
    }

    private fun stopProcess(process: Process) {
        try {
            process.outputStream.close()
        } catch (error: IOException) {
            process.destroy()
            if (!process.waitFor(500, TimeUnit.MILLISECONDS)) process.destroyForcibly()
            throw IOException("Failed to close the GPIO helper process.", error)
        }
        try {
            if (!process.waitFor(1, TimeUnit.SECONDS)) {
                process.destroy()
                if (!process.waitFor(500, TimeUnit.MILLISECONDS)) process.destroyForcibly()
            }
        } catch (error: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while stopping the GPIO helper process.", error)
        }
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    override fun close() {
        if (closed) return
        closed = true
        val activeGpsProcess = synchronized(gpsLock) {
            gpsGeneration += 1
            val active = gpsProcess
            gpsProcess = null
            gpsDevice = null
            active
        }
        activeGpsProcess?.let { process ->
            try {
                stopHardwareProcess(process)
            } catch (error: IOException) {
                Log.e("HardwareAccess", "GPS helper did not stop cleanly during shutdown.", error)
                process.destroyForcibly()
            } finally {
                processes.remove(process)
            }
        }
        gpioOutputs.values.forEach { process ->
            try {
                process.outputStream.close()
            } catch (_: IOException) {
                process.destroy()
            }
        }
        processes.forEach { process ->
            process.destroy()
            try {
                if (!process.waitFor(300, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                    process.waitFor(300, TimeUnit.MILLISECONDS)
                }
            } catch (error: InterruptedException) {
                process.destroyForcibly()
                Thread.currentThread().interrupt()
            }
        }
        gpioOutputs.clear()
        processes.clear()
        worker.shutdownNow()
        gpsReader.shutdownNow()
    }
}
