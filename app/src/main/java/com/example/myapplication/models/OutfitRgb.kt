package com.example.myapplication.models

import android.graphics.Color
import com.example.myapplication.ui.OutfitColorPicker

/** Helpers for outfit garment colors stored as [r,g,b] in Firestore (lists of longs). */
object OutfitRgb {

    fun rgbFromHex(hex: String?): IntArray? {
        val c = OutfitColorPicker.parseHex(hex) ?: return null
        return intArrayOf(Color.red(c), Color.green(c), Color.blue(c))
    }

    fun rgbArrayToColorInt(rgb: IntArray?): Int? {
        if (rgb == null || rgb.size < 3) return null
        return Color.rgb(
            rgb[0].coerceIn(0, 255),
            rgb[1].coerceIn(0, 255),
            rgb[2].coerceIn(0, 255)
        )
    }

    fun toLongList(rgb: IntArray): List<Long> =
        listOf(rgb[0].toLong(), rgb[1].toLong(), rgb[2].toLong())

    fun IntArray?.toRgbListOrNull(): List<Long>? =
        this?.takeIf { it.size >= 3 }?.let { toLongList(it) }

    fun List<Long>?.toRgbTriple(): Triple<Int, Int, Int>? {
        if (this == null || size < 3) return null
        return Triple(
            this[0].toInt().coerceIn(0, 255),
            this[1].toInt().coerceIn(0, 255),
            this[2].toInt().coerceIn(0, 255)
        )
    }

    /** Non-extension entry for call sites that do not import the list extension. */
    fun rgbListToTriple(rgb: List<Long>?): Triple<Int, Int, Int>? = rgb.toRgbTriple()

    /** Prefer Firestore RGB list; otherwise parse legacy hex for navigation / display. */
    fun garmentRgbIntArray(rgb: List<Long>?, hexFallback: String?): IntArray? =
        rgb.toIntArrayOrNull() ?: rgbFromHex(hexFallback?.takeIf { it.isNotBlank() })

    fun List<Long>?.toIntArrayOrNull(): IntArray? {
        val t = toRgbTriple() ?: return null
        return intArrayOf(t.first, t.second, t.third)
    }
}
