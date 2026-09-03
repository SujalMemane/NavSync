package com.example.navsync.sensor

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

class SensorManagerRepository(private val context: Context) : SensorEventListener, LocationListener {

    companion object {
        private const val TAG = "SensorManagerRepo"
        private const val IMU_STALE_THRESHOLD_MS = 1000L
        private const val GNSS_STALE_THRESHOLD_MS = 5000L
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val fusedLocationClient: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(context.applicationContext)
    private var fusedLocationCallback: LocationCallback? = null
    private val sensorLogger = SensorLogger()

    private var sensorThread: HandlerThread? = null
    private var sensorHandler: Handler? = null

    // Sensors
    private val accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val magSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val gravSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val rotSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    // State flows
    private val _accelerometerData = MutableStateFlow<ImuData?>(null)
    val accelerometerData: StateFlow<ImuData?> = _accelerometerData.asStateFlow()

    private val _gyroscopeData = MutableStateFlow<ImuData?>(null)
    val gyroscopeData: StateFlow<ImuData?> = _gyroscopeData.asStateFlow()

    private val _magnetometerData = MutableStateFlow<ImuData?>(null)
    val magnetometerData: StateFlow<ImuData?> = _magnetometerData.asStateFlow()

    private val _gravityData = MutableStateFlow<ImuData?>(null)
    val gravityData: StateFlow<ImuData?> = _gravityData.asStateFlow()

    private val _rotationVectorData = MutableStateFlow<RotationVectorData?>(null)
    val rotationVectorData: StateFlow<RotationVectorData?> = _rotationVectorData.asStateFlow()

    private val _compassHeading = MutableStateFlow(-1f)
    val compassHeading: StateFlow<Float> = _compassHeading.asStateFlow()

    private val _gnssData = MutableStateFlow<GnssData?>(null)
    val gnssData: StateFlow<GnssData?> = _gnssData.asStateFlow()

    private val _availability = MutableStateFlow(SensorAvailability())
    val availability: StateFlow<SensorAvailability> = _availability.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordingPath = MutableStateFlow("")
    val recordingPath: StateFlow<String> = _recordingPath.asStateFlow()

    // Tracking metrics for frequencies & health
    private class StreamTracker(val isSupported: Boolean) {
        var sampleCount: Long = 0L
        var windowSampleCount: Long = 0L
        var windowStartTimeMs: Long = System.currentTimeMillis()
        var measuredFrequencyHz: Double = 0.0
        var lastTimestampNanos: Long = 0L
        var lastSystemTimeMs: Long = 0L
        var accuracy: SensorAccuracyState = SensorAccuracyState.UNKNOWN

        fun update(eventNanos: Long, accuracyInt: Int = -1) {
            val nowMs = System.currentTimeMillis()
            sampleCount++
            windowSampleCount++
            lastTimestampNanos = eventNanos
            lastSystemTimeMs = nowMs
            if (accuracyInt != -1) {
                accuracy = SensorAccuracyState.fromInt(accuracyInt)
            }

            val elapsedMs = nowMs - windowStartTimeMs
            if (elapsedMs >= 1000L) {
                measuredFrequencyHz = (windowSampleCount * 1000.0) / elapsedMs
                windowSampleCount = 0L
                windowStartTimeMs = nowMs
            }
        }

        fun checkStale(staleThresholdMs: Long): Boolean {
            if (!isSupported || lastSystemTimeMs == 0L) return false
            return (System.currentTimeMillis() - lastSystemTimeMs) > staleThresholdMs
        }

        fun toStreamHealth(staleThresholdMs: Long): StreamHealth {
            return StreamHealth(
                isAvailable = isSupported,
                measuredFrequencyHz = measuredFrequencyHz,
                sampleCount = sampleCount,
                lastTimestampNanos = lastTimestampNanos,
                lastUpdateTimeMs = lastSystemTimeMs,
                isStale = checkStale(staleThresholdMs),
                accuracy = accuracy
            )
        }
    }

    private val trackers = ConcurrentHashMap<Int, StreamTracker>().apply {
        put(Sensor.TYPE_ACCELEROMETER, StreamTracker(accelSensor != null))
        put(Sensor.TYPE_GYROSCOPE, StreamTracker(gyroSensor != null))
        put(Sensor.TYPE_MAGNETIC_FIELD, StreamTracker(magSensor != null))
        put(Sensor.TYPE_GRAVITY, StreamTracker(gravSensor != null))
        put(Sensor.TYPE_ROTATION_VECTOR, StreamTracker(rotSensor != null))
    }
    private val gnssTracker = StreamTracker(true)

    // Satellite info tracked via GnssStatus
    private var gnssSatTotal = 0
    private var gnssSatUsedInFix = 0
    private var gnssStatusCallback: GnssStatus.Callback? = null

    private val repoScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var logcatSummaryJob: Job? = null
    private var healthCheckJob: Job? = null

    fun startSensors() {
        if (sensorThread == null) {
            sensorThread = HandlerThread("SensorAcquisitionThread").apply {
                start()
                sensorHandler = Handler(looper)
            }
        }

        val handler = sensorHandler

        accelSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST, handler) }
        gyroSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST, handler) }
        magSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST, handler) }
        gravSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST, handler) }
        rotSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST, handler) }

        startGnss()
        startMonitoringLoops()
    }

    fun stopSensors() {
        sensorManager.unregisterListener(this)
        stopGnss()

        sensorThread?.quitSafely()
        sensorThread = null
        sensorHandler = null

        logcatSummaryJob?.cancel()
        healthCheckJob?.cancel()
    }

    @SuppressLint("MissingPermission")
    fun startGnss() {
        val hasFine = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED

        Log.d("NAVSYNC_GNSS", "finePermission=$hasFine coarsePermission=$hasCoarse")

        if (!hasFine && !hasCoarse) {
            Log.w("NAVSYNC_GNSS", "GNSS permissions not granted. Cannot start location listener.")
            _gnssData.value = GnssData(status = GnssStatusState.NO_PERMISSION)
            return
        }

        val gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val netEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

        Log.d("NAVSYNC_GNSS", "locationServicesEnabled=${gpsEnabled || netEnabled} gpsEnabled=$gpsEnabled netEnabled=$netEnabled")

        if (!gpsEnabled && !netEnabled) {
            Log.w("NAVSYNC_GNSS", "Location services (GPS/Network) disabled on device.")
            _gnssData.value = GnssData(status = GnssStatusState.LOCATION_DISABLED)
            return
        }

        if (_gnssData.value == null || _gnssData.value?.status == GnssStatusState.NO_PERMISSION || _gnssData.value?.status == GnssStatusState.LOCATION_DISABLED) {
            _gnssData.value = GnssData(status = GnssStatusState.WAITING_FIX)
        }

        Log.d("NAVSYNC_GNSS", "registeringLocationCallback (FusedLocationProviderClient)...")
        try {
            // 1. Initial position check via fused getLastLocation
            fusedLocationClient.lastLocation.addOnSuccessListener { lastLoc ->
                if (lastLoc != null) {
                    val ageMs = (android.os.SystemClock.elapsedRealtimeNanos() - lastLoc.elapsedRealtimeNanos) / 1_000_000L
                    Log.d("NAVSYNC_GNSS", "fusedLastLocationAvailable ageMs=$ageMs lat=${lastLoc.latitude} lon=${lastLoc.longitude}")
                    if (ageMs < 60_000L) { // Only use if younger than 60 seconds
                        onLocationChanged(lastLoc)
                    }
                }
            }

            // 2. High Accuracy Fused Location Request (Continuous 1Hz Updates)
            val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
                .setMinUpdateIntervalMillis(500L)
                .setWaitForAccurateLocation(true)
                .build()

            fusedLocationCallback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    for (location in result.locations) {
                        val isMock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            location.isMock
                        } else {
                            location.isFromMockProvider
                        }

                        if (isMock) {
                            Log.w("NAVSYNC_GNSS", "WARNING: MOCK LOCATION DETECTED provider=${location.provider}")
                        } else {
                            Log.d("NAVSYNC_GNSS", "mockLocation=false")
                        }

                        val nanos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                            location.elapsedRealtimeNanos
                        } else System.nanoTime()

                        Log.d(
                            "NAVSYNC_GNSS_RAW",
                            "provider=${location.provider} lat=${location.latitude} lon=${location.longitude} accuracy=${location.accuracy} altitude=${location.altitude} speed=${location.speed} bearing=${location.bearing} time=${location.time} elapsedRealtimeNanos=$nanos isMock=$isMock"
                        )

                        onLocationChanged(location)
                    }
                }
            }

            fusedLocationClient.requestLocationUpdates(locationRequest, fusedLocationCallback!!, Handler(context.mainLooper).looper)
            Log.d("NAVSYNC_GNSS", "locationCallbackRegistered=true fusedClientInitialized=true")

            // 3. Register GnssStatusCallback for satellite counts
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                gnssStatusCallback = object : GnssStatus.Callback() {
                    override fun onSatelliteStatusChanged(status: GnssStatus) {
                        var total = 0
                        var used = 0
                        for (i in 0 until status.satelliteCount) {
                            total++
                            if (status.usedInFix(i)) {
                                used++
                            }
                        }
                        gnssSatTotal = total
                        gnssSatUsedInFix = used
                    }
                }
                gnssStatusCallback?.let {
                    locationManager.registerGnssStatusCallback(ContextCompat.getMainExecutor(context), it)
                    Log.d("NAVSYNC_GNSS", "gnssStatusCallbackRegistered=true")
                }
            }
        } catch (e: Exception) {
            Log.e("NAVSYNC_GNSS", "Error registering Fused GNSS updates: ${e.message}", e)
        }
    }

    fun stopGnss() {
        try {
            fusedLocationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
            fusedLocationCallback = null
            locationManager.removeUpdates(this)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                gnssStatusCallback?.let { locationManager.unregisterGnssStatusCallback(it) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping GNSS updates: ${e.message}")
        }
    }

    fun startRecording(): Boolean {
        val success = sensorLogger.startRecording(context)
        _isRecording.value = sensorLogger.isCurrentlyRecording()
        _recordingPath.value = sensorLogger.getRecordingPath()
        return success
    }

    fun stopRecording(): String? {
        val path = sensorLogger.stopRecording()
        _isRecording.value = sensorLogger.isCurrentlyRecording()
        _recordingPath.value = ""
        return path
    }

    private fun startMonitoringLoops() {
        logcatSummaryJob?.cancel()
        logcatSummaryJob = repoScope.launch {
            while (isActive) {
                delay(2000L)
                updateAvailabilityState()
                sensorLogger.logStatusSummary(
                    acc = trackers[Sensor.TYPE_ACCELEROMETER]!!.toStreamHealth(IMU_STALE_THRESHOLD_MS), latestAcc = _accelerometerData.value,
                    gyro = trackers[Sensor.TYPE_GYROSCOPE]!!.toStreamHealth(IMU_STALE_THRESHOLD_MS), latestGyro = _gyroscopeData.value,
                    mag = trackers[Sensor.TYPE_MAGNETIC_FIELD]!!.toStreamHealth(IMU_STALE_THRESHOLD_MS), latestMag = _magnetometerData.value,
                    grav = trackers[Sensor.TYPE_GRAVITY]!!.toStreamHealth(IMU_STALE_THRESHOLD_MS), latestGrav = _gravityData.value,
                    rot = trackers[Sensor.TYPE_ROTATION_VECTOR]!!.toStreamHealth(IMU_STALE_THRESHOLD_MS), latestRot = _rotationVectorData.value,
                    gnss = gnssTracker.toStreamHealth(GNSS_STALE_THRESHOLD_MS), latestGnss = _gnssData.value
                )
            }
        }

        healthCheckJob?.cancel()
        healthCheckJob = repoScope.launch {
            while (isActive) {
                delay(500L)
                updateAvailabilityState()
            }
        }
    }

    private fun updateAvailabilityState() {
        _availability.value = SensorAvailability(
            accelerometer = trackers[Sensor.TYPE_ACCELEROMETER]!!.toStreamHealth(IMU_STALE_THRESHOLD_MS),
            gyroscope = trackers[Sensor.TYPE_GYROSCOPE]!!.toStreamHealth(IMU_STALE_THRESHOLD_MS),
            magnetometer = trackers[Sensor.TYPE_MAGNETIC_FIELD]!!.toStreamHealth(IMU_STALE_THRESHOLD_MS),
            gravity = trackers[Sensor.TYPE_GRAVITY]!!.toStreamHealth(IMU_STALE_THRESHOLD_MS),
            rotationVector = trackers[Sensor.TYPE_ROTATION_VECTOR]!!.toStreamHealth(IMU_STALE_THRESHOLD_MS),
            gnss = gnssTracker.toStreamHealth(GNSS_STALE_THRESHOLD_MS)
        )
    }

    // --- SensorEventListener Implementation ---

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        val nowWallClock = System.currentTimeMillis()
        val timestampNanos = event.timestamp
        val tracker = trackers[event.sensor.type] ?: return

        tracker.update(timestampNanos, event.accuracy)

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val data = ImuData(
                    sensorType = event.sensor.type,
                    timestampNanos = timestampNanos,
                    wallClockMillis = nowWallClock,
                    x = event.values[0],
                    y = event.values[1],
                    z = event.values[2],
                    accuracy = event.accuracy
                )
                _accelerometerData.value = data
                sensorLogger.recordImu(event.sensor.type, data)
            }
            Sensor.TYPE_GYROSCOPE -> {
                val data = ImuData(
                    sensorType = event.sensor.type,
                    timestampNanos = timestampNanos,
                    wallClockMillis = nowWallClock,
                    x = event.values[0],
                    y = event.values[1],
                    z = event.values[2],
                    accuracy = event.accuracy
                )
                _gyroscopeData.value = data
                sensorLogger.recordImu(event.sensor.type, data)
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                val data = ImuData(
                    sensorType = event.sensor.type,
                    timestampNanos = timestampNanos,
                    wallClockMillis = nowWallClock,
                    x = event.values[0],
                    y = event.values[1],
                    z = event.values[2],
                    accuracy = event.accuracy
                )
                _magnetometerData.value = data
                sensorLogger.recordImu(event.sensor.type, data)
            }
            Sensor.TYPE_GRAVITY -> {
                val data = ImuData(
                    sensorType = event.sensor.type,
                    timestampNanos = timestampNanos,
                    wallClockMillis = nowWallClock,
                    x = event.values[0],
                    y = event.values[1],
                    z = event.values[2],
                    accuracy = event.accuracy
                )
                _gravityData.value = data
                sensorLogger.recordImu(event.sensor.type, data)
            }
            Sensor.TYPE_ROTATION_VECTOR -> {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                val w = if (event.values.size > 3) event.values[3] else 0f
                val headingAcc = if (event.values.size > 4) event.values[4] else -1f

                try {
                    val rotationMatrix = FloatArray(9)
                    val orientationValues = FloatArray(3)
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    SensorManager.getOrientation(rotationMatrix, orientationValues)
                    var azimuthDeg = Math.toDegrees(orientationValues[0].toDouble()).toFloat()
                    if (azimuthDeg < 0) azimuthDeg += 360f
                    _compassHeading.value = azimuthDeg
                } catch (e: Exception) {
                    // Ignore math calculation error
                }

                val data = RotationVectorData(
                    timestampNanos = timestampNanos,
                    wallClockMillis = nowWallClock,
                    x = x,
                    y = y,
                    z = z,
                    w = w,
                    headingAccuracy = headingAcc,
                    accuracy = event.accuracy
                )
                _rotationVectorData.value = data
                sensorLogger.recordRotationVector(data)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (sensor != null) {
            trackers[sensor.type]?.accuracy = SensorAccuracyState.fromInt(accuracy)
        }
    }

    // --- LocationListener Implementation ---

    override fun onLocationChanged(location: Location) {
        val nowWallClock = System.currentTimeMillis()
        val nanos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            location.elapsedRealtimeNanos
        } else {
            System.nanoTime()
        }

        gnssTracker.update(nanos)
        Log.d("NAVSYNC_GNSS", "LOCATION_RECEIVED lat=${location.latitude} lon=${location.longitude} accuracy=${location.accuracy} speed=${location.speed} bearing=${location.bearing} timestamp=${location.time}")

        val vAcc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasVerticalAccuracy()) location.verticalAccuracyMeters else 0f
        val sAcc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasSpeedAccuracy()) location.speedAccuracyMetersPerSecond else 0f
        val bAcc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasBearingAccuracy()) location.bearingAccuracyDegrees else 0f

        val gnssStatus = when {
            gnssTracker.checkStale(GNSS_STALE_THRESHOLD_MS) -> GnssStatusState.GNSS_LOST
            gnssSatUsedInFix > 0 || location.accuracy <= 20f -> GnssStatusState.GNSS_ACTIVE
            else -> GnssStatusState.GNSS_DEGRADED
        }

        val data = GnssData(
            timestampNanos = nanos,
            wallClockMillis = nowWallClock,
            latitude = location.latitude,
            longitude = location.longitude,
            altitude = location.altitude,
            speed = location.speed,
            bearing = location.bearing,
            horizontalAccuracy = location.accuracy,
            verticalAccuracy = vAcc,
            speedAccuracy = sAcc,
            bearingAccuracy = bAcc,
            satelliteCount = gnssSatTotal,
            usedInFix = gnssSatUsedInFix,
            provider = location.provider ?: "unknown",
            status = gnssStatus
        )

        _gnssData.value = data
        sensorLogger.recordGnss(data)
    }

    @Deprecated("Deprecated in API 29")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    override fun onProviderEnabled(provider: String) {}

    override fun onProviderDisabled(provider: String) {}
}
