package com.lingion.sleepy.data.jw

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class SchoolLocationPolicyTest {
    private val precise = SchoolLocationMode.PRECISE
    private val approximate = SchoolLocationMode.APPROXIMATE
    private fun fix(accuracy: Double = 30.0, ageSeconds: Long = 1) =
        SchoolLocationFix(23.123456, 114.456789, accuracy, TimeUnit.SECONDS.toNanos(ageSeconds))

    private val border = OfflineSchoolCityIndex.parse("""{"areas":[
        {"name":"A","polygons":[[[[0,0],[1,0],[1,1],[0,1],[0,0]]]]},
        {"name":"B","polygons":[[[[1,0],[2,0],[2,1],[1,1],[1,0]]]]}
    ]}""")

    @Test fun `system grants select precise approximate or no location`() {
        assertEquals(precise, SchoolLocationPolicy.modeForPermissions(coarse = true, fine = true))
        assertEquals(approximate, SchoolLocationPolicy.modeForPermissions(coarse = true, fine = false))
        assertNull(SchoolLocationPolicy.modeForPermissions(coarse = false, fine = false))
        assertNull(SchoolLocationPolicy.modeForPermissions(coarse = false, fine = true))
    }

    @Test fun `approximate excludes GPS even with an earlier precise grant`() {
        val providers = listOf("gps", "network", "fused", "passive", "network")
        assertEquals(listOf("network", "fused"), SchoolLocationPolicy.providers(approximate, providers))
        assertEquals(listOf("gps", "network", "fused"), SchoolLocationPolicy.providers(precise, providers))
    }

    @Test fun `only samples no older than thirty seconds can be fast results`() {
        for (mode in SchoolLocationMode.entries) {
            assertTrue(SchoolLocationPolicy.usable(mode, fix(ageSeconds = 30)))
            assertFalse(SchoolLocationPolicy.usable(mode, fix(ageSeconds = 31)))
            assertFalse(SchoolLocationPolicy.usable(mode, fix(ageSeconds = -1)))
            assertFalse(SchoolLocationPolicy.usable(mode, fix(ageSeconds = 300)))
        }
    }

    @Test fun `a fast network response must meet the selected accuracy`() {
        assertTrue(SchoolLocationPolicy.usable(precise, fix(1_000.0)))
        assertFalse(SchoolLocationPolicy.usable(precise, fix(1_001.0)))
        assertTrue(SchoolLocationPolicy.usable(approximate, fix(10_000.0)))
        assertFalse(SchoolLocationPolicy.usable(approximate, fix(10_001.0)))
        for (accuracy in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertFalse(SchoolLocationPolicy.usable(approximate, fix(accuracy)))
        }
    }

    @Test fun `invalid coordinates cannot become fast results`() {
        for (sample in listOf(fix().copy(latitude = Double.NaN), fix().copy(latitude = 91.0),
            fix().copy(longitude = -181.0), fix().copy(longitude = Double.POSITIVE_INFINITY))) {
            assertFalse(SchoolLocationPolicy.usable(approximate, sample))
        }
    }

    @Test fun `approximate rounds an available precise sample and accounts for the added uncertainty`() {
        val source = fix()
        val coarse = SchoolLocationPolicy.forCityLookup(approximate, source)
        assertEquals(23.12, coarse.latitude, 0.0)
        assertEquals(114.46, coarse.longitude, 0.0)
        assertEquals(830.0, coarse.accuracyMeters, 0.0)
        assertEquals(source.ageNanos, coarse.ageNanos)
        assertEquals(source, SchoolLocationPolicy.forCityLookup(precise, source))
    }

    @Test fun `wide uncertainty crossing a city border is rejected while a precise fix resolves`() {
        val sample = SchoolLocationFix(0.5, 0.999, 500.0, 0)
        assertNull(SchoolLocationPolicy.city(border, sample))
        assertEquals("A", SchoolLocationPolicy.city(border, sample.copy(accuracyMeters = 10.0)))
    }

    @Test fun `approximate rounding near a border does not manufacture a confident city`() {
        val sample = SchoolLocationFix(0.5, 0.999, 10.0, 0)
        assertNull(SchoolLocationPolicy.city(border, SchoolLocationPolicy.forCityLookup(approximate, sample)))
        assertEquals("A", SchoolLocationPolicy.city(border, SchoolLocationFix(0.5, 0.5, 5_000.0, 0)))
        assertNull(SchoolLocationPolicy.city(border, SchoolLocationFix(20.0, 20.0, 10.0, 0)))
    }
}
