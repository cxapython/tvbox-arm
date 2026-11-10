# ARM32 预览版验证记录

> 本文是 2026-11-09 的历史验证记录。2026-11-10 轻量版已移除漫画，当前功能范围以 README 和 PARITY 为准，不能将下述旧包结果当作轻量版回归结果。

日期：2026-11-09。原版基线：`030330f67eda8edf8f29e6611c1cb9ed03734f85`。原版仓库已 fetch / fast-forward 检查，工作树未修改。

## 产物

- `artifacts/yingxia-arm32-preview.apk`，7,027,906 字节，约 6.7 MiB。
- 包名：`com.yingxia.tv`；版本：`1.8.15-android.1` / versionCode 1。
- minSdk 21 / targetSdk 34；有 Android TV / Leanback 启动入口。
- 唯一原生库：`lib/armeabi-v7a/libyingxia-script.so`。
- 使用 APK 内实际字节检查：ELF32、little-endian、ARM machine 40。
- apksigner 验证通过，v1 / v2 / v3 签名有效。预览使用本机 Android 开发证书。
- 正式包不包含测试片库、测试视频、测试 DEX 插件或 debug QA 入口。
- SHA-256：`2d4014ec4f6123d237e69e50130baee756a10879a25bdb47ed9577d00f22ee07`。

## 已通过检查

`testDebugUnitTest`：14 项通过，0 失败。覆盖订阅宽松 JSON、配置包装/加密、相对 URL、M3U/TXT、集数与 Unicode 等兼容行为。

`lintDebug` 和 `lintVitalRelease`：通过，无错误。保留的提示包括依赖新版本、电视固定横屏、图标密度、中文字符串国际化等。

Android 12 / API 31 / ARM64 TV 模拟器，9 项设备集成测试全部通过：

1. 实际 DEX 插件加载，调用新 SDK、JS 宿主和 loopback proxy。
2. 下载本地 HTTP 视频，读取已完成缓存，并在无上游网络数据源时逐字节比对。
3. 侧栏导航与遥控输入。
4. 同一影片的集数进度合并，续播重新获取播放地址，备份不导出服务器账户。
5. 订阅合并、无效刷新回滚和本地持久化。
6. JSON 点播主页、搜索、详情、播放解析。
7. 原版 QuickJS 前置环境、定时器、存储、GBK、emoji、跨工作线程调用。
8. 实际 MediaCodec 解码、播放推进与历史保存；确认解码输出帧数大于 0，并通过 PixelCopy 读取视频 Surface 像素，确认彩色测试画面不是黑帧。
9. Venera 漫画源安装、浏览、详情和章节图片。

最后一轮设备测试耗时 28.899 秒。普通 `adb screencap` 未捕获模拟器视频层；独立视频 Surface 的 PixelCopy 检查通过。其实际帧保存在 `artifacts/screenshots/08-decoded-frame.png`。

## UI 截图

`artifacts/screenshots/01-home.png` 至 `07-settings.png` 分别为首页、来源、片库、详情、播放器控件、直播频道、设置。使用本地夹具和原版官网授权演示图片拍摄，不代表安装包包含片源或影视内容。截图用于布局检查，未完成与原版 tvOS 的逐像素一致性验证。

## 尚待实机验证

没有连接用户的小米电视。ARM32 库已编译并验证，但运行测试发生在额外构建 ARM64 库的 TV 模拟器上。Android 5/6、电视厂商解码器、用户实际片源与插件仍需实机确认。原版功能差异见 [PARITY.md](PARITY.md)。
