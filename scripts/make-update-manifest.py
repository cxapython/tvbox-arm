#!/usr/bin/env python3
"""Generate the update contract from the verified signed APK, not hand-maintained version fields."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess


def manifest(apk, badging, certificates, notes=""):
    package = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
    sdk = re.search(r"sdkVersion:'(\d+)'", badging)
    native = re.search(r"native-code:\s*(.+)", badging)
    signers = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})", certificates)
    if not package or not sdk or not native or not signers:
        raise ValueError("Cannot read APK identity, minSdk, native ABI or signing certificate")
    application_id, code, name = package.groups()
    if application_id != "com.yingxia.tv" or re.findall(r"'([^']+)'", native.group(1)) != ["armeabi-v7a"]:
        raise ValueError("Not the ARM32-only Yingxia TV package")
    if len(signers) != 1 or not re.fullmatch(r"[0-9A-Za-z._-]+", name):
        raise ValueError("Unsupported signing identity or version name")
    expected = os.environ.get("YINGXIA_EXPECTED_CERT_SHA256", "").lower().replace(":", "")
    if expected and expected != signers[0].lower():
        raise ValueError("APK signing certificate does not match YINGXIA_EXPECTED_CERT_SHA256")
    size = apk.stat().st_size
    if not 0 < size <= 100 * 1024 * 1024:
        raise ValueError("APK outside updater size limits")
    tag = "v" + name
    return {
        "schema": 1, "applicationId": application_id, "abi": "armeabi-v7a",
        "versionCode": int(code), "versionName": name, "minSdk": int(sdk.group(1)),
        "tag": tag, "size": size, "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
        "certificateSha256": signers[0].lower(),
        "apkUrl": f"https://github.com/cxapython/tvbox-arm/releases/download/{tag}/yingxia-tv-arm32.apk",
        "notes": notes[:4000],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--build-tools", type=Path, required=True)
    parser.add_argument("--notes", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    badging = subprocess.check_output([str(args.build_tools / "aapt"), "dump", "badging", str(args.apk)], text=True)
    certs = subprocess.check_output([str(args.build_tools / "apksigner"), "verify", "--print-certs", str(args.apk)], text=True)
    notes = args.notes.read_text() if args.notes and args.notes.is_file() else ""
    result = manifest(args.apk, badging, certs, notes)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print(f"Update manifest verified: {result['versionName']} ({result['versionCode']})")


if __name__ == "__main__":
    main()
