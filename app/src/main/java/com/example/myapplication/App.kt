package com.example.myapplication

import android.app.Application
import com.google.firebase.FirebaseApp

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        FirebaseApp.initializeApp(this)
        // No Firebase App Check here — same as the working app_project build.
        // App Check without a registered debug token blocks Firestore reads (profile, feed).
    }
}
