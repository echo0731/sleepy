package com.lingion.sleepy.data.jw

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** Local point-in-polygon lookup. Never guesses a city from its nearest centre. */
class OfflineSchoolCityIndex private constructor(private val areas: List<Area>) {
    private data class Ring(val coordinates: DoubleArray) {
        val minX = coordinates.indices.filter { it % 2 == 0 }.minOf { coordinates[it] }
        val maxX = coordinates.indices.filter { it % 2 == 0 }.maxOf { coordinates[it] }
        val minY = coordinates.indices.filter { it % 2 == 1 }.minOf { coordinates[it] }
        val maxY = coordinates.indices.filter { it % 2 == 1 }.maxOf { coordinates[it] }

        fun contains(x: Double, y: Double): Boolean {
            if (x !in minX..maxX || y !in minY..maxY) return false
            var inside = false
            var previous = coordinates.size - 2
            for (current in coordinates.indices step 2) {
                val ax = coordinates[previous]
                val ay = coordinates[previous + 1]
                val bx = coordinates[current]
                val by = coordinates[current + 1]
                val cross = (x - ax) * (by - ay) - (y - ay) * (bx - ax)
                if (abs(cross) < 1e-10 && x in minOf(ax, bx)..maxOf(ax, bx) &&
                    y in minOf(ay, by)..maxOf(ay, by)) return true
                if ((ay > y) != (by > y) && x < (bx - ax) * (y - ay) / (by - ay) + ax) inside = !inside
                previous = current
            }
            return inside
        }
    }

    private data class Polygon(val rings: List<Ring>) {
        fun contains(x: Double, y: Double) = rings.first().contains(x, y) &&
            rings.drop(1).none { it.contains(x, y) }
    }

    private data class Area(val name: String, val polygons: List<Polygon>)

    fun cityAt(latitude: Double, longitude: Double): String? {
        if (!latitude.isFinite() || !longitude.isFinite() || latitude !in -90.0..90.0 ||
            longitude !in -180.0..180.0) return null
        return areas.firstOrNull { area -> area.polygons.any { it.contains(longitude, latitude) } }?.name
    }

    companion object {
        fun parse(text: String): OfflineSchoolCityIndex {
            val entries = JSONObject(text).getJSONArray("areas")
            val areas = (0 until entries.length()).map { index ->
                val entry = entries.getJSONObject(index)
                val polygons = entry.getJSONArray("polygons")
                Area(entry.getString("name"), (0 until polygons.length()).map { polygon ->
                    val rings = polygons.getJSONArray(polygon)
                    require(rings.length() > 0) { "Missing outer ring" }
                    Polygon((0 until rings.length()).map { ring -> parseRing(rings.getJSONArray(ring)) })
                })
            }
            return OfflineSchoolCityIndex(areas)
        }

        private fun parseRing(points: JSONArray): Ring {
            require(points.length() >= 3) { "Invalid city boundary" }
            return Ring(DoubleArray(points.length() * 2) { coordinate ->
                points.getJSONArray(coordinate / 2).getDouble(coordinate % 2).also { require(it.isFinite()) }
            })
        }
    }
}
