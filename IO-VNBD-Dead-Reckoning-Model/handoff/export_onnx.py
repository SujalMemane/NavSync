import os
import sys
import torch
import numpy as np

base_dir = os.path.dirname(os.path.abspath(__file__))
sys.path.append(base_dir)

from model import DeadReckoningNet
from inference import DeadReckoningPredictor

def export():
    model_path = os.path.join(base_dir, "best_model.pt")
    onnx_path = os.path.join(base_dir, "model.onnx")

    checkpoint = torch.load(model_path, map_location="cpu")
    hp = checkpoint.get("hyperparameters", {})

    model = DeadReckoningNet(
        input_size=hp.get("input_size", 4),
        hidden_size=hp.get("hidden_size", 64),
        num_lstm_layers=hp.get("num_lstm_layers", 2),
        dropout=hp.get("dropout", 0.1),
    )
    model.load_state_dict(checkpoint["model_state_dict"])
    model.eval()

    dummy_input = torch.randn(1, 10, 4)

    # 1. Python prediction on dummy sample
    with torch.no_grad():
        py_output = model(dummy_input).numpy()

    # 2. Export ONNX using legacy exporter (dynamo=False)
    try:
        torch.onnx.export(
            model,
            dummy_input,
            onnx_path,
            export_params=True,
            opset_version=14,
            do_constant_folding=True,
            input_names=["input"],
            output_names=["output"],
            dynamic_axes={"input": {0: "batch_size"}, "output": {0: "batch_size"}},
            dynamo=False
        )
        print(f"[SUCCESS] Exported ONNX model to: {onnx_path}")
    except Exception as e:
        print(f"[EXPORT ERROR] {e}")

    # 3. Test ONNX Runtime inference vs PyTorch reference
    import onnxruntime as ort
    session = ort.InferenceSession(onnx_path)
    onnx_inputs = {"input": dummy_input.numpy()}
    onnx_output = session.run(None, onnx_inputs)[0]

    abs_diff = np.abs(py_output - onnx_output)
    print(f"PyTorch Output : {py_output}")
    print(f"ONNX Output    : {onnx_output}")
    print(f"Max Abs Diff   : {np.max(abs_diff)}")

    if np.max(abs_diff) < 1e-4:
        print("[PASSED] ONNX GOLDEN TEST PASSED (Absolute difference < 1e-4)")
    else:
        print("[FAILED] ONNX GOLDEN TEST FAILED")

if __name__ == "__main__":
    export()
