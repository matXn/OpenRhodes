# OpenRhodes

ESP32-S3 装饰灯固件的团队开发仓库。

**当前用于协作开发和硬件联调，不作为最终用户分发渠道。** 暂不提供发布版、预编译固件或安装包；功能、接口和参数仍会调整。

## 仓库范围

包含 ESP32-S3 固件、设备内嵌网页源码和网页构建脚本。PCB、原理图、网表、Gerber、制造文件及本地调试记录不纳入仓库。

## 目录

```text
src/main.cpp          触摸、PWM、灯光状态与呼吸效果
src/http_control.h    HTTP API、Wi-Fi 配网与常驻 AP
web/                  当前网页及开场素材源码
  index.html
  control.js
  portal.html
  splash.html
tools/build_web.py    生成固件需要的网页资源头文件
platformio.ini        平台、目标板及构建配置
```

`src/control_page.h`、`src/portal_page.h`、`src/splash_asset.h`、`src/splash.html` 是生成文件，不提交。修改网页请改 `web/` 后重新生成。

## 本地开发

需要 Python 3、PlatformIO Core，或 VS Code 的 PlatformIO 扩展。当前平台锁定为 `espressif32@6.13.0`，使用 Arduino ESP32 core 2.x。

```sh
python -m pip install platformio
python tools/build_web.py
pio run
```

烧录时先生成网页，再指定自己的串口：

```sh
python tools/build_web.py
pio run -t upload --upload-port COM22
pio device monitor --port COM22
```

`COM22` 只是示例。Linux/macOS 使用自己的串口路径。烧录前释放占用该端口的串口监视器。

若安装了 Node.js，可额外检查网页脚本语法：

```sh
node --check web/control.js
```

固件不依赖仓库中的 Wi-Fi 账号密码文件。通过设备页面配网，成功连接的凭据保存在设备 NVS 中，不上传 GitHub。

## 当前硬件假设

- ESP32-S3，当前配置为 10 个电容触摸通道，GPIO1–GPIO10。
- GPIO17：LED 电源轨使能；GPIO18：高边 PMOS 的反相 PWM。
- 14-bit LEDC；逻辑最大输出限制为硬件满量程的一半。
- 当前构建使用 4 MB flash 布局（`min_spiffs.csv`）。已测试的模组实际为 8 MB；这项兼容配置保留，修改分区前请和实际硬件核对。

这些是固件的接线前提，不是 PCB 设计文件。

## HTTP 与 Wi-Fi

- 常驻 AP：`RhodesLight-Setup`。
- 开发用默认 AP 密码：`RhodesLight`。这是源码中的通用默认值，不是家庭 Wi-Fi 密码。
- AP 地址：`http://192.168.4.1/`；系统登录入口为 `/portal`。
- 连入家庭 Wi-Fi 后可通过设备获得的局域网 IP 访问，AP 继续保留。
- 传感器权限、系统 Wi-Fi 登录窗口与完整浏览器的行为可能不同；原生陀螺仪通常需要安全上下文。

主要接口：

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/api/state` | 灯光与网络状态 |
| POST | `/api/light` | `brightness=0..100`、`on=0/1` |
| POST | `/api/mode` | `mode=breathing/steady` |
| GET / POST | `/api/scan` | 查询 / 启动 Wi-Fi 扫描 |
| POST | `/api/wifi` | 提交 `ssid` 和 `password` |

写入接口要求 `X-Rhodes-Control: 1`，参数使用 `application/x-www-form-urlencoded`。具体行为以 `src/http_control.h` 为准，接口暂未承诺稳定兼容。

网页和固件正处于迭代中，`web/` 与生成后的设备页面可能和旧截图、历史测试不同。合并改动前请重新构建并实际验证。

## 协作方式

从 `main` 建功能分支，完成后提交 Pull Request。涉及触摸手感、引脚、电源或网络状态机时，请在 PR 中记录硬件版本、测试方式和仍未验证的部分；不要只用网页模拟结果代替上板验证。

提交前建议运行：

```sh
python tools/build_web.py
node --check web/control.js
pio run
```

不要提交 `.pio/`、生成的资源头文件、个人密钥、Wi-Fi 凭据、抓取的设备状态或历史备份。仓库不自动生成 Release，也不上传编译产物。

## 许可与素材

团队尚未选定软件许可证，本次不自动添加开源许可证。开场页面包含项目提供的《明日方舟》相关第三方素材；其来源与后续处理需由团队确认，不代表本项目拥有这些素材或授予其使用许可。
