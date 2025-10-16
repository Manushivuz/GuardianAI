package com.dsatm.audio_redaction

import android.content.Context
import android.net.Uri
import android.util.Log
import com.dsatm.audio_redaction.ui.AudioRedactionManager // Use the existing Vosk/Decode logic
import com.dsatm.audio_redaction.ui.WavAudioMuter // Use the existing WAV muter
import com.dsatm.ner.BertNerOnnxManager
import com.dsatm.ner.mapPiiToTimeRanges
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Executes the full Audio Redaction pipeline: Decode -> Transcribe -> NER -> Mute -> Encode.
 * Exposes a clean ByteArray API to the RedactionProcessor.
 */
class AudioRedactionExecutor(
    private val context: Context,
    private val voskManager: AudioRedactionManager,
    private val nerManager: BertNerOnnxManager,
    private val audioMuter: WavAudioMuter
) {
    private val TAG = "AudioRedactionExecutor"

    /**
     * Entry point for the RedactionProcessor.
     * Takes the original audio bytes and returns the redacted audio bytes.
     */
    suspend fun redactAudio(originalAudioBytes: ByteArray): ByteArray {
        val tempInputFile = saveBytesToTempFile(originalAudioBytes, "input_audio", ".wav")
        val tempMutedFile = File(context.cacheDir, "muted_output_${System.currentTimeMillis()}.wav")

        try {
            // 1. Transcribe (Await is handled internally by VoskManager)
            val rawTranscript = suspendCoroutine<String> { continuation ->
                val uri = Uri.fromFile(tempInputFile)
                voskManager.transcribeInternal(uri, includeTimestamps = true) { result ->
                    continuation.resume(result)
                }
            }

            if (rawTranscript.startsWith("Error")) {
                throw IOException("Transcription failed: $rawTranscript")
            }

            // 2. Analyze PII and Map Time Ranges
            val cleanTranscript = rawTranscript.replace(Regex("\\[[\\d.]+-[\\d.]+\\]"), "").trim()
            val entities = nerManager.detectPii(rawTranscript)

            val muteRangesMs = try {
                mapPiiToTimeRanges(rawTranscript, entities)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to map PII to time ranges: ${e.message}")
                emptyList<Pair<Long, Long>>()
            }

            if (muteRangesMs.isEmpty()) {
                Log.i(TAG, "No PII found for muting. Returning original audio bytes.")
                return originalAudioBytes
            }

            // 3. Mute Audio (WAV file processing)
            val success = audioMuter.processAudio(tempInputFile, tempMutedFile, muteRangesMs)

            if (!success || !tempMutedFile.exists()) {
                throw IOException("Audio muting failed or output file not created.")
            }

            // 4. Return muted bytes
            return tempMutedFile.readBytes()

        } finally {
            // Cleanup temporary files
            tempInputFile.delete()
            tempMutedFile.delete()
        }
    }

    // Helper to write bytes to a temp file, necessary for MediaCodec/WavAudioMuter
    private fun saveBytesToTempFile(bytes: ByteArray, prefix: String, suffix: String): File {
        val tempFile = File(context.cacheDir, "${prefix}_${System.currentTimeMillis()}$suffix")
        FileOutputStream(tempFile).use { it.write(bytes) }
        return tempFile
    }
}