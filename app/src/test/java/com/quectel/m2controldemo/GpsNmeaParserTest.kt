package com.quectel.m2controldemo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsNmeaParserTest {
    @Test
    fun parsesGgaFixAndConvertsDegreesMinutesToDecimal() {
        val parser = GpsNmeaParser()

        val snapshot = parser.accept(
            "\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47"
        )

        assertTrue(snapshot.hasFix)
        assertEquals(1, snapshot.fixQuality)
        assertEquals(8, snapshot.satellites)
        assertEquals(48.1173, snapshot.latitude!!, 0.000001)
        assertEquals(11.5166667, snapshot.longitude!!, 0.000001)
        assertEquals(545.4, snapshot.altitudeMeters!!, 0.001)
        assertEquals("GPS", snapshot.fixQualityLabel())
    }

    @Test
    fun parsesRmcSpeedAndRejectsBadChecksums() {
        val parser = GpsNmeaParser()

        val snapshot = parser.accept(
            "\$GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W*6A"
        )

        assertTrue(snapshot.hasFix)
        assertEquals(41.4848, snapshot.speedKmh!!, 0.001)
        assertEquals(84.4, snapshot.courseDegrees!!, 0.001)

        val rejected = parser.accept(
            "\$GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W*6B"
        )
        assertEquals(1, rejected.validSentenceCount)
        assertEquals(1, rejected.invalidSentenceCount)
        assertTrue(rejected.hasFix)
    }

    @Test
    fun noFixGgaClearsPosition() {
        val parser = GpsNmeaParser()
        parser.accept("\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47")

        val noFix = parser.accept(nmea("GNGGA,123520,,,,,0,00,99.9,,,,,,"))

        assertFalse(noFix.hasFix)
        assertEquals(0, noFix.fixQuality)
        assertNull(noFix.latitude)
        assertNull(noFix.longitude)
    }

    private fun nmea(body: String): String {
        val checksum = body.fold(0) { result, character -> result xor character.code }
        return "\$$body*%02X".format(checksum)
    }
}
