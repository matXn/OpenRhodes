# OpenRhodes Android

OpenRhodes 原生 Android 客户端源码。此目录是独立 Gradle 工程，Android application
模块位于 `app/` 子目录（仓库中的 `app/app/`）。ESP 固件在仓库的 `esp_fw/`。

## 功能

- 局域网与蓝牙发现整合在添加灯具流程，优先蓝牙配网，保留 AP 和旧固件兼容入口。
- 按完整 STA MAC 自动合并同一灯具，保留历史地址；列表、小组件显示 MAC 以区分同名灯。
- 常亮/呼吸、滑动调光、轻点开关；具有反光、文字景深和柔和状态光的倾斜卡片。
- 呼吸卡片轻微旋转浮动；20 秒固定时间轴曲线向左滚动，标记在左侧上下运动。
- 呼吸新配置默认 6.8s / 13% / 0.60；实际灯具保存的参数优先。
- 首页下拉刷新使用标题下划线；启动时下划线按亮、暗、亮顺序从左向右扫过后保持。
- 3×2 与 2×2 可缩放桌面组件、实心圆形亮度循环按钮、红绿状态光和 MAC 标签。
- 保存多个 Wi-Fi、优先级、隐藏灯具配网热点、复制热点资料（需匹配 ESP 固件）。
- 应用 ID `com.openrhodes.app`；兼容组件类名用于保留既有小组件绑定与旧数据导入。

蓝牙目前用于配网；灯光控制通过同一局域网内的 HTTP，不能仅靠蓝牙调光。

## 当前开发版

2.1.25-dev（versionCode 50）。首页字标由独立 SVG 几何路径绘制，
Android 使用匹配的原生 vector，不分发 Polymath Demo 或其他字体文件。
`artwork/openrhodes-wordmark.svg` 可直接用于浏览器；
`python tools/build_wordmark.py` 同步生成 SVG 与原生 vector。
应用中的下划线独立绘制，以保留入场和刷新动画。

## 构建

需要 **JDK 21**、Android SDK 36、Build Tools 36.0.0，建议使用 Android Studio
安装 SDK。在本目录创建不会提交的 `local.properties`：

```properties
sdk.dir=/absolute/path/to/Android/Sdk
```

Windows 可使用正斜线形式的 SDK 路径。进入本目录后运行：

```sh
./gradlew --no-daemon :app:assembleDebug
./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug
```

Windows 使用 `gradlew.bat`。APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。
Gradle wrapper 与依赖版本已经固定，首次构建需要网络下载。

GitHub Releases 中的 APK 是开发测试包，使用项目现有本机 Debug 签名；签名私钥
不进入仓库。队友自行构建的默认 Debug 签名通常不同，不能直接覆盖已安装的发布包。
不要通过卸载来处理签名差异而丢失已保存数据；需先备份或使用单独测试环境。

## 工程结构

- `app/src/main/java/com/openrhodes/domain/`：灯具模型、稳定身份与命令队列。
- `data/`：HTTP、mDNS、BLE 配网、JSON 持久化与旧数据只读导入。
- `ui/`：原生 Compose 界面；`widget/`：RemoteViews 小组件。
- `app/src/test/`：协议、身份、调度、配网状态机及 Robolectric/Skia 视觉测试。
- `web/`：可选浏览器模拟预览，不是 App 运行时界面。
- `tools/emulator_fixture.py`：仅监听本机 loopback 的可选模拟灯具，不连接真实灯具。

视觉测试输出到 `app/build/reports/`，不提交截图、设备转储和构建缓存。
本次开发测试使用 UU/MuMu；模拟器无法替代真机蓝牙射频和各厂商桌面的验证。

## 协议与迁移

[蓝牙配网](docs/bluetooth-provisioning.md) · [旧数据与组件兼容](docs/migration.md)

固件对应的 HTTP/BLE 行为见 `../esp_fw/README.md`。自动发现只是地址线索，实际响应
中的完整 MAC 才用于确认灯具身份；更换地址时不会盲目重放未确认的灯光命令。

## 许可

应用实现为 OpenRhodes 独立实现，旧的组件名、数据库字段及偏好键仅用于兼容。
这不是正式的 clean-room 声明：开发过程中曾查看上游实现。
项目所有者尚未为自有代码指定统一软件许可证；不自动赋予整个仓库许可证。
AndroidX、Compose、Kotlin、OkHttp、Tink 等第三方组件保留各自许可，
见 `app/src/main/assets/third-party-notices.txt` 和 `docs/runtime-dependencies.json`。
