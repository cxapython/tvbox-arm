# 映匣 Android TV · ARM32 移植

面向小米电视的原生 Android 工程，参考 `tvbox-silicon` 的 tvOS 界面与交互。原版是 Swift / SwiftUI 应用，不能直接编译成 APK；本工程使用 Java、Android 原生视图、Media3 与原版 QuickJS 引擎实现移植。

**当前为预览版，尚未达到原版全部功能与像素级 1:1 一致。** 已实现的范围、测试边界和差异见 [功能对照](docs/PARITY.md)。没有预置商业片源或账户。

## 安装

构建产物：`artifacts/yingxia-arm32-preview.apk`。仅包含 `armeabi-v7a` 原生库，最低 Android 5.0 / API 21。需要电视系统具有正常工作的 Android MediaCodec 解码器。

1. 把 APK 复制到 U 盘，在电视文件管理器中打开，并按电视提示允许安装。
2. 或打开电视的 ADB 调试，然后执行：

```sh
adb connect 电视IP:5555
adb install -r artifacts/yingxia-arm32-preview.apk
```

启动「映匣」，在「设置」添加 TVBox / M3U / TXT 订阅。也可以打开「手机输入」，使用同一网络下的手机扫码输入地址。方向键移动、确定键打开、长按确定显示管理菜单；返回键先返回上页或侧栏，再返回首页。

预览 APK 使用本机构建环境的 Android 开发签名。它适合自用测试，后续覆盖安装需保留相同签名；正式分发应使用自己的发布证书。

## 构建

需要 JDK 17 或更新版本、Android SDK 34、Build Tools 34.0.0、NDK 28.2.13676358、CMake 3.22.1。在 `local.properties` 写入本机的 `sdk.dir`。

```sh
./scripts/build-arm32.sh
```

脚本先同步旁边的原版工程、确认移植基线，再执行单元测试、Lint、release 构建和 APK 签名，并核对 APK 只有 ARM32 原生库。原版基线记录在 [SOURCE_REVISION](docs/SOURCE_REVISION)。

手动构建：

```sh
./gradlew testDebugUnitTest lintDebug assembleRelease
```

`app/build/outputs/apk/release/app-release-unsigned.apk` 需要签名后才能安装。请勿用 `-Pemulator` 构建给 ARM32 电视的正式产物；该选项额外包含 ARM64，仅用于模拟器验证。

## 验证

```sh
./gradlew -Pemulator assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r \
  -e class com.yingxia.tv.RuntimeIntegrationTest \
  com.yingxia.tv.test/android.test.InstrumentationTestRunner
```

设备测试实际运行 QuickJS/JNI、DEX 插件、HTTP、本机代理、MediaCodec、订阅存储和离线缓存。Android 12 ARM64 TV 模拟器的通过结果不等于 ARM32 小米电视实机验证。最低系统版本通过 Lint 和反糖兼容检查，尚未在 Android 5/6 实机上运行。

本地界面验证：

```sh
python3 scripts/fixture_server.py --source ../tvbox-silicon
adb shell am start -n com.yingxia.tv/.ui.MainActivity \
  --es qaFixture http://10.0.2.2:18765/config.json --ei qaSection 0
```

此入口仅在 debug 构建生效。演示片库不会随正式 APK 安装，图片由原版官网的本地文件提供。`qaSection` 对应侧栏顺序，从 0 到 11；`qaTitle` 可指定搜索词。

## 源码与许可

- `app/src/main/java`：Android UI、订阅、播放、漫画、媒体服务器及插件宿主。
- `app/src/main/assets/scripts`：沿用原版脚本、TVBox 与 Venera 前置环境。
- `vendor/QuickJS`、`app/src/main/cpp`：原版引擎及 Android JNI 适配。
- `app/src/test`、`app/src/androidTest`：格式兼容性与设备集成测试。

沿用原版 GPL-3.0-only 许可。第三方来源见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
