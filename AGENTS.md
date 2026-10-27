# OpenRhodes

ESP32-S3 灯具固件与 Android 客户端。**当前固件源码在 `esp_fw/`，Android 工程在 `app/`**；仓库根目录的 `src/`、`web/`、
`tools/`、`platformio.ini` 是早期固件的历史基线，不参与日常开发。

构建、烧录、硬件参数与接口表见 [`esp_fw/README.md`](esp_fw/README.md)。

项目文档、沟通及新增注释使用中文；代码标识符、协议字段、命令和第三方原始许可保持原样。

## Agent skills

### Issue tracker

issue 保存在 GitHub Issues（`matXn/OpenRhodes`）中，通过 `gh` CLI 读写。参见 `docs/agents/issue-tracker.md`。

### Triage labels

使用五种标准分诊角色，标签名与角色名相同。参见 `docs/agents/triage-labels.md`。

### Domain docs

单上下文布局：根目录 `CONTEXT.md` 与 `docs/adr/`。参见 `docs/agents/domain.md`。
