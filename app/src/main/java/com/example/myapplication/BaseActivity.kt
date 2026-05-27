package com.example.myapplication

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.adapters.OutfitAdapter
import com.example.myapplication.models.AppConfig
import com.example.myapplication.models.OutfitRgb
import com.example.myapplication.models.Outfit
import com.example.myapplication.repository.OutfitRepository
import com.google.firebase.auth.FirebaseAuth

open class BaseActivity : AppCompatActivity() {

    protected val auth: FirebaseAuth = FirebaseAuth.getInstance()

    override fun onStart() {
        super.onStart()
        checkUserStatus()
    }

    override fun onResume() {
        super.onResume()
        checkUserStatus()
    }

    protected fun setupRecyclerView(rvId: Int, spanCount: Int, adapter: OutfitAdapter): RecyclerView {
        val rv = findViewById<RecyclerView>(rvId)
        rv.layoutManager = GridLayoutManager(this, spanCount)
        rv.adapter = adapter
        return rv
    }

    protected fun showToast(message: String?) {
        Toast.makeText(this, message ?: "An error occurred", Toast.LENGTH_SHORT).show()
    }

    protected open fun onBeforeLogout() {}

    protected fun performLogout() {
        onBeforeLogout()

        OutfitRepository.clearListeners()

        auth.signOut()
        val intent = Intent(applicationContext, LoginActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
        }
        applicationContext.startActivity(intent)
        finishAffinity()
    }

    /** After [FirebaseAuth.signOut], in-flight reads often fail — do not toast those. */
    protected fun shouldShowFirestoreError(error: String?): Boolean {
        if (error.isNullOrBlank()) return false
        if (auth.currentUser == null) return false
        if (isFinishing || isDestroyed) return false
        val msg = error.lowercase()
        if (msg.contains("permission") && msg.contains("denied")) return false
        if (msg.contains("permission_denied")) return false
        return true
    }

    protected fun showFirestoreError(error: String?) {
        if (shouldShowFirestoreError(error)) {
            showToast("Error: $error")
        }
    }

    private fun checkUserStatus() {
        if (auth.currentUser == null && isAuthRequired()) {
            val intent = Intent(this, LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }
    }

    private fun isAuthRequired(): Boolean {
        val currentClass = this::class.java.simpleName
        return currentClass != LoginActivity::class.java.simpleName &&
                currentClass != RegisterActivity::class.java.simpleName
    }

    protected fun setupBottomNavigation(activeButtonId: Int) {
        applyBottomNavInsets()

        val btnHome = findViewById<ImageButton>(R.id.btn_home)
        val btnAdd = findViewById<ImageButton>(R.id.btn_add_outfit)
        val btnProfile = findViewById<ImageButton>(R.id.btn_profile)
        val btnFavorites = findViewById<ImageButton>(R.id.btn_favorites)

        val buttons = listOf(btnHome, btnAdd, btnProfile, btnFavorites)

        buttons.forEach { button ->
            button?.isSelected = button.id == activeButtonId
        }

        btnHome?.setOnClickListener { navigateTo(MainActivity::class.java) }
        btnAdd?.setOnClickListener { navigateTo(UploadOutfitActivity::class.java) }
        btnFavorites?.setOnClickListener { navigateTo(FavoritesActivity::class.java) }
        btnProfile?.setOnClickListener { navigateTo(ProfileActivity::class.java) }
    }

    /** Lifts the bottom bar above the system gesture / 3-button navigation area. */
    private fun applyBottomNavInsets() {
        val navBar = findViewById<View>(R.id.bottom_nav_container) ?: return
        ViewCompat.setOnApplyWindowInsetsListener(navBar) { view, windowInsets ->
            val bottomInset = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            val lp = view.layoutParams
            when (lp) {
                is ConstraintLayout.LayoutParams -> {
                    lp.bottomMargin = bottomInset
                    view.layoutParams = lp
                }
                is ViewGroup.MarginLayoutParams -> {
                    lp.bottomMargin = bottomInset
                    view.layoutParams = lp
                }
            }
            windowInsets
        }
        ViewCompat.requestApplyInsets(navBar)
    }

    protected fun navigateToDetail(outfit: Outfit, isFromProfile: Boolean = false) {
        val intent = Intent(this, OutfitDetailActivity::class.java).apply {
            putExtra(AppConfig.EXTRA_OUTFIT_ID, outfit.id)
            putExtra(AppConfig.EXTRA_IMAGE_URL, outfit.imageUrl)
            putExtra(AppConfig.EXTRA_USER_ID, outfit.userId)
            putExtra(AppConfig.EXTRA_FROM_PROFILE, isFromProfile)
            putExtra(AppConfig.EXTRA_VIBE, outfit.vibe)
            putExtra(AppConfig.EXTRA_TOP, outfit.top)
            putExtra(AppConfig.EXTRA_BOTTOM, outfit.bottom)
            putExtra(AppConfig.EXTRA_JACKET, outfit.jacket)
            putExtra(AppConfig.EXTRA_SHOES, outfit.shoes)
            putExtra(AppConfig.EXTRA_JEWELRY, outfit.jewelry)
            putExtra(AppConfig.EXTRA_SUNGLASSES, outfit.sunglasses)
            putExtra(AppConfig.EXTRA_BAG, outfit.bag)
            OutfitRgb.garmentRgbIntArray(outfit.topRgb, outfit.topColorHex)?.let { putExtra(AppConfig.EXTRA_TOP_RGB, it) }
            OutfitRgb.garmentRgbIntArray(outfit.bottomRgb, outfit.bottomColorHex)?.let { putExtra(AppConfig.EXTRA_BOTTOM_RGB, it) }
            OutfitRgb.garmentRgbIntArray(outfit.jacketRgb, outfit.jacketColorHex)?.let { putExtra(AppConfig.EXTRA_JACKET_RGB, it) }
            OutfitRgb.garmentRgbIntArray(outfit.shoesRgb, outfit.shoesColorHex)?.let { putExtra(AppConfig.EXTRA_SHOES_RGB, it) }
            OutfitRgb.garmentRgbIntArray(outfit.jewelryRgb, outfit.jewelryColorHex)?.let { putExtra(AppConfig.EXTRA_JEWELRY_RGB, it) }
            OutfitRgb.garmentRgbIntArray(outfit.sunglassesRgb, outfit.sunglassesColorHex)?.let { putExtra(AppConfig.EXTRA_SUNGLASSES_RGB, it) }
            OutfitRgb.garmentRgbIntArray(outfit.bagRgb, outfit.bagColorHex)?.let { putExtra(AppConfig.EXTRA_BAG_RGB, it) }
        }
        startActivity(intent)
    }

    private fun navigateTo(destination: Class<*>) {
        if (this.javaClass != destination) {
            val intent = Intent(this, destination)
            intent.flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            startActivity(intent)
        }
    }
}