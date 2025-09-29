package com.dsatm.ner

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.LinkedList

/**
 * Handles tokenization for BERT-style models.
 * Assumes a standard WordPiece tokenizer with a vocab.txt file.
 */
class BertTokenizer(private val context: Context) {

    private val TAG = "BertTokenizer"
    private lateinit var vocab: Map<String, Long> // Map token string to ID

    /**
     * Initializes the tokenizer by loading the vocabulary file.
     */
    fun initialize() {
        Log.d(TAG, "Initializing tokenizer...")
        vocab = loadVocab()
        Log.d(TAG, "Vocab loaded. Size: ${vocab.size}")
    }

    /**
     * Converts a raw text string into the necessary numerical inputs for the BERT model.
     * This implementation includes logic to map tokens back to original string indices.
     */
    fun tokenize(text: String): TokenizedInput {
        if (!::vocab.isInitialized) {
            throw IllegalStateException("Tokenizer not initialized. Call initialize() first.")
        }

        // 1. Initial segmentation of the original text by whitespace
        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }

        // Lists to store the final output (including [CLS] and [SEP])
        val finalTokens = LinkedList<String>()
        val finalInputIds = LinkedList<Long>()
        val tokenToOriginalTextMap = LinkedList<Pair<Int, Int>>()

        // Add [CLS] token
        finalTokens.add("[CLS]")
        finalInputIds.add(vocab.getOrElse("[CLS]") { 101L })
        tokenToOriginalTextMap.add(Pair(0, 0))

        // Track character position in the original text
        var currentCharIndex = 0

        // 2. Tokenize each word and track indices
        for (word in words) {
            val startOfWordInOriginalText = text.indexOf(word, startIndex = currentCharIndex)

            if (startOfWordInOriginalText == -1) {
                currentCharIndex = text.length
                continue
            }

            var tokenStart = startOfWordInOriginalText

            var subWordIndex = 0
            while (subWordIndex < word.length) {
                var isFound = false
                var end = word.length
                var currToken = ""

                while (subWordIndex < end) {
                    var sub = word.substring(subWordIndex, end)
                    if (subWordIndex > 0) {
                        sub = "##$sub"
                    }
                    if (vocab.containsKey(sub)) {
                        currToken = sub
                        isFound = true
                        break
                    }
                    end--
                }

                if (!isFound) {
                    currToken = "[UNK]"
                    end = subWordIndex + 1
                }

                finalTokens.add(currToken)
                finalInputIds.add(vocab.getOrElse(currToken) { 100L })

                val originalTokenLength = if (currToken.startsWith("##")) {
                    currToken.substring(2).length
                } else {
                    currToken.length
                }

                val tokenEnd = tokenStart + originalTokenLength
                tokenToOriginalTextMap.add(Pair(tokenStart, tokenEnd))

                subWordIndex = end
                tokenStart = tokenEnd
            }

            currentCharIndex = startOfWordInOriginalText + word.length
        }

        // Add [SEP] token
        finalTokens.add("[SEP]")
        finalInputIds.add(vocab.getOrElse("[SEP]") { 102L })
        tokenToOriginalTextMap.add(Pair(text.length, text.length))

        // Create the TokenizedInput object
        return TokenizedInput(
            inputIds = finalInputIds.toLongArray(),
            attentionMask = LongArray(finalInputIds.size) { 1L },
            tokenTypeIds = LongArray(finalInputIds.size) { 0L },
            tokens = finalTokens.toList(),
            tokenToOriginalTextMap = tokenToOriginalTextMap.toList()
        )
    }

    private fun loadVocab(): Map<String, Long> {
        val vocabMap = mutableMapOf<String, Long>()
        var index = 0L
        try {
            context.assets.open("vocab.txt").use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    reader.forEachLine { line ->
                        if (line.isNotEmpty()) {
                            vocabMap[line.trim()] = index++
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading vocab.txt", e)
            vocabMap.putIfAbsent("[CLS]", 101L)
            vocabMap.putIfAbsent("[SEP]", 102L)
            vocabMap.putIfAbsent("[UNK]", 100L)
            vocabMap.putIfAbsent("[PAD]", 0L)
        }
        return vocabMap
    }
}