"""
api.py - FastAPI Microservice for Real-Time Dead-Reckoning Displacement
========================================================================

Exposes an HTTP REST API for mobile applications to submit real-time smartphone
sensor windows and receive predicted (dx, dy) vehicle displacement in meters.

Endpoints:
----------
- GET  /health   : Health check, returns service status and model configuration.
- POST /predict  : Main prediction endpoint. Accepts 1.0s window of phone IMU data.
- GET  /docs     : Interactive Swagger UI for live testing.
"""

from typing import List, Union, Optional
from fastapi import FastAPI, HTTPException, status
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field, validator
import numpy as np

from inference import predict_displacement, DeadReckoningPredictor


app = FastAPI(
    title="Vehicle Dead-Reckoning Navigation API",
    description=(
        "Deep Learning Dead-Reckoning API for GPS-denied vehicle navigation. "
        "Predicts 2D displacement (dx, dy in meters) using only smartphone IMU sensors."
    ),
    version="1.0.0",
)

# Enable CORS for local testing from mobile simulators, web frontends, etc.
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


class SensorSample(BaseModel):
    """A single sensor reading at a 10Hz time step (every 100ms)."""
    acc_x: float = Field(..., description="Screen-horizontal / lateral acceleration in m/s²")
    acc_y: float = Field(..., description="Screen-vertical / longitudinal acceleration in m/s²")
    acc_z: float = Field(..., description="Screen-normal acceleration in m/s² (includes gravity ~9.81)")
    gyro_x: float = Field(..., description="Pitch rate in rad/s")
    gyro_y: float = Field(..., description="Roll rate in rad/s")
    gyro_z: float = Field(..., description="Yaw rate in rad/s (drives heading changes)")


class WindowPayload(BaseModel):
    """
    JSON payload representing a 1.0-second sensor window (10 samples @ 10Hz).
    Accepts either a list of 10 SensorSample objects or a raw 10x6 matrix.
    """
    samples: List[Union[SensorSample, List[float]]] = Field(
        ...,
        description="Exactly 10 sensor samples sampled at 10Hz (1 sample every 100ms)",
    )

    @validator("samples")
    def validate_sample_count(cls, v):
        if len(v) != 10:
            raise ValueError(f"Window must contain exactly 10 time steps (1.0s @ 10Hz). Received {len(v)} samples.")
        return v


class PredictionResponse(BaseModel):
    dx: float = Field(..., description="Predicted East / X displacement in meters over the 1.0s window")
    dy: float = Field(..., description="Predicted North / Y displacement in meters over the 1.0s window")
    distance: float = Field(..., description="Total displacement Euclidean distance in meters")
    window_duration_seconds: float = Field(1.0, description="Duration covered by window")
    status: str = Field("ok", description="Status code")


@app.get("/", tags=["General"])
def root():
    """Welcome endpoint with service information."""
    return {
        "service": "Vehicle Dead-Reckoning Navigation API",
        "status": "online",
        "documentation": "/docs",
        "endpoints": {
            "health": "GET /health",
            "predict": "POST /predict",
        },
    }


@app.get("/health", tags=["Monitoring"])
def health_check():
    """Liveness probe verifying that the model and scaler are loaded."""
    try:
        # Dry-run test sample
        dummy = np.zeros((10, 6), dtype=np.float32)
        dummy[:, 2] = 9.81
        dx, dy = predict_displacement(dummy)
        return {
            "status": "healthy",
            "model_loaded": True,
            "window_size_samples": 10,
            "sampling_rate_hz": 10.0,
            "test_inference": "passed",
        }
    except Exception as e:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail=f"Model engine failed self-test: {str(e)}",
        )


@app.post("/predict", response_model=PredictionResponse, tags=["Inference"])
def predict(payload: WindowPayload):
    """
    Predict vehicle displacement (dx, dy in meters) from a 1.0-second phone sensor window.

    Request format:
      JSON containing 10 samples of phone IMU data sampled at 10Hz.
    """
    try:
        # Convert Pydantic samples to raw 10x6 matrix
        raw_matrix = []
        for s in payload.samples:
            if isinstance(s, SensorSample):
                raw_matrix.append([s.acc_x, s.acc_y, s.acc_z, s.gyro_x, s.gyro_y, s.gyro_z])
            elif isinstance(s, list):
                if len(s) != 6:
                    raise ValueError(f"Raw array sample must have 6 elements [acc_x, acc_y, acc_z, gyro_x, gyro_y, gyro_z], got {len(s)}")
                raw_matrix.append(s)

        window_arr = np.array(raw_matrix, dtype=np.float32)
        dx, dy = predict_displacement(window_arr)
        distance = float(np.sqrt(dx**2 + dy**2))

        return PredictionResponse(
            dx=dx,
            dy=dy,
            distance=distance,
            window_duration_seconds=1.0,
            status="ok",
        )
    except ValueError as val_err:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(val_err))
    except Exception as exc:
        raise HTTPException(status_code=status.HTTP_500_INTERNAL_SERVER_ERROR, detail=f"Inference error: {str(exc)}")


if __name__ == "__main__":
    import uvicorn
    print("Starting Dead-Reckoning API server on http://localhost:8000 ...")
    uvicorn.run("api:app", host="0.0.0.0", port=8000, reload=True)
