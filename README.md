# OpenRhodes

ESP32-S3 灯箱固件与原生 Android 控制客户端，用于团队协作开发和硬件联调。

| 目录 | 内容 |
|---|---|
| [`esp_fw/`](esp_fw/) | 当前 ESP32-S3 固件、内嵌网页、构建脚本和回归测试 |
| [`app/`](app/) | Android Gradle 工程、原生界面、BLE 配网、小组件与测试 |

Android 开发测试 APK 见 [GitHub Releases](https://github.com/matXn/OpenRhodes/releases)。
发布标签对应同仓库中的源码快照，Android 工程入口为 `app/`，application 模块为 `app/app/`。

固件支持触摸调光、开机渐亮、常亮/呼吸、多 Wi-Fi、常驻 AP、mDNS 和 BLE 配网。
App 按完整 MAC 识别同一灯具，通过局域网 HTTP 控制；蓝牙用于配置 Wi-Fi。
设备端最高 PWM 当前限制为 80%，客户端仍使用 0–100% 亮度范围。

进入各目录查看构建和测试说明。仓库根目录的早期 `src/`、`web/`、`platformio.ini`
保留为历史基线，当前固件修改与构建请使用 `esp_fw/`。

不上传 PCB、原理图、网表、Gerber、制造文件、个人 Wi-Fi 凭据、签名私钥、
构建缓存或设备调试转储。自有代码的统一软件许可证由项目所有者另行决定，
第三方组件及素材保留各自许可和版权说明。
