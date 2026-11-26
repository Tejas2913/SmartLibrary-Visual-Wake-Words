package com.tinyml.smartlibrary.util

class BenchmarkHelper(private val windowSize: Int = 10) {

    private val latencies = ArrayDeque<Long>()
    private var lastFrameTimestamp: Long = 0
    private var currentFps: Float = 0.0f

    fun recordInference(latencyMs: Long) {
        if (latencies.size >= windowSize) {
            latencies.removeFirst()
        }
        latencies.addLast(latencyMs)

        val now = System.currentTimeMillis()
        if (lastFrameTimestamp > 0) {
            val delta = now - lastFrameTimestamp
            if (delta > 0) {
                val instantFps = 1000f / delta
                currentFps = if (currentFps == 0f) instantFps else (0.8f * currentFps + 0.2f * instantFps)
            }
        }
        lastFrameTimestamp = now
    }

    val averageLatencyMs: Long
        get() = if (latencies.isEmpty()) 0L else latencies.sum() / latencies.size

    val fps: Float
        get() = currentFps
}
