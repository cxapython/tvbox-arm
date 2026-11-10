# Musify 音乐接入

入口：「音乐 → Musify」。搜索歌曲或歌手，点按歌曲播放，长按选择「收藏歌曲」或「下载歌曲」。也可以把 `https://musify.club/en/artist/empyre-one-164584` 这样的歌手页链接粘贴到搜索框，浏览并翻页。

下载保存到应用现有离线缓存，从「下载」打开；不是导出到系统音乐目录。播放器原有「更多 → 下载当前歌曲」同样可用。

## 接口与行为

- 使用公开网页搜索 `/en/search?searchText=…&type=song&page=…`，通过 Jsoup 读取歌名、完整歌手名、版本标记、时长和封面。
- 歌手列表使用 `/en/artist/…/tracks`，后续页使用 `/tracks/page2` 等路径；普通搜索和歌手列表都根据页面的下一页链接显示翻页按钮，不依赖固定条数。
- 通过 `/api/track/{id}/stream-url` 获取站点公开播放地址，每次恢复播放重新获取；下载地址过期时沿用现有续链机制。
- 页面请求及媒体请求携带 User-Agent 和 Referer。普通命令行默认 User-Agent 曾返回 403；浏览器形式的请求无需登录即可成功。
- 网页 `/track/dl/…` 按钮在本次未登录请求中跳转登录页。应用下载缓存的是已经可以公开播放的完整音频，不调用该按钮，不需要用户配置 Musify 账号。
- 原始音质由站点文件决定，不提供不存在的 FLAC／320／128 切换。播放页根据解码器显示实际格式、码率和采样率。
- 站点返回授权限制、限流或无效页面时明确报错，不自动替换为其他平台的同名歌曲。当前未接入 Musify 歌词、专辑浏览和站点账号。

## 验证

2026-11-10 使用 Empyre One 参与的《Rockstar (Hands Up Mix)》，歌曲 ID `4422849`。桌面端直接获取公开完整音频后，ffprobe 检测为 MP3、320000 bit/s、44.1 kHz、双声道，时长 313.600 秒，文件 12547261 字节。

单元测试覆盖真实搜索 HTML 的元数据、版本区分、重复及异常条目、空结果与错误页、搜索编码、两种分页、播放地址刷新、访问限制和原始音质显示。共 192 项单元测试通过，Lint、ARM32 release 构建、签名和 ELF 检查通过。

realme RMX3350 / Android 13 手机实测通过：搜索首屏 36 首、歌手首屏 50 首，两种列表翻页均返回不同结果；播放器实际解码 MP3 / 320 kbps，时长 313.652 秒，跳至 120 秒后继续播放。完整下载 12547261 字节后，无网络数据源的播放器成功从缓存起播并跳到 303 秒。首次下载观察窗口 120 秒不足，延长为 360 秒后完成验证；实际速度随站点和网络变化。设备报告保存于 `artifacts/musify-live-report.json`。

另修正原有 `JarCallGuardTest` 的线程时序假设：超时中断后先等待工作线程释放运行状态，再检查下一次调用的异常传播，避免偶发把“运行时忙”当作待测异常。

运行真实接口、手机解码和离线测试：

```sh
./gradlew -Pemulator assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r \
  -e class com.yingxia.tv.MusifyIntegrationTest \
  com.yingxia.tv.test/android.test.InstrumentationTestRunner
```

测试读取两页搜索结果及两页歌手列表，检查真实解码、两分钟跳转、完整下载，再使用没有网络数据源的播放器从缓存播放并跳转到五分钟，排除离线测试仍依赖网络的假阳性。测试结束移除本次新增的下载；报告写入应用私有目录 `musify-live-report.json`。
