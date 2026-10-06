package com.lingion.sleepy.data.jw

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/** Provider race independent of Android APIs, including cancellation and city quality checks. */
internal object SchoolLocationLookup {
    suspend fun findCity(
        mode: SchoolLocationMode,
        availableProviders: List<String>,
        cached: List<SchoolLocationFix>,
        loadIndex: suspend () -> OfflineSchoolCityIndex?,
        requestFix: suspend (String) -> SchoolLocationFix?,
    ): SchoolLocationState = coroutineScope {
        val providers = SchoolLocationPolicy.providers(mode, availableProviders)
        if (providers.isEmpty()) return@coroutineScope SchoolLocationState.PositionUnavailable
        val indexRequest = async(Dispatchers.IO) { loadIndex() }
        val results = Channel<SchoolLocationFix?>(providers.size)
        val requests = providers.map { provider ->
            launch {
                val fix = try { requestFix(provider) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { null }
                results.send(fix)
            }
        }
        try {
            val index = indexRequest.await() ?: return@coroutineScope SchoolLocationState.Unavailable
            var rejected: SchoolLocationState = SchoolLocationState.PositionUnavailable
            fun resolve(fix: SchoolLocationFix?): SchoolLocationState.Ready? {
                if (fix == null) return null
                if (!SchoolLocationPolicy.usable(mode, fix)) {
                    if (fix.ageNanos in 0..TimeUnit.SECONDS.toNanos(30)) {
                        rejected = SchoolLocationState.AccuracyInsufficient
                    }
                    return null
                }
                val cityFix = SchoolLocationPolicy.forCityLookup(mode, fix)
                val city = SchoolLocationPolicy.city(index, cityFix)
                if (city != null) return SchoolLocationState.Ready(city)
                if (index.cityAt(cityFix.latitude, cityFix.longitude) != null) {
                    rejected = SchoolLocationState.AccuracyInsufficient
                } else if (rejected != SchoolLocationState.AccuracyInsufficient) {
                    rejected = SchoolLocationState.Unavailable
                }
                return null
            }
            val recent = cached.filter { SchoolLocationPolicy.usable(mode, it) }
                .sortedWith(compareBy<SchoolLocationFix> { it.ageNanos }.thenBy { it.accuracyMeters })
            for (fix in recent) resolve(fix)?.let { return@coroutineScope it }
            repeat(providers.size) {
                resolve(results.receive())?.let { return@coroutineScope it }
            }
            rejected
        } finally {
            indexRequest.cancel()
            requests.forEach { it.cancel() }
            results.close()
        }
    }
}
