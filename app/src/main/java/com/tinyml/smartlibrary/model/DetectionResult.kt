package com.tinyml.smartlibrary.model

data class DetectionResult(
    val allDetections: List<DetectedObject>,
    val personDetections: List<DetectedObject>,
    val personCount: Int,
    val maxPersonScore: Float,
    val topNonPersonLabel: String?,
    val inferenceTimeMs: Long,
    val imageWidth: Int,
    val imageHeight: Int,
    val timestamp: Long = System.currentTimeMillis()
) {
    val hasPerson: Boolean get() = personCount > 0
}
