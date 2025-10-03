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
     * Attempts to write the redacted data to the original file path.
     * This method is only for files outside SAF control (should be avoided)
     *
     * @param filePath The absolute path of the file (original file location).
     * @param redactedData The ByteArray of the redacted file data.
     * @return The File object where the data was actually saved (either original or fallback location).
     */
    fun saveRedactedFile(filePath: String, redactedData: ByteArray): File {
        // NOTE: This method is now DEPRECATED by the SAF-compliant method below.
        // It remains here to avoid breaking old calls, but its behavior is unpredictable
        // due to the OS blocks. It now defaults to the fallback save.

        val originalFile = File(filePath)

        Log.w(TAG, "Using old path-based save. Attempting direct file write (expected to fail on API 30+).")

        // --- Fallback Mechanism (Saves to app's guaranteed writable public folder) ---
        val fallbackDir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Redacted_Output")
        if (!fallbackDir.exists()) fallbackDir.mkdirs()

        val redactedFile = File(fallbackDir, originalFile.name)

        try {
            FileOutputStream(redactedFile).use { outputStream ->
                outputStream.write(redactedData)
            }
            Log.w(TAG, "Redacted file saved to FALLBACK location: ${redactedFile.absolutePath}")

            return redactedFile

        } catch (e: IOException) {
            Log.e(TAG, "FATAL: Fallback save also failed.", e)
            throw IOException("Failed to save redacted file to app-external storage.", e)
        }
    }


    // --- NEW SAF COMPLIANT METHOD ---

    /**
     * Saves the redacted data by opening a writable output stream for the given Content URI.
     * This is the compliant method used to successfully overwrite the user-selected file.
     *
     * @param targetUri The Content URI of the file, granted write access via SAF.
     * @param redactedData The ByteArray of the redacted file data.
     * @throws IOException If writing to the URI fails (e.g., stream closed, data corrupt).
     */
    fun saveRedactedFileSaf(targetUri: Uri, redactedData: ByteArray) {
        try {
            context.contentResolver.openOutputStream(targetUri)?.use { outputStream ->
                outputStream.write(redactedData)
            } ?: throw IOException("Could not open output stream for target URI.")

            Log.d(TAG, "SUCCESS: File overwritten via SAF URI: $targetUri")
        } catch (e: Exception) {
            Log.e(TAG, "FATAL: SAF file write failed for URI: $targetUri", e)
            throw IOException("Failed to save redacted file using SAF URI: ${e.message}", e)
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
