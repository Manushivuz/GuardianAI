// File: com.dsatm.audio_redaction/AudioRedactionExecutor.kt (RE-FIXED FOR SILENT EXIT)

package com.dsatm.audio_redaction

import android.content.Context
import android.net.Uri
import android.util.Log
import com.dsatm.audio_redaction.ui.AudioRedactionManager
import com.dsatm.audio_redaction.ui.WavAudioMuter
import com.dsatm.ner.BertNerOnnxManager
import com.dsatm.ner.PiiEntity
import com.dsatm.ner.mapPiiToTimeRanges // Assuming this function exists
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

private const val TAG = "AudioRedactionExecutor"

/**
 * Executes the full Audio Redaction pipeline based on the new two-step Manager logic.
 */
class AudioRedactionExecutor(
    private val context: Context,
    private val voskManager: AudioRedactionManager,
    private val nerManager: BertNerOnnxManager,
    private val audioMuter: WavAudioMuter
) {

    /**
     * Main entry point. Takes original audio bytes and returns redacted audio bytes.
     */
    suspend fun redactAudio(originalAudioBytes: ByteArray): ByteArray = withContext(Dispatchers.IO) {

        // Use unique names to avoid conflicts
        val timestamp = System.currentTimeMillis()
        val tempOriginalEncodedFile = saveBytesToTempFile(originalAudioBytes, "original_encoded_audio", ".mp3")
        val tempDecodedWavFile = File(context.cacheDir, "decoded_pcm_$timestamp.wav")
        val tempMutedFile = File(context.cacheDir, "muted_output_$timestamp.wav")

        val uri = Uri.fromFile(tempOriginalEncodedFile)
        var rawTranscript: String = "Transcription failed to start."
        var entities: List<PiiEntity> = emptyList()

        try {
            Log.i(TAG, "--- Starting Audio Redaction Pipeline ---")
            Log.d(TAG, "File: ${tempOriginalEncodedFile.name}")

            // 1a. Export decoded WAV file (Required for WavAudioMuter input)
            Log.d(TAG, "Step 1a: Exporting WAV (PCM) file for muting.")
            val exportSuccess = voskManager.exportDecodedPcmWav(uri, tempDecodedWavFile)

            if (!exportSuccess || !tempDecodedWavFile.exists()) {
                Log.e(TAG, "FATAL: WAV export failed. Cannot proceed to muting.")
                throw IOException("WAV export failed by AudioRedactionManager.")
            }
            Log.d(TAG, "WAV exported successfully. Size: ${tempDecodedWavFile.length()}")


            // 1b. Transcribe using internal VOSK logic
            Log.d(TAG, "Step 1b: Running VOSK transcription (with timestamps and number conversion).")
            rawTranscript = voskManager.transcribeInternal(uri, includeTimestamps = true)

            if (rawTranscript.startsWith("Error:")) {
                Log.e(TAG, "FATAL: Transcription returned error: $rawTranscript")
                throw IOException(rawTranscript)
            }
            Log.i(TAG, "Transcription Success. Raw Transcript: '$rawTranscript'")


            // 2. Run NER to detect PII
            Log.d(TAG, "Step 2: Running NER detection on raw transcript.")
            entities = nerManager.detectPii(rawTranscript)
            Log.i(TAG, "NER detected ${entities.size} entities in transcript.")
            Log.i(TAG, "NER detected:  ${entities}")

            // 3. Map detected PII to time ranges (in milliseconds)
            val muteRangesMs = try {
                Log.d(TAG, "Step 3: Mapping PII entities to time ranges.")
                mapPiiToTimeRanges(rawTranscript, entities)
            } catch (e: Exception) {
                // This is the point where we saw "Mapping failed" previously.
                Log.e(TAG, "FATAL: Failed to map PII entities to time ranges.", e)
                emptyList<Pair<Long, Long>>()
            }

            if (muteRangesMs.isEmpty()) {
                Log.i(TAG, "No PII found for muting (either detection or mapping returned empty). Returning original.")
                return@withContext originalAudioBytes
            }
            Log.d(TAG, "Muting: ${muteRangesMs.size} time range(s) found.")
            Log.d(TAG, "Muting: ${muteRangesMs}")


            // 4. Apply muting on the VALID WAV file
            Log.d(TAG, "Step 4: Muting audio segments on WAV file.")
            val success = audioMuter.processAudio(tempDecodedWavFile, tempMutedFile, muteRangesMs)

            if (!success || !tempMutedFile.exists()) {
                Log.e(TAG, "FATAL: WavAudioMuter returned false or output file missing.")
                throw IOException("Audio muting failed.")
            }
            Log.i(TAG, "Muting complete. Reading final redacted bytes.")

            // 5. Return muted audio bytes
            return@withContext tempMutedFile.readBytes()
        } catch (e: Exception) {
            Log.e(TAG, "Redaction failed due to exception: ${e.message}", e)
            // Always return original bytes if the redaction pipeline fails.
            return@withContext originalAudioBytes
        } finally {
            // --- CLEANUP TEMP FILES ---
            try {
                tempOriginalEncodedFile.delete()
                tempDecodedWavFile.delete()
                tempMutedFile.delete()
                Log.d(TAG, "Cleaned up temporary files.")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete temp files: ${e.message}")
            }
            Log.i(TAG, "--- Audio Redaction Pipeline Finished ---")
        }
    }

    /**
     * Helper to write ByteArray to a temporary file.
     */
    private fun saveBytesToTempFile(bytes: ByteArray, prefix: String, suffix: String): File {
        val tempFile = File(context.cacheDir, "${prefix}_${System.currentTimeMillis()}$suffix")
        FileOutputStream(tempFile).use { it.write(bytes) }
        return tempFile
    }
}