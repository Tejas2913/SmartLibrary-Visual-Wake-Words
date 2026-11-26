package com.tinyml.smartlibrary.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.tinyml.smartlibrary.R
import com.tinyml.smartlibrary.model.DetectedObject
import java.util.Locale

/**
 * Custom overlay view drawn directly on top of CameraX PreviewView.
 * Visualizes bounding boxes and labels for all detected objects.
 * Distinctly styles person detections (green) vs non-person objects (amber/gray with [IGNORED]).
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var results: List<DetectedObject> = emptyList()
    private var imageWidth: Int = 1
    private var imageHeight: Int = 1
    private var scaleFactor: Float = 1f
    private var offsetX: Float = 0f
    private var offsetY: Float = 0f

    // Person bounding box: vibrant neon green
    private val personBoxPaint = Paint().apply {
        color = Color.parseColor("#00E676")
        strokeWidth = 6f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val personTextBgPaint = Paint().apply {
        color = Color.parseColor("#CC00E676")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val personTextPaint = Paint().apply {
        color = Color.BLACK
        textSize = 34f
        isFakeBoldText = true
        isAntiAlias = true
    }

    // Non-person object: amber outline with [IGNORED] tag to explicitly show bottle/chair rejection
    private val nonPersonBoxPaint = Paint().apply {
        color = Color.parseColor("#FFA000")
        strokeWidth = 3.5f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val nonPersonTextBgPaint = Paint().apply {
        color = Color.parseColor("#CCFFA000")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val nonPersonTextPaint = Paint().apply {
        color = Color.BLACK
        textSize = 28f
        isFakeBoldText = true
        isAntiAlias = true
    }

    private val textBounds = Rect()

    fun setResults(
        detectionResults: List<DetectedObject>,
        imgWidth: Int,
        imgHeight: Int
    ) {
        results = detectionResults
        imageWidth = if (imgWidth > 0) imgWidth else 1
        imageHeight = if (imgHeight > 0) imgHeight else 1

        calculateScaleAndOffsets()
        postInvalidate()
    }

    fun clear() {
        results = emptyList()
        postInvalidate()
    }

    private fun calculateScaleAndOffsets() {
        if (width <= 0 || height <= 0 || imageWidth <= 0 || imageHeight <= 0) return

        val viewRatio = width.toFloat() / height.toFloat()
        val imageRatio = imageWidth.toFloat() / imageHeight.toFloat()

        if (viewRatio > imageRatio) {
            // View is wider than image: scale to fill width
            scaleFactor = width.toFloat() / imageWidth.toFloat()
            offsetX = 0f
            offsetY = (height.toFloat() - imageHeight.toFloat() * scaleFactor) / 2f
        } else {
            // View is taller than image: scale to fill height
            scaleFactor = height.toFloat() / imageHeight.toFloat()
            offsetX = (width.toFloat() - imageWidth.toFloat() * scaleFactor) / 2f
            offsetY = 0f
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        calculateScaleAndOffsets()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        for (obj in results) {
            val isPerson = obj.isPerson
            val boxPaint = if (isPerson) personBoxPaint else nonPersonBoxPaint
            val textBgPaint = if (isPerson) personTextBgPaint else nonPersonTextBgPaint
            val textPaint = if (isPerson) personTextPaint else nonPersonTextPaint

            // Map image bounding box coordinates to View coordinates
            val left = (obj.boundingBox.left * scaleFactor) + offsetX
            val top = (obj.boundingBox.top * scaleFactor) + offsetY
            val right = (obj.boundingBox.right * scaleFactor) + offsetX
            val bottom = (obj.boundingBox.bottom * scaleFactor) + offsetY

            val rect = RectF(
                left.coerceIn(0f, width.toFloat()),
                top.coerceIn(0f, height.toFloat()),
                right.coerceIn(0f, width.toFloat()),
                bottom.coerceIn(0f, height.toFloat())
            )

            // Draw bounding box
            canvas.drawRoundRect(rect, 8f, 8f, boxPaint)

            // Label text: e.g. "person: 88%" or "bottle: 74% [IGNORED]"
            val labelText = if (isPerson) {
                String.format(Locale.US, "person: %.0f%%", obj.score * 100f)
            } else {
                String.format(Locale.US, "%s: %.0f%% [IGNORED]", obj.label, obj.score * 100f)
            }

            textPaint.getTextBounds(labelText, 0, labelText.length, textBounds)
            val textHeight = textBounds.height().toFloat()
            val textWidth = textBounds.width().toFloat()

            // Badge rectangle at top-left of bounding box
            val badgeTop = (rect.top - textHeight - 16f).coerceAtLeast(0f)
            val badgeBottom = badgeTop + textHeight + 16f
            val badgeRight = (rect.left + textWidth + 20f).coerceAtMost(width.toFloat())

            val badgeRect = RectF(rect.left, badgeTop, badgeRight, badgeBottom)
            canvas.drawRoundRect(badgeRect, 6f, 6f, textBgPaint)

            // Draw text
            canvas.drawText(
                labelText,
                rect.left + 10f,
                badgeBottom - 8f,
                textPaint
            )
        }
    }
}
