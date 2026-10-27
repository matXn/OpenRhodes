#include "../src/wifi_setup_priority.h"
#include "../src/wifi_profiles.h"
#include "../src/wifi_autoconnect.h"
#include <cassert>
#include <cstdio>

int main() {
  WifiSetupPriority priority;
  assert(priority.allowAutomatic(0,false));
  priority.hold(0);
  assert(priority.active(0)); // Zero millis is a valid boot-time request.
  assert(!priority.allowAutomatic(10000,false));
  priority.hold(40000);
  assert(!priority.allowAutomatic(80000,false)); // Visible-page heartbeat renews it.
  assert(priority.allowAutomatic(85000,false)); // Lost page does not strand reconnect.
  assert(!priority.allowAutomatic(85000,true)); // Scan also owns the radio after lease expiry.
  priority.hold(0xfffffff0u);
  assert(priority.active(15)); // millis rollover.
  assert(!priority.active(60000));
  priority.hold(100);priority.release();
  assert(priority.allowAutomatic(101,false));

  WifiProfiles profiles;
  profiles.remember("Old-home","oldpass88");
  profiles.remember("Studio","studiopass");
  profiles.remember("New-hotspot","newpass88",true);
  assert(!strcmp(profiles.entries[0].ssid,"New-hotspot"));
  assert(!strcmp(profiles.entries[1].ssid,"Old-home"));
  assert(!strcmp(profiles.entries[2].ssid,"Studio"));
  profiles.remember("Studio","newstudio",true);
  assert(profiles.count==3 && !strcmp(profiles.entries[0].ssid,"Studio"));
  assert(!strcmp(profiles.entries[0].password,"newstudio"));
  // Automatic password refresh must not reorder the user-selected priorities.
  profiles.remember("Old-home","updated88");
  assert(!strcmp(profiles.entries[0].ssid,"Studio"));
  for(int i=0;i<5;i++){char ssid[16];std::snprintf(ssid,sizeof ssid,"Other-%d",i);assert(profiles.remember(ssid,"password"));}
  assert(profiles.count==8);
  assert(!profiles.remember("Ninth","password",true));
  assert(profiles.remember("Old-home","finalpass",true));
  assert(profiles.count==8 && profiles.valid());
  assert(!strcmp(profiles.entries[0].ssid,"Old-home"));
  WifiAutoConnectPlan plan;
  // Four absent higher-priority profiles must not delay the visible fifth.
  plan.seen(4,6,-60);
  assert(plan.next(5,0)==4);
  assert(plan.channels[4]==6);
  // Multiple APs with the same SSID: remember the strongest observed channel.
  plan.seen(4,11,-80);assert(plan.channels[4]==6);
  plan.seen(4,1,-40);assert(plan.channels[4]==1);
  // Saved priority wins over RSSI between different visible SSIDs.
  plan.seen(1,3,-85);assert(plan.next(5,0)==1);
  assert(plan.next(5,1u<<1)==4);
  // Hidden/missed SSIDs remain eligible after visible networks are exhausted.
  assert(plan.next(5,(1u<<1)|(1u<<4))==0);
  assert(plan.next(5,31)==-1);
  WifiAutoConnectPlan failedScan;
  assert(failedScan.next(5,0)==0);
  assert(failedScan.next(5,1)==1);
  plan.seen(-1,1,-30);plan.seen(8,1,-30);
  assert(plan.visibleMask==((1u<<1)|(1u<<4)));
  assert(plan.next(0,0)==-1);
  assert(plan.next(8,255)==-1);
  std::puts("PASS: setup leases, saved priorities, visible-first startup, strongest channel, hidden-network fallback, failed scans and bounds");
}
