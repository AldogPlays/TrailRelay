package com.trailrelay.app.trails

import org.maplibre.geojson.Feature
import java.text.NumberFormat

/** Original USGS properties stay on the feature, including nulls and unknown values. */
class Trail(val feature: Feature) {
    val id: String get() = feature.getProperty("objectid").asString
    val name: String get() = text("name") ?: text("namealternate") ?: "Unnamed trail"

    private fun text(field: String): String? = feature.getProperty(field)
        ?.takeUnless { it.isJsonNull }?.asString?.trim()?.takeIf { it.isNotEmpty() }

    private fun access(field: String): String = when (text(field)?.lowercase()) {
        "y", "yes" -> "Yes"
        "n", "no" -> "No"
        else -> "Unknown"
    }

    fun details(): String = buildList {
        text("trailnumber")?.let { add("Trail number: $it") }
        text("lengthmiles")?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }?.let {
            val number = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 2 }
            add("Segment length: ${if (it > 0 && it < 0.01) "<0.01" else number.format(it)} mi")
        }
        text("trailsurface")?.let {
            val surface = when (it) {
                "Unpaved Native" -> "Unpaved – native"
                "Unpaved Improved" -> "Unpaved – improved"
                else -> it
            }
            add("Surface: $surface")
        }
        add("Motorcycle: ${access("motorcycle")}")
        add("OHV ≤50\": ${access("ohvisorunder50inches")}")
        add("4WD / OHV >50\": ${access("ohvover50inches")}")
        text("primarytrailmaintainer")?.let { add("Maintainer: ${if (it == "FS") "USFS" else it}") }
        text("nationaltraildesignation")?.let { add("Designation: $it") }
    }.joinToString("\n")
}
