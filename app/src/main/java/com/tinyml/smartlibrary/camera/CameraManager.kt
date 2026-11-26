package com.tinyml.smartlibrary.camera

import android.content.Context
import android.util.Log
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.tinyml.smartlibrary.model.DetectionResult
import com.tinyml.smartlibrary.tflite.ImageUtils
import com.tinyml.smartlibrary.tflite.PersonDetector
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class CameraManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val personDetector: PersonDetector,
    private val onResult: (DetectionResult) -> Unit,
    private val onCameraBound: (Camera) -> Unit,
    private val onError: (String) -> Unit
) {

    companion object {
        private const val TAG = "CameraManager"
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val isAnalyzing = AtomicBoolean(false)

    var currentLensFacing: Int = CameraSelector.LENS_FACING_BACK
        private set

    fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases()
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
                onError("Failed to start camera: ${exc.localizedMessage}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun switchCamera() {
        currentLensFacing = if (currentLensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        bindCameraUseCases()
    }

    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(currentLensFacing)
            .build()

        val preview = Preview.Builder()
            .setTargetResolution(Size(640, 480))
            .build()
            .also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

        val imageAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(Size(640, 480))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    processImageProxy(imageProxy)
                }
            }

        try {
            provider.unbindAll()
            val camera = provider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalysis)
            onCameraBound(camera)
        } catch (exc: Exception) {
            Log.e(TAG, "Binding camera use cases failed", exc)
            onError("Camera configuration error: ${exc.localizedMessage}")
        }
    }

    private fun processImageProxy(imageProxy: ImageProxy) {
        if (isAnalyzing.compareAndSet(false, true)) {
            try {
                val bitmap = ImageUtils.imageProxyToBitmap(imageProxy)
                val result = personDetector.detect(bitmap)
                bitmap.recycle()
                onResult(result)
            } catch (e: Exception) {
                Log.e(TAG, "Detection error on frame", e)
            } finally {
                imageProxy.close()
                isAnalyzing.set(false)
            }
        } else {
            imageProxy.close()
        }
    }

    fun shutdown() {
        cameraExecutor.shutdown()
        cameraProvider?.unbindAll()
    }
}
