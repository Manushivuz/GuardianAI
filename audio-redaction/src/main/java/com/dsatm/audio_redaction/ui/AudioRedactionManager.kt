package com.dsatm.audio_redaction.ui

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

// Vosk standard sample rate
private const val TARGET_SAMPLE_RATE = 16000
private const val TAG = "AudioRedactionManager"

/**
 * Manages Vosk model loading and audio transcription.
 */
class AudioRedactionManager(private val context: Context) {

    @Volatile
    private var model: Model? = null
    // Use a Deferred to handle asynchronous model loading
    private val modelDeferred: CompletableDeferred<Model?> = CompletableDeferred()

    init {
        try {
            StorageService.unpack(
                context,
                "vosk-model-en-us-0.22-lgraph",
                "model",
                // Success Lambda
                { unpackedModel: Model? ->
                    model = unpackedModel
                    if (!modelDeferred.isCompleted) modelDeferred.complete(unpackedModel)
                    Log.i(TAG, "Vosk model unpacked successfully")
                },
                // Failure Lambda
                { e: Exception? ->
                    Log.e(TAG, "Vosk model unpack failed", e)
                    if (!modelDeferred.isCompleted) modelDeferred.complete(null)
                }
            )
        } catch (e: Exception) {
            // Complete Catch Block
            Log.e(TAG, "StorageService.unpack threw", e)
            if (!modelDeferred.isCompleted) modelDeferred.complete(null)
        }
    }

    /**
     * Checks if the Vosk model is loaded and ready for use.
     */
    fun isModelLoaded(): Boolean = model != null || (modelDeferred.isCompleted && modelDeferred.getCompletedOrNull() != null)

    private fun <T> CompletableDeferred<T>.getCompletedOrNull(): T? =
        try {
            if (isCompleted) getCompleted() else null
        } catch (_: Exception) {
            null
        }

    /**
     * Transcribes audio without word timestamps.
     */
    fun transcribeAudio(uri: Uri, onResult: (String) -> Unit) {
        transcribeInternal(uri, includeTimestamps = false, onResult)
    }

    /**
     * Transcribes audio and includes word timestamps in the result string.
     */
    fun transcribeAudioWithTimestamps(uri: Uri, onResult: (String) -> Unit) {
        transcribeInternal(uri, includeTimestamps = true, onResult)
    }

    fun transcribeInternal(uri: Uri, includeTimestamps: Boolean, onResult: (String) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            var recognizer: Recognizer? = null
            try {
                // 1. Await model loading
                val loadedModel = model ?: run {
                    val awaited = modelDeferred.await()
                    if (awaited == null) {
                        withContext(Dispatchers.Main) { onResult("Vosk model failed to load.") }
                        return@launch
                    }
                    model = awaited
                    awaited
                }

                // 2. Initialize Recognizer
                recognizer = Recognizer(loadedModel, TARGET_SAMPLE_RATE.toFloat())
                if (includeTimestamps) {
                    try { recognizer.setWords(true) } catch (t: Throwable) {
                        Log.w(TAG, "Failed to enable word timestamps on Recognizer", t)
                    }
                }

                // 3. Decode and feed audio data
                decodeAndFeedToRecognizer(uri, recognizer)

                // 4. Get final result
                // Using .toString() forces Java String resolution, resolving compiler ambiguity.
                val finalJsonStr: String = recognizer.finalResult
                Log.d(TAG, "Vosk finalResult: $finalJsonStr")
                val finalJson = JSONObject(finalJsonStr)

                val resultText: String
                if (includeTimestamps) {
                    val wordsArr: JSONArray = finalJson.optJSONArray("result") ?: JSONArray()
                    resultText = if (wordsArr.length() == 0) {
                        finalJson.optString("text", "No transcription with timestamps available.")
                    } else {
                        buildTimestampedString(wordsArr)
                    }
                } else {
                    resultText = finalJson.optString("text", "No transcription")
                }

                withContext(Dispatchers.Main) { onResult(resultText.ifBlank { "No transcription" }) }

            } catch (ce: CancellationException) {
                Log.w(TAG, "Transcription cancelled", ce)
                withContext(Dispatchers.Main) { onResult("Transcription cancelled") }
            } catch (e: Exception) {
                Log.e(TAG, "Transcription error", e)
                withContext(Dispatchers.Main) { onResult("Error: ${e.localizedMessage ?: e.message}") }
            } finally {
                // IMPORTANT: Close the recognizer to free native resources
                try { recognizer?.close() } catch (_: Exception) {}
            }
        }
    }

    private fun buildTimestampedString(wordsArr: JSONArray): String {
        val sb = StringBuilder()
        for (i in 0 until wordsArr.length()) {
            val w = wordsArr.getJSONObject(i)
            val word = w.optString("word", "")
            val start = w.optDouble("start", 0.0)
            val end = w.optDouble("end", 0.0)
            if (sb.isNotEmpty()) sb.append(" ")
            sb.append("$word [${"%.2f".format(start)}-${"%.2f".format(end)}]")
        }
        return sb.toString()
    }

    /**
     * Decodes the audio file using MediaCodec and feeds the processed PCM data to the Vosk Recognizer.
     */
    private fun decodeAndFeedToRecognizer(uri: Uri, recognizer: Recognizer) {
        val extractor = MediaExtractor()
        // FIX: Declare 'codec' outside the try block for access in 'finally'.
        var codec: MediaCodec? = null

        try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IllegalArgumentException("No audio track found in file")

            extractor.selectTrack(trackIndex)
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val inputMime = inputFormat.getString(MediaFormat.KEY_MIME)!!

            codec = MediaCodec.createDecoderByType(inputMime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var sawInputEOS = false
            var sawOutputEOS = false
            var outputFormat: MediaFormat? = null

            while (!sawOutputEOS) {
                // 1. Handle Input
                if (!sawInputEOS) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inIndex)
                        inputBuffer?.clear()

                        val sampleSize = if (inputBuffer != null) extractor.readSampleData(inputBuffer, 0) else -1
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEOS = true
                        } else {
                            val pts = extractor.sampleTime
                            codec.queueInputBuffer(inIndex, 0, sampleSize, pts, 0)
                            extractor.advance()
                        }
                    }
                }

                // 2. Handle Output
                val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                when (outIndex) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        outputFormat = codec.outputFormat
                        Log.d(TAG, "Output format changed: $outputFormat")
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // Wait for more data
                    }
                    else -> { // outIndex >= 0
                        val outBuf = codec.getOutputBuffer(outIndex)
                        if (bufferInfo.size > 0 && outBuf != null) {
                            val currentFormat = outputFormat ?: codec.outputFormat.also { outputFormat = it }

                            val outputSampleRate = currentFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            val outputChannels = currentFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            val outputEncoding = if (currentFormat.containsKey(MediaFormat.KEY_PCM_ENCODING))
                                currentFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT

                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            val chunk = ByteArray(bufferInfo.size)
                            outBuf.get(chunk)

                            if (outputEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                var floats = byteArrayToFloatArrayLE(chunk)
                                floats = interleavedFloatToMono(floats, outputChannels)
                                floats = if (outputSampleRate != TARGET_SAMPLE_RATE)
                                    resampleFloatArray(floats, outputSampleRate, TARGET_SAMPLE_RATE)
                                else floats
                                recognizer.acceptWaveForm(floats, floats.size)
                            } else {
                                var shorts = byteArrayToShortArrayLE(chunk)
                                shorts = interleavedShortToMono(shorts, outputChannels)
                                shorts = if (outputSampleRate != TARGET_SAMPLE_RATE)
                                    resampleShortArray(shorts, outputSampleRate, TARGET_SAMPLE_RATE)
                                else shorts
                                recognizer.acceptWaveForm(shorts, shorts.size)
                            }
                        }

                        codec.releaseOutputBuffer(outIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEOS = true
                    }
                }
            }
        } finally {
            // Ensure resources are released
            try { extractor.release() } catch (_: Exception) {}
            try { codec?.stop() } catch (_: Exception) {} // Use safe call operator (?.)
            try { codec?.release() } catch (_: Exception) {} // Use safe call operator (?.)
        }
    }

    // --- Helper functions ---

    private fun byteArrayToShortArrayLE(bytes: ByteArray): ShortArray {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val asShort = bb.asShortBuffer()
        val shorts = ShortArray(asShort.remaining())
        asShort.get(shorts)
        return shorts
    }

    private fun byteArrayToFloatArrayLE(bytes: ByteArray): FloatArray {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val asFloat = bb.asFloatBuffer()
        val floats = FloatArray(asFloat.remaining())
        asFloat.get(floats)
        return floats
    }

    private fun interleavedShortToMono(src: ShortArray, channels: Int): ShortArray {
        if (channels <= 1) return src
        val monoLen = src.size / channels
        val out = ShortArray(monoLen)
        for (oi in 0 until monoLen) {
            var sum = 0L
            val si = oi * channels
            for (c in 0 until channels) sum += src[si + c].toInt()
            out[oi] = (sum / channels).toShort()
        }
        return out
    }

    private fun interleavedFloatToMono(src: FloatArray, channels: Int): FloatArray {
        if (channels <= 1) return src
        val monoLen = src.size / channels
        val out = FloatArray(monoLen)
        for (oi in 0 until monoLen) {
            var sum = 0f
            val si = oi * channels
            for (c in 0 until channels) sum += src[si + c]
            out[oi] = sum / channels
        }
        return out
    }

    private fun resampleShortArray(input: ShortArray, inRate: Int, outRate: Int): ShortArray {
        if (inRate == outRate) return input
        val ratio = inRate.toDouble() / outRate.toDouble()
        val outLen = max(1, (input.size / ratio).toInt())
        val out = ShortArray(outLen)
        for (i in 0 until outLen) {
            val srcPos = i * ratio
            val i0 = floor(srcPos).toInt().coerceIn(0, input.size - 1)
            val i1 = min(i0 + 1, input.size - 1)
            val frac = srcPos - i0
            val sample = ((1.0 - frac) * input[i0].toDouble() + frac * input[i1].toDouble()).toInt()
            out[i] = sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return out
    }

    private fun resampleFloatArray(input: FloatArray, inRate: Int, outRate: Int): FloatArray {
        if (inRate == outRate) return input
        val ratio = inRate.toDouble() / outRate.toDouble()
        val outLen = max(1, (input.size / ratio).toInt())
        val out = FloatArray(outLen)
        for (i in 0 until outLen) {
            val srcPos = i * ratio
            val i0 = floor(srcPos).toInt().coerceIn(0, input.size - 1)
            val i1 = min(i0 + 1, input.size - 1)
            val frac = srcPos - i0
            out[i] = ((1.0 - frac) * input[i0] + frac * input[i1]).toFloat()
        }
        return out
    }
}