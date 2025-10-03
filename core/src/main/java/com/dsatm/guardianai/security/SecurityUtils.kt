package com.dsatm.guardianai.security

import android.content.Context
import android.util.Log
import androidx.security.crypto.MasterKey
import java.io.File
import java.security.KeyStore

object SecurityUtils {
    private const val TAG = "SecurityUtils"
    private const val MASTER_KEY_FILE_NAME = "androidx_security_master_keyset"

    /**
     * **CRITICAL RECOVERY FUNCTION:** Deletes all local MasterKey files and Keystore aliases.
     * Call this ONLY when facing persistent AEADBadTagException failures.
     */
    fun destroyMasterKey(context: Context) {
        val keysetFile = File(context.filesDir, MASTER_KEY_FILE_NAME)
        val masterKeyAlias = MasterKey.DEFAULT_MASTER_KEY_ALIAS

        Log.e(TAG, "!!! FORCING DESTRUCTIVE MASTER KEY RECOVERY !!!")

        try {
            // 1. Delete the keyset file used by EncryptedFile (Tink)
            if (keysetFile.exists()) {
                keysetFile.delete()
                Log.w(TAG, "Deleted corrupted MasterKey keyset file: ${keysetFile.absolutePath}")
            }

            // 2. Delete the Keystore alias
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (keyStore.containsAlias(masterKeyAlias)) {
                keyStore.deleteEntry(masterKeyAlias)
                Log.w(TAG, "Deleted MasterKey Keystore alias: $masterKeyAlias")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to destroy master key components during cleanup.", e)
        }
    }
}