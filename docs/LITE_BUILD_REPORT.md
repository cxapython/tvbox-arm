# 轻量版验证记录

日期：2026-11-10。版本：`1.8.15-android.3-lite` / versionCode 3。

## 本次范围

- 保留电影、电视剧、直播和音乐，以及订阅、搜索、历史、收藏和离线下载。
- 删除漫画导航、阅读器 Activity、漫画引擎、图片处理与专用宿主接口、Venera 资源及相关测试。
- 不接入红果等官方内置短剧源；删除自带的 DJHub 短剧聚合适配脚本。用户订阅仍自行决定返回的影视内容。
- 删除网盘入口、服务器管理、AList/OpenList、Emby/Jellyfin、WebDAV 客户端及云端续链；同步导航图标、返回恢复和截图脚本。
- 旧版数据仍保留，历史中的旧网盘记录给出功能移除提示；导出备份仍对遗留服务器字段脱敏。
- 回归中修复已有下载缓存键不一致问题：播放器从持久下载请求恢复缓存键和流选择；无上游网络时读取缓存并逐字节比对通过。
- 补齐 PlaybackDataSource 使用 Media3 接口所需的 OptIn 注解，Lint 通过。

## 产物

| 产物 | 大小 | 用途 |
|---|---:|---|
| `artifacts/yingxia-arm32-lite.apk` | 7,052,254 字节 / 6.73 MiB | ARM32 电视；签名及 ELF32 ARM 校验通过 |
| `artifacts/yingxia-phone-lite.apk` | 10,508,500 字节 / 10.02 MiB | ARM32/ARM64 手机测试；已安装到 realme RMX3350 |

两个包均不包含 Venera 资源、DJHub 脚本或 ReaderActivity 注册。校验和见 `artifacts/LITE-SHA256SUMS`。手机包相对本轮前同 ABI 测试包减少 525,983 字节；电视旧包还缺少此前的多项迁移修复，不能直接用旧包大小衡量本轮裁剪收益。

## 验证结果

- 159 项单元测试：0 失败、0 错误；随网盘模块移除 WebDAV 专用测试。
- `lintDebug`、`lintVitalRelease`、debug 和 release 构建通过。
- realme RMX3350 / Android 13：8 项设备集成检查中 7 项首次通过；离线读取检查暴露缓存键问题，修复后针对该检查复测通过。覆盖 QuickJS、DEX 插件、遥控导航、订阅与历史、JSON 点播、离线缓存、MediaCodec 和 PixelCopy 视频画面检查。
- 真实 fty 订阅重新导入得到 48 个源；光影《鉴定》播放 5,050 ms / 126 帧，视界《狂飙一梦》播放 5,049 ms / 121 帧。奶酪《狂飙1998》仍报 Source error，未确认其底层原因；没有宣称所有源或线路可用。
- 测试结束逐项比对手机 library.json，原有订阅与历史完整保留，没有残留测试播放记录。

构建和设备原始结果位于 `artifacts/verification/lite-*.log`；真实源结果为 `artifacts/verification/lite-phone-live-report.json`。ARM32 库打包检查通过，尚未进行 ARM32 小米电视实机验证。

## 发现页面 HTTP 400 修复

默认请求的 featured 在线上接口返回 400；改为 trending（热门），去掉不可用的精选按钮。22 个保留分类均在线验证 HTTP 200。160 项单元测试及 Lint 再次通过，修复已覆盖安装到手机，UI 显示“✓ 热门”和真实电影海报条目。接口记录为 artifacts/verification/discover-endpoints.json，界面记录为 artifacts/verification/lite-phone-ui.png。最终两份安装包均包含此修复。

## 网盘移除后的最终复测（android.3-lite）

159 项单元测试、Lint、ARM32 release 构建及签名/ABI 检查通过。realme RMX3350 上 8 项基础设备检查全部通过；真实源报告中光影《鉴定》5,135 ms / 128 帧、视界《狂飙一梦》5,205 ms / 125 帧，奶酪所选线路依旧失败。USB/ADB 断连使主机日志缺少最后的汇总行，已从设备持久报告确认两条成功播放结果并确认 library.json 完全恢复。

手机安装版本为 1.8.15-android.3-lite。最终 UI 已核对没有网盘、漫画入口，音乐和播放历史入口正常；包内 DEX 不包含 CloudClient、ReaderActivity、MangaEngine。原始证据位于 artifacts/verification/no-cloud-*。

## 瓜子 20 秒短片修复

已修复 Android 播放器覆盖源 User-Agent 导致返回 19.566 秒短片的问题，播放和离线下载均保留源指定标识。手机复测《狂飙》第 1、2 集及《流浪地球》，确认完整正片时长、连续播放超过 35 秒、跳至两分钟后继续解码和正片截图。163 项单元测试、Lint、9 项基础设备测试通过，手机包与 ARM32 电视包均已更新。此前只播放 15 秒便认定瓜子正常的结论撤回，详见 [修复与实测记录](GUAZI_PLAYBACK_FIX.md)。
