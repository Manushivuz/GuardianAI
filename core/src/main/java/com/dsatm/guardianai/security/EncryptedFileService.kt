// file: core/src/main/java/com/dsatm/guardianai/security/EncryptedFileService.kt
package com.dsatm.guardianai.security

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import java.io.File
import java.io.IOException

/**
 * A service class for handling file encryption and decryption using AndroidX Security Crypto.
 * It uses a MasterKey to secure the files.
 */
class EncryptedFileService(
    private val context: Context,
    private val masterKey: MasterKey
) {
    // Tag for logging messages
    private val TAG = "EncryptedFileService"

    // Helper function to create an EncryptedFile object with a specific file and master key
    private fun getEncryptedFile(file: File): EncryptedFile {
        return EncryptedFile.Builder(
            context,
            file,
            masterKey,
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
        ).build()
    }

    /**
     * Encrypts the provided byte array and saves it to a new, encrypted file
     * in the app's private internal storage.
     * @param dataToEncrypt The byte array to be encrypted.
     * @param encryptedFileName The desired name of the output file.
     * @return The File object of the newly created encrypted file.
     */
    fun encryptAndSaveFile(dataToEncrypt: ByteArray, encryptedFileName: String): File {
        Log.d(TAG, "Attempting to encrypt and save file: $encryptedFileName")

        val targetFile = File(context.filesDir, encryptedFileName)
        try {
            getEncryptedFile(targetFile).openFileOutput().use { outputStream ->
                outputStream.write(dataToEncrypt)
            }
            Log.d(TAG, "Encryption complete. Saved to app private storage: ${targetFile.absolutePath}")
        } catch (e: IOException) {
            Log.e(TAG, "Error encrypting or saving file: ${e.message}")
            throw e
        }
        return targetFile
    }

    /**
     * Decrypts a file from the app's private internal storage and returns the decrypted byte array.
     * @param sourceFile The encrypted File object to read from (from internal storage).
     * @return The decrypted byte array.
     */
    fun decryptFile(sourceFile: File): ByteArray {
        Log.d(TAG, "Attempting to decrypt file from internal storage: ${sourceFile.absolutePath}")
        try {
            val encryptedFile = getEncryptedFile(sourceFile)
            val decryptedData = encryptedFile.openFileInput().use { inputStream ->
                inputStream.readBytes()
            }
            Log.d(TAG, "Decryption successful. Decrypted ${decryptedData.size} bytes.")
            return decryptedData
        } catch (e: Exception) {
            Log.e(TAG, "Decryption failed. Error: ${e.message}")
            throw e
        }
    }
}