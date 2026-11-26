package com.tinyml.smartlibrary.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tinyml.smartlibrary.decision.OccupancyDecisionManager
import com.tinyml.smartlibrary.model.DetectionResult
import com.tinyml.smartlibrary.model.OperatingMode
import com.tinyml.smartlibrary.util.BenchmarkHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainViewModel : ViewModel() {

    private val benchmarkHelper = BenchmarkHelper()
    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    // Object detection-driven occupancy manager
    private val decisionManager = OccupancyDecisionManager(
        personConfidenceThreshold = 0.45f,
        entryConfirmationFrames = 3,
        exitConfirmationFrames = 5
    )

    // Operating Mode: AUTO, MANUAL_ON, MANUAL_OFF
    private val _operatingMode = MutableStateFlow(OperatingMode.AUTO)
    val operatingMode: StateFlow<OperatingMode> = _operatingMode.asStateFlow()

    // Configurable Person Detection Confidence Threshold
    private val _confidenceThreshold = MutableStateFlow(decisionManager.personConfidenceThreshold)
    val confidenceThreshold: StateFlow<Float> = _confidenceThreshold.asStateFlow()

    // Debounce windows
    private val _entryConfirmationFrames = MutableStateFlow(decisionManager.entryConfirmationFrames)
    val entryConfirmationFrames: StateFlow<Int> = _entryConfirmationFrames.asStateFlow()

    private val _exitConfirmationFrames = MutableStateFlow(decisionManager.exitConfirmationFrames)
    val exitConfirmationFrames: StateFlow<Int> = _exitConfirmationFrames.asStateFlow()

    // Raw detection result from latest camera frame
    private val _latestDetection = MutableStateFlow<DetectionResult?>(null)
    val latestDetection: StateFlow<DetectionResult?> = _latestDetection.asStateFlow()

    // Confirmed Occupancy (stabilized via state machine)
    private val _isOccupied = MutableStateFlow(false)
    val isOccupied: StateFlow<Boolean> = _isOccupied.asStateFlow()

    // Person count in current frame
    private val _personCount = MutableStateFlow(0)
    val personCount: StateFlow<Int> = _personCount.asStateFlow()

    // Desired physical torch state (calculated from mode + occupancy)
    private val _desiredTorchEnabled = MutableStateFlow(false)
    val desiredTorchEnabled: StateFlow<Boolean> = _desiredTorchEnabled.asStateFlow()

    // Performance telemetry
    private val _averageLatencyMs = MutableStateFlow(0L)
    val averageLatencyMs: StateFlow<Long> = _averageLatencyMs.asStateFlow()

    private val _fps = MutableStateFlow(0.0f)
    val fps: StateFlow<Float> = _fps.asStateFlow()

    private val _isCameraActive = MutableStateFlow(false)
    val isCameraActive: StateFlow<Boolean> = _isCameraActive.asStateFlow()

    // Event audit trail
    private val _auditLogs = MutableStateFlow<List<String>>(emptyList())
    val auditLogs: StateFlow<List<String>> = _auditLogs.asStateFlow()

    init {
        addLog("EfficientDet-Lite0 INT8 Object Detector initialized.")
        addLog("Person occupancy filter ready. Threshold: ${decisionManager.personConfidenceThreshold} | Entry: ${decisionManager.entryConfirmationFrames}fr | Exit: ${decisionManager.exitConfirmationFrames}fr")
    }

    fun onFrameProcessed(result: DetectionResult) {
        viewModelScope.launch {
            _latestDetection.value = result
            _personCount.value = result.personCount
            benchmarkHelper.recordInference(result.inferenceTimeMs)
            _averageLatencyMs.value = benchmarkHelper.averageLatencyMs
            _fps.value = benchmarkHelper.fps

            val stateChanged = decisionManager.processResult(result)
            val currentOccupied = decisionManager.isOccupied
            _isOccupied.value = currentOccupied

            if (stateChanged) {
                if (currentOccupied) {
                    val count = result.personCount
                    val maxScore = (result.maxPersonScore * 100).toInt()
                    addLog("OCCUPANCY CONFIRMED: Bay Occupied ($count person(s), top confidence $maxScore%)")
                } else {
                    addLog("OCCUPANCY CONFIRMED: Bay Vacant (No person detected across exit window)")
                }
            } else if (result.topNonPersonLabel != null && result.personCount == 0) {
                // Periodically log non-person rejection for debugging visibility
                // (e.g. "bottle (82%) detected -> Ignored (no person)")
            }

            // In AUTO mode, update desired physical torch state
            if (_operatingMode.value == OperatingMode.AUTO) {
                updateDesiredTorchState(currentOccupied)
            }
        }
    }

    fun setOperatingMode(mode: OperatingMode) {
        if (_operatingMode.value != mode) {
            _operatingMode.value = mode
            addLog("Operating Mode changed to: ${mode.name}")
            when (mode) {
                OperatingMode.MANUAL_ON -> updateDesiredTorchState(true)
                OperatingMode.MANUAL_OFF -> updateDesiredTorchState(false)
                OperatingMode.AUTO -> updateDesiredTorchState(decisionManager.isOccupied)
            }
        }
    }

    fun setConfidenceThreshold(newThreshold: Float) {
        val clamped = newThreshold.coerceIn(0.10f, 0.95f)
        decisionManager.personConfidenceThreshold = clamped
        _confidenceThreshold.value = clamped
        addLog(String.format(Locale.US, "Person confidence threshold set to %.2f", clamped))
    }

    fun setEntryConfirmationFrames(frames: Int) {
        val clamped = frames.coerceIn(1, 15)
        decisionManager.entryConfirmationFrames = clamped
        _entryConfirmationFrames.value = clamped
        addLog("Entry confirmation debounce set to $clamped frames")
    }

    fun setExitConfirmationFrames(frames: Int) {
        val clamped = frames.coerceIn(1, 20)
        decisionManager.exitConfirmationFrames = clamped
        _exitConfirmationFrames.value = clamped
        addLog("Exit confirmation debounce set to $clamped frames")
    }

    fun setCameraActive(active: Boolean) {
        _isCameraActive.value = active
        addLog(if (active) "Camera live analysis started" else "Camera stream stopped")
        if (!active && _operatingMode.value == OperatingMode.AUTO) {
            updateDesiredTorchState(false)
        }
    }

    fun onSafetyStop() {
        updateDesiredTorchState(false)
        addLog("Safety stop: Physical torch commanded OFF")
    }

    private fun updateDesiredTorchState(shouldBeOn: Boolean) {
        val previous = _desiredTorchEnabled.value
        if (previous != shouldBeOn) {
            _desiredTorchEnabled.value = shouldBeOn
            val reason = when (_operatingMode.value) {
                OperatingMode.MANUAL_ON -> "Manual Force ON"
                OperatingMode.MANUAL_OFF -> "Manual Force OFF"
                OperatingMode.AUTO -> if (shouldBeOn) "Auto: Bay Occupied -> Torch ON" else "Auto: Bay Vacant -> Torch OFF"
            }
            addLog("Flashlight actuator -> ${if (shouldBeOn) "ON" else "OFF"} ($reason)")
        }
    }

    private fun addLog(message: String) {
        val time = timeFormatter.format(Date())
        val entry = "[$time] $message"
        val current = _auditLogs.value.toMutableList()
        current.add(0, entry)
        if (current.size > 25) {
            current.removeAt(current.size - 1)
        }
        _auditLogs.value = current
    }
}
