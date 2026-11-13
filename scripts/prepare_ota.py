"""Prepare static HTTPS update files locally. This command never publishes them."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("apk", type=Path)
parser.add_argument("directory", type=Path)
args = parser.parse_args()
sdk = Path(os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or Path.home() / "Library/Android/sdk")
tools = sdk / "build-tools/36.0.0"
subprocess.run([tools / "apksigner", "verify", "--min-sdk-version", "27", "--max-sdk-version", "27", args.apk],
               check=True)
badging = subprocess.check_output([tools / "aapt", "dump", "badging", args.apk], text=True)
package = re.search(r"^package: name='([^']+)' versionCode='([0-9]+)'", badging, re.M)
if not package or package[1] != "local.jile.v5":
    parser.error("请选择鸡肋OS V5 APK")

version = int(package[2])
size = args.apk.stat().st_size
if not 0 < size <= 160 * 1024 * 1024:
    parser.error("APK 大小超出设备限制")
args.directory.mkdir(parents=True, exist_ok=True)
with tempfile.TemporaryDirectory(prefix=".release-", dir=args.directory) as work:
    staged = Path(work) / "update.apk"
    shutil.copyfile(args.apk, staged)
    sha = hashlib.sha256()
    with staged.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            sha.update(chunk)
    filename = f"jile-v5-{version}-{sha.hexdigest()[:16]}.apk"
    os.replace(staged, args.directory / filename)
    manifest = Path(work) / "release.json"
    manifest.write_text(json.dumps({
        "versionCode": version, "size": size, "sha256": sha.hexdigest(), "apk": filename
    }, indent=2) + "\n")
    os.replace(manifest, args.directory / manifest.name)
print(args.directory / "release.json")
