#include <Arduino.h>
#include <driver/gpio.h>
#include <driver/ledc.h>
#include <esp_system.h>
#include <driver/touch_sensor.h>
#include <WebServer.h>
#include <DNSServer.h>
#include <ESPmDNS.h>
#include <Preferences.h>
#include <WiFi.h>


#include "board_config.h"
#include "light_contract.h"
#include "http_control.h"
#include "ble_provision.h"

// ===== 触摸手势参数 =====
// 触摸滑动按 IO1..IO10 的焊盘序号变化量计算。
// centroid 是一个 0..9 的浮点位置，表示当前触摸中心落在哪两个焊盘之间。
// BRIGHTNESS_PER_PAD 表示手指移动 1 个焊盘间距时，逻辑亮度变化多少。
// 现在设置为 160，大约 12~13 个焊盘间距可以走完整个 0..50% 输出范围。
// ===== 滑动行程 → 亮度的分段线性映射 =====
// 不再用单一的"每焊盘固定亮度增量"，而是按需求分配行程：
//   低亮度段（0 ~ 30% 亮度，黑暗环境下不刺眼的区间）吃掉 120% 的滑动行程；
//   高亮度段（30% ~ 100%）只占 80%。
//   合计 200% —— 也就是从最暗划到最亮需要滑两次，恢复原来的手感。
// 效果：低段每焊盘约 227 亮度单位，高段约 796，相差 3.5 倍，
//       所以夜里调暗时手感很细，而白天拉到全亮又不用划半天。
#define SLIDER_LOW_BRIGHT_FRAC 0.30f    // 低段占总亮度的比例
#define SLIDER_LOW_TRAVEL_FRAC 0.60f    // 低段占总行程的比例 = 120% / 200%
#define SLIDER_FULL_SWIPES     2.0f     // 从最暗到最亮需要几次全条滑动
// 手指移动 1 个焊盘间距，对应"滑条位置"(0..1) 前进多少
#define SLIDER_PER_PAD (1.0f / (SLIDER_FULL_SWIPES * (NUM_PADS - 1)))
// ===== 力度感应增益 =====
// 电容量受接触面积、皮肤状态等影响，只作为力度的近似指标。
// 轻触 → 变化更细。增益在开始滑动前、最多 160ms 内取峰值确定，
// 之后整个这一次滑动保持不变 —— 否则滑到一半力度变了，
// “起点亮度 + 位移 x 增益”这个绝对式映射会整体跳一下。
#define PRESSURE_GAIN_ENABLE   true
#define PRESSURE_REF           60000.0f  // 参考力度，实测正常按压 rawTotal 在 6千~14万
#define PRESSURE_GAIN_MIN      0.5f
#define PRESSURE_GAIN_MAX      2.0f
#define PRESSURE_SETTLE_MS     160
// 点按判定收紧：原来 1200ms / 1.5 个焊盘过于宽松，
// "落指-犹豫-抬手"这种准备滑动的动作会被误判成点按，把灯关掉。
// 有意的轻点通常在 80~200ms 之内，500ms 已经留足余量。
#define CLICK_MAX_MS         500
// 点按容许指腹落下和抬起的轻微漂移；已进入滑动的手势始终不执行点按。
#define CLICK_MAX_MOVEMENT   0.55f
#define SWIPE_MIN_MOVEMENT   2.0f
// 单个焊盘变化量小于 NOISE_DELTA 时视为噪声，不参与触摸位置计算。
// 开硬件 IIR 滤波后空闲噪声从峰值 81 降到 ~20，所以门限从 80 降到 30：
// 一方面让邻近焊盘的小信号能参与插值（滑动更连续），一方面轻触也更容易被认到。
// 配合上面的软阈值，跨门限不会造成 centroid 跳变。
#define NOISE_DELTA          30
// 所有有效焊盘变化量累加后必须超过这个值，才认为是真的触摸。
// 真实触摸的 delta 实测在 5万~12万量级，320 这个门限是按旧噪声定的，过于保守。
// 降到 150 让轻触和焊盘之间的位置也能被认到；离真实信号仍有近 1000 倍余量。
// 实测：环境干扰的总能量是 227~1022，真实手指是 136140。差两个数量级，
// 起始门限改为 700，配合严格的局部形状门限和 20ms 确认接收轻触。
#define TOUCH_TOTAL_MIN      700.0f
// "峰值焊盘 ± 1"窗口内的能量占总能量的比例。手指连续接触 → 接近 1.00；
// 环境干扰是散的（两端都有信号、中间是 0）→ 实测 0.39~0.72。0.85 落在空档里。
#define TOUCH_WINDOW_RATIO_MIN 0.85f
// ===== 形状判据：区分"手指"和"桌面等大面积耦合" =====
// 手指是局部的，峰值焊盘占总量 70%~97%（实测）；桌面耦合是弥散的，10 个焊盘一起小幅抬起，
// 峰值占比只有 10%~25%。两者的绝对幅度会重叠，所以提高 NOISE_DELTA / TOUCH_TOTAL_MIN 没用，
// 必须看形状。0.40 落在两者之间的空档里，余量很大。
#define TOUCH_PEAK_RATIO_MIN 0.40f
// 因形状被否决时，让基线正常跟踪把这个偏移学掉：
// 放到桌上后约 0.6 秒基线就吸收完，恢复正常灵敏度。
#define FADE_STEP_MS         8
// 手机端（HomeKit）调节亮度时的渐变时长：走完整个量程约需这么久，小幅度调节按比例更快。
// 这是非阻塞的，由 loop 里的 serviceOutputFade() 推进，不会卡住 HomeSpan 和触摸。
// 触摸调光不走这条路——它有自己的实时平滑（SLIDE_SMOOTH_ALPHA），必须跟手。
#define REMOTE_FADE_MS       400
#define STARTUP_FADE_MS      1200
// 连续 TOUCH_STABLE_FRAMES 帧检测到触摸，才进入触摸状态，避免瞬时噪声触发。
// ===== 采样率 =====
// loop 的节拍。硬件触摸 FSM 约 560Hz 出一次新数据（11 通道 x 162.5us），
// 所以软件读取超过 ~560Hz 就只是在重复读同一个值，没有意义。
// 10ms = 100Hz 是个安全的提速档；能不能真跑到看 TUI 上的实测值
// （HomeSpan poll 和 WiFi 会吃掉一部分时间）。
#define LOOP_TARGET_MS       10
// 下面这些原来是按"帧数"计的，改采样率会连带把时长减半。
// 现在全部改成按"时间"，用实测的 loopIntervalMs 换算成帧数，
// 这样改 LOOP_TARGET_MS 时手感保持不变，采样率变成一个干净的独立变量。
#define TOUCH_STABLE_MS      20
#define TOUCH_STABLE_FRAMES  3
// 连续 TOUCH_RELEASE_FRAMES 帧没有触摸，才认为手指真正松开。
#define TOUCH_RELEASE_FRAMES 8
// 点按（手指没移动）时用更短的松手确认，避免抬手后还要等 160ms 灯才反应。
#define TOUCH_RELEASE_FRAMES_TAP 3
// ===== 触摸滞回 =====
// 起始门限严（防环境干扰），保持门限松（防滑动途中短暂掉线）。
// 手指滑过焊盘之间的间隙时信号会明显变弱，用同一套严门限判定就会误判成松手，
// 表现就是“还没滑完亮度就被固定了”。
#define TOUCH_HOLD_TOTAL_SCALE      0.35f   // 保持时的幅度门限 = 起始门限 x 这个系数
#define TOUCH_HOLD_WINDOW_RATIO_MIN 0.70f   // 保持时的窗口占比门限
// 触摸连续超过这个时长一定是异常（基线漂移卡死等），强制松手并重新校准。
// 触摸期间基线是冻结的，不加这个看门狗会永久卡在假触摸里。
#define TOUCH_STUCK_TIMEOUT_MS 30000
// 触摸刚开始时先不立刻调光；移动超过这个死区后，才判定为滑动。
#define TOUCH_MOVE_DEADBAND  0.35f
// ===== centroid 与滑动平滑 =====
// 实测相邻焊盘的响应差 8~92 倍（IO3=14378 / IO4=120459 / IO5=1303），
// 线性加权时峰值焊盘占 88.5% 的权重，centroid 会被"吸附"到焊盘位置上，滑动就一格一格跳。
// 用 sqrt 压缩权重后峰值占比降到约 69%，邻居能真正参与插值。
// 1.0=线性(原行为)，0.5=开方(推荐)，0.25=更强压缩但更容易被远处噪声拉偏。
#define CENTROID_WEIGHT_POW   0.5f
// centroid 一阶 IIR。50Hz 下 alpha=0.4 → 时间常数约 50ms。
#define CENTROID_SMOOTH_ALPHA 0.4f
// 滑动时亮度向目标值一阶趋近，消除单帧跳变。alpha=0.5 → 约 40ms。
#define SLIDE_SMOOTH_ALPHA    0.5f
// 滑动时亮度变化的速率上限：亮度从 0 走到满，最快也要花这么久。
// 250ms 的取值依据：
//   - 人手最快的一次划动约 300ms，所以正常操作完全不会被限速削到，手感不变；
//   - 单帧(20ms)最多变化 8% 量程，经 gamma 后只有约 0.2% 占空比，肉眼看不见；
//   - 于是落指瞬间 centroid 抖动造成的单帧尖峰被压成看不见的量，闪烁消失。
// 按时间计算而不是按帧，所以 loop 速率变化时行为不变。
#define SLIDE_MAX_RATE_MS     150
// 一阶平滑改用时间常数，alpha 由 loopIntervalMs 现算，和采样率解耦。
// 位置慢滑 20ms、快滑 10ms；亮度 12ms，减少串联滤波的延迟。
#define CENTROID_SMOOTH_TAU_MS 20.0f
#define SLIDE_SMOOTH_TAU_MS    12.0f
// 基线跟踪时间常数。原来是固定 32 帧，50Hz 下正好 640ms。
#define BASELINE_TAU_MS        640.0f
// 本地触摸状态同步到 HomeKit 的最短间隔，避免频繁写 characteristic。
// 本地状态推送到 HomeKit 的最短间隔。这是流控（避免 50Hz 刷 notification 把 iOS 刷爆），
// 不是防冲突，滑动时保留。150ms → 约 6.7Hz，Home App 的滑块跟得上。
#define HOMEKIT_SYNC_MS      150
// 之前为了"防止指令打架"加了一整套屏蔽：触摸期间忽略 HomeKit 回写、HomeKit 写入后忽略触摸、
// 滑动全程不同步只在松手时发一次。实测不存在这个冲突，全部关掉。
// 万一回弹问题重现，把这里改回 true 就能一键恢复旧行为。
#define HOMEKIT_COLLISION_GUARD false
#define WIFI_CONNECT_TIMEOUT_MS 8000
// HomeKit 写入后短暂屏蔽触摸，避免触摸噪声立刻把旧状态写回去。
#define TOUCH_INHIBIT_AFTER_HOMEKIT_MS 400
// 触摸结束后此时间内收到的 HomeKit 回写视为 iOS 反馈回路，直接忽略。
#define HOMEKIT_ECHO_GUARD_MS 800
// 关灯状态下长按触摸条 10 秒，清除 HomeKit 配对信息并重启。
// ⚠️ 关灯状态下长按触摸条 10 秒会清除 HomeKit 配对。放桌上出现的环境干扰有可能凑满这个条件，
// 代价是配对丢失、要重新添加配件。默认关闭 —— 需要清配对时串口发 HomeSpan 的 H 命令即可。
#define ENABLE_TOUCH_HOMEKIT_RESET false
#define HOMEKIT_RESET_HOLD_MS 10000
// 长按清除 HomeKit 时，如果手指移动太多，就取消清除，避免滑动调光误触发重置。
#define HOMEKIT_RESET_MAX_MOVEMENT 2.0f
// 上电初次校准后约 10 秒尝试二次校准，用来修正上电时手指靠近/按住导致的错误 baseline。
#define TOUCH_BOOT_RECAL_DELAY_MS 10000
// 二次校准前，原始 touchRead 必须连续保持“像空闲”一段时间。
#define TOUCH_BOOT_RECAL_STABLE_MS 1200
// 原始 touchRead 超过这个值时认为可能仍有手指触摸，不执行二次校准。
#define TOUCH_BOOT_RECAL_MAX_RAW 40000

// ===== 触摸硬件滤波 =====
// Arduino core 在 S3 上的 touchRead() 调用的是 touch_pad_read_raw_data()，读的是**未滤波**的
// 原始值；core 只开了 denoise 通道（扣共模），IIR 滤波器在 S2/S3 分支里根本没启用。
// 这里手动开硬件 IIR，并改用 touch_pad_filter_read_smooth() 取平滑值。
//
// 延迟分析：硬件 FSM 每轮扫描 ≈1.79ms（≈560Hz），而软件 loop 只有 50Hz。
// IIR_2 的时间常数 = 2 个 FSM 周期 ≈ 3.6ms，不到软件一帧(20ms)的 1/5，感知不到。
// 噪声还是大就往上调 IIR_4 / IIR_8（对应 ≈7ms / ≈14ms，仍小于一帧）。
#define TOUCH_HW_FILTER     true
#define TOUCH_SMOOTH_LEVEL  TOUCH_PAD_SMOOTH_IIR_2

// 10 个触摸焊盘从左到右接在 IO1..IO10。
// S3 上触摸通道号和 GPIO 号一致（GPIO1 = TOUCH1），所以可以直接转成 touch_pad_t。
const int TOUCH_PINS[NUM_PADS] = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};

// CONFIG_AP_NAME 定义在 board_config.h（HTTP module 也要用）。
// HomeKit 配对码实际传给 HomeSpan 需要 8 位数字；iOS 显示时会自动分组。
const char* HOMEKIT_SETUP_CODE      = "00000000"; // 未使用的旧 HomeKit 占位值
// 下面几个开关只用于调试/一次性清理，默认都关闭，避免正常烧录后反复清空状态。
const bool RESET_HOMEKIT_ID_ONCE    = false;
const bool RESET_WIFI_ON_BOOT_ONCE  = false;
const bool FIXED_PWM_TEST           = false;
// 硬件诊断模式：跳过网络/触摸/HomeKit，绕开 LEDC，直接用 GPIO 控制 IO18。
// IO17 拉高开轨；IO18 在「低 5 秒（PMOS 全导通，灯全亮）」和「高 5 秒（PMOS 关断，灯灭）」
// 之间切换。每个相位 5 秒，万用表能稳定读数，两种状态都能测到。
// 测完务必改回 false。
const bool LED_DC_TEST              = false;
// DISABLE_NETWORK_TEST 定义在 board_config.h（HTTP module 也要读它）。
// TUI 仪表盘：用 ANSI 转义序列原地刷新，不滚屏。开启后关掉所有零散的行式日志，
// 否则它们会插进来把画面冲乱。需要看原始流水日志时把它改成 false。
const bool SERIAL_TUI               = true;
const bool DEBUG_LIGHT_EVENTS       = false;
const int FIXED_PWM_TEST_DUTY       = PWM_MAX_DUTY;

uint32_t baseline[NUM_PADS];
// ledOn 表示业务层开关状态；brightness 表示业务层目标亮度。
// 注意 brightness 不是 LEDC raw duty，而是 0..PWM_MAX_DUTY 的“逻辑亮度”。
// 这样 HomeKit、触摸、Preferences 都不用关心 PMOS 反相和 gamma 曲线。
bool ledOn = false;
int brightness = PWM_MAX_DUTY;

// ===== 触摸手势状态 =====
// 每次触摸开始时先按“可能是点击”处理，只有移动超过 TOUCH_MOVE_DEADBAND 后才进入滑动调光。
// 这样短按可以作为开关，不会因为手指轻微抖动变成调光。
bool touching = false;
// 记录触摸开始时灯是否打开；松手时如果判定为短按，就用它决定 toggle 方向。
bool touchStartedLedOn = false;
// 长按清除 HomeKit 的状态位，防止一次长按重复执行清除。
bool homeKitResetHoldTriggered = false;
// 只有“触摸开始时灯是关闭的”才允许长按清除 HomeKit，避免正常调光时误清配对。
bool homeKitResetHoldEligible = false;
// 本次触摸是否已经进入滑动调光；松手时用它决定是保存亮度还是执行点击开关。
bool touchAdjustedBrightness = false;
// 触摸开始瞬间的亮度基准；滑动目标亮度 = 这个基准 + 手指位移 * BRIGHTNESS_PER_PAD。
int touchStartBrightness = PWM_MIN;
// 触摸开始时间，用于区分短按、长按和普通滑动。
uint32_t touchStartMs = 0;
// HomeKit 写入后的触摸屏蔽截止时间；用于排除触摸噪声把 HomeKit 目标值覆盖掉。
uint32_t touchInhibitUntilMs = 0;
// 触摸开始位置和最近一次有效位置，单位都是焊盘序号，范围大约 0..9。
float touchStartCentroid = -1.0f;
float lastCentroid = -1.0f;
// 连续确认计数。触摸和松手都要经过多帧确认，减少电源/RF 噪声造成的误判。
int touchStableFrames = 0;
int touchReleaseFrames = 0;
// HomeKit 同步限速时间戳，避免触摸调光时每 20ms 都写一次 HomeKit。
uint32_t lastHomeKitSyncMs = 0;
// 最近一次触摸结束时间，用于屏蔽 iOS 反馈回路。
uint32_t lastTouchReleaseMs = 0;
// 如果触摸保护窗口里忽略了 HomeKit 回写，窗口结束后再主动同步一次当前本地状态。
bool homeKitNeedsResync = false;
// 二次校准状态。bootRecalDueMs 到期后，等待原始触摸值稳定空闲再校准。
bool bootRecalibrated = false;
uint32_t bootRecalDueMs = 0;
uint32_t bootRecalIdleSinceMs = 0;

Preferences prefs;
Preferences homeKitPrefs;
Preferences systemPrefs;


uint32_t lastStatusPrintMs = 0;
uint32_t lastTouchDebugMs = 0;
uint32_t lastSlideDebugMs = 0;
bool shouldSaveConfig = false;
// HomeSpan 只能 begin 一次；homeKitReady 用来避免重复创建 HomeKit accessory。
bool homeKitReady = false;
// wifiManagerActive / wifiConnectStartMs 现由 http_control.cpp 拥有（见 http_control.h 的 extern 声明）。

// NetworkState 枚举与 networkState 定义已移到 http_control.cpp；
// 本文件通过 light_contract.h / http_control.h 使用它们。

// 呼吸函数参数（网页可调，存 NVS）：
// 一个周期内 w = (0.5 + 0.5·cos φ)^(curve/100)，输出 = brightness·(floor + (1-floor)·w)
#define BREATH_PERIOD_MIN_MS 2000
#define BREATH_PERIOD_MAX_MS 20000
#define BREATH_FLOOR_MAX_PCT 80
#define BREATH_CURVE_MIN_PCT 50
#define BREATH_CURVE_MAX_PCT 300
constexpr int BREATH_DEFAULT_PERIOD_MS = 6800;
constexpr int BREATH_DEFAULT_FLOOR_PCT = 13;
constexpr int BREATH_DEFAULT_CURVE_PCT = 60;
int breathPeriodMs = BREATH_DEFAULT_PERIOD_MS;   // 一次完整呼吸的时长
int breathFloorPct = BREATH_DEFAULT_FLOOR_PCT;   // 最暗时保留的亮度，占设定亮度的百分比
int breathCurvePct = BREATH_DEFAULT_CURVE_PCT;   // 波形指数×100：100=余弦，<100 更饱满


// 读取上次保存的灯光状态，并在 PWM 分辨率变化时迁移旧亮度值。
// savedRes 用来兼容旧固件：之前可能用 8-bit PWM，现在是 12-bit PWM。
// 如果不做迁移，旧的亮度值会被当成新分辨率下的很小值，表现为升级后亮度突然变暗。
void loadPrefs() {
  prefs.begin("light", false);
  int savedRes = prefs.getInt("pwmRes", 8);
  int savedBrightness = prefs.getInt("brightness", PWM_MAX_DUTY);
  if (savedRes != PWM_RES && savedRes > 0 && savedRes <= 15) {
    int oldMaxDuty = (((1 << savedRes) - 1) / 2);
    if (oldMaxDuty > 0) {
      savedBrightness = (savedBrightness * PWM_MAX_DUTY + (oldMaxDuty / 2)) / oldMaxDuty;
    }
  }
  brightness = constrain(savedBrightness, PWM_MIN, PWM_MAX_DUTY);
  ledOn = prefs.getBool("ledOn", false);
  breathPeriodMs = constrain(prefs.getInt("brPeriod", BREATH_DEFAULT_PERIOD_MS), BREATH_PERIOD_MIN_MS, BREATH_PERIOD_MAX_MS);
  breathFloorPct = constrain(prefs.getInt("brFloor", BREATH_DEFAULT_FLOOR_PCT), 0, BREATH_FLOOR_MAX_PCT);
  breathCurvePct = constrain(prefs.getInt("brCurve", BREATH_DEFAULT_CURVE_PCT), BREATH_CURVE_MIN_PCT, BREATH_CURVE_MAX_PCT);
  prefs.end();
}

// 保存业务层灯光状态，不保存 LEDC raw duty。
// 这样以后修改 PMOS/NMOS 方向、PWM 分辨率或 gamma 曲线时，已保存亮度仍然可迁移。
// NVS 写入是阻塞的，几十毫秒起步。HomeKit 拖滑块时每次写入都调一次
// 会造成明显卡顿，还会加速 flash 磨损。改成标脏 + 延迟合并写入。
bool prefsDirty = false;
uint32_t prefsDirtyMs = 0;
void markPrefsDirty() { prefsDirty = true; prefsDirtyMs = millis(); }

void savePrefs() {
  prefs.begin("light", false);
  prefs.putInt("brightness", brightness);
  prefs.putBool("ledOn", ledOn);
  prefs.putInt("pwmRes", PWM_RES);
  prefs.putInt("brPeriod", breathPeriodMs);
  prefs.putInt("brFloor", breathFloorPct);
  prefs.putInt("brCurve", breathCurvePct);
  prefs.end();
}

// 只在手指离开、且状态稳定一段时间之后才真正落盘。
// 连续调光期间反复标脏只会不断推迟写入，最终合并成一次。
#define PREFS_SAVE_DELAY_MS 1500
void servicePrefsSave() {
  if (!prefsDirty || touching) return;
  if (millis() - prefsDirtyMs < PREFS_SAVE_DELAY_MS) return;
  prefsDirty = false;
  savePrefs();
}

void saveConfigCallback() {
  shouldSaveConfig = true;
}

int getPwmDuty();

int getRawPwmDuty() {
  return ledcRead(PWM_CH);
}

int getPhysicalPwmDuty() {
  int rawDuty = getRawPwmDuty();
  int physicalDuty = PWM_ACTIVE_LOW ? (PWM_FULL_DUTY - rawDuty) : rawDuty;
  return constrain(physicalDuty, PWM_MIN, PWM_MAX_DUTY);
}

const char* networkStateName() {
  switch (networkState) {
    case NET_OFF: return "NET_OFF";
    case NET_CONNECTING: return "NET_CONNECTING";
    case NET_PORTAL: return "NET_PORTAL";
    case NET_CONNECTED: return "NET_CONNECTED";
  }
  return "NET_UNKNOWN";
}

const char* touchPhaseName() {
  if (HOMEKIT_COLLISION_GUARD &&
      (int32_t)(touchInhibitUntilMs - millis()) > 0 && !touching) return "HOMEKIT_INHIBIT";
  if (touching && homeKitResetHoldTriggered) return "RESET_TRIGGERED";
  if (touching && touchAdjustedBrightness) return "SLIDING";
  if (touching && homeKitResetHoldEligible) return "HOLD_RESET_ARMED";
  if (touching) return "TOUCH_HELD";
  if (touchStableFrames > 0) return "TOUCH_DEBOUNCE";
  if (touchReleaseFrames > 0 && touchReleaseFrames < TOUCH_RELEASE_FRAMES) return "RELEASE_DEBOUNCE";
  return "IDLE";
}

// 输出一次灯光状态调试日志，用于定位亮度回弹来自触摸、HomeKit 还是渐变路径。
void logLightEvent(const char* source) {
  if (!DEBUG_LIGHT_EVENTS) return;

  Serial.print("[light] ");
  Serial.print(source);
  Serial.print(" ledOn=");
  Serial.print(ledOn ? "1" : "0");
  Serial.print(" brightness=");
  Serial.print(brightness);
  Serial.print(" logicalPwm=");
  Serial.print(getPwmDuty());
  Serial.print(" physicalPwm=");
  Serial.print(getPhysicalPwmDuty());
  Serial.print(" rawPwm=");
  Serial.print(getRawPwmDuty());
  Serial.print(" touching=");
  Serial.print(touching ? "1" : "0");
  Serial.print(" adjusted=");
  Serial.print(touchAdjustedBrightness ? "1" : "0");
  Serial.print(" stable=");
  Serial.print(touchStableFrames);
  Serial.print(" release=");
  Serial.print(touchReleaseFrames);
  Serial.print(" start=");
  Serial.print(touchStartBrightness);
  Serial.print(" startC=");
  Serial.print(touchStartCentroid, 2);
  Serial.print(" lastC=");
  Serial.println(lastCentroid, 2);
}

// HomeKit 写入后短暂屏蔽触摸；手指仍在滑动时不强制清空触摸会话。
// 这样可以避免 HomeKit 回调插入时把一次滑动切成多次新触摸。
void inhibitTouchAfterHomeKit() {
  touchInhibitUntilMs = millis() + TOUCH_INHIBIT_AFTER_HOMEKIT_MS;
  // 只有手指已经松开时才重置触摸状态机。
  // 如果手指还在滑动中，不强制清零 touching，否则下一帧重建会话并跳变亮度。
  if (!touching) {
    touchAdjustedBrightness = false;
    touchStableFrames = 0;
    touchReleaseFrames = TOUCH_RELEASE_FRAMES;
  }
}

// 判断当前是否处于触摸优先窗口；窗口内忽略 HomeKit 回写，防止旧值覆盖触摸结果。
// lastTouchReleaseMs 为 0 表示还没有发生过触摸释放，避免刚开机时误屏蔽 HomeKit 写入。
bool isTouchHomeKitGuardActive() {
  if (!HOMEKIT_COLLISION_GUARD) return false;
  return touching ||
         (lastTouchReleaseMs != 0 && millis() - lastTouchReleaseMs < HOMEKIT_ECHO_GUARD_MS);
}

// 在 LEDC 接管 IO18 前，尽早把 PMOS Gate 拉高关断。
// 这只能减少软件启动阶段的亮灯；如果硬件 Gate 默认下拉，上电最早瞬间仍可能亮一下。
void forcePwmOffEarly() {
  pinMode(PWM_PIN, OUTPUT);
  digitalWrite(PWM_PIN, HIGH);
}

// LED 供电轨（PW2053）当前状态。
bool railEnabled = false;

// 尽早把 LED 轨拉到确定状态。必须在 forcePwmOffEarly() 之后调用：
// 先让 PMOS 栅极为高（关断），再动轨，否则 Gate 上的 100k/100k 分压
// 会在轨建立的瞬间让 PMOS 半导通，表现为上电闪一下。
void forceRailOffEarly() {
  // 用 INPUT_OUTPUT 而不是纯 OUTPUT：输入缓冲器保持打开，
  // 这样 gpio_get_level() 读到的是引脚上真实的电压，而不是输出寄存器的值。
  // 驱动高电平时如果回读仍然是 0，说明引脚在外部被拉死（短路或重载）。
  gpio_set_direction((gpio_num_t)RAIL_EN_PIN, GPIO_MODE_INPUT_OUTPUT);
  gpio_set_level((gpio_num_t)RAIL_EN_PIN, 0);
  railEnabled = false;
}

// 回读 IO17 引脚上的实际电平。
int railPadLevel() {
  return gpio_get_level((gpio_num_t)RAIL_EN_PIN);
}

// 实测软件采样间隔（loop 周期）。用来确认滤波/网络任务有没有拖慢触摸采样。
float loopIntervalMs = 20.0f;
// 把"毫秒"换算成当前采样率下的帧数 / 一阶滤波系数。
int framesFor(float ms)  { int n = (int)(ms / loopIntervalMs + 0.5f); return n < 1 ? 1 : n; }
float alphaFor(float tauMs) { float a = loopIntervalMs / tauMs; return a > 1.0f ? 1.0f : (a < 0.02f ? 0.02f : a); }
void updateLoopRate() {
  static uint32_t last = 0;
  uint32_t now = millis();
  if (last != 0) {
    uint32_t dt = now - last;
    if (dt > 0 && dt < 1000) loopIntervalMs = loopIntervalMs * 0.9f + (float)dt * 0.1f;
  }
  last = now;
}

// 使能/关断 PW2053。使能后等待软启动完成再返回。
void setRailEnabled(bool on) {
  if (on == railEnabled) return;
  gpio_set_level((gpio_num_t)RAIL_EN_PIN, on ? 1 : 0);
  railEnabled = on;
  if (on) delay(RAIL_SETTLE_MS);
}

// 初始化串口调试。这里刻意不等待 USB 主机枚举，也不在亮灯前打印：
// 灯由充电头供电时 Serial 永远不会就绪，而 USB 已枚举但没人读时写日志还可能阻塞。
// 亮灯后的日志和 TUI 仍然可用。
void initSerialDebug() {
  Serial.begin(115200);
}

// 把业务层逻辑亮度转换成 LEDC raw duty。
// logicalDuty：0=灭，PWM_MAX_DUTY=允许范围内最亮。
// physicalDuty：经过 gamma 后真正给 LED 的导通占空比。
// raw duty：写入 LEDC 的寄存器值；PMOS active-low 时需要把 physicalDuty 反相。
int toRawDuty(int logicalDuty) {
  int duty = constrain(logicalDuty, PWM_MIN, PWM_MAX_DUTY);
  int physicalDuty = 0;
  if (duty > 0) {
    float normalized = (float)duty / (float)PWM_MAX_DUTY;
    physicalDuty = (int)(powf(normalized, PWM_GAMMA) * PWM_MAX_DUTY + 0.5f);
    physicalDuty = constrain(physicalDuty, 1, PWM_MAX_DUTY);
  }
  // PMOS 是低电平导通，所以写入 LEDC 前要把逻辑亮度反相。
  return PWM_ACTIVE_LOW ? (PWM_FULL_DUTY - physicalDuty) : physicalDuty;
}

// 调试时把 LEDC raw duty 反推回逻辑亮度；正常控制链路不依赖这个函数。
// 因此 gamma 曲线改动不会反向污染保存值。
int fromRawDuty(int rawDuty) {
  int duty = constrain(rawDuty, 0, PWM_FULL_DUTY);
  int physicalDuty = PWM_ACTIVE_LOW ? (PWM_FULL_DUTY - duty) : duty;
  physicalDuty = constrain(physicalDuty, PWM_MIN, PWM_MAX_DUTY);
  if (physicalDuty <= 0) return 0;
  float normalized = (float)physicalDuty / (float)PWM_MAX_DUTY;
  int logicalDuty = (int)(powf(normalized, 1.0f / PWM_GAMMA) * PWM_MAX_DUTY + 0.5f);
  return constrain(logicalDuty, PWM_MIN, PWM_MAX_DUTY);
}

// 所有亮度输出都经过这里，所以轨的开关也统一挂在这一层，
// 渐变、bootBlink、HomeKit、触摸全部自动覆盖。
// 顺序不能反：开灯先给轨供电再开 PMOS，关灯先关 PMOS 再断轨。
// 当前实际输出的逻辑亮度。渐变引擎用它做起点，
// 不用 getPwmDuty() 是因为那要做一次 gamma 反算，低亮度段来回取整会丢精度。
int currentOutputDuty = 0;
// 启动时灯亮起的固件 uptime（0 表示启动时处于关灯状态），供 /api/state 核对。
uint32_t bootLightOnMs = 0;
bool bootLightTracking = false;

void setPwmDuty(int logicalDuty) {
  currentOutputDuty = constrain(logicalDuty, PWM_MIN, PWM_MAX_DUTY);
  if (RAIL_POWER_GATING && logicalDuty > 0) setRailEnabled(true);
  // Arduino 2.x ledcWrite remaps (2^N-1) to full-on. For active-low
  // output that erases the shortest ON pulse; write the exact count instead.
  const ledc_mode_t mode = (ledc_mode_t)(PWM_CH / 8);
  const ledc_channel_t channel = (ledc_channel_t)(PWM_CH % 8);
  esp_err_t result = ledc_set_duty(mode, channel, toRawDuty(logicalDuty));
  if (result == ESP_OK) result = ledc_update_duty(mode, channel);
  if (result == ESP_OK && bootLightTracking && bootLightOnMs == 0 && currentOutputDuty > 0) {
    bootLightOnMs = millis();
  }
  if (result != ESP_OK && Serial) Serial.printf("PWM write ERROR: %d\n", (int)result);
  if (RAIL_POWER_GATING && logicalDuty <= 0) setRailEnabled(false);
}

int getPwmDuty() {
  return fromRawDuty(ledcRead(PWM_CH));
}

// 统一输出灯光状态。HomeKit、触摸、开机恢复都走这里。
// ledOn=false 时不改变 brightness，只把实际输出关掉；下次开灯还能恢复目标亮度。
void applyLightOutput() {
  setPwmDuty(ledOn ? constrain(brightness, PWM_MIN, PWM_MAX_DUTY) : 0);
}

// ===== 滑条位置 <-> 逻辑亮度 的分段线性映射 =====
// 滑条位置 s 是 0..1 的虚拟坐标，代表"从最暗到最亮的整个行程"，跨越两次滑动。
// 手指移动只在 s 上做线性加减，非线性全部由这两个函数承担。

// s(0..1) → 逻辑亮度
int sliderToBrightness(float s) {
  s = constrain(s, 0.0f, 1.0f);
  float frac;
  if (s < SLIDER_LOW_TRAVEL_FRAC) {
    // 低段：60% 的行程走完 30% 的亮度 —— 每单位行程的亮度变化只有高段的 1/3.5
    frac = (s / SLIDER_LOW_TRAVEL_FRAC) * SLIDER_LOW_BRIGHT_FRAC;
  } else {
    frac = SLIDER_LOW_BRIGHT_FRAC +
           (s - SLIDER_LOW_TRAVEL_FRAC) / (1.0f - SLIDER_LOW_TRAVEL_FRAC) *
           (1.0f - SLIDER_LOW_BRIGHT_FRAC);
  }
  return constrain((int)(frac * PWM_MAX_DUTY + 0.5f), PWM_MIN, PWM_MAX_DUTY);
}

// 逻辑亮度 → s(0..1)。触摸开始时用它把当前亮度换算成起点，保证接着上次的位置继续调。
float brightnessToSlider(int b) {
  float frac = (float)constrain(b, PWM_MIN, PWM_MAX_DUTY) / (float)PWM_MAX_DUTY;
  if (frac < SLIDER_LOW_BRIGHT_FRAC) {
    return (frac / SLIDER_LOW_BRIGHT_FRAC) * SLIDER_LOW_TRAVEL_FRAC;
  }
  return SLIDER_LOW_TRAVEL_FRAC +
         (frac - SLIDER_LOW_BRIGHT_FRAC) / (1.0f - SLIDER_LOW_BRIGHT_FRAC) *
         (1.0f - SLIDER_LOW_TRAVEL_FRAC);
}

// ===== 非阻塞输出渐变 =====
// 只影响实际 PWM 输出，不影响 brightness 这个状态本身。
// 所以 HomeKit 上报和 NVS 保存的一直是最终目标值，灯是慢慢走过去的。
bool breathingActive = false;
uint32_t breathingStartedMs = 0;
float breathingPhase = 0.0f;
bool remoteFadeActive = false;
uint32_t remoteFadeLastMs = 0;
uint32_t remoteFadeStartedMs = 0, remoteFadeDurationMs = 0;
int remoteFadeFrom = 0;

// 启动渐变（手机端调节用）。目标由当前的 ledOn / brightness 决定。
void startRemoteFade(uint32_t durationMs) {
  breathingActive = false;
  remoteFadeActive = true;
  remoteFadeLastMs = millis();
  remoteFadeStartedMs = remoteFadeLastMs;
  remoteFadeDurationMs = durationMs;
  remoteFadeFrom = currentOutputDuty;
}

// 触摸要跟手，任何本地操作都直接取消正在进行的渐变。
void cancelRemoteFade() {
  breathingActive = false;
  remoteFadeActive = false;
}

// 每个 loop 推进一步。按时间步进而不是按帧，所以渐变时长和 loop 速率无关。
void serviceRemoteFade() {
  if (!remoteFadeActive) return;

  int target = ledOn ? constrain(brightness, PWM_MIN, PWM_MAX_DUTY) : 0;
  if (currentOutputDuty == target && remoteFadeDurationMs == 0) {
    remoteFadeActive = false;
    return;
  }

  uint32_t now = millis();
  uint32_t dt = now - remoteFadeLastMs;
  remoteFadeLastMs = now;
  if (dt == 0) return;

  int next;
  if (remoteFadeDurationMs > 0) {
    // Startup uses the entire duration even at a low saved brightness.
    float t = min(1.0f, (float)(now - remoteFadeStartedMs) / remoteFadeDurationMs);
    float eased = t * t * (3.0f - 2.0f * t);
    next = (int)(remoteFadeFrom + (target - remoteFadeFrom) * eased + 0.5f);
    if (t >= 1.0f) remoteFadeActive = false;
  } else {
    // Keep the existing tap / remote dimming speed.
    int step = max(1, (int)((float)PWM_MAX_DUTY * dt / REMOTE_FADE_MS));
    if (currentOutputDuty < target) next = min(currentOutputDuty + step, target);
    else next = max(currentOutputDuty - step, target);
    if (next == target) remoteFadeActive = false;
  }

  setPwmDuty(next);
}

// setup 尚未进入 loop 时也持续推进渐亮，避免初始化期间停在某一档再跳亮。
void delayWithLightOutput(uint32_t durationMs) {
  uint32_t started = millis();
  while (millis() - started < durationMs) {
    serviceRemoteFade();
    uint32_t elapsed = millis() - started;
    if (elapsed < durationMs) delay(min((uint32_t)5, durationMs - elapsed));
  }
  serviceRemoteFade();
}

// 呼吸函数：相位 φ → 0..1 的输出比例（见 breathPeriodMs 处的说明）。
float breathLevel(float phase) {
  float w = powf(0.5f + 0.5f * cosf(phase), breathCurvePct / 100.0f);
  float floorFrac = breathFloorPct / 100.0f;
  return floorFrac + (1.0f - floorFrac) * w;
}

// 从给定的输出比例反推相位，用于启动和改参数时保持亮度连续、不闪。
float breathPhaseFor(float ratio) {
  float floorFrac = breathFloorPct / 100.0f;
  float w = (floorFrac >= 1.0f) ? 1.0f : constrain((ratio - floorFrac) / (1.0f - floorFrac), 0.0f, 1.0f);
  w = powf(w, 100.0f / breathCurvePct);
  return acosf(2.0f * w - 1.0f);
}

// 以当前设定亮度为上限；相位从实际输出开始，避免切换闪亮。
void startBreathing() {
  if(brightness<=0)brightness=PWM_MAX_DUTY*30/100;
  ledOn=true;remoteFadeActive=false;
  float ratio=constrain((float)currentOutputDuty/brightness,0.0f,1.0f);
  breathingPhase=breathPhaseFor(ratio);
  breathingStartedMs=millis();breathingActive=true;
}
void serviceBreathing() {
  if(!breathingActive)return;
  float phase=breathingPhase+6.2831853f*((millis()-breathingStartedMs)%breathPeriodMs)/(float)breathPeriodMs;
  int duty=(int)(brightness*breathLevel(phase)+0.5f);
  setPwmDuty(constrain(duty,PWM_MIN,PWM_MAX_DUTY));
}

// 修改呼吸参数。正在呼吸时从当前实际输出重新起相，周期/形状突变也不会跳闪。
void setBreathParams(int periodMs, int floorPct, int curvePct) {
  breathPeriodMs = constrain(periodMs, BREATH_PERIOD_MIN_MS, BREATH_PERIOD_MAX_MS);
  breathFloorPct = constrain(floorPct, 0, BREATH_FLOOR_MAX_PCT);
  breathCurvePct = constrain(curvePct, BREATH_CURVE_MIN_PCT, BREATH_CURVE_MAX_PCT);
  if (breathingActive) startBreathing();
}

// 按 FADE_STEP_DUTY / FADE_STEP_MS 渐变到目标逻辑亮度。
// 目前只对本地短按开关使用；滑动调光和 HomeKit 写入保持立即响应，便于定位回弹问题。
void fadeToOutput(int targetDuty, const char* reason) {
  int currentDuty = getPwmDuty();
  targetDuty = constrain(targetDuty, PWM_MIN, PWM_MAX_DUTY);

  if (DEBUG_LIGHT_EVENTS) {
    Serial.print("[fade] reason=");
    Serial.print(reason);
    Serial.print(" from=");
    Serial.print(currentDuty);
    Serial.print(" to=");
    Serial.print(targetDuty);
    Serial.print(" brightness=");
    Serial.print(brightness);
    Serial.print(" ledOn=");
    Serial.println(ledOn ? "1" : "0");
  }

  while (currentDuty != targetDuty) {
    if (currentDuty < targetDuty) {
      currentDuty = min(currentDuty + FADE_STEP_DUTY, targetDuty);
    } else {
      currentDuty = max(currentDuty - FADE_STEP_DUTY, targetDuty);
    }
    setPwmDuty(currentDuty);
    delay(FADE_STEP_MS);
  }
}

// 开灯；如果当前保存亮度为 0，则给到最大允许亮度，避免开灯后仍然看起来像没亮。
void turnOn(bool fade = false) {
  breathingActive=false;
  ledOn = true;
  if (brightness <= 0) brightness = PWM_MAX_DUTY;
  // 改用非阻塞渐变：原来的 fadeToOutput() 会 delay 掉整整 1 秒
  // （128 步 x 8ms），这一秒里触摸不采样、HomeSpan 不 poll，
  // 表现就是短按之后有一大段"后摇"，手指再碰也没反应。
  if (fade) startRemoteFade(); else applyLightOutput();
}

// 关灯；只改变 ledOn 和实际输出，不清除 brightness。
void turnOff(bool fade = false) {
  breathingActive=false;
  ledOn = false;
  if (fade) startRemoteFade(); else applyLightOutput();
}

// 网络关闭测试模式下使用的启动闪烁提示。
void bootBlink() {
  setPwmDuty(0);
  delay(150);
  for (int i = 0; i < 2; i++) {
    setPwmDuty(PWM_MAX_DUTY / 4);
    delay(180);
    setPwmDuty(0);
    delay(180);
  }
}

// 把内部逻辑亮度换算成 HomeKit 的 1..100% 显示值。
int brightnessPercent() {
  return constrain((brightness * 100 + (PWM_MAX_DUTY / 2)) / PWM_MAX_DUTY, 1, 100);
}

void syncHomeKitState(bool force = false);

// HTTP 页面按需读取当前状态，不再依赖 HomeKit 通知。
void syncHomeKitState(bool force) { (void)force; }
void clearHomeKitPairingFromTouch() {}  // 旧长按入口已禁用，不清除任何配对或 Wi-Fi 数据。

// 读取 10 个触摸焊盘并计算触摸中心位置 centroid。
// 返回值约为 0..9；没有确认触摸时返回 -1，并缓慢更新 baseline。
// 当前硬件日志显示真实触摸会让 touchRead 明显升高，因此只使用正向 delta。
// 统一的焊盘读取入口。开了硬件滤波就取平滑值，否则退回 Arduino 的原始读数。
bool touchFilterReady = false;
// 最近一帧"峰值±1 窗口"占总能量的比例（TUI 里显示为 win）。
float lastPeakRatio = 0.0f;
// 最近一帧每个焊盘超出门限的量（低于门限记 0），供 TUI 画条形图，避免重复读取。
float lastExcess[NUM_PADS] = {0};
// 最近一帧的总能量，TUI 里显示为 sum，用来对照 TOUCH_TOTAL_MIN。
float lastRawTotal = 0.0f;
// 本次触摸的力度增益，以及用于确定它的前几帧峰值。
float touchPressureGain = 1.0f;
float touchPeakRawTotal = 0.0f;
// 候选触摸从第一帧计时并保留起点，确认期间的快速位移不能丢失。
uint32_t touchCandidateMs = 0;
uint32_t touchLastValidMs = 0;
float touchCandidateCentroid = -1.0f;
float touchCandidateMaxMovement = 0.0f;
float touchRawCentroid = -1.0f;
int touchSlideTarget = 0;
// 上一次执行滑动步进的时刻，用于按时间做速率钳制。触摸开始时清零重新起算。
uint32_t lastSlideStepMs = 0;
// 本次触摸期间偏离起点的最大距离。点按判定必须用它而不是净位移：
// 先右滑再滑回来的净位移接近 0，用净位移会被误判成点按。
float touchMaxMovement = 0.0f;
int touchSlideConfirmFrames = 0;
// 最近一帧的判定结果。REJECTED 表示幅度够了但形状不像手指——排查环境干扰主要看这个。
enum TouchVerdict { TV_IDLE, TV_WEAK, TV_REJECTED, TV_FINGER };
TouchVerdict lastVerdict = TV_IDLE;

uint32_t readTouchPad(int pin) {
  if (TOUCH_HW_FILTER && touchFilterReady) {
    uint32_t smooth = 0;
    if (touch_pad_filter_read_smooth((touch_pad_t)pin, &smooth) == ESP_OK && smooth != 0) {
      return smooth;
    }
  }
  return (uint32_t)touchRead(pin);
}

// 必须在 Arduino 的 touch_pad_init() 之后调用，而那是在第一次 touchRead() 时惰性执行的。
// 所以先把每个通道都读一次，把通道注册好，再启用滤波器。
void initTouchHardwareFilter() {
  if (!TOUCH_HW_FILTER) return;
  for (int i = 0; i < NUM_PADS; i++) {
    touchRead(TOUCH_PINS[i]);
    serviceRemoteFade();
  }

  touch_filter_config_t cfg = {};
  cfg.mode         = TOUCH_PAD_FILTER_IIR_16;  // 只影响内部触摸判决，我们不用
  cfg.debounce_cnt = 1;
  cfg.noise_thr    = 0;
  cfg.jitter_step  = 4;
  cfg.smh_lvl      = TOUCH_SMOOTH_LEVEL;       // 这个才是作用在原始数据上的 IIR

  esp_err_t e1 = touch_pad_filter_set_config(&cfg);
  esp_err_t e2 = touch_pad_filter_enable();
  touchFilterReady = (e1 == ESP_OK && e2 == ESP_OK);

  Serial.print("Touch HW filter: ");
  Serial.println(touchFilterReady ? "enabled (IIR smooth)" : "FAILED, 退回原始读数");
  delayWithLightOutput(20);  // 让滤波器充满几轮，同时继续渐亮。
}

float getCentroid() {
  static float smoothedCentroid = -1.0f;
  uint32_t values[NUM_PADS];
  float positiveWeightedSum = 0;
  float positiveTotalWeight = 0;
  float rawTotal = 0;
  float peakExcess = 0;

  // 第一遍：算出每个焊盘超出门限的量，累计总能量，并记下峰值所在的焊盘。
  int peakIdx = 0;
  for (int i = 0; i < NUM_PADS; i++) {
    values[i] = readTouchPad(TOUCH_PINS[i]);
    float delta = (float)values[i] - (float)baseline[i];
    // 软阈值：跨过门限时贡献从 0 开始连续增长，centroid 不会突跳。
    lastExcess[i] = (delta > NOISE_DELTA) ? (delta - NOISE_DELTA) : 0.0f;
    rawTotal += lastExcess[i];
    if (lastExcess[i] > peakExcess) { peakExcess = lastExcess[i]; peakIdx = i; }
  }

  // 第二遍：只取"峰值焊盘 ± 1"这个窗口。
  // 手指是连续接触的，能量必然集中在相邻的几个焊盘里；而环境干扰是散的
  // （实测 IO1 高、IO2 为 0、IO9/IO10 又有信号 —— 一根手指做不出这种分布）。
  // 所以用"窗口能量占总能量的比例"来判定，比看峰值占比可靠得多：
  //   实测干扰 0.39~0.72，真手指 1.00，中间空档很大。
  // 顺带把窗口外的杂散信号排除在插值之外，centroid 不会再被两端的干扰拉偏。
  int lo = (peakIdx > 0) ? peakIdx - 1 : 0;
  int hi = (peakIdx < NUM_PADS - 1) ? peakIdx + 1 : NUM_PADS - 1;
  float windowSum = 0;
  for (int i = lo; i <= hi; i++) {
    windowSum += lastExcess[i];
    if (lastExcess[i] > 0) {
      // centroid 用压缩后的权重，让邻近焊盘的小信号也能参与插值。
      float w = powf(lastExcess[i], CENTROID_WEIGHT_POW);
      positiveWeightedSum += (float)i * w;
      positiveTotalWeight += w;
    }
  }
  lastPeakRatio = (rawTotal > 0) ? (windowSum / rawTotal) : 0.0f;
  lastRawTotal  = rawTotal;

  // 滞回：已经在触摸中时用更松的门限，避免手指划过焊盘间隙、信号短暂变弱就被判成松手。
  float totalMin  = touching ? (TOUCH_TOTAL_MIN * TOUCH_HOLD_TOTAL_SCALE) : TOUCH_TOTAL_MIN;
  float windowMin = touching ? TOUCH_HOLD_WINDOW_RATIO_MIN : TOUCH_WINDOW_RATIO_MIN;

  // 幅度够 且 能量集中在相邻窗口里，才算真触摸；否则往下走基线更新，把干扰学掉。
  if (rawTotal < totalMin)                              lastVerdict = (rawTotal > 0) ? TV_WEAK : TV_IDLE;
  else if (lastPeakRatio < windowMin)                   lastVerdict = TV_REJECTED;
  else                                                  lastVerdict = TV_FINGER;

  if (lastVerdict == TV_FINGER && positiveTotalWeight > 0) {
    float c = positiveWeightedSum / positiveTotalWeight;
    touchRawCentroid = c;
    // 一阶 IIR 平滑。手指刚落下时没有历史值，直接取当前值，避免从上一次的位置滑过来。
    // 快速跨焊盘时缩短滤波，慢速微调仍保留平滑。
    float tau = (smoothedCentroid >= 0 && fabsf(c - smoothedCentroid) > 0.35f)
                  ? 10.0f : CENTROID_SMOOTH_TAU_MS;
    smoothedCentroid = (smoothedCentroid < 0)
                         ? c
                         : smoothedCentroid + (c - smoothedCentroid) * alphaFor(tau);
    return smoothedCentroid;
  }

  // 松手后清掉历史，下次触摸重新起算。
  touchRawCentroid = -1.0f;
  if (!touching) smoothedCentroid = -1.0f;

  // 已确认手势的短暂弱信号不能被学进基线；局部弱触摸也暂缓正向跟踪。
  if (touching) return -1.0f;
  bool localWeakTouch = rawTotal >= TOUCH_TOTAL_MIN * TOUCH_HOLD_TOTAL_SCALE &&
                        lastPeakRatio >= TOUCH_WINDOW_RATIO_MIN;

  // 没有确认触摸时，缓慢跟踪环境漂移并更新基线。
  // baseline 只在“没有触摸”时更新，避免手指按住时把触摸值学习成新的空闲值。
  for (int i = 0; i < NUM_PADS; i++) {
    // 基线跟踪时间常数固定为 BASELINE_TAU_MS，不随采样率变化。
    if (localWeakTouch && values[i] > baseline[i]) continue;
    uint32_t n = (uint32_t)framesFor(BASELINE_TAU_MS);
    baseline[i] = (baseline[i] * (n - 1) + values[i]) / n;
  }
  return -1.0f;
}

// 短暂掉线仍追到最后一个有效目标，不外推手指位置。
void serviceTouchSlide() {
  if (!touching || !touchAdjustedBrightness) return;
  uint32_t now = millis();
  uint32_t dt = lastSlideStepMs == 0 ? LOOP_TARGET_MS : now - lastSlideStepMs;
  lastSlideStepMs = now;
  if (dt == 0) return;
  dt = min(dt, (uint32_t)40);
  int maxStep = max(1, (int)(PWM_MAX_DUTY * (float)dt / SLIDE_MAX_RATE_MS));
  float alpha = min(1.0f, (float)dt / SLIDE_SMOOTH_TAU_MS);
  float step = (touchSlideTarget - brightness) * alpha;
  int istep = (int)(step > 0 ? step + 0.5f : step - 0.5f);
  brightness = constrain(brightness + constrain(istep, -maxStep, maxStep), PWM_MIN, PWM_MAX_DUTY);
  if (touchSlideTarget == 0 && brightness < BRIGHTNESS_OFF_FLOOR) brightness = 0;
  ledOn = brightness > PWM_MIN;
  cancelRemoteFade();
  applyLightOutput();
  syncHomeKitState();
}

// PWM 稳定后建立 baseline。每轮读全部焊盘，滤波读数采 40 轮 × 5ms = 200ms。
// 原来每个焊盘单独采 20 次 × 10ms，总共 2 秒，是启动延迟的大头之一。
// 这段时间不要触摸焊盘，否则 baseline 会被校准到错误位置。
void calibrate() {
  // 延长观测窗覆盖更多噪声变化；退回原始读数时采 400ms。
  const int rounds = touchFilterReady ? 40 : 80;
  uint32_t sums[NUM_PADS] = {0};
  for (int j = 0; j < rounds; j++) {
    for (int i = 0; i < NUM_PADS; i++) {
      sums[i] += readTouchPad(TOUCH_PINS[i]);
    }
    delay(5);
  }
  for (int i = 0; i < NUM_PADS; i++) {
    baseline[i] = sums[i] / rounds;
  }
}

// 判断当前原始触摸值是否像空闲状态。
// 这里不依赖 baseline，避免“baseline 已经校准错了”时继续误判。
bool rawTouchLooksIdle() {
  for (int i = 0; i < NUM_PADS; i++) {
    if (readTouchPad(TOUCH_PINS[i]) > TOUCH_BOOT_RECAL_MAX_RAW) {
      return false;
    }
  }
  return true;
}

// 上电后延迟二次校准：到期后等待原始触摸值稳定空闲，再重新建立 baseline。
// 如果上电时手一直按着，rawTouchLooksIdle() 会阻止重校准，直到手松开。
void serviceBootRecalibration() {
  if (bootRecalibrated || bootRecalDueMs == 0 || millis() < bootRecalDueMs) return;

  if (touching || touchStableFrames > 0) {
    bootRecalIdleSinceMs = 0;
    return;
  }

  if (!rawTouchLooksIdle()) {
    bootRecalIdleSinceMs = 0;
    return;
  }

  if (bootRecalIdleSinceMs == 0) {
    bootRecalIdleSinceMs = millis();
    return;
  }

  if (millis() - bootRecalIdleSinceMs < TOUCH_BOOT_RECAL_STABLE_MS) return;

  // 空闲期间 getCentroid() 已持续跟踪基线，不再阻塞采样约两秒。
  Serial.println("Touch boot baseline settling done");
  touching = false;
  touchAdjustedBrightness = false;
  touchStableFrames = 0;
  touchReleaseFrames = TOUCH_RELEASE_FRAMES;
  touchStartCentroid = -1.0f;
  lastCentroid = -1.0f;
  bootRecalibrated = true;
  Serial.println("Touch boot recalibration done");
}

// 打印触摸调试信息：IOx=baseline/current/delta，可用于判断阈值、噪声和焊盘响应。
// ===== 串口 TUI 仪表盘 =====
// ANSI 转义原地刷新：每次先把光标上移 TUI_LINES 行，再逐行重画并用 \033[K 清掉行尾残留。
#define TUI_LINES         15
#define TUI_BAR_W         30
#define TUI_REFRESH_MS    200
// 焊盘条形图用对数刻度：空闲 0、轻触几百、重按十几万，线性刻度什么都看不出来。
#define TUI_PAD_FULLSCALE 200000.0f

void tuiBar(float ratio, int width) {
  int n = constrain((int)(ratio * width + 0.5f), 0, width);
  for (int i = 0; i < width; i++) Serial.print(i < n ? "█" : "░");
}

void printDashboard() {
  static uint32_t last = 0;
  static bool first = true;
  uint32_t now = millis();
  if (now - last < TUI_REFRESH_MS) return;
  last = now;

  if (!first) Serial.printf("\033[%dA", TUI_LINES);
  first = false;

  Serial.printf("\033[K\033[1m── Rhodes Light ──\033[0m  %4.1fHz   WiFi:%-3s HTTP:%-3s Filter:%-3s\r\n",
                1000.0f / loopIntervalMs,
                WiFi.status() == WL_CONNECTED ? "on" : "--",
                httpReady ? "on" : "--",
                touchFilterReady ? "on" : "--");

  for (int i = 0; i < NUM_PADS; i++) {
    float e = lastExcess[i];
    float ratio = (e > 0) ? log10f(1.0f + e) / log10f(1.0f + TUI_PAD_FULLSCALE) : 0.0f;
    Serial.printf("\033[K IO%-2d ", TOUCH_PINS[i]);
    tuiBar(ratio, TUI_BAR_W);
    Serial.printf(" %7ld\r\n", (long)e);
  }

  Serial.print("\033[K ────────────────────────────────────────────────────\r\n");

  const char* verdict = "─ idle";
  switch (lastVerdict) {
    case TV_FINGER:   verdict = "\033[32m● FINGER\033[0m"; break;
    case TV_REJECTED: verdict = "\033[31m✗ NOISE \033[0m"; break;
    case TV_WEAK:     verdict = "\033[33m· weak  \033[0m"; break;
    default:          verdict = "  idle  "; break;
  }
  Serial.printf("\033[K TOUCH  %s  win %.2f  sum %-7ld gain %.2f  centroid %s\r\n",
                verdict, lastPeakRatio, (long)lastRawTotal, touchPressureGain,
                lastVerdict == TV_FINGER ? String(lastCentroid, 2).c_str() : "  --");

  int pct = ledOn ? constrain((brightness * 100 + PWM_MAX_DUTY / 2) / PWM_MAX_DUTY, 0, 100) : 0;
  Serial.printf("\033[K LIGHT  %-3s ", ledOn ? "ON" : "off");
  tuiBar(pct / 100.0f, TUI_BAR_W);
  Serial.printf(" %3d%%  duty %d\r\n", pct, currentOutputDuty);

  Serial.printf("\033[K RAIL   IO17 %s/%d   fade %s\r\n",
                railEnabled ? "HIGH" : "LOW ", railPadLevel(),
                remoteFadeActive ? "running" : "idle   ");
}

void printTouchDebug(float centroid) {
  if (SERIAL_TUI) return;
  uint32_t now = millis();
  if (now - lastTouchDebugMs < 500) return;
  lastTouchDebugMs = now;

  Serial.print("touch base/current/delta: ");
  for (int i = 0; i < NUM_PADS; i++) {
    uint32_t value = readTouchPad(TOUCH_PINS[i]);
    int32_t delta = (int32_t)value - (int32_t)baseline[i];
    Serial.print("IO");
    Serial.print(TOUCH_PINS[i]);
    Serial.print("=");
    Serial.print(baseline[i]);
    Serial.print("/");
    Serial.print(value);
    Serial.print("/");
    Serial.print(delta);
    if (i < NUM_PADS - 1) Serial.print(" ");
  }
  Serial.print(" peakRatio=");
  Serial.print(lastPeakRatio, 2);
  Serial.print(" centroid=");
  Serial.print(centroid, 2);
  Serial.print(" ledOn=");
  Serial.print(ledOn ? "1" : "0");
  Serial.print(" logicalPwm=");
  Serial.print(getPwmDuty());
  Serial.print(" physicalPwm=");
  Serial.print(getPhysicalPwmDuty());
  Serial.print(" rawPwm=");
  Serial.println(getRawPwmDuty());
}

// 低频打印网络状态。HomeKit 找不到设备时，先看这里的 WiFi/IP/HomeKit 状态。
void printConnectionStatus(bool force = false) {
  uint32_t now = millis();
  if (!force && now - lastStatusPrintMs < 7000) return;
  lastStatusPrintMs = now;

  if (SERIAL_TUI) return;
  Serial.println("--- Rhodes Light status ---");
  Serial.print("NetworkState: ");
  Serial.println(networkStateName());
  Serial.print("WiFi: ");
  Serial.println(WiFi.status() == WL_CONNECTED ? "connected" : "disconnected");
  Serial.print("SSID: ");
  Serial.println(WiFi.SSID());
  Serial.print("IP: ");
  Serial.println(WiFi.localIP());
  Serial.print("RSSI: ");
  Serial.println(WiFi.RSSI());
  Serial.print("HTTP: ");
  Serial.println(httpReady ? "started" : "waiting");
  Serial.print("WiFiManager portal: ");
  Serial.println(wifiManagerActive ? "active" : "inactive");
  Serial.print("TouchPhase: ");
  Serial.println(touchPhaseName());
  Serial.print("TouchFrames stable/release: ");
  Serial.print(touchStableFrames);
  Serial.print("/");
  Serial.println(touchReleaseFrames);
  Serial.print("HomeKitGuard: ");
  Serial.print(isTouchHomeKitGuardActive() ? "active" : "inactive");
  Serial.print(", inhibitMsLeft=");
  Serial.println((int32_t)(touchInhibitUntilMs - now) > 0 ? (uint32_t)(touchInhibitUntilMs - now) : 0);
  Serial.print("HomeKit delayed resync: ");
  Serial.println(homeKitNeedsResync ? "pending" : "none");
  Serial.print("Boot recalibration: ");
  if (bootRecalibrated) {
    Serial.println("done");
  } else if (bootRecalDueMs == 0) {
    Serial.println("disabled");
  } else if (now < bootRecalDueMs) {
    Serial.print("waiting, msLeft=");
    Serial.println(bootRecalDueMs - now);
  } else if (bootRecalIdleSinceMs == 0) {
    Serial.println("armed, waiting idle");
  } else {
    Serial.print("idle confirm, ms=");
    Serial.println(now - bootRecalIdleSinceMs);
  }
  Serial.print("Light ledOn/brightness/percent: ");
  Serial.print(ledOn ? "1" : "0");
  Serial.print("/");
  Serial.print(brightness);
  Serial.print("/");
  Serial.println(brightnessPercent());
  Serial.print("Touch sample rate: ");
  Serial.print(1000.0f / loopIntervalMs, 1);
  Serial.print(" Hz (loop ");
  Serial.print(loopIntervalMs, 1);
  Serial.print("ms), HW filter=");
  Serial.println(touchFilterReady ? "on" : "off");
  Serial.print("LED rail IO17 driving/readback: ");
  Serial.print(railEnabled ? "HIGH" : "LOW");
  Serial.print("/");
  Serial.println(railPadLevel());
  Serial.print("PWM logical/physical/raw: ");
  Serial.print(getPwmDuty());
  Serial.print("/");
  Serial.print(getPhysicalPwmDuty());
  Serial.print("/");
  Serial.println(getRawPwmDuty());
  Serial.println("---------------------------");
}

// 固件启动入口：尽早关断 PMOS，初始化 PWM、触摸、WiFiManager 和 HomeKit 网络状态机。
void setup() {
  // 顺序有意为之：先关断 PMOS 栅极，再把 LED 轨拉到确定的关断状态。
  forcePwmOffEarly();
  forceRailOffEarly();
  initSerialDebug();

  // LEDC 接管引脚前，先把通道 duty 预置为“关灯 raw duty”。
  // 否则 active-low PMOS 在 attach 的瞬间可能看到 raw duty=0，表现为第二下闪亮。
  // Initialize Arduino's channel bookkeeping at a frequency supported by XTAL,
  // then configure the same timer with APB before attaching the output pin.
  uint32_t actualPwmHz = ledcSetup(PWM_CH, 2000, PWM_RES);
  if (actualPwmHz != 0) {
    ledc_timer_config_t timer = {};
    timer.speed_mode = (ledc_mode_t)(PWM_CH / 8);
    timer.timer_num = (ledc_timer_t)((PWM_CH / 2) % 4);
    timer.duty_resolution = (ledc_timer_bit_t)PWM_RES;
    timer.freq_hz = PWM_FREQ;
    timer.clk_cfg = LEDC_USE_APB_CLK;
    esp_err_t result = ledc_timer_config(&timer);
    actualPwmHz = result == ESP_OK ? ledc_get_freq(timer.speed_mode, timer.timer_num) : 0;
  }
  if (actualPwmHz == 0) {
    Serial.println("PWM ERROR: timer setup failed; LED rail remains off");
    while (true) delay(1000);
  }
  ledcWrite(PWM_CH, PWM_ACTIVE_LOW ? PWM_FULL_DUTY : 0);
  ledcAttachPin(PWM_PIN, PWM_CH);
  setPwmDuty(0);

  // PW2053 的 EN 硬件上没有下拉，必须由固件接管。
  // 常开模式在这里一次性使能；power-gating 模式由 setPwmDuty() 按需开关。
  if (!RAIL_POWER_GATING) setRailEnabled(true);

  if (LED_DC_TEST) {
    // 把 IO18 从 LEDC 手里收回来，改成普通 GPIO，输入缓冲保持打开以便回读真实电平。
    ledcDetachPin(PWM_PIN);
    gpio_set_direction((gpio_num_t)PWM_PIN, GPIO_MODE_INPUT_OUTPUT);
    gpio_set_level((gpio_num_t)PWM_PIN, 1);   // 先关断，安全起步
    WiFi.mode(WIFI_OFF);
    btStop();
    Serial.println("=== LED DC TEST MODE ===");
    Serial.println("IO17=HIGH(轨常开)，IO18 每 5 秒在 LOW(灯亮)/HIGH(灯灭) 之间切换");
    return;
  }

  if (FIXED_PWM_TEST) {
    setPwmDuty(FIXED_PWM_TEST_DUTY);
    Serial.print("Fixed PWM test mode, duty=");
    Serial.println(FIXED_PWM_TEST_DUTY);
    return;
  }

  loadPrefs();

  // 读完 NVS 就开始渐亮，不等网络；触摸硬件预热与渐亮重叠。
  bootLightTracking = ledOn && brightness > 0;
  if (bootLightTracking) startRemoteFade(STARTUP_FADE_MS);
  else applyLightOutput();
  Serial.printf("PWM configured: %lu Hz, %d bits, APB clock\n", (unsigned long)actualPwmHz, PWM_RES);
  Serial.printf("PWM output ceiling: %d%% (%d/%d)\n", PWM_OUTPUT_LIMIT_PERCENT, PWM_MAX_DUTY, PWM_FULL_DUTY);
  Serial.println("PWM initialized");
  Serial.print("LED rail (IO17): ");
  Serial.print(RAIL_POWER_GATING ? "power-gated" : "always on");
  Serial.print(", driving=");
  Serial.print(railEnabled ? "HIGH" : "LOW");
  Serial.print(", pad readback=");
  Serial.println(railPadLevel());
  Serial.print("Prefs loaded, ledOn=");
  Serial.print(ledOn ? "1" : "0");
  Serial.print(", brightness=");
  Serial.println(brightness);

  initTouchHardwareFilter();

  // 先完成渐亮再采基线，避免变化中的 PWM 负载被学进 baseline。
  while (remoteFadeActive) delayWithLightOutput(5);
  bootLightTracking = false;
  delayWithLightOutput(50);
  Serial.printf("Boot light on at %lu ms\n", (unsigned long)bootLightOnMs);

  Serial.println("Calibrating touch");
  // 触摸校准必须在正常空闲状态下完成。
  // 如果上电时手指正压在触摸条上，baseline 会偏掉，后续就会出现误触或无法滑动。
  calibrate();
  bootRecalibrated = false;
  bootRecalDueMs = millis() + TOUCH_BOOT_RECAL_DELAY_MS;
  bootRecalIdleSinceMs = 0;
  Serial.println("Touch calibration done");

  if (DISABLE_NETWORK_TEST) {
    WiFi.mode(WIFI_OFF);
    btStop();
    bootBlink();
    Serial.println("Network disabled test mode");
    return;
  }

  startHttpNetwork();
  if(!enableBleProvision())Serial.println("BLE provisioning unavailable; AP setup remains available");
  printConnectionStatus(true);
  Serial.printf("Boot ready at %lu ms (light at %lu ms)\n",
                (unsigned long)millis(), (unsigned long)bootLightOnMs);
}

// 主循环：维护网络/HomeKit，并执行触摸点击、滑动调光和长按清除配对的状态机。
void loop() {
  updateLoopRate();

  if (LED_DC_TEST) {
    static bool lightOn = false;
    static uint32_t lastToggle = 0;
    if (lastToggle == 0 || millis() - lastToggle >= 5000) {
      lastToggle = millis();
      lightOn = !lightOn;
      // PMOS 低电平导通：IO18=LOW 灯亮，IO18=HIGH 灯灭。
      gpio_set_level((gpio_num_t)PWM_PIN, lightOn ? 0 : 1);
      Serial.print("[DC TEST] 灯应该 ");
      Serial.print(lightOn ? "亮 " : "灭 ");
      Serial.print("| IO18 drive=");
      Serial.print(lightOn ? "LOW " : "HIGH");
      Serial.print(" readback=");
      Serial.print(gpio_get_level((gpio_num_t)PWM_PIN));
      Serial.print(" | IO17 drive=HIGH readback=");
      Serial.println(railPadLevel());
    }
    delay(50);
    return;
  }

  if (FIXED_PWM_TEST) {
    delay(1000);
    return;
  }

  if (DISABLE_NETWORK_TEST) {
    // 纯触摸/PWM 调试模式：完全关闭网络和 HomeKit。
    // 用来确认 RF/WiFi 是否影响 LED 或触摸。
    float centroid = getCentroid();
    printTouchDebug(centroid);
    if (centroid >= 0) {
      int target = map((int)(centroid * 100), 0, (NUM_PADS - 1) * 100, PWM_MAX_DUTY / 12, PWM_MAX_DUTY);
      brightness = constrain(target, PWM_MIN, PWM_MAX_DUTY);
      ledOn = true;
      setPwmDuty(brightness);
    }
    delay(LOOP_TARGET_MS);
    return;
  }

  serviceNetwork();
  printConnectionStatus();
  serviceBootRecalibration();
  serviceRemoteFade();
  serviceBreathing();
  servicePrefsSave();
  // 没有监视器连接时 HWCDC 的发送缓冲会填满并阻塞 write，直接拖慢整个 loop。
  // Serial 表示端口已打开；触摸期间暂停整屏打印，避免大量 USB 写入阻塞采样。
  if (SERIAL_TUI && Serial && !touching && touchStableFrames == 0) printDashboard();

  if (homeKitNeedsResync && !isTouchHomeKitGuardActive()) {
    homeKitNeedsResync = false;
    logLightEvent("homekit delayed resync");
    syncHomeKitState(true);
  }

  if (HOMEKIT_COLLISION_GUARD && (int32_t)(touchInhibitUntilMs - millis()) > 0 && !touching) {
    // HomeKit 刚写入亮度后，短时间不处理触摸。
    // 这可以防止触摸基线漂移或残留手势把 HomeKit 写入的目标亮度拉回旧值。
    touching = false;
    touchAdjustedBrightness = false;
    touchStableFrames = 0;
    touchReleaseFrames = TOUCH_RELEASE_FRAMES;
    delay(LOOP_TARGET_MS);
    return;
  }

  float centroid = getCentroid();
  printTouchDebug(centroid);
  bool isTouching = centroid >= 0;

  uint32_t touchNow = millis();
  if (isTouching) {
    if (!touching && touchStableFrames == 0) {
      touchCandidateMs = touchNow;
      touchCandidateCentroid = touchRawCentroid;
      touchCandidateMaxMovement = 0.0f;
      touchPeakRawTotal = lastRawTotal;
    }
    if (!touching) {
      touchCandidateMaxMovement = max(touchCandidateMaxMovement,
                                     fabsf(touchRawCentroid - touchCandidateCentroid));
      touchPeakRawTotal = max(touchPeakRawTotal, lastRawTotal);
    }
    touchLastValidMs = touchNow;
    touchStableFrames = min(touchStableFrames + 1, 200);
    touchReleaseFrames = 0;
  } else {
    touchReleaseFrames = min(touchReleaseFrames + 1, 200);
    touchStableFrames = 0;
    touchSlideConfirmFrames = 0;
  }

  bool touchConfirmed = isTouching && touchStableFrames >= 2 &&
                        touchNow - touchCandidateMs >= TOUCH_STABLE_MS;
  uint32_t releaseNeeded = touchAdjustedBrightness ? 80 : 30;
  bool releaseConfirmed = !isTouching && touching &&
                          touchNow - touchLastValidMs >= releaseNeeded;

  if (touchConfirmed && !touching) {
    // 触摸刚确认：记录起点，但不立刻改变亮度。
    // 后面根据移动距离判断这是点击、滑动，还是关灯状态下的 HomeKit 长按重置。
    touching = true;
    touchStartedLedOn = ledOn;
    homeKitResetHoldTriggered = false;
    homeKitResetHoldEligible = !ledOn;
    touchAdjustedBrightness = false;
    touchStartBrightness = currentOutputDuty;
    touchStartMs = touchCandidateMs;
    touchStartCentroid = touchCandidateCentroid;
    // 落指还不是手势：点按确认后反向渐变，滑动确认后才接管输出。
    touchSlideConfirmFrames = 0;
    touchPressureGain = PRESSURE_GAIN_ENABLE
      ? constrain(sqrtf(touchPeakRawTotal / PRESSURE_REF), PRESSURE_GAIN_MIN, PRESSURE_GAIN_MAX)
      : 1.0f;
    lastSlideStepMs = 0;
    touchMaxMovement = touchCandidateMaxMovement;
    lastCentroid = centroid;
    logLightEvent("touch start");
  }
  // 已确认触摸只要求当前样本有效，间隙恢复后不再重新等待确认。
  if (isTouching && touching) {
    // 只在尚未开始滑动时估计力度；一旦调光就冻结，避免增益变化重算整个手势。
    if (PRESSURE_GAIN_ENABLE && !touchAdjustedBrightness &&
        touchNow - touchStartMs < PRESSURE_SETTLE_MS) {
      touchPeakRawTotal = max(touchPeakRawTotal, lastRawTotal);
      touchPressureGain = constrain(sqrtf(touchPeakRawTotal / PRESSURE_REF),
                                    PRESSURE_GAIN_MIN, PRESSURE_GAIN_MAX);
    }

    // 卡死看门狗：触摸期间基线是冻结的，一旦因为基线漂移进入假触摸就会永久卡住。
    if (millis() - touchStartMs > TOUCH_STUCK_TIMEOUT_MS) {
      Serial.println("[touch] stuck >30s, forcing release + recalibrate");
      touching = false; touchAdjustedBrightness = false;
      touchStableFrames = 0; touchReleaseFrames = TOUCH_RELEASE_FRAMES;
      calibrate();
      return;
    }
    // 触摸持续中：只要移动超过死区，就进入滑动调光。
    // 滑动调光以“触摸开始时的亮度”为基准，避免每帧在上一次结果上累加导致漂移。
    if (fabsf(centroid - touchStartCentroid) > HOMEKIT_RESET_MAX_MOVEMENT) {
      homeKitResetHoldEligible = false;
    }

    // 只有关灯开始的长按才允许清除 HomeKit。
    // 如果手指移动超过 HOMEKIT_RESET_MAX_MOVEMENT，会取消这个资格，防止滑动时误清。
    if (ENABLE_TOUCH_HOMEKIT_RESET &&
        !homeKitResetHoldTriggered &&
        homeKitResetHoldEligible &&
        millis() - touchStartMs >= HOMEKIT_RESET_HOLD_MS) {
      homeKitResetHoldTriggered = true;
      clearHomeKitPairingFromTouch();
      return;
    }

    float totalMoved = centroid - touchStartCentroid;
    touchMaxMovement = max(touchMaxMovement, fabsf(touchRawCentroid - touchStartCentroid));
    // 需要连续两个有效位置越过死区，避免点按的单帧漂移抢走渐变。
    if (fabsf(totalMoved) > TOUCH_MOVE_DEADBAND) touchSlideConfirmFrames++;
    else touchSlideConfirmFrames = 0;
    if (touchSlideConfirmFrames >= 2 || touchAdjustedBrightness) {
      if (!touchAdjustedBrightness) {
        // brightness 保存的是目标值；渐变中应从当前实际输出接管，不能跳回目标。
        touchStartBrightness = currentOutputDuty;
        brightness = currentOutputDuty;
        cancelRemoteFade();
      }
      // totalMoved 为正表示向右滑，亮度增加；为负表示向左滑，亮度降低。
      // targetBrightness 每次都从 touchStartBrightness 重新计算，不依赖上一帧 brightness。
      // 先把"触摸开始时的亮度"换算成滑条位置，在滑条坐标上线性加位移，再换算回亮度。
      // 非线性全在映射函数里，这里始终是线性的，所以不会出现"越滑越快"之类的累积误差。
      float sTarget = brightnessToSlider(touchStartBrightness) +
                      totalMoved * SLIDER_PER_PAD * touchPressureGain;
      int targetBrightness = sliderToBrightness(sTarget);
      if (targetBrightness < BRIGHTNESS_OFF_FLOOR) targetBrightness = 0;
      touchSlideTarget = targetBrightness;
      touchAdjustedBrightness = true;
      // serviceTouchSlide() 在实际输出更新后同步 HomeKit。
      uint32_t now = millis();
      if (now - lastSlideDebugMs >= 200) {
        lastSlideDebugMs = now;
        logLightEvent("touch slide apply");
      }
    }
    lastCentroid = centroid;
  }
  serviceTouchSlide();
  if (releaseConfirmed && touching) {
    // 手指确认松开：在这里保存状态（NVS 只在松手时写一次，避免滑动中反复擦写）。
    // HomeKit 已经在滑动过程中实时同步过了，这里再 force 一次确保最终值一致。
    touching = false;
    lastTouchReleaseMs = millis();  // 记录松手时间，用于屏蔽 iOS 反馈回路
    if (homeKitResetHoldTriggered) {
      delay(LOOP_TARGET_MS);
      return;
    }

    uint32_t duration = touchLastValidMs - touchStartMs;
    float totalMoved = lastCentroid - touchStartCentroid;

    if (DEBUG_LIGHT_EVENTS) {
      Serial.print("[touch release] duration=");
      Serial.print(duration);
      Serial.print(" totalMoved=");
      Serial.print(totalMoved, 2);
      Serial.print(" adjusted=");
      Serial.print(touchAdjustedBrightness ? "1" : "0");
      Serial.print(" startedLedOn=");
      Serial.print(touchStartedLedOn ? "1" : "0");
      Serial.print(" brightness=");
      Serial.print(brightness);
      Serial.print(" pwm=");
      Serial.println(getPwmDuty());
    }

    if (touchAdjustedBrightness) {
      // 本次是滑动调光：保存最终亮度。
      brightness = constrain(brightness, PWM_MIN, PWM_MAX_DUTY);
      ledOn = brightness > PWM_MIN;
      applyLightOutput();
      logLightEvent("touch release slide save");
      markPrefsDirty();
      syncHomeKitState(true);
    } else if (duration < CLICK_MAX_MS && touchMaxMovement < CLICK_MAX_MOVEMENT) {
      // 本次是短按：开关灯。
      // 关灯时不清 brightness，所以再次开灯可恢复上一次目标亮度。
      logLightEvent("touch release click fade");
      if (touchStartedLedOn) turnOff(true); else turnOn(true);
      markPrefsDirty();
      syncHomeKitState(true);
    } else if (totalMoved > SWIPE_MIN_MOVEMENT) {
      // 从关灯状态向右滑但没有进入调光时，至少执行开灯。
      logLightEvent("touch release swipe on");
      if (!ledOn) turnOn();
      markPrefsDirty();
      syncHomeKitState(true);
    } else {
      // 其他没有明确动作的触摸，仍保存/同步一次当前状态，保证 HomeKit 显示一致。
      logLightEvent("touch release noop save");
      markPrefsDirty();
      syncHomeKitState(true);
    }
  }

  delay(LOOP_TARGET_MS);
}
