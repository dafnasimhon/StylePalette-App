package com.example.myapplication

import android.app.Application
import com.google.firebase.FirebaseApp

class App : Application() {

    companion object {
        lateinit var instance: App
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        FirebaseApp.initializeApp(this)
        // No Firebase App Check here — same as the working app_project build.
        // App Check without a registered debug token blocks Firestore reads (profile, feed).
    }
}
