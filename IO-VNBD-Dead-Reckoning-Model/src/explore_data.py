"""
explore_data.py - IO-VNBD Dataset Exploration & Signal Analysis
================================================================

This script loads and inspects vehicle driving sequences from the IO-VNBD
(Inertial and Odometry Vehicle Navigation Benchmark Dataset), with specific
focus on Smartphone (S) sensors for dead-reckoning modeling.

COLUMN SUMMARY AND MAPPINGS
---------------------------
1. Ground Truth Position (GPS):
   - 'GPS LATITUDE (degrees)'      : WGS84 Latitude
   - 'GPS LONGITUDE (degrees)'     : WGS84 Longitude
   - 'GPS ALTITUDE (m)'            : Altitude above sea level in meters
   - 'GPS SPEED (Kmh)'             : Ground speed from GPS receiver
   - 'GPS ACCURACY (m)'            : GPS horizontal accuracy estimate
   - 'GPS ORIENTATION (°)'         : GPS bearing / heading angle
   - 'GPS SATELLITES IN RANGE'     : Active satellites / total in view

2. Phone Inertial Inputs (Model Features):
   - 'ACCELEROMETER X (m/s²)'      : Phone body-frame lateral / X acceleration
   - 'ACCELEROMETER Y (m/s²)'      : Phone body-frame longitudinal / Y acceleration
   - 'ACCELEROMETER Z (m/s²)'      : Phone body-frame vertical / Z acceleration (includes gravity)
   - 'GYROSCOPE X (rad/s)'         : Phone angular velocity around body X-axis (pitch rate)
   - 'GYROSCOPE Y (rad/s)'         : Phone angular velocity around body Y-axis (roll rate)
   - 'GYROSCOPE Z (rad/s)'         : Phone angular velocity around body Z-axis (yaw rate)

3. Additional Phone Sensors (Available for Orientation / Attitude features):
   - 'GRAVITY X (m/s²)'            : Estimated gravity vector X component
   - 'GRAVITY Y (m/s²)'            : Estimated gravity vector Y component
   - 'GRAVITY Z (m/s²)'            : Estimated gravity vector Z component
   - 'MAGNETIC FIELD X (μT)'       : Calibrated magnetometer X in microteslas
   - 'MAGNETIC FIELD Y (μT)'       : Calibrated magnetometer Y in microteslas
   - 'MAGNETIC FIELD Z (μT)'       : Calibrated magnetometer Z in microteslas
   - 'ORIENTATION (Azimuth) (°)'   : Compass heading from sensor fusion
   - 'ORIENTATION (Pitch) (°)'     : Phone pitch angle
   - 'ORIENTATION (Roll ) (°)'     : Phone roll angle

4. Phone Timing & Metadata:
   - 'TIME SINCE START (ms)'       : Phone monotonic elapsed time in milliseconds
   - 'DATE (YYYY-MO-DD HH-MI-SS_SSS)': Wall-clock UTC timestamp

5. Vehicle-Only Columns (Present in Synchronized V-Dataset files):
   - 'Wheel Speed Front Left (rad/sec)'  \
   - 'Wheel Speed Front Right (rad/sec)'  |-> Wheel odometry
   - 'Wheel Speed Rear Left (rad/sec)'   |
   - 'Wheel Speed Rear Right (rad/sec)'  /
   - 'Steering Angle (degrees)'          : Car steering wheel angle
   - 'Yaw Rate (deg/sec)'                : Vehicle chassis gyro yaw rate
   - 'Indicated Vehicle Speed (km/hr)'   : Dashboard / CAN bus speedometer
   - 'Indicated Longitudinal Acceleration (g)'
   - 'Indicated Lateral Acceleration (g)'
   - 'Brake Pressure (psi)', 'Brake Position', 'Handbrake', 'Clutch Position'
   - 'Engine Speed (rev/min)', 'Gear', 'Accelerator Pedal Position'
   ** NOTE ON VEHICLE SENSORS **:
   These vehicle columns are STRICTLY EXCLUDED from model training and inference.
   Our mobile navigation app will only ever have access to smartphone IMU sensors.
   Evaluating or training with vehicle wheel speed or CAN bus signals would yield
   misleading performance metrics that cannot be replicated on an un-tethered phone.
"""

import os
import sys
import argparse
import glob
import numpy as np
import pandas as pd
import matplotlib
import matplotlib.pyplot as plt

# Categorization rules for IO-VNBD columns
PHONE_GPS_KEYWORDS = ["GPS LATITUDE", "GPS LONGITUDE", "GPS ALTITUDE", "GPS SPEED", "GPS ACCURACY", "GPS ORIENTATION", "GPS SATELLITES"]
PHONE_IMU_KEYWORDS = ["ACCELEROMETER", "GYROSCOPE"]
PHONE_OTHER_KEYWORDS = ["GRAVITY", "MAGNETIC FIELD", "ORIENTATION (Azimuth)", "ORIENTATION (Pitch)", "ORIENTATION (Roll"]
PHONE_TIME_KEYWORDS = ["TIME SINCE START", "DATE"]

VEHICLE_KEYWORDS = [
    "Wheel Speed", "Steering Angle", "Yaw Rate", "Indicated Vehicle Speed",
    "Indicated Longitudinal Acceleration", "Indicated Lateral Acceleration",
    "Handbrake", "Gear", "Engine Speed", "Coolant Temperature",
    "Clutch Position", "Brake Pressure", "Brake Position", "Battery Voltage",
    "Air Temperature", "Accelerator Pedal Position", "Vertical velocity",
    "Sample period", "Heading", "Height", "No of GPS Satellites Available",
    "Time Since Start of Day"
]


def classify_column(col_name: str) -> str:
    """Classify a column into Phone (S) categories or Vehicle (V) category."""
    clean = col_name.strip()
    for kw in PHONE_GPS_KEYWORDS:
        if kw.lower() in clean.lower():
            return "Phone (S) - GPS Ground Truth"
    for kw in PHONE_IMU_KEYWORDS:
        if kw.lower() in clean.lower():
            return "Phone (S) - Inertial Input (Accel / Gyro)"
    for kw in PHONE_OTHER_KEYWORDS:
        if kw.lower() in clean.lower():
            return "Phone (S) - Auxiliary (Gravity / Mag / Attitude)"
    for kw in PHONE_TIME_KEYWORDS:
        if kw.lower() in clean.lower():
            return "Phone (S) - Timestamp & Clock"
    for kw in VEHICLE_KEYWORDS:
        if kw.lower() in clean.lower():
            return "Vehicle (V) - Car Sensor [EXCLUDED FROM MODEL]"
    return "Unclassified / Other"


def load_sequence(file_path: str) -> pd.DataFrame:
    """Load CSV sequence with encoding fallback and stripped column headers."""
    if not os.path.exists(file_path):
        raise FileNotFoundError(f"File not found: {file_path}")

    # Try utf-8 first, fallback to latin-1
    try:
        df = pd.read_csv(file_path, encoding="utf-8")
    except UnicodeDecodeError:
        df = pd.read_csv(file_path, encoding="latin-1")

    # Clean whitespace around headers
    df.columns = df.columns.str.strip()
    return df


def compute_haversine_distance(lats: np.ndarray, lons: np.ndarray) -> np.ndarray:
    """Compute step-by-step Haversine distance in meters."""
    R = 6371000.0  # Earth radius in meters
    lat_rad = np.radians(lats)
    lon_rad = np.radians(lons)

    dlat = np.diff(lat_rad)
    dlon = np.diff(lon_rad)

    a = (np.sin(dlat / 2.0) ** 2 +
         np.cos(lat_rad[:-1]) * np.cos(lat_rad[1:]) * np.sin(dlon / 2.0) ** 2)
    a = np.clip(a, 0.0, 1.0)
    c = 2.0 * np.arcsin(np.sqrt(a))
    dist_steps = R * c
    return np.insert(dist_steps, 0, 0.0)


def latlon_to_local_meters(lats: np.ndarray, lons: np.ndarray):
    """
    Convert lat/lon array to local Cartesian coordinates (x=East, y=North)
    in meters relative to initial position.
    """
    R = 6371000.0
    lat0 = np.radians(lats[0])
    lon0 = np.radians(lons[0])

    lat_rad = np.radians(lats)
    lon_rad = np.radians(lons)

    x = R * (lon_rad - lon0) * np.cos(lat0)
    y = R * (lat_rad - lat0)
    return x, y


def print_inspection_report(df: pd.DataFrame, file_path: str):
    """Print complete shape, categorized columns, nulls, and sample rows."""
    print("=" * 80)
    print(f"IO-VNBD DATASET INSPECTION: {os.path.basename(file_path)}")
    print(f"Full Path: {os.path.abspath(file_path)}")
    print("=" * 80)
    print(f"Shape: {df.shape[0]:,} rows x {df.shape[1]} columns")

    # Categorize columns
    categorized = {}
    for col in df.columns:
        cat = classify_column(col)
        categorized.setdefault(cat, []).append(col)

    print("\n--- COLUMN INVENTORY & CATEGORIZATION ---")
    for category, cols in sorted(categorized.items()):
        print(f"\n[{category}] ({len(cols)} columns):")
        for c in cols:
            null_cnt = df[c].isnull().sum()
            dtype = df[c].dtype
            null_str = f"({null_cnt} NaNs)" if null_cnt > 0 else "(0 NaNs)"
            print(f"  • {c:<36} | type: {str(dtype):<8} | {null_str}")

    # Missing / NaN summary
    total_nans = df.isnull().sum().sum()
    print(f"\nTotal missing / NaN values in sequence: {total_nans}")

    # Print sample rows
    print("\n--- SAMPLE ROWS (First 3 rows) ---")
    # Show key columns for brevity in terminal
    key_cols = [c for c in df.columns if any(k in c for k in ["LATITUDE", "LONGITUDE", "SPEED", "ACCELEROMETER X", "GYROSCOPE Z", "TIME SINCE START"])]
    if not key_cols:
        key_cols = list(df.columns[:6])
    print(df[key_cols].head(3).to_string())
    print("\n" + "=" * 80)


def compute_sequence_stats(df: pd.DataFrame):
    """Compute and print duration, distance, and actual observed sampling rate."""
    print("\n--- OBSERVED SEQUENCE METRICS ---")

    # Time & Sampling rate
    time_col = None
    for col in ["TIME SINCE START (ms)", "Time Since Start of Day (seconds)"]:
        if col in df.columns:
            time_col = col
            break

    if time_col == "TIME SINCE START (ms)":
        time_ms = df[time_col].to_numpy()
        dt_ms = np.diff(time_ms)
        # Filter out reset anomalies if any (e.g. negative jump)
        valid_dt = dt_ms[dt_ms > 0]
        median_dt = np.median(valid_dt)
        mean_dt = np.mean(valid_dt)
        std_dt = np.std(valid_dt)
        min_dt = np.min(valid_dt)
        max_dt = np.max(valid_dt)

        actual_sampling_hz = 1000.0 / median_dt if median_dt > 0 else 0.0

        # Total duration: if negative reset exists, sum positive dt or use date
        neg_jumps = np.sum(dt_ms < 0)
        total_duration_sec = np.sum(valid_dt) / 1000.0
        print(f"Sampling Period: Median = {median_dt:.2f} ms (Mean = {mean_dt:.2f} ms ± {std_dt:.2f} ms)")
        print(f"Observed Sampling Rate: {actual_sampling_hz:.2f} Hz (Range: {1000.0/max_dt:.1f} Hz - {1000.0/min_dt:.1f} Hz)")
        if neg_jumps > 0:
            print(f"Note: Detected {neg_jumps} timer reset point(s); duration accumulated over continuous segments.")
        print(f"Total Duration: {total_duration_sec:.2f} s ({total_duration_sec / 60.0:.2f} min / {total_duration_sec / 3600.0:.2f} hr)")

    # Distance calculation from GPS
    lat_col = [c for c in df.columns if "LATITUDE" in c.upper()]
    lon_col = [c for c in df.columns if "LONGITUDE" in c.upper()]

    if lat_col and lon_col:
        lats = df[lat_col[0]].to_numpy()
        lons = df[lon_col[0]].to_numpy()
        step_distances = compute_haversine_distance(lats, lons)
        total_distance_m = np.sum(step_distances)
        total_distance_km = total_distance_m / 1000.0

        print(f"Total Distance Covered (GPS): {total_distance_m:,.2f} m ({total_distance_km:.2f} km)")

        # Inferred average speed
        if "total_duration_sec" in locals() and total_duration_sec > 0:
            avg_speed_kmh = (total_distance_km / (total_duration_sec / 3600.0))
            print(f"Average Trajectory Speed: {avg_speed_kmh:.2f} km/h")

    # GPS Speed column if available
    speed_col = [c for c in df.columns if "GPS SPEED" in c.upper() or "VELOCITY" in c.upper()]
    if speed_col:
        speeds = df[speed_col[0]].to_numpy()
        print(f"Max Speed Logged: {np.max(speeds):.2f} km/h (Mean: {np.mean(speeds):.2f} km/h)")

    print("=" * 80)


def plot_trajectory_and_signals(df: pd.DataFrame, file_name: str, output_dir: str = "plots", show: bool = False):
    """Plot GPS trajectory and raw IMU signals, saving publication-quality figures."""
    os.makedirs(output_dir, exist_ok=True)
    base_name = os.path.splitext(os.path.basename(file_name))[0]

    # Resolve GPS columns
    lat_cols = [c for c in df.columns if "LATITUDE" in c.upper()]
    lon_cols = [c for c in df.columns if "LONGITUDE" in c.upper()]

    if lat_cols and lon_cols:
        lats = df[lat_cols[0]].to_numpy()
        lons = df[lon_cols[0]].to_numpy()
        x_m, y_m = latlon_to_local_meters(lats, lons)

        # Plot 1: GPS Trajectory
        fig, axs = plt.subplots(1, 2, figsize=(15, 6), facecolor="white")

        # Subplot 1: Lat / Lon
        axs[0].plot(lons, lats, color="#1f77b4", linewidth=1.5, label="GPS Path")
        axs[0].scatter(lons[0], lats[0], color="#2ca02c", s=80, zorder=5, label="Start", edgecolors="black")
        axs[0].scatter(lons[-1], lats[-1], color="#d62728", s=80, zorder=5, marker="s", label="End", edgecolors="black")
        axs[0].set_title(f"GPS Trajectory (WGS84) - {base_name}", fontsize=12, fontweight="bold")
        axs[0].set_xlabel("Longitude (degrees)")
        axs[0].set_ylabel("Latitude (degrees)")
        axs[0].grid(True, linestyle="--", alpha=0.5)
        axs[0].legend(loc="best")

        # Subplot 2: Local Cartesian (East vs North in meters)
        axs[1].plot(x_m, y_m, color="#ff7f0e", linewidth=1.5, label="Local Trajectory")
        axs[1].scatter(0, 0, color="#2ca02c", s=80, zorder=5, label="Start (0,0)", edgecolors="black")
        axs[1].scatter(x_m[-1], y_m[-1], color="#d62728", s=80, zorder=5, marker="s", label="End", edgecolors="black")
        axs[1].set_title(f"Local Cartesian Displacement - {base_name}", fontsize=12, fontweight="bold")
        axs[1].set_xlabel("East / X displacement (meters)")
        axs[1].set_ylabel("North / Y displacement (meters)")
        axs[1].axis("equal")
        axs[1].grid(True, linestyle="--", alpha=0.5)
        axs[1].legend(loc="best")

        plt.tight_layout()
        traj_path = os.path.join(output_dir, f"{base_name}_gps_trajectory.png")
        plt.savefig(traj_path, dpi=200)
        print(f"[SAVED] GPS Trajectory plot: {traj_path}")
        if show:
            plt.show()
        plt.close(fig)

    # Plot 2: Raw IMU Signals (Accelerometer and Gyroscope)
    acc_cols = [c for c in df.columns if "ACCELEROMETER" in c.upper() and any(axis in c for axis in [" X", " Y", " Z"])]
    gyro_cols = [c for c in df.columns if "GYROSCOPE" in c.upper() and any(axis in c for axis in [" X", " Y", " Z"])]

    if acc_cols or gyro_cols:
        # Construct time axis in seconds
        if "TIME SINCE START (ms)" in df.columns:
            # Monotonic time axis starting at 0
            t_ms = df["TIME SINCE START (ms)"].to_numpy()
            t_diff = np.diff(t_ms)
            t_diff[t_diff < 0] = 100  # repair any reset gap with nominal 100ms
            t_sec = np.insert(np.cumsum(t_diff) / 1000.0, 0, 0.0)
        else:
            t_sec = np.arange(len(df)) * 0.1

        fig, axs = plt.subplots(3, 1, figsize=(14, 10), sharex=True, facecolor="white")

        # 1. Accelerometer
        if len(acc_cols) >= 3:
            ax = df[acc_cols[0]].to_numpy()
            ay = df[acc_cols[1]].to_numpy()
            az = df[acc_cols[2]].to_numpy()
            a_norm = np.sqrt(ax**2 + ay**2 + az**2)

            axs[0].plot(t_sec, ax, label="Acc X (lateral)", color="#1f77b4", alpha=0.8, linewidth=0.8)
            axs[0].plot(t_sec, ay, label="Acc Y (longitudinal)", color="#2ca02c", alpha=0.8, linewidth=0.8)
            axs[0].plot(t_sec, az, label="Acc Z (vertical)", color="#d62728", alpha=0.7, linewidth=0.8)
            axs[0].plot(t_sec, a_norm, label="|Acc| (norm)", color="#333333", alpha=0.5, linewidth=0.8, linestyle=":")
            axs[0].set_ylabel("Acceleration (m/s²)", fontweight="bold")
            axs[0].set_title(f"Phone Accelerometer Signals Over Time - {base_name}", fontsize=12, fontweight="bold")
            axs[0].legend(loc="upper right", ncol=4, framealpha=0.9)
            axs[0].grid(True, linestyle="--", alpha=0.5)

        # 2. Gyroscope
        if len(gyro_cols) >= 3:
            gx = df[gyro_cols[0]].to_numpy()
            gy = df[gyro_cols[1]].to_numpy()
            gz = df[gyro_cols[2]].to_numpy()

            axs[1].plot(t_sec, gx, label="Gyro X (pitch rate)", color="#9467bd", alpha=0.8, linewidth=0.8)
            axs[1].plot(t_sec, gy, label="Gyro Y (roll rate)", color="#8c564b", alpha=0.8, linewidth=0.8)
            axs[1].plot(t_sec, gz, label="Gyro Z (yaw rate)", color="#e377c2", alpha=0.9, linewidth=0.8)
            axs[1].set_ylabel("Angular Velocity (rad/s)", fontweight="bold")
            axs[1].set_title("Phone Gyroscope Signals Over Time", fontsize=12, fontweight="bold")
            axs[1].legend(loc="upper right", ncol=3, framealpha=0.9)
            axs[1].grid(True, linestyle="--", alpha=0.5)

        # 3. Zoomed-in 30-second window to show sensor noise and fine dynamics
        zoom_start = min(100.0, t_sec[-1] * 0.2)
        zoom_end = zoom_start + 30.0
        mask = (t_sec >= zoom_start) & (t_sec <= zoom_end)

        if np.any(mask) and len(acc_cols) >= 3 and len(gyro_cols) >= 3:
            axs[2].plot(t_sec[mask], ax[mask], label="Acc X", color="#1f77b4", linewidth=1.0)
            axs[2].plot(t_sec[mask], ay[mask], label="Acc Y", color="#2ca02c", linewidth=1.0)
            axs[2].plot(t_sec[mask], gz[mask] * 10.0, label="Gyro Z (yaw rate x10)", color="#e377c2", linewidth=1.2)
            axs[2].set_xlim(zoom_start, zoom_end)
            axs[2].set_title(f"30-Second Zoomed Segment [{zoom_start:.0f}s - {zoom_end:.0f}s] (Signal Quality & Vibration Noise)", fontsize=12, fontweight="bold")
            axs[2].set_xlabel("Time (seconds)", fontweight="bold")
            axs[2].set_ylabel("Amplitude", fontweight="bold")
            axs[2].legend(loc="upper right", ncol=3, framealpha=0.9)
            axs[2].grid(True, linestyle="--", alpha=0.5)
        else:
            axs[2].set_xlabel("Time (seconds)", fontweight="bold")

        plt.tight_layout()
        imu_path = os.path.join(output_dir, f"{base_name}_imu_signals.png")
        plt.savefig(imu_path, dpi=200)
        print(f"[SAVED] IMU Signals plot: {imu_path}")
        if show:
            plt.show()
        plt.close(fig)


def main():
    parser = argparse.ArgumentParser(description="Explore IO-VNBD Smartphone Sensor Driving Sequences")
    parser.add_argument("--file", type=str, default=None, help="Path to CSV sequence file (default: searches in data/)")
    parser.add_argument("--outdir", type=str, default="plots", help="Directory to save generated plots (default: plots/)")
    parser.add_argument("--show", action="store_true", help="Display interactive plots with plt.show()")
    args = parser.parse_args()

    # Find file if not specified
    if args.file is None:
        csv_files = sorted(glob.glob("data/*.csv"))
        if not csv_files:
            print("Error: No CSV files found in data/ directory.")
            print("Please run `python src/download_sample_data.py` or specify `--file <path>`.")
            sys.exit(1)
        # Prefer S-S1.csv or S-M.csv
        file_path = csv_files[0]
        for f in csv_files:
            if "S-S1" in f:
                file_path = f
                break
    else:
        file_path = args.file

    print(f"Loading sequence from: {file_path}")
    df = load_sequence(file_path)

    # 1. Print inspection report
    print_inspection_report(df, file_path)

    # 2. Print metrics
    compute_sequence_stats(df)

    # 3. Generate plots
    plot_trajectory_and_signals(df, file_path, output_dir=args.outdir, show=args.show)

    print("\n[COMPLETE] Exploration finished successfully.")
    print("Please confirm the proposed column mapping before proceeding to preprocessing.")


if __name__ == "__main__":
    main()
