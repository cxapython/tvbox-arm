#!/usr/bin/env bash
# Publish only verified artifacts. Re-running a published version is intentionally a no-op.
set -euo pipefail
artifact_dir="${1:?usage: publish-release.sh ARTIFACT_DIRECTORY}"
source "$(dirname -- "${BASH_SOURCE[0]}")/release-version.sh"
release_version
version_name=$release_name
version_code=$release_code
tag="v${version_name}"
if [[ "${GITHUB_REF:-}" == refs/tags/* && "${GITHUB_REF#refs/tags/}" != "$tag" ]]; then
  echo 'Pushed tag does not match the APK version' >&2; exit 1
fi
python3 - "$artifact_dir" "$version_name" "$version_code" <<'PY'
import hashlib, json, sys
from pathlib import Path
root = Path(sys.argv[1])
metadata = json.loads((root / 'update.json').read_text())
apk = root / 'yingxia-tv-arm32.apk'
assert metadata['schema'] == 1 and metadata['applicationId'] == 'com.yingxia.tv'
assert metadata['abi'] == 'armeabi-v7a' and metadata['versionName'] == sys.argv[2]
assert metadata['versionCode'] == int(sys.argv[3]) and metadata['tag'] == 'v' + sys.argv[2]
assert metadata['apkUrl'] == f"https://github.com/cxapython/tvbox-arm/releases/download/{metadata['tag']}/yingxia-tv-arm32.apk"
assert metadata['size'] == apk.stat().st_size
assert metadata['sha256'] == hashlib.sha256(apk.read_bytes()).hexdigest()
names = set()
for line in (root / 'SHA256SUMS').read_text().splitlines():
    digest, name = line.split()
    assert name in ('yingxia-tv-arm32.apk', 'update.json') and name not in names
    names.add(name)
    assert digest == hashlib.sha256((root / name).read_bytes()).hexdigest(), name
assert names == {'yingxia-tv-arm32.apk', 'update.json'}
PY
# A queued build may finish after main has advanced. Never promote an outdated checkout.
if [[ -n "${TVBOX_RELEASE_RUN:-}" ]]; then
  head="$(git ls-remote --exit-code origin refs/heads/main)"
  if [[ "${head%%$'\t'*}" != "${GITHUB_SHA:?missing build commit}" ]]; then
    echo '::notice::Skipping release because main has advanced.'
    exit 0
  fi
fi
# No tag deletion, release overwrite or publishing changed bytes under an installed version.
if existing_draft="$(gh release view "$tag" --json isDraft --jq '.isDraft' 2>/dev/null)"; then
  if [[ "$existing_draft" == false ]]; then
    echo "::notice::Release $tag is already published; leaving its APK and tag untouched."
    exit 0
  fi
fi
# Reject a forgotten numeric bump even if the human-readable version name was changed.
if previous_tag="$(gh release view --json tagName --jq '.tagName' 2>/dev/null)"; then
  if [[ "$previous_tag" =~ -r([1-9][0-9]{4,9})$ ]]; then
    # CI applies its version via Gradle properties, so the tag carries the actual APK code.
    previous_code="${BASH_REMATCH[1]}"
  else
    previous_code="$(git show "${previous_tag}:app/build.gradle" 2>/dev/null | sed -n 's/^[[:space:]]*versionCode \([0-9]*\).*/\1/p' | head -n 1)" || previous_code=''
  fi
  if [[ -n "$previous_code" && "$version_code" -le "$previous_code" && "$previous_tag" != "$tag" ]]; then
    echo "New versionCode ($version_code) must be greater than published versionCode ($previous_code)" >&2; exit 1
  fi
fi
if git show-ref --verify --quiet "refs/tags/$tag"; then
  if [[ "$(git rev-list -n 1 "$tag")" != "${GITHUB_SHA:-$(git rev-parse HEAD)}" ]]; then
    echo "Tag $tag already points to different source; bump the version rather than replace it" >&2; exit 1
  fi
else
  git config user.name 'github-actions[bot]'
  git config user.email '41898282+github-actions[bot]@users.noreply.github.com'
  git tag -a "$tag" "${GITHUB_SHA:-HEAD}" -m "Release $tag"
  git push origin "$tag"
fi
# Build a draft first: an updater must never observe a half-uploaded release.
if [[ "${existing_draft:-}" != true ]]; then
  gh release create "$tag" --draft --verify-tag --title "$tag" --notes-file docs/RELEASE_NOTES.md
fi
gh release upload "$tag" "$artifact_dir/yingxia-tv-arm32.apk" "$artifact_dir/SHA256SUMS" "$artifact_dir/update.json" --clobber
if [[ -n "${TVBOX_RELEASE_RUN:-}" ]]; then
  head="$(git ls-remote --exit-code origin refs/heads/main)"
  if [[ "${head%%$'\t'*}" != "$GITHUB_SHA" ]]; then
    echo '::notice::Leaving the release as a draft because main advanced during upload.'
    exit 0
  fi
fi
# The Release body and update.json both come from docs/RELEASE_NOTES.md at this commit.
gh release edit "$tag" --draft=false --latest
echo "Published $tag with validated update metadata."
