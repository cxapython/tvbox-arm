# android.6 更新检查修正

## 原因与修改

android.5 先请求 GitHub REST API 的 latest Release，再获取 update.json；它把所有 403/429 一律提示为限流。手机报告的原始 HTTP 响应没有抓到，不能证明一定是配额耗尽。此设计在共享出口/代理下容易受匿名 API 配额影响，且 403 提示不准确。

android.6 改为直接请求本仓库正式 Release 的 `releases/latest/download/update.json`，保留 HTTPS、大小限制、清单验证和 APK 实际签名校验。普通 403 与有耗尽配额/Retry-After 证据的限流分开提示。没有新镜像、内置 Token 或安装绕过。

2026-11-17 现场检查：REST API 可用，返回 android.4；直接附件地址跳转至 android.4 并返回 404。线上尚无更新描述，正确提示是“线上暂未提供新的应用内更新包”，而不是声称已确认是最新版本。

## 验证

- Java 单测 221 项通过，其中 UpdateFeed 新增 10 项，覆盖请求地址、404/403/429/503、元数据上限、恶意 APK 地址、带标签跳转与 CDN 跳转链及 HTTP 降级。
- Python 发布脚本测试 9 项通过。
- ARM32 正式 APK 构建、Lint、签名、ABI、SHA256SUMS 检查通过；Lint 65 项 Warning，无 Error/Fatal。
- 真实 OkHttp 客户端请求官方线上地址：得到无可用更新包，符合当前 404 状态。
- realme RMX3350 / Android 13：android.5 保留数据覆盖到 android.6，正式包进入“检查更新”，实际显示“线上暂未提供新的应用内更新包”。截图留在本地 `artifacts/phone-test/android6-update-result.png`。
- 同手机运行 RuntimeIntegrationTest + UpdateSecurityIntegrationTest，23 项全部通过。
- 电视模拟器 22/23 项通过；离线下载测试等待 20 秒后仍在排队，独立重试亦失败。设备 dumpsys connectivity 显示 Active default network: none，Media3 下载要求 NETWORK。没有为此修改业务逻辑或伪报全部通过；同测试在上述手机上通过。

## 边界

此修正解决检查入口及错误分类，不代表正式在线升级渠道已经上线。固定签名 Secrets 和含 update.json 的正式 Release 尚需配置/发布；没有执行安装测试用更高版本惰性归档，也未验证 GitHub 下载至系统安装确认的完整升级链路。
