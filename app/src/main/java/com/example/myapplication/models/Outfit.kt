package com.example.myapplication.models

/**
 * Garment colors are stored in Firestore as **RGB triples** (`topRgb`, …): each is a list of 3 numbers
 * `[r, g, b]` in `0…255`.
 *
 * Legacy documents may still have `*ColorHex` fields; the app reads those for display / palette matching
 * when the corresponding `*Rgb` list is missing.
 */
data class Outfit(
    val id: String = "",
    val userId: String = "",
    val imageUrl: String = "",
    val timestamp: Long = 0,
    val top: String = "",
    val bottom: String = "",
    val jacket: String = "",
    val shoes: String = "",
    val jewelry: String = "",
    val sunglasses: String = "",
    val bag: String = "",
    val vibe: String = "",
    val vibeSearchKey: String = "",
    val topRgb: List<Long>? = null,
    val bottomRgb: List<Long>? = null,
    val jacketRgb: List<Long>? = null,
    val shoesRgb: List<Long>? = null,
    val jewelryRgb: List<Long>? = null,
    val sunglassesRgb: List<Long>? = null,
    val bagRgb: List<Long>? = null,
    val topColorHex: String? = null,
    val bottomColorHex: String? = null,
    val jacketColorHex: String? = null,
    val shoesColorHex: String? = null,
    val jewelryColorHex: String? = null,
    val sunglassesColorHex: String? = null,
    val bagColorHex: String? = null
)
