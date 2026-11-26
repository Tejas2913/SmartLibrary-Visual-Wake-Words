package com.tinyml.smartlibrary.model

import android.graphics.RectF

data class DetectedObject(
    val label: String,
    val score: Float,
    val boundingBox: RectF, // Normalized [0..1] or pixel coordinates
    val isPerson: Boolean = label.equals("person", ignoreCase = true)
)
