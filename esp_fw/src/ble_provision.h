#pragma once
#include <Arduino.h>            // 先引入 FreeRTOS.h；队列/信号量头要求它在前面
#include <WiFi.h>               // bleCompactState() 读 WiFi.status()/localIP()
#include <protocomm.h>
#include <protocomm_ble.h>
#include <protocomm_security1.h>
#include <freertos/queue.h>
#include <freertos/semphr.h>
#include "ble_provision_protocol.h"
// 显式声明本 module 依赖的 interface。此前缺这几行，能编译完全依赖 main.cpp 的 include 顺序。
#include "board_config.h"
#include "http_control.h"
#include "light_contract.h"

constexpr uint32_t BLE_SETUP_WINDOW_MS=300000;
protocomm_t* bleProvision=nullptr;
// protocomm retains service_uuid/config pointers for async GAP/GATT callbacks.
protocomm_ble_config_t bleConfig={};
protocomm_ble_name_uuid_t bleEndpoints[]={{"prov-session",0xff51},{"proto-ver",0xff52},{"rhodes-config",0xff53}};
String bleDeviceName;
uint32_t bleUntilMs=0,bleToken=0;
QueueHandle_t bleRequests=nullptr,bleReplies=nullptr;
SemaphoreHandle_t bleHandlerLock=nullptr;
struct BleQueuedRequest {uint32_t token;uint8_t size;uint8_t data[101];};
struct BleQueuedReply {uint32_t token;uint16_t size;char data[512];};

esp_err_t bleRequestHandler(uint32_t,const uint8_t* input,ssize_t size,
    uint8_t** output,ssize_t* outputSize,void*) {
  if(size<1||size>101||!bleHandlerLock)return ESP_ERR_INVALID_ARG;
  if(xSemaphoreTake(bleHandlerLock,pdMS_TO_TICKS(100))!=pdTRUE)return ESP_FAIL;
  BleQueuedRequest request={};request.token=++bleToken;request.size=size;
  memcpy(request.data,input,size);
  BleQueuedReply reply={};
  while(xQueueReceive(bleReplies,&reply,0)==pdTRUE){}
  bool ok=xQueueSend(bleRequests,&request,0)==pdTRUE;
  uint32_t began=millis();bool received=false;
  while(ok&&millis()-began<3000) {
    if(xQueueReceive(bleReplies,&reply,pdMS_TO_TICKS(100))==pdTRUE&&reply.token==request.token){received=true;break;}
  }
  memset(&request,0,sizeof request);
  if(received) {
    *output=(uint8_t*)malloc(reply.size);
    ok=*output!=nullptr;
    if(ok){memcpy(*output,reply.data,reply.size);*outputSize=reply.size;}
  } else ok=false;
  xSemaphoreGive(bleHandlerLock);return ok?ESP_OK:ESP_FAIL;
}
bool enableBleProvision() {
  if(bleProvision){bleUntilMs=millis()+BLE_SETUP_WINDOW_MS;return true;}
  if(!bleRequests)bleRequests=xQueueCreate(1,sizeof(BleQueuedRequest));
  if(!bleReplies)bleReplies=xQueueCreate(1,sizeof(BleQueuedReply));
  if(!bleHandlerLock)bleHandlerLock=xSemaphoreCreateMutex();
  if(!bleRequests||!bleReplies||!bleHandlerLock)return false;
  bleProvision=protocomm_new();if(!bleProvision)return false;
  String mac=WiFi.macAddress();mac.replace(":","");
  bleDeviceName="RhodesLight-"+mac.substring(6);
  protocomm_ble_config_t& config=bleConfig;
  memset(&config,0,sizeof config);
  bleDeviceName.toCharArray(config.device_name,sizeof config.device_name);
  const uint8_t uuid[]={0x10,0x4e,0xb5,0x0c,0x4b,0x10,0x6f,0x9e,0xd0,0x4a,0xf7,0x43,0x6c,0xb3,0xe7,0x95};
  memcpy(config.service_uuid,uuid,16);
  config.nu_lookup=bleEndpoints;config.nu_lookup_count=3;
  if(protocomm_ble_start(bleProvision,&config)!=ESP_OK){protocomm_delete(bleProvision);bleProvision=nullptr;return false;}
  // Security 1 retains X25519/AES-CTR; nullptr omits the optional possession code.
  bool ok=protocomm_set_security(bleProvision,"prov-session",&protocomm_security1,nullptr)==ESP_OK &&
    protocomm_set_version(bleProvision,"proto-ver","{\"rhodes\":{\"ver\":1,\"sec_ver\":1,\"pop_required\":false}}") == ESP_OK &&
    protocomm_add_endpoint(bleProvision,"rhodes-config",bleRequestHandler,nullptr)==ESP_OK;
  if(!ok){protocomm_ble_stop(bleProvision);protocomm_delete(bleProvision);bleProvision=nullptr;return false;}
  bleUntilMs=millis()+BLE_SETUP_WINDOW_MS;
  Serial.printf("BLE provisioning ready: %s (at least 5 min; until Wi-Fi setup succeeds)\n",bleDeviceName.c_str());
  return true;
}
String bleProvisionInfo() {
  return "{\"ready\":"+String(bleProvision?"true":"false")+",\"name\":"+jsonQuote(bleDeviceName)+
    ",\"pop_required\":false,\"expires_ms\":"+
    String(!bleProvision?0:wifiProfiles.count==0?-1:max(0,(int32_t)(bleUntilMs-millis())))+"}";
}
String bleCompactState() {
  bool pending=wifiChangePending||wifiDisconnectWaiting||networkState==NET_CONNECTING;
  bool connected=WiFi.status()==WL_CONNECTED&&!pending;
  String status=pending?"connecting":wifiError.length()?"failed":connected?"connected":"ready";
  return "{\"v\":1,\"mac\":"+jsonQuote(WiFi.macAddress())+",\"status\":"+jsonQuote(status)+
    ",\"attempt_id\":"+String(wifiAttemptId)+",\"ip\":"+jsonQuote(connected?WiFi.localIP().toString():String(""))+
    ",\"ssid\":"+jsonQuote(connected?WiFi.SSID():targetSsid)+",\"error\":"+jsonQuote(wifiError)+"}";
}
String processBleRequest(const BleProvisionRequest& request) {
  if(request.operation==5){setupPriority.release();return "{\"v\":1,\"closed\":true}";}
  holdWifiSetup();
  if(request.operation==1)return bleCompactState();
  if(request.operation==2){beginWifiScan();return "{\"v\":1,\"scanning\":true}";}
  if(request.operation==3) {
    String out="{\"v\":1,\"scanning\":";out+=scanActive?"true":"false";
    out+=",\"generation\":"+String(bleScanGeneration)+",\"error\":"+jsonQuote(scanError)+",\"networks\":[";
    int end=min((int)request.offset,bleNetworkCount);
    while(end<bleNetworkCount&&end<(int)request.offset+2) {
      String item="{\"ssid\":"+jsonQuote(bleNetworks[end].ssid)+",\"rssi\":"+String(bleNetworks[end].rssi)+",\"secure\":";
      item+=bleNetworks[end].secure?"true":"false";
      item+=",\"saved\":";item+=wifiProfiles.find(bleNetworks[end].ssid.c_str())>=0?"true":"false";item+="}";
      if(out.length()+item.length()+32>512)break;
      if(end!=request.offset)out+=",";
      out+=item;end++;
    }
    out+="],\"next\":"+String(end<bleNetworkCount?end:-1)+"}";return out;
  }
  int index=wifiProfiles.find(request.ssid);
  if(request.operation==6&&index<0)return "{\"error\":\"此网络没有已保存的密码\"}";
  if(index<0&&wifiProfiles.count==WifiProfiles::CAPACITY)return "{\"error\":\"已保存 8 个网络，请先忘记一个\"}";
  holdWifiSetup(true);cancelWifiScan();wifiTriedMask=0;
  scheduleWifi(request.ssid,request.operation==6?wifiProfiles.entries[index].password:request.password,true);
  return "{\"v\":1,\"accepted\":true,\"attempt_id\":"+String(wifiAttemptId)+"}";
}
void serviceBleProvision() {
  if(!bleProvision)return;
  BleQueuedRequest queued={};
  if(xQueueReceive(bleRequests,&queued,0)==pdTRUE) {
    bleUntilMs=millis()+BLE_SETUP_WINDOW_MS;
    BleProvisionRequest request;
    String response=request.decode(queued.data,queued.size)?processBleRequest(request):"{\"error\":\"配网请求格式无效\"}";
    memset(&request,0,sizeof request);memset(queued.data,0,sizeof queued.data);
    if(response.length()>sizeof(BleQueuedReply::data))response="{\"error\":\"配网响应过长\"}";
    BleQueuedReply reply={};reply.token=queued.token;reply.size=response.length();
    memcpy(reply.data,response.c_str(),reply.size);xQueueOverwrite(bleReplies,&reply);
  }
  // No saved profile means setup has never completed. Keep advertising until it does.
  if(wifiProfiles.count>0&&(int32_t)(millis()-bleUntilMs)>=0&&xSemaphoreTake(bleHandlerLock,0)==pdTRUE) {
    protocomm_ble_stop(bleProvision);protocomm_delete(bleProvision);bleProvision=nullptr;
    xSemaphoreGive(bleHandlerLock);Serial.println("BLE provisioning idle; AP remains available");
  }
}
