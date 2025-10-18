// File: com.dsatm.ner/DataModels.kt

package com.dsatm.ner

/**
 * Represents a single detected PII entity.
 */
data class PiiEntity(
    val label: String,
    val text: String,
    val start: Int, // Character index start in original text (inclusive)
    val end: Int    // Character index end in original text (exclusive)
)

/**
 * Represents the BERT model's tokenized input.
 */
data class TokenizedInput(
    val inputIds: LongArray,
    val attentionMask: LongArray,
    val tokenTypeIds: LongArray,
    val tokens: List<String>,
    // Maps token index (1, 2, 3...) to character span in ORIGINAL text (Pair<start, end>)
    val tokenToOriginalTextMap: List<Pair<Int, Int>>
)