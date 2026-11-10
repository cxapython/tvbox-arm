# 瓜子┃不卡：20 秒短片修复

日期：2026-11-10。设备：realme RMX3350 / Android 13。

## 原因与源项目对照

此前 `guazi-live-report.md` 中“实际播放正常”的结论撤回：旧测试只检查播放推进 15 秒和输出视频帧，没有检查总时长，错误地接受了 19.566 秒的短片。

本项目的 `Guazi.js` 与源项目基线 `030330f` 完全一致；检查远端最新 `ddaa0ce` 后，瓜子脚本也没有更新。问题位于 Android 播放器的请求头传递，而非瓜子接口签名或解密。

源项目 `App/PlaybackView.swift` 的 `makeItem` 将 `channel.headers` 交给 AVURLAsset；`Sources/TVCore/DownloadTransfer.swift` 的 `prepare` 只在源未指定 User-Agent 时补默认值。瓜子指定 `User-Agent: Lavf/57.83.100` 和 Referer。

Android 旧实现同时设置 `DefaultHttpDataSource.Factory.setUserAgent(Http.UA)` 和源请求头。Media3 会用前者覆盖请求头中的 User-Agent。同一个《狂飙》第 1 集播放地址实测：

| 请求标识 | HLS 大小 | 总时长 |
|---|---:|---:|
| 源指定 `Lavf/57.83.100` | 9,211 字节 | 2,744.120 秒 |
| Android 通用浏览器标识 | 146 字节 | 19.566667 秒 |

## 修改

- 新增共用 `PlaybackHttp` 工厂，默认 User-Agent 作为普通请求属性提供，源指定值优先，兼容头名称大小写；不再使用会覆盖源值的 `setUserAgent`。
- 播放器每次创建网络数据源时读取当前播放项的请求头；离线下载采用相同规则，包含每个请求的 DataSpec 头。
- 增加真实本地 HTTP 请求回归检查，覆盖默认标识、三种大小写的源标识、Referer，以及下载使用的请求级标识。
- 瓜子实测要求剧集至少 10 分钟、电影至少 1 小时，连续解码超过 35 秒，再跳至 120 秒并继续解码至 125 秒；保存播放前后的视频帧，不再把短片当作成功。

原始证据位于 `artifacts/verification/guazi-duration-report.json`、`guazi-source.m3u8`、`guazi-android.m3u8` 及 `guazi-fix-*`。

## 手机实测结果

| 条目 | 正片总时长 | 连续播放 / 输出帧 | 跳转后的进度 / 累计输出帧 |
|---|---:|---:|---:|
| 《狂飙》第 1 集，1080 | 45 分 44.120 秒 | 35.255 秒 / 880 帧 | 125.122 秒 / 1,015 帧 |
| 《流浪地球》，1080 | 2 小时 16 分 35.560 秒 | 35.128 秒 / 876 帧 | 125.083 秒 / 1,008 帧 |
| 《狂飙》第 2 集，1080 | 45 分 44.120 秒 | 35.255 秒 / 880 帧 | 125.064 秒 / 1,012 帧 |

三项均通过，已人工查看两分钟位置的视频帧，确认是正片画面，且第 1、2 集画面不同。这里只验证连续播放和跳转后的片段，没有看完整部电影或整集，也没有覆盖全部清晰度和片库。

163 项单元测试、Lint、9 项基础设备测试通过；ARM32 release 构建、签名和 ELF32 ARM 检查通过。手机已覆盖安装修复版，`artifacts/yingxia-phone-lite.apk` 和 `artifacts/yingxia-arm32-lite.apk` 已更新，校验和在 `artifacts/LITE-SHA256SUMS`。

USB 在三项实测期间短暂重连，主机 `guazi-fix-live.log` 缺少末尾汇总；设备端持久报告三项均为 `stage=complete, passed=true`，设备 TestRunner 日志补证后两项通过，见 `guazi-fix-device-tests.log`。
