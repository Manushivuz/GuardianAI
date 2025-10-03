package com.dsatm.guardianai.security

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Custom file encryption service using the Secure Key managed by SecurityManager.
 */
class EncryptedFileService(private val context: Context, private val securityManager: SecurityManager) {
    private val TAG = "CustomEncryptedFileService"
    private val TRANSFORMATION = "AES/GCM/NoPadding"
    private val IV_SIZE = 12

    /**
     * Retrieves the single SecretKey managed by the SecurityManager for I/O.
     */
    private fun getSecretKey(): SecretKey {
        return securityManager.getOrCreateAuthKey()
    }

    /**
     * Encrypts the provided byte array, prepends the IV, and saves the result to internal storage.
     */
    fun encryptAndSaveFile(dataToEncrypt: ByteArray, encryptedFileName: String): File {
        Log.d(TAG, "Attempting custom encryption for: $encryptedFileName")

        val targetFile = File(context.filesDir, encryptedFileName)

        try {
            // 1. Initialize Cipher for ENCRYPT mode
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, getSecretKey())
            }

            // 2. Perform encryption and get the generated IV
            val encryptedData = cipher.doFinal(dataToEncrypt)
            val iv = cipher.iv

            // 3. Write IV (12 bytes) followed by encrypted data to file
            FileOutputStream(targetFile).use { outputStream ->
                outputStream.write(iv)
                outputStream.write(encryptedData)
            }

            Log.d(TAG, "Custom encryption complete. Saved to: ${targetFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Custom encryption failed.", e)
            throw IOException("Failed custom file encryption: ${e.message}", e)
        }
        return targetFile
    }

    /**
     * Decrypts a file by reading the IV and then the ciphertext.
     */
    fun decryptFile(sourceFile: File): ByteArray {
        Log.d(TAG, "Attempting custom decryption for: ${sourceFile.name}")

        FileInputStream(sourceFile).use { inputStream ->
            // 1. Read IV (Initialization Vector)
            val iv = ByteArray(IV_SIZE)
            if (inputStream.read(iv) != IV_SIZE) {
                throw IOException("Corrupt encrypted file: IV not found or file is too small.")
            }

            // 2. Read encrypted data
            val encryptedData = inputStream.readBytes()

            // 3. Initialize Cipher for DECRYPT mode
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                val spec = GCMParameterSpec(128, iv)
                init(Cipher.DECRYPT_MODE, getSecretKey(), spec)
            }

            // 4. Perform decryption
            return cipher.doFinal(encryptedData)
        }
    }
}
