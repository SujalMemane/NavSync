
package com.example.navsync.services

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque
import kotlin.math.sqrt

/**
 * Result of ML Dead-Reckoning displacement inference.
 * @param dx Lateral displacement (m)
 * @param dy Longitudinal / forward displacement (m)
 * @param speedMps Estimated vehicle forward speed in m/s
 * @param isStationary True if the vehicle is judged stationary
 */
data class DisplacementResult(
    val dx: Float,
    val dy: Float,
    val speedMps: Float,
    val isStationary: Boolean,
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * Raw IMU 6-channel sensor sample (accelerometer m/s² + gyroscope rad/s).
 */
data class RawImuSample(
    val ax: Float,
    val ay: Float,
    val az: Float,
    val gx: Float,
    val gy: Float,
    val gz: Float,
    val timestampNanos: Long
)

/**
 * IO-VNBD Machine Learning Dead-Reckoning Inference Engine.
 *
 * Implements 1D-CNN + LSTM sequential inference on real-time smartphone IMU data:
 * - Buffer: Rolling 10 samples (1.0s @ 10Hz)
 * - Features (4): [acc_mag, acc_lin_mag, gyro_z, gyro_mag]
 * - StandardScaler: Fitted on the IO-VNBD benchmark dataset
 * - Model: DeadReckoningNet exported to ONNX (306 KB)
 * - Output: (dx, dy) vehicle displacement in meters
 */
class DeadReckoningMlEngine(private val context: Context) {

    companion object {
        private const val TAG = "NAVSYNC_ML_DR"
        private const val MODEL_ASSET_PATH = "models/dead_reckoning_model.onnx"
        private const val WINDOW_SIZE = 10 // 10 steps @ 10Hz = 1.0s
        private const val NUM_FEATURES = 4

        // Exact StandardScaler constants from IO-VNBD training dataset
        private val SCALER_MEAN = floatArrayOf(9.96122191f, 0.154571751f, 0.00268715505f, 0.139419292f)
        private val SCALER_SCALE = floatArrayOf(0.53868888f, 0.53868888f, 0.05326527f, 0.11922237f)

        // Threshold below which vehicle is considered stationary (engine idle / hand tremor tolerance)
        private const val STATIONARY_ACC_VARIANCE_THRESHOLD = 0.28f
        private const val STATIONARY_GYRO_MAG_THRESHOLD = 0.15f
    }

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var isInitialized = false

    // Sliding ring buffer for 10Hz IMU samples
    private val sampleBuffer = ConcurrentLinkedDeque<RawImuSample>()
    private var lastSampleNanos: Long = 0L

    // Low-pass exponential moving average (EMA) states to eliminate noise & jitter
    private var smoothedDx: Float = 0f
    private var smoothedDy: Float = 0f
    private var smoothedSpeed: Float = 0f

    init {
        initializeModel()
    }

    private fun initializeModel() {
        try {
            ortEnv = OrtEnvironment.getEnvironment()
            val modelBytes = context.assets.open(MODEL_ASSET_PATH).use { it.readBytes() }
            val sessionOptions = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
            }
            ortSession = ortEnv?.createSession(modelBytes, sessionOptions)
            isInitialized = true
            Log.d(TAG, "IO-VNBD ONNX model loaded successfully from assets. Input count=${ortSession?.numInputs}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load ONNX model from assets: ${e.message}", e)
            isInitialized = false
        }
    }

    /**
     * Add high-frequency sensor readings, downsampling to ~10Hz (1 sample per 90-110ms).
     */
    fun addSensorData(
        ax: Float, ay: Float, az: Float,
        gx: Float, gy: Float, gz: Float,
        timestampNanos: Long
    ) {
        val deltaNanos = timestampNanos - lastSampleNanos
        // Target 10Hz sampling (>= 90ms between accepted samples)
        if (deltaNanos < 90_000_000L && lastSampleNanos != 0L) {
            return
        }

        lastSampleNanos = timestampNanos
        sampleBuffer.addLast(RawImuSample(ax, ay, az, gx, gy, gz, timestampNanos))

        // Keep buffer trimmed to required window size
        while (sampleBuffer.size > WINDOW_SIZE) {
            sampleBuffer.pollFirst()
        }
    }

    /**
     * Run ONNX inference on the current 10-step IMU window.
     * Returns predicted displacement (dx, dy) and estimated speed.
     */
    fun predictDisplacement(): DisplacementResult {
        val samples = sampleBuffer.toList()

        // If buffer isn't full yet, pad with the most recent sample
        val effectiveSamples = if (samples.isEmpty()) {
            List(WINDOW_SIZE) { RawImuSample(0f, 0f, 9.81f, 0f, 0f, 0f, System.nanoTime()) }
        } else if (samples.size < WINDOW_SIZE) {
            val first = samples.first()
            val padCount = WINDOW_SIZE - samples.size
            List(padCount) { first } + samples
        } else {
            samples.takeLast(WINDOW_SIZE)
        }

        // 1. Stationary Detection (check if dynamic acceleration and gyro are minimal)
        var accMagSum = 0f
        var gyroMagSum = 0f
        val accMags = FloatArray(WINDOW_SIZE)
        for (i in effectiveSamples.indices) {
            val s = effectiveSamples[i]
            val mag = sqrt(s.ax * s.ax + s.ay * s.ay + s.az * s.az)
            val gyroMag = sqrt(s.gx * s.gx + s.gy * s.gy + s.gz * s.gz)
            accMags[i] = mag
            accMagSum += mag
            gyroMagSum += gyroMag
        }
        val accMean = accMagSum / WINDOW_SIZE
        var accVar = 0f
        for (mag in accMags) {
            accVar += (mag - accMean) * (mag - accMean)
        }
        accVar /= WINDOW_SIZE
        val avgGyroMag = gyroMagSum / WINDOW_SIZE
        val dynamicAcc = kotlin.math.abs(accMean - 9.80665f)

        val isStationary = (accVar < STATIONARY_ACC_VARIANCE_THRESHOLD && avgGyroMag < STATIONARY_GYRO_MAG_THRESHOLD && dynamicAcc < 0.38f)

        if (isStationary) {
            smoothedDx = 0f
            smoothedDy = 0f
            smoothedSpeed = 0f

            Log.d(TAG, String.format(Locale.US,
                "ML_INPUT: window=%d samples | stationary=true [accVar=%.3f, gyroMag=%.3f, dynAcc=%.3f]",
                effectiveSamples.size, accVar, avgGyroMag, dynamicAcc
            ))
            Log.d(TAG, "ML_ESTIMATE: predicted dx=0.000 m, dy=0.000 m | speed=0.00 m/s (0.0 km/h) | stationary=true")

            return DisplacementResult(
                dx = 0f,
                dy = 0f,
                speedMps = 0f,
                isStationary = true
            )
        }

        // 2. Feature Extraction & Standardization
        // Shape: (10, 4) flattened to floatBuffer of length 40
        val inputFloats = FloatArray(WINDOW_SIZE * NUM_FEATURES)
        var idx = 0
        for (s in effectiveSamples) {
            val accMag = sqrt(s.ax * s.ax + s.ay * s.ay + s.az * s.az)
            val accLinMag = accMag - 9.80665f
            val gz = s.gz
            val gyroMag = sqrt(s.gx * s.gx + s.gy * s.gy + s.gz * s.gz)

            // StandardScaler: (x - mean) / scale
            inputFloats[idx++] = (accMag - SCALER_MEAN[0]) / SCALER_SCALE[0]
            inputFloats[idx++] = (accLinMag - SCALER_MEAN[1]) / SCALER_SCALE[1]
            inputFloats[idx++] = (gz - SCALER_MEAN[2]) / SCALER_SCALE[2]
            inputFloats[idx++] = (gyroMag - SCALER_MEAN[3]) / SCALER_SCALE[3]
        }

        val latest = effectiveSamples.last()
        val latestAccMag = sqrt(latest.ax * latest.ax + latest.ay * latest.ay + latest.az * latest.az)
        val latestAccLinMag = latestAccMag - 9.80665f
        val latestGyroMag = sqrt(latest.gx * latest.gx + latest.gy * latest.gy + latest.gz * latest.gz)

        Log.d(TAG, String.format(Locale.US,
            "ML_INPUT: window=%d | rawSample=[ax=%.2f, ay=%.2f, az=%.2f, gx=%.3f, gy=%.3f, gz=%.3f] | features=[accMag=%.2f, linAcc=%.2f, gz=%.3f, gyroMag=%.3f] | normFirst=[%.2f, %.2f, %.2f, %.2f]",
            effectiveSamples.size, latest.ax, latest.ay, latest.az, latest.gx, latest.gy, latest.gz,
            latestAccMag, latestAccLinMag, latest.gz, latestGyroMag,
            inputFloats[0], inputFloats[1], inputFloats[2], inputFloats[3]
        ))

        // 3. ONNX Model Inference
        var dx = 0f
        var dy = 0f

        if (isInitialized && ortEnv != null && ortSession != null) {
            try {
                val floatBuffer = FloatBuffer.wrap(inputFloats)
                val shape = longArrayOf(1, WINDOW_SIZE.toLong(), NUM_FEATURES.toLong())
                val tensor = OnnxTensor.createTensor(ortEnv, floatBuffer, shape)

                val output = ortSession?.run(Collections.singletonMap("input", tensor))
                val outputTensor = output?.get(0) as? OnnxTensor
                if (outputTensor != null) {
                    val rawVal = outputTensor.value
                    if (rawVal is Array<*>) {
                        val firstRow = rawVal[0]
                        if (firstRow is FloatArray) {
                            dx = firstRow[0]
                            dy = firstRow[1]
                        }
                    }
                }
                output?.close()
                tensor.close()
            } catch (e: Exception) {
                Log.w(TAG, "ONNX inference error, using kinematic fallback: ${e.message}")
                dy = (accMean - 9.81f).coerceAtLeast(0f) * 0.5f
            }
        } else {
            dy = (accMean - 9.81f).coerceAtLeast(0f) * 0.5f
        }

        // Noise floor deadband: ignore micro-movements under 0.35m or without true dynamic accel
        val rawMagnitude = sqrt(dx * dx + dy * dy)
        if (rawMagnitude < 0.35f || dynamicAcc < 0.20f) {
            dx = 0f
            dy = 0f
        }

        // Clamp plausible forward displacement for 1 second in vehicle (0 to 35 m/s ~= 125 km/h)
        val clampedDy = dy.coerceIn(0f, 35f)
        // Heavily dampen lateral drift noise to keep vehicle stable in lane
        val clampedDx = (dx * 0.30f).coerceIn(-3f, 3f)

        // Exponential Moving Average (EMA) low-pass filter to eliminate sensor jitter
        smoothedDx = smoothedDx * 0.70f + clampedDx * 0.30f
        smoothedDy = smoothedDy * 0.70f + clampedDy * 0.30f

        val stepDistance = sqrt(smoothedDx * smoothedDx + smoothedDy * smoothedDy)
        val speedMps = stepDistance
        val isStepStationary = (stepDistance < 0.25f || speedMps < 0.70f)
        if (isStepStationary) {
            smoothedSpeed = 0f
        } else {
            smoothedSpeed = smoothedSpeed * 0.70f + speedMps * 0.30f
        }
        val displayKmh = smoothedSpeed * 3.6f

        Log.d(TAG, String.format(Locale.US,
            "ML_ESTIMATE: raw=(dx=%.3f, dy=%.3f) -> smoothed=(dx=%.3f, dy=%.3f) m | speed=%.2f m/s (%.1f km/h) | stationary=%b",
            dx, dy, smoothedDx, smoothedDy, smoothedSpeed, displayKmh, isStepStationary
        ))

        return DisplacementResult(
            dx = smoothedDx,
            dy = smoothedDy,
            speedMps = smoothedSpeed,
            isStationary = isStepStationary
        )
    }

    fun reset() {
        smoothedDx = 0f
        smoothedDy = 0f
        smoothedSpeed = 0f
        sampleBuffer.clear()
    }

    fun release() {
        try {
            ortSession?.close()
            ortEnv?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing ONNX session: ${e.message}")
        }
    }
}
