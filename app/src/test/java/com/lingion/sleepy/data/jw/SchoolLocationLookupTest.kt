package com.lingion.sleepy.data.jw

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class SchoolLocationLookupTest {
    private val index = SchoolBoundaryFixture.parse("""{"areas":[
        {"name":"A","polygons":[[[[0,0],[1,0],[1,1],[0,1],[0,0]]]]}
    ]}""")
    private val sample = SchoolLocationFix(0.5, 0.5, 30.0, 0)

    @Test fun `fast network city returns without waiting for GPS and cancels GPS`() = runBlocking {
        var gpsStarted = false
        var gpsCancelled = false
        val result = withTimeout(2_000) {
            SchoolLocationLookup.findCity(SchoolLocationMode.PRECISE,
                listOf("gps", "network"), emptyList(), { index }) { provider ->
                if (provider == "gps") {
                    gpsStarted = true
                    try { CompletableDeferred<SchoolLocationFix>().await() }
                    finally { gpsCancelled = true }
                } else sample
            }
        }
        assertEquals(SchoolLocationState.Ready("A"), result)
        assertTrue(gpsStarted)
        assertTrue(gpsCancelled)
    }

    @Test fun `recent qualified cache returns while all live providers wait`() = runBlocking {
        val result = withTimeout(2_000) {
            SchoolLocationLookup.findCity(SchoolLocationMode.PRECISE,
                listOf("gps", "network"), listOf(sample), { index }) {
                CompletableDeferred<SchoolLocationFix>().await()
            }
        }
        assertEquals(SchoolLocationState.Ready("A"), result)
    }

    @Test fun `fast inaccurate result cannot beat a slower precise result`() = runBlocking {
        val networkReturned = CompletableDeferred<Unit>()
        val result = SchoolLocationLookup.findCity(SchoolLocationMode.PRECISE,
            listOf("gps", "network"), listOf(sample.copy(ageNanos = 60_000_000_000)), { index }) {
            if (it == "network") {
                networkReturned.complete(Unit)
                sample.copy(accuracyMeters = 5_000.0)
            } else {
                networkReturned.await()
                sample
            }
        }
        assertEquals(SchoolLocationState.Ready("A"), result)
    }

    @Test fun `approximate never starts the GPS provider`() = runBlocking {
        val requested = mutableListOf<String>()
        val result = SchoolLocationLookup.findCity(SchoolLocationMode.APPROXIMATE,
            listOf("gps", "network"), emptyList(), { index }) {
            requested += it
            sample
        }
        assertEquals(SchoolLocationState.Ready("A"), result)
        assertEquals(listOf("network"), requested)
    }

    @Test fun `leaving the page cancels every pending provider`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var starts = 0
        var cancellations = 0
        val job = launch {
            SchoolLocationLookup.findCity(SchoolLocationMode.PRECISE,
                listOf("gps", "network"), emptyList(), { index }) {
                starts++
                if (starts == 2) started.complete(Unit)
                try { CompletableDeferred<SchoolLocationFix>().await() }
                finally { cancellations++ }
            }
        }
        withTimeout(2_000) { started.await() }
        job.cancelAndJoin()
        assertEquals(2, cancellations)
    }

    @Test fun `outside imprecise and absent results have distinct outcomes`() = runBlocking {
        suspend fun result(fix: SchoolLocationFix?) = SchoolLocationLookup.findCity(
            SchoolLocationMode.PRECISE, listOf("network"), emptyList(), { index }) { fix }
        assertEquals(SchoolLocationState.Unavailable, result(sample.copy(latitude = 20.0)))
        assertEquals(SchoolLocationState.AccuracyInsufficient, result(sample.copy(accuracyMeters = 5_000.0)))
        assertEquals(SchoolLocationState.PositionUnavailable, result(null))
        assertEquals(SchoolLocationState.AccuracyInsufficient, result(sample.copy(longitude = 0.999, accuracyMeters = 500.0)))
    }
}
