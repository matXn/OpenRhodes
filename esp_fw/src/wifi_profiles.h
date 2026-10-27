#pragma once
#include <stdint.h>
#include <string.h>

// One versioned NVS blob: order and credentials commit together.
struct WifiProfile { char ssid[33]; char password[65]; };
struct WifiProfiles {
  static constexpr int CAPACITY = 8;
  uint8_t version = 1;
  uint8_t count = 0;
  WifiProfile entries[CAPACITY] = {};

  bool valid() const {
    if (version != 1 || count > CAPACITY) return false;
    for (int i = 0; i < count; ++i) {
      if (!entries[i].ssid[0] || !memchr(entries[i].ssid, 0, 33) ||
          !memchr(entries[i].password, 0, 65)) return false;
      for (int j = 0; j < i; ++j)
        if (!strcmp(entries[i].ssid, entries[j].ssid)) return false;
    }
    return true;
  }
  int find(const char* ssid) const {
    for (int i = 0; i < count; ++i)
      if (!strcmp(entries[i].ssid, ssid)) return i;
    return -1;
  }
  bool remember(const char* ssid, const char* password, bool first = false) {
    int i = find(ssid);
    if (i < 0) { if (count == CAPACITY) return false; i = count++; }
    strncpy(entries[i].ssid, ssid, 32); entries[i].ssid[32] = 0;
    strncpy(entries[i].password, password, 64); entries[i].password[64] = 0;
    if(first)move(i,0);
    return true;
  }
  bool move(int from, int to) {
    if (from < 0 || from >= count || to < 0 || to >= count) return false;
    WifiProfile item = entries[from];
    if (from < to) for (int i = from; i < to; ++i) entries[i] = entries[i+1];
    else for (int i = from; i > to; --i) entries[i] = entries[i-1];
    entries[to] = item; return true;
  }
  bool erase(int i) {
    if (i < 0 || i >= count) return false;
    for (int j = i; j + 1 < count; ++j) entries[j] = entries[j+1];
    entries[--count] = {}; return true;
  }
};
