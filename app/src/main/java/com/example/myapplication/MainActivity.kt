package com.example.myapplication

import android.os.Bundle
import android.view.inputmethod.EditorInfo
import com.example.myapplication.adapters.OutfitAdapter
import com.example.myapplication.models.FeedFilters
import com.example.myapplication.models.Outfit
import com.example.myapplication.models.PersonalPalette
import com.example.myapplication.repository.OutfitRepository
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.firestore.ListenerRegistration

class MainActivity : BaseActivity() {

    private lateinit var adapter: OutfitAdapter
    private val outfitRepository = OutfitRepository()

    private var allOutfitsList: List<Outfit> = emptyList()
    private var currentFilters = FeedFilters()
    private var userPalette: PersonalPalette? = null
    private var searchVibeQuery: String = ""
    private var ignorePaletteToggleEvent = false
    /** Last [matchMyPalette] we successfully wrote; used until [observeUserFeedState] matches (avoids stale snapshots). */
    private var pendingMatchMyPalette: Boolean? = null

    private var outfitsRegistration: ListenerRegistration? = null
    private var userFeedRegistration: ListenerRegistration? = null
    private var likedIdsRegistration: ListenerRegistration? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setupBottomNavigation(R.id.btn_home)
        setupToolbar()
        setupSearchBar()
        setupPaletteSwitch()
        setupRecyclerView()
    }

    override fun onStart() {
        super.onStart()
        if (auth.currentUser != null) {
            attachFeedListeners()
        }
    }

    override fun onBeforeLogout() {
        detachFirestoreListeners()
    }

    override fun onStop() {
        detachFirestoreListeners()
        super.onStop()
    }

    override fun onDestroy() {
        detachFirestoreListeners()
        super.onDestroy()
    }

    private fun detachFirestoreListeners() {
        outfitsRegistration?.remove()
        outfitsRegistration = null
        userFeedRegistration?.remove()
        userFeedRegistration = null
        likedIdsRegistration?.remove()
        likedIdsRegistration = null
    }

    private fun setupToolbar() {
        findViewById<MaterialToolbar>(R.id.main_toolbar)
    }

    private fun setupRecyclerView() {
        adapter = OutfitAdapter(
            outfits = emptyList(),
            showLikeButton = true
        ) { outfit ->
            navigateToDetail(outfit)
        }
        setupRecyclerView(R.id.main_RV_list, 2, adapter)
    }

    private fun setupSearchBar() {
        val etSearch = findViewById<TextInputEditText>(R.id.main_ET_search_vibe)
        val tilSearch = findViewById<TextInputLayout>(R.id.main_TIL_search_vibe)
        tilSearch.setEndIconOnClickListener { applySearchQueryFromField(etSearch) }
        etSearch.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                applySearchQueryFromField(v as TextInputEditText)
                true
            } else {
                false
            }
        }
    }

    private fun applySearchQueryFromField(etSearch: TextInputEditText) {
        val next = etSearch.text?.toString()?.trim().orEmpty()
        if (next == searchVibeQuery) return
        searchVibeQuery = next
        applyFiltersToList()
    }

    private fun setupPaletteSwitch() {
        val switchPalette = findViewById<MaterialSwitch>(R.id.main_switch_match_palette)
        switchPalette.setOnCheckedChangeListener { _, isChecked ->
            if (ignorePaletteToggleEvent) return@setOnCheckedChangeListener
            val previousFilters = currentFilters
            val filters = currentFilters.copy(matchMyPalette = isChecked)
            pendingMatchMyPalette = isChecked
            currentFilters = filters
            applyFiltersToList()
            outfitRepository.saveFeedFilters(filters) { ok, err ->
                if (ok) {
                    pendingMatchMyPalette = filters.matchMyPalette
                } else {
                    pendingMatchMyPalette = null
                    currentFilters = previousFilters
                    ignorePaletteToggleEvent = true
                    switchPalette.isChecked = previousFilters.matchMyPalette
                    ignorePaletteToggleEvent = false
                    applyFiltersToList()
                    val detail = err?.takeIf { it.isNotBlank() } ?: getString(R.string.feed_filters_save_unknown)
                    showToast(getString(R.string.feed_filters_save_failed, detail))
                }
            }
        }
    }

    private fun attachFeedListeners() {
        attachOutfitsListener()
        attachUserFeedListener()
        attachLikedIdsListener()
    }

    private fun attachLikedIdsListener() {
        likedIdsRegistration?.remove()
        likedIdsRegistration = outfitRepository.observeLikedOutfitIds { _ ->
            if (auth.currentUser == null || isFinishing || isDestroyed) return@observeLikedOutfitIds
            if (::adapter.isInitialized) {
                adapter.notifyDataSetChanged()
            }
        }
    }

    private fun attachOutfitsListener() {
        outfitsRegistration?.remove()
        outfitsRegistration = outfitRepository.observeAllOutfits { list, error ->
            if (auth.currentUser == null || isFinishing || isDestroyed) return@observeAllOutfits
            if (list != null) {
                allOutfitsList = list
                applyFiltersToList()
            } else {
                showFirestoreError(error)
            }
        }
    }

    private fun attachUserFeedListener() {
        userFeedRegistration?.remove()
        userFeedRegistration = outfitRepository.observeUserFeedState { filters, palette ->
            if (auth.currentUser == null || isFinishing || isDestroyed) return@observeUserFeedState
            userPalette = palette
            val resolved = resolveFeedFiltersAgainstPending(filters)
            currentFilters = resolved
            val switchPalette = findViewById<MaterialSwitch>(R.id.main_switch_match_palette)
            ignorePaletteToggleEvent = true
            switchPalette.isChecked = resolved.matchMyPalette
            ignorePaletteToggleEvent = false
            applyFiltersToList()
        }
    }

    /**
     * Firestore can emit a cached snapshot right after we save [matchMyPalette] = false, still
     * showing true; that would keep the feed palette-filtered. Prefer [pendingMatchMyPalette]
     * until the server snapshot agrees, then clear it.
     */
    private fun resolveFeedFiltersAgainstPending(fromServer: FeedFilters): FeedFilters {
        val pending = pendingMatchMyPalette ?: return fromServer
        if (fromServer.matchMyPalette == pending) {
            pendingMatchMyPalette = null
            return fromServer
        }
        return fromServer.copy(matchMyPalette = pending)
    }

    private fun applyFiltersToList() {
        var list = allOutfitsList
        val query = searchVibeQuery.trim()
        if (query.isNotBlank()) {
            list = list.filter { it.vibe.contains(query, ignoreCase = true) }
        }
        // When on: only outfits whose saved garment colors fall inside your palette swatches.
        if (currentFilters.matchMyPalette) {
            val pal = userPalette
            val hasSwatches = pal != null &&
                    (pal.powerSwatches.isNotEmpty() || pal.neutralSwatches.isNotEmpty())
            if (hasSwatches && pal != null) {
                list = list.filter { FeedPaletteMatcher.outfitMatchesPersonalPalette(it, pal) }
            }
            // If palette is not loaded yet (common right after signup), show all outfits instead of an empty feed.
        }
        adapter.updateData(list)
    }

    override fun onResume() {
        super.onResume()
        if (auth.currentUser != null) {
            attachFeedListeners()
        }
        if (::adapter.isInitialized) {
            adapter.updateData(allOutfitsList)
            applyFiltersToList()
        }
    }
}