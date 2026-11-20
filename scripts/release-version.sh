#!/usr/bin/env bash
# Sourced by the APK builder and publisher so they agree on the same immutable version.
release_version() {
  local run=${TVBOX_RELEASE_RUN:-}
  release_code="$(sed -n 's/^[[:space:]]*versionCode \([0-9]*\).*/\1/p' app/build.gradle | head -n 1)"
  release_name="$(sed -n "s/.*versionName ['\"]\([^'\"]*\)['\"].*/\1/p" app/build.gradle | head -n 1)"
  if [[ ! "$release_code" =~ ^[1-9][0-9]*$ || ! "$release_name" =~ ^[A-Za-z0-9._-]{1,85}$ ]]; then
    echo 'Unable to read APK version from app/build.gradle' >&2; return 1
  fi
  if [[ -n "$run" ]]; then
    if [[ "${GITHUB_REF:-}" != refs/heads/main || ! "$run" =~ ^[1-9][0-9]{0,8}$ ]]; then
      echo 'Automatic versions require a valid workflow run number on main' >&2; return 1
    fi
    # Reserve a range above existing manually numbered APKs. Retries use the same run number.
    release_code=$((10000 + run))
    release_name+="-r${release_code}"
  fi
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  release_version || exit 1
  printf '%s\n%s\n' "$release_code" "$release_name"
fi
