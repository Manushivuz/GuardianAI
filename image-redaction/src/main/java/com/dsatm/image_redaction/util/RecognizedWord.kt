package com.dsatm.image_redaction.util

import android.graphics.Rect

data class RecognizedWord(
    val text: String,
    val confidence: Float,
    val boundingBox: Rect
)