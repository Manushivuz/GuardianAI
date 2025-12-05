package com.dsatm.ner

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.LongBuffer

/**
 * Manages the NER model pipeline specifically for **Text/Clipboard Redaction**.
 * * This manager uses the TextRedactionPostProcessor for combining NER results with
 * Regex matches and applying partial masking. It ensures that the input tensors
 * are correctly sized to match the BERT model's expectations after tokenization.
 */
class TextBertNerOnnxManager(private val context: Context) {

    private val TAG = "TextBertNerOnnxManager"
    private lateinit var ortEnv: OrtEnvironment
    private lateinit var ortSession: OrtSession
    private lateinit var tokenizer: BertTokenizer

    // The specific post-processor for text features (partial masking + regex)
    lateinit var textPostProcessor: TextRedactionPostProcessor

    /**
     * Initializes all components of the Text NER pipeline.
     */
    fun initialize() {
        Log.d(TAG, "Starting initialization of Text NER manager...")
        try {
            ortEnv = OrtEnvironment.getEnvironment()
            // NOTE: Ensure 'mobilebert_ner.onnx' is in your assets folder
            ortSession = ortEnv.createSession(context.assets.open("mobilebert_ner.onnx").readBytes(), OrtSession.SessionOptions())
            Log.d(TAG, "ONNX session created successfully.")

            tokenizer = BertTokenizer(context)
            tokenizer.initialize()
            Log.d(TAG, "Tokenizer initialized.")

            textPostProcessor = TextRedactionPostProcessor(context)
            textPostProcessor.initialize()
            Log.d(TAG, "Text Post-processor initialized.")

            Log.d(TAG, "Text NER manager initialization complete.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Text NER manager.", e)
            close()
            throw e
        }
    }

    /**
     * Runs the PII detection pipeline on the given text.
     * * @param text The raw text string (does not strip Vosk timestamps).
     * @return List of detected PiiEntity objects.
     */
    fun detectPiiForText(text: String): List<PiiEntity> {
        if (!::ortSession.isInitialized) {
            throw IllegalStateException("TextBertNerOnnxManager is not initialized. Call initialize() first.")
        }
        Log.d(TAG, "Starting PII detection for text: '$text'")

        val tokenizedInput = tokenizer.tokenize(text)

        val inputs = createOnnxTensors(tokenizedInput)

        var outputTensor: OnnxTensor? = null
        try {
            val results = ortSession.run(inputs)
            outputTensor = results[0] as OnnxTensor
            Log.d(TAG, "Inference successful. Starting post-processing.")

            // Pass the original text for accurate substring extraction inside the processor
            val entities = textPostProcessor.process(
                originalText = text,
                tokenizedInput = tokenizedInput,
                logits = outputTensor.floatBuffer.array()
            )

            Log.d(TAG, "Text NER detection complete. Found ${entities.size} entities.")
            return entities
        } finally {
            inputs.values.forEach { it.close() }
            outputTensor?.close()
        }
    }

    /**
     * Creates the ONNX tensors (input_ids, attention_mask, token_type_ids)
     * using the correct sequence length to avoid shape mismatch errors.
     */
    private fun createOnnxTensors(tokenizedInput: TokenizedInput): Map<String, OnnxTensor> {
        val inputs = mutableMapOf<String, OnnxTensor>()

        // The length of inputIds is the actual sequence length after truncation.
        val finalLength = tokenizedInput.inputIds.size.toLong()

        // Shape for all tensors is [1, finalLength]
        val shape = longArrayOf(1, finalLength)

        // 1. input_ids uses the truncated array directly
        inputs["input_ids"] = createTensor(tokenizedInput.inputIds, shape)

        // 2. attention_mask and token_type_ids were padded to MAX_SEQUENCE_LENGTH in the tokenizer.
        // We must slice them to match the actual sequence length (finalLength) before creating the tensor.
        val attentionMaskData = tokenizedInput.attentionMask.sliceArray(0 until finalLength.toInt())
        inputs["attention_mask"] = createTensor(attentionMaskData, shape)

        val tokenTypeIdsData = tokenizedInput.tokenTypeIds.sliceArray(0 until finalLength.toInt())
        inputs["token_type_ids"] = createTensor(tokenTypeIdsData, shape)

        return inputs
    }

    private fun createTensor(data: LongArray, shape: LongArray): OnnxTensor {
        val longBuffer = LongBuffer.allocate(data.size)
        longBuffer.put(data)
        longBuffer.flip()
        return OnnxTensor.createTensor(ortEnv, longBuffer, shape)
    }

    /**
     * Releases resources held by the ONNX Runtime session and environment.
     */
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