package com.dsatm.ner

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.InputStream
import kotlin.collections.ArrayList

// NOTE: Ensure PiiEntity and TokenizedInput are defined in DataModels.kt with start/end indices.

/**
 * Represents a generic sensitive data match, used for both NER and Regex results.
 */
data class SensitiveMatch(
    val text: String,
    val label: String,
    val start: Int,
    val end: Int
)

class NerPostProcessor(private val context: Context) {

    private val TAG = "NerPostProcessor"
    private lateinit var idToLabel: Map<Int, String>

    // --- REGEX PATTERNS FOR FIXED-FORMAT PII (NO CAPTURE GROUPS NEEDED) ---
    private val fixedPatternRegexes = listOf(
        // 🇮🇳 Aadhaar Number (12 digits, often spaced or grouped)
        RegexPii(
            label = "AADHAAR",
            regex = "\\b\\d{4}[-\\s]?\\d{4}[-\\s]?\\d{4}\\b"
        ),
        // 🇮🇳 PAN (Permanent Account Number) (5 letters, 4 digits, 1 letter)
        RegexPii(
            label = "PAN",
            regex = "\\b[A-Z]{5}\\d{4}[A-Z]{1}\\b"
        ),
        // 🇮🇳/Global Passport Number (Typically 1-2 letters followed by 7-8 digits)
        RegexPii(
            label = "PASSPORT",
            regex = "\\b[A-Z]{1,2}\\d{7,8}\\b"
        ),
        // 🇮🇳 IFSC Code (4 letters, 1 zero, 6 digits)
        RegexPii(
            label = "IFSC",
            regex = "\\b[A-Z]{4}0[A-Z0-9]{6}\\b"
        ),
        // Global Bank Account Number (General 9 to 18 digits)
        RegexPii(
            label = "ACCNUM",
            regex = "\\b\\d{9,18}\\b"
        ),
        // Global Credit Card Number (16 digits, with optional separators)
        RegexPii(
            label = "CARD_NUM",
            regex = "\\b\\d{4}[ -]?\\d{4}[ -]?\\d{4}[ -]?\\d{4}\\b"
        ),
        // Global Mobile Phone Number (10 digits starting 6, 7, 8, 9, or common international)
        RegexPii(
            label = "MOBILE",
            regex = "\\+?\\d{1,3}[-.\\s]?\\(?\\d{2,4}\\)?[^a-zA-Z]{1,2}\\d{3}[^a-zA-Z]{1,2}\\d{4,6}|\\b[6789]\\d{9}\\b"
        ),
        // Global Email Address (Standard)
        RegexPii(
            label = "EMAIL",
            regex = "\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b"
        ),
        // Global Date (Covers MM/DD/YYYY, YYYY-MM-DD, and Month Day, Year)
        RegexPii(
            label = "DATE",
            regex = "\\b(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\\s+\\d{1,2},\\s+\\d{4}\\b|\\b\\d{1,2}[-/]\\d{1,2}[-/]\\d{2,4}\\b|\\b\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}\\b"
        ),
    )

    // --- CONTEXTUAL REGEX PATTERNS (USES CAPTURE GROUP TO GET ONLY THE VALUE) ---
    private val contextualRegexes = listOf(
        // Mother's maiden name:
        RegexPii(
            label = "MAIDEN_NAME",
            regex = "Mother’s maiden name:\\s*([^;]+)" // Capture group 1: the name
        ),
        // Biometric Data (placeholder hash):
        RegexPii(
            label = "BIOMETRIC_DATA",
            regex = "Biometric data \\(placeholder hash\\):\\s*([^;]+)" // Capture group 1: the hash
        ),
        // ITR Summary:
        RegexPii(
            label = "ITR_SUMMARY",
            regex = "ITR summary:\\s*([^;]+)"
        ),
        // Medical Record Note:
        RegexPii(
            label = "MEDICAL_RECORD",
            regex = "Medical record note:\\s*([^;]+)"
        ),
        // Criminal Record:
        RegexPii(
            label = "CRIMINAL_RECORD",
            regex = "Criminal record:\\s*([^;]+)"
        ),
        // Educational Certificate/Marksheet ID:
        RegexPii(
            label = "EDUCATION_ID",
            regex = "Educational certificate/Marksheet ID:\\s*([^;]+)"
        ),
        // Employment Details (Captures Company Name, Employee ID, Salary - targets values)
        RegexPii(
            label = "EMPLOYMENT_DETAILS_COMPANY",
            regex = "Company:\\s*([^,]+)"
        ),
        RegexPii(
            label = "EMPLOYMENT_DETAILS_ID",
            regex = "Employee ID:\\s*([^,]+)"
        ),
        RegexPii(
            label = "EMPLOYMENT_DETAILS_SALARY",
            regex = "Monthly salary\\s*([^\\s]+)" // Captures the amount
        )
    )

    private data class RegexPii(val label: String, val regex: String)

    /**
     * Initializes the post-processor by loading the label mapping.
     */
    fun initialize() {
        Log.d(TAG, "Initializing post-processor...")
        try {
            val configStream = context.assets.open("config.json")
            // FIX: This call is now valid as the function is a private member
            idToLabel = loadLabelsFromConfig(configStream)
            Log.d(TAG, "ID-to-label mapping loaded. Total labels: ${idToLabel.size}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize post-processor.", e)
            throw e
        }
    }

    // ---------------------------------------------------------------------------------------------
    // --- PII MASKING LOGIC (NER + REGEX) ---
    // ---------------------------------------------------------------------------------------------

    /**
     * Executes the combined NER + Regex masking pipeline, applying partial masking.
     */
    fun redactText(originalText: String, nerEntities: List<PiiEntity>): String {
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

            // Re-extract text using original indices for precise masking
            val entityText = originalText.substring(startIndex, endIndex)
            val entityLength = endIndex - startIndex

            if (entityLength <= 2) {
                // For entities with 0, 1, or 2 characters
                val masked = when (entityLength) {
                    0 -> ""
                    1 -> "*"
                    else -> "${entityText.first()}*" // Length 2: 'A*'
                }
                sb.replace(startIndex, endIndex, masked)
            } else {
                // For entities longer than 2 characters (e.g., "John" -> "J**n")
                val firstChar = entityText.first()
                val lastChar = entityText.last()
                val numMaskChars = entityLength - 2
                val maskedMiddle = "*".repeat(numMaskChars)

                val replacement = "$firstChar$maskedMiddle$lastChar"

                try {
                    sb.replace(startIndex, endIndex, replacement)
                    Log.d(TAG, "Redacted: '${entityText}' -> '$replacement' (Label: ${match.label})")
                } catch (e: IndexOutOfBoundsException) {
                    Log.e(TAG, "Redaction failed. Indices: $startIndex, $endIndex. Match: ${match.text}", e)
                }
            }
        }
        return sb.toString()
    }

    // ---------------------------------------------------------------------------------------------
    // --- NER POST-PROCESSING LOGIC ---
    // ---------------------------------------------------------------------------------------------

    /**
     * Processes model logits and tokens to extract PII entities with their precise character indices.
     */
    fun process(tokenizedInput: TokenizedInput, logits: FloatArray): List<PiiEntity> {
        if (!::idToLabel.isInitialized) {
            throw IllegalStateException("PostProcessor has not been initialized. Call initialize() first.")
        }
        Log.d(TAG, "Starting post-processing of logits.")

        val predictedLabelIds = getPredictions(logits, tokenizedInput.tokens.size)

        val entities = mutableListOf<PiiEntity>()
        var currentLabel = ""
        var currentEntityText = StringBuilder()
        var entityStartIndex = -1
        var entityEndIndex = -1

        for (i in 1 until tokenizedInput.tokens.size) {
            val token = tokenizedInput.tokens[i]
            val labelId = predictedLabelIds[i]
            val label = idToLabel[labelId] ?: "O"

            val (tokenStart, tokenEnd) =
                if (i < tokenizedInput.tokenToOriginalTextMap.size) {
                    tokenizedInput.tokenToOriginalTextMap[i]
                } else {
                    Pair(-1, -1)
                }

            if (tokenStart == -1 || tokenEnd == -1) {
                if (currentEntityText.isNotEmpty() && entityStartIndex != -1) {
                    entities.add(PiiEntity(currentLabel, currentEntityText.toString().trim(), entityStartIndex, entityEndIndex))
                }
                currentLabel = ""
                currentEntityText = StringBuilder()
                entityStartIndex = -1
                entityEndIndex = -1
                continue
            }

            if (label.startsWith("B-")) {
                if (currentEntityText.isNotEmpty() && entityStartIndex != -1) {
                    entities.add(PiiEntity(currentLabel, currentEntityText.toString().trim(), entityStartIndex, entityEndIndex))
                }

                currentLabel = label.substring(2)
                currentEntityText = StringBuilder(cleanToken(token))
                entityStartIndex = tokenStart
                entityEndIndex = tokenEnd

            } else if (label.startsWith("I-") && label.substring(2) == currentLabel) {
                currentEntityText.append(if (token.startsWith("##")) "" else " ").append(cleanToken(token))
                entityEndIndex = tokenEnd
            } else {
                if (currentEntityText.isNotEmpty() && entityStartIndex != -1) {
                    entities.add(PiiEntity(currentLabel, currentEntityText.toString().trim(), entityStartIndex, entityEndIndex))
                }
                currentLabel = ""
                currentEntityText = StringBuilder()
                entityStartIndex = -1
                entityEndIndex = -1
            }
        }

        if (currentEntityText.isNotEmpty() && entityStartIndex != -1) {
            entities.add(PiiEntity(currentLabel, currentEntityText.toString().trim(), entityStartIndex, entityEndIndex))
        }
        return entities
    }

    // ---------------------------------------------------------------------------------------------
    // --- PRIVATE HELPER FUNCTIONS ---
    // ---------------------------------------------------------------------------------------------

    /**
     * Finds PII entities using regular expressions.
     */
    private fun detectPiiWithRegex(text: String): List<SensitiveMatch> {
        val matches = ArrayList<SensitiveMatch>()

        val allRegexPatterns = fixedPatternRegexes + contextualRegexes

        for (pattern in allRegexPatterns) {
            val regex = pattern.regex.toRegex()
            regex.findAll(text).forEach { result ->
                // Check if a capturing group (Group 1) exists, meaning it's a CONTEXTUAL PII match
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
                    // For standard FIXED-PATTERN PII
                    matches.add(
                        SensitiveMatch(
                            text = result.value,
                            label = pattern.label,
                            start = result.range.first,
                            end = result.range.last + 1 // Regex range is inclusive, need exclusive end
                        )
                    )
                }
            }
        }
        return matches
    }

    /**
     * Loads the ID-to-Label mapping from the config.json asset file.
     */
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

    private fun getPredictions(logits: FloatArray, seqLength: Int): List<Int> {
        val predictions = mutableListOf<Int>()
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

    private fun cleanToken(token: String): String {
        return if (token.startsWith("##")) {
            token.substring(2)
        } else {
            token
        }
    }
}