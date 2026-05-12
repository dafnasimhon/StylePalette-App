package com.example.myapplication

import android.os.Bundle
import com.example.myapplication.adapters.OutfitAdapter
import com.example.myapplication.repository.OutfitRepository

class FavoritesActivity : BaseActivity() {

    private lateinit var adapter: OutfitAdapter
    private val outfitRepository = OutfitRepository()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_favorites)

        setupBottomNavigation(R.id.btn_favorites)

        setupRecyclerView()
        loadFavoriteOutfits()
    }

    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized) {
            loadFavoriteOutfits()
        }
    }

    private fun setupRecyclerView() {
        adapter = OutfitAdapter(
            outfits = emptyList(),
            showLikeButton = true
        ) { outfit ->
            navigateToDetail(outfit)
        }
        setupRecyclerView(R.id.fav_RV_list, 2, adapter)
    }

    private fun loadFavoriteOutfits() {
        outfitRepository.getFavoriteOutfits { list, error ->
            if (list != null) {
                adapter.updateData(list)

                if (list.isEmpty()) {
                    showToast("Your wishlist is empty!")
                }
            } else {
                showToast("Error: $error")
            }
        }
    }
}