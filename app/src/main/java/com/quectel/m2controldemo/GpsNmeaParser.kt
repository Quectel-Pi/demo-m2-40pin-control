package com.quectel.m2controldemo

import kotlin.math.floor

data class GpsSnapshot(
    val hasFix: Boolean = false,
    val fixQuality: Int? = null,
    val satellites: Int? = null,
    val hdop: Double? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitudeMeters: Double? = null,
    val speedKmh: Double? = null,
    val courseDegrees: Double? = null,
    val utcTime: String? = null,
    val validSentenceCount: Int = 0,
    val invalidSentenceCount: Int = 0,
    val lastSentenceType: String? = null
)

class GpsNmeaParser {
    var snapshot: GpsSnapshot = GpsSnapshot()
        private set

    fun reset(): GpsSnapshot {
        snapshot = GpsSnapshot()
        return snapshot
    }

    fun accept(rawSentence: String): GpsSnapshot {
        val sentence = rawSentence.trim()
        val fields = checkedFields(sentence)
        if (fields == null) {
            snapshot = snapshot.copy(invalidSentenceCount = snapshot.invalidSentenceCount + 1)
            return snapshot
        }

        val type = fields.firstOrNull()?.takeLast(3) ?: ""
        snapshot = snapshot.copy(
            validSentenceCount = snapshot.validSentenceCount + 1,
            lastSentenceType = type
        )
        when (type) {
            "GGA" -> acceptGga(fields)
            "RMC" -> acceptRmc(fields)
        }
        return snapshot
    }

    private fun acceptGga(fields: List<String>) {
        if (fields.size < 11) return
        val quality = fields[6].toIntOrNull() ?: return
        val latitude = if (quality > 0) coordinate(fields[2], fields[3], true) else null
        val longitude = if (quality > 0) coordinate(fields[4], fields[5], false) else null
        val hasFix = quality > 0 && latitude != null && longitude != null
        snapshot = snapshot.copy(
            hasFix = hasFix,
            fixQuality = quality,
            satellites = fields[7].toIntOrNull(),
            hdop = fields[8].toDoubleOrNull()?.takeIf(Double::isFinite),
            latitude = latitude.takeIf { hasFix },
            longitude = longitude.takeIf { hasFix },
            altitudeMeters = fields[9].toDoubleOrNull()?.takeIf(Double::isFinite),
            utcTime = fields[1].takeIf(String::isNotBlank)
        )
    }

    private fun acceptRmc(fields: List<String>) {
        if (fields.size < 10) return
        val active = fields[2] == "A"
        val latitude = if (active) coordinate(fields[3], fields[4], true) else null
        val longitude = if (active) coordinate(fields[5], fields[6], false) else null
        val hasFix = active && latitude != null && longitude != null
        snapshot = snapshot.copy(
            hasFix = hasFix,
            fixQuality = if (hasFix) snapshot.fixQuality?.takeIf { it > 0 } else null,
            latitude = latitude.takeIf { hasFix },
            longitude = longitude.takeIf { hasFix },
            speedKmh = if (hasFix) {
                fields[7].toDoubleOrNull()?.takeIf(Double::isFinite)?.times(1.852)
            } else {
                null
            },
            courseDegrees = if (hasFix) {
                fields[8].toDoubleOrNull()?.takeIf(Double::isFinite)
            } else {
                null
            },
            utcTime = fields[1].takeIf(String::isNotBlank)
        )
    }

    private fun checkedFields(sentence: String): List<String>? {
        if (!sentence.startsWith('$')) return null
        val star = sentence.lastIndexOf('*')
        if (star <= 1 || star + 3 != sentence.length) return null
        val expected = sentence.substring(star + 1).toIntOrNull(16) ?: return null
        var checksum = 0
        for (index in 1 until star) checksum = checksum xor sentence[index].code
        if (checksum != expected) return null
        return sentence.substring(1, star).split(',')
    }

    private fun coordinate(value: String, hemisphere: String, latitude: Boolean): Double? {
        val raw = value.toDoubleOrNull()?.takeIf(Double::isFinite) ?: return null
        if (raw < 0) return null
        val degrees = floor(raw / 100.0)
        val minutes = raw - degrees * 100.0
        if (minutes !in 0.0..<60.0) return null
        val limit = if (latitude) 90.0 else 180.0
        if (degrees > limit || (degrees == limit && minutes != 0.0)) return null
        val direction = hemisphere.uppercase()
        if (latitude && direction !in setOf("N", "S")) return null
        if (!latitude && direction !in setOf("E", "W")) return null
        val decimal = degrees + minutes / 60.0
        return if (direction == "S" || direction == "W") -decimal else decimal
    }
}

fun GpsSnapshot.fixQualityLabel(): String = when (fixQuality) {
    1 -> "GPS"
    2 -> "DGPS / SBAS"
    3 -> "PPS"
    4 -> "RTK fixed"
    5 -> "RTK float"
    6 -> "Estimated"
    0 -> "No fix"
    else -> if (hasFix) "Position fix" else "Searching"
}
