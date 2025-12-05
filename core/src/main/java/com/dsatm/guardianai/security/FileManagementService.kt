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
      * Saves the redacted data by opening a writable output stream for the given Content URI.
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
            return internalDir.listFiles()?.filter { it.name.endsWith(".encrypted") }?.toList() ?: emptyList()
          }
}