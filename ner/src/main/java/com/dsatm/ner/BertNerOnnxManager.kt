package com.dsatm.ner

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.LongBuffer
// 💡 This is the crucial import to ensure the class is visible
import com.dsatm.ner.BertTokenizer

/**
 * Manages the entire NER model pipeline.
 * ...
 */
class BertNerOnnxManager(private val context: Context) {

    private val TAG = "BertNerOnnxManager"
    private lateinit var ortEnv: OrtEnvironment
    private lateinit var ortSession: OrtSession
    private lateinit var tokenizer: BertTokenizer
    lateinit var postProcessor: NerPostProcessor

    /**
     * Initializes all components of the NER pipeline.
     */
    fun initialize() {
        Log.d(TAG, "Starting initialization of NER manager...")
        try {
            ortEnv = OrtEnvironment.getEnvironment()
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
        Log.d(TAG, "Starting PII detection for text: '$text'")

        val tokenizedInput = tokenizer.tokenize(text)

        val inputs = createOnnxTensors(tokenizedInput)

        var outputTensor: OnnxTensor? = null
        try {
            val results = ortSession.run(inputs)
            outputTensor = results[0] as OnnxTensor
            Log.d(TAG, "Inference successful. Starting post-processing.")

            val entities = postProcessor.process(
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

        inputs["input_ids"] = createTensor(tokenizedInput.inputIds, longArrayOf(1, tokenizedInput.inputIds.size.toLong()))
        inputs["attention_mask"] = createTensor(tokenizedInput.attentionMask, longArrayOf(1, tokenizedInput.attentionMask.size.toLong()))
        inputs["token_type_ids"] = createTensor(tokenizedInput.tokenTypeIds, longArrayOf(1, tokenizedInput.tokenTypeIds.size.toLong()))

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