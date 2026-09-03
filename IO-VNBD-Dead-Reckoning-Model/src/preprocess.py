"""
preprocess.py - Preprocessing & Feature Engineering for IO-VNBD Sequences
========================================================================

Converts raw smartphone IMU & GPS sensor data into normalized sliding window
datasets for deep learning dead-reckoning models.

Key Pipeline Steps:
1. Load raw sequence CSV (UTF-8 / Latin-1 fallback, stripped column headers).
2. Convert GPS Latitude / Longitude to local Cartesian coordinates (x=East, y=North)
   in meters with the first GPS fix as the origin (0, 0).
3. Compute window-level ground-truth displacement (dx, dy) and (distance, delta_heading).
4. Extract phone inertial features:
   - Approach (a) [Default & Recommended]: Orientation-invariant features:
     * Accelerometer magnitude: sqrt(ax^2 + ay^2 + az^2)
     * Linear acceleration magnitude: acc_mag - 9.80665 m/s²
     * Gyroscope Z (yaw rate): primary rotation signal driving heading changes
     * Gyroscope magnitude: sqrt(gx^2 + gy^2 + gz^2)
     (Optional: include raw 3-axis accel and gyro alongside invariant features)
   - Approach (b): Device-attitude rotated features:
     * Uses device orientation / gravity vectors to project acceleration into
       the horizontal ground plane (horizontal, lateral, vertical).
5. Generate sliding windows (shape: [num_windows, WINDOW_SIZE, num_features]).
6. Standardize features using StandardScaler (fit ONLY on the training split).
7. Time-based split: First 70% Train, Next 15% Validation, Final 15% Test.
8. Save arrays to data/processed/ (.npz / .npy) and the fitted scaler to models/scaler.pkl.
"""

import os
import sys
import argparse
import json
import pickle
import numpy as np
import pandas as pd
from sklearn.preprocessing import StandardScaler


def load_sequence(file_path: str) -> pd.DataFrame:
    """Load raw sequence CSV and strip whitespace around headers."""
    if not os.path.exists(file_path):
        raise FileNotFoundError(f"File not found: {file_path}")
    try:
        df = pd.read_csv(file_path, encoding="utf-8")
    except UnicodeDecodeError:
        df = pd.read_csv(file_path, encoding="latin-1")
    df.columns = df.columns.str.strip()
    return df


def gps_to_local_cartesian(lats: np.ndarray, lons: np.ndarray):
    """
    Convert WGS84 GPS (lat, lon) to local Cartesian (x=East, y=North) in meters.
    Origin (0, 0) is set to the first coordinate point.
    """
    R = 6371000.0  # Earth radius in meters
    lat0 = np.radians(lats[0])
    lon0 = np.radians(lons[0])

    lat_rad = np.radians(lats)
    lon_rad = np.radians(lons)

    # Equirectangular local projection
    x = R * (lon_rad - lon0) * np.cos(lat0)
    y = R * (lat_rad - lat0)
    return x, y


def extract_features(df: pd.DataFrame, feature_mode: str = "invariant") -> tuple[np.ndarray, list[str]]:
    """
    Extract model input features based on selected orientation handling approach.

    Modes:
      - 'invariant': Accelerometer magnitude, linear acceleration, Gyro Z (yaw rate), Gyro magnitude.
      - 'invariant_plus_raw': 6-axis raw IMU + invariant magnitudes (10 features).
      - 'rotated': Gravity-leveled horizontal / lateral / vertical acceleration + gyro.
    """
    # Required raw sensor columns
    ax = df["ACCELEROMETER X (m/s²)"].to_numpy(dtype=np.float32)
    ay = df["ACCELEROMETER Y (m/s²)"].to_numpy(dtype=np.float32)
    az = df["ACCELEROMETER Z (m/s²)"].to_numpy(dtype=np.float32)

    gx = df["GYROSCOPE X (rad/s)"].to_numpy(dtype=np.float32)
    gy = df["GYROSCOPE Y (rad/s)"].to_numpy(dtype=np.float32)
    gz = df["GYROSCOPE Z (rad/s)"].to_numpy(dtype=np.float32)

    # Orientation-invariant computed signals
    acc_mag = np.sqrt(ax**2 + ay**2 + az**2)
    acc_lin_mag = acc_mag - 9.80665  # dynamic linear acceleration magnitude
    gyro_mag = np.sqrt(gx**2 + gy**2 + gz**2)

    if feature_mode == "invariant":
        # Approach (a): 4 features independent of phone mounting tilt
        features = np.column_stack([acc_mag, acc_lin_mag, gz, gyro_mag])
        feature_names = [
            "acc_magnitude",
            "acc_linear_magnitude",
            "gyro_z_yaw_rate",
            "gyro_magnitude",
        ]

    elif feature_mode == "invariant_plus_raw":
        # Full 10-feature representation
        features = np.column_stack([
            ax, ay, az, acc_mag, acc_lin_mag,
            gx, gy, gz, gyro_mag
        ])
        feature_names = [
            "acc_x", "acc_y", "acc_z", "acc_magnitude", "acc_linear_magnitude",
            "gyro_x", "gyro_y", "gyro_z", "gyro_magnitude"
        ]

    elif feature_mode == "rotated":
        # Approach (b): Gravity-vector projection
        # Decompose acceleration into vertical (along gravity vector) and horizontal plane
        if "GRAVITY X (m/s²)" in df.columns and "GRAVITY Y (m/s²)" in df.columns and "GRAVITY Z (m/s²)" in df.columns:
            grav_x = df["GRAVITY X (m/s²)"].to_numpy(dtype=np.float32)
            grav_y = df["GRAVITY Y (m/s²)"].to_numpy(dtype=np.float32)
            grav_z = df["GRAVITY Z (m/s²)"].to_numpy(dtype=np.float32)
            grav_norm = np.sqrt(grav_x**2 + grav_y**2 + grav_z**2) + 1e-8
            # Unit gravity direction (pointing downward)
            g_u_x, g_u_y, g_u_z = grav_x / grav_norm, grav_y / grav_norm, grav_z / grav_norm

            # Projection of acceleration onto vertical axis
            a_vertical = (ax * g_u_x + ay * g_u_y + az * g_u_z) - 9.80665
            # Remaining horizontal acceleration components
            a_horiz_x = ax - (ax * g_u_x) * g_u_x
            a_horiz_y = ay - (ay * g_u_y) * g_u_y
            a_horiz_z = az - (az * g_u_z) * g_u_z
            a_horiz_mag = np.sqrt(a_horiz_x**2 + a_horiz_y**2 + a_horiz_z**2)

            features = np.column_stack([a_horiz_mag, a_vertical, gz, gyro_mag])
            feature_names = [
                "acc_horizontal_plane_mag",
                "acc_vertical_linear",
                "gyro_z_yaw_rate",
                "gyro_magnitude",
            ]
        else:
            raise ValueError("Gravity columns not found in dataset for 'rotated' mode.")
    else:
        raise ValueError(f"Unknown feature mode: {feature_mode}")

    return features.astype(np.float32), feature_names


def create_sliding_windows(
    features: np.ndarray,
    local_x: np.ndarray,
    local_y: np.ndarray,
    window_size: int = 10,
    stride: int = 1,
) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """
    Create sliding windows of IMU features paired with ground-truth displacement.

    Parameters:
      features: (N, num_features) array of standardized/raw IMU features
      local_x: (N,) array of local Cartesian x (East) in meters
      local_y: (N,) array of local Cartesian y (North) in meters
      window_size: Number of consecutive samples in each window (e.g. 10 = 1.0s at 10Hz)
      stride: Step size between consecutive sliding windows

    Returns:
      X: (num_windows, window_size, num_features)
      y_disp: (num_windows, 2) [dx, dy] in meters over the window
      y_dist_heading: (num_windows, 2) [delta_distance (m), delta_heading (rad)]
    """
    num_samples = len(features)
    if num_samples <= window_size:
        raise ValueError(f"Sequence length ({num_samples}) must be greater than window_size ({window_size})")

    X_list = []
    disp_list = []
    dist_heading_list = []

    for start_idx in range(0, num_samples - window_size, stride):
        end_idx = start_idx + window_size
        window_feat = features[start_idx:end_idx]

        # Ground truth displacement from start of window to end of window
        dx = local_x[end_idx] - local_x[start_idx]
        dy = local_y[end_idx] - local_y[start_idx]

        delta_dist = np.sqrt(dx**2 + dy**2)
        delta_heading = np.arctan2(dy, dx)

        X_list.append(window_feat)
        disp_list.append([dx, dy])
        dist_heading_list.append([delta_dist, delta_heading])

    X = np.array(X_list, dtype=np.float32)
    y_disp = np.array(disp_list, dtype=np.float32)
    y_dist_heading = np.array(dist_heading_list, dtype=np.float32)

    return X, y_disp, y_dist_heading


def time_based_split(
    X: np.ndarray,
    y: np.ndarray,
    y_aux: np.ndarray,
    train_ratio: float = 0.70,
    val_ratio: float = 0.15,
) -> dict:
    """
    Split windows into train, val, and test chronologically by time.
    CRITICAL: Avoids data leakage in sequential time-series data.
    """
    num_samples = len(X)
    train_end = int(num_samples * train_ratio)
    val_end = int(num_samples * (train_ratio + val_ratio))

    splits = {
        "X_train": X[:train_end],
        "y_train": y[:train_end],
        "y_aux_train": y_aux[:train_end],
        "X_val": X[train_end:val_end],
        "y_val": y[train_end:val_end],
        "y_aux_val": y_aux[train_end:val_end],
        "X_test": X[val_end:],
        "y_test": y[val_end:],
        "y_aux_test": y_aux[val_end:],
    }
    return splits


def standardize_windows(
    splits: dict,
    models_dir: str = "models",
) -> tuple[dict, StandardScaler]:
    """
    Standardize feature channels across train, val, and test.
    The StandardScaler is fit ONLY on the training split.
    """
    os.makedirs(models_dir, exist_ok=True)
    scaler = StandardScaler()

    # Flatten train windows across batch and time dimensions to fit feature-wise
    X_train = splits["X_train"]
    num_features = X_train.shape[-1]
    X_train_flat = X_train.reshape(-1, num_features)
    scaler.fit(X_train_flat)

    # Transform each split
    for split_key in ["X_train", "X_val", "X_test"]:
        orig_shape = splits[split_key].shape
        flat = splits[split_key].reshape(-1, num_features)
        scaled_flat = scaler.transform(flat)
        splits[split_key] = scaled_flat.reshape(orig_shape).astype(np.float32)

    # Save fitted scaler
    scaler_path = os.path.join(models_dir, "scaler.pkl")
    with open(scaler_path, "wb") as f:
        pickle.dump(scaler, f)
    print(f"[SAVED] Fitted StandardScaler saved to: {scaler_path}")

    return splits, scaler


def run_pipeline(
    input_file: str,
    output_dir: str = "data/processed",
    models_dir: str = "models",
    window_size: int = 10,
    stride: int = 1,
    feature_mode: str = "invariant",
    train_ratio: float = 0.70,
    val_ratio: float = 0.15,
) -> dict:
    """Run full end-to-end preprocessing pipeline."""
    print("=" * 80)
    print("IO-VNBD PREPROCESSING PIPELINE")
    print("=" * 80)
    print(f"Input file:        {input_file}")
    print(f"Window size:       {window_size} samples (10Hz -> {window_size / 10.0:.1f} seconds)")
    print(f"Sliding stride:    {stride}")
    print(f"Feature mode:      {feature_mode}")
    print(f"Time-based split:  Train={train_ratio:.0%}, Val={val_ratio:.0%}, Test={1.0 - train_ratio - val_ratio:.0%}")
    print("=" * 80)

    # 1. Load sequence
    df = load_sequence(input_file)
    print(f"[1/6] Loaded sequence with {len(df):,} rows.")

    # 2. GPS -> Local Cartesian (meters)
    lat_col = [c for c in df.columns if "LATITUDE" in c.upper()][0]
    lon_col = [c for c in df.columns if "LONGITUDE" in c.upper()][0]
    local_x, local_y = gps_to_local_cartesian(df[lat_col].to_numpy(), df[lon_col].to_numpy())
    print(f"[2/6] Converted GPS to local Cartesian coordinates.")
    print(f"      Origin: Lat={df[lat_col].iloc[0]:.6f}, Lon={df[lon_col].iloc[0]:.6f}")
    print(f"      Displacement span: X=[{np.min(local_x):.1f}m, {np.max(local_x):.1f}m], Y=[{np.min(local_y):.1f}m, {np.max(local_y):.1f}m]")

    # 3. Extract phone features
    raw_features, feature_names = extract_features(df, feature_mode=feature_mode)
    print(f"[3/6] Extracted {len(feature_names)} features: {feature_names}")

    # 4. Create sliding windows & paired displacement labels
    X_raw, y_disp, y_dist_heading = create_sliding_windows(
        raw_features, local_x, local_y, window_size=window_size, stride=stride
    )
    print(f"[4/6] Generated {len(X_raw):,} sliding windows. Raw shape: {X_raw.shape}")

    # 5. Time-based split
    splits = time_based_split(X_raw, y_disp, y_dist_heading, train_ratio=train_ratio, val_ratio=val_ratio)
    print(f"[5/6] Applied chronological time split (no data leakage).")

    # 6. Standardize features (fit on train only)
    splits, scaler = standardize_windows(splits, models_dir=models_dir)
    print(f"[6/6] Features standardized using StandardScaler (fit on train only).")

    # Save to data/processed/
    os.makedirs(output_dir, exist_ok=True)
    np.savez_compressed(
        os.path.join(output_dir, "dataset.npz"),
        X_train=splits["X_train"],
        y_train=splits["y_train"],
        y_aux_train=splits["y_aux_train"],
        X_val=splits["X_val"],
        y_val=splits["y_val"],
        y_aux_val=splits["y_aux_val"],
        X_test=splits["X_test"],
        y_test=splits["y_test"],
        y_aux_test=splits["y_aux_test"],
    )

    # Save individual npy arrays for convenient memory-mapping if needed
    for key, arr in splits.items():
        np.save(os.path.join(output_dir, f"{key}.npy"), arr)

    # Save dataset metadata
    metadata = {
        "input_file": os.path.abspath(input_file),
        "feature_names": feature_names,
        "feature_mode": feature_mode,
        "window_size": window_size,
        "stride": stride,
        "sampling_rate_hz": 10.0,
        "num_total_windows": len(X_raw),
        "shapes": {k: list(v.shape) for k, v in splits.items()},
        "feature_means": scaler.mean_.tolist(),
        "feature_stds": scaler.scale_.tolist(),
        "train_ratio": train_ratio,
        "val_ratio": val_ratio,
        "test_ratio": round(1.0 - train_ratio - val_ratio, 2),
    }
    with open(os.path.join(output_dir, "metadata.json"), "w") as f:
        json.dump(metadata, f, indent=2)

    print("\n" + "=" * 80)
    print("PREPROCESSING SUMMARY & TENSOR SHAPES")
    print("=" * 80)
    print(f"X_train:     {str(splits['X_train'].shape):<20} | y_train (dx, dy): {splits['y_train'].shape}")
    print(f"X_val:       {str(splits['X_val'].shape):<20} | y_val   (dx, dy): {splits['y_val'].shape}")
    print(f"X_test:      {str(splits['X_test'].shape):<20} | y_test  (dx, dy): {splits['y_test'].shape}")
    print(f"Features:    {feature_names}")
    print(f"Saved files in: {os.path.abspath(output_dir)}")
    print("=" * 80)

    return splits


def main():
    parser = argparse.ArgumentParser(description="Preprocess IO-VNBD sequences for Dead-Reckoning model")
    parser.add_argument("--file", type=str, default="data/S-S1.csv", help="Sequence CSV file (default: data/S-S1.csv)")
    parser.add_argument("--window_size", type=int, default=10, help="Window length in samples (default: 10 = 1.0s at 10Hz)")
    parser.add_argument("--stride", type=int, default=1, help="Stride step size between windows (default: 1)")
    parser.add_argument(
        "--feature_mode",
        type=str,
        default="invariant",
        choices=["invariant", "invariant_plus_raw", "rotated"],
        help="Feature engineering mode: 'invariant' (Approach a), 'rotated' (Approach b), or 'invariant_plus_raw'",
    )
    parser.add_argument("--outdir", type=str, default="data/processed", help="Directory for processed arrays (default: data/processed)")
    parser.add_argument("--models_dir", type=str, default="models", help="Directory for scaler (default: models)")
    parser.add_argument("--train_ratio", type=float, default=0.70, help="Train time ratio (default: 0.70)")
    parser.add_argument("--val_ratio", type=float, default=0.15, help="Val time ratio (default: 0.15)")
    args = parser.parse_args()

    run_pipeline(
        input_file=args.file,
        output_dir=args.outdir,
        models_dir=args.models_dir,
        window_size=args.window_size,
        stride=args.stride,
        feature_mode=args.feature_mode,
        train_ratio=args.train_ratio,
        val_ratio=args.val_ratio,
    )


if __name__ == "__main__":
    main()
