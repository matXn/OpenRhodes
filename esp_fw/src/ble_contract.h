#pragma once
// ===== BLE 配网 module 的 interface =====
#include <Arduino.h>
bool enableBleProvision();
String bleProvisionInfo();
void serviceBleProvision();
