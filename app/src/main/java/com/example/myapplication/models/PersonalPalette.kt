package com.example.myapplication.models

import com.example.myapplication.SelfieAnalysisResult

/**
 * User's saved seasonal palette (Firestore user document `personalPalette`). Power and neutral
 * entries are inclusive **RGB ranges** (`rgb_range` with `rgb_min` / `rgb_max`), aligned with the backend.
 */
data class PersonalPalette(
    val seasonalPalette: String,
    val paletteDescription: String,
    val skinTone: String,
    val eyeColor: String,
    val hairColor: String,
    val powerSwatches: List<PaletteSwatch>,
    val neutralSwatches: List<PaletteSwatch>,
    val skinRgb: IntArray?,
    val eyeRgb: IntArray?,
    val hairRgb: IntArray?
) {
    companion object {
        fun fromAnalysis(
            result: SelfieAnalysisResult,
            skinTone: String,
            eyeColor: String,
            hairColor: String
        ): PersonalPalette {
            return PersonalPalette(
                seasonalPalette = result.seasonalPalette.orEmpty(),
                paletteDescription = result.paletteDescription.orEmpty(),
                skinTone = skinTone,
                eyeColor = eyeColor,
                hairColor = hairColor,
                powerSwatches = result.powerSwatches,
                neutralSwatches = result.neutralSwatches,
                skinRgb = result.skinRgb,
                eyeRgb = result.eyeRgb,
                hairRgb = result.hairRgb
            )
        }

        @Suppress("UNCHECKED_CAST")
        fun fromFirestore(map: Map<String, Any>?): PersonalPalette? {
            if (map == null) return null
            val seasonal = map["seasonalPalette"] as? String ?: return null
            val desc = map["paletteDescription"] as? String ?: ""
            val skinT = map["skinTone"] as? String ?: ""
            val eye = map["eyeColor"] as? String ?: ""
            val hair = map["hairColor"] as? String ?: ""
            val power = parseSwatchListFromFirestore(map["powerColors"] as? List<*>)
            val neutral = parseSwatchListFromFirestore(map["neutralColors"] as? List<*>)
            val skinRgb = rgbFromList(map["skinRgb"] as? List<*>)
            val eyeRgb = rgbFromList(map["eyeRgb"] as? List<*>)
            val hairRgb = rgbFromList(map["hairRgb"] as? List<*>)
            return PersonalPalette(
                seasonalPalette = seasonal,
                paletteDescription = desc,
                skinTone = skinT,
                eyeColor = eye,
                hairColor = hair,
                powerSwatches = power,
                neutralSwatches = neutral,
                skinRgb = skinRgb,
                eyeRgb = eyeRgb,
                hairRgb = hairRgb
            )
        }

        fun toFirestoreMap(p: PersonalPalette): HashMap<String, Any?> {
            val m = HashMap<String, Any?>()
            m["seasonalPalette"] = p.seasonalPalette
            m["paletteDescription"] = p.paletteDescription
            m["skinTone"] = p.skinTone
            m["eyeColor"] = p.eyeColor
            m["hairColor"] = p.hairColor
            m["powerColors"] = p.powerSwatches.map { swatchMap(it) }
            m["neutralColors"] = p.neutralSwatches.map { swatchMap(it) }
            p.skinRgb?.let { m["skinRgb"] = it.toList() }
            p.eyeRgb?.let { m["eyeRgb"] = it.toList() }
            p.hairRgb?.let { m["hairRgb"] = it.toList() }
            m["updatedAt"] = System.currentTimeMillis()
            return m
        }

        /** Matches backend / API: one entry is `{ "rgb_range": { "rgb_min": [...], "rgb_max": [...] } }`. */
        private fun swatchMap(s: PaletteSwatch): Map<String, Any> = mapOf(
            "rgb_range" to mapOf(
                "rgb_min" to s.rgbMin.toList(),
                "rgb_max" to s.rgbMax.toList()
            )
        )

        private fun parseSwatchListFromFirestore(list: List<*>?): List<PaletteSwatch> {
            if (list == null) return emptyList()
            val out = ArrayList<PaletteSwatch>(list.size)
            for (item in list) {
                val row = item as? Map<*, *> ?: continue
                swatchFromFirestoreRow(row)?.let { out.add(it) }
            }
            return out
        }

        /**
         * Reads nested `rgb_range` (backend + new app saves), or legacy flat `rgbMin` / `rgbMax`
         * from older app versions.
         */
        private fun swatchFromFirestoreRow(row: Map<*, *>): PaletteSwatch? {
            val nested = row["rgb_range"] as? Map<*, *>
            val minList: List<*>?
            val maxList: List<*>?
            if (nested != null) {
                minList = nested["rgb_min"] as? List<*> ?: nested["rgbMin"] as? List<*>
                maxList = nested["rgb_max"] as? List<*> ?: nested["rgbMax"] as? List<*>
            } else {
                minList = row["rgbMin"] as? List<*> ?: row["rgb_min"] as? List<*>
                maxList = row["rgbMax"] as? List<*> ?: row["rgb_max"] as? List<*>
            }
            val min = rgbFromList(minList) ?: return null
            val max = rgbFromList(maxList) ?: return null
            if (min.size < 3 || max.size < 3) return null
            return PaletteSwatch(min, max)
        }

        private fun rgbFromList(list: List<*>?): IntArray? {
            if (list == null || list.size < 3) return null
            return intArrayOf(
                (list[0] as Number).toInt(),
                (list[1] as Number).toInt(),
                (list[2] as Number).toInt()
            )
        }
    }
}
