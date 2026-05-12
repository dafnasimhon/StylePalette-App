package com.example.myapplication

import com.example.myapplication.models.Outfit
import com.example.myapplication.models.OutfitRgb
import com.example.myapplication.models.PaletteSwatch
import com.example.myapplication.models.PersonalPalette

/**
 * True when at least one garment **Firestore RGB triple** lies inside any personal-palette
 * **RGB range** ([PaletteSwatch] inclusive min/max per channel). Hex fields are ignored.
 */
object FeedPaletteMatcher {

    fun outfitMatchesPersonalPalette(outfit: Outfit, palette: PersonalPalette): Boolean {
        val swatches = palette.powerSwatches + palette.neutralSwatches
        if (swatches.isEmpty()) return false
        val rgbs = outfitGarmentRgbColors(outfit)
        if (rgbs.isEmpty()) return false
        return rgbs.any { (r, g, b) -> swatches.any { rgbInSwatch(r, g, b, it) } }
    }

    private fun outfitGarmentRgbColors(outfit: Outfit): List<Triple<Int, Int, Int>> {
        val lists = listOf(
            outfit.topRgb,
            outfit.bottomRgb,
            outfit.jacketRgb,
            outfit.shoesRgb,
            outfit.jewelryRgb,
            outfit.sunglassesRgb,
            outfit.bagRgb
        )
        val out = ArrayList<Triple<Int, Int, Int>>()
        for (rgbList in lists) {
            OutfitRgb.rgbListToTriple(rgbList)?.let { out.add(it) }
        }
        return out
    }

    private fun rgbInSwatch(r: Int, g: Int, b: Int, s: PaletteSwatch): Boolean {
        if (s.rgbMin.size < 3 || s.rgbMax.size < 3) return false
        fun inChannel(v: Int, lo: Int, hi: Int): Boolean {
            val a = minOf(lo, hi)
            val z = maxOf(lo, hi)
            return v in a..z
        }
        return inChannel(r, s.rgbMin[0], s.rgbMax[0]) &&
            inChannel(g, s.rgbMin[1], s.rgbMax[1]) &&
            inChannel(b, s.rgbMin[2], s.rgbMax[2])
    }
}
