"""
Script to download sample smartphone sensor sequences from the IO-VNBD dataset.

Downloads directly from GitHub's media CDN to handle Git LFS files seamlessly.
Target sequences:
- S-M.csv   (Route M - Smartphone Sensors)
- S-S1.csv  (Route S1 - Smartphone Sensors)

Optional Ground Truth (Vehicle reference):
- V-M.csv, V-S1.csv (Not used as model input; kept for reference/evaluation if needed)
"""

import os
import urllib.request
import sys

BASE_URL = (
    "https://media.githubusercontent.com/media/onyekpeu/IO-VNBD/master/"
    "Synchronised%20V%20abd%20S%20datasets/Uncategorised%20IOVNB%20Dataset"
)

DEST_DIR = os.path.join(os.path.dirname(os.path.dirname(__file__)), "data")

SAMPLE_FILES = [
    ("S-Dataset/S-M.csv", "S-M.csv"),
    ("S-Dataset/S-S1.csv", "S-S1.csv"),
]

def download_file(rel_path: str, local_name: str):
    os.makedirs(DEST_DIR, exist_ok=True)
    target_path = os.path.join(DEST_DIR, local_name)
    url = f"{BASE_URL}/{rel_path}"

    if os.path.exists(target_path) and os.path.getsize(target_path) > 1000:
        print(f"[SKIP] {local_name} already exists ({os.path.getsize(target_path):,} bytes).")
        return

    print(f"Downloading {local_name} from:\n  {url}")
    try:
        def reporthook(count, block_size, total_size):
            if total_size > 0:
                percent = int(count * block_size * 100 / total_size)
                mb_downloaded = (count * block_size) / (1024 * 1024)
                mb_total = total_size / (1024 * 1024)
                sys.stdout.write(f"\r  Progress: {percent}% ({mb_downloaded:.1f}/{mb_total:.1f} MB)")
                sys.stdout.flush()

        urllib.request.urlretrieve(url, target_path, reporthook)
        print(f"\n[DONE] Saved to: {target_path} ({os.path.getsize(target_path):,} bytes)")
    except Exception as e:
        print(f"\n[ERROR] Failed to download {local_name}: {e}")
        if os.path.exists(target_path):
            os.remove(target_path)

if __name__ == "__main__":
    print("=" * 60)
    print("IO-VNBD Smartphone Data Downloader")
    print(f"Destination folder: {DEST_DIR}")
    print("=" * 60)
    for rel_path, local_name in SAMPLE_FILES:
        download_file(rel_path, local_name)
    print("=" * 60)
