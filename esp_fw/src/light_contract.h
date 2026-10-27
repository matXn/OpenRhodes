#pragma once
// ===== 灯光输出 module 的 interface =====
// 实现暂留在 main.cpp；http_control / ble_provision 只需要这些符号。
#include <Arduino.h>
#include "board_config.h"

extern bool ledOn;
extern int brightness;
extern bool touching;
extern int touchStableFrames;
extern int currentOutputDuty;
extern bool breathingActive;
extern float breathingPhase;
extern uint32_t breathingStartedMs;
extern int breathPeriodMs;
extern int breathFloorPct;
extern int breathCurvePct;
extern uint32_t bootLightOnMs;

void markPrefsDirty();
void applyLightOutput();
void startRemoteFade(uint32_t durationMs = 0);
void cancelRemoteFade();
void startBreathing();
void setBreathParams(int periodMs, int floorPct, int curvePct);
int getRawPwmDuty();
