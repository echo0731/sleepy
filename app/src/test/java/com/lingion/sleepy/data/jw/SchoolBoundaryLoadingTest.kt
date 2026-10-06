package com.lingion.sleepy.data.jw

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.concurrent.TimeUnit

class SchoolBoundaryLoadingTest {
    private val sample = SchoolBoundaryFixture.bytes("""{"areas":[{"name":"A",
        "polygons":[[[[0,0],[10,0],[10,10],[0,10],[0,0]]]]}]}""")

    @Test fun `unknown truncated and trailing formats are rejected`() {
        for (data in listOf(ByteArray(4), sample.copyOf(12), sample + byteArrayOf(1))) {
            try {
                OfflineSchoolCityIndex.parse(ByteArrayInputStream(data))
                fail("Invalid boundary stream accepted")
            } catch (_: IllegalArgumentException) { }
            catch (_: java.io.EOFException) { }
        }
    }

    @Test fun `invalid ring count and out of range coordinates are rejected`() {
        val tooShort = SchoolBoundaryFixture.bytes("""{"areas":[{"name":"A",
            "polygons":[[[[0,0],[1,1]]]]}]}""")
        val outside = SchoolBoundaryFixture.bytes("""{"areas":[{"name":"A",
            "polygons":[[[[181,0],[1,1],[0,0]]]]}]}""")
        for (data in listOf(tooShort, outside)) {
            assertThrows(IllegalArgumentException::class.java) {
                OfflineSchoolCityIndex.parse(ByteArrayInputStream(data))
            }
        }
    }

    @Test fun `large ring parsing checks cancellation before reading the whole ring`() {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { output ->
            output.writeInt(0x53434231); output.writeInt(1)
            output.writeInt(1); output.writeByte('A'.code)
            output.writeInt(1); output.writeInt(1); output.writeInt(10000)
            repeat(10000) { output.writeInt(it); output.writeInt(it) }
        }
        val input = ByteArrayInputStream(buffer.toByteArray())
        var checks = 0
        assertThrows(CancellationException::class.java) {
            OfflineSchoolCityIndex.parse(input) {
                if (++checks == 6) throw CancellationException("cancel")
            }
        }
        assertTrue("Should stop while most ring points are still unread", input.available() > 60000)
    }

    @Test fun `timeout during index loading promptly cancels provider and does not publish index`() = runBlocking {
        val parsingStarted = CompletableDeferred<Unit>()
        val providerCancelled = CompletableDeferred<Unit>()
        var published = false
        val start = System.nanoTime()
        val result = withTimeoutOrNull(100) {
            SchoolLocationLookup.findCity(SchoolLocationMode.PRECISE, listOf("gps"), emptyList(), {
                val context = currentCoroutineContext()
                val parsed = OfflineSchoolCityIndex.parse(ByteArrayInputStream(sample)) {
                    parsingStarted.complete(Unit)
                    // Simulate CPU-bound slow parsing, continuously observing cancellation.
                    while (context.isActiveForTest()) Thread.yield()
                    context.ensureActive()
                }
                published = true
                parsed
            }) {
                try { kotlinx.coroutines.awaitCancellation() }
                finally { providerCancelled.complete(Unit) }
            }
        }
        assertNull(result)
        assertTrue(parsingStarted.isCompleted)
        assertTrue(providerCancelled.isCompleted)
        assertFalse(published)
        assertTrue("Timeout must not wait for a full parse", System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2))
    }

    @Test fun `parent cancellation during parsing also cancels provider`() = runBlocking {
        val parsingStarted = CompletableDeferred<Unit>()
        val providerStarted = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val task = async {
            SchoolLocationLookup.findCity(SchoolLocationMode.PRECISE, listOf("gps"), emptyList(), {
                val context = currentCoroutineContext()
                OfflineSchoolCityIndex.parse(ByteArrayInputStream(sample)) {
                    parsingStarted.complete(Unit)
                    while (context.isActiveForTest()) Thread.yield()
                    context.ensureActive()
                }
            }) {
                try {
                    providerStarted.complete(Unit)
                    kotlinx.coroutines.awaitCancellation()
                }
                finally { cancelled.complete(Unit) }
            }
        }
        try {
            // Both tasks must be running before testing their cancellation. The IO parser
            // can start before the provider coroutine gets its first dispatch on the JVM.
            withTimeout(5_000) {
                parsingStarted.await()
                providerStarted.await()
            }
            task.cancelAndJoin()
            assertTrue(task.isCancelled)
            assertTrue(cancelled.isCompleted)
        } finally {
            task.cancelAndJoin()
        }
    }

    private fun kotlin.coroutines.CoroutineContext.isActiveForTest(): Boolean =
        this[kotlinx.coroutines.Job]?.isActive != false
}
