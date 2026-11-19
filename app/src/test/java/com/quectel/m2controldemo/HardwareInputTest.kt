package com.quectel.m2controldemo

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HardwareInputTest {
    @Test
    fun m2PhysicalPinMapsToChipAndLine() {
        assertEquals(2 to 6, HardwareInput.gpioForPin("13"))
        assertEquals(4 to 4, HardwareInput.gpioForPin("16"))
    }

    @Test
    fun bulkGpioTargetsOnlyTheMappedUniqueLines() {
        assertEquals(28, HardwareInput.m2Pins.size)
        assertEquals(28, HardwareInput.m2Pins.values.toSet().size)
    }

    @Test
    fun rejectsPowerAndGroundPinsAsGpio() {
        assertThrows(IllegalArgumentException::class.java) {
            HardwareInput.gpioForPin("2")
        }
    }

    @Test
    fun parsesHexPayloadWithCommonSeparators() {
        assertArrayEquals(
            byteArrayOf(0xAA.toByte(), 0x55, 0, 0xFF.toByte()),
            HardwareInput.hexPayload("0xAA 55,00:FF")
        )
    }

    @Test
    fun rejectsMalformedHexPayload() {
        assertThrows(IllegalArgumentException::class.java) {
            HardwareInput.hexPayload("AA 5G")
        }
    }

    @Test
    fun roundTripsByteHex() {
        val bytes = byteArrayOf(0, 0x60, 0xFF.toByte())
        assertEquals("0060FF", HardwareInput.toHex(bytes))
        assertArrayEquals(bytes, HardwareInput.fromHex("0060FF"))
    }

    @Test
    fun restrictsDeviceNodesToExpectedLinuxInterfaces() {
        assertEquals("/dev/ttyS1", HardwareInput.uartDevice("/dev/ttyS1"))
        assertEquals("/dev/spidev1.0", HardwareInput.spiDevice("/dev/spidev1.0"))
        assertThrows(IllegalArgumentException::class.java) {
            HardwareInput.spiDevice("spidev1.0; reboot")
        }
    }

    @Test
    fun oledTextFitsTheM2DisplayFont() {
        assertEquals("M2 CONTROL", HardwareInput.oledText("M2 CONTROL"))
        assertThrows(IllegalArgumentException::class.java) {
            HardwareInput.oledText("0123456789012345678901")
        }
        assertThrows(IllegalArgumentException::class.java) {
            HardwareInput.oledText("中文")
        }
    }
}
