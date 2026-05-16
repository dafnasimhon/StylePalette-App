package com.example.myapplication

import com.google.firebase.firestore.FirebaseFirestore

/**
 * Clears Firestore's local cache and client after sign-out so the next login does not see
 * the previous user's cached (often empty / permission-denied) snapshots.
 */
object FirebaseSession {
    fun resetAfterSignOut(onComplete: () -> Unit) {
        FirebaseFirestore.getInstance().terminate().addOnCompleteListener {
            onComplete()
        }
    }
}
