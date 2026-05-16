package com.example.myapplication

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
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
import com.google.android.material.card.MaterialCardView
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore

/**
 * Loads `users/{uid}` with a direct Firestore [get] (working app pattern) plus a live listener.
 */
class ProfileActivity : BaseActivity() {

    private lateinit var adapter: OutfitAdapter
    private val repository = OutfitRepository()
    private val db = FirebaseFirestore.getInstance()

    private lateinit var cardPalette: MaterialCardView
    private lateinit var llPower: LinearLayout
    private lateinit var llNeutral: LinearLayout
    private lateinit var tvName: TextView
    private lateinit var ivProfile: ImageView
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

        ivProfile.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        findViewById<ImageButton>(R.id.profile_BTN_logout).setOnClickListener {
            showLogoutDialog()
        }
    }

    override fun onStart() {
        super.onStart()
        if (auth.currentUser == null) return
        repository.observeUserProfile(
            onResult = { profile ->
                if (loggingOut || isFinishing || isDestroyed) return@observeUserProfile
                val user = auth.currentUser ?: return@observeUserProfile
                applyUserProfile(user, profile.fullName, profile.profileImageUrl, profile.palette)
            },
            onError = { message ->
                if (loggingOut || isFinishing || isDestroyed) return@observeUserProfile
                Log.e(TAG, "Profile listener error: $message")
                if (shouldShowFirestoreError(message)) {
                    showToast("${getString(R.string.profile_load_failed)}: $message")
                }
            }
        )
        loadUserData()
        loadMyOutfits()
    }

    override fun onResume() {
        super.onResume()
        if (auth.currentUser == null) return
        loadUserData()
        if (::adapter.isInitialized) {
            loadMyOutfits()
        }
    }

    override fun onStop() {
        if (!loggingOut) {
            repository.clearProfileListeners()
        }
        super.onStop()
    }

    override fun onDestroy() {
        repository.clearProfileListeners()
        super.onDestroy()
    }

    private fun showLogoutDialog() {
        AlertDialog.Builder(this)
            .setTitle("Logout")
            .setMessage("Are you sure you want to log out?")
            .setPositiveButton("Logout") { _, _ -> performLogout() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performLogout() {
        loggingOut = true
        repository.clearProfileListeners()
        auth.signOut()
        FirebaseSession.resetAfterSignOut {
            if (isFinishing) return@resetAfterSignOut
            startActivity(
                Intent(this, LoginActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
            )
            finish()
        }
    }

    private fun uploadImage(uri: Uri) {
        showToast("Updating profile image...")
        repository.uploadProfileImage(this, uri) { success, error, _ ->
            if (success) {
                showToast("Profile updated successfully!")
                loadUserData()
            } else {
                Log.e(TAG, "Upload failed: $error")
                showToast("Failed to upload: $error")
            }
        }
    }

    private fun setupRecyclerView() {
        adapter = OutfitAdapter(
            outfits = emptyList(),
            showLikeButton = false
        ) { outfit ->
            navigateToDetail(outfit, true)
        }
        rvOutfits = setupRecyclerView(R.id.profile_RV_my_outfits, 3, adapter)
    }

    /** Direct document read — matches the working app_project ProfileActivity. */
    private fun loadUserData() {
        if (loggingOut) return
        val user = auth.currentUser
        if (user == null) {
            tvName.text = getString(R.string.profile_not_signed_in)
            cardPalette.visibility = View.GONE
            return
        }

        tvName.text = nameFromAuth(user)

        db.collection(AppConfig.COLL_USERS).document(user.uid)
            .get()
            .addOnSuccessListener { doc ->
                if (loggingOut || isFinishing || isDestroyed) return@addOnSuccessListener
                if (!doc.exists()) {
                    Log.w(TAG, "No Firestore user doc for uid=${user.uid}")
                    cardPalette.visibility = View.GONE
                    return@addOnSuccessListener
                }
                val profile = repository.userProfileFromSnapshot(doc)
                Log.d(
                    TAG,
                    "Profile loaded: name=${profile.fullName}, photo=${!profile.profileImageUrl.isNullOrBlank()}, " +
                        "palette=${profile.palette != null}"
                )
                applyUserProfile(user, profile.fullName, profile.profileImageUrl, profile.palette)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Firestore profile read failed", e)
                if (!loggingOut && shouldShowFirestoreError(e.message)) {
                    showToast("${getString(R.string.profile_load_failed)}: ${e.message}")
                }
            }
    }

    private fun applyUserProfile(
        user: FirebaseUser,
        fullName: String?,
        profileImageUrl: String?,
        palette: PersonalPalette?
    ) {
        val nameFromDoc = fullName?.trim().orEmpty()
        tvName.text = if (nameFromDoc.isNotEmpty()) nameFromDoc else nameFromAuth(user)

        val imageUrl = normalizeProfileImageUrl(profileImageUrl)
        if (!imageUrl.isNullOrEmpty()) {
            if (imageUrl != lastShownImageUrl) {
                Glide.with(this).clear(ivProfile)
                lastShownImageUrl = imageUrl
            }
            Glide.with(this)
                .load(imageUrl)
                .signature(ObjectKey(imageUrl))
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .centerCrop()
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

    private fun nameFromAuth(u: FirebaseUser): String =
        u.displayName?.takeIf { it.isNotBlank() }
            ?: u.email?.substringBefore("@")
            ?: getString(R.string.profile_default_name)

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

    private fun loadMyOutfits() {
        if (loggingOut) return
        val uid = auth.currentUser?.uid
        if (uid == null) {
            if (::adapter.isInitialized) {
                adapter.updateData(emptyList())
                showOutfitsEmptyState(true)
            }
            return
        }

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
        rvOutfits.visibility = if (show) View.GONE else View.VISIBLE
    }

    private fun normalizeProfileImageUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return url.replace(
            "stylemate-32bf7.firebasestorage.app",
            "stylepallete-32bf7.firebasestorage.app"
        )
    }

    private companion object {
        const val TAG = "StyleMate_Profile"
    }
}
