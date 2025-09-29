package com.dsatm.ner

/**
 * Data class for the output of the tokenization process,
 * including tokens, IDs, masks, and the mapping from token index to original text start/end index.
 */
data class TokenizedInput(
    val inputIds: LongArray,
    val attentionMask: LongArray,
    val tokenTypeIds: LongArray,
    val tokens: List<String>,
    // Crucial: A list of pairs where each pair is (start_index, end_index) in the ORIGINAL string
    // corresponding to the token at the same list index.
    val tokenToOriginalTextMap: List<Pair<Int, Int>>
)

/**
 * Represents a single detected PII entity.
 *
 * @param label The type of PII (e.g., GIVENNAME, STREET).
 * @param text The extracted text of the entity.
 * @param start The inclusive start index of the entity in the original text.
 * @param end The exclusive end index of the entity in the original text.
 */
data class PiiEntity(
    val label: String,
    val text: String,
    // ADDED: The precise character indices in the original text
    val start: Int,
    val end: Int
)