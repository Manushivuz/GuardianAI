// File: com.dsatm.ner/BertTokenizer.kt

package com.dsatm.ner

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

class BertTokenizer(private val context: Context) {

      private val TAG = "BertTokenizer"
      private lateinit var vocab: Map<String, Int>
      private val MAX_SEQUENCE_LENGTH = 512

      private val UNK_ID = 100
      private val CLS_ID = 101
      private val SEP_ID = 102
      private val PAD_ID = 0 

      fun initialize() {
            Log.d(TAG, "Initializing tokenizer...")
            vocab = loadVocab()
            Log.d(TAG, "Vocabulary size: ${vocab.size}")
          }

      private fun loadVocab(): Map<String, Int> {
            val vocabMap = mutableMapOf<String, Int>()
            try {
                  val stream = context.assets.open("vocab.txt")
                  val reader = BufferedReader(InputStreamReader(stream))
                  reader.forEachLine { line ->
                    if (line.isNotBlank()) {
                      vocabMap[line.trim()] = vocabMap.size
                    }
                  }
                } catch (e: Exception) {
                  Log.e(TAG, "Failed to load vocab.txt", e)
                  return mapOf("[UNK]" to UNK_ID, "[CLS]" to CLS_ID, "[SEP]" to SEP_ID, "[PAD]" to PAD_ID)
                }
            return vocabMap
          }

      /**
      * Tokenizes the text, handles character mapping, and enforces max length truncation.
      */
      fun tokenize(text: String): TokenizedInput {
            val originalWords = text.split(Regex("\\s+")).filter { it.isNotEmpty() }

            val tokenIds = mutableListOf<Long>(CLS_ID.toLong())
            val tokenStrings = mutableListOf<String>("[CLS]")
            val charIndexMap = mutableListOf<Pair<Int, Int>>(Pair(0, 0))

            var currentOffset = 0
            for (word in originalWords) {
                  val token = word.toLowerCase()

                  val wordStart = text.indexOf(word, currentOffset, ignoreCase = true)

                  if (wordStart == -1) {
                    currentOffset += word.length
                    continue
                  }
                  val wordEnd = wordStart + word.length
                  currentOffset = wordEnd + 1

                  val id = vocab[token] ?: UNK_ID
                  tokenIds.add(id.toLong())
                  tokenStrings.add(word)
                  charIndexMap.add(Pair(wordStart, wordEnd))
                }

            tokenIds.add(SEP_ID.toLong())
            tokenStrings.add("[SEP]")
            charIndexMap.add(Pair(text.length, text.length))

            val finalLength = minOf(tokenIds.size, MAX_SEQUENCE_LENGTH)

            val inputIds = tokenIds.take(finalLength).toLongArray()
            val tokens = tokenStrings.take(finalLength)
            val map = charIndexMap.take(finalLength)

            val attentionMask = LongArray(MAX_SEQUENCE_LENGTH) { 0L }
            val tokenTypeIds = LongArray(MAX_SEQUENCE_LENGTH) { 0L }

            for (i in 0 until finalLength) {
                  attentionMask[i] = 1L
                  tokenTypeIds[i] = 0L
                }

            return TokenizedInput(
              inputIds = inputIds,
              attentionMask = attentionMask,
              tokenTypeIds = tokenTypeIds,
              tokens = tokens,
              tokenToOriginalTextMap = map
            )
          }
}