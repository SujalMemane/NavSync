"""
model.py - 1D-CNN + LSTM Dead-Reckoning Neural Network
=====================================================

Defines the DeadReckoningNet PyTorch architecture for vehicle displacement
prediction from smartphone inertial sensor windows (IONet-style).

Architecture Overview:
----------------------
1. 1D-CNN Temporal Feature Extractor:
   - Captures localized high-frequency inertial patterns, engine vibration signatures,
     and abrupt accelerations across consecutive time steps.
   - Preserves temporal sequence length with padding.
2. Recurrent Sequence Encoder (LSTM):
   - Integrates temporal evolution, sequential momentum, and vehicle dynamics
     over the 1.0s window.
3. Fully-Connected Regression Head:
   - Projects temporal latent features into 2D Cartesian displacement (dx, dy) in meters.

Input shape:  [batch_size, window_size, num_features]  (e.g., [batch, 10, 4])
Output shape: [batch_size, 2]                          (dx, dy)
"""

import torch
import torch.nn as nn
from typing import Tuple


class DeadReckoningNet(nn.Module):
    """
    Hybrid 1D-CNN + LSTM Network for Inertial Dead-Reckoning Displacement Estimation.

    Args:
        input_size (int): Number of input IMU feature channels (default: 4 for orientation-invariant features).
        conv_channels (Tuple[int, int]): Number of channels in the two 1D convolution layers.
        kernel_size (int): Kernel size for temporal 1D convolutions.
        hidden_size (int): Number of features in LSTM hidden state.
        num_lstm_layers (int): Number of recurrent LSTM layers.
        fc_hidden (int): Hidden dimension for the final regression MLP head.
        dropout (float): Dropout probability for regularization.
        bidirectional (bool): Whether to use bidirectional LSTM (default: False for causal sequential inference).
    """

    def __init__(
        self,
        input_size: int = 4,
        conv_channels: Tuple[int, int] = (32, 64),
        kernel_size: int = 3,
        hidden_size: int = 64,
        num_lstm_layers: int = 2,
        fc_hidden: int = 32,
        dropout: float = 0.1,
        bidirectional: bool = False,
    ):
        super().__init__()
        self.input_size = input_size
        self.hidden_size = hidden_size
        self.num_lstm_layers = num_lstm_layers
        self.bidirectional = bidirectional

        # 1. 1D-CNN Feature Extractor (Processes temporal signals across feature channels)
        self.conv1 = nn.Conv1d(
            in_channels=input_size,
            out_channels=conv_channels[0],
            kernel_size=kernel_size,
            padding="same",
        )
        self.bn1 = nn.BatchNorm1d(conv_channels[0])
        self.act1 = nn.ReLU()
        self.drop1 = nn.Dropout(dropout)

        self.conv2 = nn.Conv1d(
            in_channels=conv_channels[0],
            out_channels=conv_channels[1],
            kernel_size=kernel_size,
            padding="same",
        )
        self.bn2 = nn.BatchNorm1d(conv_channels[1])
        self.act2 = nn.ReLU()
        self.drop2 = nn.Dropout(dropout)

        # 2. Recurrent Sequence Model (LSTM)
        self.lstm = nn.LSTM(
            input_size=conv_channels[1],
            hidden_size=hidden_size,
            num_layers=num_lstm_layers,
            batch_first=True,
            dropout=dropout if num_lstm_layers > 1 else 0.0,
            bidirectional=bidirectional,
        )

        # 3. Fully-Connected Regression Head
        lstm_out_dim = hidden_size * (2 if bidirectional else 1)
        self.fc = nn.Sequential(
            nn.Linear(lstm_out_dim, fc_hidden),
            nn.ReLU(),
            nn.Dropout(dropout),
            nn.Linear(fc_hidden, 2),  # Outputs (dx, dy) in meters
        )

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        """
        Forward pass.

        Args:
            x (torch.Tensor): Input tensor of shape [batch_size, window_size, num_features]

        Returns:
            torch.Tensor: Predicted displacement [batch_size, 2] (dx, dy) in meters
        """
        # x: [batch, window_size, num_features]
        # Conv1d expects [batch, channels, length] -> permute
        x = x.transpose(1, 2)

        # 1D Convolution block 1
        x = self.conv1(x)
        x = self.bn1(x)
        x = self.act1(x)
        x = self.drop1(x)

        # 1D Convolution block 2
        x = self.conv2(x)
        x = self.bn2(x)
        x = self.act2(x)
        x = self.drop2(x)

        # Transpose back to [batch, window_size, conv_out_channels] for LSTM
        x = x.transpose(1, 2)

        # Pass through LSTM
        lstm_out, _ = self.lstm(x)

        # Take representation at final time step of window
        last_step = lstm_out[:, -1, :]  # [batch, lstm_out_dim]

        # Final regression to (dx, dy)
        displacement = self.fc(last_step)  # [batch, 2]

        return displacement


def count_parameters(model: nn.Module) -> Tuple[int, int]:
    """Return total parameter count and trainable parameter count."""
    total_params = sum(p.numel() for p in model.parameters())
    trainable_params = sum(p.numel() for p in model.parameters() if p.requires_grad)
    return total_params, trainable_params


def print_model_summary(model: DeadReckoningNet, window_size: int = 10, batch_size: int = 16):
    """Print architectural breakdown, layer dimensions, and parameter counts."""
    total, trainable = count_parameters(model)
    print("=" * 75)
    print(f"MODEL SUMMARY: {model.__class__.__name__}")
    print("=" * 75)
    print(f"Input features    : {model.input_size}")
    print(f"Window size       : {window_size} samples (1.0s @ 10Hz)")
    print(f"Hidden size       : {model.hidden_size}")
    print(f"LSTM layers       : {model.num_lstm_layers}")
    print(f"Bidirectional     : {model.bidirectional}")
    print(f"Total parameters  : {total:,} ({total * 4 / 1024:.2f} KB in FP32)")
    print(f"Trainable params  : {trainable:,}")
    print("-" * 75)
    print("Layer Breakdown:")
    for name, module in model.named_children():
        layer_params = sum(p.numel() for p in module.parameters())
        print(f"  • {name:<12} : {module.__class__.__name__:<16} ({layer_params:,} parameters)")
    print("=" * 75)


def sanity_check():
    """Sanity check: instantiate model and verify dummy batch forward pass."""
    batch_size = 16
    window_size = 10
    num_features = 4  # Matches Phase 2 orientation-invariant feature set

    print("\n--- Running DeadReckoningNet Sanity Check ---")

    # Determine hardware device (Apple Silicon MPS or CPU fallback)
    if torch.backends.mps.is_available():
        device = torch.device("mps")
    elif torch.cuda.is_available():
        device = torch.device("cuda")
    else:
        device = torch.device("cpu")

    print(f"Target execution device: {device.type.upper()}")

    # Instantiate model
    model = DeadReckoningNet(
        input_size=num_features,
        conv_channels=(32, 64),
        kernel_size=3,
        hidden_size=64,
        num_lstm_layers=2,
        fc_hidden=32,
        dropout=0.1,
        bidirectional=False,
    ).to(device)

    print_model_summary(model, window_size=window_size, batch_size=batch_size)

    # Generate dummy batch
    dummy_input = torch.randn(batch_size, window_size, num_features, device=device)
    print(f"\nDummy input tensor shape : {list(dummy_input.shape)} [batch, window_size, features]")

    # Run forward pass
    model.eval()
    with torch.no_grad():
        output = model(dummy_input)

    print(f"Forward output shape     : {list(output.shape)} [batch, 2] -> (dx, dy)")
    print(f"Sample prediction row 0  : dx = {output[0, 0].item():.4f} m, dy = {output[0, 1].item():.4f} m")

    # Assertions
    assert output.shape == (batch_size, 2), f"Expected output shape {(batch_size, 2)}, got {output.shape}"
    assert not torch.isnan(output).any(), "NaN detected in forward pass output"
    print("\n[PASSED] Sanity check completed successfully! Output shape matches [batch, 2].")


if __name__ == "__main__":
    sanity_check()
