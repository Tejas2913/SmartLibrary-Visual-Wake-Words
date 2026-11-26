package com.tinyml.smartlibrary.tflite

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.tinyml.smartlibrary.model.DetectedObject
import com.tinyml.smartlibrary.model.DetectionResult
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.detector.ObjectDetector

/**
 * On-device Person & Object Detector using the official Google COCO-pretrained
 * EfficientDet-Lite0 INT8 model via TensorFlow Lite Task Vision.
 *
 * Explicitly distinguishes "person" detections from other COCO objects
 * (e.g., water bottles, chairs, backpacks, cups, TVs).
 */
class PersonDetector(
    private val context: Context,
    private val modelFilename: String = "efficientdet_lite0_int8.tflite",
    initialThreshold: Float = DEFAULT_THRESHOLD,
    private val numThreads: Int = 4
) {

    companion object {
        private const val TAG = "PersonDetector"
        const val DEFAULT_THRESHOLD = 0.45f
        const val MAX_RESULTS = 10
        const val LABEL_PERSON = "person"
    }

    var scoreThreshold: Float = initialThreshold
        set(value) {
            field = value.coerceIn(0.10f, 0.95f)
            initDetector()
        }

    private var detector: ObjectDetector? = null

    init {
        initDetector()
    }

    @Synchronized
    private fun initDetector() {
        try {
            detector?.close()

            val baseOptions = BaseOptions.builder()
                .setNumThreads(numThreads)
                .build()

            val options = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(baseOptions)
                // Set detector sensitivity low enough so non-person objects (e.g. bottles)
                // are detected and reported for debugging/visualization, while person-filtering
                // uses the calibrated scoreThreshold.
                .setScoreThreshold(0.20f)
                .setMaxResults(MAX_RESULTS)
                .build()

            detector = ObjectDetector.createFromFileAndOptions(
                context,
                modelFilename,
                options
            )
            Log.i(TAG, "Successfully initialized EfficientDet-Lite0 INT8 detector.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize ObjectDetector: ${e.message}", e)
            throw IllegalStateException("Failed to load $modelFilename: ${e.localizedMessage}", e)
        }
    }

    @Synchronized
    fun detect(bitmap: Bitmap): DetectionResult {
        val activeDetector = detector
            ?: throw IllegalStateException("ObjectDetector is not initialized")

        val startTime = SystemClock.uptimeMillis()

        val tensorImage = TensorImage.fromBitmap(bitmap)
        val rawDetections = activeDetector.detect(tensorImage)

        val allDetected = mutableListOf<DetectedObject>()
        val personDetected = mutableListOf<DetectedObject>()
        var maxPersonScore = 0.0f
        var topNonPerson: DetectedObject? = null

        for (detection in rawDetections) {
            val category = detection.categories.firstOrNull() ?: continue
            val label = category.label.lowercase().trim()
            val score = category.score
            val isPerson = label == LABEL_PERSON

            val obj = DetectedObject(
                label = label,
                score = score,
                boundingBox = detection.boundingBox,
                isPerson = isPerson
            )
            allDetected.add(obj)

            if (isPerson) {
                // Apply the calibrated person threshold
                if (score >= scoreThreshold) {
                    personDetected.add(obj)
                    if (score > maxPersonScore) {
                        maxPersonScore = score
                    }
                }
            } else {
                if (topNonPerson == null || score > topNonPerson.score) {
                    topNonPerson = obj
                }
            }
        }

        val latencyMs = SystemClock.uptimeMillis() - startTime

        val topNonPersonStr = topNonPerson?.let {
            "${it.label} (${(it.score * 100).toInt()}%)"
        }

        return DetectionResult(
            allDetections = allDetected,
            personDetections = personDetected,
            personCount = personDetected.size,
            maxPersonScore = maxPersonScore,
            topNonPersonLabel = topNonPersonStr,
            inferenceTimeMs = latencyMs,
            imageWidth = bitmap.width,
            imageHeight = bitmap.height
        )
    }

    fun close() {
        detector?.close()
        detector = null
    }
}
