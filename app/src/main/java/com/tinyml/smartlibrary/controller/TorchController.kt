package com.tinyml.smartlibrary.controller

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager as AndroidCameraManager
import android.util.Log
import androidx.camera.core.Camera
import androidx.camera.core.TorchState
import androidx.core.content.ContextCompat
import com.tinyml.smartlibrary.model.PhysicalTorchState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executor

class TorchController(private val context: Context) {

    companion object {
        private const val TAG = "TorchController"
    }

    private val androidCameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? AndroidCameraManager
    private val mainExecutor: Executor = ContextCompat.getMainExecutor(context)

    // Bound CameraX instance (when live preview is running)
    private var activeCamera: Camera? = null

    // Target state desired by model / manual override
    private var currentTargetEnabled: Boolean = false

    // Actual hardware state observed from Camera / HAL
    private val _hardwareTorchState = MutableStateFlow(PhysicalTorchState.OFF)
    val hardwareTorchState: StateFlow<PhysicalTorchState> = _hardwareTorchState.asStateFlow()

    // Hardware capability & availability
    private val _isTorchAvailable = MutableStateFlow(hasPhysicalFlashSupport())
    val isTorchAvailable: StateFlow<Boolean> = _isTorchAvailable.asStateFlow()

    private val _statusMessage = MutableStateFlow("Flashlight ready")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private var rearCameraIdWithFlash: String? = findRearCameraWithFlash()

    private val torchCallback = object : AndroidCameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            super.onTorchModeChanged(cameraId, enabled)
            if (activeCamera == null && cameraId == rearCameraIdWithFlash) {
                _hardwareTorchState.value = if (enabled) PhysicalTorchState.ON else PhysicalTorchState.OFF
            }
        }

        override fun onTorchModeUnavailable(cameraId: String) {
            super.onTorchModeUnavailable(cameraId)
            if (activeCamera == null && cameraId == rearCameraIdWithFlash) {
                _isTorchAvailable.value = false
                _hardwareTorchState.value = PhysicalTorchState.UNAVAILABLE
                _statusMessage.value = "Torch unavailable on hardware"
            }
        }
    }

    init {
        try {
            androidCameraManager?.registerTorchCallback(torchCallback, null)
        } catch (e: Exception) {
            Log.w(TAG, "Could not register Android CameraManager TorchCallback", e)
        }
    }

    private fun hasPhysicalFlashSupport(): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)
    }

    private fun findRearCameraWithFlash(): String? {
        val manager = androidCameraManager ?: return null
        try {
            for (id in manager.cameraIdList) {
                val chars = manager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                val flashAvailable = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                if (facing == CameraCharacteristics.LENS_FACING_BACK && flashAvailable) {
                    return id
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error enumerating camera characteristics", e)
        }
        return null
    }

    /**
     * Attach an active CameraX instance.
     * When CameraX is active, torch is controlled via Camera.cameraControl.enableTorch().
     */
    fun attachCamera(camera: Camera) {
        activeCamera = camera
        val hasFlash = camera.cameraInfo.hasFlashUnit()
        _isTorchAvailable.value = hasFlash

        if (!hasFlash) {
            _hardwareTorchState.value = PhysicalTorchState.UNAVAILABLE
            _statusMessage.value = "Active camera lens lacks physical LED flash"
            return
        }

        _statusMessage.value = "Hardware torch linked to active camera"

        // Observe CameraX torch state LiveData
        camera.cameraInfo.torchState.observeForever { state ->
            val actual = if (state == TorchState.ON) PhysicalTorchState.ON else PhysicalTorchState.OFF
            _hardwareTorchState.value = actual
            _statusMessage.value = if (actual == PhysicalTorchState.ON) {
                "PHYSICAL REAR FLASHLIGHT: ACTIVE (ON)"
            } else {
                "PHYSICAL REAR FLASHLIGHT: STANDBY (OFF)"
            }
        }

        // Apply any pending target state
        setTorchEnabled(currentTargetEnabled)
    }

    /**
     * Detach CameraX when preview is paused/stopped.
     */
    fun detachCamera() {
        activeCamera = null
        _hardwareTorchState.value = PhysicalTorchState.OFF
    }

    /**
     * Request physical flashlight state.
     * Only invokes hardware when state actually changes.
     */
    @Synchronized
    fun setTorchEnabled(enable: Boolean) {
        currentTargetEnabled = enable

        val camera = activeCamera
        if (camera != null) {
            // CameraX is active: use cameraControl
            if (!camera.cameraInfo.hasFlashUnit()) {
                _hardwareTorchState.value = PhysicalTorchState.UNAVAILABLE
                _statusMessage.value = "Cannot enable torch: Camera lens has no flash unit"
                return
            }

            try {
                val future = camera.cameraControl.enableTorch(enable)
                future.addListener({
                    try {
                        future.get() // verify success
                    } catch (e: Exception) {
                        Log.e(TAG, "CameraControl enableTorch failed", e)
                        _statusMessage.value = "Torch control error: ${e.localizedMessage}"
                    }
                }, mainExecutor)
            } catch (e: Exception) {
                Log.e(TAG, "Exception calling enableTorch", e)
                _statusMessage.value = "Torch call failed: ${e.localizedMessage}"
            }
        } else {
            // Standalone Camera2 fallback
            val cameraId = rearCameraIdWithFlash
            if (cameraId == null || androidCameraManager == null) {
                _hardwareTorchState.value = PhysicalTorchState.UNAVAILABLE
                _statusMessage.value = "No physical rear flash detected"
                return
            }

            try {
                androidCameraManager.setTorchMode(cameraId, enable)
                _hardwareTorchState.value = if (enable) PhysicalTorchState.ON else PhysicalTorchState.OFF
                _statusMessage.value = if (enable) "Physical Torch ON (Standalone)" else "Physical Torch OFF (Standalone)"
            } catch (e: Exception) {
                Log.e(TAG, "Camera2 setTorchMode failed", e)
                _statusMessage.value = "Camera2 torch error: ${e.localizedMessage}"
            }
        }
    }

    /**
     * Safety method: unconditionally turns physical torch OFF.
     * Called during onPause, onStop, and onDestroy to guarantee no flash stays on.
     */
    @Synchronized
    fun turnOffSync() {
        currentTargetEnabled = false
        activeCamera?.cameraControl?.enableTorch(false)
        rearCameraIdWithFlash?.let { id ->
            try {
                androidCameraManager?.setTorchMode(id, false)
            } catch (ignored: Exception) {
            }
        }
        _hardwareTorchState.value = PhysicalTorchState.OFF
    }

    fun release() {
        turnOffSync()
        try {
            androidCameraManager?.unregisterTorchCallback(torchCallback)
        } catch (ignored: Exception) {
        }
        activeCamera = null
    }
}
