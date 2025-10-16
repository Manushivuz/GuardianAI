// File: com.dsatm.ner/BertNerOnnxManager.kt

package com.dsatm.ner

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.LongBuffer

class BertNerOnnxManager(private val context: Context) {

    private val TAG = "BertNerOnnxManager"
    private lateinit var ortEnv: OrtEnvironment
    private lateinit var ortSession: OrtSession
    private lateinit var tokenizer: BertTokenizer

    // Make postProcessor lateinit since it's initialized in `initialize()`
    lateinit var postProcessor: NerPostProcessor

    fun initialize() {
        Log.d(TAG, "Starting initialization of NER manager...")
        try {
            ortEnv = OrtEnvironment.getEnvironment()
            // NOTE: Ensure 'mobilebert_ner.onnx' is in your assets folder
            ortSession = ortEnv.createSession(context.assets.open("mobilebert_ner.onnx").readBytes(), OrtSession.SessionOptions())
            Log.d(TAG, "ONNX session created successfully.")

            tokenizer = BertTokenizer(context)
            tokenizer.initialize()
            Log.d(TAG, "Tokenizer initialized.")

            postProcessor = NerPostProcessor(context)
            postProcessor.initialize()
            Log.d(TAG, "Post-processor initialized.")

            Log.d(TAG, "NER manager initialization complete.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize NER manager.", e)
            close()
            throw e
        }
    }

    /**
     * Runs the PII detection pipeline on the given text.
     */
    fun detectPii(text: String): List<PiiEntity> {
        if (!::ortSession.isInitialized) {
            throw IllegalStateException("BertNerOnnxManager is not initialized. Call initialize() first.")
        }

        // 🛑 FIX 2: Remove Vosk timestamps (e.g., "[1.23-1.50]") before passing to tokenizer
        val cleanText = text.replace(Regex("\\[[\\d.]+-[\\d.]+\\]"), "").trim()

        Log.d(TAG, "Starting PII detection for clean text: '$cleanText'")

        // Use the cleaned text for tokenization
        val tokenizedInput = tokenizer.tokenize(cleanText)

        val inputs = createOnnxTensors(tokenizedInput)

        var outputTensor: OnnxTensor? = null
        try {
            val results = ortSession.run(inputs)
            outputTensor = results[0] as OnnxTensor
            Log.d(TAG, "Inference successful. Starting post-processing.")

            // Pass the original clean text to the post-processor for accurate string retrieval
            val entities = postProcessor.process(
                originalText = cleanText,
                tokenizedInput = tokenizedInput,
                logits = outputTensor.floatBuffer.array()
            )

            Log.d(TAG, "NER detection complete. Found ${entities.size} entities.")
            return entities
        } finally {
            inputs.values.forEach { it.close() }
            outputTensor?.close()
        }
    }

    private fun createOnnxTensors(tokenizedInput: TokenizedInput): Map<String, OnnxTensor> {
        val inputs = mutableMapOf<String, OnnxTensor>()

        // Use the actual data length (which is the truncated size)
        val finalLength = tokenizedInput.inputIds.size.toLong()

        // Tensors must be created with the shape that matches the data length
        inputs["input_ids"] = createTensor(tokenizedInput.inputIds, longArrayOf(1, finalLength))
        // Attention mask uses the data from the padded array, but we only send `finalLength` elements
        val attentionMaskData = tokenizedInput.attentionMask.sliceArray(0 until finalLength.toInt())
        inputs["attention_mask"] = createTensor(attentionMaskData, longArrayOf(1, finalLength))
        val tokenTypeIdsData = tokenizedInput.tokenTypeIds.sliceArray(0 until finalLength.toInt())
        inputs["token_type_ids"] = createTensor(tokenTypeIdsData, longArrayOf(1, finalLength))

        return inputs
    }

    private fun createTensor(data: LongArray, shape: LongArray): OnnxTensor {
        val longBuffer = LongBuffer.allocate(data.size)
        longBuffer.put(data)
        longBuffer.flip()
        return OnnxTensor.createTensor(ortEnv, longBuffer, shape)
    }

    fun close() {
        Log.d(TAG, "Closing ONNX session and environment.")
        if (::ortSession.isInitialized) {
            ortSession.close()
        }
        if (::ortEnv.isInitialized) {
            ortEnv.close()
        }
    }
}