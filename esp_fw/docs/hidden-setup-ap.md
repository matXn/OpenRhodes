# 隐藏配网热点

隐藏 SSID 时，配网 AP 仍照常提供 DHCP、强制门户 DNS 与 HTTP 服务。默认保持可见。
选择写入 `rhodes-net/ap-hidden`，跨重启保留；启动流程与 AP 恢复路径都复用它。
这不会改变已保存的站点网络、它们的优先级、AP 密码或局域网控制。

`GET /api/state` 会公布 `ap_hidden`，但从不包含凭据。

`GET /api/wifi/ap` 返回当前配网 AP 的 `{ssid,password,hidden,ip}`。
`POST /api/wifi/ap` 携带表单 `hidden=1` 或 `hidden=0` 改变可见性，并返回同样确认过的配置。
两个方法都要求现有的 `X-Rhodes-Control: 1` 请求头，并返回 `Cache-Control: no-store, max-age=0`。
该请求头是既有的显式调用检查，不是用户认证。
本接口不暴露已保存的家庭/路由器密码。

修改可见性只编辑当前 AP 配置中的 `ssid_hidden`，保留其信道、认证方式与客户端配置。
配置或持久化失败会返回错误；保存失败时会尝试恢复此前的无线电设置。
重复设置相同值不会产生额外的 NVS 写入。

Android 客户端分别复制 SSID 与密码，密码在用户请求前保持遮罩，并把剪贴板中的密码内容标记为敏感。
手动加入时，在手机的 Wi-Fi 设置中选择隐藏网络与 WPA2-Personal。
