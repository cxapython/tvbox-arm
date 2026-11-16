#!/usr/bin/env python3
"""Build an inert, same-debug-key APK for on-device updater verification (never a release)."""
import argparse
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--apk', type=Path, required=True)
parser.add_argument('--sdk', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
tools = args.sdk / 'build-tools/34.0.0'
badging = subprocess.check_output([str(tools / 'aapt'), 'dump', 'badging', str(args.apk)], text=True)
version = re.search(r"versionCode='(\d+)' versionName='([^']+)'", badging)
if not version:
    raise ValueError('Cannot read debug APK identity')
code, name = int(version[1]) + 1, version[2] + '.fixture'
args.output.mkdir(parents=True, exist_ok=True)
with tempfile.TemporaryDirectory() as directory:
    root = Path(directory)
    xml = root / 'AndroidManifest.xml'
    xml.write_text(f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android"
        package="com.yingxia.tv" android:versionCode="{code}" android:versionName="{name}">
        <uses-sdk android:minSdkVersion="21" android:targetSdkVersion="34" />
        <application android:label="Updater test fixture - do not install" android:hasCode="false" />
    </manifest>''')
    unsigned = root / 'unsigned.apk'
    subprocess.run([str(tools / 'aapt'), 'package', '-f', '-M', str(xml), '-I',
                    str(args.sdk / 'platforms/android-34/android.jar'), '-F', str(unsigned)], check=True)
    with zipfile.ZipFile(args.apk) as source, zipfile.ZipFile(unsigned, 'a') as destination:
        destination.writestr('lib/armeabi-v7a/libyingxia-script.so', source.read('lib/armeabi-v7a/libyingxia-script.so'))
    aligned = root / 'aligned.apk'
    subprocess.run([str(tools / 'zipalign'), '-f', '4', str(unsigned), str(aligned)], check=True)
    signed = args.output / 'update-fixture.apk'
    subprocess.run([str(tools / 'apksigner'), 'sign', '--ks', str(Path.home() / '.android/debug.keystore'),
                    '--ks-key-alias', 'androiddebugkey', '--ks-pass', 'pass:android', '--key-pass', 'pass:android',
                    '--out', str(signed), str(aligned)], check=True)
    spec = importlib.util.spec_from_file_location('update_manifest', Path(__file__).with_name('make-update-manifest.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    badging = subprocess.check_output([str(tools / 'aapt'), 'dump', 'badging', str(signed)], text=True)
    certs = subprocess.check_output([str(tools / 'apksigner'), 'verify', '--print-certs', str(signed)], text=True)
    # Expected production signing constraints do not apply to this isolated debug fixture.
    import os
    os.environ.pop('YINGXIA_EXPECTED_CERT_SHA256', None)
    (args.output / 'update-fixture.json').write_text(json.dumps(module.manifest(signed, badging, certs)))
