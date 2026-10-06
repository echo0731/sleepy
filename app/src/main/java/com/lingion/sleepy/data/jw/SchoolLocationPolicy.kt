package com.lingion.sleepy.data.jw

import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin
import java.util.concurrent.TimeUnit

enum class SchoolLocationMode { PRECISE, APPROXIMATE }

/** Transient sample; never persisted. Age uses the monotonic clock, not wall-clock time. */
internal data class SchoolLocationFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
    val ageNanos: Long,
)

/** Cached and newly requested fixes share quality checks. */
internal object SchoolLocationPolicy {
    private val maxAgeNanos = TimeUnit.SECONDS.toNanos(30)

    fun modeForPermissions(coarse: Boolean, fine: Boolean): SchoolLocationMode? = when {
        !coarse -> null
        fine -> SchoolLocationMode.PRECISE
        else -> SchoolLocationMode.APPROXIMATE
    }

    fun providers(mode: SchoolLocationMode, available: List<String>): List<String> =
        available.filter { it != "passive" && (mode == SchoolLocationMode.PRECISE || it != "gps") }.distinct()

    fun usable(mode: SchoolLocationMode, fix: SchoolLocationFix): Boolean =
        fix.latitude.isFinite() && fix.latitude in -90.0..90.0 &&
            fix.longitude.isFinite() && fix.longitude in -180.0..180.0 &&
            fix.ageNanos in 0..maxAgeNanos && fix.accuracyMeters.isFinite() &&
            fix.accuracyMeters > 0 &&
            fix.accuracyMeters <= if (mode == SchoolLocationMode.PRECISE) 1_000.0 else 10_000.0

    fun forCityLookup(mode: SchoolLocationMode, fix: SchoolLocationFix): SchoolLocationFix =
        if (mode == SchoolLocationMode.PRECISE) fix else fix.copy(
            // An earlier fine grant must not make the approximate choice use fine coordinates.
            latitude = round(fix.latitude * 100) / 100,
            longitude = round(fix.longitude * 100) / 100,
            accuracyMeters = fix.accuracyMeters + 800.0,
        )

    fun city(index: OfflineSchoolCityIndex, fix: SchoolLocationFix): String? {
        val city = index.cityAt(fix.latitude, fix.longitude) ?: return null
        val latitudeRadius = fix.accuracyMeters / 111_320.0
        val longitudeRadius = latitudeRadius / cos(Math.toRadians(fix.latitude)).coerceAtLeast(0.01)
        // Sample the reported uncertainty in eight directions to reduce border ambiguity.
        for (step in 0 until 8) {
            val angle = step * Math.PI / 4
            if (index.cityAt(fix.latitude + latitudeRadius * sin(angle),
                    fix.longitude + longitudeRadius * cos(angle)) != city) return null
        }
        return city
    }
}
