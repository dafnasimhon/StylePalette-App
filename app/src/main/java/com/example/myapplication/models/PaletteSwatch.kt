package com.example.myapplication.models

/**
 * Inclusive sRGB bounds (0–255) for one recommended palette color, same shape as the backend
 * `rgb_range` object (`rgb_min` / `rgb_max` each length 3).
 */
data class PaletteSwatch(
    val rgbMin: IntArray,
    val rgbMax: IntArray
)
