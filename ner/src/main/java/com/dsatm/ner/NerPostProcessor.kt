// File: com.dsatm.ner/NerPostProcessor.kt

package com.dsatm.ner

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.InputStream
import kotlin.collections.ArrayList
import kotlin.math.min

class NerPostProcessor(private val context: Context) {

    private val TAG = "NerPostProcessor"
    private lateinit var idToLabel: Map<Int, String>

    // Dummy RegexPii for completeness (assuming you have a real list)
    private data class RegexPii(val label: String, val regex: String)
    private val fixedPatternRegexes = listOf<RegexPii>()
    private val contextualRegexes = listOf<RegexPii>()

    fun initialize() {
        Log.d(TAG, "Initializing post-processor...")
        try {
            // NOTE: Ensure 'config.json' is in your assets folder
            val configStream = context.assets.open("config.json")
            idToLabel = loadLabelsFromConfig(configStream)
            Log.d(TAG, "ID-to-label mapping loaded. Total labels: ${idToLabel.size}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize post-processor.", e)
            throw e
        }
    }

    // ---------------------------------------------------------------------------------------------
    // --- NER POST-PROCESSING (CRITICAL IOB FIX) ---
    // ---------------------------------------------------------------------------------------------

    /**
     * Processes model logits and tokens to extract PII entities using character indices.
     */
    fun process(
        originalText: String,
        tokenizedInput: TokenizedInput,
        logits: FloatArray
    ): List<PiiEntity> {
        if (!::idToLabel.isInitialized) {
            throw IllegalStateException("PostProcessor has not been initialized. Call initialize() first.")
        }
        Log.d(TAG, "Starting post-processing of logits.")

        val predictedLabelIds = getPredictions(logits, tokenizedInput.tokens.size)
        val entities = mutableListOf<PiiEntity>()

        var currentLabelType: String? = null
        var entityStartIndex = -1 // Character start index in original text
        var entityEndIndex = -1   // Character end index in original text

        // Start from index 1 to skip the initial [CLS] token
        for (i in 1 until tokenizedInput.tokens.size) {
            // Stop if we hit padding/end of predictions
            if (i >= predictedLabelIds.size) break

            val fullLabel = idToLabel[predictedLabelIds[i]] ?: "O"
            val parts = fullLabel.split("-")
            val labelTag = parts.getOrElse(0) { "O" }
            val labelType = parts.getOrElse(1) { "O" }

            val (tokenStart, tokenEnd) = tokenizedInput.tokenToOriginalTextMap.getOrElse(i) {
                Pair(originalText.length, originalText.length)
            }

            // Skip non-mappable tokens (like [SEP] which maps to end of text)
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
                // 1. Close any existing entity
                if (currentLabelType != null) {
                    entities.add(extractPiiEntity(originalText, currentLabelType!!, entityStartIndex, entityEndIndex))
                }

                // 2. Start new entity
                currentLabelType = labelType
                entityStartIndex = tokenStart
                entityEndIndex = tokenEnd

            } else if (labelTag == "I" && labelType == currentLabelType) {
                // 3. Continue the current entity, only update the END index
                entityEndIndex = tokenEnd

            } else { // Tag is 'O', or I- with mismatched type, or B- with mismatched type
                // 4. Close any existing entity
                if (currentLabelType != null) {
                    entities.add(extractPiiEntity(originalText, currentLabelType!!, entityStartIndex, entityEndIndex))
                }

                // Reset tracking variables
                currentLabelType = null
                entityStartIndex = -1
                entityEndIndex = -1
            }
        }

        // Final check to close any open entity at the end of the sequence
        if (currentLabelType != null && entityStartIndex != -1) {
            entities.add(extractPiiEntity(originalText, currentLabelType!!, entityStartIndex, entityEndIndex))
        }

        return entities
    }

    /**
     * Helper to safely create the final PiiEntity using the collected character indices.
     */
    private fun extractPiiEntity(originalText: String, label: String, start: Int, end: Int): PiiEntity {
        // Ensure indices are within bounds
        val safeStart = maxOf(0, start)
        val safeEnd = min(originalText.length, end)

        val text = if (safeStart < safeEnd) {
            originalText.substring(safeStart, safeEnd)
        } else {
            ""
        }
        return PiiEntity(label, text, safeStart, safeEnd)
    }

    // ---------------------------------------------------------------------------------------------
    // --- REDACTION LOGIC (RESOLVES 'redactText' Unresolved Reference) ---
    // ---------------------------------------------------------------------------------------------

    /**
     * Replaces detected PII entities in the original text with a standardized tag.
     */
    fun redactText(originalText: String, entities: List<PiiEntity>): String {
        if (entities.isEmpty()) {
            return originalText
        }

        // Sort entities by start index in reverse order (END to START) to prevent index shifting
        val sortedEntities = entities.sortedByDescending { it.start }

        var redactedText = StringBuilder(originalText)

        for (entity in sortedEntities) {
            val replacementTag = "[${entity.label}]"

            // Check bounds to be safe
            if (entity.start < entity.end && entity.end <= redactedText.length) {
                // The StringBuilder.replace is the safest way to modify the string
                redactedText.replace(entity.start, entity.end, replacementTag)
            } else {
                Log.w(TAG, "Entity index out of bounds: $entity")
            }
        }

        return redactedText.toString()
    }


    private fun getPredictions(logits: FloatArray, seqLength: Int): List<Int> {
        val predictions = mutableListOf<Int>()
        val numLabels = idToLabel.size

        for (i in 0 until seqLength) {
            var maxIndex = 0
            var maxValue = Float.MIN_VALUE
            for (j in 0 until numLabels) {
                // Logits are a flat array: [token1_label0, token1_label1, ..., tokenN_labelX]
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