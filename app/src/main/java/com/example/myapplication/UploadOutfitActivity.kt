package com.example.myapplication

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ImageView
import android.widget.ProgressBar
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.example.myapplication.models.OutfitRgb
import com.example.myapplication.repository.OutfitRepository
import com.example.myapplication.ui.OutfitColorPicker
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText

/**
 * Activity that handles the selection, tagging, and uploading of new outfits to Firebase.
 */
class UploadOutfitActivity : BaseActivity() {

    private lateinit var ivPreview: ImageView
    private lateinit var btnUpload: MaterialButton
    private lateinit var progressBar: ProgressBar
    private lateinit var actvVibe: AutoCompleteTextView

    private lateinit var etTop: TextInputEditText
    private lateinit var etBottom: TextInputEditText
    private lateinit var etJacket: TextInputEditText
    private lateinit var etShoes: TextInputEditText
    private lateinit var etJewelry: TextInputEditText
    private lateinit var etSunglasses: TextInputEditText
    private lateinit var etBag: TextInputEditText

    private lateinit var swatchTop: View
    private lateinit var swatchBottom: View
    private lateinit var swatchJacket: View
    private lateinit var swatchShoes: View
    private lateinit var swatchJewelry: View
    private lateinit var swatchSunglasses: View
    private lateinit var swatchBag: View

    private val outfitRepository = OutfitRepository
    private var imageUri: Uri? = null

    /** Per-item picker colors as #RRGGBB hex strings (empty means unset). */
    private var topColorHex: String = ""
    private var bottomColorHex: String = ""
    private var jacketColorHex: String = ""
    private var shoesColorHex: String = ""
    private var jewelryColorHex: String = ""
    private var sunglassesColorHex: String = ""
    private var bagColorHex: String = ""

    private val selectImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            imageUri = it
            ivPreview.setImageURI(it)
            ivPreview.setPadding(0, 0, 0, 0)
            ivPreview.scaleType = ImageView.ScaleType.CENTER_CROP
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_upload_outfit)

        setupBottomNavigation(R.id.btn_add_outfit)

        initViews()
        setupVibeDropdown()
        setupColorSwatches()
        setupSwatchLongPressToClear()

        findViewById<MaterialCardView>(R.id.upload_CV_image_container).setOnClickListener {
            selectImageLauncher.launch("image/*")
        }

        btnUpload.setOnClickListener {
            validateAndUpload()
        }
    }

    private fun initViews() {
        ivPreview = findViewById(R.id.upload_IV_preview)
        btnUpload = findViewById(R.id.upload_BTN_upload)
        progressBar = findViewById(R.id.upload_PB_loading)
        actvVibe = findViewById(R.id.upload_ACTV_vibe)

        etTop = findViewById(R.id.upload_ET_top)
        etBottom = findViewById(R.id.upload_ET_bottom)
        etJacket = findViewById(R.id.upload_ET_jacket)
        etShoes = findViewById(R.id.upload_ET_shoes)
        etJewelry = findViewById(R.id.upload_ET_jewelry)
        etSunglasses = findViewById(R.id.upload_ET_sunglasses)
        etBag = findViewById(R.id.upload_ET_bag)

        swatchTop = findViewById(R.id.upload_swatch_top)
        swatchBottom = findViewById(R.id.upload_swatch_bottom)
        swatchJacket = findViewById(R.id.upload_swatch_jacket)
        swatchShoes = findViewById(R.id.upload_swatch_shoes)
        swatchJewelry = findViewById(R.id.upload_swatch_jewelry)
        swatchSunglasses = findViewById(R.id.upload_swatch_sunglasses)
        swatchBag = findViewById(R.id.upload_swatch_bag)
    }

    private fun setupColorSwatches() {
        wirePicker(swatchTop, R.string.color_picker_title_top, { topColorHex }) { topColorHex = it }
        wirePicker(swatchBottom, R.string.color_picker_title_bottom, { bottomColorHex }) { bottomColorHex = it }
        wirePicker(swatchJacket, R.string.color_picker_title_jacket, { jacketColorHex }) { jacketColorHex = it }
        wirePicker(swatchShoes, R.string.color_picker_title_shoes, { shoesColorHex }) { shoesColorHex = it }
        wirePicker(swatchJewelry, R.string.color_picker_title_jewelry, { jewelryColorHex }) { jewelryColorHex = it }
        wirePicker(swatchSunglasses, R.string.color_picker_title_sunglasses, { sunglassesColorHex }) { sunglassesColorHex = it }
        wirePicker(swatchBag, R.string.color_picker_title_bag, { bagColorHex }) { bagColorHex = it }
    }

    private fun wirePicker(swatch: View, titleRes: Int, getHex: () -> String, setHex: (String) -> Unit) {
        swatch.setOnClickListener {
            OutfitColorPicker.show(this, getString(titleRes), getHex().takeIf { it.isNotBlank() }) { hex ->
                setHex(hex.orEmpty())
                bindSwatch(swatch, hex.orEmpty())
            }
        }
    }

    private fun setupSwatchLongPressToClear() {
        val pairs = listOf(
            swatchTop to { topColorHex = ""; bindSwatch(swatchTop, "") },
            swatchBottom to { bottomColorHex = ""; bindSwatch(swatchBottom, "") },
            swatchJacket to { jacketColorHex = ""; bindSwatch(swatchJacket, "") },
            swatchShoes to { shoesColorHex = ""; bindSwatch(swatchShoes, "") },
            swatchJewelry to { jewelryColorHex = ""; bindSwatch(swatchJewelry, "") },
            swatchSunglasses to { sunglassesColorHex = ""; bindSwatch(swatchSunglasses, "") },
            swatchBag to { bagColorHex = ""; bindSwatch(swatchBag, "") }
        )
        pairs.forEach { (v, clear) ->
            v.setOnLongClickListener {
                clear()
                true
            }
        }
    }

    private fun bindSwatch(view: View, hex: String) {
        if (hex.isBlank()) {
            view.setBackgroundResource(R.drawable.bg_color_swatch_empty)
            return
        }
        val rgb = OutfitColorPicker.parseHex(hex) ?: run {
            view.setBackgroundResource(R.drawable.bg_color_swatch_empty)
            return
        }
        val d = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 8f * resources.displayMetrics.density
            setColor(rgb)
            setStroke((1 * resources.displayMetrics.density).toInt().coerceAtLeast(1), Color.argb(100, 0, 0, 0))
        }
        view.background = d
    }

    private fun setupVibeDropdown() {
        val vibes = resources.getStringArray(R.array.outfit_vibes)
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, vibes)
        actvVibe.setAdapter(adapter)
    }

    private fun hexToRgbList(hex: String): List<Long>? =
        OutfitRgb.rgbFromHex(hex.takeIf { it.isNotBlank() })?.let { OutfitRgb.toLongList(it) }

    private fun validateAndUpload() {
        val vibe = actvVibe.text.toString().trim()
        val top = etTop.text.toString().trim()
        val bottom = etBottom.text.toString().trim()
        val jacket = etJacket.text.toString().trim()
        val shoes = etShoes.text.toString().trim()
        val jewelry = etJewelry.text.toString().trim()
        val sunglasses = etSunglasses.text.toString().trim()
        val bag = etBag.text.toString().trim()

        if (imageUri == null) {
            showToast("Please select an outfit image")
            return
        }

        if (vibe.isEmpty()) {
            showToast(getString(R.string.msg_error_vibe))
            return
        }

        if (top.isEmpty() || bottom.isEmpty()) {
            showToast("Top and Bottom are required")
            return
        }

        setLoading(true)

        outfitRepository.uploadOutfit(
            imageUri = imageUri!!,
            top = top,
            bottom = bottom,
            jacket = jacket,
            shoes = shoes,
            jewelry = jewelry,
            sunglasses = sunglasses,
            bag = bag,
            vibe = vibe,
            topRgb = hexToRgbList(topColorHex),
            bottomRgb = hexToRgbList(bottomColorHex),
            jacketRgb = hexToRgbList(jacketColorHex),
            shoesRgb = hexToRgbList(shoesColorHex),
            jewelryRgb = hexToRgbList(jewelryColorHex),
            sunglassesRgb = hexToRgbList(sunglassesColorHex),
            bagRgb = hexToRgbList(bagColorHex)
        ) { success, error ->
            setLoading(false)
            if (success) {
                showToast(getString(R.string.msg_upload_success))
                finish()
            } else {
                showToast("Upload failed: $error")
            }
        }
    }

    private fun setLoading(isLoading: Boolean) {
        if (isLoading) {
            progressBar.visibility = View.VISIBLE
            btnUpload.isEnabled = false
            btnUpload.alpha = 0.5f
        } else {
            progressBar.visibility = View.GONE
            btnUpload.isEnabled = true
            btnUpload.alpha = 1.0f
        }
    }
}