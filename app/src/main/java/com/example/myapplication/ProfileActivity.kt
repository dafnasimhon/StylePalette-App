package com.example.myapplication

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.signature.ObjectKey
import com.example.myapplication.adapters.OutfitAdapter
import com.example.myapplication.models.AppConfig
import com.example.myapplication.models.PersonalPalette
import com.example.myapplication.repository.OutfitRepository
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore

/** Profile activity managed via the new unified OutfitRepository object structure. */
class ProfileActivity : BaseActivity() {

    private lateinit var adapter: OutfitAdapter
    private val repository = OutfitRepository

    private lateinit var cardPalette: MaterialCardView
    private lateinit var llPower: LinearLayout
    private lateinit var llNeutral: LinearLayout
    private lateinit var tvName: TextView
    private lateinit var ivProfile: com.google.android.material.imageview.ShapeableImageView
    private lateinit var tvEmptyOutfits: TextView
    private lateinit var rvOutfits: androidx.recyclerview.widget.RecyclerView

    private var loggingOut = false
    private var lastShownImageUrl: String? = null

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { uploadImage(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)

        setupBottomNavigation(R.id.btn_profile)

        cardPalette = findViewById(R.id.profile_card_palette)
        llPower = findViewById(R.id.profile_LL_palette_power)
        llNeutral = findViewById(R.id.profile_LL_palette_neutral)
        tvName = findViewById(R.id.profile_TV_username)
        ivProfile = findViewById(R.id.profile_IV_user)
        tvEmptyOutfits = findViewById(R.id.profile_TV_empty_outfits)
        findViewById<View>(R.id.profile_PB_loading).visibility = View.GONE

        setupRecyclerView()
        showOutfitsEmptyState(true)

        ivProfile.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        findViewById<MaterialButton>(R.id.profile_BTN_logout).setOnClickListener {
            showLogoutDialog()
        }

        fetchAndApplyUserProfile()
    }

    override fun onStart() {
        super.onStart()
        if (auth.currentUser == null) return
        fetchAndApplyUserProfile()
        loadMyOutfits()
    }

    override fun onResume() {
        super.onResume()
        if (auth.currentUser == null || loggingOut) return
        if (::adapter.isInitialized) {
            loadMyOutfits()
        }
    }

    override fun onStop() {
        if (!loggingOut) {
            repository.clearListeners()
        }
        super.onStop()
    }

    override fun onDestroy() {
        repository.clearListeners()
        super.onDestroy()
    }

    override fun onBeforeLogout() {
        loggingOut = true
        repository.clearListeners()
    }

    /** Loads display name, profile image URL, and personal palette from Firestore. */
    private fun fetchAndApplyUserProfile() {
        val userId = auth.currentUser?.uid ?: return

        FirebaseFirestore.getInstance().collection(AppConfig.COLL_USERS).document(userId)
            .get()
            .addOnSuccessListener { document ->
                if (loggingOut || isFinishing || isDestroyed) return@addOnSuccessListener
                if (document != null && document.exists()) {
                    val fullName = document.getString("fullName")
                    val profileImageUrl = document.getString("profileImageUrl")

                    val paletteMap = document.get("personalPalette") as? Map<String, Any>

                    // Map Firestore fields into PersonalPalette (power vs neutral swatches).
                    val palette = PersonalPalette.fromFirestore(paletteMap)

                    auth.currentUser?.let { user ->
                        applyUserProfile(user, fullName, profileImageUrl, palette)
                    }
                } else {
                    auth.currentUser?.let { applyBasicProfileInfo(it) }
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to fetch user profile from Firestore: ${e.message}")
                auth.currentUser?.let { applyBasicProfileInfo(it) }
            }
    }

    private fun applyBasicProfileInfo(user: FirebaseUser) {
        tvName.text = user.displayName?.takeIf { it.isNotBlank() }
            ?: user.email?.substringBefore("@")
                    ?: "StylePalette USER"
        ivProfile.setImageResource(R.drawable.ic_person)
        cardPalette.visibility = View.GONE
    }

    private fun applyUserProfile(
        user: FirebaseUser,
        fullName: String?,
        profileImageUrl: String?,
        palette: PersonalPalette?
    ) {
        val mergedName = fullName?.trim().orEmpty()
        tvName.text = if (mergedName.isNotEmpty()) mergedName else {
            user.displayName?.takeIf { it.isNotBlank() } ?: user.email?.substringBefore("@") ?: "StylePalette USER"
        }

        if (!profileImageUrl.isNullOrEmpty()) {
            if (profileImageUrl != lastShownImageUrl) {
                Glide.with(this).clear(ivProfile)
                lastShownImageUrl = profileImageUrl
            }
            Glide.with(this)
                .load(profileImageUrl)
                .signature(ObjectKey(profileImageUrl))
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .circleCrop()
                .placeholder(R.drawable.ic_person)
                .error(R.drawable.ic_person)
                .into(ivProfile)
        } else {
            lastShownImageUrl = null
            Glide.with(this).clear(ivProfile)
            ivProfile.setImageResource(R.drawable.ic_person)
        }

        bindPaletteIfPresent(palette)
    }

    private fun bindPaletteIfPresent(palette: PersonalPalette?) {
        if (palette == null) {
            cardPalette.visibility = View.GONE
            return
        }

        val hasContent = palette.seasonalPalette.isNotBlank() ||
                palette.skinTone.isNotBlank() ||
                palette.eyeColor.isNotBlank() ||
                palette.hairColor.isNotBlank() ||
                palette.paletteDescription.isNotBlank() ||
                palette.powerSwatches.isNotEmpty() ||
                palette.neutralSwatches.isNotEmpty()

        if (!hasContent) {
            cardPalette.visibility = View.GONE
            return
        }

        findViewById<TextView>(R.id.profile_TV_palette_title).text =
            if (palette.seasonalPalette.isNotBlank()) "${palette.seasonalPalette} palette" else "Your palette"

        findViewById<TextView>(R.id.profile_TV_palette_traits).text =
            "Skin: ${palette.skinTone} · Eyes: ${palette.eyeColor} · Hair: ${palette.hairColor}"

        findViewById<TextView>(R.id.profile_TV_palette_description).text =
            palette.paletteDescription

        llPower.removeAllViews()
        llNeutral.removeAllViews()
        palette.powerSwatches.forEach { addRangeSwatch(llPower, it.rgbMin, it.rgbMax) }
        palette.neutralSwatches.forEach { addRangeSwatch(llNeutral, it.rgbMin, it.rgbMax) }

        cardPalette.visibility = View.VISIBLE
    }

    private fun addRangeSwatch(parent: LinearLayout, rgbMin: IntArray, rgbMax: IntArray) {
        if (rgbMin.size < 3 || rgbMax.size < 3) return
        val d = resources.displayMetrics.density
        val w = (40 * d).toInt()
        val h = (48 * d).toInt()
        val marginEnd = (6 * d).toInt()
        val view = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(w, h).apply { setMargins(0, 0, marginEnd, 0) }
            val gd = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(
                    Color.rgb(rgbMin[0], rgbMin[1], rgbMin[2]),
                    Color.rgb(rgbMax[0], rgbMax[1], rgbMax[2])
                )
            )
            gd.cornerRadius = 6f * d
            background = gd
        }
        parent.addView(view)
    }

    private fun showLogoutDialog() {
        AlertDialog.Builder(this)
            .setTitle("Logout")
            .setMessage("Are you sure you want to log out?")
            .setPositiveButton("Logout") { _, _ -> performLogout() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun uploadImage(uri: Uri) {
        Glide.with(this)
            .load(uri)
            .circleCrop()
            .placeholder(R.drawable.ic_person)
            .into(ivProfile)

        showToast("Updating profile image...")

        repository.uploadProfileImage(uri) { success, error ->
            if (success) {
                showToast("Profile updated successfully!")
                fetchAndApplyUserProfile()
            } else {
                Log.e(TAG, "Upload failed: $error")
                showToast("Failed to upload: $error")
            }
        }
    }

    private fun setupRecyclerView() {
        adapter = OutfitAdapter(
            outfits = emptyList(),
            showLikeButton = false,
            repository = repository
        ) { outfit ->
            navigateToDetail(outfit, true)
        }
        rvOutfits = setupRecyclerView(R.id.profile_RV_my_outfits, 3, adapter)
    }

    private fun loadMyOutfits() {
        if (loggingOut) return
        val uid = auth.currentUser?.uid ?: return

        repository.getMyOutfits { list, error ->
            if (loggingOut || isFinishing || isDestroyed) return@getMyOutfits
            if (list != null) {
                adapter.updateData(list)
                showOutfitsEmptyState(list.isEmpty())
            } else {
                Log.e(TAG, "Error loading outfits: $error")
                adapter.updateData(emptyList())
                showOutfitsEmptyState(true)
                showFirestoreError(error)
            }
        }
    }

    private fun showOutfitsEmptyState(show: Boolean) {
        tvEmptyOutfits.visibility = if (show) View.VISIBLE else View.GONE
        if (::rvOutfits.isInitialized) {
            rvOutfits.visibility = if (show) View.GONE else View.VISIBLE
        }
    }

    private companion object {
        const val TAG = "StylePalette_Profile"
    }
}