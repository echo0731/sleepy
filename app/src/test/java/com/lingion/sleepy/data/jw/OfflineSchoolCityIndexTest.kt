package com.lingion.sleepy.data.jw

import org.junit.Assert.*
import org.json.JSONObject
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream

class OfflineSchoolCityIndexTest {
    companion object {
        private val index by lazy {
            val root = System.getProperty("sleepy.test.root")?.let(::File) ?: File("..")
            GZIPInputStream(File(root, "app/src/main/assets/school_city_boundaries.json.gz").inputStream())
                .bufferedReader().use { OfflineSchoolCityIndex.parse(it.readText()) }
        }
    }

    @Test fun `reported emulator location resolves to Huizhou without any geocoder`() {
        assertEquals("惠州市", index.cityAt(23.103775, 114.471620))
    }

    @Test fun `adjacent Guangdong cities do not use the nearest city centre`() {
        assertEquals("佛山市", index.cityAt(23.1385, 113.0270))
        assertEquals("广州市", index.cityAt(23.1291, 113.2644))
        assertEquals("深圳市", index.cityAt(22.5431, 114.0579))
        assertEquals("东莞市", index.cityAt(23.0207, 113.7518))
    }

    @Test fun `municipality districts resolve to city names used in school data`() {
        assertEquals("北京市", index.cityAt(39.9042, 116.4074))
        assertEquals("上海市", index.cityAt(31.2304, 121.4737))
        assertEquals("天津市", index.cityAt(39.1256, 117.1902))
        assertEquals("重庆市", index.cityAt(29.5630, 106.5516))
    }

    @Test fun `locations outside boundaries never return an arbitrary Chinese city`() {
        assertNull(index.cityAt(37.4220, -122.0840))
        assertNull(index.cityAt(0.0, 0.0))
        assertNull(index.cityAt(35.6762, 139.6503))
    }

    @Test fun `invalid coordinates cannot resolve to a city`() {
        assertNull(index.cityAt(Double.NaN, 114.0))
        assertNull(index.cityAt(23.0, Double.POSITIVE_INFINITY))
        assertNull(index.cityAt(91.0, 114.0))
        assertNull(index.cityAt(23.0, -181.0))
    }

    @Test fun `holes and disconnected islands are respected`() {
        val small = OfflineSchoolCityIndex.parse("""{"areas":[{"name":"A","polygons":[
            [[[0,0],[10,0],[10,10],[0,10],[0,0]],[[4,4],[6,4],[6,6],[4,6],[4,4]]],
            [[[20,20],[22,20],[22,22],[20,22],[20,20]]]
        ]}]}""")
        assertEquals("A", small.cityAt(1.0, 1.0))
        assertNull(small.cityAt(5.0, 5.0))
        assertEquals("A", small.cityAt(21.0, 21.0))
        assertNull(small.cityAt(15.0, 15.0))
        assertEquals("A", small.cityAt(0.0, 2.0))
    }

    @Test fun `Huizhou city result selects its actual school entry`() {
        val school = JwSchoolInfo("H", "惠州学院", cities = listOf("惠州市"))
        val other = JwSchoolInfo("G", "广东东软学院", cities = listOf("佛山市"))
        val city = requireNotNull(index.cityAt(23.103775, 114.471620))
        assertEquals(listOf(school), SchoolCityIndex.schoolsInCity(listOf(school, other), city))
    }

    @Test fun `all configured school cities exist in the bundled boundary names`() {
        val root = System.getProperty("sleepy.test.root")?.let(::File) ?: File("..")
        val assets = File(root, "app/src/main/assets")
        val areas = GZIPInputStream(File(assets, "school_city_boundaries.json.gz").inputStream())
            .bufferedReader().use { JSONObject(it.readText()).getJSONArray("areas") }
        val names = (0 until areas.length()).map { areas.getJSONObject(it).getString("name") }.toSet()
        val cities = SchoolCityIndex.parse(File(assets, "school_cities.json").readText())
        for ((school, locations) in cities) {
            for (city in locations) assertTrue("Missing boundary for $school: $city", city in names)
        }
    }

    @Test fun `prefecture location resolves a moved school independently of its name`() {
        val root = System.getProperty("sleepy.test.root")?.let(::File) ?: File("..")
        val cities = SchoolCityIndex.parse(File(root, "app/src/main/assets/school_cities.json").readText())
        val moved = JwSchoolInfo("J", "江西农业大学南昌商学院",
            cities = cities.getValue("江西农业大学南昌商学院"))
        val city = requireNotNull(index.cityAt(29.2, 115.8)) // A point in the Gongqingcheng area.
        assertEquals("九江市", city)
        assertEquals(listOf(moved), SchoolCityIndex.schoolsInCity(listOf(moved), city))
        assertTrue(SchoolCityIndex.schoolsInCity(listOf(moved), "南昌市").isEmpty())
    }
}
