package com.tinyml.smartlibrary

import android.graphics.RectF
import com.tinyml.smartlibrary.decision.OccupancyDecisionManager
import com.tinyml.smartlibrary.model.DetectedObject
import com.tinyml.smartlibrary.model.DetectionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OccupancyDecisionManagerTest {

    private fun makeDetectionResult(
        vararg objects: Pair<String, Float> // label to score
    ): DetectionResult {
        val detectedList = objects.map { (label, score) ->
            DetectedObject(
                label = label,
                score = score,
                boundingBox = RectF(10f, 10f, 100f, 100f),
                isPerson = label.equals("person", ignoreCase = true)
            )
        }

        val personList = detectedList.filter { it.isPerson }
        val nonPersonList = detectedList.filter { !it.isPerson }
        val topNonPerson = nonPersonList.maxByOrNull { it.score }?.let { "${it.label} (${(it.score * 100).toInt()}%)" }

        return DetectionResult(
            allDetections = detectedList,
            personDetections = personList,
            personCount = personList.size,
            maxPersonScore = personList.maxOfOrNull { it.score } ?: 0.0f,
            topNonPersonLabel = topNonPerson,
            inferenceTimeMs = 15,
            imageWidth = 640,
            imageHeight = 480
        )
    }

    @Test
    fun testInitialState() {
        val manager = OccupancyDecisionManager()
        assertFalse("Initial bay must be vacant", manager.isOccupied)
        assertEquals(0, manager.consecutivePersonFrames)
        assertEquals(0, manager.consecutiveNoPersonFrames)
    }

    @Test
    fun testWaterBottleNeverTriggersOccupancy() {
        val manager = OccupancyDecisionManager(
            personConfidenceThreshold = 0.45f,
            entryConfirmationFrames = 3,
            exitConfirmationFrames = 5
        )

        // Simulate 10 consecutive frames showing a water bottle with 95% confidence
        for (i in 1..10) {
            val result = makeDetectionResult("bottle" to 0.95f)
            val changed = manager.processResult(result)

            assertFalse("Water bottle must NEVER trigger state change to occupied", changed)
            assertFalse("Occupancy must remain FALSE when only bottle is present", manager.isOccupied)
            assertEquals("Person count must be 0", 0, manager.latestPersonCount)
        }
    }

    @Test
    fun testChairAndBackpackNeverTriggerOccupancy() {
        val manager = OccupancyDecisionManager()

        // Empty chair + backpack
        for (i in 1..10) {
            val result = makeDetectionResult("chair" to 0.88f, "backpack" to 0.92f)
            val changed = manager.processResult(result)

            assertFalse("Chair and backpack must NEVER trigger occupancy", changed)
            assertFalse(manager.isOccupied)
        }
    }

    @Test
    fun testTemporalEntryConfirmation() {
        val manager = OccupancyDecisionManager(
            personConfidenceThreshold = 0.45f,
            entryConfirmationFrames = 3,
            exitConfirmationFrames = 5
        )

        val personResult = makeDetectionResult("person" to 0.85f)

        // Frame 1: Person detected -> not yet confirmed (requires 3 frames)
        val c1 = manager.processResult(personResult)
        assertFalse("Frame 1 should not trigger immediate occupancy", c1)
        assertFalse(manager.isOccupied)
        assertEquals(1, manager.consecutivePersonFrames)

        // Frame 2: Person detected -> not yet confirmed
        val c2 = manager.processResult(personResult)
        assertFalse("Frame 2 should not trigger occupancy yet", c2)
        assertFalse(manager.isOccupied)
        assertEquals(2, manager.consecutivePersonFrames)

        // Frame 3: Person detected -> CONFIRMED OCCUPIED!
        val c3 = manager.processResult(personResult)
        assertTrue("Frame 3 must confirm occupancy", c3)
        assertTrue("Occupancy must now be true", manager.isOccupied)
        assertEquals(3, manager.consecutivePersonFrames)

        // Frame 4: Person remains -> already occupied, no transition
        val c4 = manager.processResult(personResult)
        assertFalse(c4)
        assertTrue(manager.isOccupied)
    }

    @Test
    fun testTemporalExitConfirmationPreventsFlickering() {
        val manager = OccupancyDecisionManager(
            personConfidenceThreshold = 0.45f,
            entryConfirmationFrames = 3,
            exitConfirmationFrames = 5
        )

        // First establish confirmed occupancy
        val personResult = makeDetectionResult("person" to 0.80f)
        manager.processResult(personResult)
        manager.processResult(personResult)
        manager.processResult(personResult)
        assertTrue(manager.isOccupied)

        // Patron looks down or transient camera occlusion (empty bay for 1..4 frames)
        val emptyResult = makeDetectionResult()

        // Frame 1 empty: must retain occupied
        val c1 = manager.processResult(emptyResult)
        assertFalse(c1)
        assertTrue("Occupancy must be retained on frame 1 empty", manager.isOccupied)
        assertEquals(1, manager.consecutiveNoPersonFrames)

        // Frame 2 empty: must retain occupied
        val c2 = manager.processResult(emptyResult)
        assertFalse(c2)
        assertTrue("Occupancy must be retained on frame 2 empty", manager.isOccupied)

        // Frame 3 empty: must retain occupied
        val c3 = manager.processResult(emptyResult)
        assertFalse(c3)
        assertTrue("Occupancy must be retained on frame 3 empty", manager.isOccupied)

        // Frame 4 empty: must retain occupied
        val c4 = manager.processResult(emptyResult)
        assertFalse(c4)
        assertTrue("Occupancy must be retained on frame 4 empty", manager.isOccupied)

        // Frame 5 empty: CONFIRMED VACANT!
        val c5 = manager.processResult(emptyResult)
        assertTrue("Frame 5 empty must transition to vacant", c5)
        assertFalse("Occupancy must now be false", manager.isOccupied)
        assertEquals(5, manager.consecutiveNoPersonFrames)
    }

    @Test
    fun testSimultaneousPersonAndWaterBottle() {
        val manager = OccupancyDecisionManager(
            personConfidenceThreshold = 0.45f,
            entryConfirmationFrames = 2
        )

        // Person studying with water bottle on the desk
        val studyResult = makeDetectionResult("person" to 0.82f, "bottle" to 0.90f)

        manager.processResult(studyResult)
        val changed = manager.processResult(studyResult)

        assertTrue("Person alongside bottle must activate occupancy", changed)
        assertTrue(manager.isOccupied)
        assertEquals(1, manager.latestPersonCount)
        assertEquals(0.82f, manager.latestMaxPersonScore, 0.001f)
    }

    @Test
    fun testMultiplePeopleDetection() {
        val manager = OccupancyDecisionManager(
            personConfidenceThreshold = 0.45f,
            entryConfirmationFrames = 1
        )

        // Group study bay: 3 people
        val groupResult = makeDetectionResult("person" to 0.88f, "person" to 0.76f, "person" to 0.91f)
        val changed = manager.processResult(groupResult)

        assertTrue(changed)
        assertTrue(manager.isOccupied)
        assertEquals(3, manager.latestPersonCount)
        assertEquals(0.91f, manager.latestMaxPersonScore, 0.001f)
    }

    @Test
    fun testLowConfidencePersonBelowThresholdIgnored() {
        val manager = OccupancyDecisionManager(
            personConfidenceThreshold = 0.50f,
            entryConfirmationFrames = 2
        )

        // Faint ambiguity (score 0.35, below 0.50 threshold)
        val lowConfResult = makeDetectionResult("person" to 0.35f)
        manager.processResult(lowConfResult)
        val c2 = manager.processResult(lowConfResult)

        assertFalse("Person below confidence threshold must not activate", c2)
        assertFalse(manager.isOccupied)
    }

    @Test
    fun testReset() {
        val manager = OccupancyDecisionManager(entryConfirmationFrames = 1)
        manager.processResult(makeDetectionResult("person" to 0.90f))
        assertTrue(manager.isOccupied)

        manager.reset()
        assertFalse(manager.isOccupied)
        assertEquals(0, manager.consecutivePersonFrames)
        assertEquals(0, manager.consecutiveNoPersonFrames)
    }
}
