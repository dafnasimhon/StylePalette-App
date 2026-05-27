package com.example.myapplication

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import com.example.myapplication.adapters.OutfitAdapter
import com.example.myapplication.repository.OutfitRepository

class FavoritesActivity : BaseActivity() {

    companion object {
        private const val TAG = "StylePalette_Like"
    }

    private lateinit var adapter: OutfitAdapter
    private lateinit var tvEmpty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_favorites)

        setupBottomNavigation(R.id.btn_favorites)
        tvEmpty = findViewById(R.id.fav_TV_empty)
        setupRecyclerView()
        showEmptyState(true)
        attachFavoritesListener()
    }

    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized && auth.currentUser != null) {
            Log.i(TAG, "Favorites onResume — reloading wishlist")
            attachFavoritesListener()
        }
    }

    private fun setupRecyclerView() {
        adapter = OutfitAdapter(
            outfits = emptyList(),
            showLikeButton = true,
            repository = OutfitRepository
        ) { outfit ->
            navigateToDetail(outfit)
        }
        setupRecyclerView(R.id.fav_RV_list, 2, adapter)
    }

    private fun attachFavoritesListener() {
        Log.i(TAG, "Favorites attachFavoritesListener")

        OutfitRepository.getFavoriteOutfits { list, error ->
            if (isFinishing || isDestroyed) return@getFavoriteOutfits
            if (list != null) {
                Log.i(
                    TAG,
                    "Favorites load result: count=${list.size} ids=${list.map { it.id }} error=$error"
                )
                adapter.updateData(list)
                showEmptyState(list.isEmpty())
            } else {
                Log.e(TAG, "Favorites load failed: $error")
                adapter.updateData(emptyList())
                showEmptyState(true)
                showFirestoreError(error)
            }
        }
    }

    private fun showEmptyState(show: Boolean) {
        tvEmpty.visibility = if (show) View.VISIBLE else View.GONE
        findViewById<View>(R.id.fav_RV_list).visibility = if (show) View.GONE else View.VISIBLE
    }
}