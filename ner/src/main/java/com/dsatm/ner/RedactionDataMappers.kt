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
fun mapPiiToTimeRanges(
    rawTimestampedTranscript: String,
    piiEntities: List<PiiEntity>
): List<Pair<Long, Long>> {
    val TAG = "PiiTimeMapper"
    val ranges = mutableListOf<Pair<Long, Long>>()

    // Regex to find and extract both the word and its time range
    val pattern = Regex("(\\S+)\\s*\\[([\\d.]+)-([\\d.]+)\\]")
    val matches = pattern.findAll(rawTimestampedTranscript)

    // A list of VoskWord objects that includes timing
    val voskWordsWithTiming = mutableListOf<VoskWord>()

    // Current char index in the CLEANED transcript (no time tags)
    var currentCleanCharIndex = 0

    // 1. Parse the timestamped transcript into a list of VoskWord objects
    for (match in matches) {
        val (word, startStr, endStr) = match.destructured

        // Remove trailing punctuation that might be included in Vosk's 'word' but not
        // in the character span logic (e.g., 'hello. [1.0-1.5]'). A quick fix:
        val cleanWord = word.trim().replace(Regex("[.,?!:;]"), "")

        // Create the VoskWord object
        val voskWord = VoskWord(
            word = cleanWord,
            start = startStr.toFloatOrNull() ?: continue,
            end = endStr.toFloatOrNull() ?: continue
        )

        // Log.d(TAG, "Parsed Word: $cleanWord, Timing: ${voskWord.start}-${voskWord.end}")
        voskWordsWithTiming.add(voskWord)
    }

    // 2. Map PII entities (char indices) to the Vosk timing data
    for (entity in piiEntities) {
        var startWord: VoskWord? = null
        var endWord: VoskWord? = null
        currentCleanCharIndex = 0

        // Iterate through the parsed Vosk words to find the word corresponding to the PII span
        for (word in voskWordsWithTiming) {
            val wordLength = word.word.length
            val wordEndCharIndex = currentCleanCharIndex + wordLength

            // The entity's text is the span of characters. We need the word that *contains* the entity's start index
            // and the word that *contains* the entity's end index.

            // Check for Start Word: Word starts before/at PII start AND ends after PII start.
            if (startWord == null && currentCleanCharIndex <= entity.start && wordEndCharIndex > entity.start) {
                startWord = word
            }

            // Check for End Word: Word starts before PII end AND ends after/at PII end.
            if (currentCleanCharIndex < entity.end && wordEndCharIndex >= entity.end) {
                endWord = word
                // If we found both, we can break early since words are sequential
                if (startWord != null) break
            }

            // Advance the index for the next word (add 1 for the space that was removed)
            currentCleanCharIndex = wordEndCharIndex + 1
        }

        if (startWord != null && endWord != null) {
            // Use the start time of the first word and the end time of the last word in the PII phrase
            val startMs = (startWord.start * 1000).toLong()
            val endMs = (endWord.end * 1000).toLong()
            ranges.add(Pair(startMs, endMs))
            Log.d(TAG, "Mapped PII '${entity.text}' to time range: $startMs ms - $endMs ms")
        } else {
            Log.w(TAG, "Could not map PII entity '${entity.text}' (indices ${entity.start}-${entity.end}) to Vosk timestamps.")
        }
    }

    // Ensure ranges are unique and sorted
    return ranges.distinct().sortedBy { it.first }
}