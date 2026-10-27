# BLE Wi-Fi 配网

ESP32-S3 固件在既有的 AP/HTTP 配网之外增加了蓝牙低功耗（BLE）配网。
启动渐亮、触摸校准、已保存的 Wi-Fi 优先级与 AP 可见性均未改变。
蓝牙在灯具完成初始启动之后才启动，不会早于第一次亮灯。

## 生命周期与免配网码（2026-10-24）

- 广播名称：`RhodesLight-` 加上站点 MAC 的后六位。
- 启动窗口：至少五分钟。每次加密请求都会续期。
- 若没有成功保存过任何 Wi-Fi 配置，窗口到期后蓝牙仍保持可用。
  一旦保存过配置，到期即停止蓝牙传输；AP 与站点 Wi-Fi 仍然可用。
  `expires_ms: -1` 表示蓝牙正在等待首次配网。
- 显式 `POST /api/wifi/ble` 可重新开启蓝牙。GET 读取其状态。两者都要求
  `X-Rhodes-Control: 1`，并返回 `ready`、`name`、`pop_required: false`、`expires_ms`，
  同时带 `Cache-Control: no-store`。
- Security 1 使用空的（null）可选证明，因此更新后的 App 连接时不需要输入配网码。
  既有的 `rhodes-net/ble-pop` NVS 数据保持原样，但不再使用也不再暴露。
  本地 HTTP 保留其控制请求头检查。
- 这保留了加密，但去掉了所有权证明。附近设备在开启窗口内可以发起配网；
  它无法阻止主动冒充者。没有用固定全局码或 MAC 派生密钥来替代被移除的证明。

即使蓝牙连接成功，AP 配网仍然保留。两者使用同一份八配置存储；
成功选择的网络会成为优先级一。失败的尝试不会替换已保存的凭据。

## GATT 与会话

服务 UUID：`95e7b36c-43f7-4ad0-9e6f-104b0cb54e10`。

| 端点 | 特征 UUID | 用途 |
| --- | --- | --- |
| prov-session | 95e7ff51-43f7-4ad0-9e6f-104b0cb54e10 | Espressif Security 1 |
| proto-ver | 95e7ff52-43f7-4ad0-9e6f-104b0cb54e10 | 先写入 ESP，再读取版本 |
| rhodes-config | 95e7ff53-43f7-4ad0-9e6f-104b0cb54e10 | 加密的配网命令 |

版本响应：`{"rhodes":{"ver":1,"sec_ver":1,"pop_required":false}}`。
Security 1 使用 X25519 与标准的连续 AES-CTR 流，但不混入其可选的 SHA-256 证明。
不存在明文凭据端点，也没有 Security 0 回退。
GATT 配置与 UUID 字节具有静态生命周期，因为 protocomm 会为异步回调保留它们的指针。

可选证明的行为由 Espressif 的
[Security 1 handler](https://github.com/espressif/esp-idf/blob/v4.4.7/components/protocomm/src/security/security1.c#L256)
实现。

## 加密命令帧

所有长度均以 UTF-8 字节计数。帧最长 101 字节；JSON 响应最长 512 字节。
响应包含 `v: 1`，请求级错误对象除外。响应中不含 Wi-Fi 密码。

| 操作 | 精确的请求字节 | 响应 |
| --- | --- | --- |
| 状态 | `01` | MAC、status、attempt_id、IP、SSID、error |
| 开始扫描 | `02` | scanning |
| 扫描分页 | `03 offset` | scanning、generation、networks、next |
| 新凭据 | `04 ssidLen passwordLen ssid password` | accepted、attempt_id |
| 释放配网租约 | `05` | closed |
| 复用已保存密码 | `06 ssidLen ssid` | accepted、attempt_id |

SSID 长度为 1–32 字节。密码长度：开放网络为 0，口令为 8–63 字节，
原始密钥为 64 个十六进制字节。内嵌 NUL、尾部多余或被截断的载荷、以及非法长度都会被拒绝。
扫描快照最多保留 24 个最强的唯一 SSID，每次响应最多两条，
并对转义后的 SSID 做长度保护。`next: -1` 表示分页结束；
`generation` 用于识别被替换的新扫描。条目暴露 SSID、RSSI、secure 与 saved 标志。

状态取值为 ready、connecting、connected 与 failed。客户端跟随被接受的 attempt_id；
连接成功要等既有的网络流程确认 Wi-Fi 与持久化之后才上报。
完整的站点 MAC 是跨 SSID 的稳定身份。蓝牙回调会排队有界请求；
主循环通过既有的配网优先级租约、扫描与连接状态机执行所有射频与配置操作。

## 修复被中断的蓝牙读取（2026-10-24）

ESP-IDF 4.4.7 的传输层把 `read_len` / `read_buf` 作为静态状态跨 ATT 读取与断连保留。
对 53 字节版本响应的一次 22 字节读取被中断后会剩下 31 字节；
新连接就只能收到下一份版本响应的前 31 字节，从而触发 Android 的尾逗号解析错误。
原始回调无法通过新的回归夹具；改为无状态的基于偏移的回调后通过。

本地 vendored 的 Apache-2.0 传输层与配套私有头文件位于
`src/vendor/espidf_protocomm`。固件 map 验证确认新传输层已被链接。
特征容量同时从 257 字节提升到 512 字节，以匹配既有的分页加密响应。
Security 1 保持不变。

## 未配网时的蓝牙可用性（2026-10-24）

当持久的 Wi-Fi 配置列表为空时，蓝牙配网服务现在会超过五分钟继续存活。
配置通常只在连接成功后才保存；遗留的已保存配置会在启动时迁移。
成功配网不会缩短最初五分钟。加密的蓝牙请求仍会续期。
在这段首次配网等待期间，状态会报告 `expires_ms: -1`。AP 保持开启。
