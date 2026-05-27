package com.example.myapplication

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.myapplication.models.PersonalPalette
import com.example.myapplication.repository.AuthRepository
import com.example.myapplication.repository.OutfitRepository
import com.google.android.material.card.MaterialCardView
import com.google.firebase.firestore.FirebaseFirestore
import java.io.File

class RegisterActivity : AppCompatActivity() {

    private lateinit var authRepository: AuthRepository

    private var profileImageUri: Uri? = null
    private var profileImageCacheFile: File? = null
    private var selfiePaletteUri: Uri? = null

    private var pendingFullName: String? = null
    private var pendingEmail: String? = null
    private var pendingPassword: String? = null
    private var pendingSelfieAnalysis: SelfieAnalysisResult? = null
    private var pendingRegistrationPalette: PersonalPalette? = null
    private var registrationInFlight = false

    private lateinit var ivProfile: ImageView
    private lateinit var ivSelfiePalette: ImageView
    private lateinit var progressBar: ProgressBar
    private lateinit var cardTraitReview: MaterialCardView
    private lateinit var cardPaletteResult: MaterialCardView
    private lateinit var spinnerSkin: Spinner
    private lateinit var spinnerEye: Spinner
    private lateinit var spinnerHair: Spinner
    private lateinit var btnConfirmTraits: Button
    private lateinit var llPower: LinearLayout
    private lateinit var llNeutral: LinearLayout

    private val pickProfileLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            profileImageUri = it
            ivProfile.setImageURI(it)
            ivProfile.scaleType = ImageView.ScaleType.CENTER_CROP
            if (!cacheProfileImageFromUri(it)) {
                Toast.makeText(this, getString(R.string.msg_profile_photo_read_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val pickSelfieLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            selfiePaletteUri = it
            ivSelfiePalette.setImageURI(it)
            ivSelfiePalette.scaleType = ImageView.ScaleType.CENTER_CROP
            cardTraitReview.visibility = View.GONE
            cardPaletteResult.visibility = View.GONE
            pendingSelfieAnalysis = null
            pendingFullName = null
            pendingEmail = null
            pendingPassword = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register)

        authRepository = AuthRepository()

        ivProfile = findViewById(R.id.register_IV_profile)
        ivSelfiePalette = findViewById(R.id.register_IV_selfie_palette)
        progressBar = findViewById(R.id.register_PB_loading)
        cardTraitReview = findViewById(R.id.card_trait_review)
        cardPaletteResult = findViewById(R.id.card_palette_result)
        spinnerSkin = findViewById(R.id.spinner_skin_tone)
        spinnerEye = findViewById(R.id.spinner_eye_color)
        spinnerHair = findViewById(R.id.spinner_hair_color)
        btnConfirmTraits = findViewById(R.id.btn_confirm_traits)
        llPower = findViewById(R.id.ll_palette_power)
        llNeutral = findViewById(R.id.ll_palette_neutral)

        bindSpinner(spinnerSkin, R.array.trait_skin_options)
        bindSpinner(spinnerEye, R.array.trait_eye_options)
        bindSpinner(spinnerHair, R.array.trait_hair_options)

        val etFullName = findViewById<EditText>(R.id.et_username)
        val etEmail = findViewById<EditText>(R.id.et_email)
        val etPassword = findViewById<EditText>(R.id.et_password)
        val etRePassword = findViewById<EditText>(R.id.et_retype_password)
        val btnRegister = findViewById<Button>(R.id.btn_register)
        val tvGoToLogin = findViewById<TextView>(R.id.tv_go_to_login)

        ivProfile.setOnClickListener {
            pickProfileLauncher.launch("image/*")
        }
        ivSelfiePalette.setOnClickListener {
            pickSelfieLauncher.launch("image/*")
        }

        btnRegister.setOnClickListener {
            val fullName = etFullName.text.toString().trim()
            val email = etEmail.text.toString().trim()
            val password = etPassword.text.toString().trim()
            val rePassword = etRePassword.text.toString().trim()

            if (fullName.isEmpty() || email.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Please fill all fields", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (selfiePaletteUri == null) {
                Toast.makeText(this, "Please add a selfie for color analysis", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (password != rePassword) {
                Toast.makeText(this, "Passwords do not match", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            setLoading(true)
            cardTraitReview.visibility = View.GONE
            cardPaletteResult.visibility = View.GONE

            val baseUrl = getString(R.string.backend_analysis_base_url)
            BackendApi.analyzeSelfieFromUri(this, baseUrl, selfiePaletteUri!!) { result ->
                if (!result.ok) {
                    setLoading(false)
                    Toast.makeText(
                        this,
                        result.errorMessage ?: "Color analysis failed",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    pendingFullName = fullName
                    pendingEmail = email
                    pendingPassword = password
                    pendingSelfieAnalysis = result

                    applySpinnerFromServer(spinnerSkin, R.array.trait_skin_options, result.serverSkinToneLabel)
                    applySpinnerFromServer(spinnerEye, R.array.trait_eye_options, result.serverEyeColorLabel)
                    applySpinnerFromServer(spinnerHair, R.array.trait_hair_options, result.serverHairColorLabel)

                    cardTraitReview.visibility = View.VISIBLE
                    setLoading(false)
                    findViewById<ScrollView>(R.id.register_scroll).post {
                        findViewById<ScrollView>(R.id.register_scroll)
                            .smoothScrollTo(0, cardTraitReview.top)
                    }
                }
            }
        }

        btnConfirmTraits.setOnClickListener {
            if (registrationInFlight) return@setOnClickListener

            val fullName = pendingFullName
            val email = pendingEmail
            val password = pendingPassword
            val first = pendingSelfieAnalysis
            if (fullName == null || email == null || password == null || first == null) {
                Toast.makeText(this, "Please tap Sign Up first to analyze your selfie", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val skin = spinnerSkin.selectedItem as String
            val eye = spinnerEye.selectedItem as String
            val hair = spinnerHair.selectedItem as String

            registrationInFlight = true
            setLoading(true)
            val baseUrl = getString(R.string.backend_analysis_base_url)
            BackendApi.postPaletteFromTraits(
                baseUrl,
                skinTone = skin,
                eyeColor = eye,
                hairColor = hair,
                skinRgb = first.skinRgb,
                eyeRgb = first.eyeRgb,
                hairRgb = first.hairRgb
            ) { paletteResult ->
                if (!paletteResult.ok) {
                    registrationInFlight = false
                    setLoading(false)
                    Toast.makeText(
                        this,
                        paletteResult.errorMessage ?: "Could not build palette",
                        Toast.LENGTH_LONG
                    ).show()
                    return@postPaletteFromTraits
                }

                showPaletteUi(paletteResult)
                cardTraitReview.visibility = View.GONE
                cardPaletteResult.visibility = View.VISIBLE
                findViewById<ScrollView>(R.id.register_scroll).post {
                    findViewById<ScrollView>(R.id.register_scroll)
                        .smoothScrollTo(0, cardPaletteResult.top)
                }
                setLoading(false)

                Toast.makeText(this, getString(R.string.msg_creating_account), Toast.LENGTH_SHORT).show()

                // 1. Create Firebase Auth account.
                authRepository.register(email, password, fullName) { success, error ->
                    if (!success) {
                        registrationInFlight = false
                        setLoading(false)
                        Toast.makeText(this, "Error: $error", Toast.LENGTH_LONG).show()
                        return@register
                    }

                    // 2. Build PersonalPalette from selfie analysis plus trait selections.
                    val personal = PersonalPalette.fromAnalysis(
                        paletteResult,
                        skinTone = skin,
                        eyeColor = eye,
                        hairColor = hair
                    )
                    pendingRegistrationPalette = personal

                    // 3. Persist user profile and personalPalette map on the user document.
                    val userId = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
                    if (userId != null) {
                        val userMap = hashMapOf<String, Any>(
                            "uid" to userId,
                            "fullName" to fullName,
                            "email" to email,
                            "personalPalette" to PersonalPalette.toFirestoreMap(personal)
                        )

                        FirebaseFirestore.getInstance().collection("users").document(userId)
                            .set(userMap)
                            .addOnCompleteListener { task ->
                                if (!task.isSuccessful) {
                                    Toast.makeText(this, "Account created; palette not saved", Toast.LENGTH_LONG).show()
                                }
                                uploadProfilePhotoAndFinish()
                            }
                    } else {
                        uploadProfilePhotoAndFinish()
                    }
                }
            }
        }

        tvGoToLogin.setOnClickListener {
            finish()
        }
    }

    private fun bindSpinner(spinner: Spinner, arrayRes: Int) {
        ArrayAdapter.createFromResource(this, arrayRes, android.R.layout.simple_spinner_item).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinner.adapter = adapter
        }
    }

    private fun applySpinnerFromServer(spinner: Spinner, arrayRes: Int, serverValue: String?) {
        val options = resources.getStringArray(arrayRes)
        val idx = serverValue?.let { v -> options.indexOfFirst { it.equals(v, ignoreCase = true) } } ?: -1
        if (idx >= 0) spinner.setSelection(idx)
    }

    private fun showPaletteUi(result: SelfieAnalysisResult) {
        findViewById<TextView>(R.id.tv_season_palette_title).text =
            result.seasonalPalette?.let { "$it palette" } ?: "Your palette"

        findViewById<TextView>(R.id.tv_palette_description).text =
            result.paletteDescription.orEmpty()

        llPower.removeAllViews()
        llNeutral.removeAllViews()

        result.powerSwatches.forEach { addRangeSwatch(llPower, it.rgbMin, it.rgbMax) }
        result.neutralSwatches.forEach { addRangeSwatch(llNeutral, it.rgbMin, it.rgbMax) }
    }

    private fun addRangeSwatch(parent: LinearLayout, rgbMin: IntArray, rgbMax: IntArray) {
        if (rgbMin.size < 3 || rgbMax.size < 3) return
        val d = resources.displayMetrics.density
        val w = (44 * d).toInt()
        val h = (52 * d).toInt()
        val marginEnd = (8 * d).toInt()
        val view = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(w, h).apply { setMargins(0, 0, marginEnd, 0) }
            val gd = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(
                    Color.rgb(rgbMin[0], rgbMin[1], rgbMin[2]),
                    Color.rgb(rgbMax[0], rgbMax[1], rgbMax[2])
                )
            )
            gd.cornerRadius = 8f * d
            background = gd
        }
        parent.addView(view)
    }

    private fun cacheProfileImageFromUri(uri: Uri): Boolean {
        return try {
            val dest = File(cacheDir, "register_profile_photo.jpg")
            contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } ?: return false
            if (dest.length() == 0L) return false
            profileImageCacheFile = dest
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun ensureProfileImageCached(): Boolean {
        profileImageCacheFile?.takeIf { it.exists() && it.length() > 0 }?.let { return true }
        val uri = profileImageUri ?: selfiePaletteUri ?: return false
        return cacheProfileImageFromUri(uri)
    }

    private fun uploadProfilePhotoAndFinish() {
        if (!ensureProfileImageCached() || profileImageUri == null) {
            finishRegistration()
            return
        }

        Toast.makeText(this, getString(R.string.msg_saving_profile_photo), Toast.LENGTH_SHORT).show()
        setLoading(true)

        OutfitRepository.uploadProfileImage(profileImageUri!!) { success, error ->
            if (!success) {
                Toast.makeText(this, "Profile photo upload failed: $error", Toast.LENGTH_LONG).show()
            }
            finishRegistration()
        }
    }

    private fun finishRegistration() {
        registrationInFlight = false
        setLoading(true)

        val handler = Handler(Looper.getMainLooper())
        var navigated = false
        val openMain = Runnable {
            if (navigated || isFinishing || isDestroyed) return@Runnable
            navigated = true
            setLoading(false)
            Toast.makeText(this, "Welcome to StylePalette!", Toast.LENGTH_SHORT).show()
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
            )
            finish()
        }

        handler.postDelayed(openMain, 2000L)
    }

    private fun setLoading(isLoading: Boolean) {
        progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        val busy = isLoading || registrationInFlight
        findViewById<Button>(R.id.btn_register).isEnabled = !busy
        btnConfirmTraits.isEnabled = !busy
    }

    override fun onDestroy() {
        registrationInFlight = false
        progressBar.visibility = View.GONE
        super.onDestroy()
    }
}