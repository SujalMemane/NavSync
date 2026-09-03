"""
inference.py - Real-Time Dead-Reckoning Inference Interface
===========================================================

Provides a clean, self-contained Python interface for predicting vehicle
displacement (dx, dy in meters) from raw smartphone accelerometer and gyroscope
sensor readings.

CRITICAL SENSOR CONTRACT FOR MOBILE DEVELOPER:
----------------------------------------------
- Sensor Source  : Standard Android / iOS Smartphone sensors ONLY (no car/OBD data).
- Sampling Rate  : 10 Hz (exactly 1 sample every 100 milliseconds).
- Window Length  : 10 consecutive time steps (1.0 second of motion).
- Required Units :
    * Accelerometer : m/s²  (SI meters per second squared, includes Earth gravity ~9.81)
    * Gyroscope     : rad/s (radians per second, phone body-frame angular rate)
- Feature Order (6 Channels):
    Index 0: acc_x   (lateral / screen-horizontal acceleration in m/s²)
    Index 1: acc_y   (longitudinal / screen-vertical acceleration in m/s²)
    Index 2: acc_z   (perpendicular / screen-normal acceleration in m/s²)
    Index 3: gyro_x  (pitch rate in rad/s)
    Index 4: gyro_y  (roll rate in rad/s)
    Index 5: gyro_z  (yaw rate in rad/s - crucial for heading changes)
"""

import os
import pickle
from typing import List, Tuple, Union, Dict, Any
import numpy as np
import torch

from model import DeadReckoningNet


class DeadReckoningPredictor:
    """
    Inference engine that loads model weights and feature scaler once,
    performing real-time displacement prediction for incoming sensor windows.
    """

    def __init__(
        self,
        model_path: str = None,
        scaler_path: str = None,
        device: str = "cpu",
    ):
        # Resolve paths with fallbacks for both root repo and /handoff directory
        base_dir = os.path.dirname(os.path.abspath(__file__))
        repo_dir = os.path.dirname(base_dir)

        candidate_model_paths = [
            model_path,
            os.path.join(repo_dir, "models", "best_model.pt"),
            os.path.join(base_dir, "best_model.pt"),
            os.path.join(repo_dir, "handoff", "best_model.pt"),
        ]
        resolved_model_path = next((p for p in candidate_model_paths if p and os.path.exists(p)), None)
        if not resolved_model_path:
            raise FileNotFoundError(f"Could not locate model weights ('best_model.pt'). Looked in: {candidate_model_paths}")

        candidate_scaler_paths = [
            scaler_path,
            os.path.join(repo_dir, "models", "scaler.pkl"),
            os.path.join(base_dir, "scaler.pkl"),
            os.path.join(repo_dir, "handoff", "scaler.pkl"),
        ]
        resolved_scaler_path = next((p for p in candidate_scaler_paths if p and os.path.exists(p)), None)
        if not resolved_scaler_path:
            raise FileNotFoundError(f"Could not locate scaler ('scaler.pkl'). Looked in: {candidate_scaler_paths}")

        self.device = torch.device(device)

        # 1. Load fitted feature scaler
        with open(resolved_scaler_path, "rb") as f:
            self.scaler = pickle.load(f)

        # 2. Load PyTorch model weights
        checkpoint = torch.load(resolved_model_path, map_location=self.device)
        hp = checkpoint.get("hyperparameters", {})

        self.model = DeadReckoningNet(
            input_size=hp.get("input_size", 4),
            hidden_size=hp.get("hidden_size", 64),
            num_lstm_layers=hp.get("num_lstm_layers", 2),
            dropout=hp.get("dropout", 0.1),
        )
        self.model.load_state_dict(checkpoint["model_state_dict"])
        self.model.to(self.device)
        self.model.eval()

        self.window_size = hp.get("window_size", 10)
        self.model_path = resolved_model_path
        self.scaler_path = resolved_scaler_path

    def preprocess_window(
        self, imu_window: Union[np.ndarray, List[List[float]], List[Dict[str, float]]]
    ) -> np.ndarray:
        """
        Convert raw phone sensor window into standardized orientation-invariant features.

        Expected shapes:
          - (10, 6): [acc_x, acc_y, acc_z, gyro_x, gyro_y, gyro_z]
          - (10, 4): [acc_mag, acc_lin_mag, gyro_z, gyro_mag]
          - List of 10 dicts: [{'acc_x': ..., 'acc_y': ..., 'acc_z': ..., 'gyro_x': ..., 'gyro_y': ..., 'gyro_z': ...}]
        """
        # Convert list of dicts to 2D numpy array
        if isinstance(imu_window, list) and len(imu_window) > 0 and isinstance(imu_window[0], dict):
            raw_matrix = []
            for row in imu_window:
                raw_matrix.append([
                    row.get("acc_x", 0.0),
                    row.get("acc_y", 0.0),
                    row.get("acc_z", 9.81),
                    row.get("gyro_x", 0.0),
                    row.get("gyro_y", 0.0),
                    row.get("gyro_z", 0.0),
                ])
            arr = np.array(raw_matrix, dtype=np.float32)
        else:
            arr = np.array(imu_window, dtype=np.float32)

        if arr.ndim != 2:
            raise ValueError(f"Expected 2D array of shape (10, 6) or (10, 4), got dimension {arr.ndim}")

        if arr.shape[0] != self.window_size:
            raise ValueError(f"Expected exactly {self.window_size} time samples (1.0s @ 10Hz), got {arr.shape[0]}")

        # If 6 raw channels provided [acc_x, acc_y, acc_z, gyro_x, gyro_y, gyro_z]
        if arr.shape[1] == 6:
            ax, ay, az = arr[:, 0], arr[:, 1], arr[:, 2]
            gx, gy, gz = arr[:, 3], arr[:, 4], arr[:, 5]

            # Orientation-invariant feature transformation
            acc_mag = np.sqrt(ax**2 + ay**2 + az**2)
            acc_lin_mag = acc_mag - 9.80665  # dynamic acceleration
            gyro_mag = np.sqrt(gx**2 + gy**2 + gz**2)

            features = np.column_stack([acc_mag, acc_lin_mag, gz, gyro_mag]).astype(np.float32)
        elif arr.shape[1] == 4:
            # Pre-computed 4 orientation-invariant features
            features = arr.astype(np.float32)
        else:
            raise ValueError(f"Expected 6 raw sensor columns or 4 invariant features, got {arr.shape[1]} columns")

        # Apply fitted scaler
        scaled_features = self.scaler.transform(features).astype(np.float32)
        return scaled_features

    def predict(
        self, imu_window: Union[np.ndarray, List[List[float]], List[Dict[str, float]]]
    ) -> Tuple[float, float]:
        """
        Predict (dx, dy) vehicle displacement in meters over the input 1.0s window.
        """
        scaled_window = self.preprocess_window(imu_window)  # shape: (10, 4)

        # Reshape to [batch_size=1, window_size=10, num_features=4]
        input_tensor = torch.from_numpy(scaled_window).unsqueeze(0).to(self.device)

        with torch.no_grad():
            pred_tensor = self.model(input_tensor)  # shape: [1, 2]

        dx = float(pred_tensor[0, 0].item())
        dy = float(pred_tensor[0, 1].item())
        return dx, dy


# Singleton predictor instance for fast module-level function calls
_GLOBAL_PREDICTOR = None


def predict_displacement(
    imu_window: Union[np.ndarray, List[List[float]], List[Dict[str, float]]]
) -> Tuple[float, float]:
    """
    Predicts 2D vehicle displacement (dx, dy in meters) for a 1.0-second phone sensor window.

    CONTRACT & SPECIFICATIONS:
    --------------------------
    - Input 'imu_window':
        * Shape        : Exactly 10 rows (10Hz sampling over 1.0 second).
        * Columns (6)  : [acc_x, acc_y, acc_z, gyro_x, gyro_y, gyro_z]
        * Accelerometer: m/s² (Earth gravity ~9.81 m/s² included when stationary).
        * Gyroscope    : rad/s (Phone body rotational rate).
    - Output:
        * Tuple[float, float]: (dx, dy) in meters in the vehicle's local Cartesian frame.
        * To update vehicle position:
            new_x = current_x + dx
            new_y = current_y + dy

    Example Usage:
    --------------
    >>> import numpy as np
    >>> # 10 samples of phone sitting in moving car (x=lateral, y=fwd, z=vertical)
    >>> sample_window = np.zeros((10, 6))
    >>> sample_window[:, 1] = 1.2    # 1.2 m/s² forward acceleration
    >>> sample_window[:, 2] = 9.81   # Earth gravity
    >>> sample_window[:, 5] = 0.05   # slight yaw rate in rad/s
    >>> dx, dy = predict_displacement(sample_window)
    >>> print(f"Vehicle displaced by ({dx:.2f}m, {dy:.2f}m)")
    """
    global _GLOBAL_PREDICTOR
    if _GLOBAL_PREDICTOR is None:
        device = "mps" if torch.backends.mps.is_available() else "cpu"
        _GLOBAL_PREDICTOR = DeadReckoningPredictor(device=device)

    return _GLOBAL_PREDICTOR.predict(imu_window)


if __name__ == "__main__":
    print("=" * 70)
    print("Testing DeadReckoningPredictor Standalone Inference")
    print("=" * 70)
    # Generate dummy 1.0s window: 10 readings, 6 sensors
    dummy_window = np.zeros((10, 6), dtype=np.float32)
    dummy_window[:, 1] = 1.5   # 1.5 m/s² longitudinal forward motion
    dummy_window[:, 2] = 9.81  # 1G vertical gravity
    dummy_window[:, 5] = 0.02  # small yaw turn

    dx, dy = predict_displacement(dummy_window)
    distance = np.sqrt(dx**2 + dy**2)
    print(f"Sample Input Window Shape : {dummy_window.shape} (10 samples @ 10Hz)")
    print(f"Predicted Displacement dx : {dx:+.4f} meters")
    print(f"Predicted Displacement dy : {dy:+.4f} meters")
    print(f"Total Displaced Distance  : {distance:.4f} meters")
    print("=" * 70)
    print("[SUCCESS] Inference interface operational.")
