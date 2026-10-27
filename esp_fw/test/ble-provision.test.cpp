#include "../src/ble_provision_protocol.h"
#include <cassert>
#include <cstdio>
#include <vector>
int main() {
  BleProvisionRequest request;
  uint8_t state[]={1};assert(request.decode(state,1));
  uint8_t scan[]={2};assert(request.decode(scan,1));
  uint8_t page[]={3,22};assert(request.decode(page,2)&&request.offset==22);
  uint8_t close[]={5};assert(request.decode(close,1));
  uint8_t saved[]={6,4,'h','o','m','e'};assert(request.decode(saved,sizeof saved)&&!strcmp(request.ssid,"home"));
  assert(!request.decode(saved,sizeof saved-1));
  const char* name="Lamp & WiFi";const char* pass=" a+b&1234 ";
  std::vector<uint8_t> wire={4,(uint8_t)strlen(name),(uint8_t)strlen(pass)};
  wire.insert(wire.end(),name,name+strlen(name));wire.insert(wire.end(),pass,pass+strlen(pass));
  assert(request.decode(wire.data(),wire.size()));
  assert(!strcmp(request.ssid,name)&&!strcmp(request.password,pass));
  assert(!request.decode(wire.data(),wire.size()-1));
  wire[3]=0;assert(!request.decode(wire.data(),wire.size()));
  uint8_t open[]={4,4,0,'o','p','e','n'};assert(request.decode(open,sizeof open));
  uint8_t invalid[]={4,1,7,'x','1','2','3','4','5','6','7'};assert(!request.decode(invalid,sizeof invalid));
  uint8_t unknown[]={9};assert(!request.decode(unknown,1));
  assert(!request.decode(nullptr,1));assert(!request.decode(state,0));
  puts("PASS: BLE frame operations, exact credentials, open network, lengths, truncation and NUL rejection");
}
