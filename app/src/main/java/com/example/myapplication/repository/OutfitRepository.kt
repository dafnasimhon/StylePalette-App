package com.example.myapplication.repository

import android.net.Uri
import android.util.Log
import com.example.myapplication.models.AppConfig
import com.example.myapplication.models.Outfit
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage
import java.util.UUID

/**
 * Repository object (Singleton) for managing StylePalette data.
 * Using 'object' ensures consistent listener management across the app.
 */
object OutfitRepository {

    private val db = FirebaseFirestore.getInstance()
    private val storage = FirebaseStorage.getInstance()
    private val auth = FirebaseAuth.getInstance()

    private val currentUserId: String? get() = auth.currentUser?.uid
    private const val TAG = "StylePalette_Repo"

    private var allOutfitsListener: ListenerRegistration? = null
    private var myOutfitsListener: ListenerRegistration? = null
    private var favoritesListener: ListenerRegistration? = null

    fun clearListeners() {
        allOutfitsListener?.remove()
        allOutfitsListener = null

        myOutfitsListener?.remove()
        myOutfitsListener = null

        favoritesListener?.remove()
        favoritesListener = null

        Log.d(TAG, "All active listeners have been cleared.")
    }

    fun uploadOutfit(
        imageUri: Uri, top: String, bottom: String, jacket: String,
        shoes: String, jewelry: String, sunglasses: String, bag: String,
        vibe: String,
        topRgb: List<Long>? = null,
        bottomRgb: List<Long>? = null,
        jacketRgb: List<Long>? = null,
        shoesRgb: List<Long>? = null,
        jewelryRgb: List<Long>? = null,
        sunglassesRgb: List<Long>? = null,
        bagRgb: List<Long>? = null,
        onResult: (Boolean, String?) -> Unit
    ) {
        val userId = currentUserId ?: return onResult(false, "User not logged in")
        val outfitId = UUID.randomUUID().toString()
        val fileRef = storage.reference.child("${AppConfig.PATH_OUTFITS}/$outfitId.jpg")

        fileRef.putFile(imageUri).continueWithTask {
            fileRef.downloadUrl
        }.addOnSuccessListener { uri ->
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
                vibe = vibe,
                topRgb = topRgb,
                bottomRgb = bottomRgb,
                jacketRgb = jacketRgb,
                shoesRgb = shoesRgb,
                jewelryRgb = jewelryRgb,
                sunglassesRgb = sunglassesRgb,
                bagRgb = bagRgb
            )
            saveOutfitToFirestore(outfit, onResult)
        }.addOnFailureListener { onResult(false, it.message) }
    }

    private fun saveOutfitToFirestore(outfit: Outfit, onResult: (Boolean, String?) -> Unit) {
        db.collection(AppConfig.COLL_OUTFITS).document(outfit.id).set(outfit)
            .addOnSuccessListener { onResult(true, null) }
            .addOnFailureListener { onResult(false, it.message) }
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

    fun getAllOutfits(onResult: (List<Outfit>?, String?) -> Unit) {
        allOutfitsListener?.remove()
        allOutfitsListener = db.collection(AppConfig.COLL_OUTFITS)
            .orderBy(AppConfig.FIELD_TIMESTAMP, Query.Direction.DESCENDING)
            .addSnapshotListener { value, error ->
                if (error != null) return@addSnapshotListener onResult(null, error.message)
                val list = value?.toObjects(Outfit::class.java) ?: emptyList()
                onResult(list.filter { it.userId != currentUserId }, null)
            }
    }

    fun getMyOutfits(onResult: (List<Outfit>?, String?) -> Unit) {
        val userId = currentUserId ?: return onResult(null, "User not logged in")

        myOutfitsListener?.remove()
        myOutfitsListener = db.collection(AppConfig.COLL_OUTFITS)
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
        val favRef = db.collection(AppConfig.COLL_USERS).document(userId)
            .collection(AppConfig.COLL_FAVORITES).document(outfitId)

        val task = if (isLiked) favRef.set(mapOf("likedAt" to System.currentTimeMillis())) else favRef.delete()
        task.addOnSuccessListener { onResult(true) }.addOnFailureListener { onResult(false) }
    }

    fun getFavoriteOutfits(onResult: (List<Outfit>?, String?) -> Unit) {
        val userId = currentUserId ?: return onResult(null, "User not logged in")

        favoritesListener?.remove()
        favoritesListener = db.collection(AppConfig.COLL_USERS)
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
        db.collection(AppConfig.COLL_USERS).document(userId)
            .collection(AppConfig.COLL_FAVORITES).document(outfitId).get()
            .addOnSuccessListener { onResult(it.exists()) }
            .addOnFailureListener { onResult(false) }
    }

    fun uploadProfileImage(imageUri: Uri, onResult: (Boolean, String?) -> Unit) {
        val userId = currentUserId ?: return onResult(false, "User not logged in")
        val fileRef = storage.reference.child("${AppConfig.PATH_PROFILES}/$userId.jpg")

        fileRef.putFile(imageUri).continueWithTask { fileRef.downloadUrl }.addOnSuccessListener { uri ->
            db.collection(AppConfig.COLL_USERS).document(userId)
                .set(mapOf("profileImageUrl" to uri.toString()), SetOptions.merge())
                .addOnSuccessListener { onResult(true, null) }
                .addOnFailureListener { onResult(false, it.message) }
        }.addOnFailureListener { onResult(false, it.message) }
    }
}