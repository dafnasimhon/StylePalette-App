package com.example.myapplication.ui

import android.graphics.Color
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.github.dhaval2404.colorpicker.ColorPickerDialog
import com.github.dhaval2404.colorpicker.MaterialColorPickerDialog
import com.github.dhaval2404.colorpicker.model.ColorShape
import java.util.ArrayList
import java.util.LinkedHashSet
import java.util.Locale

/**
 * Large swatch grid + optional color wheel, backed by [Dhaval2404 ColorPicker](https://github.com/Dhaval2404/ColorPicker).
 */
object OutfitColorPicker {

    fun toHex(color: Int): String =
        String.format(Locale.US, "#%06X", 0xFFFFFF and color)

    fun parseHex(hex: String?): Int? {
        if (hex.isNullOrBlank()) return null
        return try {
            var h = hex.trim()
            if (!h.startsWith("#")) h = "#$h"
            Color.parseColor(h)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /**
     * Opens a color chooser: common swatches, full grid, color wheel, or no color.
     * @param onPicked hex `#RRGGBB`, or `null` if the user chose “No color”.
     */
    fun show(
        activity: AppCompatActivity,
        title: String,
        initialHex: String?,
        onPicked: (String?) -> Unit
    ) {
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setItems(
                arrayOf(
                    activity.getString(com.example.myapplication.R.string.color_picker_mode_common),
                    activity.getString(com.example.myapplication.R.string.color_picker_mode_palette),
                    activity.getString(com.example.myapplication.R.string.color_picker_automatic)
                )
            ) { _, which ->
                when (which) {
                    0 -> showMaterialSwatches(activity, title, initialHex, buildCommonPalette(), onPicked)
                    1 -> showSwatchGrid(activity, title, initialHex, onPicked)
                    2 -> onPicked(null)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Black, white, grays, and popular fashion / UI colors in one compact grid.
     */
    private fun buildCommonPalette(): ArrayList<String> {
        return arrayListOf(
            // White → black
            "#FFFFFF", "#FAFAFA", "#F5F5F5", "#EEEEEE", "#E0E0E0", "#BDBDBD", "#9E9E9E",
            "#757575", "#616161", "#424242", "#212121", "#000000",
            // Reds & pinks
            "#FFEBEE", "#FFCDD2", "#EF5350", "#E53935", "#C62828", "#B71C1C",
            "#FCE4EC", "#F48FB1", "#EC407A", "#D81B60", "#AD1457",
            // Orange, yellow, gold
            "#FFF3E0", "#FFE0B2", "#FFB74D", "#FB8C00", "#EF6C00", "#E65100",
            "#FFFDE7", "#FFF9C4", "#FFEE58", "#FDD835", "#F9A825", "#FBC02D",
            // Greens & teal
            "#E8F5E9", "#C8E6C9", "#66BB6A", "#43A047", "#2E7D32", "#1B5E20",
            "#E0F2F1", "#B2DFDB", "#26A69A", "#00897B", "#00695C",
            // Blues & navy
            "#E3F2FD", "#BBDEFB", "#42A5F5", "#1E88E5", "#1565C0", "#0D47A1",
            "#E8EAF6", "#9FA8DA", "#5C6BC0", "#3949AB", "#1A237E",
            // Purple
            "#F3E5F5", "#E1BEE7", "#AB47BC", "#8E24AA", "#6A1B9A",
            // Brown, beige, khaki
            "#EFEBE9", "#D7CCC8", "#A1887F", "#8D6E63", "#795548", "#5D4037", "#3E2723",
            "#FFF8E1", "#FFECB3", "#FFE082", "#C5A572",
            // Cool grays (denim-adjacent)
            "#ECEFF1", "#CFD8DC", "#90A4AE", "#607D8B", "#455A64", "#37474F"
        )
    }

    private fun showMaterialSwatches(
        activity: AppCompatActivity,
        title: String,
        initialHex: String?,
        colors: ArrayList<String>,
        onPicked: (String?) -> Unit
    ) {
        val defaultArgb = parseHex(initialHex) ?: Color.parseColor("#757575")
        MaterialColorPickerDialog.Builder(activity)
            .setTitle(title)
            .setColorShape(ColorShape.SQAURE)
            .setColors(colors)
            .setDefaultColor(toHex(defaultArgb))
            .setTickColorPerCard(true)
            .setColorListener { _, colorHex ->
                onPicked(normalizeHex(colorHex))
            }
            .show()
    }

    private fun showSwatchGrid(
        activity: AppCompatActivity,
        title: String,
        initialHex: String?,
        onPicked: (String?) -> Unit
    ) {
        showMaterialSwatches(activity, title, initialHex, buildExtendedPalette(), onPicked)
    }

    private fun showColorWheel(
        activity: AppCompatActivity,
        title: String,
        initialHex: String?,
        onPicked: (String?) -> Unit
    ) {
        val defaultArgb = parseHex(initialHex) ?: Color.parseColor("#757575")
        ColorPickerDialog.Builder(activity)
            .setTitle(title)
            .setDefaultColor(toHex(defaultArgb))
            .setColorShape(ColorShape.SQAURE)
            .setColorListener { _, colorHex ->
                onPicked(normalizeHex(colorHex))
            }
            .show()
    }

    private fun normalizeHex(colorHex: String): String {
        var h = colorHex.trim()
        if (!h.startsWith("#")) h = "#$h"
        return h.uppercase(Locale.US)
    }

    /**
     * Dense discrete palette: HSV sweeps + grayscale ramp (similar idea to “Standard” office pickers).
     */
    private fun buildExtendedPalette(): ArrayList<String> {
        val set = LinkedHashSet<String>()
        val hsv = FloatArray(3)
        for (v in listOf(1f, 0.88f, 0.72f, 0.55f, 0.38f, 0.24f)) {
            for (s in listOf(0.25f, 0.5f, 0.75f, 1f)) {
                var h = 0f
                while (h < 360f) {
                    hsv[0] = h
                    hsv[1] = s
                    hsv[2] = v
                    set.add(toHex(Color.HSVToColor(hsv)))
                    h += 9f
                }
            }
        }
        for (i in 0..24) {
            val g = 255 * i / 24
            set.add(toHex(Color.rgb(g, g, g)))
        }
        return ArrayList(set)
    }
}
