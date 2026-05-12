package com.example.myapplication

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import com.bumptech.glide.Glide
import com.example.myapplication.adapters.OutfitAdapter
import com.example.myapplication.models.AppConfig
import com.example.myapplication.models.PersonalPalette
import com.example.myapplication.repository.OutfitRepository
import com.google.android.material.card.MaterialCardView
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

class ProfileActivity : BaseActivity() {

    private lateinit var adapter: OutfitAdapter
    private val repository = OutfitRepository()
    private val db = FirebaseFirestore.getInstance()

    private var userProfileListener: ListenerRegistration? = null
    private var myOutfitsListener: ListenerRegistration? = null

    private lateinit var cardPalette: MaterialCardView
    private lateinit var llPower: LinearLayout
    private lateinit var llNeutral: LinearLayout

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

        setupRecyclerView()
        // Listeners attach in onStart (removed in onStop) so we do not double-load onCreate + onResume.

        val ivProfile = findViewById<ImageView>(R.id.profile_IV_user)
        ivProfile.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        val btnLogout = findViewById<ImageButton>(R.id.profile_BTN_logout)
        btnLogout.setOnClickListener {
            showLogoutDialog()
        }
    }

    private fun showLogoutDialog() {
        AlertDialog.Builder(this)
            .setTitle("Logout")
            .setMessage("Are you sure you want to log out?")
            .setPositiveButton("Logout") { _, _ ->
                performLogout()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performLogout() {
        auth.signOut()
        val intent = Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }

    override fun onStart() {
        super.onStart()
        loadUserData()
        loadMyOutfits()
    }

    private fun uploadImage(uri: Uri) {
        showToast("Updating profile image...")
        repository.uploadProfileImage(uri) { success, error ->
            if (success) {
                showToast("Profile updated successfully!")
            } else {
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

        setupRecyclerView(R.id.profile_RV_my_outfits, 3, adapter)
    }

    private fun loadUserData() {
        val tvName = findViewById<TextView>(R.id.profile_TV_username)
        val ivProfile = findViewById<ImageView>(R.id.profile_IV_user)

        val user = auth.currentUser
        if (user == null) {
            tvName.text = getString(R.string.profile_not_signed_in)
            cardPalette.visibility = View.GONE
            return
        }

        fun nameFromAuth(u: FirebaseUser): String {
            return u.displayName?.takeIf { it.isNotBlank() }
                ?: u.email?.substringBefore("@")
                ?: getString(R.string.profile_default_name)
        }

        val userId = user.uid
        // Never leave the default "Loading Name…" if Firestore is slow or offline.
        tvName.text = nameFromAuth(user)

        userProfileListener?.remove()
        userProfileListener = db.collection(AppConfig.COLL_USERS).document(userId)
            .addSnapshotListener { snap, e ->
                if (isFinishing) return@addSnapshotListener
                if (e != null) {
                    tvName.text = nameFromAuth(user)
                    cardPalette.visibility = View.GONE
                    showToast("${getString(R.string.profile_load_failed)}: ${e.message}")
                    return@addSnapshotListener
                }
                applyUserSnapshot(snap, user, tvName, ivProfile)
            }
    }

    private fun applyUserSnapshot(
        snap: DocumentSnapshot?,
        user: FirebaseUser,
        tvName: TextView,
        ivProfile: ImageView
    ) {
        fun nameFromAuth(u: FirebaseUser): String {
            return u.displayName?.takeIf { it.isNotBlank() }
                ?: u.email?.substringBefore("@")
                ?: getString(R.string.profile_default_name)
        }
        if (snap == null || !snap.exists()) {
            tvName.text = nameFromAuth(user)
            cardPalette.visibility = View.GONE
            return
        }

        val nameFromDoc = snap.getString("fullName")?.trim().orEmpty()
        tvName.text = if (nameFromDoc.isNotEmpty()) nameFromDoc else nameFromAuth(user)

        val profileUrl = snap.getString("profileImageUrl")
        if (!profileUrl.isNullOrEmpty()) {
            Glide.with(this@ProfileActivity)
                .load(profileUrl)
                .centerCrop()
                .placeholder(R.drawable.ic_person)
                .into(ivProfile)
        }

        @Suppress("UNCHECKED_CAST")
        val rawPalette = snap.get(AppConfig.FIELD_PERSONAL_PALETTE) as? Map<String, Any>
        val palette = PersonalPalette.fromFirestore(rawPalette)
        if (palette != null) {
            bindProfilePalette(palette)
            cardPalette.visibility = View.VISIBLE
        } else {
            cardPalette.visibility = View.GONE
        }
    }

    private fun bindProfilePalette(p: PersonalPalette) {
        findViewById<TextView>(R.id.profile_TV_palette_title).text =
            "${p.seasonalPalette} palette"

        findViewById<TextView>(R.id.profile_TV_palette_traits).text =
            "Skin: ${p.skinTone} · Eyes: ${p.eyeColor} · Hair: ${p.hairColor}"

        findViewById<TextView>(R.id.profile_TV_palette_description).text =
            p.paletteDescription

        llPower.removeAllViews()
        llNeutral.removeAllViews()

        p.powerSwatches.forEach { addRangeSwatch(llPower, it.rgbMin, it.rgbMax) }
        p.neutralSwatches.forEach { addRangeSwatch(llNeutral, it.rgbMin, it.rgbMax) }
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

    private fun loadMyOutfits() {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            adapter.updateData(emptyList())
            return
        }

        myOutfitsListener?.remove()
        myOutfitsListener = repository.observeMyOutfits { list, error ->
            if (isFinishing) return@observeMyOutfits
            if (list != null) {
                adapter.updateData(list)
            } else {
                showToast("Error: $error")
            }
        }
    }

    override fun onStop() {
        userProfileListener?.remove()
        userProfileListener = null
        myOutfitsListener?.remove()
        myOutfitsListener = null
        super.onStop()
    }
}
