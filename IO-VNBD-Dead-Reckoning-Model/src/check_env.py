import sys

def main():
    print("=" * 60)
    print("Environment & Hardware Verification")
    print("=" * 60)
    print(f"Python version : {sys.version.split()[0]}")

    # Check PyTorch installation
    try:
        import torch
        print(f"PyTorch version: {torch.__version__}")
    except ImportError:
        print("[ERROR] PyTorch is not installed in the current environment.")
        print("Install via: pip install -r requirements.txt")
        sys.exit(1)

    # Check Apple Silicon MPS (Metal Performance Shaders) availability
    mps_built = torch.backends.mps.is_built()
    mps_available = torch.backends.mps.is_available()

    print(f"MPS built-in   : {mps_built}")
    print(f"MPS available  : {mps_available}")

    # Determine execution device
    device_str = "mps" if mps_available else "cpu"
    device = torch.device(device_str)
    print(f"Target device  : {device_str.upper()}")

    # Perform a quick tensor computation verification on device
    try:
        x = torch.ones((2, 3), device=device)
        y = x * 2 + 1
        print(f"Device test    : SUCCESS (Allocated and computed on {device_str})")
    except Exception as e:
        print(f"Device test    : FAILED ({e})")

    # Check auxiliary scientific libraries
    print("-" * 60)
    print("Auxiliary Libraries Status:")
    for pkg in ["numpy", "pandas", "matplotlib", "sklearn", "scipy"]:
        try:
            mod = __import__(pkg)
            version = getattr(mod, "__version__", "installed")
            print(f"  - {pkg:<12}: OK ({version})")
        except ImportError:
            print(f"  - {pkg:<12}: MISSING (pip install {pkg})")
    print("=" * 60)

if __name__ == "__main__":
    main()
