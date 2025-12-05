// File: com.dsatm.ner/TextRedactionPostProcessor.kt

package com.dsatm.ner

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.InputStream
import kotlin.collections.ArrayList
import kotlin.math.min
import com.dsatm.ner.PiiEntity
import com.dsatm.ner.TokenizedInput

/**
 * Post-processor specifically for **Text Redaction** (Clipboard/Keyboard).
 * It includes partial masking logic and additional Regex-based detection for PII.
 */
class TextRedactionPostProcessor(private val context: Context) {

    private val TAG = "TextRedactionPostProcessor"
    private lateinit var idToLabel: Map<Int, String>

    // --- REGEX PATTERNS FOR FIXED-FORMAT PII (Copied from text-redaction code) ---
    private val fixedPatternRegexes = listOf(
        // 🇮🇳 Aadhaar Number (12 digits, often spaced or grouped)
        RegexPii(label = "AADHAAR", regex = "\\b\\d{4}[-\\s]?\\d{4}[-\\s]?\\d{4}\\b"),
        // 🇮🇳 PAN (Permanent Account Number) (5 letters, 4 digits, 1 letter)
        RegexPii(label = "PAN", regex = "\\b[A-Z]{5}\\d{4}[A-Z]{1}\\b"),
        // 🇮🇳/Global Passport Number (Typically 1-2 letters followed by 7-8 digits)
        RegexPii(label = "PASSPORT", regex = "\\b[A-Z]{1,2}\\d{7,8}\\b"),
        // 🇮🇳 IFSC Code (4 letters, 1 zero, 6 digits)
        RegexPii(label = "IFSC", regex = "\\b[A-Z]{4}0[A-Z0-9]{6}\\b"),
        // Global Bank Account Number (General 9 to 18 digits)
        RegexPii(label = "ACCNUM", regex = "\\b\\d{9,18}\\b"),
        // Global Credit Card Number (16 digits, with optional separators)
        RegexPii(label = "CARD_NUM", regex = "\\b\\d{4}[ -]?\\d{4}[ -]?\\d{4}[ -]?\\d{4}\\b"),
        // Global Mobile Phone Number (10 digits starting 6, 7, 8, 9, or common international)
        RegexPii(label = "MOBILE", regex = "\\+?\\d{1,3}[-.\\s]?\\(?\\d{2,4}\\)?[^a-zA-Z]{1,2}\\d{3}[^a-zA-Z]{1,2}\\d{4,6}|\\b[6789]\\d{9}\\b"),
        // Global Email Address (Standard)
        RegexPii(label = "EMAIL", regex = "\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b"),
        // Global Date (Covers MM/DD/YYYY, YYYY-MM-DD, and Month Day, Year)
        RegexPii(label = "DATE", regex = "\\b(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\\s+\\d{1,2},\\s+\\d{4}\\b|\\b\\d{1,2}[-/]\\d{1,2}[-/]\\d{2,4}\\b|\\b\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}\\b"),
    )

    // --- CONTEXTUAL REGEX PATTERNS (Copied from text-redaction code) ---
    private val contextualRegexes = listOf(
        RegexPii(label = "MAIDEN_NAME", regex = "Mother’s maiden name:\\s*([^;]+)"),
        RegexPii(label = "BIOMETRIC_DATA", regex = "Biometric data \\(placeholder hash\\):\\s*([^;]+)"),
        RegexPii(label = "ITR_SUMMARY", regex = "ITR summary:\\s*([^;]+)"),
        RegexPii(label = "MEDICAL_RECORD", regex = "Medical record note:\\s*([^;]+)"),
        RegexPii(label = "CRIMINAL_RECORD", regex = "Criminal record:\\s*([^;]+)"),
        RegexPii(label = "EDUCATION_ID", regex = "Educational certificate/Marksheet ID:\\s*([^;]+)"),
        RegexPii(label = "EMPLOYMENT_DETAILS_COMPANY", regex = "Company:\\s*([^,]+)"),
        RegexPii(label = "EMPLOYMENT_DETAILS_ID", regex = "Employee ID:\\s*([^,]+)"),
        RegexPii(label = "EMPLOYMENT_DETAILS_SALARY", regex = "Monthly salary\\s*([^\\s]+)")
    )


    fun initialize() {
        Log.d(TAG, "Initializing text redaction post-processor...")
        try {
            val configStream = context.assets.open("config.json")
            idToLabel = loadLabelsFromConfig(configStream)
            Log.d(TAG, "ID-to-label mapping loaded.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize post-processor.", e)
            throw e
        }
    }

    /**
     * Executes the combined NER + Regex masking pipeline, applying partial masking.
     * This is the NEW, renamed method for text/clipboard feature.
     */
    fun applyClipboardRedaction(originalText: String, nerEntities: List<PiiEntity>): String {
        val regexMatches = detectPiiWithRegex(originalText)

        // Convert NER entities to the generic SensitiveMatch structure
        val allMatches = nerEntities.map {
            SensitiveMatch(it.text, it.label, it.start, it.end)
        }.toMutableList()
        // Add Regex matches
        allMatches.addAll(regexMatches)

        // 1. Filter overlapping matches and sort by start index in DESCENDING order (CRUCIAL)
        val sortedMatches = allMatches
            .distinctBy { it.start to it.end }
            .sortedByDescending { it.start }

        val sb = StringBuilder(originalText)

        for (match in sortedMatches) {
            val startIndex = match.start
            val endIndex = match.end
            val entityText = originalText.substring(startIndex, endIndex)
            val entityLength = endIndex - startIndex

            val replacement = if (entityLength <= 2) {
                when (entityLength) {
                    0 -> ""
                    1 -> "*"
                    else -> "${entityText.first()}*"
                }
            } else {
                // For entities longer than 2 characters (e.g., "John" -> "J**n")
                val firstChar = entityText.first()
                val lastChar = entityText.last()
                val numMaskChars = entityLength - 2
                val maskedMiddle = "*".repeat(numMaskChars)
                "$firstChar$maskedMiddle$lastChar"
            }

            try {
                sb.replace(startIndex, endIndex, replacement)
                Log.d(TAG, "Redacted: '${entityText}' -> '$replacement' (Label: ${match.label})")
            } catch (e: IndexOutOfBoundsException) {
                Log.e(TAG, "Redaction failed. Indices: $startIndex, $endIndex. Match: ${match.text}", e)
            }
        }
        return sb.toString()
    }


    /**
     * Processes model logits and tokens to extract PII entities using character indices.
     * This method keeps the same signature as the original NER logic.
     */
    fun process(
        originalText: String,
        tokenizedInput: TokenizedInput,
        logits: FloatArray
    ): List<PiiEntity> {
        if (!::idToLabel.isInitialized) {
            throw IllegalStateException("PostProcessor has not been initialized.")
        }

        // This process logic uses the IOB fixing logic from the Audio team's version
        val predictedLabelIds = getPredictions(logits, tokenizedInput.tokens.size)
        val entities = mutableListOf<PiiEntity>()

        var currentLabelType: String? = null
        var entityStartIndex = -1
        var entityEndIndex = -1

        for (i in 1 until tokenizedInput.tokens.size) {
            if (i >= predictedLabelIds.size) break

            val fullLabel = idToLabel[predictedLabelIds[i]] ?: "O"
            val parts = fullLabel.split("-")
            val labelTag = parts.getOrElse(0) { "O" }
            val labelType = parts.getOrElse(1) { "O" }

            val (tokenStart, tokenEnd) = tokenizedInput.tokenToOriginalTextMap.getOrElse(i) {
                Pair(originalText.length, originalText.length)
            }

            if (tokenStart >= originalText.length) {
                if (currentLabelType != null) {
                    entities.add(extractPiiEntity(originalText, currentLabelType!!, entityStartIndex, entityEndIndex))
                }
                currentLabelType = null
                entityStartIndex = -1
                entityEndIndex = -1
                continue
            }

            if (labelTag == "B") {
                if (currentLabelType != null) {
                    entities.add(extractPiiEntity(originalText, currentLabelType!!, entityStartIndex, entityEndIndex))
                }
                currentLabelType = labelType
                entityStartIndex = tokenStart
                entityEndIndex = tokenEnd
            } else if (labelTag == "I" && labelType == currentLabelType) {
                entityEndIndex = tokenEnd
            } else {
                if (currentLabelType != null) {
                    entities.add(extractPiiEntity(originalText, currentLabelType!!, entityStartIndex, entityEndIndex))
                }
                currentLabelType = null
                entityStartIndex = -1
                entityEndIndex = -1
            }
        }

        if (currentLabelType != null && entityStartIndex != -1) {
            entities.add(extractPiiEntity(originalText, currentLabelType!!, entityStartIndex, entityEndIndex))
        }
        return entities
    }

    /**
     * Helper to safely create the final PiiEntity using the collected character indices.
     */
    private fun extractPiiEntity(originalText: String, label: String, start: Int, end: Int): PiiEntity {
        val safeStart = maxOf(0, start)
        val safeEnd = min(originalText.length, end)
        val text = if (safeStart < safeEnd) originalText.substring(safeStart, safeEnd) else ""
        return PiiEntity(label, text, safeStart, safeEnd)
    }

    /**
     * Finds PII entities using regular expressions.
     */
    private fun detectPiiWithRegex(text: String): List<SensitiveMatch> {
        val matches = ArrayList<SensitiveMatch>()
        val allRegexPatterns = fixedPatternRegexes + contextualRegexes

        for (pattern in allRegexPatterns) {
            val regex = pattern.regex.toRegex()
            regex.findAll(text).forEach { result ->
                if (result.groups.size > 1 && result.groups[1] != null) {
                    val group = result.groups[1]!!
                    matches.add(
                        SensitiveMatch(
                            text = group.value,
                            label = pattern.label,
                            start = group.range.first,
                            end = group.range.last + 1
                        )
                    )
                } else {
                    matches.add(
                        SensitiveMatch(
                            text = result.value,
                            label = pattern.label,
                            start = result.range.first,
                            end = result.range.last + 1
                        )
                    )
                }
            }
        }
        return matches
    }

    private fun getPredictions(logits: FloatArray, seqLength: Int): List<Int> {
        val predictions = mutableListOf<Int>()
        // Assuming idToLabel is initialized and accessible
        val numLabels = idToLabel.size
        for (i in 0 until seqLength) {
            var maxIndex = 0
            var maxValue = Float.MIN_VALUE
            for (j in 0 until numLabels) {
                val value = logits[i * numLabels + j]
                if (value > maxValue) {
                    maxValue = value
                    maxIndex = j
                }
            }
            predictions.add(maxIndex)
        }
        return predictions
    }

    private fun loadLabelsFromConfig(inputStream: InputStream): Map<Int, String> {
        val json = JSONObject(inputStream.bufferedReader().readText())
        val idToLabelJson = json.getJSONObject("id2label")
        val map = mutableMapOf<Int, String>()
        val keys = idToLabelJson.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            map[key.toInt()] = idToLabelJson.getString(key)
        }
        return map
    }
}