"""
evaluate.py - Trajectory Evaluation & Drift Correction Benchmark
================================================================

Quantitatively proves that DeadReckoningNet corrects for sensor drift during
GPS-denied navigation by benchmarking against naive (non-ML) inertial integration.

Benchmark Comparison:
---------------------
1. Ground Truth GPS:
   - Real vehicle path from phone GPS converted to local Cartesian (x=East, y=North) meters.
2. Naive INS (Traditional Double-Integration, No ML):
   - Direct integration of raw accelerometer and gyroscope readings.
   - Suffers from quadratic sensor drift (accumulates error ~ t²), typical of phone IMUs.
3. Model-Corrected Dead-Reckoning (DeadReckoningNet):
   - Reconstructed trajectory by accumulating ML-predicted (dx, dy) displacement vectors
     window-by-window over the GPS-denied duration.

Key Outputs:
------------
- Plot: models/trajectory_comparison.png (and plots/trajectory_comparison.png)
- Metrics: Mean Position Error (m), Final Drift Error (m), and % Improvement.
"""

import os
import sys
import json
import argparse
import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

import torch
import torch.nn as nn

from model import DeadReckoningNet
from preprocess import load_sequence, gps_to_local_cartesian


def load_model_checkpoint(model_path: str = "models/best_model.pt", device: str = "cpu") -> tuple[DeadReckoningNet, dict]:
    """Load trained model weights and hyperparameter config from checkpoint."""
    if not os.path.exists(model_path):
        raise FileNotFoundError(f"Model checkpoint not found at: {model_path}. Please train model first.")

    checkpoint = torch.load(model_path, map_location=device)
    hp = checkpoint.get("hyperparameters", {})

    model = DeadReckoningNet(
        input_size=hp.get("input_size", 4),
        hidden_size=hp.get("hidden_size", 64),
        num_lstm_layers=hp.get("num_lstm_layers", 2),
        dropout=hp.get("dropout", 0.1),
    )
    model.load_state_dict(checkpoint["model_state_dict"])
    model.to(device)
    model.eval()

    return model, checkpoint


def compute_naive_ins_trajectory(
    test_df,
    dt: float = 0.1,
    v0: float = 0.0,
    theta0: float = 0.0,
) -> np.ndarray:
    """
    Simulate standard non-ML INS dead-reckoning by direct double integration.

    Physics model:
      theta[t] = theta[t-1] + gyro_z[t-1] * dt
      v[t]     = max(0, v[t-1] + (||a|| - g) * dt)
      x[t]     = x[t-1] + v[t] * cos(theta[t]) * dt
      y[t]     = y[t-1] + v[t] * sin(theta[t]) * dt
    """
    N = len(test_df)
    ax = test_df["ACCELEROMETER X (m/s²)"].to_numpy()
    ay = test_df["ACCELEROMETER Y (m/s²)"].to_numpy()
    az = test_df["ACCELEROMETER Z (m/s²)"].to_numpy()
    gz = test_df["GYROSCOPE Z (rad/s)"].to_numpy()

    # Dynamic forward linear acceleration (magnitude minus standard gravity)
    a_norm = np.sqrt(ax**2 + ay**2 + az**2) - 9.80665

    pos_naive = np.zeros((N, 2), dtype=np.float64)
    vel = float(v0)
    theta = float(theta0)

    for i in range(1, N):
        theta += gz[i - 1] * dt
        vel = max(0.0, vel + a_norm[i - 1] * dt)
        pos_naive[i, 0] = pos_naive[i - 1, 0] + vel * np.cos(theta) * dt
        pos_naive[i, 1] = pos_naive[i - 1, 1] + vel * np.sin(theta) * dt

    return pos_naive


def evaluate_dead_reckoning(
    data_dir: str = "data/processed",
    models_dir: str = "models",
    plots_dir: str = "plots",
    device_name: str = "cpu",
):
    """Run full trajectory comparison and quantitative benchmark."""
    os.makedirs(models_dir, exist_ok=True)
    os.makedirs(plots_dir, exist_ok=True)

    # 1. Load metadata and test arrays
    metadata_path = os.path.join(data_dir, "metadata.json")
    with open(metadata_path, "r") as f:
        metadata = json.load(f)

    raw_file = metadata["input_file"]
    window_size = metadata.get("window_size", 10)
    dt = 1.0 / metadata.get("sampling_rate_hz", 10.0)

    X_test = np.load(os.path.join(data_dir, "X_test.npy"))
    y_test = np.load(os.path.join(data_dir, "y_test.npy"))

    print("=" * 80)
    print("DEAD-RECKONING EVALUATION BENCHMARK")
    print("=" * 80)
    print(f"Dataset source    : {raw_file}")
    print(f"Test split windows: {len(X_test):,} windows ({len(X_test) * dt:.1f} seconds of continuous driving)")
    print(f"Window length     : {window_size} samples ({window_size * dt:.1f} s per displacement step)")

    # 2. Load model and predict
    model_path = os.path.join(models_dir, "best_model.pt")
    model, checkpoint = load_model_checkpoint(model_path, device=device_name)
    print(f"Loaded trained model checkpoint (Best Epoch: {checkpoint.get('epoch', 'N/A')})")

    with torch.no_grad():
        inputs = torch.from_numpy(X_test).to(device_name)
        predictions = model(inputs).cpu().numpy()

    # 3. Subsample consecutive non-overlapping windows to reconstruct continuous trajectory
    step = window_size
    preds_sub = predictions[::step]
    y_sub = y_test[::step]
    num_steps = len(preds_sub)
    time_sec = np.arange(num_steps + 1) * (step * dt)

    # Ground truth path accumulated from window ground-truth displacements
    gt_trajectory = np.cumsum(np.vstack([[0.0, 0.0], y_sub]), axis=0)
    # Model-corrected path accumulated from predicted (dx, dy)
    model_trajectory = np.cumsum(np.vstack([[0.0, 0.0], preds_sub]), axis=0)

    # 4. Compute Naive INS Trajectory on the exact same continuous test time segment
    df = load_sequence(raw_file)
    train_ratio = metadata.get("train_ratio", 0.70)
    val_ratio = metadata.get("val_ratio", 0.15)
    val_end_idx = int(len(df) * (train_ratio + val_ratio))
    test_df = df.iloc[val_end_idx:].copy().reset_index(drop=True)

    # Initial conditions from ground truth
    speed_cols = [c for c in df.columns if "SPEED" in c.upper() or "VELOCITY" in c.upper()]
    v0 = (test_df[speed_cols[0]].iloc[0] / 3.6) if speed_cols else 0.0
    theta0 = np.arctan2(y_sub[0, 1], y_sub[0, 0]) if (y_sub[0, 0] != 0 or y_sub[0, 1] != 0) else 0.0

    pos_naive_raw = compute_naive_ins_trajectory(test_df, dt=dt, v0=v0, theta0=theta0)
    # Sample naive INS at identical window boundaries
    pos_naive_sub = pos_naive_raw[: num_steps * step : step]
    naive_trajectory = np.vstack([[0.0, 0.0], pos_naive_sub])

    # Align array lengths
    min_len = min(len(gt_trajectory), len(model_trajectory), len(naive_trajectory))
    gt_trajectory = gt_trajectory[:min_len]
    model_trajectory = model_trajectory[:min_len]
    naive_trajectory = naive_trajectory[:min_len]
    time_sec = time_sec[:min_len]

    # 5. Compute Quantitative Metrics
    naive_errors = np.linalg.norm(naive_trajectory - gt_trajectory, axis=1)
    model_errors = np.linalg.norm(model_trajectory - gt_trajectory, axis=1)

    # Position Error Metrics (meters)
    naive_mean_err = np.mean(naive_errors)
    naive_median_err = np.median(naive_errors)
    naive_final_drift = naive_errors[-1]
    naive_max_err = np.max(naive_errors)

    model_mean_err = np.mean(model_errors)
    model_median_err = np.median(model_errors)
    model_final_drift = model_errors[-1]
    model_max_err = np.max(model_errors)

    # Percentage improvements
    improvement_mean = (naive_mean_err - model_mean_err) / naive_mean_err * 100.0
    improvement_final = (naive_final_drift - model_final_drift) / naive_final_drift * 100.0
    total_dist_covered = np.sum(np.linalg.norm(y_sub, axis=1))

    print("\n" + "=" * 80)
    print("QUANTITATIVE PERFORMANCE METRICS")
    print("=" * 80)
    print(f"Total Test Sequence Duration: {time_sec[-1]:.1f} seconds ({time_sec[-1] / 60.0:.2f} minutes)")
    print(f"Total Ground Truth Distance : {total_dist_covered:,.2f} meters ({total_dist_covered / 1000.0:.2f} km)")
    print("-" * 80)
    print(f"{'Metric':<32} | {'Naive INS (No ML)':<18} | {'DeadReckoningNet':<18} | {'Improvement':<12}")
    print("-" * 80)
    print(f"{'Mean Position Error (m)':<32} | {naive_mean_err:>14.2f} m | {model_mean_err:>14.2f} m | {improvement_mean:>10.2f}%")
    print(f"{'Median Position Error (m)':<32} | {naive_median_err:>14.2f} m | {model_median_err:>14.2f} m | {(naive_median_err - model_median_err)/naive_median_err*100:>10.2f}%")
    print(f"{'Final Drift Error (m)':<32} | {naive_final_drift:>14.2f} m | {model_final_drift:>14.2f} m | {improvement_final:>10.2f}%")
    print(f"{'Maximum Position Error (m)':<32} | {naive_max_err:>14.2f} m | {model_max_err:>14.2f} m | {(naive_max_err - model_max_err)/naive_max_err*100:>10.2f}%")
    print("=" * 80)

    print("\n" + "★" * 80)
    print(f"SIH DEMO HEADLINE RESULT:")
    print(f"  • Mean Position Error Reduced by   : {improvement_mean:.1f}% ({naive_mean_err:.1f} m → {model_mean_err:.1f} m)")
    print(f"  • Final Trajectory Drift Reduced by: {improvement_final:.1f}% ({naive_final_drift:.1f} m → {model_final_drift:.1f} m)")
    print("★" * 80 + "\n")

    # 6. Generate Publication-Quality Visualizations
    fig = plt.figure(figsize=(16, 7), facecolor="white")
    gs = fig.add_gridspec(1, 2, width_ratios=[1.2, 1.0])

    # Left Subplot: 2D Trajectory Spatial Comparison
    ax1 = fig.add_subplot(gs[0])
    ax1.plot(gt_trajectory[:, 0], gt_trajectory[:, 1], color="#1f77b4", linewidth=2.5, label="Ground Truth GPS", zorder=3)
    ax1.plot(model_trajectory[:, 0], model_trajectory[:, 1], color="#2ca02c", linewidth=2.0, linestyle="-", label=f"Model-Corrected Net (Drift: {model_final_drift:.0f}m)", zorder=4)
    ax1.plot(naive_trajectory[:, 0], naive_trajectory[:, 1], color="#d62728", linewidth=1.5, linestyle="--", alpha=0.85, label=f"Naive INS (Drift: {naive_final_drift:.0f}m)", zorder=2)

    # Start and End Markers
    ax1.scatter(0, 0, color="#1a9850", s=120, edgecolors="black", zorder=6, label="Start (0,0)")
    ax1.scatter(gt_trajectory[-1, 0], gt_trajectory[-1, 1], color="#1f77b4", s=100, marker="X", edgecolors="black", zorder=6, label="GPS End")
    ax1.scatter(model_trajectory[-1, 0], model_trajectory[-1, 1], color="#2ca02c", s=100, marker="s", edgecolors="black", zorder=6, label="Model End")

    ax1.set_title("2D Trajectory Comparison (Simulated GPS-Denied Period)", fontsize=13, fontweight="bold", pad=12)
    ax1.set_xlabel("East / X Position (meters)", fontsize=11, fontweight="bold")
    ax1.set_ylabel("North / Y Position (meters)", fontsize=11, fontweight="bold")
    ax1.grid(True, linestyle="--", alpha=0.5)
    ax1.legend(loc="best", framealpha=0.95, fontsize=9.5)

    # Right Subplot: Drift / Position Error Over Time
    ax2 = fig.add_subplot(gs[1])
    ax2.plot(time_sec, naive_errors, color="#d62728", linewidth=2.0, linestyle="--", label=f"Naive INS (Mean: {naive_mean_err:.0f}m)")
    ax2.plot(time_sec, model_errors, color="#2ca02c", linewidth=2.5, label=f"DeadReckoningNet (Mean: {model_mean_err:.0f}m)")
    ax2.fill_between(time_sec, model_errors, naive_errors, color="#2ca02c", alpha=0.15, label="Error Reduction Area")

    ax2.set_title("Cumulative Position Drift Over Time", fontsize=13, fontweight="bold", pad=12)
    ax2.set_xlabel("Time Since GPS Lost (seconds)", fontsize=11, fontweight="bold")
    ax2.set_ylabel("Position Error (meters)", fontsize=11, fontweight="bold")
    ax2.grid(True, linestyle="--", alpha=0.5)
    ax2.legend(loc="upper left", framealpha=0.95, fontsize=10)

    # Add Summary Banner Box in Right Plot
    banner_text = (
        f"PERFORMANCE HEADLINE\n"
        f"Mean Error Improvement : {improvement_mean:.1f}%\n"
        f"Final Drift Improvement: {improvement_final:.1f}%\n"
        f"Sequence Duration      : {time_sec[-1]/60.0:.1f} min"
    )
    ax2.text(
        0.52, 0.45, banner_text,
        transform=ax2.transAxes,
        fontsize=10,
        fontfamily="monospace",
        fontweight="bold",
        verticalalignment="center",
        bbox=dict(boxstyle="round,pad=0.6", facecolor="#f0fdf4", edgecolor="#22c55e", alpha=0.95),
    )

    plt.tight_layout()

    # Save to models/ and plots/
    model_plot_path = os.path.join(models_dir, "trajectory_comparison.png")
    plt.savefig(model_plot_path, dpi=250)
    print(f"[PLOT] Saved primary trajectory comparison to: {model_plot_path}")

    plots_path = os.path.join(plots_dir, "trajectory_comparison.png")
    plt.savefig(plots_path, dpi=250)
    print(f"[PLOT] Saved copy to: {plots_path}")
    plt.close(fig)

    # Save quantitative evaluation json
    eval_metrics = {
        "dataset": raw_file,
        "test_duration_seconds": float(time_sec[-1]),
        "total_distance_meters": float(total_dist_covered),
        "naive_ins": {
            "mean_position_error_m": float(naive_mean_err),
            "median_position_error_m": float(naive_median_err),
            "final_drift_error_m": float(naive_final_drift),
            "max_position_error_m": float(naive_max_err),
        },
        "dead_reckoning_net": {
            "mean_position_error_m": float(model_mean_err),
            "median_position_error_m": float(model_median_err),
            "final_drift_error_m": float(model_final_drift),
            "max_position_error_m": float(model_max_err),
        },
        "improvements": {
            "mean_position_error_reduction_percent": float(improvement_mean),
            "final_drift_reduction_percent": float(improvement_final),
        },
    }
    metrics_path = os.path.join(models_dir, "evaluation_metrics.json")
    with open(metrics_path, "w") as f:
        json.dump(eval_metrics, f, indent=2)
    print(f"[METRICS] Saved quantitative benchmark JSON to: {metrics_path}")

    return eval_metrics


def main():
    parser = argparse.ArgumentParser(description="Evaluate DeadReckoningNet Trajectory vs Naive INS")
    parser.add_argument("--data_dir", type=str, default="data/processed", help="Path to processed data directory")
    parser.add_argument("--models_dir", type=str, default="models", help="Directory where model is stored")
    parser.add_argument("--plots_dir", type=str, default="plots", help="Directory where comparison plots will be saved")
    parser.add_argument("--device", type=str, default="cpu", help="Device for evaluation ('cpu' or 'mps')")
    args = parser.parse_args()

    evaluate_dead_reckoning(
        data_dir=args.data_dir,
        models_dir=args.models_dir,
        plots_dir=args.plots_dir,
        device_name=args.device,
    )


if __name__ == "__main__":
    main()
