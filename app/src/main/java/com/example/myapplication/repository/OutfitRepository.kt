package com.example.myapplication.repository

import android.net.Uri
import com.example.myapplication.models.AppConfig
import com.example.myapplication.models.FeedFilters
import com.example.myapplication.models.Outfit
import com.example.myapplication.models.OutfitRgb
import com.example.myapplication.models.PersonalPalette
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage
import java.util.Locale
import java.util.UUID

class OutfitRepository {

    /** Firestore often returns `Map<*, *>`; normalize so [FeedFilters.fromFirestore] reads booleans reliably. */
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
        imageUri: Uri, top: String, bottom: String, jacket: String,
        shoes: String, jewelry: String, sunglasses: String, bag: String,
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

        val fileRef = storage.reference.child("${AppConfig.PATH_OUTFITS}/$outfitId.jpg")

        fileRef.putFile(imageUri).continueWithTask {
            fileRef.downloadUrl
        }.addOnSuccessListener { uri ->
            val normalizedVibe = vibe.trim()
            val outfit = Outfit(
                id = outfitId,
                userId = userId,
                imageUrl = uri.toString(),
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
            saveOutfitToFirestore(outfit, onResult)
        }.addOnFailureListener { onResult(false, it.message) }
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

    /**
     * Live stream of the current user’s outfits. Caller must [ListenerRegistration.remove] when done
     * (e.g. in [android.app.Activity.onStop]) to avoid duplicate listeners if re-registering.
     */
    fun observeMyOutfits(onResult: (List<Outfit>?, String?) -> Unit): ListenerRegistration? {
        val userId = currentUserId ?: run {
            onResult(null, "User not logged in")
            return null
        }
        return db.collection(AppConfig.COLL_OUTFITS)
            .whereEqualTo(AppConfig.FIELD_USER_ID, userId)
            .addSnapshotListener { value, error ->
                if (error != null) return@addSnapshotListener onResult(null, error.message)

                val list = value?.toObjects(Outfit::class.java) ?: emptyList()
                val sortedList = list.sortedByDescending { it.timestamp }
                onResult(sortedList, null)
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

    fun uploadProfileImage(imageUri: Uri, onResult: (Boolean, String?) -> Unit) {
        val userId = currentUserId ?: return onResult(false, "User not logged in")
        val fileRef = storage.reference.child("${AppConfig.PATH_PROFILES}/$userId.jpg")

        fileRef.putFile(imageUri).continueWithTask { fileRef.downloadUrl }.addOnSuccessListener { uri ->
            db.collection(AppConfig.COLL_USERS).document(userId).update("profileImageUrl", uri.toString())
                .addOnSuccessListener { onResult(true, null) }
                .addOnFailureListener { onResult(false, it.message) }
        }.addOnFailureListener { onResult(false, it.message) }
    }

    fun savePersonalPalette(palette: PersonalPalette, onResult: (Boolean, String?) -> Unit) {
        val userId = currentUserId ?: return onResult(false, "User not logged in")
        val data = PersonalPalette.toFirestoreMap(palette)
        db.collection(AppConfig.COLL_USERS).document(userId)
            .set(
                mapOf(AppConfig.FIELD_PERSONAL_PALETTE to data),
                SetOptions.merge()
            )
            .addOnSuccessListener { onResult(true, null) }
            .addOnFailureListener { onResult(false, it.message) }
    }
}