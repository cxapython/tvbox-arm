#!/bin/sh
set -eu
project_dir=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
cd "$project_dir"
# QuickJS and scripts are vendored at docs/SOURCE_REVISION. Build this checkout as-is;
# updating the Apple upstream is a separate migration, not a build-time side effect.
sdk_dir=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-"$HOME/Library/Android/sdk"}}
build_tools="$sdk_dir/build-tools/34.0.0"
./gradlew testDebugUnitTest lintDebug assembleRelease
mkdir -p artifacts
signing_key=${YINGXIA_SIGNING_KEY:-"$HOME/.android/debug.keystore"}
if [ ! -f "$signing_key" ]; then
    echo '缺少签名文件；先运行 ./gradlew assembleDebug 创建本机开发证书。' >&2
    exit 1
fi
# Passwords are passed through the environment, never expanded into command-line arguments.
YINGXIA_STORE_PASSWORD=${YINGXIA_STORE_PASSWORD:-android}
YINGXIA_KEY_PASSWORD=${YINGXIA_KEY_PASSWORD:-android}
export YINGXIA_STORE_PASSWORD YINGXIA_KEY_PASSWORD
"$build_tools/apksigner" sign --ks "$signing_key" --ks-key-alias "${YINGXIA_KEY_ALIAS:-androiddebugkey}" \
    --ks-pass env:YINGXIA_STORE_PASSWORD --key-pass env:YINGXIA_KEY_PASSWORD \
    --out artifacts/yingxia-tv-arm32.apk \
    app/build/outputs/apk/release/app-release-unsigned.apk
"$build_tools/apksigner" verify --verbose artifacts/yingxia-tv-arm32.apk
python3 scripts/verify_apk.py artifacts/yingxia-tv-arm32.apk
shasum -a 256 artifacts/yingxia-tv-arm32.apk > artifacts/SHA256SUMS
echo '已生成 artifacts/yingxia-tv-arm32.apk'
