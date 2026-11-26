package com.tinyml.smartlibrary.decision

import com.tinyml.smartlibrary.model.DetectionResult

/**
 * Stabilized Occupancy State Machine driven strictly by valid Person Object Detections.
 *
 * Core Guarantees:
 * 1. Non-Person Object Immunity: Bottles, chairs, backpacks, cups, TVs, etc., NEVER activate occupancy.
 * 2. Temporal Entry Confirmation: Requires [entryConfirmationFrames] consecutive frames with valid person
 *    detections before switching VACANT → OCCUPIED.
 * 3. Temporal Exit Confirmation: Requires [exitConfirmationFrames] consecutive frames with no person
 *    before switching OCCUPIED → VACANT (prevents torch flickering when a seated reader briefly turns away).
 * 4. State Retention: Retains the previous confirmed state during brief interruptions.
 */
class OccupancyDecisionManager(
    var personConfidenceThreshold: Float = 0.45f,
    var entryConfirmationFrames: Int = 3,
    var exitConfirmationFrames: Int = 5
) {

    /** Current stable occupancy state. */
    var isOccupied: Boolean = false
        private set

    /** Consecutive frames where at least one valid person was detected above threshold. */
    var consecutivePersonFrames: Int = 0
        private set

    /** Consecutive frames with zero valid person detections. */
    var consecutiveNoPersonFrames: Int = 0
        private set

    /** Total number of person detections in the latest processed frame. */
    var latestPersonCount: Int = 0
        private set

    /** Highest person confidence score in the latest frame (0.0 if no person). */
    var latestMaxPersonScore: Float = 0.0f
        private set

    /** Description of any non-person object detected in latest frame (for logging/debug). */
    var latestNonPersonObject: String? = null
        private set

    /**
     * Ingest one frame's object detection result.
     * @return true if the stable occupancy state changed as a result of this frame.
     */
    fun processResult(result: DetectionResult): Boolean {
        latestPersonCount = result.personCount
        latestMaxPersonScore = result.maxPersonScore
        latestNonPersonObject = result.topNonPersonLabel

        // Strict Person Check: Only valid person detections above the threshold count!
        val hasValidPerson = result.personCount > 0 && result.maxPersonScore >= personConfidenceThreshold

        val previous = isOccupied

        if (hasValidPerson) {
            consecutivePersonFrames++
            consecutiveNoPersonFrames = 0

            if (!isOccupied && consecutivePersonFrames >= entryConfirmationFrames) {
                isOccupied = true
            }
        } else {
            consecutiveNoPersonFrames++
            consecutivePersonFrames = 0

            if (isOccupied && consecutiveNoPersonFrames >= exitConfirmationFrames) {
                isOccupied = false
            }
        }

        return previous != isOccupied
    }

    fun reset() {
        isOccupied = false
        consecutivePersonFrames = 0
        consecutiveNoPersonFrames = 0
        latestPersonCount = 0
        latestMaxPersonScore = 0f
        latestNonPersonObject = null
    }
}
