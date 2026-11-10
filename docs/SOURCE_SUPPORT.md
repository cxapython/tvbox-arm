# 源适配与二期清单

更新：2026-11-10；版本：1.8.15-android.4-tv。

## 展示规则

订阅原始配置完整保留。源列表、推荐源选择、全局搜索以及历史／收藏的播放入口只展示当前支持的源；手动“恢复隐藏点播源”不会解除适配限制。删除、刷新和导出订阅不丢失被隐藏的源数据。

- JSON / XML / type 4 接口和直接 JavaScript 源保留。
- `csp_` 源必须存在 TVCore 轻量 JS 映射；依赖 JAR 的脚本加载器、没有 JS 映射的插件与 Python 源暂时隐藏。
- `source-support.json` 记录已知的站点阻塞或未实现的播放协议；更换订阅中的显示名不会绕过该规则。
- “运行时支持”与“真实站点已验证”分开记录，不因存在 JS 文件就声称所有内容可用。

## 本期“不卡”源

| 源 | 结论 | 证据／后续 |
|---|---|---|
| 光影 | 已验证 | 片库、分类、详情；《鉴定》第 1 集总长 20:33，连续播放 35 秒并跳至两分钟继续解码。“狂飙”搜索无结果，不据此认定搜索异常或正片可用。 |
| 瓜子 | 已修复、验证 | 保留源指定 User-Agent，纠正 19.566 秒短片误判；《狂飙》第 1、2 集及《流浪地球》总时长、连续解码和跳转验证通过。 |
| 比特 | 已验证 | 片库、分类、搜索、39 集详情；《狂飙》第 1 集总长约 45:44，连续播放 35 秒并跳至两分钟继续解码。 |
| 立播 | 暂时隐藏 | 订阅 www.libvio.pw 与发布页列出的 libvio.host、www.libvio.to、libviobd.com 均返回 HTTP 403：Access denied by geographic restriction。现有 JS 已迁入，待站点可达后验证；未宣称适配完成。 |
| 厂长 | 二期、隐藏 | 当前订阅使用 csp_NewCzGuard，TVCore 只有旧 CZ.js，不能直接视作同一个源。按用户要求本期不实现新 JS。 |
| 海绵 | 二期、隐藏 | csp_HmysGuard 暂无 JS 映射，按用户要求本期不实现。 |

以上是播放片段与跳转检查，没有逐集看完整集或覆盖全部清晰度。

## 默认配置中暂不展示的源

当前内置配置 48 项：显示 23 项，隐藏 25 项。下面是该快照的隐藏清单；新导入配置按相同规则动态判定。

| 名称 | 插件 | 原因 |
|---|---|---|
| 豆豆┃片单 <兼容安卓4> | `csp_DouDouGuard` | 尚无 JavaScript 适配，JAR 源留待二期 |
| 🗂我的云盘┃配置 | `csp_MyDriveGuard` | 尚无 JavaScript 适配，JAR 源留待二期 |
| 👽玩偶哥哥┃4K弹幕 | `csp_WoGGGuard` | 尚无 JavaScript 适配，JAR 源留待二期 |
| 💡聚剧┃四盘 | `csp_SeedhubGuard` | 尚无 JavaScript 适配，JAR 源留待二期 |
| 📔厂长┃不卡 | `csp_NewCzGuard` | 尚无 JavaScript 适配，JAR 源留待二期 |
| 🐬海绵┃不卡 | `csp_HmysGuard` | 尚无 JavaScript 适配，JAR 源留待二期 |
| 🌟立播┃不卡 | `csp_LibvioGuard` | 当前站点及发布页备用域名返回 HTTP 403 地区限制，恢复可访问后再验证 |
| 💮文采┃秒播 | `csp_JpysGuard` | 尚无 JavaScript 适配，JAR 源留待二期 |
| ☁️风云┃多线 | `csp_FyGuard` | 尚无 JavaScript 适配，JAR 源留待二期 |
| 🥝荐片┃多线 | `csp_JPJGuard` | 荐片私有 P2P/FTP 播放协议尚未适配 |
| 🧲新6V┃磁力 | `csp_SixVGuard` | 尚无 JavaScript 适配，JAR 源留待二期 |
| ❤️AI剧┃弹幕 | `csp_AiDjGuard` | 尚无 JavaScript 适配，JAR 源留待二期 |
| 🚌巴士┃动漫 | `csp_Dm84Guard` | 尚无 JavaScript 适配，JAR 源留待二期 |
| ⚽八八┃看球 | `csp_KanqiuGuard` | 尚无 JavaScript 适配，JAR 源留待二期 |
| 🏐吃瓜┃看球 | `csp_LiveGzGuard` | 尚无 JavaScript 适配，JAR 源留待二期 |
| 🎮一直播┃直播 | `csp_AllliveGuard` | 尚无 JavaScript 适配，JAR 源留待二期 |
| 🎧有声┃小说 | `csp_YueTingGuard` | 尚无 JavaScript 适配，JAR 源留待二期 |
| 🎈盘搜┃四盘 | `csp_S_zpsGuard` | 尚无 JavaScript 适配，JAR 源留待二期 |
| 🦋易搜┃四盘 | `csp_S_zpsGuard` | 尚无 JavaScript 适配，JAR 源留待二期 |
| 🐌盘她┃夸父 | `csp_YpanSoGuard` | 尚无 JavaScript 适配，JAR 源留待二期 |
| 🐞盘他┃嘟嘟 | `csp_BpanSoGuard` | 尚无 JavaScript 适配，JAR 源留待二期 |
| 🌶️抠抠┃搜搜 | `csp_KkSsGuard` | 尚无 JavaScript 适配，JAR 源留待二期 |
| 🌈优汐┃搜搜 | `csp_UuSsGuard` | 尚无 JavaScript 适配，JAR 源留待二期 |
| 🛴手机┃推送 | `csp_PushGuard` | 尚无 JavaScript 适配，JAR 源留待二期 |
| 请勿信视频中任何广告 | `csp_XPathGuard` | 尚无 JavaScript 适配，JAR 源留待二期 |

## 二期恢复条件

1. 为没有 JS 的插件建立适配，或完成该插件的 Android JAR 宿主兼容验证；不要把旧版同名脚本随意映射到新插件。
2. 完成网站、私有协议或所需运行时后，在本地测试和手机上验证片库、详情、真实总时长、连续解码与跳转。
3. 从 `source-support.json` 中移除对应暂缓项，或加入 `scripts/sites/index.json` 映射。已有订阅中的源自动重新展示，无需再次导入。

网盘、漫画、媒体服务器保持当前移除范围，不因恢复其他插件而重新开放。

## 内置电台

ItaloDance 独立于影视源列表，始终在音乐和直播页提供入口。使用 ItaloSound 两条公开 Icecast MP3 流，参照 Mp4Hub ARM32 TV 的元数据、封面、进度与音质切换实现。没有将它伪装成点播影片或允许下载无结束时间的直播流。
