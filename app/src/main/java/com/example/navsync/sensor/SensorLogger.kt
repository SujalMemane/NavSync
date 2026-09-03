package com.example.navsync.sensor

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SensorLogger {

    companion object {
        private const val TAG = "NAVSYNC_SENSOR"
    }

    private var isRecording = false
    private var recordingDir: File? = null

    private var accWriter: BufferedWriter? = null
    private var gyroWriter: BufferedWriter? = null
    private var magWriter: BufferedWriter? = null
    private var gravWriter: BufferedWriter? = null
    private var rotWriter: BufferedWriter? = null
    private var gnssWriter: BufferedWriter? = null

    private val recordScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val logChannel = Channel<Pair<BufferedWriter, String>>(capacity = 10000)

    init {
        recordScope.launch {
            for ((writer, line) in logChannel) {
                try {
                    writer.write(line)
                    writer.newLine()
                } catch (e: Exception) {
                    Log.e(TAG, "Error writing sensor line to CSV: ${e.message}")
                }
            }
        }
    }

    fun isCurrentlyRecording(): Boolean = isRecording

    fun getRecordingPath(): String = recordingDir?.absolutePath ?: ""

    @Synchronized
    fun startRecording(context: Context): Boolean {
        if (isRecording) return true
        try {
            val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            val folderName = "rec_${dateFormat.format(Date())}"
            val baseDir = context.getExternalFilesDir("sensor_recordings") ?: context.filesDir
            val dir = File(baseDir, folderName)
            if (!dir.exists()) {
                dir.mkdirs()
            }
            recordingDir = dir

            accWriter = BufferedWriter(FileWriter(File(dir, "accelerometer.csv")))
            accWriter?.write("timestamp_nanos,wall_clock_ms,x,y,z,accuracy\n")

            gyroWriter = BufferedWriter(FileWriter(File(dir, "gyroscope.csv")))
            gyroWriter?.write("timestamp_nanos,wall_clock_ms,x,y,z,accuracy\n")

            magWriter = BufferedWriter(FileWriter(File(dir, "magnetometer.csv")))
            magWriter?.write("timestamp_nanos,wall_clock_ms,x,y,z,accuracy\n")

            gravWriter = BufferedWriter(FileWriter(File(dir, "gravity.csv")))
            gravWriter?.write("timestamp_nanos,wall_clock_ms,x,y,z,accuracy\n")

            rotWriter = BufferedWriter(FileWriter(File(dir, "rotation_vector.csv")))
            rotWriter?.write("timestamp_nanos,wall_clock_ms,x,y,z,w,accuracy\n")

            gnssWriter = BufferedWriter(FileWriter(File(dir, "gnss.csv")))
            gnssWriter?.write("timestamp_nanos,wall_clock_ms,latitude,longitude,altitude,speed,bearing,accuracy,vertical_accuracy,speed_accuracy,bearing_accuracy,satellite_count,satellites_used,provider\n")

            isRecording = true
            Log.i(TAG, "Started sensor CSV recording in ${dir.absolutePath}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording: ${e.message}", e)
            stopRecording()
            return false
        }
    }

    @Synchronized
    fun stopRecording(): String? {
        if (!isRecording) return null
        val path = recordingDir?.absolutePath
        isRecording = false

        recordScope.launch {
            try {
                accWriter?.flush()
                accWriter?.close()
                gyroWriter?.flush()
                gyroWriter?.close()
                magWriter?.flush()
                magWriter?.close()
                gravWriter?.flush()
                gravWriter?.close()
                rotWriter?.flush()
                rotWriter?.close()
                gnssWriter?.flush()
                gnssWriter?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing CSV writers: ${e.message}")
            } finally {
                accWriter = null
                gyroWriter = null
                magWriter = null
                gravWriter = null
                rotWriter = null
                gnssWriter = null
            }
        }
        Log.i(TAG, "Stopped sensor CSV recording. Data saved in $path")
        return path
    }

    fun recordImu(sensorType: Int, imuData: ImuData) {
        if (!isRecording) return
        val writer = when (sensorType) {
            android.hardware.Sensor.TYPE_ACCELEROMETER -> accWriter
            android.hardware.Sensor.TYPE_GYROSCOPE -> gyroWriter
            android.hardware.Sensor.TYPE_MAGNETIC_FIELD -> magWriter
            android.hardware.Sensor.TYPE_GRAVITY -> gravWriter
            else -> null
        } ?: return

        val line = "${imuData.timestampNanos},${imuData.wallClockMillis},${imuData.x},${imuData.y},${imuData.z},${imuData.accuracy}"
        logChannel.trySend(Pair(writer, line))
    }

    fun recordRotationVector(rotData: RotationVectorData) {
        if (!isRecording) return
        val writer = rotWriter ?: return
        val line = "${rotData.timestampNanos},${rotData.wallClockMillis},${rotData.x},${rotData.y},${rotData.z},${rotData.w},${rotData.accuracy}"
        logChannel.trySend(Pair(writer, line))
    }

    fun recordGnss(gnssData: GnssData) {
        if (!isRecording) return
        val writer = gnssWriter ?: return
        val line = "${gnssData.timestampNanos},${gnssData.wallClockMillis},${gnssData.latitude},${gnssData.longitude},${gnssData.altitude},${gnssData.speed},${gnssData.bearing},${gnssData.horizontalAccuracy},${gnssData.verticalAccuracy},${gnssData.speedAccuracy},${gnssData.bearingAccuracy},${gnssData.satelliteCount},${gnssData.usedInFix},${gnssData.provider}"
        logChannel.trySend(Pair(writer, line))
    }

    fun logStatusSummary(
        acc: StreamHealth, latestAcc: ImuData?,
        gyro: StreamHealth, latestGyro: ImuData?,
        mag: StreamHealth, latestMag: ImuData?,
        grav: StreamHealth, latestGrav: ImuData?,
        rot: StreamHealth, latestRot: RotationVectorData?,
        gnss: StreamHealth, latestGnss: GnssData?
    ) {
        val sb = StringBuilder()
        sb.append("\n================ NAVSYNC SENSOR STATUS ================\n")

        if (acc.isAvailable) {
            sb.append(String.format(Locale.US, "ACC  | samples=%-5d | frequency=%5.1f Hz | x=%7.3f | y=%7.3f | z=%7.3f | accuracy=%s | stale=%b\n",
                acc.sampleCount, acc.measuredFrequencyHz, latestAcc?.x ?: 0f, latestAcc?.y ?: 0f, latestAcc?.z ?: 0f, acc.accuracy.name, acc.isStale))
        } else {
            sb.append("ACC  | UNAVAILABLE ON THIS DEVICE\n")
        }

        if (gyro.isAvailable) {
            sb.append(String.format(Locale.US, "GYRO | samples=%-5d | frequency=%5.1f Hz | x=%7.4f | y=%7.4f | z=%7.4f | accuracy=%s | stale=%b\n",
                gyro.sampleCount, gyro.measuredFrequencyHz, latestGyro?.x ?: 0f, latestGyro?.y ?: 0f, latestGyro?.z ?: 0f, gyro.accuracy.name, gyro.isStale))
        } else {
            sb.append("GYRO | UNAVAILABLE ON THIS DEVICE\n")
        }

        if (mag.isAvailable) {
            sb.append(String.format(Locale.US, "MAG  | samples=%-5d | frequency=%5.1f Hz | x=%6.1f | y=%6.1f | z=%6.1f | accuracy=%s | stale=%b\n",
                mag.sampleCount, mag.measuredFrequencyHz, latestMag?.x ?: 0f, latestMag?.y ?: 0f, latestMag?.z ?: 0f, mag.accuracy.name, mag.isStale))
        } else {
            sb.append("MAG  | UNAVAILABLE ON THIS DEVICE\n")
        }

        if (grav.isAvailable) {
            sb.append(String.format(Locale.US, "GRAV | samples=%-5d | frequency=%5.1f Hz | x=%7.3f | y=%7.3f | z=%7.3f | stale=%b\n",
                grav.sampleCount, grav.measuredFrequencyHz, latestGrav?.x ?: 0f, latestGrav?.y ?: 0f, latestGrav?.z ?: 0f, grav.isStale))
        } else {
            sb.append("GRAV | UNAVAILABLE ON THIS DEVICE\n")
        }

        if (rot.isAvailable) {
            sb.append(String.format(Locale.US, "ROT  | samples=%-5d | frequency=%5.1f Hz | x=%6.3f | y=%6.3f | z=%6.3f | w=%6.3f | stale=%b\n",
                rot.sampleCount, rot.measuredFrequencyHz, latestRot?.x ?: 0f, latestRot?.y ?: 0f, latestRot?.z ?: 0f, latestRot?.w ?: 0f, rot.isStale))
        } else {
            sb.append("ROT  | UNAVAILABLE ON THIS DEVICE\n")
        }

        if (gnss.isAvailable) {
            sb.append(String.format(Locale.US, "GNSS | updates=%-5d | frequency=%4.2f Hz | lat=%10.6f | lon=%10.6f | speed=%5.2fm/s | acc=%4.1fm | sats=%d(used %d) | status=%s\n",
                gnss.sampleCount, gnss.measuredFrequencyHz, latestGnss?.latitude ?: 0.0, latestGnss?.longitude ?: 0.0, latestGnss?.speed ?: 0f, latestGnss?.horizontalAccuracy ?: 0f, latestGnss?.satelliteCount ?: 0, latestGnss?.usedInFix ?: 0, latestGnss?.status?.name ?: "UNKNOWN"))
        } else {
            sb.append("GNSS | UNAVAILABLE / NO PERMISSION\n")
        }

        if (isRecording) {
            sb.append("RECORDING | ACTIVE | Directory: ${recordingDir?.name}\n")
        }
        sb.append("=========================================================")

        Log.d(TAG, sb.toString())
    }
}
