"""
train.py - Training Pipeline for DeadReckoningNet on IO-VNBD Data
================================================================

Trains the 1D-CNN + LSTM DeadReckoningNet model to predict 2D vehicle
displacement (dx, dy in meters) using normalized smartphone IMU windows.

Key Features:
-------------
1. Loads preprocessed datasets from data/processed/ (X_train, y_train, X_val, y_val).
2. Hardware Acceleration: Defaults to Apple Silicon MPS ('mps' on M1 Pro), with CPU fallback.
3. Optimization: Adam optimizer (lr=1e-3) + ReduceLROnPlateau learning rate scheduler.
4. Loss & Tracking: MSE loss for optimization + Mean Absolute Error (MAE in meters) tracking.
5. Early Stopping: Prevents overfitting and saves compute (default patience: 10 epochs).
6. Checkpointing: Saves best weights (lowest validation loss) to models/best_model.pt.
7. Diagnostics: Saves training vs. validation loss/MAE curves to models/loss_curve.png.
8. Execution Timing: Measures and reports total runtime.
"""

import os
import sys
import time
import argparse
import json
import numpy as np
import matplotlib
matplotlib.use("Agg")  # Non-interactive backend for headless / server plotting
import matplotlib.pyplot as plt

import torch
import torch.nn as nn
from torch.utils.data import TensorDataset, DataLoader

from model import DeadReckoningNet, count_parameters


def get_device() -> torch.device:
    """Detect and select best available hardware accelerator."""
    if torch.backends.mps.is_available():
        device = torch.device("mps")
        print("[DEVICE] Using Apple Silicon GPU acceleration (MPS - Metal Performance Shaders).")
    elif torch.cuda.is_available():
        device = torch.device("cuda")
        print("[DEVICE] Using NVIDIA CUDA GPU acceleration.")
    else:
        device = torch.device("cpu")
        print("[DEVICE] Accelerator not available; falling back to CPU.")
    return device


def load_data(data_dir: str = "data/processed") -> tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray, dict]:
    """Load train/val data and metadata from processed data directory."""
    metadata_path = os.path.join(data_dir, "metadata.json")
    if not os.path.exists(metadata_path):
        raise FileNotFoundError(f"Metadata file not found at {metadata_path}. Please run src/preprocess.py first.")

    with open(metadata_path, "r") as f:
        metadata = json.load(f)

    # Prefer loading from individual .npy files (or dataset.npz)
    npz_path = os.path.join(data_dir, "dataset.npz")
    if os.path.exists(npz_path):
        data = np.load(npz_path)
        X_train = data["X_train"]
        y_train = data["y_train"]
        X_val = data["X_val"]
        y_val = data["y_val"]
    else:
        X_train = np.load(os.path.join(data_dir, "X_train.npy"))
        y_train = np.load(os.path.join(data_dir, "y_train.npy"))
        X_val = np.load(os.path.join(data_dir, "X_val.npy"))
        y_val = np.load(os.path.join(data_dir, "y_val.npy"))

    return X_train, y_train, X_val, y_val, metadata


def compute_metrics(predictions: torch.Tensor, targets: torch.Tensor) -> tuple[float, float, float]:
    """
    Compute MSE, Component-wise L1 MAE, and Euclidean Distance Error (in meters).
    """
    mse = nn.functional.mse_loss(predictions, targets).item()
    l1_mae = nn.functional.l1_loss(predictions, targets).item()
    # Euclidean displacement error: sqrt((dx_pred - dx_true)^2 + (dy_pred - dy_true)^2)
    euclidean_error = torch.norm(predictions - targets, dim=1).mean().item()
    return mse, l1_mae, euclidean_error


def train_one_epoch(
    model: nn.Module,
    dataloader: DataLoader,
    optimizer: torch.optim.Optimizer,
    criterion: nn.Module,
    device: torch.device,
) -> tuple[float, float, float]:
    """Train model for one epoch over the training dataset."""
    model.train()
    total_loss = 0.0
    total_l1 = 0.0
    total_euclidean = 0.0
    total_samples = 0

    for batch_X, batch_y in dataloader:
        batch_X = batch_X.to(device)
        batch_y = batch_y.to(device)
        batch_size = batch_X.size(0)

        optimizer.zero_grad()
        predictions = model(batch_X)
        loss = criterion(predictions, batch_y)
        loss.backward()

        # Gradient clipping to stabilize LSTM training
        nn.utils.clip_grad_norm_(model.parameters(), max_norm=5.0)
        optimizer.step()

        mse, l1, euc = compute_metrics(predictions, batch_y)
        total_loss += mse * batch_size
        total_l1 += l1 * batch_size
        total_euclidean += euc * batch_size
        total_samples += batch_size

    return (
        total_loss / total_samples,
        total_l1 / total_samples,
        total_euclidean / total_samples,
    )


@torch.no_grad()
def evaluate(
    model: nn.Module,
    dataloader: DataLoader,
    criterion: nn.Module,
    device: torch.device,
) -> tuple[float, float, float]:
    """Evaluate model on validation or test dataset."""
    model.eval()
    total_loss = 0.0
    total_l1 = 0.0
    total_euclidean = 0.0
    total_samples = 0

    for batch_X, batch_y in dataloader:
        batch_X = batch_X.to(device)
        batch_y = batch_y.to(device)
        batch_size = batch_X.size(0)

        predictions = model(batch_X)
        mse, l1, euc = compute_metrics(predictions, batch_y)

        total_loss += mse * batch_size
        total_l1 += l1 * batch_size
        total_euclidean += euc * batch_size
        total_samples += batch_size

    return (
        total_loss / total_samples,
        total_l1 / total_samples,
        total_euclidean / total_samples,
    )


def plot_training_curves(history: dict, save_path: str):
    """Plot and save training and validation loss/MAE curves."""
    epochs = range(1, len(history["train_loss"]) + 1)

    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(14, 5), facecolor="white")

    # Subplot 1: MSE Loss
    ax1.plot(epochs, history["train_loss"], label="Train Loss (MSE)", color="#1f77b4", linewidth=1.8)
    ax1.plot(epochs, history["val_loss"], label="Val Loss (MSE)", color="#ff7f0e", linewidth=1.8, linestyle="--")
    ax1.set_title("Training & Validation Loss (MSE)", fontsize=12, fontweight="bold")
    ax1.set_xlabel("Epoch", fontweight="bold")
    ax1.set_ylabel("Mean Squared Error (m²)", fontweight="bold")
    ax1.grid(True, linestyle="--", alpha=0.5)
    ax1.legend(loc="upper right")

    # Subplot 2: Euclidean Positioning Error (MAE in meters)
    ax2.plot(epochs, history["train_euc_mae"], label="Train Displacement Error", color="#2ca02c", linewidth=1.8)
    ax2.plot(epochs, history["val_euc_mae"], label="Val Displacement Error", color="#d62728", linewidth=1.8, linestyle="--")
    ax2.set_title("Mean Displacement Error (Euclidean MAE)", fontsize=12, fontweight="bold")
    ax2.set_xlabel("Epoch", fontweight="bold")
    ax2.set_ylabel("Error per 1.0s Window (meters)", fontweight="bold")
    ax2.grid(True, linestyle="--", alpha=0.5)
    ax2.legend(loc="upper right")

    plt.tight_layout()
    plt.savefig(save_path, dpi=200)
    plt.close(fig)
    print(f"[PLOT] Training curves successfully saved to: {save_path}")


def train_model(
    data_dir: str = "data/processed",
    models_dir: str = "models",
    epochs: int = 50,
    batch_size: int = 64,
    learning_rate: float = 1e-3,
    patience: int = 10,
    hidden_size: int = 64,
    num_lstm_layers: int = 2,
    dropout: float = 0.1,
) -> dict:
    """
    Main training routine.

    Hyperparameter Choices:
    -----------------------
    - batch_size=64: Ideal sweet spot for Apple Silicon MPS (balances GPU occupancy without memory bottlenecks).
    - lr=1e-3: Standard starting learning rate for Adam on regression tasks; paired with ReduceLROnPlateau.
    - patience=10: Early stopping threshold ensuring training halts automatically when validation performance plateaus.
    - hidden_size=64, num_lstm_layers=2: Compact capacity (~75k parameters) fast enough to train in ~1-2 minutes.
    """
    os.makedirs(models_dir, exist_ok=True)
    device = get_device()

    # 1. Load data
    print(f"\n[DATA] Loading processed arrays from {data_dir}...")
    X_train, y_train, X_val, y_val, metadata = load_data(data_dir)
    print(f"       Train set: {X_train.shape} -> {y_train.shape}")
    print(f"       Val set  : {X_val.shape} -> {y_val.shape}")

    # 2. Construct DataLoaders
    train_dataset = TensorDataset(torch.from_numpy(X_train), torch.from_numpy(y_train))
    val_dataset = TensorDataset(torch.from_numpy(X_val), torch.from_numpy(y_val))

    # Shuffle training samples; validation order stays preserved
    train_loader = DataLoader(train_dataset, batch_size=batch_size, shuffle=True, drop_last=False)
    val_loader = DataLoader(val_dataset, batch_size=batch_size, shuffle=False)

    # 3. Instantiate Model
    num_features = X_train.shape[-1]
    window_size = X_train.shape[1]

    model = DeadReckoningNet(
        input_size=num_features,
        conv_channels=(32, 64),
        kernel_size=3,
        hidden_size=hidden_size,
        num_lstm_layers=num_lstm_layers,
        fc_hidden=32,
        dropout=dropout,
        bidirectional=False,
    ).to(device)

    total_params, _ = count_parameters(model)
    print(f"[MODEL] DeadReckoningNet initialized ({total_params:,} parameters).")

    # 4. Loss, Optimizer, and Scheduler
    criterion = nn.MSELoss()
    optimizer = torch.optim.Adam(model.parameters(), lr=learning_rate, weight_decay=1e-5)
    scheduler = torch.optim.lr_scheduler.ReduceLROnPlateau(
        optimizer, mode="min", factor=0.5, patience=3, min_lr=1e-5
    )

    # 5. Training Loop Setup
    best_val_loss = float("inf")
    best_val_mae = float("inf")
    best_epoch = 0
    patience_counter = 0
    checkpoint_path = os.path.join(models_dir, "best_model.pt")

    history = {
        "train_loss": [],
        "val_loss": [],
        "train_l1_mae": [],
        "val_l1_mae": [],
        "train_euc_mae": [],
        "val_euc_mae": [],
        "lr": [],
    }

    print("\n" + "=" * 88)
    print(f"{'Epoch':<7} | {'Train MSE':<11} | {'Val MSE':<11} | {'Train MAE (m)':<14} | {'Val MAE (m)':<14} | {'LR':<9} | {'Status'}")
    print("=" * 88)

    start_time = time.time()

    for epoch in range(1, epochs + 1):
        epoch_start = time.time()
        current_lr = optimizer.param_groups[0]["lr"]

        # Train
        train_mse, train_l1, train_euc = train_one_epoch(model, train_loader, optimizer, criterion, device)

        # Validate
        val_mse, val_l1, val_euc = evaluate(model, val_loader, criterion, device)

        # Step LR scheduler based on validation MSE loss
        scheduler.step(val_mse)

        # Record history
        history["train_loss"].append(train_mse)
        history["val_loss"].append(val_mse)
        history["train_l1_mae"].append(train_l1)
        history["val_l1_mae"].append(val_l1)
        history["train_euc_mae"].append(train_euc)
        history["val_euc_mae"].append(val_euc)
        history["lr"].append(current_lr)

        # Check for checkpoint improvement
        status_msg = ""
        if val_mse < best_val_loss:
            best_val_loss = val_mse
            best_val_mae = val_euc
            best_epoch = epoch
            patience_counter = 0

            # Save checkpoint
            checkpoint = {
                "epoch": epoch,
                "model_state_dict": model.state_dict(),
                "optimizer_state_dict": optimizer.state_dict(),
                "val_mse": val_mse,
                "val_euc_mae": val_euc,
                "metadata": metadata,
                "hyperparameters": {
                    "input_size": num_features,
                    "window_size": window_size,
                    "hidden_size": hidden_size,
                    "num_lstm_layers": num_lstm_layers,
                    "batch_size": batch_size,
                    "learning_rate": learning_rate,
                    "dropout": dropout,
                },
            }
            torch.save(checkpoint, checkpoint_path)
            status_msg = "⭐ Best saved"
        else:
            patience_counter += 1
            status_msg = f"patience ({patience_counter}/{patience})"

        epoch_time = time.time() - epoch_start
        print(
            f"{epoch:>3}/{epochs:<3} | "
            f"{train_mse:>11.5f} | "
            f"{val_mse:>11.5f} | "
            f"{train_euc:>14.4f} | "
            f"{val_euc:>14.4f} | "
            f"{current_lr:>9.1e} | "
            f"{status_msg} ({epoch_time:.1f}s)"
        )

        # Early Stopping Trigger
        if patience_counter >= patience:
            print(f"\n[EARLY STOPPING] Validation loss did not improve for {patience} consecutive epochs.")
            print(f"                 Terminating training early at epoch {epoch}.")
            break

    total_time = time.time() - start_time
    print("=" * 88)
    print(f"\n[TRAINING COMPLETE]")
    print(f"Total training time   : {total_time:.2f} seconds ({total_time / 60.0:.2f} minutes)")
    print(f"Average time per epoch: {total_time / len(history['train_loss']):.2f} seconds")
    print(f"Best Epoch            : {best_epoch}")
    print(f"Best Val MSE          : {best_val_loss:.6f} m²")
    print(f"Best Val Euclidean MAE: {best_val_mae:.4f} meters per window")
    print(f"Checkpoint saved to   : {checkpoint_path}")

    # Plot and save curves
    plot_path = os.path.join(models_dir, "loss_curve.png")
    plot_training_curves(history, plot_path)

    # Save training history log
    log_path = os.path.join(models_dir, "train_history.json")
    with open(log_path, "w") as f:
        json.dump({
            "total_time_seconds": total_time,
            "best_epoch": best_epoch,
            "best_val_loss": best_val_loss,
            "best_val_mae": best_val_mae,
            "epochs_completed": len(history["train_loss"]),
            "history": history,
        }, f, indent=2)

    return history


def main():
    parser = argparse.ArgumentParser(description="Train DeadReckoningNet Model on IO-VNBD IMU Windows")
    parser.add_argument("--data_dir", type=str, default="data/processed", help="Path to processed data directory")
    parser.add_argument("--models_dir", type=str, default="models", help="Directory to save model checkpoints & plots")
    parser.add_argument("--epochs", type=int, default=50, help="Maximum number of training epochs (default: 50)")
    parser.add_argument("--batch_size", type=int, default=64, help="Batch size for DataLoader (default: 64)")
    parser.add_argument("--lr", type=float, default=1e-3, help="Initial Adam learning rate (default: 0.001)")
    parser.add_argument("--patience", type=int, default=10, help="Early stopping patience in epochs (default: 10)")
    parser.add_argument("--hidden_size", type=int, default=64, help="LSTM hidden size (default: 64)")
    parser.add_argument("--num_lstm_layers", type=int, default=2, help="Number of LSTM layers (default: 2)")
    parser.add_argument("--dropout", type=float, default=0.1, help="Dropout rate (default: 0.1)")
    args = parser.parse_args()

    train_model(
        data_dir=args.data_dir,
        models_dir=args.models_dir,
        epochs=args.epochs,
        batch_size=args.batch_size,
        learning_rate=args.lr,
        patience=args.patience,
        hidden_size=args.hidden_size,
        num_lstm_layers=args.num_lstm_layers,
        dropout=args.dropout,
    )


if __name__ == "__main__":
    main()
