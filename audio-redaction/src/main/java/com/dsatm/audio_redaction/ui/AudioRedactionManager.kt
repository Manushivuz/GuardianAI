package com.dsatm.audio_redaction.ui

import android.content.Context
import android.media.*
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.collections.ArrayList

private const val TAG = "AudioRedactionManager"
private const val TARGET_SAMPLE_RATE = 16000

class AudioRedactionManager(private val context: Context) {

    @Volatile
    private var model: Model? = null

    // --- START: Number Conversion Logic ---
    private val WORD_TO_DIGIT = mapOf(
        "zero" to "0", "one" to "1", "two" to "2", "three" to "3",
        "four" to "4", "five" to "5", "six" to "6", "seven" to "7",
        "eight" to "8", "nine" to "9", "ten" to "10", "eleven" to "11",
        "twelve" to "12", "thirteen" to "13", "fourteen" to "14", "fifteen" to "15",
        "sixteen" to "16", "seventeen" to "17", "eighteen" to "18", "nineteen" to "19",
        "twenty" to "20", "thirty" to "30", "forty" to "40", "fifty" to "50",
        "sixty" to "60", "seventy" to "70", "eighty" to "80", "ninety" to "90"
    )

    private fun convertWordNumbersToDigits(text: String): String {
        val words = text.lowercase().split(Regex("\\s+"))

        return words.joinToString(" ") { word ->
            val match = Regex("(\\p{L}+|\\d+)").find(word)
            val cleanedWord = match?.value ?: ""

            val digit = WORD_TO_DIGIT[cleanedWord]

            if (digit != null) {
                word.replace(cleanedWord, digit)
            } else {
                word
            }
        }
    }
    // --- END: Number Conversion Logic ---

    init {
        StorageService.unpack(
            context,
            "vosk-model-en-us-0.22-lgraph",
            "model",
            { m ->
                model = m
                Log.i(TAG, "Vosk model loaded.")
            },
            { e ->
                Log.e(TAG, "Failed to load Vosk model", e)
            }
        )
    }

    fun isModelLoaded(): Boolean = model != null

    /**
     * NEW: Returns the raw, lowercase, space-separated transcript (no digit conversion).
     * This output MUST be used by the PII Mapper/NER system to ensure correct character index and word matching.
     */
    suspend fun getRawLowercaseTranscript(uri: Uri): String =
        withContext(Dispatchers.IO) {
            val m = model ?: return@withContext "Error: Vosk model not loaded"

            val recognizer = Recognizer(m, TARGET_SAMPLE_RATE.toFloat())
            recognizer.setWords(true) // Ensure word-level results

            return@withContext try {
                decodeAndFeedToRecognizer(uri, recognizer)
                val finalJson = JSONObject(recognizer.finalResult)

                // Combine the word results array into a single, space-separated, lowercase string
                val arr = finalJson.optJSONArray("result") ?: JSONArray()
                val rawWords = (0 until arr.length()).map { i ->
                    arr.getJSONObject(i).optString("word", "")
                }
                rawWords.joinToString(" ").lowercase()

            } catch (e: Exception) {
                Log.e(TAG, "Raw transcription failed", e)
                "Error: ${e.message}"
            } finally {
                try {
                    recognizer.close()
                } catch (_: Exception) {}
            }
        }

    /**
     * Internal method for transcription, now used for the FINAL output only.
     * It applies the digit conversion and returns the final text.
     */
    suspend fun transcribeInternal(uri: Uri, includeTimestamps: Boolean): String =
        withContext(Dispatchers.IO) {
            val m = model ?: return@withContext "Error: Vosk model not loaded"

            val recognizer = Recognizer(m, TARGET_SAMPLE_RATE.toFloat())
            if (includeTimestamps) recognizer.setWords(true)

            return@withContext try {
                decodeAndFeedToRecognizer(uri, recognizer)
                val finalJson = JSONObject(recognizer.finalResult)

                if (!includeTimestamps) {
                    val rawText = finalJson.optString("text", "")
                    // Applies digit conversion for the final display output
                    convertWordNumbersToDigits(rawText)
                } else {
                    val arr = finalJson.optJSONArray("result") ?: JSONArray()
                    // buildTimestampText handles the conversion internally for display
                    buildTimestampText(arr)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Transcription failed", e)
                "Error: ${e.message}"
            } finally {
                try {
                    recognizer.close()
                } catch (_: Exception) {}
            }
        }

    private fun buildTimestampText(arr: JSONArray): String {
        if (arr.length() == 0) return ""
        val sb = StringBuilder()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)

            val rawWord = o.optString("word", "")
            // Ensure the word is passed in lowercase to the converter for consistency
            val w = convertWordNumbersToDigits(rawWord.lowercase())

            val s = o.optDouble("start", 0.0)
            val e = o.optDouble("end", 0.0)

            if (w.isNotEmpty()) {
                if (sb.isNotEmpty()) sb.append(" ")
                sb.append("$w [${"%.2f".format(s)}-${"%.2f".format(e)}]")
            }
        }
        return sb.toString()
    }

    // --- (The rest of the MediaCodec and WAV utility functions remain unchanged as they handle audio data) ---

    private fun decodeAndFeedToRecognizer(uri: Uri, recognizer: Recognizer) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var outputFormat: MediaFormat? = null

        // ... (Implementation logic remains the same, including resampling)
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return

            extractor.selectTrack(track)
            val fmt = extractor.getTrackFormat(track)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: return

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(fmt, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var eosIn = false
            var eosOut = false

            while (!eosOut) {
                if (!eosIn) {
                    val inIndex = codec.dequeueInputBuffer(10000)
                    if (inIndex >= 0) {
                        val inBuf = codec.getInputBuffer(inIndex)
                        val sampleSize = extractor.readSampleData(inBuf!!, 0)

                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eosIn = true
                        } else {
                            val pts = extractor.sampleTime
                            codec.queueInputBuffer(inIndex, 0, sampleSize, pts, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(info, 10000)
                when (outIndex) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        outputFormat = codec.outputFormat
                        Log.d(TAG, "Codec output format changed: $outputFormat")
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {}
                    else -> if (outIndex >= 0) {
                        val outBuf = codec.getOutputBuffer(outIndex)
                        if (info.size > 0 && outBuf != null) {
                            val currentFormat = outputFormat ?: codec.outputFormat.also { outputFormat = it }

                            val outputSampleRate = currentFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            val channels = if (currentFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                                currentFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            } else 1

                            val encoding = if (currentFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                                currentFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            } else AudioFormat.ENCODING_PCM_16BIT

                            val bytes = ByteArray(info.size)
                            outBuf.get(bytes)

                            var pcmChunk: ShortArray = when (encoding) {
                                AudioFormat.ENCODING_PCM_FLOAT -> floatToMonoPcm16(toFloatArray(bytes), channels)
                                else -> shortMono(toShortArray(bytes), channels)
                            }

                            if (outputSampleRate != TARGET_SAMPLE_RATE) {
                                pcmChunk = resampleShortArray(pcmChunk, outputSampleRate, TARGET_SAMPLE_RATE)
                            }

                            recognizer.acceptWaveForm(pcmChunk, pcmChunk.size)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0)
                            eosOut = true
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding audio", e)
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            extractor.release()
        }
    }


    fun exportDecodedPcmWav(uri: Uri, outFile: File): Boolean {
        return try {
            val (pcm, sampleRate) = decodeToPcm16(uri) ?: return false
            writeWav16(pcm, sampleRate, outFile)
            Log.i(TAG, "WAV exported at ${sampleRate} Hz.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "exportDecodedPcmWav failed", e)
            outFile.delete()
            false
        }
    }

    private fun decodeToPcm16(uri: Uri): Pair<ShortArray, Int>? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        val shorts = ArrayList<Short>()
        var finalSampleRate = TARGET_SAMPLE_RATE
        var outputFormat: MediaFormat? = null

        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null

            extractor.selectTrack(track)
            val fmt = extractor.getTrackFormat(track)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: return null

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(fmt, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var eosIn = false
            var eosOut = false

            while (!eosOut) {
                if (!eosIn) {
                    val inIndex = codec.dequeueInputBuffer(10000)
                    if (inIndex >= 0) {
                        val inBuf = codec.getInputBuffer(inIndex)
                        val sampleSize = extractor.readSampleData(inBuf!!, 0)

                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eosIn = true
                        } else {
                            val pts = extractor.sampleTime
                            codec.queueInputBuffer(inIndex, 0, sampleSize, pts, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(info, 10000)
                when (outIndex) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        outputFormat = codec.outputFormat
                        finalSampleRate = outputFormat!!.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {}
                    else -> if (outIndex >= 0) {
                        val outBuf = codec.getOutputBuffer(outIndex)
                        val bytes = ByteArray(info.size)
                        outBuf?.get(bytes)

                        val outFmt = outputFormat ?: codec.outputFormat
                        finalSampleRate = outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)

                        val channels = if (outFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        } else 1
                        val encoding = if (outFmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            outFmt.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        } else AudioFormat.ENCODING_PCM_16BIT

                        val pcmChunk: ShortArray = when (encoding) {
                            AudioFormat.ENCODING_PCM_FLOAT -> floatToMonoPcm16(toFloatArray(bytes), channels)
                            else -> shortMono(toShortArray(bytes), channels)
                        }

                        shorts.addAll(pcmChunk.toList())

                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0)
                            eosOut = true
                    }
                }
            }

            return Pair(shorts.toShortArray(), finalSampleRate)

        } catch (e: Exception) {
            Log.e(TAG, "decodeToPcm16 failed", e)
            return null
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            extractor.release()
        }
    }


    private fun writeWav16(samples: ShortArray, sampleRate: Int, outFile: File) {
        FileOutputStream(outFile).use { fos ->
            val bytes = samples.size * 2
            val header = makeWavHeader(bytes, sampleRate, 1, 16)
            fos.write(header)

            val buffer = ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN)
            for (s in samples) buffer.putShort(s)
            fos.write(buffer.array())
        }
    }

    private fun makeWavHeader(dataSize: Int, sr: Int, ch: Int, bits: Int): ByteArray {
        val header = ByteArray(44)

        fun putStr(p: Int, s: String) {
            System.arraycopy(s.toByteArray(), 0, header, p, s.length)
        }

        fun putIntLE(p: Int, v: Int) {
            header[p] = v.toByte()
            header[p+1] = (v shr 8).toByte()
            header[p+2] = (v shr 16).toByte()
            header[p+3] = (v shr 24).toByte()
        }

        fun putShortLE(p: Int, v: Int) {
            header[p] = v.toByte()
            header[p+1] = (v shr 8).toByte()
        }

        putStr(0, "RIFF")
        putIntLE(4, 36 + dataSize)
        putStr(8, "WAVE")
        putStr(12, "fmt ")
        putIntLE(16, 16)
        putShortLE(20, 1)
        putShortLE(22, ch)
        putIntLE(24, sr)
        putIntLE(28, sr * ch * bits / 8)
        putShortLE(32, ch * bits / 8)
        putShortLE(34, bits)
        putStr(36, "data")
        putIntLE(40, dataSize)

        return header
    }

    private fun toShortArray(bytes: ByteArray): ShortArray {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val sb = bb.asShortBuffer()
        val out = ShortArray(sb.remaining())
        sb.get(out)
        return out
    }

    private fun toFloatArray(bytes: ByteArray): FloatArray {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val fb = bb.asFloatBuffer()
        val out = FloatArray(fb.remaining())
        fb.get(out)
        return out
    }

    private fun shortMono(src: ShortArray, ch: Int): ShortArray {
        val out = ShortArray(src.size / ch)
        var idx = 0
        for (i in out.indices) {
            var sum = 0
            for (c in 0 until ch) sum += src[idx++]
            out[i] = (sum / ch).toShort()
        }
        return out
    }

    private fun floatToMonoPcm16(src: FloatArray, ch: Int): ShortArray {
        val out = ShortArray(src.size / ch)
        var idx = 0
        for (i in out.indices) {
            var sum = 0f
            for (c in 0 until ch) sum += src[idx++]
            val v = (sum / ch * Short.MAX_VALUE).toInt()
            out[i] = v.coerceIn(-32768, 32767).toShort()
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
}