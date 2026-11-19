package com.quectel.m2controldemo

object HardwareInput {
    val m2Pins = mapOf(
        3 to 140, 5 to 141, 7 to 79, 8 to 72, 10 to 73, 11 to 78, 12 to 87,
        13 to 70, 15 to 71, 16 to 132, 18 to 134, 19 to 82, 21 to 83, 22 to 76,
        23 to 85, 24 to 84, 26 to 81, 27 to 97, 28 to 96, 29 to 93, 31 to 92,
        32 to 77, 33 to 91, 35 to 86, 36 to 90, 37 to 95, 38 to 88, 40 to 89
    )

    val supportedBaudRates = setOf(
        9600, 19200, 38400, 57600, 115200, 230400, 460800, 921600, 1500000
    )

    fun decimal(name: String, raw: String, min: Int, max: Int): Int {
        val value = raw.trim().toIntOrNull()
            ?: throw IllegalArgumentException("$name must be a decimal integer")
        require(value in min..max) { "$name must be between $min and $max" }
        return value
    }

    fun hexByte(name: String, raw: String, min: Int = 0, max: Int = 0xFF): Int {
        val text = raw.trim().removePrefix("0x").removePrefix("0X")
        val value = text.toIntOrNull(16)
            ?: throw IllegalArgumentException("$name must be a hexadecimal value, e.g. 0x76")
        require(value in min..max) { "$name is outside the valid range" }
        return value
    }

    fun gpioForPin(raw: String): Pair<Int, Int> {
        val pin = decimal("Physical pin", raw, 1, 40)
        val gpio = m2Pins[pin]
            ?: throw IllegalArgumentException("Pin $pin is not a supported M2 GPIO")
        return (gpio / 32) to (gpio % 32)
    }

    fun uartDevice(raw: String): String {
        val device = raw.trim()
        require(Regex("^/dev/tty[A-Za-z0-9._-]+$").matches(device)) {
            "Invalid UART device path, e.g. /dev/ttyS1"
        }
        return device
    }

    fun spiDevice(raw: String): String {
        val device = raw.trim()
        require(Regex("^/dev/spidev[0-9]+\\.[0-9]+$").matches(device)) {
            "Invalid SPI device path, e.g. /dev/spidev1.0"
        }
        return device
    }

    fun oledText(raw: String): String {
        val text = raw.trim()
        require(text.isNotEmpty()) { "Enter text to display" }
        require(text.length <= 21) { "Text must be 21 characters or fewer" }
        require(text.all { it.code in 0x20..0x7E }) { "Only printable ASCII text is supported" }
        return text
    }

    fun hexPayload(raw: String, maxBytes: Int = 256): ByteArray {
        val tokens = raw.trim().split(Regex("[\\s,:]+")).filter { it.isNotEmpty() }
        require(tokens.isNotEmpty()) { "Enter at least one hexadecimal byte" }
        require(tokens.size <= maxBytes) { "Payload cannot exceed $maxBytes bytes" }
        return tokens.map { token ->
            val value = token.removePrefix("0x").removePrefix("0X").toIntOrNull(16)
                ?: throw IllegalArgumentException("Invalid hexadecimal byte: $token")
            require(value in 0..0xFF) { "Each value must be between 00 and FF" }
            value.toByte()
        }.toByteArray()
    }

    fun toHex(payload: ByteArray): String = payload.joinToString("") {
        "%02X".format(it.toInt() and 0xFF)
    }

    fun fromHex(raw: String): ByteArray {
        require(raw.length % 2 == 0) { "The device returned incomplete hexadecimal data" }
        return ByteArray(raw.length / 2) { index ->
            raw.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    fun displayBytes(payload: ByteArray): String {
        val text = payload.toString(Charsets.UTF_8)
            .replace("\u0000", "·")
            .replace("\r", "\\r")
            .replace("\n", "\\n")
        return if (payload.all { (it.toInt() and 0xFF) in 0x20..0x7E || it == 10.toByte() || it == 13.toByte() }) {
            "ASCII: $text"
        } else {
            "UTF-8: $text"
        }
    }
}
