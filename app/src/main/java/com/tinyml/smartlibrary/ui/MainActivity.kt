package com.tinyml.smartlibrary.ui

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.tinyml.smartlibrary.R
import com.tinyml.smartlibrary.camera.CameraManager
import com.tinyml.smartlibrary.controller.TorchController
import com.tinyml.smartlibrary.databinding.ActivityMainBinding
import com.tinyml.smartlibrary.model.DetectionResult
import com.tinyml.smartlibrary.model.OperatingMode
import com.tinyml.smartlibrary.model.PhysicalTorchState
import com.tinyml.smartlibrary.tflite.PersonDetector
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private var personDetector: PersonDetector? = null
    private var cameraManager: CameraManager? = null
    private lateinit var torchController: TorchController

    private var glowAnimator: ObjectAnimator? = null

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            binding.layoutPermission.visibility = View.GONE
            setupCamera()
        } else {
            binding.layoutPermission.visibility = View.VISIBLE
            Toast.makeText(this, "Camera permission is required for person detection & torch actuation", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        torchController = TorchController(this)

        initializeDetector()
        setupUI()
        observeViewModel()
        checkCameraPermission()
    }

    private fun initializeDetector() {
        try {
            personDetector = PersonDetector(
                context = this,
                modelFilename = "efficientdet_lite0_int8.tflite",
                initialThreshold = viewModel.confidenceThreshold.value
            )
        } catch (e: Exception) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Detector Initialization Error")
                .setMessage("Could not load EfficientDet-Lite0 INT8 model: ${e.localizedMessage}")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun setupUI() {
        // Operating Mode Toggle Group
        binding.toggleModeGroup.check(R.id.btnModeAuto)
        binding.toggleModeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                when (checkedId) {
                    R.id.btnModeAuto -> viewModel.setOperatingMode(OperatingMode.AUTO)
                    R.id.btnModeManualOn -> viewModel.setOperatingMode(OperatingMode.MANUAL_ON)
                    R.id.btnModeManualOff -> viewModel.setOperatingMode(OperatingMode.MANUAL_OFF)
                }
            }
        }

        // Camera Switch Button (Rear / Front)
        binding.btnSwitchCamera.setOnClickListener {
            cameraManager?.switchCamera()
        }

        // Threshold & Actuator Settings Dialog
        binding.btnThresholdSettings.setOnClickListener {
            showThresholdDialog()
        }

        binding.btnGrantPermission.setOnClickListener {
            checkCameraPermission()
        }
    }

    private fun checkCameraPermission() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED -> {
                binding.layoutPermission.visibility = View.GONE
                setupCamera()
            }
            shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> {
                binding.layoutPermission.visibility = View.VISIBLE
            }
            else -> {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun setupCamera() {
        val detector = personDetector ?: return
        if (cameraManager == null) {
            cameraManager = CameraManager(
                context = this,
                lifecycleOwner = this,
                previewView = binding.viewFinder,
                personDetector = detector,
                onResult = { result ->
                    runOnUiThread {
                        viewModel.onFrameProcessed(result)
                    }
                },
                onCameraBound = { camera ->
                    runOnUiThread {
                        torchController.attachCamera(camera)
                    }
                },
                onError = { errorMsg ->
                    runOnUiThread {
                        Toast.makeText(this, errorMsg, Toast.LENGTH_SHORT).show()
                    }
                }
            )
            cameraManager?.startCamera()
            viewModel.setCameraActive(true)
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Desired torch state to physical controller
                launch {
                    viewModel.desiredTorchEnabled.collect { desiredState ->
                        torchController.setTorchEnabled(desiredState)
                    }
                }

                // Actual physical hardware state from controller
                launch {
                    torchController.hardwareTorchState.collect { actualState ->
                        updatePhysicalTorchUI(actualState)
                    }
                }

                launch {
                    torchController.statusMessage.collect { msg ->
                        binding.txtTorchHardwareMessage.text = msg
                        binding.txtTelemetryTorchHardware.text = msg
                    }
                }

                launch {
                    viewModel.latestDetection.collect { detection ->
                        updateDetectionUI(detection)
                    }
                }

                launch {
                    viewModel.operatingMode.collect { mode ->
                        updateModeUI(mode)
                    }
                }

                launch {
                    viewModel.isOccupied.collect { isOccupied ->
                        updateOccupancyTelemetry(isOccupied)
                    }
                }

                launch {
                    viewModel.confidenceThreshold.collect { threshold ->
                        personDetector?.scoreThreshold = threshold
                        updateThresholdTelemetry()
                    }
                }

                launch {
                    viewModel.entryConfirmationFrames.collect {
                        updateThresholdTelemetry()
                    }
                }

                launch {
                    viewModel.exitConfirmationFrames.collect {
                        updateThresholdTelemetry()
                    }
                }

                launch {
                    viewModel.fps.collect { fps ->
                        binding.txtFpsPill.text = String.format(Locale.US, "%.1f FPS", fps)
                    }
                }

                launch {
                    viewModel.auditLogs.collect { logs ->
                        binding.txtAuditLog.text = logs.joinToString("\n")
                    }
                }
            }
        }
    }

    private fun updateThresholdTelemetry() {
        val thresh = viewModel.confidenceThreshold.value
        val entry = viewModel.entryConfirmationFrames.value
        val exit = viewModel.exitConfirmationFrames.value
        binding.txtTelemetryThreshold.text = String.format(
            Locale.US,
            "%.2f (In: %dfr / Out: %dfr)",
            thresh,
            entry,
            exit
        )
    }

    private fun updateDetectionUI(detection: DetectionResult?) {
        if (detection == null) {
            binding.overlayView.clear()
            return
        }

        // Draw bounding boxes on canvas overlay
        binding.overlayView.setResults(
            detectionResults = detection.allDetections,
            imgWidth = detection.imageWidth,
            imgHeight = detection.imageHeight
        )

        val isOccupied = viewModel.isOccupied.value
        val personCount = detection.personCount
        val maxScore = (detection.maxPersonScore * 100).toInt()

        if (isOccupied) {
            binding.txtDetectionPill.text = getString(R.string.person_detected)
            binding.txtDetectionPill.setTextColor(ContextCompat.getColor(this, R.color.status_occupied))
        } else {
            binding.txtDetectionPill.text = getString(R.string.no_person_detected)
            binding.txtDetectionPill.setTextColor(ContextCompat.getColor(this, R.color.status_vacant))
        }

        binding.txtConfidenceScore.text = String.format(
            Locale.US,
            "People: %d (Max: %d%%)",
            personCount,
            maxScore
        )

        binding.txtLatencyScore.text = String.format(Locale.US, "%d ms", detection.inferenceTimeMs)

        // Telemetry row for non-person objects (e.g. "bottle (82%) [Ignored]")
        if (detection.topNonPersonLabel != null) {
            binding.txtTelemetryNonPerson.text = "${detection.topNonPersonLabel} [IGNORED]"
            binding.txtTelemetryNonPerson.setTextColor(ContextCompat.getColor(this, R.color.accent_cyan))
        } else {
            binding.txtTelemetryNonPerson.text = "None (Bay Clean)"
            binding.txtTelemetryNonPerson.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
        }
    }

    private fun updatePhysicalTorchUI(state: PhysicalTorchState) {
        val onBg = ContextCompat.getColor(this, R.color.light_card_on_bg)
        val offBg = ContextCompat.getColor(this, R.color.light_card_off_bg)
        val amberColor = ContextCompat.getColor(this, R.color.light_amber_on)
        val whiteColor = ContextCompat.getColor(this, R.color.text_primary)
        val redColor = ContextCompat.getColor(this, R.color.status_vacant)

        when (state) {
            PhysicalTorchState.ON -> {
                binding.txtLightingStatus.text = getString(R.string.torch_status_on)
                binding.txtLightingStatus.setTextColor(amberColor)
                binding.txtBayStatusDesc.text = getString(R.string.bay_occupied_desc)
                binding.imgBulb.setImageResource(R.drawable.ic_lightbulb_on)
                binding.cardLighting.setCardBackgroundColor(onBg)
                binding.bulbGlowView.visibility = View.VISIBLE
                binding.txtHardwareStatePill.text = "HARDWARE TORCH: ON"
                binding.txtHardwareStatePill.setTextColor(amberColor)

                startGlowAnimation()
            }
            PhysicalTorchState.OFF -> {
                binding.txtLightingStatus.text = getString(R.string.torch_status_off)
                binding.txtLightingStatus.setTextColor(whiteColor)
                binding.txtBayStatusDesc.text = getString(R.string.bay_unoccupied_desc)
                binding.imgBulb.setImageResource(R.drawable.ic_lightbulb_off)
                binding.cardLighting.setCardBackgroundColor(offBg)
                binding.bulbGlowView.visibility = View.INVISIBLE
                binding.txtHardwareStatePill.text = "HARDWARE TORCH: OFF"
                binding.txtHardwareStatePill.setTextColor(ContextCompat.getColor(this, R.color.status_vacant))

                stopGlowAnimation()
            }
            PhysicalTorchState.UNAVAILABLE -> {
                binding.txtLightingStatus.text = getString(R.string.torch_status_unavailable)
                binding.txtLightingStatus.setTextColor(redColor)
                binding.txtBayStatusDesc.text = "Device or selected lens lacks physical flash"
                binding.imgBulb.setImageResource(R.drawable.ic_lightbulb_off)
                binding.cardLighting.setCardBackgroundColor(offBg)
                binding.bulbGlowView.visibility = View.INVISIBLE
                binding.txtHardwareStatePill.text = "TORCH UNAVAILABLE"
                binding.txtHardwareStatePill.setTextColor(redColor)

                stopGlowAnimation()
            }
        }
    }

    private fun startGlowAnimation() {
        if (glowAnimator == null) {
            val scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 0.9f, 1.25f)
            val scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.9f, 1.25f)
            val alpha = PropertyValuesHolder.ofFloat(View.ALPHA, 0.4f, 0.9f)
            glowAnimator = ObjectAnimator.ofPropertyValuesHolder(binding.bulbGlowView, scaleX, scaleY, alpha).apply {
                duration = 1000
                repeatCount = ObjectAnimator.INFINITE
                repeatMode = ObjectAnimator.REVERSE
                interpolator = AccelerateDecelerateInterpolator()
            }
        }
        if (!glowAnimator!!.isRunning) {
            glowAnimator?.start()
        }
    }

    private fun stopGlowAnimation() {
        glowAnimator?.cancel()
        binding.bulbGlowView.scaleX = 1f
        binding.bulbGlowView.scaleY = 1f
        binding.bulbGlowView.alpha = 1f
    }

    private fun updateModeUI(mode: OperatingMode) {
        val expectedButtonId = when (mode) {
            OperatingMode.AUTO -> R.id.btnModeAuto
            OperatingMode.MANUAL_ON -> R.id.btnModeManualOn
            OperatingMode.MANUAL_OFF -> R.id.btnModeManualOff
        }
        if (binding.toggleModeGroup.checkedButtonId != expectedButtonId) {
            binding.toggleModeGroup.check(expectedButtonId)
        }

        binding.txtModeBadge.text = when (mode) {
            OperatingMode.AUTO -> "Mode: AUTO (Object Detection Actuator)"
            OperatingMode.MANUAL_ON -> "Mode: MANUAL OVERRIDE (FORCED TORCH ON)"
            OperatingMode.MANUAL_OFF -> "Mode: MANUAL OVERRIDE (FORCED TORCH OFF)"
        }
    }

    private fun updateOccupancyTelemetry(isOccupied: Boolean) {
        if (isOccupied) {
            binding.txtTelemetryOccupancy.text = getString(R.string.status_occupied)
            binding.txtTelemetryOccupancy.setTextColor(ContextCompat.getColor(this, R.color.status_occupied))
        } else {
            binding.txtTelemetryOccupancy.text = getString(R.string.status_unoccupied)
            binding.txtTelemetryOccupancy.setTextColor(ContextCompat.getColor(this, R.color.status_vacant))
        }
    }

    private fun showThresholdDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_threshold, null)
        val sliderThreshold = dialogView.findViewById<Slider>(R.id.sliderThreshold)
        val txtThresholdVal = dialogView.findViewById<TextView>(R.id.txtDialogThresholdValue)
        val sliderEntry = dialogView.findViewById<Slider>(R.id.sliderEntryFrames)
        val txtEntryVal = dialogView.findViewById<TextView>(R.id.txtDialogEntryValue)
        val sliderExit = dialogView.findViewById<Slider>(R.id.sliderExitFrames)
        val txtExitVal = dialogView.findViewById<TextView>(R.id.txtDialogExitValue)
        val btnSetRec = dialogView.findViewById<MaterialButton>(R.id.btnResetColabRecommended)
        val btnSetDef = dialogView.findViewById<MaterialButton>(R.id.btnResetDefault)
        val btnApply = dialogView.findViewById<MaterialButton>(R.id.btnApplyThreshold)

        sliderThreshold.value = viewModel.confidenceThreshold.value
        txtThresholdVal.text = String.format(Locale.US, "%.2f", sliderThreshold.value)

        sliderEntry.value = viewModel.entryConfirmationFrames.value.toFloat()
        txtEntryVal.text = sliderEntry.value.toInt().toString()

        sliderExit.value = viewModel.exitConfirmationFrames.value.toFloat()
        txtExitVal.text = sliderExit.value.toInt().toString()

        sliderThreshold.addOnChangeListener { _, value, _ ->
            txtThresholdVal.text = String.format(Locale.US, "%.2f", value)
        }

        sliderEntry.addOnChangeListener { _, value, _ ->
            txtEntryVal.text = value.toInt().toString()
        }

        sliderExit.addOnChangeListener { _, value, _ ->
            txtExitVal.text = value.toInt().toString()
        }

        btnSetRec.setOnClickListener {
            sliderThreshold.value = 0.45f
            txtThresholdVal.text = "0.45"
            sliderEntry.value = 3f
            txtEntryVal.text = "3"
            sliderExit.value = 5f
            txtExitVal.text = "5"
        }

        btnSetDef.setOnClickListener {
            sliderThreshold.value = 0.60f
            txtThresholdVal.text = "0.60"
            sliderEntry.value = 2f
            txtEntryVal.text = "2"
            sliderExit.value = 4f
            txtExitVal.text = "4"
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        btnApply.setOnClickListener {
            viewModel.setConfidenceThreshold(sliderThreshold.value)
            viewModel.setEntryConfirmationFrames(sliderEntry.value.toInt())
            viewModel.setExitConfirmationFrames(sliderExit.value.toInt())
            dialog.dismiss()
        }

        dialog.show()
    }

    override fun onPause() {
        super.onPause()
        // Hardware Safety: Unconditionally turn physical flashlight off when backgrounded
        torchController.turnOffSync()
        viewModel.onSafetyStop()
    }

    override fun onStop() {
        super.onStop()
        torchController.turnOffSync()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopGlowAnimation()
        torchController.release()
        cameraManager?.shutdown()
        personDetector?.close()
    }
}
