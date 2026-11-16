#!/usr/bin/env python3
import importlib.util
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("make_update_manifest", Path(__file__).with_name("make-update-manifest.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

class UpdateManifestTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.apk = Path(self.directory.name) / "test.apk"
        self.apk.write_bytes(b"signed fixture")
        self.badging = "package: name='com.yingxia.tv' versionCode='5' versionName='1.8.15-android.5-tv' platformBuildVersionName='14'\nsdkVersion:'21'\nnative-code: 'armeabi-v7a'\n"
        self.certs = "Signer #1 certificate SHA-256 digest: " + "a" * 64

    @patch.dict(os.environ, {"YINGXIA_EXPECTED_CERT_SHA256": ""})
    def test_extracts_identity_from_apk(self):
        result = module.manifest(self.apk, self.badging, self.certs, "更新说明")
        self.assertEqual(5, result["versionCode"])
        self.assertEqual(21, result["minSdk"])
        self.assertEqual(self.apk.stat().st_size, result["size"])
        self.assertEqual("更新说明", result["notes"])
        self.assertIn("/v1.8.15-android.5-tv/", result["apkUrl"])

    def test_rejects_wrong_abi_package_and_unsigned_apk(self):
        for badging, certs in [(self.badging.replace("armeabi-v7a", "arm64-v8a"), self.certs),
                               (self.badging.replace("com.yingxia.tv", "other.app"), self.certs),
                               (self.badging, "unsigned")]:
            with self.assertRaises(ValueError):
                module.manifest(self.apk, badging, certs)

    @patch.dict(os.environ, {"YINGXIA_EXPECTED_CERT_SHA256": "b" * 64})
    def test_rejects_changed_signing_key(self):
        with self.assertRaises(ValueError):
            module.manifest(self.apk, self.badging, self.certs)

if __name__ == "__main__":
    unittest.main()
