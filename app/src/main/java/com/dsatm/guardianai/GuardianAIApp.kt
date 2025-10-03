package com.dsatm.guardianai

import android.app.Application
import android.util.Log
import com.google.mlkit.common.MlKit
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class GuardianAIApp : Application() {

    private val TAG = "GuardianAIApp"

    override fun onCreate() {
        super.onCreate()

        // --- ML KIT INITIALIZATION ---
        // Initialize the base ML Kit Context only.
        // We removed the specific TextRecognition import to resolve the Unresolved reference error.
        try {
            MlKit.initialize(this)
            Log.d(TAG, "ML Kit Context initialized successfully.")

        } catch (e: Exception) {
            Log.e(TAG, "FATAL: Failed to initialize ML Kit Context.", e)
        }
    }
}