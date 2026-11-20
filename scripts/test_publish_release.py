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
        self.write_manifest(5, '1.8.15-android.5-tv')
        self.git = shutil.which('git')
        for command in [('init', '-q'), ('config', 'user.name', 'Fixture'), ('config', 'user.email', 'fixture@invalid'),
                        ('add', '.'), ('commit', '-qm', 'fixture')]:
            subprocess.run([self.git, *command], cwd=self.root, check=True, stdout=subprocess.PIPE)
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        (self.bin / 'gh').write_text('''#!/usr/bin/env bash
if [[ "$1 $2" == 'release view' ]]; then
  if [[ "$3" == --json ]]; then
    [[ -n "$PREVIOUS_TAG" ]] && { echo "$PREVIOUS_TAG"; exit 0; }
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
if [[ "$1" == ls-remote ]]; then
  if [[ "$MODE" == stale_at_publish && -f "$TRACE" ]]; then
    printf '%s\\trefs/heads/main\\n' outdated
  else
    printf '%s\\trefs/heads/main\\n' "$REMOTE_MAIN"
  fi
  exit 0
fi
if [[ "$1" == push ]]; then echo "git $*" >> "$TRACE"; exit 0; fi
exec '{self.git}' "$@"
''')
        for command in self.bin.iterdir():
            command.chmod(0o755)
        self.trace = self.root / 'trace'
        self.env = dict(os.environ, PATH=str(self.bin) + os.pathsep + os.environ['PATH'],
                        TRACE=str(self.trace), GITHUB_REF='refs/heads/main')
        for key in ('GITHUB_SHA', 'TVBOX_RELEASE_RUN', 'PREVIOUS_TAG'):
            self.env.pop(key, None)

    def write_manifest(self, code, name):
        apk = self.assets / 'yingxia-tv-arm32.apk'
        metadata = dict(schema=1, applicationId='com.yingxia.tv', abi='armeabi-v7a', versionCode=code,
                        versionName=name, tag='v' + name,
                        apkUrl=f'https://github.com/cxapython/tvbox-arm/releases/download/v{name}/yingxia-tv-arm32.apk',
                        size=apk.stat().st_size, sha256=hashlib.sha256(apk.read_bytes()).hexdigest())
        (self.assets / 'update.json').write_text(json.dumps(metadata))
        (self.assets / 'SHA256SUMS').write_text(''.join(
            hashlib.sha256((self.assets / asset).read_bytes()).hexdigest() + '  ' + asset + '\n'
            for asset in ['yingxia-tv-arm32.apk', 'update.json']))

    def enable_auto_release(self, run=42):
        commit = subprocess.check_output([self.git, 'rev-parse', 'HEAD'], cwd=self.root, text=True).strip()
        self.env.update(GITHUB_SHA=commit, REMOTE_MAIN=commit, TVBOX_RELEASE_RUN=str(run))
        self.write_manifest(10000 + run, f'1.8.15-android.5-tv-r{10000 + run}')

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

    def test_automatic_release_uses_unique_numeric_version_for_same_base(self):
        self.enable_auto_release()
        self.env['PREVIOUS_TAG'] = 'v1.8.15-android.5-tv-r10041'
        result = self.run_release('fresh')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('Published v1.8.15-android.5-tv-r10042', result.stdout)
        self.assertIn('git push origin v1.8.15-android.5-tv-r10042', self.trace.read_text())
        # A retry of the same workflow run resolves to the same published tag.
        self.trace.unlink()
        result = self.run_release('published')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('v1.8.15-android.5-tv-r10042 is already published', result.stdout)
        self.assertFalse(self.trace.exists())

    def test_automatic_run_cannot_downgrade_latest(self):
        self.enable_auto_release()
        self.env['PREVIOUS_TAG'] = 'v1.8.15-android.5-tv-r10043'
        result = self.run_release('fresh')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('must be greater', result.stderr)
        self.assertFalse(self.trace.exists())

    def test_stale_main_commit_is_not_published(self):
        self.enable_auto_release()
        self.env['REMOTE_MAIN'] = 'different commit'
        result = self.run_release('fresh')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('main has advanced', result.stdout)
        self.assertFalse(self.trace.exists())

    def test_main_advance_during_upload_keeps_draft(self):
        self.enable_auto_release()
        result = self.run_release('stale_at_publish')
        self.assertEqual(0, result.returncode, result.stderr)
        trace = self.trace.read_text()
        self.assertIn('release upload', trace)
        self.assertNotIn('--draft=false', trace)

    def test_invalid_automatic_run_is_rejected(self):
        self.enable_auto_release()
        self.env['TVBOX_RELEASE_RUN'] = '042'
        result = self.run_release('fresh')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('valid workflow run number', result.stderr)
        self.assertFalse(self.trace.exists())

    def test_missing_checksum_entry_is_rejected(self):
        checksums = self.assets / 'SHA256SUMS'
        checksums.write_text(checksums.read_text().splitlines()[0] + '\n')
        self.assertNotEqual(0, self.run_release('fresh').returncode)
        self.assertFalse(self.trace.exists())

if __name__ == '__main__':
    unittest.main()
