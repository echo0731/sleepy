package com.lingion.sleepy.data.jw

import org.json.JSONObject

/** City metadata is separate from the import directory, so it cannot alter URLs or protocols. */
object SchoolCityIndex {
    fun parse(text: String): Map<String, List<String>> {
        val schools = JSONObject(text).getJSONObject("schools")
        return schools.keys().asSequence().associateWith { name ->
            val cities = schools.getJSONArray(name)
            (0 until cities.length()).map { cities.getString(it) }.filter { it.isNotBlank() }
        }
    }

    fun normalizeCity(city: String): String = city.trim().removeSuffix("市")

    /** Municipality geocoders sometimes return a district as locality; prefer their adminArea. */
    fun resolveCity(locality: String?, subAdminArea: String?, adminArea: String?): String? {
        val municipalities = setOf("北京", "上海", "天津", "重庆", "香港", "澳门")
        val admin = adminArea?.trim().orEmpty().removeSuffix("特别行政区")
        if (normalizeCity(admin) in municipalities) return admin
        return subAdminArea?.takeIf { it.isNotBlank() }
            ?: locality?.takeIf { it.isNotBlank() }
    }

    fun schoolsInCity(schools: List<JwSchoolInfo>, city: String): List<JwSchoolInfo> {
        val normalized = normalizeCity(city)
        if (normalized.isBlank()) return emptyList()
        return schools.filter { school -> school.cities.any { normalizeCity(it) == normalized } }
            .distinctBy { it.name }
            .sortedWith(compareBy<JwSchoolInfo> { !it.isSupported }
                .thenBy { it.sortKeyFull.ifBlank { it.name } })
    }
}
