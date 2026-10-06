package com.lingion.sleepy.data.jw

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

sealed interface SchoolLocationState {
    data object Idle : SchoolLocationState
    data object Locating : SchoolLocationState
    data class Ready(val city: String) : SchoolLocationState
    data object PermissionDenied : SchoolLocationState
    data object AccuracyInsufficient : SchoolLocationState
    data object LocationDisabled : SchoolLocationState
    data object PositionUnavailable : SchoolLocationState
    data object Unavailable : SchoolLocationState
}

/** One user-initiated foreground lookup. Coordinates are never stored or logged. */
class SchoolCityLocator(private val context: Context) {
    suspend fun locate(): SchoolLocationState {
        val coarse = hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        val fine = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        val mode = SchoolLocationPolicy.modeForPermissions(coarse, fine)
            ?: return SchoolLocationState.PermissionDenied
        val manager = context.getSystemService(LocationManager::class.java)
            ?: return SchoolLocationState.PositionUnavailable
        return try {
            if (!LocationManagerCompat.isLocationEnabled(manager)) return SchoolLocationState.LocationDisabled
            withTimeoutOrNull(12_000) { findCity(manager, mode) } ?: SchoolLocationState.PositionUnavailable
        } catch (e: CancellationException) {
            throw e
        } catch (_: SecurityException) {
            SchoolLocationState.PermissionDenied
        } catch (_: Exception) {
            SchoolLocationState.Unavailable
        }
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // Checked before entering; approximate mode excludes GPS.
    private suspend fun findCity(manager: LocationManager, mode: SchoolLocationMode): SchoolLocationState {
        val providers = SchoolLocationPolicy.providers(mode, manager.getProviders(true))
        val cached = providers.mapNotNull { provider ->
            try { manager.getLastKnownLocation(provider)?.toFix() } catch (_: Exception) { null }
        }
        return SchoolLocationLookup.findCity(mode, providers, cached, { offlineIndex() }) { provider ->
            suspendCancellableCoroutine<Location?> { continuation ->
                val signal = CancellationSignal()
                continuation.invokeOnCancellation { signal.cancel() }
                LocationManagerCompat.getCurrentLocation(
                    manager, provider, signal, ContextCompat.getMainExecutor(context)
                ) { result ->
                    if (continuation.isActive) continuation.resume(result)
                }
            }?.toFix()
        }
    }

    private fun Location.toFix() = SchoolLocationFix(
        latitude, longitude, if (hasAccuracy()) accuracy.toDouble() else Double.NaN,
        SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos,
    )

    private fun offlineIndex(): OfflineSchoolCityIndex? = try {
        cachedOfflineIndex ?: synchronized(offlineIndexLock) {
            // AAPT unpacks .gz source assets and strips that suffix in the APK.
            cachedOfflineIndex ?: context.assets.open("school_city_boundaries.json")
                .bufferedReader().use { OfflineSchoolCityIndex.parse(it.readText()) }
                .also { cachedOfflineIndex = it }
        }
    } catch (_: Exception) { null }

    private companion object {
        val offlineIndexLock = Any()
        @Volatile var cachedOfflineIndex: OfflineSchoolCityIndex? = null
    }
}
