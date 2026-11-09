#!/bin/sh
set -eu
project_dir=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
cd "$project_dir"
source_dir=${YINGXIA_SOURCE_DIR:-"$project_dir/../tvbox-silicon"}
if [ -d "$source_dir/.git" ]; then
    git -C "$source_dir" fetch origin
    git -C "$source_dir" pull --ff-only
    expected_revision=$(cat docs/SOURCE_REVISION)
    source_revision=$(git -C "$source_dir" rev-parse HEAD)
    if [ "$source_revision" != "$expected_revision" ]; then
        echo '原版版本已变化；先更新移植基线与相关代码，再重新打包。' >&2
        exit 1
    fi
fi
sdk_dir=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-"$HOME/Library/Android/sdk"}}
build_tools="$sdk_dir/build-tools/34.0.0"
./gradlew testDebugUnitTest lintDebug assembleRelease
if [ -d "$source_dir/.git" ]; then
    git -C "$source_dir" fetch origin
    git -C "$source_dir" merge-base --is-ancestor origin/main HEAD
fi
mkdir -p artifacts
signing_key=${YINGXIA_SIGNING_KEY:-"$HOME/.android/debug.keystore"}
if [ ! -f "$signing_key" ]; then
    echo '缺少签名文件；先运行 ./gradlew assembleDebug 创建本机开发证书。' >&2
    exit 1
fi
"$build_tools/apksigner" sign --ks "$signing_key" --ks-key-alias androiddebugkey \
    --ks-pass pass:android --key-pass pass:android \
    --out artifacts/yingxia-arm32-preview.apk \
    app/build/outputs/apk/release/app-release-unsigned.apk
"$build_tools/apksigner" verify --verbose artifacts/yingxia-arm32-preview.apk
python3 scripts/verify_apk.py artifacts/yingxia-arm32-preview.apk
shasum -a 256 artifacts/yingxia-arm32-preview.apk > artifacts/SHA256SUMS
echo '已生成 artifacts/yingxia-arm32-preview.apk'
