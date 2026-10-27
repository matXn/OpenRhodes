# OpenRhodes ESP firmware

ESP32-S3 灯箱固件，供队友协作开发与硬件联调。此目录是当前 ESP 固件源码入口。
不包含 PCB、个人 Wi-Fi 凭据、编译产物或设备调试转储。

## 目录

```text
platformio.ini       PlatformIO 平台、分区与编译参数
src/main.cpp         触摸、PWM、渐亮与呼吸效果
src/http_control.h   HTTP API、常驻 AP、mDNS 与配网状态机
src/wifi_*.h         Wi-Fi 配置存储、优先级、自动连接
src/ble_provision*   蓝牙配网服务与协议
src/vendor/          修复 ATT 分片读取的 Espressif transport 及原始许可
web/                内嵌网页、字体及现有页面资源
tools/              网页打包与 PlatformIO 构建前钩子
test/               主机回归、网页模拟与可选真实 BLE 检查
docs/               配网协议及设计说明
```

## 构建与烧录

需要 Python 3 和 PlatformIO Core，或 VS Code PlatformIO 扩展：

```sh
python -m pip install platformio
cd esp_fw
pio run
pio run -t upload --upload-port COM_PORT
pio device monitor --port COM_PORT
```

将 `COM_PORT` 换成自己的串口（Linux/macOS 使用对应设备路径）。
构建前钩子自动运行 `tools/build_web.py`，无需提交生成的网页头文件。
也可执行 `python tools/build_web.py`，输出的 `test/http_preview/` 仅供本地预览。
烧录前关闭占用串口的监视器；普通编译不会烧写、清空 NVS 或修改灯具。

平台锁定 `espressif32@6.13.0`（Arduino ESP32 2.0.17）。当前使用
`esp32-s3-devkitm-1` 板配置与 4 MB `min_spiffs.csv` 分区，已用过的模组
实际为 8 MB。修改 flash/分区前核对模组与 NVS/OTA 布局。

## 当前硬件与默认参数

- GPIO1–10：电容触摸焊盘；GPIO17：PW2053 电源使能；GPIO18：高边 PMOS 反相 PWM。
- PWM 19 kHz / 12 bit；物理占空比上限 80%，客户端仍使用 0–100% 范围。
- 保留开机渐亮、触摸校准、亮度/模式保存。
- 新配置的呼吸默认值：周期 6.8s，最暗 13%，曲线 0.60；已有 NVS 参数保留。
- 最多保存 8 个 Wi-Fi；成功手动连接的新网络提升为优先级 1。
- 蓝牙配网开启至少 5 分钟；安全请求续期，未保存成功网络时保持开启。
- BLE 使用 Espressif Security 1，不要求输入配网码；目前用于配网，灯光控制仍走 HTTP。

运行参数以源码为准；本次目录整理未修改上述运行源码。

## 配网和控制

常驻热点 `RhodesLight-Setup`，通用开发默认密码 `RhodesLight`，
入口 `http://192.168.4.1/setup`。可隐藏热点广播；AP 和家庭 Wi-Fi 可共存。
路由器凭据仅在设备 NVS 保存，不写入仓库、不通过状态 API 返回。
局域网可使用设备 IP 或 `rhodeslight.local`；mDNS 是否可达取决于网络多播支持。
完整 STA MAC 和稳定 `device_id` 用于跨 Wi-Fi 识别同一灯具。

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/api/state` | 灯光、网络、MAC/设备 ID、呼吸相位 |
| POST | `/api/light` | `brightness=0..100`、`on=0/1` |
| POST | `/api/mode` | `mode=breathing/steady` |
| POST | `/api/breath` | `period` 毫秒、`floor` 百分比、`curve` 指数 × 100 |
| GET / POST | `/api/scan` | 查询 / 启动 SSID 扫描 |
| POST | `/api/wifi` | 提交 `ssid` 和 `password` |
| GET / POST | `/api/wifi/profiles` | 保存的网络、调整优先级、忘记网络 |
| POST | `/api/wifi/setup` | `action=hold/release` 配网优先权 |
| GET / POST | `/api/wifi/ap` | 查询热点资料 / `hidden=0/1` |
| GET / POST | `/api/wifi/ble` | 查询 / 重新开启蓝牙配网 |

写入使用表单编码与 `X-Rhodes-Control: 1`；热点资料和 BLE 状态读取也需要该头。
此请求头是显式调用检查，并非身份认证。具体字段与校验以源码为准。

详见 [蓝牙配网](docs/bluetooth-provisioning.md)、
[Wi-Fi 调度](docs/wifi-setup-priority.md)、[隐藏热点](docs/hidden-setup-ap.md)、
[设备身份与 mDNS](docs/mdns-identity.md)。

## 验证

```sh
pio run
node --check web/control.js
node --check web/setup.js
```

Windows 主机测试需要 Visual Studio C++ Build Tools；在 **x64 Native Tools
Command Prompt** 中进入本目录运行（Python 需在 PATH）：

```bat
test\run-startup.cmd
test\run-wifi-setup.cmd
test\run-ap-settings.cmd
test\run-ble-provision.cmd
test\run-ble-read.cmd
```

这些测试覆盖实际启动渐亮/校准逻辑、Wi-Fi 调度和配置、热点设置回滚、
蓝牙帧解析与中断/重复 ATT 读取。不会连接灯具或提交真实凭据。

网页模拟测试需要 Node.js 和 Playwright，可在本目录用
`npm install --no-save --package-lock=false playwright` 安装，再设置
`PLAYWRIGHT_CHANNEL=msedge` 选择已安装的 Edge，或安装 Playwright Chromium。
以 `node test/features.browser.cjs`、`node test/wifi-setup.browser.cjs` 等运行。
真实蓝牙只读检查 `test/ble-live-read.py` 需另装 `bleak`、`cryptography`；
它会扫描附近蓝牙设备，请显式提供目标广播名。

主机/网页模拟通过不能代替上板触摸噪声、供电、射频和断电恢复验证。

## 许可与提交范围

团队尚未为自有代码选定统一软件许可证，不自动添加许可证。
`src/vendor/espidf_protocomm` 保留 Espressif Apache-2.0 许可和改动说明。
网页保留现有第三方字体/素材，其权利归原权利人，不能将本仓库视为素材授权。
不提交 `.pio/`、生成头文件、`node_modules/`、本机调试记录、密钥、Wi-Fi 凭据或 PCB。
