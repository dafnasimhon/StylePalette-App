package com.example.myapplication

import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import com.example.myapplication.adapters.OutfitAdapter
import com.example.myapplication.models.AppConfig
import com.example.myapplication.models.FeedFilters
import com.example.myapplication.models.Outfit
import com.example.myapplication.models.PersonalPalette
import com.example.myapplication.repository.OutfitRepository
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.firestore.FirebaseFirestore

class MainActivity : BaseActivity() {

    companion object {
        private const val TAG = "StyleMate_Main"
    }

    private lateinit var adapter: OutfitAdapter
    private var tvEmpty: TextView? = null

    private var allOutfitsList: List<Outfit> = emptyList()
    private var currentFilters = FeedFilters()
    private var userPalette: PersonalPalette? = null
    private var searchVibeQuery: String = ""
    private var ignorePaletteToggleEvent = false

    /** Last [matchMyPalette] we successfully wrote; used until remote data matches (avoids stale snapshots). */
    private var pendingMatchMyPalette: Boolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setupBottomNavigation(R.id.btn_home)
        setupToolbar()
        setupSearchBar()
        setupPaletteSwitch()

        val emptyViewId = resources.getIdentifier("main_TV_empty", "id", packageName)
        if (emptyViewId != 0) {
            tvEmpty = findViewById(emptyViewId)
        }

        setupRecyclerView()
        fetchUserPaletteAndSettings()
        loadFeedData()
    }

    override fun onResume() {
        super.onResume()
        if (auth.currentUser != null) {
            fetchUserPaletteAndSettings()
            loadFeedData()
        }
    }

    override fun onDestroy() {
        OutfitRepository.clearListeners()
        super.onDestroy()
    }

    private fun setupToolbar() {
        findViewById<MaterialToolbar>(R.id.main_toolbar)
    }

    private fun setupRecyclerView() {
        adapter = OutfitAdapter(
            outfits = emptyList(),
            showLikeButton = true,
            repository = OutfitRepository
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
        applyFiltersAndDisplay()
    }

    private fun setupPaletteSwitch() {
        val switchPalette = findViewById<MaterialSwitch>(R.id.main_switch_match_palette)
        switchPalette?.setOnCheckedChangeListener { _, isChecked ->
            if (ignorePaletteToggleEvent) return@setOnCheckedChangeListener

            val previousFilters = currentFilters
            val filters = currentFilters.copy(matchMyPalette = isChecked)

            pendingMatchMyPalette = isChecked
            currentFilters = filters
            applyFiltersAndDisplay()

            // עדכון ה-Preference של המשתמש ב-Firestore תחת הגדרות הפיד שלו
            val userId = auth.currentUser?.uid ?: return@setOnCheckedChangeListener
            FirebaseFirestore.getInstance().collection(AppConfig.COLL_USERS).document(userId)
                .update("feedFilters.matchMyPalette", isChecked)
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        pendingMatchMyPalette = filters.matchMyPalette
                    } else {
                        pendingMatchMyPalette = null
                        currentFilters = previousFilters
                        ignorePaletteToggleEvent = true
                        switchPalette.isChecked = previousFilters.matchMyPalette
                        ignorePaletteToggleEvent = false
                        applyFiltersAndDisplay()
                        showToast("Failed to save filters configuration")
                    }
                }
        }
    }

    /**
     * מביא את הגדרות הסינון הפעילות ואת הפאלטה המדויקת של המשתמש ('personalPalette') מתוך Firestore
     */
    private fun fetchUserPaletteAndSettings() {
        val userId = auth.currentUser?.uid ?: return

        FirebaseFirestore.getInstance().collection(AppConfig.COLL_USERS).document(userId)
            .get()
            .addOnSuccessListener { document ->
                if (isFinishing || isDestroyed) return@addOnSuccessListener
                if (document != null && document.exists()) {
                    // 1. שליפה ופענוח של הפאלטה האישית לפי המבנה המדויק ב-DB
                    val paletteMap = document.get("personalPalette") as? Map<String, Any>
                    userPalette = PersonalPalette.fromFirestore(paletteMap)

                    // 2. שליפת מצב ה-Switch השמור של המשתמש מתוך השרת
                    val feedFiltersMap = document.get("feedFilters") as? Map<*, *>
                    val serverMatchMyPalette = feedFiltersMap?.get("matchMyPalette") as? Boolean ?: false

                    val serverFilters = FeedFilters(matchMyPalette = serverMatchMyPalette)
                    val resolved = resolveFeedFiltersAgainstPending(serverFilters)
                    currentFilters = resolved

                    // 3. עדכון מצב ה-Switch הויזואלי במסך ללא הפעלת לולאת אירועים
                    val switchPalette = findViewById<MaterialSwitch>(R.id.main_switch_match_palette)
                    if (switchPalette != null) {
                        ignorePaletteToggleEvent = true
                        switchPalette.isChecked = resolved.matchMyPalette
                        ignorePaletteToggleEvent = false
                    }

                    applyFiltersAndDisplay()
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to load user palette states: ${e.message}")
            }
    }

    private fun resolveFeedFiltersAgainstPending(fromServer: FeedFilters): FeedFilters {
        val pending = pendingMatchMyPalette ?: return fromServer
        if (fromServer.matchMyPalette == pending) {
            pendingMatchMyPalette = null
            return fromServer
        }
        return fromServer.copy(matchMyPalette = pending)
    }

    private fun loadFeedData() {
        Log.i(TAG, "Loading outfits feed...")

        OutfitRepository.getAllOutfits { list, error ->
            if (isFinishing || isDestroyed) return@getAllOutfits
            if (list != null) {
                allOutfitsList = list
                applyFiltersAndDisplay()
            } else {
                Log.e(TAG, "Failed to load feed: $error")
                showFirestoreError(error)
            }
        }
    }

    private fun applyFiltersAndDisplay() {
        var filteredList = allOutfitsList
        val query = searchVibeQuery.trim()

        // 1. סינון לפי החיפוש החופשי (Vibe) במידה והוקלד טקסט
        if (query.isNotBlank()) {
            filteredList = filteredList.filter { it.vibe.contains(query, ignoreCase = true) }
        }

        // 2. סינון לפי פאלטה: רץ רק כאשר ה-Switch דלוק (currentFilters.matchMyPalette == true)
        if (currentFilters.matchMyPalette) {
            val pal = userPalette
            val hasSwatches = pal != null && (pal.powerSwatches.isNotEmpty() || pal.neutralSwatches.isNotEmpty())

            if (hasSwatches && pal != null) {
                filteredList = filteredList.filter { outfit ->
                    FeedPaletteMatcher.outfitMatchesPersonalPalette(outfit, pal)
                }
            }
        }

        if (::adapter.isInitialized) {
            adapter.updateData(filteredList)
            showEmptyState(filteredList.isEmpty())
        }
    }

    private fun showEmptyState(show: Boolean) {
        tvEmpty?.visibility = if (show) View.VISIBLE else View.GONE
        findViewById<View>(R.id.main_RV_list).visibility = if (show) View.GONE else View.VISIBLE
    }
}