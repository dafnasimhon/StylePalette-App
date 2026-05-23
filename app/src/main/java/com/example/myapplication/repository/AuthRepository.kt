package com.example.myapplication.repository

import com.example.myapplication.models.AppConfig
import com.example.myapplication.models.User
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

class AuthRepository {

    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()


    fun register(email: String, password: String, fullName: String, onResult: (Boolean, String?) -> Unit) {
        auth.createUserWithEmailAndPassword(email, password)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val uid = auth.currentUser?.uid ?: ""
                    val user = User(uid = uid, fullName = fullName, email = email)

                    saveUserToFirestore(user, onResult)
                } else {
                    onResult(false, task.exception?.message)
                }
            }
    }

    private fun saveUserToFirestore(user: User, onResult: (Boolean, String?) -> Unit) {
        val doc = hashMapOf(
            "uid" to user.uid,
            "fullName" to user.fullName,
            "email" to user.email,
            "profileImageUrl" to user.profileImageUrl
        )
        db.collection(AppConfig.COLL_USERS).document(user.uid)
            .set(doc, SetOptions.merge())
            .addOnSuccessListener { onResult(true, null) }
            .addOnFailureListener { e -> onResult(false, e.message) }
    }


    fun login(email: String, password: String, onResult: (Boolean, String?) -> Unit) {
        auth.signInWithEmailAndPassword(email, password)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    ensureFirestoreUserRecord()
                    onResult(true, null)
                } else {
                    onResult(false, task.exception?.message)
                }
            }
    }

    /** Auth-only accounts may lack `users/{uid}`; profile and feed expect that document. */
    private fun ensureFirestoreUserRecord() {
        val firebaseUser = auth.currentUser ?: return
        val uid = firebaseUser.uid
        val ref = db.collection(AppConfig.COLL_USERS).document(uid)
        ref.get().addOnSuccessListener { snap ->
            if (snap.exists()) return@addOnSuccessListener
            val user = User(
                uid = uid,
                fullName = firebaseUser.displayName?.trim().orEmpty(),
                email = firebaseUser.email?.trim().orEmpty()
            )
            ref.set(user, SetOptions.merge())
        }
    }

    fun isUserLoggedIn(): Boolean = auth.currentUser != null

    fun logout() {
        auth.signOut()
    }
}