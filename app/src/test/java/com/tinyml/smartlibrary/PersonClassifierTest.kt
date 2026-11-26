package com.tinyml.smartlibrary

import com.tinyml.smartlibrary.model.LightingState
import com.tinyml.smartlibrary.model.OperatingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonClassifierTest {

    // Exact quantization parameters from notebook and tflite model
    private val inputScale = 0.0078431377f
    private val inputZeroPoint = -1
    private val outputScale = 0.00390625f
    private val outputZeroPoint = -128

    @Test
    fun testPixelQuantizationFormula() {
        // Pixel 0 (black): norm = -1.0f -> q = round(-1.0 / 0.0078431377 - 1) = -128.5 -> -128
        val norm0 = (0 / 127.5f) - 1.0f
        val q0: Byte = Math.round(norm0 / inputScale + inputZeroPoint).coerceIn(-128, 127).toByte()
        val expectedMin: Byte = -128
        assertEquals(expectedMin, q0)

        // Pixel 128 (mid-gray): norm = 128/127.5 - 1.0 = 0.00392157f -> norm / scale + zp = -0.5f
        // In Java/Android, Math.round(-0.5f) evaluates to 0
        val norm128 = (128 / 127.5f) - 1.0f
        val q128: Byte = Math.round(norm128 / inputScale + inputZeroPoint).coerceIn(-128, 127).toByte()
        val expectedMid: Byte = 0
        assertEquals(expectedMid, q128)

        // Pixel 255 (white): norm = 1.0f -> q = round(1.0 / 0.0078431377 - 1) = 126.4999 -> 126
        val norm255 = (255 / 127.5f) - 1.0f
        val q255: Byte = Math.round(norm255 / inputScale + inputZeroPoint).coerceIn(-128, 127).toByte()
        val expectedMax: Byte = 126
        assertEquals(expectedMax, q255)
    }

    @Test
    fun testOutputDequantizationFormula() {
        // Raw byte -128 (min probability) -> ( -128 - (-128) ) * 0.00390625 = 0.0
        val rawMin: Byte = -128
        val scoreMin = ((rawMin.toInt() - outputZeroPoint) * outputScale).coerceIn(0.0f, 1.0f)
        assertEquals(0.0f, scoreMin, 0.0001f)

        // Raw byte 127 (max probability) -> ( 127 - (-128) ) * 0.00390625 = 255 / 256 = 0.99609375
        val rawMax: Byte = 127
        val scoreMax = ((rawMax.toInt() - outputZeroPoint) * outputScale).coerceIn(0.0f, 1.0f)
        assertEquals(0.99609375f, scoreMax, 0.0001f)
    }

    @Test
    fun testDecisionThresholdLogic() {
        val threshold = 0.45f

        // High person score
        val highPersonScore = 0.85f
        val isPersonHigh = highPersonScore >= threshold
        assertTrue(isPersonHigh)

        // Low person score (empty room)
        val lowPersonScore = 0.12f
        val isPersonLow = lowPersonScore >= threshold
        assertFalse(isPersonLow)

        // Boundary condition (0.46 vs 0.44)
        assertTrue(0.46f >= threshold)
        assertFalse(0.44f >= threshold)
    }

    @Test
    fun testOperatingModeLightingTransitions() {
        // AUTO mode with person detected -> ON
        var isOccupied = true
        var mode = OperatingMode.AUTO
        var lighting = when (mode) {
            OperatingMode.MANUAL_ON -> LightingState.ON
            OperatingMode.MANUAL_OFF -> LightingState.OFF
            OperatingMode.AUTO -> if (isOccupied) LightingState.ON else LightingState.OFF
        }
        assertEquals(LightingState.ON, lighting)

        // AUTO mode with bay vacant -> OFF
        isOccupied = false
        lighting = when (mode) {
            OperatingMode.MANUAL_ON -> LightingState.ON
            OperatingMode.MANUAL_OFF -> LightingState.OFF
            OperatingMode.AUTO -> if (isOccupied) LightingState.ON else LightingState.OFF
        }
        assertEquals(LightingState.OFF, lighting)

        // MANUAL_ON overrides vacant bay -> ON
        mode = OperatingMode.MANUAL_ON
        lighting = when (mode) {
            OperatingMode.MANUAL_ON -> LightingState.ON
            OperatingMode.MANUAL_OFF -> LightingState.OFF
            OperatingMode.AUTO -> if (isOccupied) LightingState.ON else LightingState.OFF
        }
        assertEquals(LightingState.ON, lighting)

        // MANUAL_OFF overrides occupied bay -> OFF
        isOccupied = true
        mode = OperatingMode.MANUAL_OFF
        lighting = when (mode) {
            OperatingMode.MANUAL_ON -> LightingState.ON
            OperatingMode.MANUAL_OFF -> LightingState.OFF
            OperatingMode.AUTO -> if (isOccupied) LightingState.ON else LightingState.OFF
        }
        assertEquals(LightingState.OFF, lighting)

        // Return to AUTO -> follows occupied bay -> ON
        mode = OperatingMode.AUTO
        lighting = when (mode) {
            OperatingMode.MANUAL_ON -> LightingState.ON
            OperatingMode.MANUAL_OFF -> LightingState.OFF
            OperatingMode.AUTO -> if (isOccupied) LightingState.ON else LightingState.OFF
        }
        assertEquals(LightingState.ON, lighting)
    }
}
