package com.dsatm.image_redaction

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.dsatm.image_redaction.util.ImageRedactionUtils
import java.io.ByteArrayOutputStream

/**
 * Manages the high-level image redaction flow, converting input bytes to output bytes.
 */
class ImageRedactionManager(private val context: Context) {
    private val TAG = "ImageRedactionManager"

    /**
     * Takes raw image bytes, performs redaction, and returns the redacted image bytes.
     * @param originalImageBytes The unencrypted, original image data.
     * @return The redacted image data as a ByteArray.
     */
    suspend fun redactImage(originalImageBytes: ByteArray): ByteArray {
        Log.d(TAG, "Starting redaction for image data of size ${originalImageBytes.size}")

        // Convert Bytes to Bitmap
        val bitmap = BitmapFactory.decodeByteArray(originalImageBytes, 0, originalImageBytes.size)
            ?: throw IllegalArgumentException("Could not decode image bytes into Bitmap.")

        // Perform the core redaction logic, which returns the modified Bitmap
        val redactedBitmap = ImageRedactionUtils.redactSensitiveInImage(context, bitmap)

        // --- FINAL FIX: Ensure Bitmap is compressed correctly ---
        val outputStream = ByteArrayOutputStream()

        // Use a safe compression format (JPEG is widely supported and handles colorspace issues better)
        redactedBitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)

        Log.d(TAG, "Redaction complete. Output bytes size: ${outputStream.size()} (Should be slightly smaller than input due to JPEG recompression)")

        // Clean up the Bitmap objects to avoid memory issues (Crucial in Android)
        if (!bitmap.isRecycled) bitmap.recycle()
        if (!redactedBitmap.isRecycled && redactedBitmap !== bitmap) redactedBitmap.recycle()

        return outputStream.toByteArray()
    }
}
