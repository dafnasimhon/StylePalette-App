package com.example.myapplication.repository

import android.content.Context
import android.net.Uri
import com.example.myapplication.App
import android.os.Handler
import android.os.Looper
import com.example.myapplication.models.AppConfig
import com.example.myapplication.models.FeedFilters
import com.example.myapplication.models.Outfit
import com.example.myapplication.models.OutfitRgb
import com.example.myapplication.models.PersonalPalette
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import java.io.File
import java.util.Collections
import java.util.Locale
import java.util.UUID

class OutfitRepository {

    private companion object {
        const val UPLOAD_TIMEOUT_MS = 120_000L
        const val PROFILE_LOAD_TIMEOUT_MS = 15_000L
        const val PROFILE_LOG_TAG = "StyleMate_Profile"
        const val OUTFIT_LOG_TAG = "StyleMate_Outfit"
        const val LIKE_LOG_TAG = "StyleMate_Like"
        private const val OUTFIT_FIRESTORE_RETRY_MS = 3_000L
        private const val OUTFIT_FIRESTORE_MAX_RETRIES = 8
        const val LIKE_WRITE_TIMEOUT_MS = 20_000L
        private const val LIKE_SYNC_DEBOUNCE_MS = 500L
        private const val PREFS_LIKES = "stylemate_liked_outfits"
        private const val PREFS_KEY_PREFIX = "liked_ids_"

        @Volatile
        var rememberedProfileUid: String? = null
        @Volatile
        var rememberedProfile: UserProfileSnapshot? = null
        @Volatile
        var rememberedMyOutfitsUid: String? = null
        @Volatile
        var rememberedMyOutfits: List<Outfit>? = null
        @Volatile
        var rememberedFavoritesUid: String? = null
        @Volatile
        var rememberedFavorites: List<Outfit>? = null
        /** Latest feed snapshot — used to resolve liked outfits when `whereIn` misses. */
        @Volatile
        var rememberedFeedOutfits: List<Outfit>? = null
        @Volatile
        var rememberedLikedIdsUid: String? = null
        val rememberedLikedIds: MutableSet<String> =
            Collections.synchronizedSet(mutableSetOf())
        /** Set after signup until a complete server user doc arrives (avoids stale-cache wipes). */
        @Volatile
        var registrationSeededUid: String? = null
        /** Shared across all [OutfitRepository] instances (Register vs Main vs Adapter). */
        @Volatile
        var firestoreUserReadyUid: String? = null
        @Volatile
        var lastCloudSyncedLikedCsv: String? = null
        @Volatile
        var likeSyncInFlight = false
        private val likeSyncHandler = Handler(Looper.getMainLooper())
        private var likeSyncDebounceRunnable: Runnable? = null
        private var likeSyncTimeoutRunnable: Runnable? = null
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private var myOutfitsListener: ListenerRegistration? = null
    private var myOutfitsListenerUid: String? = null
    private var favoritesListener: ListenerRegistration? = null
    private var favoritesSubcollectionListener: ListenerRegistration? = null
    private var favoritesOutfitsListener: ListenerRegistration? = null
    private var likedIdsListener: ListenerRegistration? = null
    private var userProfileListener: ListenerRegistration? = null
    /** One-shot listener used by [loadUserProfileNoCache]; cancelled when a new load starts. */
    private var profileLoadListener: ListenerRegistration? = null
    private var profileLoadTimeoutRunnable: Runnable? = null
    private val outfitFirestoreRetryCounts = HashMap<String, Int>()

    /** Firestore often returns `Map<*, *>`; normalize so [FeedFilters.fromFirestore] reads booleans reliably. */
    /** Corrects legacy Storage hostnames so Glide can load images on all sessions. */
    fun normalizeStorageUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return url.replace(
            "stylemate-32bf7.firebasestorage.app",
            "stylepallete-32bf7.firebasestorage.app"
        )
    }

    private fun firestoreErrorMessage(e: Exception): String {
        val fromMessage = e.message?.trim().orEmpty()
        if (fromMessage.isNotEmpty()) return fromMessage
        if (e is FirebaseFirestoreException) {
            return e.code.name.replace('_', ' ').lowercase()
                .replaceFirstChar { it.uppercase() }
        }
        return e.javaClass.simpleName
    }

    private fun coerceStringKeyMap(value: Any?): Map<String, Any>? {
        val m = value as? Map<*, *> ?: return null
        val out = HashMap<String, Any>(m.size)
        for ((k, v) in m) {
            if (k == null || v == null) continue
            out[k.toString()] = v
        }
        return out
    }

    /** Firestore nested maps/lists need key coercion before [PersonalPalette.fromFirestore]. */
    private fun coercePaletteMap(value: Any?): Map<String, Any>? {
        val m = value as? Map<*, *> ?: return null
        val out = HashMap<String, Any>(m.size)
        for ((k, v) in m) {
            val key = k?.toString() ?: continue
            when (v) {
                is Map<*, *> -> coercePaletteMap(v)?.let { out[key] = it }
                is List<*> -> {
                    val list = ArrayList<Any>(v.size)
                    for (item in v) {
                        when (item) {
                            is Map<*, *> -> coercePaletteMap(item)?.let { list.add(it) }
                            null -> Unit
                            else -> list.add(item)
                        }
                    }
                    out[key] = list
                }
                else -> if (v != null) out[key] = v
            }
        }
        return out
    }

    private val db = FirebaseFirestore.getInstance()
    private val storage = FirebaseStorage.getInstance()
    private val auth = FirebaseAuth.getInstance()

    private val currentUserId: String? get() = auth.currentUser?.uid

    fun loadGlobalVibes(defaultVibes: List<String>, onResult: (List<String>, String?) -> Unit) {
        db.collection(AppConfig.COLL_APP_META).document(AppConfig.DOC_GLOBAL_VIBES).get()
            .addOnSuccessListener { snap ->
                val raw = snap.get(AppConfig.FIELD_VALUES) as? List<*>
                val fromCloud = raw.orEmpty().mapNotNull { it as? String }.map { it.trim() }.filter { it.isNotEmpty() }
                val merged = mergeDistinctCaseInsensitive(defaultVibes, fromCloud)
                onResult(merged, null)
            }
            .addOnFailureListener { onResult(defaultVibes, it.message) }
    }

    /**
     * Garment colors must be `[r,g,b]` lists in `0…255` (Firestore `topRgb`, …). Hex is not written.
     */
    fun uploadOutfit(
        context: Context,
        imageUri: Uri,
        top: String,
        bottom: String,
        jacket: String,
        shoes: String,
        jewelry: String,
        sunglasses: String,
        bag: String,
        vibe: String,
        topRgb: List<Long>?,
        bottomRgb: List<Long>?,
        jacketRgb: List<Long>?,
        shoesRgb: List<Long>?,
        jewelryRgb: List<Long>?,
        sunglassesRgb: List<Long>?,
        bagRgb: List<Long>?,
        onResult: (Boolean, String?) -> Unit
    ) {
        val userId = currentUserId ?: return onResult(false, "User not logged in")
        val outfitId = UUID.randomUUID().toString()

        val tempFile = copyUriToCacheFile(context, imageUri)
        if (tempFile == null) {
            onResult(false, "Could not read the selected image")
            return
        }

        var finished = false
        val mainHandler = Handler(Looper.getMainLooper())
        fun complete(success: Boolean, error: String?) {
            if (finished) return
            finished = true
            tempFile.delete()
            onResult(success, error)
        }

        val timeout = Runnable {
            complete(false, "Upload timed out. Check your connection and Firebase Storage rules.")
        }
        mainHandler.postDelayed(timeout, UPLOAD_TIMEOUT_MS)

        val fileRef = storage.reference.child("${AppConfig.PATH_OUTFITS}/$outfitId.jpg")
        val metadata = StorageMetadata.Builder()
            .setContentType(guessImageContentType(context, imageUri, tempFile.name))
            .build()

        fileRef.putFile(Uri.fromFile(tempFile), metadata)
            .continueWithTask { task ->
                if (!task.isSuccessful) {
                    throw task.exception ?: Exception("Image upload failed")
                }
                fileRef.downloadUrl
            }
            .addOnSuccessListener { downloadUri ->
                mainHandler.removeCallbacks(timeout)
                val normalizedVibe = vibe.trim()
                val imageUrl = normalizeStorageUrl(downloadUri.toString()) ?: downloadUri.toString()
                val outfit = Outfit(
                    id = outfitId,
                    userId = userId,
                    imageUrl = imageUrl,
                    timestamp = System.currentTimeMillis(),
                    top = top,
                    bottom = bottom,
                    jacket = jacket,
                    shoes = shoes,
                    jewelry = jewelry,
                    sunglasses = sunglasses,
                    bag = bag,
                    vibe = normalizedVibe,
                    vibeSearchKey = normalizedVibe.lowercase(),
                    topRgb = topRgb?.takeIf { it.size >= 3 },
                    bottomRgb = bottomRgb?.takeIf { it.size >= 3 },
                    jacketRgb = jacketRgb?.takeIf { it.size >= 3 },
                    shoesRgb = shoesRgb?.takeIf { it.size >= 3 },
                    jewelryRgb = jewelryRgb?.takeIf { it.size >= 3 },
                    sunglassesRgb = sunglassesRgb?.takeIf { it.size >= 3 },
                    bagRgb = bagRgb?.takeIf { it.size >= 3 }
                )
                prependMyOutfitToCache(userId, outfit)
                complete(true, null)
                saveOutfitToFirestore(outfit) { ok, err ->
                    if (ok) {
                        outfitFirestoreRetryCounts.remove(outfit.id)
                    } else {
                        scheduleOutfitFirestoreSync(outfit)
                    }
                }
            }
            .addOnFailureListener { e ->
                mainHandler.removeCallbacks(timeout)
                complete(false, e.message ?: e.javaClass.simpleName)
            }
    }

    private fun copyUriToCacheFile(context: Context, uri: Uri): File? {
        return try {
            val mime = context.contentResolver.getType(uri).orEmpty()
            val ext = when {
                mime.contains("png") -> "png"
                mime.contains("webp") -> "webp"
                else -> "jpg"
            }
            val dest = File(context.cacheDir, "outfit_upload_${UUID.randomUUID()}.$ext")
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            dest
        } catch (_: Exception) {
            null
        }
    }

    private fun guessImageContentType(context: Context, uri: Uri, fileName: String): String {
        val mime = context.contentResolver.getType(uri)
        if (!mime.isNullOrBlank()) return mime
        return when {
            fileName.endsWith(".png", ignoreCase = true) -> "image/png"
            fileName.endsWith(".webp", ignoreCase = true) -> "image/webp"
            else -> "image/jpeg"
        }
    }

    /** Writes only known fields and RGB lists — never `*ColorHex` keys. */
    private fun outfitToFirestoreMap(o: Outfit): HashMap<String, Any> {
        val m = HashMap<String, Any>()
        m["id"] = o.id
        m["userId"] = o.userId
        m["imageUrl"] = o.imageUrl
        m["timestamp"] = o.timestamp
        m["top"] = o.top
        m["bottom"] = o.bottom
        m["jacket"] = o.jacket
        m["shoes"] = o.shoes
        m["jewelry"] = o.jewelry
        m["sunglasses"] = o.sunglasses
        m["bag"] = o.bag
        m["vibe"] = o.vibe
        m["vibeSearchKey"] = o.vibeSearchKey
        o.topRgb?.takeIf { it.size >= 3 }?.let { m["topRgb"] = it }
        o.bottomRgb?.takeIf { it.size >= 3 }?.let { m["bottomRgb"] = it }
        o.jacketRgb?.takeIf { it.size >= 3 }?.let { m["jacketRgb"] = it }
        o.shoesRgb?.takeIf { it.size >= 3 }?.let { m["shoesRgb"] = it }
        o.jewelryRgb?.takeIf { it.size >= 3 }?.let { m["jewelryRgb"] = it }
        o.sunglassesRgb?.takeIf { it.size >= 3 }?.let { m["sunglassesRgb"] = it }
        o.bagRgb?.takeIf { it.size >= 3 }?.let { m["bagRgb"] = it }
        return m
    }

    private fun saveOutfitToFirestore(outfit: Outfit, onResult: (Boolean, String?) -> Unit) {
        db.collection(AppConfig.COLL_OUTFITS).document(outfit.id).set(outfitToFirestoreMap(outfit))
            .addOnSuccessListener {
                addGlobalVibeBestEffort(outfit.vibe)
                onResult(true, null)
            }
            .addOnFailureListener { onResult(false, it.message) }
    }

    private fun addGlobalVibeBestEffort(vibe: String) {
        if (vibe.isBlank()) return
        db.collection(AppConfig.COLL_APP_META).document(AppConfig.DOC_GLOBAL_VIBES)
            .set(
                mapOf(AppConfig.FIELD_VALUES to com.google.firebase.firestore.FieldValue.arrayUnion(vibe)),
                SetOptions.merge()
            )
    }

    private fun mergeDistinctCaseInsensitive(a: List<String>, b: List<String>): List<String> {
        val out = ArrayList<String>(a.size + b.size)
        fun addIfMissing(value: String) {
            if (out.none { it.equals(value, ignoreCase = true) }) out.add(value)
        }
        a.forEach { addIfMissing(it) }
        b.forEach { addIfMissing(it) }
        return out
    }

    fun deleteOutfit(outfitId: String, imageUrl: String, onResult: (Boolean) -> Unit) {
        db.collection(AppConfig.COLL_OUTFITS).document(outfitId).delete()
            .addOnSuccessListener {
                try {
                    val imageRef = storage.getReferenceFromUrl(imageUrl)
                    imageRef.delete()
                        .addOnSuccessListener { onResult(true) }
                        .addOnFailureListener { onResult(false) }
                } catch (e: Exception) {
                    onResult(true)
                }
            }
            .addOnFailureListener { onResult(false) }
    }

    fun observeAllOutfits(onResult: (List<Outfit>?, String?) -> Unit): ListenerRegistration {
        return db.collection(AppConfig.COLL_OUTFITS)
            .orderBy(AppConfig.FIELD_TIMESTAMP, Query.Direction.DESCENDING)
            .addSnapshotListener { value, error ->
                if (error != null) return@addSnapshotListener onResult(null, error.message)
                val list = decodeOutfits(value).filter { it.userId != currentUserId }
                rememberedFeedOutfits = list
                onResult(list, null)
            }
    }

    fun observeOutfitsByVibe(vibeQuery: String, onResult: (List<Outfit>?, String?) -> Unit): ListenerRegistration {
        val normalized = vibeQuery.trim()
        val prefixes = linkedSetOf(
            normalized,
            normalized.lowercase(Locale.ROOT),
            normalized.uppercase(Locale.ROOT),
            normalized.split(Regex("\\s+"))
                .joinToString(" ") { part ->
                    part.lowercase(Locale.ROOT).replaceFirstChar { c -> c.titlecase(Locale.ROOT) }
                }
        ).filter { it.isNotBlank() }.toList()

        val latestByPrefix = HashMap<String, List<Outfit>>()
        val regs = ArrayList<ListenerRegistration>()

        fun emitMerged() {
            val merged = LinkedHashMap<String, Outfit>()
            latestByPrefix.values.flatten().forEach { merged[it.id] = it }
            val filtered = merged.values
                .filter { it.userId != currentUserId }
                .sortedByDescending { it.timestamp }
            onResult(filtered, null)
        }

        prefixes.forEach { prefix ->
            val reg = db.collection(AppConfig.COLL_OUTFITS)
                .orderBy(AppConfig.FIELD_VIBE)
                .startAt(prefix)
                .endAt(prefix + "\uf8ff")
                .addSnapshotListener { value, error ->
                    if (error != null) return@addSnapshotListener onResult(null, error.message)
                    latestByPrefix[prefix] = decodeOutfits(value)
                    emitMerged()
                }
            regs.add(reg)
        }

        return ListenerRegistration {
            regs.forEach { it.remove() }
            regs.clear()
            latestByPrefix.clear()
        }
    }

    /**
     * Live updates for [AppConfig.FIELD_FEED_FILTERS] and [AppConfig.FIELD_PERSONAL_PALETTE] on the signed-in user.
     */
    fun observeUserFeedState(
        onResult: (filters: FeedFilters, palette: PersonalPalette?) -> Unit
    ): ListenerRegistration? {
        val uid = currentUserId ?: return null
        return db.collection(AppConfig.COLL_USERS).document(uid)
            .addSnapshotListener(MetadataChanges.INCLUDE) { snap, _ ->
                if (snap == null) return@addSnapshotListener
                onFirestoreUserReadyFromSnapshot(uid, snap)
                val filterMap = coerceStringKeyMap(snap.get(AppConfig.FIELD_FEED_FILTERS))
                val profile = effectiveUserProfile(snap)
                rememberUserProfile(profile)
                applyLikedIdsFromUserDocument(snap)
                onResult(FeedFilters.fromFirestore(filterMap), profile.palette)
            }
    }

    private fun likedIdsFromSnapshot(snap: DocumentSnapshot): List<String> {
        if (!snap.exists()) return emptyList()
        val raw: Any? = snap.get(AppConfig.FIELD_LIKED_OUTFIT_IDS)
            ?: snap.data?.get(AppConfig.FIELD_LIKED_OUTFIT_IDS)
        return when (raw) {
            is List<*> -> raw.mapNotNull { it?.toString()?.trim()?.takeIf { id -> id.isNotEmpty() } }
            is String -> raw.trim().takeIf { it.isNotEmpty() }?.let { listOf(it) } ?: emptyList()
            else -> emptyList()
        }
    }

    /**
     * Stable key for comparing local vs last successful cloud write.
     */
    private fun likedIdsToSortedCsv(ids: Collection<String>): String =
        ids.map { it.trim() }.filter { it.isNotEmpty() }.sorted().joinToString(",")

    private fun readLikedIdsFromPrefsOnly(uid: String): Set<String> {
        val raw = likesPrefs().getString(PREFS_KEY_PREFIX + uid, null) ?: return emptySet()
        if (raw.isEmpty()) return emptySet()
        return raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    /** In-memory likes plus anything persisted locally for this uid. */
    private fun localLikedIdsSet(uid: String): Set<String> {
        val fromMemory = if (rememberedLikedIdsUid == uid) rememberedLikedIds.toSet() else emptySet()
        return fromMemory + readLikedIdsFromPrefsOnly(uid)
    }

    /** True when local/prefs differ from the last Firestore write we confirmed. */
    private fun hasPendingLikeChanges(uid: String): Boolean {
        val localCsv = likedIdsToSortedCsv(localLikedIdsSet(uid))
        if (lastCloudSyncedLikedCsv == null) return localCsv.isNotEmpty()
        return localCsv != lastCloudSyncedLikedCsv
    }

    private fun commitLikedIds(uid: String, ids: Collection<String>) {
        val copy = ids.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        rememberedLikedIdsUid = uid
        rememberedLikedIds.clear()
        rememberedLikedIds.addAll(copy)
        persistLikedIdsForUser(uid)
    }

    /** Prefs are the on-device source of truth; memory is a hot cache on top. */
    private fun authoritativeLikedIds(uid: String): List<String> {
        restoreLikedIdsFromPrefs(uid)
        return localLikedIdsSet(uid).sorted()
    }

    /**
     * Pulls `likedOutfitIds` from Firestore into prefs when the server is ahead and in sync.
     * Never replaces local likes with an empty server snapshot (the main Favorites bug).
     */
    private fun mergeServerLikedIdsFromUserDocument(snap: DocumentSnapshot) {
        val uid = currentUserId ?: return
        if (!snap.exists()) return
        val data = snap.data ?: return
        if (!data.containsKey(AppConfig.FIELD_LIKED_OUTFIT_IDS)) {
            android.util.Log.d(
                LIKE_LOG_TAG,
                "mergeLikedIds: field missing on users/$uid — keeping ${authoritativeLikedIds(uid)}"
            )
            return
        }
        restoreLikedIdsFromPrefs(uid)
        val serverIds = likedIdsFromSnapshot(snap).toSet()
        val local = readLikedIdsFromPrefsOnly(uid)
        if (serverIds.isEmpty()) {
            if (local.isNotEmpty()) {
                android.util.Log.d(
                    LIKE_LOG_TAG,
                    "mergeLikedIds: ignore empty server snapshot; local=$local"
                )
                scheduleCloudLikeSync(delayMs = 0L)
            }
            return
        }
        if (hasPendingLikeChanges(uid)) {
            android.util.Log.d(
                LIKE_LOG_TAG,
                "mergeLikedIds: pending local=$local — push to cloud, ignore server=$serverIds"
            )
            scheduleCloudLikeSync(delayMs = 0L)
            return
        }
        if (serverIds != local) {
            commitLikedIds(uid, serverIds.sorted())
            android.util.Log.i(
                LIKE_LOG_TAG,
                "mergeLikedIds: applied server ids=$serverIds (was local=$local)"
            )
        }
    }

    private fun applyLikedIdsFromUserDocument(snap: DocumentSnapshot) {
        mergeServerLikedIdsFromUserDocument(snap)
    }

    private fun currentLikedOutfitIds(): List<String> {
        val uid = currentUserId ?: return emptyList()
        return authoritativeLikedIds(uid)
    }

    private fun deliverFavoriteOutfits(
        userId: String,
        list: List<Outfit>,
        onResult: (List<Outfit>?, String?) -> Unit
    ) {
        val sorted = list.sortedByDescending { it.timestamp }
        rememberedFavoritesUid = userId
        rememberedFavorites = sorted
        mainHandler.post { onResult(sorted, null) }
    }

    private fun fetchOutfitsByDocIds(
        ids: List<String>,
        onDone: (List<Outfit>, String?) -> Unit
    ) {
        val distinct = ids.distinct().filter { it.isNotBlank() }
        if (distinct.isEmpty()) {
            onDone(emptyList(), null)
            return
        }
        val results = Collections.synchronizedList(mutableListOf<Outfit>())
        var pending = distinct.size
        var firstError: String? = null
        for (id in distinct) {
            db.collection(AppConfig.COLL_OUTFITS).document(id).get()
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        decodeOutfit(task.result)?.let { results.add(it) }
                            ?: android.util.Log.w(LIKE_LOG_TAG, "fetchByDocId: no doc for id=$id")
                    } else if (firstError == null) {
                        firstError = task.exception?.message
                        android.util.Log.e(LIKE_LOG_TAG, "fetchByDocId failed id=$id: $firstError")
                    }
                    pending--
                    if (pending == 0) {
                        onDone(results, firstError)
                    }
                }
        }
    }

    private fun loadOutfitsForLikedIds(
        userId: String,
        ids: List<String>,
        onResult: (List<Outfit>?, String?) -> Unit
    ) {
        val distinctIds = ids.distinct().filter { it.isNotBlank() }
        if (distinctIds.isEmpty()) {
            android.util.Log.i(LIKE_LOG_TAG, "loadOutfitsForLikedIds: no ids — empty wishlist")
            rememberedFavoritesUid = userId
            rememberedFavorites = emptyList()
            mainHandler.post { onResult(emptyList(), null) }
            return
        }

        val fromFeed = rememberedFeedOutfits.orEmpty().filter { it.id in distinctIds }
        if (fromFeed.isNotEmpty()) {
            android.util.Log.i(
                LIKE_LOG_TAG,
                "loadOutfitsForLikedIds: ${fromFeed.size} outfit(s) from feed cache ids=$distinctIds"
            )
            deliverFavoriteOutfits(userId, fromFeed, onResult)
        }

        val queryIds = distinctIds.take(10)
        android.util.Log.i(LIKE_LOG_TAG, "loadOutfitsForLikedIds: whereIn documentId $queryIds")
        favoritesOutfitsListener?.remove()
        favoritesOutfitsListener = db.collection(AppConfig.COLL_OUTFITS)
            .whereIn(FieldPath.documentId(), queryIds)
            .addSnapshotListener(MetadataChanges.INCLUDE) { outfitsSnap, outfitsErr ->
                if (outfitsErr != null) {
                    android.util.Log.e(LIKE_LOG_TAG, "whereIn error: ${outfitsErr.message}")
                    if (fromFeed.isEmpty()) onResult(null, outfitsErr.message)
                    return@addSnapshotListener
                }
                val fromQuery = try {
                    decodeOutfits(outfitsSnap)
                } catch (e: Exception) {
                    android.util.Log.e(LIKE_LOG_TAG, "whereIn decode failed", e)
                    if (fromFeed.isEmpty()) onResult(null, e.message)
                    return@addSnapshotListener
                }
                val foundIds = fromQuery.map { it.id }.toSet()
                val missing = distinctIds.filter { it !in foundIds }
                android.util.Log.i(
                    LIKE_LOG_TAG,
                    "whereIn returned ${fromQuery.size}/${distinctIds.size} " +
                        "missing=$missing cache=${outfitsSnap?.metadata?.isFromCache}"
                )
                if (missing.isEmpty()) {
                    deliverFavoriteOutfits(userId, fromQuery, onResult)
                    return@addSnapshotListener
                }
                fetchOutfitsByDocIds(missing) { extra, fetchErr ->
                    val combined = (fromQuery + extra).distinctBy { it.id }
                    android.util.Log.i(
                        LIKE_LOG_TAG,
                        "loadOutfitsForLikedIds: combined ${combined.size} (query=${fromQuery.size} fetch=${extra.size})"
                    )
                    if (combined.isEmpty() && fromFeed.isEmpty()) {
                        onResult(emptyList(), fetchErr)
                    } else if (combined.isNotEmpty()) {
                        deliverFavoriteOutfits(userId, combined, onResult)
                    }
                }
            }
    }

    fun saveFeedFilters(filters: FeedFilters, onResult: (Boolean, String?) -> Unit) {
        val uid = currentUserId ?: return onResult(false, "User not logged in")
        val payload = hashMapOf<String, Any>(
            AppConfig.FIELD_FEED_FILTERS to filters.toFirestoreMap()
        )
        val doc = db.collection(AppConfig.COLL_USERS).document(uid)
        doc.set(payload, SetOptions.merge())
            .addOnSuccessListener { onResult(true, null) }
            .addOnFailureListener { e ->
                onResult(false, e.message ?: e.javaClass.simpleName)
            }
    }

    data class UserProfileSnapshot(
        val fullName: String?,
        val profileImageUrl: String?,
        val palette: PersonalPalette?
    )

    /** Keep Firestore list/map types intact — deep coercion breaks swatch arrays on some devices. */
    private fun paletteMapFromSnapshot(snap: DocumentSnapshot): Map<String, Any>? {
        val raw: Any? = snap.data?.get(AppConfig.FIELD_PERSONAL_PALETTE)
            ?: snap.data?.get("personalPalette")
            ?: snap.get(AppConfig.FIELD_PERSONAL_PALETTE)
            ?: snap.get("personalPalette")
        val m = raw as? Map<*, *> ?: return null
        val out = HashMap<String, Any>(m.size)
        for ((k, v) in m) {
            if (k != null && v != null) out[k.toString()] = v
        }
        return out
    }

    private fun paletteFromUserSnapshot(snap: DocumentSnapshot): PersonalPalette? {
        val paletteMap = paletteMapFromSnapshot(snap) ?: return null
        val parsed = PersonalPalette.fromFirestore(paletteMap)
        if (parsed == null) {
            android.util.Log.w(
                PROFILE_LOG_TAG,
                "personalPalette parse failed; keys=${paletteMap.keys.sorted()}"
            )
        }
        return parsed
    }

    /** True when a cached user doc likely predates palette/photo writes (common right after signup). */
    private fun isStaleProfileCache(snap: DocumentSnapshot, profile: UserProfileSnapshot): Boolean {
        if (!snap.metadata.isFromCache) return false
        return profile.palette == null || profile.profileImageUrl.isNullOrBlank()
    }

    /** Merge signup/feed memory with Firestore so new users are not blanked by an empty local cache doc. */
    private fun effectiveUserProfile(snap: DocumentSnapshot): UserProfileSnapshot {
        val fromSnap = userProfileFromSnapshot(snap)
        if (!snap.exists() || !isStaleProfileCache(snap, fromSnap)) return fromSnap
        val mem = peekRememberedUserProfile() ?: return fromSnap
        return UserProfileSnapshot(
            fullName = fromSnap.fullName?.takeIf { it.isNotBlank() } ?: mem.fullName,
            profileImageUrl = fromSnap.profileImageUrl?.takeIf { it.isNotBlank() } ?: mem.profileImageUrl,
            palette = fromSnap.palette ?: mem.palette
        )
    }

    private fun userDocMergePayload(likedIds: List<String>): HashMap<String, Any> {
        val uid = currentUserId ?: return hashMapOf()
        val payload = hashMapOf<String, Any>(
            "uid" to uid,
            AppConfig.FIELD_LIKED_OUTFIT_IDS to likedIds
        )
        peekRememberedUserProfile()?.let { profile ->
            profile.fullName?.takeIf { it.isNotBlank() }?.let { payload["fullName"] = it }
            profile.profileImageUrl?.takeIf { it.isNotBlank() }?.let { payload["profileImageUrl"] = it }
            profile.palette?.let {
                payload[AppConfig.FIELD_PERSONAL_PALETTE] = PersonalPalette.toFirestoreMap(it)
            }
        }
        return payload
    }

    /**
     * Call from [RegisterActivity] after palette/photo writes, before opening the main app.
     * Ensures profile, feed palette filter, and likes work without a cold restart.
     */
    fun seedPostRegistrationSession(
        fullName: String,
        email: String,
        palette: PersonalPalette,
        profileImageUrl: String?
    ) {
        val uid = currentUserId ?: return
        registrationSeededUid = uid
        val photo = profileImageUrl?.takeIf { it.isNotBlank() }
            ?: peekRememberedUserProfile()?.profileImageUrl
        rememberUserProfile(
            UserProfileSnapshot(
                fullName = fullName.takeIf { it.isNotBlank() },
                profileImageUrl = photo,
                palette = palette
            )
        )
        firestoreUserReadyUid = uid
        rememberedLikedIdsUid = uid
        restoreLikedIdsFromPrefs(uid)
        rememberedMyOutfitsUid = uid
        if (rememberedMyOutfits == null) {
            rememberedMyOutfits = emptyList()
        }
        android.util.Log.i(
            PROFILE_LOG_TAG,
            "Seeded post-registration uid=$uid name=$fullName photo=${!photo.isNullOrBlank()} " +
                "palette=true email=${email.isNotBlank()}"
        )
    }

    private fun prependMyOutfitToCache(userId: String, outfit: Outfit) {
        rememberedMyOutfitsUid = userId
        val current = rememberedMyOutfits?.toMutableList() ?: mutableListOf()
        current.removeAll { it.id == outfit.id }
        current.add(outfit)
        rememberedMyOutfits = current.sortedByDescending { it.timestamp }
        android.util.Log.i(
            OUTFIT_LOG_TAG,
            "Cached my outfit id=${outfit.id} image=${!outfit.imageUrl.isBlank()}"
        )
    }

    private fun scheduleOutfitFirestoreSync(outfit: Outfit) {
        val attempts = outfitFirestoreRetryCounts.getOrDefault(outfit.id, 0)
        if (attempts >= OUTFIT_FIRESTORE_MAX_RETRIES) {
            android.util.Log.e(OUTFIT_LOG_TAG, "Outfit Firestore sync gave up id=${outfit.id}")
            return
        }
        outfitFirestoreRetryCounts[outfit.id] = attempts + 1
        mainHandler.postDelayed({
            saveOutfitToFirestore(outfit) { ok, err ->
                if (ok) {
                    outfitFirestoreRetryCounts.remove(outfit.id)
                    android.util.Log.i(OUTFIT_LOG_TAG, "Outfit Firestore sync OK id=${outfit.id}")
                } else {
                    android.util.Log.w(
                        OUTFIT_LOG_TAG,
                        "Outfit Firestore sync retry ${attempts + 1}: ${err ?: "unknown"}"
                    )
                    scheduleOutfitFirestoreSync(outfit)
                }
            }
        }, OUTFIT_FIRESTORE_RETRY_MS)
    }

    private fun applyMyOutfitsFromSnapshot(
        userId: String,
        snapshot: QuerySnapshot?,
        onResult: (List<Outfit>?, String?) -> Unit
    ) {
        if (snapshot == null) return
        try {
            val fromFirestore = decodeOutfits(snapshot)
            if (fromFirestore.isEmpty() && snapshot.metadata.isFromCache) {
                val mem = if (rememberedMyOutfitsUid == userId) rememberedMyOutfits else null
                if (!mem.isNullOrEmpty()) {
                    android.util.Log.i(
                        OUTFIT_LOG_TAG,
                        "My outfits: keeping ${mem.size} cached (stale empty Firestore cache)"
                    )
                    onResult(mem, null)
                    return
                }
            }
            val merged = mergeMyOutfitsLists(userId, fromFirestore)
            rememberedMyOutfitsUid = userId
            rememberedMyOutfits = merged
            onResult(merged, null)
        } catch (e: Exception) {
            onResult(null, e.message)
        }
    }

    private fun mergeMyOutfitsLists(userId: String, firestoreList: List<Outfit>): List<Outfit> {
        val mem = if (rememberedMyOutfitsUid == userId) rememberedMyOutfits else null
        if (mem.isNullOrEmpty()) return firestoreList
        val ids = firestoreList.map { it.id }.toSet()
        val onlyInMem = mem.filter { it.id !in ids }
        if (onlyInMem.isEmpty()) return firestoreList
        return (onlyInMem + firestoreList).sortedByDescending { it.timestamp }
    }

    fun userProfileFromSnapshot(snap: DocumentSnapshot): UserProfileSnapshot {
        if (!snap.exists()) return UserProfileSnapshot(null, null, null)
        val data = snap.data
        if (data != null) {
            android.util.Log.d(PROFILE_LOG_TAG, "user doc keys=${data.keys.sorted()}")
        }
        val photo = snap.getString("profileImageUrl")
            ?: data?.get("profileImageUrl") as? String
        val name = snap.getString("fullName")
            ?: data?.get("fullName") as? String
        return UserProfileSnapshot(
            fullName = name,
            profileImageUrl = photo,
            palette = paletteFromUserSnapshot(snap)
        )
    }

    fun clearProfileListeners() {
        cancelInFlightProfileLoad()
        clearRememberedUserProfile()
        clearRememberedOutfitsAndFavorites()
        clearMyOutfitsListener()
        clearFavoritesListeners()
        clearUserProfileListener()
    }

    private fun clearRememberedUserProfile() {
        rememberedProfileUid = null
        rememberedProfile = null
    }

    private fun clearRememberedOutfitsAndFavorites() {
        rememberedMyOutfitsUid = null
        rememberedMyOutfits = null
        rememberedFavoritesUid = null
        rememberedFavorites = null
        rememberedFeedOutfits = null
        rememberedLikedIdsUid = null
        rememberedLikedIds.clear()
        firestoreUserReadyUid = null
        registrationSeededUid = null
        lastCloudSyncedLikedCsv = null
        outfitFirestoreRetryCounts.clear()
        cancelScheduledLikeCloudSync()
        currentUserId?.let { clearLikedIdsPrefs(it) }
    }

    /** Call after registration or when the main feed opens so likes can sync to Firestore. */
    fun markFirestoreUserReady() {
        val uid = currentUserId ?: return
        if (firestoreUserReadyUid != uid) {
            android.util.Log.d(LIKE_LOG_TAG, "markFirestoreUserReady uid=$uid")
        }
        firestoreUserReadyUid = uid
        restoreLikedIdsFromPrefs(uid)
        scheduleCloudLikeSync(delayMs = 0L)
    }

    /** Same as [markFirestoreUserReady] — use from activities that create their own [OutfitRepository]. */
    fun ensureFirestoreUserReady() = markFirestoreUserReady()

    private fun onFirestoreUserReadyFromSnapshot(uid: String, snap: DocumentSnapshot) {
        if (!snap.exists()) return
        firestoreUserReadyUid = uid
        if (snap.metadata.isFromCache) {
            scheduleCloudLikeSync(delayMs = 0L)
            return
        }
        val profile = effectiveUserProfile(snap)
        if (profile.palette != null && !profile.profileImageUrl.isNullOrBlank()) {
            registrationSeededUid = null
        }
        scheduleCloudLikeSync(delayMs = 0L)
    }

    private fun cancelScheduledLikeCloudSync() {
        likeSyncDebounceRunnable?.let { likeSyncHandler.removeCallbacks(it) }
        likeSyncDebounceRunnable = null
        likeSyncTimeoutRunnable?.let { likeSyncHandler.removeCallbacks(it) }
        likeSyncTimeoutRunnable = null
    }

    /** One debounced Firestore write — avoids overlapping [set] calls that hang on new accounts. */
    private fun scheduleCloudLikeSync(delayMs: Long = LIKE_SYNC_DEBOUNCE_MS) {
        likeSyncDebounceRunnable?.let { likeSyncHandler.removeCallbacks(it) }
        likeSyncDebounceRunnable = Runnable {
            likeSyncDebounceRunnable = null
            performCloudLikeSync()
        }
        if (delayMs <= 0L) {
            likeSyncHandler.post(likeSyncDebounceRunnable!!)
        } else {
            likeSyncHandler.postDelayed(likeSyncDebounceRunnable!!, delayMs)
        }
    }

    private fun performCloudLikeSync() {
        val userId = currentUserId ?: return
        if (firestoreUserReadyUid != userId) {
            android.util.Log.d(LIKE_LOG_TAG, "Cloud sync deferred — user doc not ready yet")
            scheduleCloudLikeSync(2_000L)
            return
        }
        if (likeSyncInFlight) {
            scheduleCloudLikeSync(LIKE_SYNC_DEBOUNCE_MS)
            return
        }
        val ids = ArrayList(localLikedIdsSet(userId))
        val csv = likedIdsToSortedCsv(ids)
        if (csv == lastCloudSyncedLikedCsv) {
            android.util.Log.d(LIKE_LOG_TAG, "Cloud sync skipped (unchanged): $ids")
            return
        }
        if (ids.isEmpty() && lastCloudSyncedLikedCsv.isNullOrEmpty()) {
            android.util.Log.d(LIKE_LOG_TAG, "Cloud sync skipped (no likes, never synced)")
            return
        }

        likeSyncInFlight = true
        val userRef = db.collection(AppConfig.COLL_USERS).document(userId)
        val payload = userDocMergePayload(ids)

        likeSyncTimeoutRunnable?.let { likeSyncHandler.removeCallbacks(it) }
        likeSyncTimeoutRunnable = Runnable {
            if (!likeSyncInFlight) return@Runnable
            likeSyncInFlight = false
            android.util.Log.w(LIKE_LOG_TAG, "Cloud sync timeout ids=$ids — retry in 3s")
            scheduleCloudLikeSync(3_000L)
        }
        likeSyncHandler.postDelayed(likeSyncTimeoutRunnable!!, LIKE_WRITE_TIMEOUT_MS)

        runWithFreshAuthToken(
            onReady = {
                db.enableNetwork().addOnCompleteListener {
                    userRef.set(payload, SetOptions.merge())
                        .addOnCompleteListener { task ->
                            likeSyncTimeoutRunnable?.let { likeSyncHandler.removeCallbacks(it) }
                            likeSyncInFlight = false
                            if (task.isSuccessful) {
                                lastCloudSyncedLikedCsv = csv
                                android.util.Log.i(
                                    LIKE_LOG_TAG,
                                    "Cloud likedOutfitIds OK: $ids"
                                )
                            } else {
                                android.util.Log.e(
                                    LIKE_LOG_TAG,
                                    "Cloud sync failed: ${task.exception?.message}"
                                )
                                scheduleCloudLikeSync(3_000L)
                            }
                        }
                }
            },
            onError = { message ->
                likeSyncTimeoutRunnable?.let { likeSyncHandler.removeCallbacks(it) }
                likeSyncInFlight = false
                android.util.Log.e(LIKE_LOG_TAG, "Cloud sync aborted: $message")
            }
        )
    }

    private fun likesPrefs() =
        App.instance.getSharedPreferences(PREFS_LIKES, Context.MODE_PRIVATE)

    private fun persistLikedIdsForUser(uid: String) {
        val csv = likedIdsToSortedCsv(rememberedLikedIds)
        likesPrefs().edit().putString(PREFS_KEY_PREFIX + uid, csv).apply()
        android.util.Log.d(LIKE_LOG_TAG, "persistLikedIds prefs[$uid]=$csv")
    }

    private fun restoreLikedIdsFromPrefs(uid: String) {
        val fromPrefs = readLikedIdsFromPrefsOnly(uid)
        if (fromPrefs.isEmpty()) {
            android.util.Log.d(LIKE_LOG_TAG, "restoreLikedIdsFromPrefs[$uid]: (empty)")
            return
        }
        if (rememberedLikedIdsUid != uid) {
            rememberedLikedIds.clear()
            rememberedLikedIdsUid = uid
        }
        val before = rememberedLikedIds.size
        rememberedLikedIds.addAll(fromPrefs)
        android.util.Log.d(
            LIKE_LOG_TAG,
            "restoreLikedIdsFromPrefs[$uid]: prefs=$fromPrefs memory=$before->${rememberedLikedIds.size}"
        )
    }

    private fun clearLikedIdsPrefs(uid: String) {
        likesPrefs().edit().remove(PREFS_KEY_PREFIX + uid).apply()
    }

    private fun runWithFreshAuthToken(onReady: () -> Unit, onError: (String) -> Unit) {
        val user = auth.currentUser ?: return onError("Not signed in")
        user.getIdToken(true)
            .addOnSuccessListener {
                android.util.Log.d(LIKE_LOG_TAG, "Auth token ready for Firestore write")
                onReady()
            }
            .addOnFailureListener { e ->
                android.util.Log.w(LIKE_LOG_TAG, "getIdToken failed: ${e.message}; retrying write anyway")
                onReady()
            }
    }

    /**
     * Updates local like state immediately, then syncs `likedOutfitIds` to Firestore in the background.
     */
    private fun writeLikeToUserDocument(
        outfitId: String,
        isLiked: Boolean,
        applyOptimisticCache: Boolean,
        onResult: (Boolean, String?) -> Unit
    ) {
        val userId = currentUserId ?: return onResult(false, "Not signed in")
        restoreLikedIdsFromPrefs(userId)

        if (applyOptimisticCache) {
            rememberedLikedIdsUid = userId
            if (isLiked) rememberedLikedIds.add(outfitId) else rememberedLikedIds.remove(outfitId)
            commitLikedIds(userId, rememberedLikedIds)
            android.util.Log.i(
                LIKE_LOG_TAG,
                "Like ${if (isLiked) "ADD" else "REMOVE"} outfitId=$outfitId " +
                    "likedOutfitIds=${currentLikedOutfitIds()} prefs=${readLikedIdsFromPrefsOnly(userId)}"
            )
        }

        scheduleCloudLikeSync()
        android.util.Log.i(
            LIKE_LOG_TAG,
            "Like local OK uid=$userId outfitId=$outfitId liked=$isLiked all=${currentLikedOutfitIds()}"
        )
        onResult(true, null)
    }

    fun clearFavoritesListeners() {
        favoritesListener?.remove()
        favoritesListener = null
        favoritesOutfitsListener?.remove()
        favoritesOutfitsListener = null
        likedIdsListener?.remove()
        likedIdsListener = null
    }

    /** Sync read — use instead of [isOutfitLiked] (blocking [get] hangs after signup). */
    fun isOutfitLikedCached(outfitId: String): Boolean {
        val uid = currentUserId ?: return false
        return rememberedLikedIdsUid == uid && rememberedLikedIds.contains(outfitId)
    }

    /**
     * Keeps [rememberedLikedIds] warm from `users/{uid}.likedOutfitIds` (not the favorites subcollection).
     */
    fun observeLikedOutfitIds(onIds: (Set<String>) -> Unit): ListenerRegistration? {
        val userId = currentUserId ?: return null
        restoreLikedIdsFromPrefs(userId)
        mainHandler.post {
            val initial = currentLikedOutfitIds().toSet()
            android.util.Log.d(LIKE_LOG_TAG, "observeLikedOutfitIds initial=$initial")
            onIds(initial)
        }
        likedIdsListener?.remove()
        likedIdsListener = db.collection(AppConfig.COLL_USERS)
            .document(userId)
            .addSnapshotListener(MetadataChanges.INCLUDE) { snap, error ->
                if (error != null) {
                    android.util.Log.e(LIKE_LOG_TAG, "likedOutfitIds listener error: ${error.message}")
                    return@addSnapshotListener
                }
                if (snap == null) return@addSnapshotListener
                applyLikedIdsFromUserDocument(snap)
                val ids = currentLikedOutfitIds().toSet()
                android.util.Log.d(LIKE_LOG_TAG, "observeLikedOutfitIds emit=$ids")
                mainHandler.post { onIds(ids) }
            }
        return likedIdsListener
    }

    private fun rememberUserProfile(profile: UserProfileSnapshot) {
        val uid = currentUserId ?: return
        if (profile.fullName.isNullOrBlank() && profile.profileImageUrl.isNullOrBlank() &&
            profile.palette == null
        ) {
            return
        }
        rememberedProfileUid = uid
        rememberedProfile = profile
    }

    private fun peekRememberedUserProfile(): UserProfileSnapshot? {
        val uid = currentUserId ?: return null
        return if (rememberedProfileUid == uid) rememberedProfile else null
    }

    /** In-memory profile from feed/signup; safe to call on the main thread. */
    fun getCachedUserProfile(): UserProfileSnapshot? = peekRememberedUserProfile()

    fun clearUserProfileListener() {
        userProfileListener?.remove()
        userProfileListener = null
    }

    private fun cancelInFlightProfileLoad() {
        profileLoadTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        profileLoadTimeoutRunnable = null
        profileLoadListener?.remove()
        profileLoadListener = null
    }

    fun clearMyOutfitsListener() {
        myOutfitsListener?.remove()
        myOutfitsListener = null
        myOutfitsListenerUid = null
    }

    /** Live profile updates (cache + server). Used by [ProfileActivity]. */
    fun observeUserProfile(
        onResult: (UserProfileSnapshot) -> Unit,
        onError: (String) -> Unit
    ) {
        val uid = currentUserId ?: run {
            onError("User not logged in")
            return
        }
        peekRememberedUserProfile()?.let { onResult(it) }
        val doc = db.collection(AppConfig.COLL_USERS).document(uid)
        userProfileListener?.remove()
        userProfileListener = doc.addSnapshotListener(MetadataChanges.INCLUDE) { snap, error ->
            if (error != null) {
                onError(firestoreErrorMessage(error))
                return@addSnapshotListener
            }
            if (snap == null || !snap.exists()) return@addSnapshotListener
            try {
                val profile = effectiveUserProfile(snap)
                rememberUserProfile(profile)
                onResult(profile)
            } catch (e: Exception) {
                onError(firestoreErrorMessage(e))
            }
        }
    }

    private fun coerceRgbList(value: Any?): List<Long>? {
        val list = value as? List<*> ?: return null
        if (list.size < 3) return null
        return list.take(3).map { (it as Number).toLong() }
    }

    private fun decodeOutfit(doc: DocumentSnapshot): Outfit? {
        if (!doc.exists()) return null
        return try {
            Outfit(
                // Always use the Firestore document id — the `id` field can disagree on legacy docs.
                id = doc.id,
                userId = doc.getString(AppConfig.FIELD_USER_ID).orEmpty(),
                imageUrl = normalizeStorageUrl(doc.getString(AppConfig.FIELD_IMAGE_URL)).orEmpty(),
                timestamp = doc.getLong(AppConfig.FIELD_TIMESTAMP) ?: 0L,
                top = doc.getString(AppConfig.FIELD_TOP).orEmpty(),
                bottom = doc.getString(AppConfig.FIELD_BOTTOM).orEmpty(),
                jacket = doc.getString(AppConfig.FIELD_JACKET).orEmpty(),
                shoes = doc.getString(AppConfig.FIELD_SHOES).orEmpty(),
                jewelry = doc.getString(AppConfig.FIELD_JEWELRY).orEmpty(),
                sunglasses = doc.getString(AppConfig.FIELD_SUNGLASSES).orEmpty(),
                bag = doc.getString(AppConfig.FIELD_BAG).orEmpty(),
                vibe = doc.getString(AppConfig.FIELD_VIBE).orEmpty(),
                vibeSearchKey = doc.getString(AppConfig.FIELD_VIBE_SEARCH_KEY).orEmpty(),
                topRgb = coerceRgbList(doc.get(AppConfig.FIELD_TOP_RGB)),
                bottomRgb = coerceRgbList(doc.get(AppConfig.FIELD_BOTTOM_RGB)),
                jacketRgb = coerceRgbList(doc.get(AppConfig.FIELD_JACKET_RGB)),
                shoesRgb = coerceRgbList(doc.get(AppConfig.FIELD_SHOES_RGB)),
                jewelryRgb = coerceRgbList(doc.get(AppConfig.FIELD_JEWELRY_RGB)),
                sunglassesRgb = coerceRgbList(doc.get(AppConfig.FIELD_SUNGLASSES_RGB)),
                bagRgb = coerceRgbList(doc.get(AppConfig.FIELD_BAG_RGB)),
                topColorHex = doc.getString(AppConfig.FIELD_TOP_COLOR_HEX),
                bottomColorHex = doc.getString(AppConfig.FIELD_BOTTOM_COLOR_HEX),
                jacketColorHex = doc.getString(AppConfig.FIELD_JACKET_COLOR_HEX),
                shoesColorHex = doc.getString(AppConfig.FIELD_SHOES_COLOR_HEX),
                jewelryColorHex = doc.getString(AppConfig.FIELD_JEWELRY_COLOR_HEX),
                sunglassesColorHex = doc.getString(AppConfig.FIELD_SUNGLASSES_COLOR_HEX),
                bagColorHex = doc.getString(AppConfig.FIELD_BAG_COLOR_HEX)
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeOutfits(snapshot: QuerySnapshot?): List<Outfit> {
        if (snapshot == null) return emptyList()
        return snapshot.documents.mapNotNull { decodeOutfit(it) }
            .sortedByDescending { it.timestamp }
    }

    private fun profileNeedsServerRefresh(profile: UserProfileSnapshot, snap: DocumentSnapshot): Boolean {
        if (!snap.exists()) return true
        val paletteIncomplete = profile.palette == null ||
            (profile.palette != null &&
                profile.palette.seasonalPalette.isBlank() &&
                profile.palette.powerSwatches.isEmpty())
        return paletteIncomplete || profile.profileImageUrl.isNullOrBlank()
    }

    private fun logProfileSnapshot(source: String, uid: String, snap: DocumentSnapshot, profile: UserProfileSnapshot) {
        android.util.Log.i(
            PROFILE_LOG_TAG,
            "$source users/$uid: exists=${snap.exists()} cache=${snap.metadata.isFromCache} " +
                "name=${profile.fullName} photoLen=${profile.profileImageUrl?.length ?: 0} " +
                "palette=${profile.palette != null} swatches=" +
                "${profile.palette?.powerSwatches?.size ?: 0}/${profile.palette?.neutralSwatches?.size ?: 0}"
        )
    }

    /**
     * Profile screen load. Never uses blocking [get] (hangs after signup on some devices).
     * 1) In-memory profile from feed listener / signup if available
     * 2) Firestore snapshot listener (same path as [observeUserFeedState])
     * 3) Timeout always calls [onResult]
     */
    fun loadUserProfileNoCache(onResult: (UserProfileSnapshot, String?) -> Unit) {
        val uid = currentUserId ?: run {
            onResult(UserProfileSnapshot(null, null, null), "User not logged in")
            return
        }
        cancelInFlightProfileLoad()

        peekRememberedUserProfile()?.let { cached ->
            if (!cached.fullName.isNullOrBlank() || cached.palette != null ||
                !cached.profileImageUrl.isNullOrBlank()
            ) {
                android.util.Log.i(PROFILE_LOG_TAG, "MEMORY users/$uid: name=${cached.fullName} " +
                    "photo=${!cached.profileImageUrl.isNullOrBlank()} palette=${cached.palette != null}")
                onResult(cached, null)
                return
            }
        }

        val ref = db.collection(AppConfig.COLL_USERS).document(uid)
        var finished = false

        val timeoutRunnable = Runnable {
            if (finished) return@Runnable
            finished = true
            cancelInFlightProfileLoad()
            android.util.Log.w(PROFILE_LOG_TAG, "Profile load timed out after ${PROFILE_LOAD_TIMEOUT_MS}ms")
            onResult(UserProfileSnapshot(null, null, null), "Profile load timed out. Check network.")
        }
        profileLoadTimeoutRunnable = timeoutRunnable

        fun complete(profile: UserProfileSnapshot, error: String?) {
            if (finished) return
            finished = true
            cancelInFlightProfileLoad()
            onResult(profile, error)
        }

        fun tryFinishFromSnapshot(snap: DocumentSnapshot, via: String) {
            if (finished) return
            if (!snap.exists()) {
                if (!snap.metadata.isFromCache) {
                    android.util.Log.w(PROFILE_LOG_TAG, "$via: user doc missing on server")
                    complete(UserProfileSnapshot(null, null, null), null)
                }
                return
            }
            try {
                val profile = effectiveUserProfile(snap)
                if (snap.metadata.isFromCache && profileNeedsServerRefresh(profile, snap)) {
                    android.util.Log.i(
                        PROFILE_LOG_TAG,
                        "$via: incomplete cache (palette=${profile.palette != null}, " +
                            "photo=${!profile.profileImageUrl.isNullOrBlank()}), waiting…"
                    )
                    return
                }
                logProfileSnapshot(via, uid, snap, profile)
                rememberUserProfile(profile)
                complete(profile, null)
            } catch (e: Exception) {
                android.util.Log.e(PROFILE_LOG_TAG, "$via parse failed", e)
                complete(UserProfileSnapshot(null, null, null), firestoreErrorMessage(e))
            }
        }

        mainHandler.postDelayed(timeoutRunnable, PROFILE_LOAD_TIMEOUT_MS)

        android.util.Log.i(PROFILE_LOG_TAG, "Snapshot listener for users/$uid")
        profileLoadListener = ref.addSnapshotListener { snap, error ->
            if (finished) return@addSnapshotListener
            if (error != null) {
                android.util.Log.e(PROFILE_LOG_TAG, "Listener error: ${error.message}")
                complete(UserProfileSnapshot(null, null, null), firestoreErrorMessage(error))
                return@addSnapshotListener
            }
            if (snap == null) return@addSnapshotListener
            val via = if (snap.metadata.isFromCache) "LISTENER_CACHE" else "LISTENER_SERVER"
            tryFinishFromSnapshot(snap, via)
        }
    }

    /**
     * Profile live updates: ignores every snapshot where [DocumentSnapshot.metadata.isFromCache].
     */
    fun observeUserProfileNoCache(
        onResult: (UserProfileSnapshot) -> Unit,
        onError: (String) -> Unit
    ): ListenerRegistration? {
        val uid = currentUserId ?: run {
            onError("User not logged in")
            return null
        }
        val doc = db.collection(AppConfig.COLL_USERS).document(uid)

        userProfileListener?.remove()
        userProfileListener = doc.addSnapshotListener { snap, error ->
            if (error != null) {
                onError(firestoreErrorMessage(error))
                return@addSnapshotListener
            }
            if (snap == null || snap.metadata.isFromCache) return@addSnapshotListener
            try {
                onResult(userProfileFromSnapshot(snap))
            } catch (e: Exception) {
                onError(firestoreErrorMessage(e))
            }
        }

        return userProfileListener
    }

    /**
     * Live outfit list for profile. Emits cached/empty data immediately so the UI is not blank
     * while Firestore syncs after signup (default [get] can hang on a fresh session).
     */
    fun getMyOutfits(onResult: (List<Outfit>?, String?) -> Unit) {
        val userId = currentUserId ?: return onResult(null, "User not logged in")

        val cached = if (rememberedMyOutfitsUid == userId) rememberedMyOutfits else null
        mainHandler.post { onResult(cached ?: emptyList(), null) }

        if (myOutfitsListener != null && myOutfitsListenerUid == userId) return

        myOutfitsListener?.remove()
        myOutfitsListenerUid = userId
        myOutfitsListener = db.collection(AppConfig.COLL_OUTFITS)
            .whereEqualTo(AppConfig.FIELD_USER_ID, userId)
            .addSnapshotListener(MetadataChanges.INCLUDE) { value, error ->
                if (error != null) return@addSnapshotListener onResult(null, error.message)
                applyMyOutfitsFromSnapshot(userId, value, onResult)
            }
    }

    /** Profile grid: show cached outfits immediately (same pattern as profile photo/palette). */
    fun getCachedMyOutfits(): List<Outfit> {
        val uid = currentUserId ?: return emptyList()
        return if (rememberedMyOutfitsUid == uid) rememberedMyOutfits.orEmpty() else emptyList()
    }

    /** One-shot outfit list for profile screen. */
    fun loadMyOutfitsOnce(onResult: (List<Outfit>?, String?) -> Unit) {
        val userId = currentUserId ?: run {
            onResult(null, "User not logged in")
            return
        }
        db.collection(AppConfig.COLL_OUTFITS)
            .whereEqualTo(AppConfig.FIELD_USER_ID, userId)
            .get()
            .addOnSuccessListener { snap ->
                try {
                    onResult(decodeOutfits(snap), null)
                } catch (e: Exception) {
                    onResult(null, e.message)
                }
            }
            .addOnFailureListener { e -> onResult(null, e.message) }
    }

    /**
     * Live stream of the current user’s outfits. Remove in [android.app.Activity.onDestroy].
     */
    fun observeMyOutfits(onResult: (List<Outfit>?, String?) -> Unit): ListenerRegistration? {
        val userId = currentUserId ?: run {
            onResult(null, "User not logged in")
            return null
        }
        val cached = if (rememberedMyOutfitsUid == userId) rememberedMyOutfits else null
        mainHandler.post { onResult(cached ?: emptyList(), null) }

        return db.collection(AppConfig.COLL_OUTFITS)
            .whereEqualTo(AppConfig.FIELD_USER_ID, userId)
            .addSnapshotListener(MetadataChanges.INCLUDE) { value, error ->
                if (error != null) return@addSnapshotListener onResult(null, error.message)
                applyMyOutfitsFromSnapshot(userId, value, onResult)
            }
    }

    fun toggleLike(outfitId: String, isLiked: Boolean, onResult: (Boolean, String?) -> Unit) {
        val userId = currentUserId ?: return onResult(false, "Not signed in")
        restoreLikedIdsFromPrefs(userId)
        android.util.Log.i(LIKE_LOG_TAG, "toggleLike uid=$userId outfitId=$outfitId liked=$isLiked")
        if (outfitId.isBlank()) {
            android.util.Log.e(LIKE_LOG_TAG, "toggleLike: blank outfitId")
            return onResult(false, "Outfit id missing")
        }
        writeLikeToUserDocument(outfitId, isLiked, applyOptimisticCache = true, onResult = onResult)
    }

    fun getFavoriteOutfits(onResult: (List<Outfit>?, String?) -> Unit) {
        val userId = currentUserId ?: return onResult(null, "User not logged in")
        markFirestoreUserReady()

        val localIds = authoritativeLikedIds(userId)
        android.util.Log.i(
            LIKE_LOG_TAG,
            "getFavoriteOutfits start uid=$userId likedOutfitIds=$localIds " +
                "prefs=${readLikedIdsFromPrefsOnly(userId)} pending=${hasPendingLikeChanges(userId)}"
        )
        if (localIds.isNotEmpty()) {
            loadOutfitsForLikedIds(userId, localIds, onResult)
        } else {
            val cached = if (rememberedFavoritesUid == userId) rememberedFavorites else null
            android.util.Log.i(
                LIKE_LOG_TAG,
                "getFavoriteOutfits: no liked ids; cached outfits=${cached?.size ?: 0}"
            )
            mainHandler.post { onResult(cached ?: emptyList(), null) }
        }

        favoritesListener?.remove()
        favoritesListener = db.collection(AppConfig.COLL_USERS)
            .document(userId)
            .addSnapshotListener(MetadataChanges.INCLUDE) { snap, error ->
                if (error != null) {
                    android.util.Log.e(LIKE_LOG_TAG, "getFavoriteOutfits user listener error: ${error.message}")
                    return@addSnapshotListener onResult(null, error.message)
                }
                if (snap == null) return@addSnapshotListener
                mergeServerLikedIdsFromUserDocument(snap)
                val favIds = authoritativeLikedIds(userId)
                android.util.Log.i(
                    LIKE_LOG_TAG,
                    "getFavoriteOutfits user snapshot -> likedOutfitIds=$favIds"
                )
                if (favIds.isEmpty()) {
                    // Do not push an empty list over a non-empty UI from prefs/feed cache.
                    return@addSnapshotListener
                }
                loadOutfitsForLikedIds(userId, favIds, onResult)
            }
    }

    fun isOutfitLiked(outfitId: String, onResult: (Boolean) -> Unit) {
        onResult(isOutfitLikedCached(outfitId))
    }

    /**
     * Copies [imageUri] to a temp file before upload (gallery `content://` URIs often fail with [putFile]).
     */
    fun uploadProfileImage(
        context: Context,
        imageUri: Uri,
        onResult: (Boolean, String?, profileImageUrl: String?) -> Unit
    ) {
        val tempFile = copyUriToCacheFile(context, imageUri)
        if (tempFile == null) {
            onResult(false, "Could not read the selected image", null)
            return
        }
        uploadProfileImageFile(context, tempFile, deleteWhenDone = true, contentType = null, onResult)
    }

    /**
     * Uploads an on-disk image (e.g. cached at pick time during registration).
     */
    fun uploadProfileImageFile(
        context: Context,
        imageFile: File,
        deleteWhenDone: Boolean = false,
        contentType: String? = null,
        onResult: (Boolean, String?, profileImageUrl: String?) -> Unit
    ) {
        val userId = currentUserId ?: return onResult(false, "User not logged in", null)
        if (!imageFile.exists() || imageFile.length() == 0L) {
            onResult(false, "Could not read the selected image", null)
            return
        }

        var finished = false
        val mainHandler = Handler(Looper.getMainLooper())
        fun complete(success: Boolean, error: String?, imageUrl: String? = null) {
            if (finished) return
            finished = true
            if (deleteWhenDone) imageFile.delete()
            onResult(success, error, imageUrl)
        }

        val timeout = Runnable {
            complete(false, "Profile photo upload timed out. Check Firebase Storage rules.", null)
        }
        mainHandler.postDelayed(timeout, UPLOAD_TIMEOUT_MS)

        val mime = contentType ?: guessImageContentType(context, Uri.fromFile(imageFile), imageFile.name)
        val fileRef = storage.reference.child("${AppConfig.PATH_PROFILES}/$userId.jpg")
        val metadata = StorageMetadata.Builder().setContentType(mime).build()

        fileRef.putFile(Uri.fromFile(imageFile), metadata)
            .continueWithTask { task ->
                if (!task.isSuccessful) {
                    throw task.exception ?: Exception("Image upload failed")
                }
                fileRef.downloadUrl
            }
            .addOnSuccessListener { downloadUri ->
                mainHandler.removeCallbacks(timeout)
                val url = normalizeStorageUrl(downloadUri.toString()) ?: downloadUri.toString()
                val prev = peekRememberedUserProfile()
                rememberUserProfile(
                    UserProfileSnapshot(
                        fullName = prev?.fullName,
                        profileImageUrl = url,
                        palette = prev?.palette
                    )
                )
                complete(true, null, url)
                db.collection(AppConfig.COLL_USERS).document(userId)
                    .set(mapOf("profileImageUrl" to url), SetOptions.merge())
                    .addOnFailureListener { e ->
                        android.util.Log.w(
                            PROFILE_LOG_TAG,
                            "Profile photo URL Firestore sync failed: ${e.message}"
                        )
                    }
            }
            .addOnFailureListener { e ->
                mainHandler.removeCallbacks(timeout)
                complete(false, e.message ?: e.javaClass.simpleName, null)
            }
    }

    fun savePersonalPalette(
        palette: PersonalPalette,
        fullName: String? = null,
        email: String? = null,
        onResult: (Boolean, String?) -> Unit
    ) {
        val userId = currentUserId ?: return onResult(false, "User not logged in")
        val payload = hashMapOf<String, Any>(
            AppConfig.FIELD_PERSONAL_PALETTE to PersonalPalette.toFirestoreMap(palette)
        )
        fullName?.takeIf { it.isNotBlank() }?.let { payload["fullName"] = it }
        email?.takeIf { it.isNotBlank() }?.let { payload["email"] = it }
        db.collection(AppConfig.COLL_USERS).document(userId)
            .set(payload, SetOptions.merge())
            .addOnSuccessListener {
                rememberUserProfile(
                    UserProfileSnapshot(
                        fullName = fullName,
                        profileImageUrl = rememberedProfile?.takeIf { rememberedProfileUid == userId }
                            ?.profileImageUrl,
                        palette = palette
                    )
                )
                onResult(true, null)
            }
            .addOnFailureListener { onResult(false, it.message) }
    }
}