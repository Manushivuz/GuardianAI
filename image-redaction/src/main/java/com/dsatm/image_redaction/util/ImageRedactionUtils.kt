package com.dsatm.image_redaction.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

// Assuming MLKitTextRecognizer and RecognizedWord classes are available in your project scope

object ImageRedactionUtils {
    private const val TAG = "ImageRedactionUtils"

    // Comprehensive regex to match explicit PII data VALUES (IDs, Numbers, Emails, Dates, Keywords)
    // The focus here is on patterns that represent data values, not just labels.
    private val sensitiveDataRegex = """
        # --- Explicit Keywords (Redact the word itself if it's a value, not a label) ---
        \b(?:confidential|secret|private|pii)\b | 

        # --- Email Addresses ---\
        [a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,63} |

        # --- Phone Numbers (standard 10-12 digits with separators, like 01234 567890) ---
        \b\d{4,5}[-.\s]?\d{6,7}\b | # e.g., 01234 567890
        \b(?:Tel|Phone|Mobile):\s?\d[\d\s-]*\d\b | # e.g., Tel: 123-456-7890

        # --- Aadhar / UID (4-4-4 pattern) ---
        \b\d{4}[-.\s]\d{4}[-.\s]\d{4}\b |
        
        # --- Alphanumeric IDs (DM1234567MJPS, License/Voter/Passport IDs, PAN) ---
        \b[A-Z]{2,4}\s?\d{6,10}\s?\w*\b | # Generic alphanumeric IDs like AB1234567C
        \b[A-Z]{3}[PABCFGHLJT]{1}[A-Z]{1}\d{4}[A-Z]{1}\b | # Indian PAN Card format (AAA P A 0000 A)

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

    // Regex to identify labels that precede sensitive information.
    private val sensitiveLabelRegex =
        """\b(?:Name|ID Number|ID|Number|No|Issued|Expires|DOB|Date of Birth|Username|User Name|Email|Tel|Phone|Mobile|Password|PIN|SSN|Aadhar|Voter ID|Passport No|HH/ Name)\s*[:/]?\s*""".toRegex(
            RegexOption.IGNORE_CASE
        )

    fun getAllImagesInFolder(context: Context, treeUri: Uri): List<DocumentFile> {
        Log.d(TAG, "Scanning for images in folder: $treeUri")
        val documents = mutableListOf<DocumentFile>()
        val rootDocument = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()

        fun findImages(document: DocumentFile) {
            if (document.isDirectory) {
                document.listFiles().forEach { child ->
                    findImages(child)
                }
            } else if (document.isFile && document.type?.startsWith("image/") == true) {
                documents.add(document)
                Log.d(TAG, "Found image file: ${document.name}")
            }
        }
        findImages(rootDocument)
        Log.d(TAG, "Finished scanning. Found ${documents.size} images.")
        return documents
    }

    suspend fun redactSensitiveInImage(context: Context, inputDoc: DocumentFile, outputDir: File): File? {
        Log.d(TAG, "Starting redaction process for image: ${inputDoc.name}")

        val bitmap = context.contentResolver.openInputStream(inputDoc.uri)?.use { inputStream ->
            BitmapFactory.decodeStream(inputStream)
        } ?: return null

        val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)

        val recognizedWords = MLKitTextRecognizer.recognizeText(context, inputDoc.uri)

        val wordsToRedact = mutableSetOf<RecognizedWord>()

        // --- Redaction Strategy ---

        // Step 1: Directly identify words that are sensitive VALUES based on patterns.
        for (word in recognizedWords) {
            val text = word.text.trim()
            if (sensitiveDataRegex.containsMatchIn(text) && !sensitiveLabelRegex.matches(text)) {
                // Only add if it's a sensitive data pattern AND NOT just a label.
                wordsToRedact.add(word)
                Log.d(TAG, "Direct match PII value for redaction: \"${word.text}\"")
            } else if (text.matches("\\b\\d{4}[-.\\s]\\d{4}[-.\\s]\\d{4}\\b".toRegex()) ||  // Aadhar
                text.matches("\\b[A-Z]{2,4}\\s?\\d{6,10}\\s?\\w*\\b".toRegex()) ||    // Alphanumeric ID
                text.matches("\\b[A-Z]{3}[PABCFGHLJT]{1}[A-Z]{1}\\d{4}[A-Z]{1}\\b".toRegex()) || // PAN
                text.matches("\\*{4,}".toRegex())) { // Asterisk placeholders
                // These are definite PII values, force add them if not already in the set
                wordsToRedact.add(word)
                Log.d(TAG, "Forcing redaction of definite PII value: \"${word.text}\"")
            }
        }

        // Step 2: Identify words that follow sensitive labels (like Name, ID Number, etc.)
        for (i in recognizedWords.indices) {
            val currentWord = recognizedWords[i]
            val currentText = currentWord.text.trim()

            // Check if the current word is a sensitive label
            if (sensitiveLabelRegex.containsMatchIn(currentText)) {
                Log.d(TAG, "Sensitive label found: \"${currentWord.text}\"")

                // Determine how many words to scan after the label
                val wordsToScan = if (currentText.lowercase().contains("name") ||
                    currentText.lowercase().contains("id number") ||
                    currentText.lowercase().contains("id") ||
                    currentText.lowercase().contains("no") ||
                    currentText.lowercase().contains("num") ||
                    currentText.lowercase().contains("passport") ||
                    currentText.lowercase().contains("aadhar")) 2 else 1

                for (j in 1..wordsToScan) {
                    val nextIndex = i + j
                    if (nextIndex < recognizedWords.size) {
                        val nextWord = recognizedWords[nextIndex]

                        // Heuristic: Ensure the next word is on a similar horizontal line as the label
                        val yDelta = Math.abs(currentWord.boundingBox.centerY() - nextWord.boundingBox.centerY())
                        val xDistance = nextWord.boundingBox.left - currentWord.boundingBox.right

                        // FIXED ERROR: Replaced .height and .width with .height() and .width()
                        if (yDelta < currentWord.boundingBox.height() * 0.75 && xDistance < currentWord.boundingBox.width() * 2) {
                            val nextWordText = nextWord.text.lowercase().trim()

                            // Prevent redacting common filler/label words if they happen to follow a label.
                            if (!nextWordText.matches("\\b(and|or|of|a|the|is|are|was|were|for|in|on|:|/|as|to|from)\\b".toRegex()) &&
                                !sensitiveLabelRegex.matches(nextWordText)) { // Also ensure it's not another label
                                wordsToRedact.add(nextWord)
                                Log.w(TAG, "Redacting word following label: \"${nextWord.text}\"")
                            }
                        }
                    }
                }
            }
        }

        // Step 3: SPECIAL CASE: Redact QR Code for Aadhar Card (Handles image data like QR codes)
        val isAadharDoc = recognizedWords.any { it.text.contains("आधार", ignoreCase = true) || it.text.contains("Aadhar", ignoreCase = true) }

        if (isAadharDoc) {
            // Redact a hardcoded, highly probable area for the QR code (bottom right corner).
            val qrCodeArea = Rect(
                (mutableBitmap.width * 0.65).toInt(),
                (mutableBitmap.height * 0.60).toInt(),
                mutableBitmap.width,
                mutableBitmap.height
            )

            if (qrCodeArea.width() > 50 && qrCodeArea.height() > 50) {
                // Add a dummy RecognizedWord with the QR code bounding box to trigger redaction
                wordsToRedact.add(RecognizedWord("QR_CODE_AREA", 1.0f, qrCodeArea))
                Log.w(TAG, "Redacting hardcoded QR code area for Aadhar card.")
            }
        }


        if (wordsToRedact.isNotEmpty()) {
            val totalRedactedWords = wordsToRedact.map { it.text }.joinToString()
            Log.w(TAG, "Sensitive content identified for redaction: $totalRedactedWords")

            val canvas = Canvas(mutableBitmap)
            val paint = Paint().apply { isAntiAlias = true }

            for (word in wordsToRedact) {
                Log.d(TAG, "Applying redaction blur to: \"${word.text}\" at ${word.boundingBox}")

                // Ensure bounding box is within bitmap bounds before cropping
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
                } else {
                    Log.e(TAG, "Invalid bounding box for word: ${word.text}")
                }
            }
        } else {
            Log.i(TAG, "No sensitive words found in the image. Skipping redaction.")
        }

        val outFile = File(outputDir, inputDoc.name + "_redacted.jpg")
        FileOutputStream(outFile).use { fos ->
            mutableBitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos)
        }
        Log.d(TAG, "Image redacted and saved to: ${outFile.absolutePath}")
        return outFile
    }

    private fun blurBitmapRegion(src: Bitmap, rect: Rect, radius: Int = 20): Bitmap {
        // [Existing blur implementation remains here]
        Log.d(TAG, "Applying blur to region: $rect")

        val left = rect.left.coerceAtLeast(0)
        val top = rect.top.coerceAtLeast(0)
        // Ensure dimensions are positive
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