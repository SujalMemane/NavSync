package com.example.navsync.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.navsync.sensor.GnssData
import com.example.navsync.sensor.GnssStatusState
import com.example.navsync.sensor.ImuData
import com.example.navsync.sensor.RotationVectorData
import com.example.navsync.sensor.SensorAvailability
import com.example.navsync.sensor.StreamHealth
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SensorDebugScreen(
    viewModel: SensorViewModel,
    hasLocationPermission: Boolean,
    onRequestPermission: () -> Unit
) {
    val accel by viewModel.accelerometerData.collectAsState()
    val gyro by viewModel.gyroscopeData.collectAsState()
    val mag by viewModel.magnetometerData.collectAsState()
    val grav by viewModel.gravityData.collectAsState()
    val rot by viewModel.rotationVectorData.collectAsState()
    val gnss by viewModel.gnssData.collectAsState()
    val availability by viewModel.availability.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val recordingPath by viewModel.recordingPath.collectAsState()

    val darkBg = Color(0xFF0F172A)
    val cardBg = Color(0xFF1E293B)
    val textPrimary = Color(0xFFF8FAFC)
    val textSecondary = Color(0xFF94A3B8)
    val accentBlue = Color(0xFF38BDF8)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "NavSync Sensor Telemetry",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = textPrimary
                            )
                        )
                        Text(
                            text = "Raw Acquisition & Data Pipeline",
                            style = MaterialTheme.typography.bodySmall.copy(color = textSecondary)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = darkBg)
            )
        },
        containerColor = darkBg
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Permission Banner if location permission missing
            if (!hasLocationPermission) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF7C2D12)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Location Permission Required", color = Color.White, fontWeight = FontWeight.Bold)
                            Text("GNSS collection requires FINE/COARSE location permission.", color = Color(0xFFFDBA74), fontSize = 12.sp)
                        }
                        Button(
                            onClick = onRequestPermission,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEA580C))
                        ) {
                            Text("Grant", color = Color.White)
                        }
                    }
                }
            }

            // Recording Controls
            Card(
                colors = CardDefaults.cardColors(containerColor = cardBg),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, if (isRecording) Color(0xFFEF4444) else Color(0xFF3B82F6), RoundedCornerShape(12.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = if (isRecording) "RECORDING IN PROGRESS" else "RAW DATA LOGGING",
                                fontWeight = FontWeight.Bold,
                                color = if (isRecording) Color(0xFFEF4444) else accentBlue,
                                fontSize = 14.sp
                            )
                            Text(
                                text = if (isRecording) "Saving raw IMU + GNSS CSV streams" else "Ready to record dataset for ML pipeline",
                                color = textSecondary,
                                fontSize = 12.sp
                            )
                        }

                        Button(
                            onClick = { viewModel.toggleRecording() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isRecording) Color(0xFFDC2626) else Color(0xFF2563EB)
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(if (isRecording) "STOP RECORDING" else "START RECORDING", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }

                    if (isRecording && recordingPath.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Output: $recordingPath",
                            color = Color(0xFF4ADE80),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            // SENSOR STATUS & AVAILABILITY
            SectionHeader(title = "1. SENSOR AVAILABILITY & RATES")
            SensorAvailabilityCard(availability = availability, gnssData = gnss)

            // LIVE ACCELEROMETER
            SectionHeader(title = "2. LIVE ACCELEROMETER (m/s²)")
            ImuTelemetryCard(
                sensorName = "Accelerometer",
                health = availability.accelerometer,
                data = accel,
                unit = "m/s²"
            )

            // LIVE GYROSCOPE
            SectionHeader(title = "3. LIVE GYROSCOPE (rad/s)")
            ImuTelemetryCard(
                sensorName = "Gyroscope",
                health = availability.gyroscope,
                data = gyro,
                unit = "rad/s"
            )

            // LIVE MAGNETOMETER
            SectionHeader(title = "4. LIVE MAGNETOMETER (µT)")
            ImuTelemetryCard(
                sensorName = "Magnetometer",
                health = availability.magnetometer,
                data = mag,
                unit = "µT"
            )

            // LIVE GRAVITY & ROTATION VECTOR
            SectionHeader(title = "5. OPTIONAL SENSORS")
            OptionalSensorsCard(
                gravityHealth = availability.gravity,
                gravityData = grav,
                rotHealth = availability.rotationVector,
                rotData = rot
            )

            // GNSS TELEMETRY
            SectionHeader(title = "6. GNSS TELEMETRY")
            GnssTelemetryCard(health = availability.gnss, gnss = gnss)

            // DATA QUALITY SUMMARY
            SectionHeader(title = "7. DATA QUALITY & STREAM HEALTH")
            DataQualityCard(availability = availability)

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun SectionHeader(title: String) {
    Text(
        text = title,
        color = Color(0xFF94A3B8),
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
    )
}

@Composable
fun SensorAvailabilityCard(availability: SensorAvailability, gnssData: GnssData?) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AvailabilityRow(name = "Accelerometer", health = availability.accelerometer)
            AvailabilityRow(name = "Gyroscope", health = availability.gyroscope)
            AvailabilityRow(name = "Magnetometer", health = availability.magnetometer)
            AvailabilityRow(name = "Gravity", health = availability.gravity)
            AvailabilityRow(name = "Rotation Vector", health = availability.rotationVector)
            AvailabilityRow(name = "GNSS Fix", health = availability.gnss, extraStatus = gnssData?.status?.name)
        }
    }
}

@Composable
fun AvailabilityRow(name: String, health: StreamHealth, extraStatus: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BadgeChip(
                text = if (health.isAvailable) "AVAILABLE" else "UNAVAILABLE",
                bgColor = if (health.isAvailable) Color(0xFF15803D) else Color(0xFF991B1B)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (health.isAvailable) {
                Text(
                    text = String.format(Locale.US, "%.1f Hz", health.measuredFrequencyHz),
                    color = Color(0xFF38BDF8),
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(8.dp))
                if (health.isStale) {
                    BadgeChip(text = "STALE", bgColor = Color(0xFFB45309))
                } else {
                    BadgeChip(text = health.accuracy.name, bgColor = Color(0xFF334155))
                }
            } else {
                Text(text = "NOT AVAILABLE", color = Color(0xFF64748B), fontSize = 12.sp)
            }
            if (extraStatus != null) {
                Spacer(modifier = Modifier.width(6.dp))
                BadgeChip(text = extraStatus, bgColor = if (extraStatus == "GNSS_ACTIVE") Color(0xFF047857) else Color(0xFFB45309))
            }
        }
    }
}

@Composable
fun ImuTelemetryCard(sensorName: String, health: StreamHealth, data: ImuData?, unit: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        if (!health.isAvailable) {
            Box(modifier = Modifier.padding(16.dp)) {
                Text("$sensorName NOT AVAILABLE ON THIS DEVICE", color = Color(0xFF64748B), fontSize = 13.sp)
            }
            return@Card
        }

        Column(modifier = Modifier.padding(14.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Timestamp Nanos: ${data?.timestampNanos ?: 0L}", color = Color(0xFF94A3B8), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Text("Accuracy: ${health.accuracy.name}", color = Color(0xFF38BDF8), fontSize = 11.sp)
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                ValueColumn("X", data?.x ?: 0f, unit)
                ValueColumn("Y", data?.y ?: 0f, unit)
                ValueColumn("Z", data?.z ?: 0f, unit)
            }
        }
    }
}

@Composable
fun ValueColumn(label: String, value: Float, unit: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Color(0xFF94A3B8), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(
            text = String.format(Locale.US, "%+8.4f", value),
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        Text(unit, color = Color(0xFF64748B), fontSize = 10.sp)
    }
}

@Composable
fun OptionalSensorsCard(
    gravityHealth: StreamHealth, gravityData: ImuData?,
    rotHealth: StreamHealth, rotData: RotationVectorData?
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Gravity Sensor:", color = Color(0xFF38BDF8), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            if (gravityHealth.isAvailable && gravityData != null) {
                Text(
                    text = String.format(Locale.US, "X: %+7.3f  | Y: %+7.3f  | Z: %+7.3f m/s²", gravityData.x, gravityData.y, gravityData.z),
                    color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace
                )
            } else {
                Text("NOT AVAILABLE ON THIS DEVICE", color = Color(0xFF64748B), fontSize = 12.sp)
            }

            HorizontalDivider(color = Color(0xFF334155))

            Text("Rotation Vector Sensor:", color = Color(0xFF38BDF8), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            if (rotHealth.isAvailable && rotData != null) {
                Text(
                    text = String.format(Locale.US, "X: %+6.3f | Y: %+6.3f | Z: %+6.3f | W: %+6.3f", rotData.x, rotData.y, rotData.z, rotData.w),
                    color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace
                )
            } else {
                Text("NOT AVAILABLE ON THIS DEVICE", color = Color(0xFF64748B), fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun GnssTelemetryCard(health: StreamHealth, gnss: GnssData?) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Status: ", color = Color(0xFF94A3B8), fontSize = 12.sp)
                    BadgeChip(
                        text = gnss?.status?.name ?: "GNSS_LOST",
                        bgColor = when (gnss?.status) {
                            GnssStatusState.GNSS_ACTIVE -> Color(0xFF047857)
                            GnssStatusState.GNSS_DEGRADED -> Color(0xFFB45309)
                            else -> Color(0xFF991B1B)
                        }
                    )
                }
                Text("Provider: ${gnss?.provider ?: "N/A"}", color = Color(0xFF38BDF8), fontSize = 12.sp)
            }

            HorizontalDivider(color = Color(0xFF334155))

            GnssRow("Latitude", String.format(Locale.US, "%.6f°", gnss?.latitude ?: 0.0))
            GnssRow("Longitude", String.format(Locale.US, "%.6f°", gnss?.longitude ?: 0.0))
            GnssRow("Altitude", String.format(Locale.US, "%.2f m", gnss?.altitude ?: 0.0))
            GnssRow("Ground Speed", String.format(Locale.US, "%.2f m/s (%.1f km/h)", gnss?.speed ?: 0f, (gnss?.speed ?: 0f) * 3.6f))
            GnssRow("Bearing", String.format(Locale.US, "%.1f°", gnss?.bearing ?: 0f))
            GnssRow("Horiz. Accuracy", String.format(Locale.US, "%.2f m", gnss?.horizontalAccuracy ?: 0f))
            GnssRow("Satellites Total/Fix", "${gnss?.satelliteCount ?: 0} in view / ${gnss?.usedInFix ?: 0} used in fix")
            GnssRow("Elapsed Realtime Nanos", "${gnss?.timestampNanos ?: 0L}")
        }
    }
}

@Composable
fun GnssRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color(0xFF94A3B8), fontSize = 13.sp)
        Text(value, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun DataQualityCard(availability: SensorAvailability) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            QualityRow("ACC Sampling Rate", availability.accelerometer)
            QualityRow("GYRO Sampling Rate", availability.gyroscope)
            QualityRow("MAG Sampling Rate", availability.magnetometer)
            QualityRow("GRAV Sampling Rate", availability.gravity)
            QualityRow("ROT VECTOR Rate", availability.rotationVector)
            QualityRow("GNSS Fix Update Rate", availability.gnss)
        }
    }
}

@Composable
fun QualityRow(label: String, health: StreamHealth) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color(0xFF94A3B8), fontSize = 12.sp)
        if (health.isAvailable) {
            val statusText = if (health.isStale) "STALE (No data > 1s)" else String.format(Locale.US, "HEALTHY (%.1f Hz)", health.measuredFrequencyHz)
            Text(
                text = statusText,
                color = if (health.isStale) Color(0xFFF59E0B) else Color(0xFF10B981),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        } else {
            Text("UNAVAILABLE", color = Color(0xFF64748B), fontSize = 12.sp)
        }
    }
}

@Composable
fun BadgeChip(text: String, bgColor: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bgColor)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text = text, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}
