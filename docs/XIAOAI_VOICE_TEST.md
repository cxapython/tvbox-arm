# 小米电视小爱语音测试

本文用于在 `MiTV-MTEQ0`（Android 10、32 位 ARM）实机安装和测试映匣的小爱语音桥接。

## 1. 构建 APK

在项目根目录执行：

```sh
./gradlew assembleDebug
```

APK 路径：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 2. 连接电视并确认型号

电视和电脑连接同一个局域网，在电视中打开：

```text
设置 → 关于 → 连续点击版本号 7 次 → 开发者选项 → ADB 调试
```

然后执行：

```sh
adb connect 电视IP:5555
adb shell getprop ro.product.model
adb shell getprop ro.product.cpu.abilist
```

预期型号包含：

```text
MiTV-MTEQ0
```

## 3. 安装映匣

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

如果电视提示允许 USB 调试，请选择允许。覆盖安装一般不会删除已有订阅和应用数据。

## 4. 授予读取小爱日志权限

这个权限用于读取小爱识别完成后的文本，不需要无障碍权限：

```sh
adb shell pm grant com.yingxia.tv android.permission.READ_LOGS
```

确认权限记录：

```sh
adb shell dumpsys package com.yingxia.tv | grep -i READ_LOGS
```

授予权限后重启映匣：

```sh
adb shell am force-stop com.yingxia.tv
adb shell monkey -p com.yingxia.tv 1
```

## 5. 先测试 Deep Link，不依赖小爱

### 5.1 打开音乐搜索页

```sh
adb shell am start -a android.intent.action.VIEW \
  -d 'yingxia://music/search?query=%E5%91%A8%E6%9D%B0%E4%BC%A6%20%E6%99%B4%E5%A4%A9'
```

应打开映匣音乐搜索页，并显示“周杰伦 晴天”的搜索结果。

### 5.2 自动播放歌曲

```sh
adb shell am start -a android.intent.action.VIEW \
  -d 'yingxia://music/search?query=%E5%91%A8%E6%9D%B0%E4%BC%A6%20%E6%99%B4%E5%A4%A9&autoplay=true'
```

也可以用自定义 Action：

```sh
adb shell am start -a com.yingxia.tv.action.SEARCH_MUSIC \
  -n com.yingxia.tv/.ui.MainActivity \
  --es query "周杰伦 晴天" \
  --ez autoplay true
```

如果这一步失败，先不要测试小爱，优先检查映匣的订阅、网络和音乐源。

## 6. 验证小爱是否输出识别结果

先清空日志，然后监听小爱识别行：

```sh
adb logcat -c
adb logcat -v raw -T 1 -e onConversationFinished
```

按住遥控器语音键，说一句完整命令，例如：

```text
播放歌曲 周杰伦 晴天
```

预期看到类似内容：

```text
onConversationFinished Conversation(type=QUERY, engineType=NEAR_FIELD, queryType=ASR, query=播放歌曲 周杰伦 晴天, requestId='...', ...)
```

如果能看到 `query=`，说明电视型号上的小爱日志格式与当前桥接兼容。

## 7. 测试映匣语音桥接

先打开映匣并保持在前台：

```sh
adb shell monkey -p com.yingxia.tv 1
```

另开一个电脑终端监听映匣日志：

```sh
adb logcat -v threadtime | grep -E --line-buffered 'onConversationFinished|Yingxia.Xiaoai|Yingxia.Voice'
```

逐项测试：

```text
播放歌曲 周杰伦 晴天
```

预期：进入音乐播放器并播放歌曲。

```text
搜索歌曲 周杰伦 晴天
```

预期：进入音乐搜索结果页，不自动播放。

```text
搜索电影 流浪地球
```

预期：进入“全局搜索”，显示影视搜索结果。

```text
播放电影 流浪地球
```

预期：并发搜索可见点播源，找到结果后准备第一条可播放线路。

```text
播放电视剧 狂飙
```

预期：找到电视剧后打开第一集。播放线路是否可用取决于当前订阅源。

## 8. 无障碍是否需要开启

无障碍不是语音桥接的必要条件。当前功能分工如下：

| 功能 | 是否需要无障碍 |
|---|---|
| 读取小爱识别文本 | 不需要无障碍，只需 `READ_LOGS` |
| 搜索歌曲、电影和电视剧 | 不需要 |
| 自动播放 | 不需要 |
| 关闭小爱结果弹窗 | 需要 |

可以先不启用无障碍测试。如果小爱弹窗自动消失，保持关闭即可。如果弹窗一直盖住映匣，再打开：

```text
设置 → 设备偏好设置 → 无障碍 → 映匣小爱桥接 → 启用
```

未启用时，映匣会跳过关闭弹窗操作，不应崩溃。

## 9. 常见问题

### 没有任何 `onConversationFinished` 日志

确认以下几点：

```sh
adb shell pm grant com.yingxia.tv android.permission.READ_LOGS
adb shell am force-stop com.yingxia.tv
adb shell monkey -p com.yingxia.tv 1
```

然后重新清空日志并按遥控器语音键。如果电视固件升级过，小爱可能修改日志格式，需要重新抓取实际日志。

### 能看到日志，但映匣没有反应

确认映匣在前台，并监听自身日志：

```sh
adb logcat -v threadtime | grep -E --line-buffered 'Yingxia.Xiaoai|Yingxia.Voice'
```

如果有 `onConversationFinished`、没有 `Yingxia.Xiaoai`，通常是应用没有拿到 `READ_LOGS`，或映匣没有重启。

### 搜索到了内容但没有播放

这是点播源没有返回可播放集数、线路失效或需要登录，不代表语音桥接失败。先用遥控器进入映匣，手动打开同一条搜索结果确认线路。

### 恢复原状态

停止映匣即可停止日志监听：

```sh
adb shell am force-stop com.yingxia.tv
```

如需卸载测试版本：

```sh
adb uninstall com.yingxia.tv
```
