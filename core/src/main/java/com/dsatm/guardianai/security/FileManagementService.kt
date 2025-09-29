// file: core/src/main/java/com/dsatm/guardianai/security/FileManagementService.kt

package com.dsatm.guardianai.security

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class FileManagementService(
    private val context: Context,
    private val encryptedFileService: EncryptedFileService
) {
    private val TAG = "FileManagementService"

    /**
     * Reads a file from a public Uri, encrypts the original, and saves it to
     * a new file in the app's private internal storage. It returns the original
     * data as a ByteArray to be used for redaction.
     *
     * @param sourceUri The Uri of the file from external storage.
     * @param originalFileName The desired name for the encrypted file in internal storage.
     * @return The original file data as a ByteArray.
     */
    fun processAndEncryptOriginalFile(sourceUri: Uri, originalFileName: String): ByteArray {
        val originalData = context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
            inputStream.readBytes()
        } ?: throw IOException("Failed to read data from source Uri.")

        encryptedFileService.encryptAndSaveFile(originalData, originalFileName)

        Log.d(TAG, "Original file encrypted and saved to internal storage.")
        return originalData
    }

    /**
     * Writes the redacted data to a public external storage location (e.g., the Downloads folder).
     *
     * @param fileName The desired name for the redacted file.
     * @param redactedData The ByteArray of the redacted file data.
     */
    fun saveRedactedFile(fileName: String, redactedData: ByteArray) {
        val redactedFile = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName)
        try {
            FileOutputStream(redactedFile).use { outputStream ->
                outputStream.write(redactedData)
            }
            Log.d(TAG, "Redacted file saved to public storage at: ${redactedFile.absolutePath}")
        } catch (e: IOException) {
            Log.e(TAG, "Error saving redacted file to public storage: ${e.message}")
            throw e
        }
    }

    /**
     * Lists all encrypted files saved in the app's internal storage directory.
     *
     * @return A list of File objects representing the encrypted files.
     */
    fun listEncryptedFiles(): List<File> {
        val internalDir = context.filesDir
        return internalDir.listFiles()?.toList() ?: emptyList()
    }

    /**
     * Decrypts a specific file from internal storage for viewing inside the app.
     *
     * @param encryptedFile The File object of the encrypted file to be decrypted.
     * @return The decrypted ByteArray.
     */
    fun decryptAndAccessOriginalFile(encryptedFile: File): ByteArray {
        Log.d(TAG, "Attempting to decrypt file for in-app access: ${encryptedFile.name}")
        return encryptedFileService.decryptFile(encryptedFile)
    }
}