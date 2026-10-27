# 硬件身份与 mDNS 验证

ESP32 通过 `/api/state` 暴露完整的站点 MAC 与 `rhodes-<12 hex digits>` 身份（后缀为 12 位十六进制数字）。
AP 与站点共用这个逻辑身份；IP 地址和 Wi-Fi 名称都不是设备身份。

mDNS 运行在 ESP-IDF 任务中。应用在站点关联之前就先在 AP 上启动它，
每两秒重试一次初始化或服务失败，并在站点地址变化时重启响应器。
HTTP 服务的实例名包含 MAC 后缀，TXT 记录包含完整的 `mac`、`device_id` 与 `model`。
主机名保持 `rhodeslight.local`。

Android NSD 负责发现与解析服务；应用状态轮询则单独走 HTTP。
提高 HTTP 轮询频率不会改变 mDNS 查询时序，也不会绕过网络的组播过滤。
