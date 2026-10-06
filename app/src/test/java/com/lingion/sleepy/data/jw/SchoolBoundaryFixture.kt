package com.lingion.sleepy.data.jw

import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.math.roundToInt

/** JSON remains readable in fixtures; production parses only the compact stream. */
internal object SchoolBoundaryFixture {
    fun parse(json: String) = OfflineSchoolCityIndex.parse(ByteArrayInputStream(bytes(json)))

    fun bytes(json: String): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { output ->
            output.writeInt(0x53434231)
            val areas = JSONObject(json).getJSONArray("areas")
            output.writeInt(areas.length())
            repeat(areas.length()) { area ->
                val entry = areas.getJSONObject(area)
                val name = entry.getString("name").toByteArray(Charsets.UTF_8)
                output.writeInt(name.size)
                output.write(name)
                val polygons = entry.getJSONArray("polygons")
                output.writeInt(polygons.length())
                repeat(polygons.length()) { polygon ->
                    val rings = polygons.getJSONArray(polygon)
                    output.writeInt(rings.length())
                    repeat(rings.length()) { ring ->
                        val points = rings.getJSONArray(ring)
                        output.writeInt(points.length())
                        repeat(points.length()) { point ->
                            val coordinates = points.getJSONArray(point)
                            output.writeInt((coordinates.getDouble(0) * 10000).roundToInt())
                            output.writeInt((coordinates.getDouble(1) * 10000).roundToInt())
                        }
                    }
                }
            }
        }
        return buffer.toByteArray()
    }
}
