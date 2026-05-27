package com.example.myapplication

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.bumptech.glide.Glide
import com.example.myapplication.models.AppConfig
import com.example.myapplication.models.OutfitRgb
import com.example.myapplication.repository.OutfitRepository
import com.google.firebase.firestore.FirebaseFirestore

class OutfitDetailActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_outfit_detail)

        val ivImage = findViewById<ImageView>(R.id.detail_IV_image)
        val tvUsername = findViewById<TextView>(R.id.detail_TV_username)
        val btnDelete = findViewById<ImageButton>(R.id.detail_BTN_delete)

        val outfitId = intent.getStringExtra(AppConfig.EXTRA_OUTFIT_ID)
        val imageUrl = intent.getStringExtra(AppConfig.EXTRA_IMAGE_URL)
        val userId = intent.getStringExtra(AppConfig.EXTRA_USER_ID)
        val vibe = intent.getStringExtra(AppConfig.EXTRA_VIBE)
        val isFromProfile = intent.getBooleanExtra(AppConfig.EXTRA_FROM_PROFILE, false)

        val activeTab = if (isFromProfile) R.id.btn_profile else 0
        setupBottomNavigation(activeTab)

        setupFieldWithColor(findViewById(R.id.detail_TV_vibe), "VIBE", vibe, null)
        setupFieldWithColor(
            findViewById(R.id.detail_TV_top),
            "TOP",
            intent.getStringExtra(AppConfig.EXTRA_TOP),
            intent.getIntArrayExtra(AppConfig.EXTRA_TOP_RGB)
        )
        setupFieldWithColor(
            findViewById(R.id.detail_TV_bottom),
            "BOTTOM",
            intent.getStringExtra(AppConfig.EXTRA_BOTTOM),
            intent.getIntArrayExtra(AppConfig.EXTRA_BOTTOM_RGB)
        )
        setupFieldWithColor(
            findViewById(R.id.detail_TV_jacket),
            "JACKET",
            intent.getStringExtra(AppConfig.EXTRA_JACKET),
            intent.getIntArrayExtra(AppConfig.EXTRA_JACKET_RGB)
        )
        setupFieldWithColor(
            findViewById(R.id.detail_TV_shoes),
            "SHOES",
            intent.getStringExtra(AppConfig.EXTRA_SHOES),
            intent.getIntArrayExtra(AppConfig.EXTRA_SHOES_RGB)
        )
        setupFieldWithColor(
            findViewById(R.id.detail_TV_jewelry),
            "JEWELRY",
            intent.getStringExtra(AppConfig.EXTRA_JEWELRY),
            intent.getIntArrayExtra(AppConfig.EXTRA_JEWELRY_RGB)
        )
        setupFieldWithColor(
            findViewById(R.id.detail_TV_sunglasses),
            "SUNGLASSES",
            intent.getStringExtra(AppConfig.EXTRA_SUNGLASSES),
            intent.getIntArrayExtra(AppConfig.EXTRA_SUNGLASSES_RGB)
        )
        setupFieldWithColor(
            findViewById(R.id.detail_TV_bag),
            "BAG",
            intent.getStringExtra(AppConfig.EXTRA_BAG),
            intent.getIntArrayExtra(AppConfig.EXTRA_BAG_RGB)
        )

        val currentUserId = auth.currentUser?.uid
        btnDelete.visibility = if (currentUserId != null && currentUserId == userId && isFromProfile) {
            View.VISIBLE
        } else {
            View.GONE
        }

        Glide.with(this)
            .load(imageUrl)
            .placeholder(R.drawable.placeholder_outfit)
            .into(ivImage)

        fetchUsername(userId, tvUsername)

        btnDelete.setOnClickListener {
            if (outfitId != null && imageUrl != null) {
                showDeleteConfirmation(outfitId, imageUrl)
            }
        }
    }

    private fun setupFieldWithColor(textView: TextView, label: String, value: String?, rgb: IntArray?) {
        if (!value.isNullOrEmpty()) {
            textView.visibility = View.VISIBLE
            textView.text = "${label.uppercase()}   —   ${value.uppercase()}"
            applyEndColorDot(textView, rgb)
        } else {
            textView.visibility = View.GONE
            textView.setCompoundDrawablesRelative(null, null, null, null)
        }
    }

    private fun applyEndColorDot(textView: TextView, rgbArray: IntArray?) {
        val rgb = OutfitRgb.rgbArrayToColorInt(rgbArray) ?: run {
            textView.setCompoundDrawablesRelative(null, null, null, null)
            return
        }
        val d = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(rgb)
            setStroke((1 * resources.displayMetrics.density).toInt().coerceAtLeast(1), Color.argb(120, 0, 0, 0))
        }
        val size = (18 * resources.displayMetrics.density).toInt()
        d.setBounds(0, 0, size, size)
        textView.setCompoundDrawablesRelative(null, null, d, null)
        textView.compoundDrawablePadding = (8 * resources.displayMetrics.density).toInt()
    }

    private fun fetchUsername(userId: String?, textView: TextView) {
        if (userId == null) {
            textView.text = "CURATED BY StylePalette"
            return
        }

        FirebaseFirestore.getInstance().collection(AppConfig.COLL_USERS).document(userId)
            .get()
            .addOnSuccessListener { document ->
                val name = document.getString("fullName")?.uppercase() ?: "UNKNOWN"
                textView.text = "CURATED BY $name"
            }
            .addOnFailureListener {
                textView.text = "CURATED BY StylePalette"
            }
    }

    private fun showDeleteConfirmation(outfitId: String, imageUrl: String) {
        AlertDialog.Builder(this)
            .setTitle("Delete Outfit")
            .setMessage("Remove this look from your wardrobe?")
            .setPositiveButton("Delete") { _, _ ->
                OutfitRepository.deleteOutfit(outfitId, imageUrl) { success ->
                    if (success) {
                        showToast("Outfit deleted")
                        finish()
                    } else {
                        showToast("Failed to delete")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}