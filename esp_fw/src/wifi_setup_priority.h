#pragma once
#include <stdint.h>

// A visible setup page renews this lease. Closing it cannot strand auto-connect.
struct WifiSetupPriority {
  static constexpr uint32_t LEASE_MS = 45000;
  uint32_t touchedAt = 0;
  bool held = false;
  void hold(uint32_t now) { touchedAt = now; held = true; }
  void release() { held = false; }
  bool active(uint32_t now) const { return held && uint32_t(now-touchedAt) < LEASE_MS; }
  bool allowAutomatic(uint32_t now, bool scanBusy) const { return !scanBusy && !active(now); }
};
