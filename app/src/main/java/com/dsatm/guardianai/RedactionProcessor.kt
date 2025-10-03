package com.dsatm.guardianai

import android.content.Context
import android.net.Uri
import android.util.Log
import com.dsatm.guardianai.security.FileManagementService
import com.dsatm.image_redaction.ImageRedactionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.*
import androidx.documentfile.provider.DocumentFile

class RedactionProcessor(
    private val context: Context,
    private val fileManagementService: FileManagementService,
    private val imageRedactionManager: ImageRedactionManager,
    // Add audioRedactionManager here later
) {
    private val TAG = "RedactionProcessor"

    // Define file types for easy filtering
    private val IMAGE_EXTENSIONS = listOf(".jpg", ".jpeg", ".png", ".webp")
    private val AUDIO_EXTENSIONS = listOf(".mp3", ".wav", ".ogg", ".m4a")

    /**
     * Filters files in a directory based on redaction options.
     */
    private fun File.shouldBeProcessed(isImageRedactionEnabled: Boolean, isAudioRedactionEnabled: Boolean): Boolean {
        if (!this.isFile) return false

        val nameLower = this.name.lowercase(Locale.ROOT)

        val isImage = IMAGE_EXTENSIONS.any { nameLower.endsWith(it) }
        val isAudio = AUDIO_EXTENSIONS.any { nameLower.endsWith(it) }

        return (isImage && isImageRedactionEnabled) || (isAudio && isAudioRedactionEnabled)
    }

    /**
     * Starts the recursive redaction process for a given folder.
     */
    suspend fun startFolderRedaction(
        rootFolder: File,
        folderUri: Uri, // Writable Content URI
        isImageRedactionEnabled: Boolean,
        isAudioRedactionEnabled: Boolean,
        onProgress: (Int, Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        if (!rootFolder.isDirectory) {
            Log.e(TAG, "Input is not a directory: ${rootFolder.absolutePath}")
            return@withContext
        }

        // --- SAF SETUP: Convert absolute path to DocumentFile structure ---
        val rootDocument = DocumentFile.fromTreeUri(context, folderUri)
            ?: run {
                Log.e(TAG, "FATAL: Could not create DocumentFile from folder URI.")
                return@withContext
            }

        // Use the File object for efficient local traversal and filtering
        val allFiles = rootFolder.walkTopDown().filter { it.isFile }.toList()
        val filesToProcess = allFiles.filter { it.shouldBeProcessed(isImageRedactionEnabled, isAudioRedactionEnabled) }

        val totalFiles = filesToProcess.size
        var processedCount = 0
        Log.d(TAG, "Found $totalFiles files to process in total.")

        for (file in filesToProcess) {
            try {
                // Find the corresponding writable DocumentFile for the current file
                val relativePath = file.absolutePath.substringAfter(rootFolder.absolutePath + "/")
                var currentDoc: DocumentFile? = rootDocument

                // Navigate the DocumentFile tree to find the target file
                relativePath.split("/").forEach { segment ->
                    currentDoc = currentDoc?.findFile(segment)
                }

                val targetDocument = currentDoc
                if (targetDocument == null || !targetDocument.canWrite()) {
                    Log.e(TAG, "Skipping: Could not find writable DocumentFile for ${file.name}")
                    continue // Skip to next file
                }

                // 1. Encrypt and Save Original
                val encryptedFileName = "${file.name}.${UUID.randomUUID()}.encrypted"

                val originalBytes = fileManagementService.processAndEncryptOriginalFile(
                    sourceUri = Uri.fromFile(file),
                    originalFileName = encryptedFileName
                )

                // 2. Perform Redaction
                val redactedBytes: ByteArray

                if (isImageRedactionEnabled && IMAGE_EXTENSIONS.any { file.name.lowercase().endsWith(it) }) {
                    // IMAGE REDACTION
                    redactedBytes = imageRedactionManager.redactImage(originalBytes)
                    Log.d(TAG, "Redaction performed. Saving redacted bytes.")
                }
                // else if (isAudioRedactionEnabled && AUDIO_EXTENSIONS.any { file.name.lowercase().endsWith(it) }) {
                //     // AUDIO REDACTION (Implement this part later)
                //     redactedBytes = audioRedactionManager.redactAudio(originalBytes)
                // }
                else {
                    // If no redaction was necessary based on the file type/checkbox, save the original file AS IS.
                    redactedBytes = originalBytes
                    Log.d(TAG, "No redaction applied. Saving original bytes (unmodified).")
                }

                // 3. CORE SERVICE: Overwrite original file using the SECURE URI
                fileManagementService.saveRedactedFileSaf(targetDocument.uri, redactedBytes)

                processedCount++
                onProgress(processedCount, totalFiles)
                Log.d(TAG, "SUCCESS: File ${file.name} overwritten via SAF URI.")

            } catch (e: Exception) {
                Log.e(TAG, "FATAL FAILURE processing file ${file.name}: ${e.message}", e)
            }
        }
        Log.d(TAG, "Folder redaction finished. Processed $processedCount of $totalFiles.")
    }
}

