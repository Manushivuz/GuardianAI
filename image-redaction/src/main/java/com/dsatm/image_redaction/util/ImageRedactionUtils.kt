package com.dsatm.image_redaction.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import kotlin.math.min

// NOTE: RecognizedWord is assumed to be defined as data class RecognizedWord(val text: String, val confidence: Float, val boundingBox: Rect)

object ImageRedactionUtils {
    private const val TAG = "ImageRedactionUtils"

    // --- REGEX DEFINITIONS REMAIN UNCHANGED ---
    private val sensitiveDataRegex = """
        # --- Explicit Keywords (Redact the word itself if it's a value, not a label) ---
        \b(?:confidential|secret|private|pii)\b | 
        # --- Email Addresses ---\
        [a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,63} |
        # --- Phone Numbers (standard 10-12 digits with separators, like 01234 567890) ---
        \b\d{4,5}[-.\s]?\d{6,7}\b | 
        \b(?:Tel|Phone|Mobile):\s?\d[\d\s-]*\d\b | 
        # --- Aadhar / UID (4-4-4 pattern) ---
        \b\d{4}[-.\s]\d{4}[-.\s]\d{4}\b |
        # --- Alphanumeric IDs (DM1234567MJPS, License/Voter/Passport IDs, PAN) ---
        \b[A-Z]{2,4}\s?\d{6,10}\s?\w*\b | 
        \b[A-Z]{3}[PABCFGHLJT]{1}[A-Z]{1}\d{4}[A-Z]{1}\b | 
        # --- Dates (DD/MM/YYYY) ---
        \b\d{1,2}[-./]\d{1,2}[-./]\d{2,4}\b |
        # --- Numeric PIN/Passwords (e.g., 1234, 123456) ---
        \b\d{4,6}\b |
        # --- Placeholder asterisks ---
        \*{4,}
    """.trimIndent().toRegex(
        setOf(
            RegexOption.IGNORE_CASE,
            RegexOption.COMMENTS,
            RegexOption.DOT_MATCHES_ALL
        )
    )
    private val sensitiveLabelRegex =
        """\b(?:Name|ID Number|ID|Number|No|Issued|Expires|DOB|Date of Birth|Username|User Name|Email|Tel|Phone|Mobile|Password|PIN|SSN|Aadhar|Voter ID|Passport No|HH/ Name)\s*[:/]?\s*""".toRegex(
            RegexOption.IGNORE_CASE
        )
    // --- END REGEX DEFINITIONS ---

    // NOTE: getAllImagesInFolder is omitted for brevity but remains unchanged.

    suspend fun redactSensitiveInImage(context: Context, originalBitmap: Bitmap): Bitmap {
        Log.d(TAG, "Starting redaction process for internal bitmap.")

        val mutableBitmap = originalBitmap.copy(Bitmap.Config.ARGB_8888, true)
        val recognizedWords = com.dsatm.image_redaction.util.MLKitTextRecognizer.recognizeText(context, mutableBitmap)

        val wordsToRedact = mutableSetOf<com.dsatm.image_redaction.util.RecognizedWord>()
        val piiLogList = mutableListOf<String>()

        // Step 1: Directly identify sensitive VALUES
        for (word in recognizedWords) {
            val text = word.text.trim()
            var matched = false

            if (sensitiveDataRegex.containsMatchIn(text) && !sensitiveLabelRegex.matches(text)) {
                wordsToRedact.add(word)
                matched = true
            } else if (text.matches("\\b\\d{4}[-.\\s]\\d{4}[-.\\s]\\d{4}\\b".toRegex()) ||
                text.matches("\\b[A-Z]{2,4}\\s?\\d{6,10}\\s?\\w*\\b".toRegex()) ||
                text.matches("\\b[A-Z]{3}[PABCFGHLJT]{1}[A-Z]{1}\\d{4}[A-Z]{1}\\b".toRegex()) ||
                text.matches("\\*{4,}".toRegex())) {
                wordsToRedact.add(word)
                matched = true
            }

            if (matched) {
                piiLogList.add("Detected: ${word.text} | Type: Regex/Pattern | Conf: ${"%.2f".format(word.confidence)}")
            }
        }

        // Step 2: Identify words that follow sensitive labels (Label Follow Heuristic)
        for (i in recognizedWords.indices) {
            val currentWord = recognizedWords[i]
            if (sensitiveLabelRegex.containsMatchIn(currentWord.text.trim())) {
                val wordsToScan = if (currentWord.text.lowercase().contains("name") || currentWord.text.lowercase().contains("id")) 2 else 1

                for (j in 1..wordsToScan) {
                    val nextIndex = i + j
                    if (nextIndex < recognizedWords.size) {
                        val nextWord = recognizedWords[nextIndex]
                        val yDelta = kotlin.math.abs(currentWord.boundingBox.centerY() - nextWord.boundingBox.centerY())

                        if (yDelta < currentWord.boundingBox.height() * 0.75 && !wordsToRedact.contains(nextWord)) {
                            wordsToRedact.add(nextWord)
                            piiLogList.add("Detected: ${nextWord.text} | Type: Heuristic Follow | Conf: ${"%.2f".format(nextWord.confidence)}")
                        }
                    }
                }
            }
        }

        // Step 3: SPECIAL CASE: Redact QR Code area for Aadhar Card (Added to logs if triggered)
        val isAadharDoc = recognizedWords.any { it.text.contains("आधार", ignoreCase = true) || it.text.contains("Aadhar", ignoreCase = true) }
        if (isAadharDoc) {
            val qrCodeArea = Rect((mutableBitmap.width * 0.65).toInt(), (mutableBitmap.height * 0.60).toInt(), mutableBitmap.width, mutableBitmap.height)
            if (qrCodeArea.width() > 50 && qrCodeArea.height() > 50) {
                wordsToRedact.add(com.dsatm.image_redaction.util.RecognizedWord("QR_CODE_AREA", 1.0f, qrCodeArea))
                piiLogList.add("Detected: QR_CODE_AREA | Type: Area Match | Conf: 1.00")
            }
        }

        // --- LOG THE RESULTS FOR OFFLINE ANALYSIS ---
        if (piiLogList.isNotEmpty()) {
            Log.w(TAG, "--- METRICS LOG START ---")
            piiLogList.forEach { logEntry ->
                Log.w(TAG, logEntry)
            }
            Log.w(TAG, "Total unique entities for image: ${wordsToRedact.size}")
            Log.w(TAG, "--- METRICS LOG END ---")
        } else {
            Log.i(TAG, "No sensitive content detected for redaction.")
        }
        // --- END LOGGING ---


        // --- VISUAL APPLICATION (Redaction/Blurring) ---
        if (wordsToRedact.isNotEmpty()) {
            val canvas = Canvas(mutableBitmap)
            val paint = Paint().apply { isAntiAlias = true }

            for (word in wordsToRedact) {
                val safeRect = Rect(
                    word.boundingBox.left.coerceIn(0, mutableBitmap.width - 1),
                    word.boundingBox.top.coerceIn(0, mutableBitmap.height - 1),
                    word.boundingBox.right.coerceIn(1, mutableBitmap.width),
                    word.boundingBox.bottom.coerceIn(1, mutableBitmap.height)
                )

                if (safeRect.width() > 0 && safeRect.height() > 0) {
                    val blurred = blurBitmapRegion(mutableBitmap, safeRect)
                    val sourceRect = Rect(0, 0, blurred.width, blurred.height)
                    canvas.drawBitmap(blurred, sourceRect, safeRect, paint)
                }
            }
        }

        return mutableBitmap
    }

    // NOTE: blurBitmapRegion is assumed to be defined below and remains unchanged.

    private fun blurBitmapRegion(src: Bitmap, rect: Rect, radius: Int = 20): Bitmap {
        // [Existing blur implementation]
        Log.d(TAG, "Applying blur to region: $rect")

        val left = rect.left.coerceAtLeast(0)
        val top = rect.top.coerceAtLeast(0)
        val actualWidth = (rect.right - left).coerceAtMost(src.width - left).coerceAtLeast(1)
        val actualHeight = (rect.bottom - top).coerceAtMost(src.height - top).coerceAtLeast(1)

        val cropped = try {
            Bitmap.createBitmap(src, left, top, actualWidth, actualHeight)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Error creating bitmap region: ${e.message}. Using 1x1 default.", e)
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }


        val blurred = cropped.copy(cropped.config ?: Bitmap.Config.ARGB_8888, true)
        val w = blurred.width
        val h = blurred.height
        val pixels = IntArray(w * h)
        blurred.getPixels(pixels, 0, w, 0, 0, w, h)
        val newPixels = pixels.copyOf()

        val div = radius * 2 + 1
        val dv = IntArray(256 * div)
        for (i in 0 until 256 * div) {
            dv[i] = i / div
        }
        // Horizontal Pass
        for (y in 0 until h) {
            var rSum = 0
            var gSum = 0
            var bSum = 0
            for (i in -radius..radius) {
                val xClamp = (0 + i).coerceIn(0, w - 1)
                val p = pixels[y * w + xClamp]
                rSum += (p shr 16) and 0xFF
                gSum += (p shr 8) and 0xFF
                bSum += p and 0xFF
            }
            for (x in 0 until w) {
                newPixels[y * w + x] = (0xFF shl 24) or (dv[rSum] shl 16) or (dv[gSum] shl 8) or dv[bSum]
                val pIn = pixels[y * w + (x + radius + 1).coerceIn(0, w - 1)]
                val pOut = pixels[y * w + (x - radius).coerceIn(0, w - 1)]
                rSum += (pIn shr 16) and 0xFF
                gSum += (pIn shr 8) and 0xFF
                bSum += pIn and 0xFF
                rSum -= (pOut shr 16) and 0xFF
                gSum -= (pOut shr 8) and 0xFF
                bSum -= pOut and 0xFF
            }
        }
        // Vertical Pass
        for (x in 0 until w) {
            var rSum = 0
            var gSum = 0
            var bSum = 0
            for (i in -radius..radius) {
                val yClamp = (0 + i).coerceIn(0, h - 1)
                val p = newPixels[yClamp * w + x]
                rSum += (p shr 16) and 0xFF
                gSum += (p shr 8) and 0xFF
                bSum += p and 0xFF
            }
            for (y in 0 until h) {
                val pIn = newPixels[(y + radius + 1).coerceIn(0, h - 1) * w + x]
                val pOut = newPixels[(y - radius).coerceIn(0, h - 1) * w + x]
                rSum += (pIn shr 16) and 0xFF
                gSum += (pIn shr 8) and 0xFF
                bSum += pIn and 0xFF
                rSum -= (pOut shr 16) and 0xFF
                gSum -= (pOut shr 8) and 0xFF
                bSum -= pOut and 0xFF
                pixels[y * w + x] = (0xFF shl 24) or (dv[rSum] shl 16) or (dv[gSum] shl 8) or dv[bSum]
            }
        }
        blurred.setPixels(pixels, 0, w, 0, 0, w, h)
        return blurred
    }
}