#pragma once
// ===== HTTP / 配网 module 的 interface =====
// 这个头只声明类型、跨 module 状态和服务入口，不含任何函数体、
// 不包含生成的页面资源（control_page.h / splash_asset.h / ...）。
// 因此任何 module 都可以按任意顺序包含它。
#include <Arduino.h>
#include <esp_wifi.h>
#include "board_config.h"
#include "wifi_profiles.h"
#include "wifi_setup_priority.h"
#include "wifi_autoconnect.h"
#include "ble_provision_protocol.h"

// ===== 网络状态机 =====
// NET_CONNECTING：尝试连接已保存 WiFi。
// NET_PORTAL：连接失败后打开 RhodesLight-Setup 配网热点和 192.168.4.1 配网页面。
// NET_CONNECTED：WiFi 已连上。
// 8 处赋值全部发生在 http_control 实现里，所以枚举与变量都归网络 module。
enum NetworkState { NET_OFF, NET_CONNECTING, NET_PORTAL, NET_CONNECTED };
extern NetworkState networkState;

// ===== 跨 module 可见的状态 =====
// main.cpp 读取（TUI / 状态打印）：httpReady、wifiManagerActive
extern bool httpReady;
extern bool wifiManagerActive;

// ble_provision 读取或写入：以下全部
extern String targetSsid;
extern String wifiError;
extern uint32_t wifiAttemptId;
extern bool wifiChangePending, wifiDisconnectWaiting;
extern bool scanActive;
extern bool scanForAutoConnect, autoSelectPending;
extern String scanError;
extern WifiProfiles wifiProfiles;
extern WifiSetupPriority setupPriority;
extern uint16_t wifiTriedMask;
extern uint32_t autoScanDurationMs, wifiConnectedAtMs;

// BLE 配网扫描结果（由本 module 的扫描逻辑填充）
struct BleWifiNetwork { String ssid; int rssi; bool secure; };
extern BleWifiNetwork bleNetworks[24];
extern int bleNetworkCount;
extern uint32_t bleScanGeneration;

// ===== 对外操作 =====
String jsonQuote(const String& value);
void scheduleWifi(const String& ssid, const String& password, bool explicitRequest);
void holdWifiSetup(bool interruptExplicit = false);
void cancelWifiScan();
bool beginWifiScan();

// ===== 服务入口 =====
void startHttpNetwork();
void serviceNetwork();
