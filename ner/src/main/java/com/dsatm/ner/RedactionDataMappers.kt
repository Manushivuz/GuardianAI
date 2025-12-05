package com.dsatm.ner

// File: com.dsatm.ner/RedactionDataMappers.kt (or similar location)
import android.util.Log

/**
 * 1. Data model for PII entity as returned by your BertNerOnnxManager.
 * This uses character indices against the *cleaned* transcript.
 */


/**
 * 2. Data model for Vosk word with timestamps.
 * NOTE: Vosk returns time in SECONDS (Float).
 */
data class VoskWord(
    val word: String,
    val start: Float, // Start time in seconds
    val end: Float    // End time in seconds
)

/**
 * 3. The CRITICAL function to map PII character indices to audio time ranges.
 * This is the bridge between text analysis and audio processing.
 *
 * @param rawTimestampedTranscript The full transcript string returned by Vosk, including the [start-end] tags.
 * @param piiEntities The list of PII entities found by NER against the *cleaned* transcript.
 * @return A list of audio mute ranges as (startMs, endMs) pairs.
 */
// File: com.dsatm.ner/RedactionDataMappers.kt (REPLACEMENT CODE)

// Note: Assuming PiiEntity and VoskWord data classes are correctly defined in scope.

fun mapPiiToTimeRanges(
    rawTimestampedTranscript: String,
    piiEntities: List<PiiEntity>
): List<Pair<Long, Long>> {
    val TAG = "PiiTimeMapper"
    val ranges = mutableListOf<Pair<Long, Long>>()

    // Data class to track both audio time and character indices
    data class IndexedVoskWord(
        val word: String,
        val startChar: Int,  // Start index in the NER's input string
        val endChar: Int,    // End index in the NER's input string
        val audioStart: Float,
        val audioEnd: Float
    )

    val pattern = Regex("(\\S+)\\s*\\[([\\d.]+)-([\\d.]+)\\]")
    val matches = pattern.findAll(rawTimestampedTranscript)
    val indexedWords = mutableListOf<IndexedVoskWord>()

    // Tracks the current index in the "cleaned" transcript (must match the NER's input)
    var currentCleanCharIndex = 0

    // --- Step 1: Parse Vosk output and build the index map ---
    for (match in matches) {
        val (rawWord, startStr, endStr) = match.destructured

        // CRITICAL: Must use the exact same cleaning logic as the NER model
        val cleanWord = rawWord.trim().replace(Regex("[.,?!:;]"), "")

        if (cleanWord.isEmpty()) continue

        val startChar = currentCleanCharIndex
        val endChar = startChar + cleanWord.length

        indexedWords.add(
            IndexedVoskWord(
                word = cleanWord,
                startChar = startChar,
                endChar = endChar,
                audioStart = startStr.toFloatOrNull() ?: continue,
                audioEnd = endStr.toFloatOrNull() ?: continue
            )
        )

        // Advance the index: word length + 1 for the space separator between words
        currentCleanCharIndex = endChar + 1
    }

    // --- Step 2: Map PII entities (char indices) using overlap check ---
    for (entity in piiEntities) {

        // Find ALL words that geometrically overlap with the PII entity span.
        val overlappingWords = indexedWords.filter { word ->
            // Robust Overlap Check: Captures all individual Vosk words within the long PII entity span.
            // Word ends after entity starts AND Word starts before entity ends.
            word.startChar < entity.end && word.endChar > entity.start
        }

        if (overlappingWords.isNotEmpty()) {
            val startWord = overlappingWords.first()
            val endWord = overlappingWords.last()

            // Use the start time of the first word and the end time of the last word
            val startMs = (startWord.audioStart * 1000).toLong()
            val endMs = (endWord.audioEnd * 1000).toLong()

            ranges.add(Pair(startMs, endMs))
            Log.d(TAG, "Mapped PII '${entity.text}' to time range: $startMs ms - $endMs ms")
        } else {
            // This handles the error scenario more robustly.
            Log.w(TAG, "Could not map PII entity '${entity.text}' (indices ${entity.start}-${entity.end}) to Vosk timestamps.")
        }
    }

    // Ensure ranges are unique and sorted
    return ranges.distinct().sortedBy { it.first }
}