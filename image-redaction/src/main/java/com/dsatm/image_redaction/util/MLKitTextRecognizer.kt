package com.dsatm.image_redaction.util

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

object MLKitTextRecognizer {
    private const val TAG = "MLKitTextRecognizer"

// ... (Existing code remains above) ...

    suspend fun recognizeText(context: Context, imageBitmap: Bitmap): List<RecognizedWord> {
        // Use Bitmap directly to create InputImage
        val inputImage = InputImage.fromBitmap(imageBitmap, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val recognizedWords = mutableListOf<RecognizedWord>()

        Log.d(TAG, "Starting ML Kit text recognition for in-memory Bitmap.")

        val visionText = try {
            recognizer.process(inputImage).await()
        } catch (e: Exception) {
            Log.e(TAG, "ML Kit text recognition failed: ${e.message}", e)
            return emptyList()
        }

        val fullText = visionText.text
        Log.d(TAG, "Full text recognized by ML Kit:\n$fullText")

        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                for (element in line.elements) {
                    if (element.confidence > 0.7 && element.boundingBox != null) {
                        val wordText = element.text
                        val boundingBox = element.boundingBox!!
                        recognizedWords.add(RecognizedWord(wordText, element.confidence, boundingBox))
                    }
                }
            }
        }
        return recognizedWords
    }
}
