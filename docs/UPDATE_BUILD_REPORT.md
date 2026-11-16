# android.5 更新与播放改进验证

日期：2026-11-16；版本：1.8.15-android.5-tv（5）。

本报告为提交、推送前的本地验证快照；后续线上构建及发布状态以 GitHub Actions / Releases 为准。

## 已完成的检查

| 检查 | 结果 |
| --- | --- |
| `./scripts/build-arm32.sh` | 通过；包含脚本单测、Java 单测、Lint、release 构建、签名、ABI 和更新描述生成 |
| Java 单元测试 | 211 项，45 个 suite，0 失败/错误/跳过 |
| Python 发布/更新描述回归 | 9 项，全部通过；真实临时 Git 仓库，模拟 gh 和 push，不访问或修改线上 Release |
| Android Lint Debug / Vital Release | 通过；Debug XML 为 65 项 Warning，无 Error/Fatal |
| `actionlint`（v1.7.7） | workflow 检查通过 |
| `bash -n scripts/publish-release.sh` / `sh -n scripts/build-arm32.sh` | 通过 |
| `git diff --check` | 通过 |
| APK 签名 | apksigner 验证通过，使用本机既有开发证书 |
| APK 原生库 | ELF32 little-endian ARM，仅 armeabi-v7a |
| SHA256SUMS | APK / update.json 均校验通过 |
| Android 12 / API 31 ARM64 TV 模拟器 | RuntimeIntegrationTest + UpdateSecurityIntegrationTest，共 23 项全部通过 |

## 设备测试重点

- 本地 HTTP JSON/JS/JAR 宿主、订阅持久化/失败回滚、已有源可见策略、请求头传递等回归。
- 实际 MediaCodec 视频解码，视频帧输出与播放进度入历史。
- 先打开不能解析的直播地址，自动切换到本地有效 MP4 流；保持频道名称与频道身份。
- 30 秒配置正确应用到播放器快进/后退控制。
- 两条外部字幕重排后重新选择“中文”，关闭字幕后更换媒体仍保持关闭。
- 实际 PackageManager 读取更高版本同签名测试 APK，并校验包身份、证书及哈希。
- 拒绝同大小被篡改 APK、错误签名、与真实 APK 不一致的版本描述。
- FileProvider 可读指定更新文件，不允许分享资料库目录。
- 自动检查开关与一天一次节流；已下载更新页面有安装和返回控件。
- ARM64-only 模拟器被正式 ARM32 安装资格检查明确拒绝；独立归档校验用测试入口执行，没有给正式更新器加入测试绕过。

本轮还修正了旧设备测试夹具与现行产品策略的不一致：测试源补上“不卡”名称，快进按钮无障碍文本按 Media3 的当前语言/秒数资源断言，而非硬编码中文。没有改变现行源展示策略来迎合测试。

## 产物

```text
artifacts/yingxia-tv-arm32.apk
artifacts/update.json
artifacts/SHA256SUMS
artifacts/update-screen.png  # 设备测试页面；fixture 是测试归档，不是已上线版本
```

## 未完成的部署与验证边界

- 本地验证时尚未提交、推送、创建远程标签或发布 Release；线上 workflow 结果不属于本报告的本地验收范围。
- 没有配置/修改 GitHub 签名 Secrets；当前 APK 为本地开发签名，与上一份 GitHub android.4 Release 的签名不同。用户电视当前装包签名未知。
- 未连接用户 ARM32 小米电视，未验证该电视的未知应用授权/安装器确认及真实覆盖升级。
- 测试用更新归档从未安装；没有以“校验通过”代替真实升级验收。
- 正式 GitHub 更新下载链路仍需在首个包含 update.json 的正式 Release 发布后验收；旧线上版本没有该文件。网络不可达/限流会显示错误，不影响播放。
- 音乐歌词同录音匹配用确定性单测验证，没有承诺各第三方音乐目录当前每首都可用。

签名衔接、首次安装与发布要求见 [APP_UPDATES.md](APP_UPDATES.md)。
