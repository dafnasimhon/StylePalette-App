package com.example.myapplication.repository

import android.content.Context
import android.net.Uri
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
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import java.io.File
import java.util.Locale
import java.util.UUID

class OutfitRepository {

    private companion object {
        const val UPLOAD_TIMEOUT_MS = 120_000L
    }

    private var myOutfitsListener: ListenerRegistration? = null
    private var userProfileListener: ListenerRegistration? = null

    /** Firestore often returns `Map<*, *>`; normalize so [FeedFilters.fromFirestore] reads booleans reliably. */
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
                val outfit = Outfit(
                    id = outfitId,
                    userId = userId,
                    imageUrl = downloadUri.toString(),
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
                saveOutfitToFirestore(outfit) { ok, err ->
                    complete(ok, err)
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

                val list = value?.toObjects(Outfit::class.java) ?: emptyList()
                onResult(list.filter { it.userId != currentUserId }, null)
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
                    latestByPrefix[prefix] = value?.toObjects(Outfit::class.java) ?: emptyList()
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
            .addSnapshotListener { snap, _ ->
                val filterMap = coerceStringKeyMap(snap?.get(AppConfig.FIELD_FEED_FILTERS))
                val paletteMap = coerceStringKeyMap(snap?.get(AppConfig.FIELD_PERSONAL_PALETTE))
                onResult(
                    FeedFilters.fromFirestore(filterMap),
                    PersonalPalette.fromFirestore(paletteMap)
                )
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

    private fun paletteFromUserSnapshot(snap: DocumentSnapshot): PersonalPalette? {
        val nested = snap.get(AppConfig.FIELD_PERSONAL_PALETTE)
            ?: snap.get("personalPalette")
        PersonalPalette.fromFirestore(coerceStringKeyMap(nested))?.let { return it }
        val data = snap.data ?: return null
        if (data.containsKey("seasonalPalette") ||
            data.containsKey("powerColors") ||
            data.containsKey("power_colors") ||
            data.containsKey(AppConfig.FIELD_PERSONAL_PALETTE)
        ) {
            val paletteMap = coerceStringKeyMap(data[AppConfig.FIELD_PERSONAL_PALETTE]) ?: data
            return PersonalPalette.fromFirestore(coerceStringKeyMap(paletteMap))
        }
        return null
    }

    fun userProfileFromSnapshot(snap: DocumentSnapshot): UserProfileSnapshot {
        if (!snap.exists()) return UserProfileSnapshot(null, null, null)
        return UserProfileSnapshot(
            fullName = snap.getString("fullName"),
            profileImageUrl = snap.getString("profileImageUrl"),
            palette = paletteFromUserSnapshot(snap)
        )
    }

    fun clearProfileListeners() {
        myOutfitsListener?.remove()
        myOutfitsListener = null
        userProfileListener?.remove()
        userProfileListener = null
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
                id = doc.getString("id")?.takeIf { it.isNotBlank() } ?: doc.id,
                userId = doc.getString(AppConfig.FIELD_USER_ID).orEmpty(),
                imageUrl = doc.getString(AppConfig.FIELD_IMAGE_URL).orEmpty(),
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
        val decoded = snapshot.documents.mapNotNull { decodeOutfit(it) }
        if (decoded.isNotEmpty()) return decoded.sortedByDescending { it.timestamp }
        return snapshot.toObjects(Outfit::class.java).sortedByDescending { it.timestamp }
    }

    /** One-shot read — same as the working ProfileActivity (`get()` default source). */
    fun loadUserProfile(onResult: (UserProfileSnapshot, String?) -> Unit) {
        val uid = currentUserId ?: run {
            onResult(UserProfileSnapshot(null, null, null), "User not logged in")
            return
        }
        db.collection(AppConfig.COLL_USERS).document(uid)
            .get()
            .addOnSuccessListener { snap ->
                try {
                    onResult(userProfileFromSnapshot(snap), null)
                } catch (e: Exception) {
                    onResult(UserProfileSnapshot(null, null, null), firestoreErrorMessage(e))
                }
            }
            .addOnFailureListener { e ->
                onResult(UserProfileSnapshot(null, null, null), firestoreErrorMessage(e))
            }
    }

    /**
     * Live user doc updates. Cleared via [clearProfileListeners].
     */
    fun observeUserProfile(
        onResult: (UserProfileSnapshot) -> Unit,
        onError: (String) -> Unit
    ): ListenerRegistration? {
        val uid = currentUserId ?: run {
            onError("User not logged in")
            return null
        }
        val doc = db.collection(AppConfig.COLL_USERS).document(uid)

        fun deliver(snap: DocumentSnapshot) {
            try {
                onResult(userProfileFromSnapshot(snap))
            } catch (e: Exception) {
                onError(firestoreErrorMessage(e))
            }
        }

        userProfileListener?.remove()
        userProfileListener = doc.addSnapshotListener { snap, error ->
            if (error != null) {
                onError(firestoreErrorMessage(error))
                return@addSnapshotListener
            }
            if (snap != null) deliver(snap)
        }

        return userProfileListener
    }

    /** Live outfit list for profile (same pattern as the working app). */
    fun getMyOutfits(onResult: (List<Outfit>?, String?) -> Unit) {
        val userId = currentUserId ?: return onResult(null, "User not logged in")

        myOutfitsListener?.remove()
        myOutfitsListener = db.collection(AppConfig.COLL_OUTFITS)
            .whereEqualTo(AppConfig.FIELD_USER_ID, userId)
            .addSnapshotListener { value, error ->
                if (error != null) return@addSnapshotListener onResult(null, error.message)
                try {
                    onResult(decodeOutfits(value), null)
                } catch (e: Exception) {
                    onResult(null, e.message)
                }
            }
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
        val query = db.collection(AppConfig.COLL_OUTFITS)
            .whereEqualTo(AppConfig.FIELD_USER_ID, userId)

        fun emit(value: QuerySnapshot?) {
            try {
                onResult(decodeOutfits(value), null)
            } catch (e: Exception) {
                onResult(null, e.message ?: "Could not load outfits")
            }
        }

        query.get().addOnSuccessListener { emit(it) }
            .addOnFailureListener { e -> onResult(null, e.message) }

        return query.addSnapshotListener { value, error ->
            if (error != null) return@addSnapshotListener onResult(null, error.message)
            emit(value)
        }
    }

    fun toggleLike(outfitId: String, isLiked: Boolean, onResult: (Boolean) -> Unit) {
        val userId = currentUserId ?: return onResult(false)

        val favRef = db.collection(AppConfig.COLL_USERS)
            .document(userId)
            .collection(AppConfig.COLL_FAVORITES)
            .document(outfitId)

        val task = if (isLiked) favRef.set(mapOf("likedAt" to System.currentTimeMillis())) else favRef.delete()
        task.addOnSuccessListener { onResult(true) }.addOnFailureListener { onResult(false) }
    }

    fun getFavoriteOutfits(onResult: (List<Outfit>?, String?) -> Unit) {
        val userId = currentUserId ?: return onResult(null, "User not logged in")

        db.collection(AppConfig.COLL_USERS)
            .document(userId)
            .collection(AppConfig.COLL_FAVORITES)
            .addSnapshotListener { snapshot, error ->
                if (error != null) return@addSnapshotListener onResult(null, error.message)
                val favIds = snapshot?.documents?.map { it.id } ?: emptyList()
                if (favIds.isEmpty()) return@addSnapshotListener onResult(emptyList(), null)

                db.collection(AppConfig.COLL_OUTFITS)
                    .whereIn(FieldPath.documentId(), favIds)
                    .get()
                    .addOnSuccessListener { onResult(it.toObjects(Outfit::class.java), null) }
                    .addOnFailureListener { onResult(null, it.message) }
            }
    }

    fun isOutfitLiked(outfitId: String, onResult: (Boolean) -> Unit) {
        val userId = currentUserId ?: return onResult(false)
        db.collection(AppConfig.COLL_USERS)
            .document(userId)
            .collection(AppConfig.COLL_FAVORITES)
            .document(outfitId).get()
            .addOnSuccessListener { onResult(it.exists()) }
            .addOnFailureListener { onResult(false) }
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
                val url = downloadUri.toString()
                db.collection(AppConfig.COLL_USERS).document(userId)
                    .set(mapOf("profileImageUrl" to url), SetOptions.merge())
                    .addOnSuccessListener { complete(true, null, url) }
                    .addOnFailureListener { e ->
                        complete(false, e.message ?: "Could not save profile photo URL", null)
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
            .addOnSuccessListener { onResult(true, null) }
            .addOnFailureListener { onResult(false, it.message) }
    }
}