package com.dsatm.guardianai

import android.content.Context
import android.net.Uri
import android.util.Log
import com.dsatm.guardianai.security.FileManagementService
import com.dsatm.image_redaction.ImageRedactionManager
import com.dsatm.audio_redaction.AudioRedactionExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.*
import androidx.documentfile.provider.DocumentFile
import com.dsatm.guardianai.ui.screens.analyzeFolderForRedaction // NEW IMPORT

class RedactionProcessor(
      private val context: Context,
      private val fileManagementService: FileManagementService,
      private val imageRedactionManager: ImageRedactionManager,
      private val audioRedactionExecutor: AudioRedactionExecutor
) {
      private val TAG = "RedactionProcessor"

      private val IMAGE_EXTENSIONS = listOf(".jpg", ".jpeg", ".png", ".webp")
      private val AUDIO_EXTENSIONS = listOf(".mp3", ".wav", ".ogg", ".m4a")

      private fun File.shouldBeProcessed(
            isImageRedactionEnabled: Boolean,
        isAudioRedactionEnabled: Boolean
      ): Boolean {
            if (!this.isFile) return false

            val nameLower = this.name.lowercase(Locale.ROOT)
            val isImage = IMAGE_EXTENSIONS.any { nameLower.endsWith(it) }
            val isAudio = AUDIO_EXTENSIONS.any { nameLower.endsWith(it) }

            return (isImage && isImageRedactionEnabled) || (isAudio && isAudioRedactionEnabled)
          }

      suspend fun startFolderRedaction(
            rootFolder: File,
        folderUri: Uri,
        isImageRedactionEnabled: Boolean,
        isAudioRedactionEnabled: Boolean,
        onProgress: (Int, Int) -> Unit
      ) = withContext(Dispatchers.IO) {
            if (!rootFolder.isDirectory) {
                  Log.e(TAG, "Input is not a directory: ${rootFolder.absolutePath}")
                  return@withContext
                }

            val rootDocument = DocumentFile.fromTreeUri(context, folderUri)
              ?: run {
                    Log.e(TAG, "FATAL: Could not create DocumentFile from folder URI.")
                    return@withContext
                  }

            val allFiles = rootFolder.walkTopDown().filter { it.isFile }.toList()
            val filesToProcess =
              allFiles.filter { it.shouldBeProcessed(isImageRedactionEnabled, isAudioRedactionEnabled) }

            val totalFiles = filesToProcess.size
            var processedCount = 0
            var totalLatency = 0.0
            Log.d(TAG, "Found $totalFiles files to process in total.")

            for (file in filesToProcess) {
                  try {
                    val relativePath = file.absolutePath.substringAfter(rootFolder.absolutePath + "/")
                    var currentDoc: DocumentFile? = rootDocument

                    relativePath.split("/").forEach { segment ->
                      currentDoc = currentDoc?.findFile(segment)
                    }

                    val targetDocument = currentDoc
                    if (targetDocument == null || !targetDocument.canWrite()) {
                      Log.e(TAG, "Skipping: Could not find writable DocumentFile for ${file.name}")
                      continue
                    }

                    // 1. Encrypt and Save Original
                    // Append original file extension before .encrypted suffix for viewer/decryption logic
                    val originalExtension = file.extension.lowercase(Locale.ROOT)
                    val encryptedFileName = "${file.name}.${UUID.randomUUID()}.$originalExtension.encrypted" // FIX: Include original extension

                    val originalBytes = fileManagementService.processAndEncryptOriginalFile(
                      sourceUri = Uri.fromFile(file),
                      originalFileName = encryptedFileName
                    )

                    // 2. Perform Redaction with latency measurement
                    val redactedBytes: ByteArray
                    val nameLower = file.name.lowercase(Locale.ROOT)

                    val startTime = System.nanoTime()

                    redactedBytes = when {
                      isImageRedactionEnabled && IMAGE_EXTENSIONS.any { nameLower.endsWith(it) } -> {
                        Log.d(TAG, "Image redaction initiated for: ${file.name}")
                        imageRedactionManager.redactImage(originalBytes)
                      }

                      isAudioRedactionEnabled && AUDIO_EXTENSIONS.any { nameLower.endsWith(it) } -> {
                        Log.d(TAG, "Audio redaction initiated for: ${file.name}")
                        audioRedactionExecutor.redactAudio(originalBytes)
                      }

                      else -> {
                        originalBytes
                      }
                    }

                    val endTime = System.nanoTime()
                    val inferenceTimeMs = (endTime - startTime) / 1_000_000.0
                    totalLatency += inferenceTimeMs

                    Log.d(TAG, "Inference Latency for ${file.name}: $inferenceTimeMs ms")

                    if (redactedBytes !== originalBytes) {
                      Log.d(TAG, "Redaction performed. Saving redacted bytes.")
                    } else {
                      Log.d(TAG, "No redaction applied. Saving original bytes (unmodified).")
                    }

                    // 3. Save redacted output
                    fileManagementService.saveRedactedFileSaf(targetDocument.uri, redactedBytes)

                    processedCount++
                    // Report progress back to the UI
                    withContext(Dispatchers.Main) {
                      onProgress(processedCount, totalFiles)
                    }
                    Log.d(TAG, "SUCCESS: File ${file.name} overwritten via SAF URI.")

                  } catch (e: Exception) {
                    Log.e(TAG, "FATAL FAILURE processing file ${file.name}: ${e.message}", e)
                  }
                }

            if (processedCount > 0) {
                  val avgLatency = totalLatency / processedCount
                  Log.d(TAG, "Average Inference Latency across $processedCount files: $avgLatency ms")
                } else {
                  Log.d(TAG, "No files processed — average latency not computed.")
                }

            Log.d(TAG, "Folder redaction finished. Processed $processedCount of $totalFiles.")
          }
}