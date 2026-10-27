#pragma once
// 板级硬件与输出映射常量。
// 纯常量，不依赖任何其它 module；任何 module 都可以安全包含它。

// ===== PWM / PMOS 输出配置 =====
// IO18 通过 220R 接 AO3401A 的 Gate，AO3401A 是高边 PMOS。
// PMOS 的 Source 接 3V/3V3，Drain 接 LED 正极 L+。
// 因此 IO18 的逻辑和常见低边 NMOS 相反：
//   - IO18 = HIGH：Gate 接近 Source，Vgs≈0，PMOS 关闭，灯灭。
//   - IO18 = LOW ：Gate 被拉低，Vgs 为负，PMOS 导通，灯亮。
// LEDC 写入的是“raw duty”，这里用 PWM_ACTIVE_LOW 统一做反相，业务层只处理“逻辑亮度”。
#define PWM_PIN     18
#define PWM_CH      0
// 19kHz / 12-bit 需要至少 77.824MHz：setup 显式选择 80MHz APB。
#define PWM_FREQ    19000
#define PWM_RES     12
#define PWM_ACTIVE_LOW true

// ===== PW2053 使能 / LED 供电轨 =====
// IO17 接 PW2053 的 EN 脚，控制 LED 那条 3V 轨（PMOS 的 Source 就挂在这条轨上）。
// 硬件上 PW2053 的 EN 没有任何上下拉，上电后到固件驱动它之前 IO17 是高阻，
// 这条轨的状态是未定义的。所以必须在 setup() 最早期就显式驱动 IO17。
#define RAIL_EN_PIN   17
// false：轨常开。行为最简单可预测，首次点板用这个。
// true ：关灯时同时关断 PW2053。省掉待机功耗，更重要的是待机时
//        触摸焊盘旁边少一个开关电源在跑，触摸噪声更低。确认灯正常后再打开。
#define RAIL_POWER_GATING false
// PW2053 软启动时间。使能后等这么久再让 PMOS 导通，避免轨还没建立就拉负载。
#define RAIL_SETTLE_MS 5

// ===== 亮度范围 =====
// PWM_RES=12 时，硬件计数范围是 0..4095。
// 将最大物理 PWM 占空比限制为 80%；App 的 0..100% 映射到这个允许范围。
#define NUM_PADS    10
#define PWM_MIN     0
// 滑到这个值以下就直接归零关灯。低于 163 的逻辑亮度经 gamma 后一律输出 duty=1，
// 彼此在视觉上无法区分，所以在这里截断不会损失任何可用档位，
// 却能保证滑到底部时真的关灯，而不是停在 0.3% 这种“显示 0% 但还亮着”的状态。
#define BRIGHTNESS_OFF_FLOOR (PWM_MAX_DUTY / 100)
// ESP32 LEDC 在 N 位分辨率下占空比范围是 0 ~ 2^N，要输出 100% 必须写 2^N。
// 写 2^N-1 只有 99.994%，active-low 时关灯会残留 1 个 LSB 的低电平脉冲，PMOS 微导通。
#define PWM_FULL_DUTY (1 << PWM_RES)
#define PWM_OUTPUT_LIMIT_PERCENT 80
#define PWM_MAX_DUTY (PWM_FULL_DUTY * PWM_OUTPUT_LIMIT_PERCENT / 100)
#define FADE_STEP_DUTY ((PWM_MAX_DUTY / 128) > 1 ? (PWM_MAX_DUTY / 128) : 1)
// 人眼感知亮度曲线。
// HomeKit 和触摸保存的是线性的“逻辑亮度”，真正输出到 PWM 前再做 gamma 映射。
// gamma 越高，低亮度段占用的逻辑区间越大，肉眼看起来暗部变化会更细。
// 3.9 远超人眼感知曲线（约 2.2），把低亮度段压没了。改成 2.2 后 1%~13% 段内
// 可分辨档位从 1 个变成 92 个。注意：最暗档不会因此变亮，gamma 只决定行程怎么分配。
#define PWM_GAMMA    2.2f

// ===== 配网 AP 与调试开关 =====
// WiFiManager 配网 AP 名称；未连接到已保存 WiFi 时会广播这个热点。
// 用 inline constexpr char[]（而不是 const char*）才能真正摆脱对定义顺序的依赖。
inline constexpr char CONFIG_AP_NAME[] = "RhodesLight-Setup";
// 硬件诊断模式：跳过网络/触摸/HomeKit。HTTP module 也要读它。
inline constexpr bool DISABLE_NETWORK_TEST = false;
