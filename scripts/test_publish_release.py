#!/usr/bin/env python3
"""Offline regression tests: mocked gh/push, real disposable Git repo; never touches GitHub."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).with_name('publish-release.sh').resolve()

class PublishReleaseTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        (self.root / 'app').mkdir()
        (self.root / 'docs').mkdir()
        (self.root / 'docs/RELEASE_NOTES.md').write_text('Notes')
        (self.root / 'app/build.gradle').write_text("versionCode 5\nversionName '1.8.15-android.5-tv'\n")
        self.assets = self.root / 'assets'
        self.assets.mkdir()
        apk = self.assets / 'yingxia-tv-arm32.apk'
        apk.write_bytes(b'verified artifact fixture')
        metadata = dict(schema=1, applicationId='com.yingxia.tv', abi='armeabi-v7a', versionCode=5,
                        versionName='1.8.15-android.5-tv', tag='v1.8.15-android.5-tv',
                        apkUrl='https://github.com/cxapython/tvbox-arm/releases/download/v1.8.15-android.5-tv/yingxia-tv-arm32.apk',
                        size=apk.stat().st_size, sha256=hashlib.sha256(apk.read_bytes()).hexdigest())
        (self.assets / 'update.json').write_text(json.dumps(metadata))
        (self.assets / 'SHA256SUMS').write_text(''.join(
            hashlib.sha256((self.assets / name).read_bytes()).hexdigest() + '  ' + name + '\n'
            for name in ['yingxia-tv-arm32.apk', 'update.json']))
        self.git = shutil.which('git')
        for command in [('init', '-q'), ('config', 'user.name', 'Fixture'), ('config', 'user.email', 'fixture@invalid'),
                        ('add', '.'), ('commit', '-qm', 'fixture')]:
            subprocess.run([self.git, *command], cwd=self.root, check=True, stdout=subprocess.PIPE)
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        (self.bin / 'gh').write_text('''#!/usr/bin/env bash
if [[ "$1 $2" == 'release view' ]]; then
  if [[ "$3" == --json ]]; then
    [[ "$MODE" == oldcode ]] && { echo vprevious; exit 0; }
    exit 1
  fi
  [[ "$MODE" == published ]] && { echo false; exit 0; }
  [[ "$MODE" == draft ]] && { echo true; exit 0; }
  exit 1
fi
echo "gh $*" >> "$TRACE"
''')
        (self.bin / 'git').write_text(f'''#!/usr/bin/env bash
if [[ "$1" == push ]]; then echo "git $*" >> "$TRACE"; exit 0; fi
exec '{self.git}' "$@"
''')
        for command in self.bin.iterdir():
            command.chmod(0o755)
        self.trace = self.root / 'trace'
        self.env = dict(os.environ, PATH=str(self.bin) + os.pathsep + os.environ['PATH'],
                        TRACE=str(self.trace), GITHUB_REF='refs/heads/main')
        self.env.pop('GITHUB_SHA', None)

    def run_release(self, mode):
        return subprocess.run(['bash', str(SCRIPT), str(self.assets)], cwd=self.root,
                              env=dict(self.env, MODE=mode), text=True, capture_output=True)

    def test_published_version_is_noop_not_a_failure(self):
        result = self.run_release('published')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('already published', result.stdout)
        self.assertFalse(self.trace.exists())

    def test_new_version_uploads_all_assets_before_publishing(self):
        result = self.run_release('fresh')
        self.assertEqual(0, result.returncode, result.stderr)
        trace = self.trace.read_text()
        self.assertIn('--draft --verify-tag', trace)
        self.assertIn('update.json', trace)
        self.assertLess(trace.index('release upload'), trace.index('--draft=false'))

    def test_resumes_draft_without_recreating_tag_or_release(self):
        subprocess.run([self.git, 'tag', 'v1.8.15-android.5-tv'], cwd=self.root, check=True)
        result = self.run_release('draft')
        self.assertEqual(0, result.returncode, result.stderr)
        trace = self.trace.read_text()
        self.assertNotIn('release create', trace)
        self.assertNotIn('git push', trace)
        self.assertIn('release upload', trace)

    def test_mismatched_push_tag_is_rejected(self):
        self.env['GITHUB_REF'] = 'refs/tags/vwrong'
        result = self.run_release('fresh')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('tag does not match', result.stderr)
        self.assertFalse(self.trace.exists())

    def test_modified_apk_is_rejected_before_any_remote_write(self):
        (self.assets / 'yingxia-tv-arm32.apk').write_bytes(b'tampered')
        result = self.run_release('fresh')
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.trace.exists())

    def test_nonincreasing_versioncode_is_rejected(self):
        subprocess.run([self.git, 'tag', 'vprevious'], cwd=self.root, check=True)
        result = self.run_release('oldcode')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('must be greater', result.stderr)
        self.assertFalse(self.trace.exists())

if __name__ == '__main__':
    unittest.main()
