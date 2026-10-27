#pragma once
#include <esp_wifi.h>
#include "wifi_profiles.h"
#include "wifi_setup_priority.h"
#include "wifi_autoconnect.h"
#include "control_page.h"
#include "splash_asset.h"
#include "portal_page.h"
#include "setup_page.h"

WebServer httpServer(80);
DNSServer captiveDns;
Preferences networkPrefs;
bool httpReady=false, mdnsReady=false;
bool mdnsAttempted=false;
uint32_t lastMdnsAttemptMs=0;
String mdnsStationAddress;
const char* AP_PASSWORD="RhodesLight";
bool setupApHidden=false;
bool enableBleProvision();
String bleProvisionInfo();
void serviceBleProvision();
struct BleWifiNetwork {String ssid;int rssi;bool secure;};
BleWifiNetwork bleNetworks[24];
int bleNetworkCount=0;
uint32_t bleScanGeneration=0;
String savedSsid, savedPassword, targetSsid, targetPassword, wifiError;
bool wifiChangePending=false, wifiDisconnectWaiting=false, explicitWifiAttempt=false;
uint32_t wifiApplyAt=0, wifiAttemptId=0, wifiLastRetryMs=0, lastApCheckMs=0;
volatile uint16_t lastDisconnectReason=0;
bool scanActive=false, scanStarting=false;
volatile bool scanEventDone=false;
volatile uint32_t scanEventStatus=0;
uint32_t scanBeginAt=0, scanStartedMs=0;
uint32_t scanRequestedMs=0, scanStopAt=0;
volatile bool scanStopWaiting=false;
bool scanCleanupPending=false;
bool scanForAutoConnect=false, autoSelectPending=false;
WifiAutoConnectPlan autoConnectPlan;
uint32_t autoScanDurationMs=0, wifiConnectedAtMs=0;
WifiSetupPriority setupPriority;
String scanResults="[]", scanError;
int scanCount=0;
WifiProfiles wifiProfiles;
uint16_t wifiTriedMask=0;
uint32_t serialTestOffAt=0;

void selectPrimaryWifi() {
  savedSsid=wifiProfiles.count?wifiProfiles.entries[0].ssid:"";
  savedPassword=wifiProfiles.count?wifiProfiles.entries[0].password:"";
}
bool saveWifiProfiles(const WifiProfiles& next) {
  networkPrefs.begin("rhodes-net",false);
  bool ok=networkPrefs.putBytes("profiles",&next,sizeof next)==sizeof next;
  if(ok){networkPrefs.remove("ssid");networkPrefs.remove("pass");}
  networkPrefs.end();
  if(ok){wifiProfiles=next;selectPrimaryWifi();}
  return ok;
}
String jsonQuote(const String& value);
String profilesJson() {
  String out="[";
  for(int i=0;i<wifiProfiles.count;i++){
    if(i)out+=",";
    // Never include passwords in HTTP responses.
    out+="{\"ssid\":"+jsonQuote(String(wifiProfiles.entries[i].ssid))+",\"priority\":"+String(i+1)+"}";
  }
  return out+"]";
}

String jsonQuote(const String& value) {
  String out="\"";
  for(size_t i=0;i<value.length();i++) {
    unsigned char c=value[i];
    if(c=='"'||c=='\\'){out+='\\';out+=(char)c;}
    else if(c<32){char escaped[7];snprintf(escaped,sizeof escaped,"\\u%04x",c);out+=escaped;}
    else out+=(char)c;
  }
  return out+'"';
}
String stateJson() {
  bool connected=WiFi.status()==WL_CONNECTED;
  String phase=connected?"connected":(scanForAutoConnect||autoSelectPending||wifiChangePending||wifiDisconnectWaiting||networkState==NET_CONNECTING)?"connecting":wifiError.length()?"failed":"idle";
  String name=connected?WiFi.SSID():targetSsid.length()?targetSsid:savedSsid;
  String body="{\"on\":";body+=ledOn?"true":"false";
  String hardwareMac=WiFi.macAddress();
  String hardwareId=hardwareMac;hardwareId.replace(":","");hardwareId.toLowerCase();
  body+=",\"device_id\":"+jsonQuote("rhodes-"+hardwareId);
  body+=",\"mac\":"+jsonQuote(hardwareMac);
  body+=",\"brightness\":"+String((brightness*100+PWM_MAX_DUTY/2)/PWM_MAX_DUTY);
  body+=",\"breathing\":";body+=breathingActive?"true":"false";
  body+=",\"breath_period\":"+String(breathPeriodMs)+",\"breath_floor\":"+String(breathFloorPct)+",\"breath_curve\":"+String(breathCurvePct);
  float lampPhase=breathingPhase+6.2831853f*((millis()-breathingStartedMs)%breathPeriodMs)/(float)breathPeriodMs;
  body+=",\"breath_phase\":"+String(fmodf(lampPhase,6.2831853f),5);
  body+=",\"uptime_ms\":"+String(millis())+",\"reset_reason\":"+String((int)esp_reset_reason());
  body+=",\"boot_light_on_ms\":"+String(bootLightOnMs);
  body+=",\"wifi_scan_ms\":"+String(autoScanDurationMs)+",\"wifi_connected_at_ms\":"+String(wifiConnectedAtMs);
  body+=",\"pwm_hz\":"+String(ledc_get_freq((ledc_mode_t)(PWM_CH/8),(ledc_timer_t)((PWM_CH/2)%4)));
  body+=",\"pwm_raw\":"+String(getRawPwmDuty())+",\"output_logical\":"+String(currentOutputDuty);
  body+=",\"wifi_disconnect_reason\":"+String(lastDisconnectReason);
  body+=",\"wifi_profiles\":"+profilesJson();
  body+=",\"connected\":";body+=connected?"true":"false";
  body+=",\"connecting\":";body+=phase=="connecting"?"true":"false";
  body+=",\"setup_active\":";body+=setupPriority.active(millis())?"true":"false";
  body+=",\"explicit_attempt\":";body+=explicitWifiAttempt?"true":"false";
  body+=",\"ssid\":"+jsonQuote(name)+",\"target_ssid\":"+jsonQuote(targetSsid)+",\"saved_ssid\":"+jsonQuote(savedSsid);
  body+=",\"wifi_phase\":"+jsonQuote(phase)+",\"wifi_error\":"+jsonQuote(wifiError);
  body+=",\"attempt_id\":"+String(wifiAttemptId);
  body+=",\"ip\":"+jsonQuote(connected?WiFi.localIP().toString():String(""));
  body+=",\"ap_ip\":"+jsonQuote(WiFi.softAPIP().toString());
  body+=",\"ap_ssid\":"+jsonQuote(WiFi.softAPSSID());
  body+=",\"ap_hidden\":";body+=setupApHidden?"true":"false";
  body+=",\"rssi\":"+String(connected?WiFi.RSSI():0)+"}";
  return body;
}
void sendJson(int code,const String& body) {
  httpServer.sendHeader("Cache-Control","no-store, max-age=0");
  httpServer.send(code,"application/json; charset=utf-8",body);
}
void sendState(){sendJson(200,stateJson());}
void httpError(int code,const String& message){sendJson(code,"{\"error\":"+jsonQuote(message)+"}");}
bool localWriteRequest() {
  if(httpServer.header("X-Rhodes-Control")!="1"){httpError(403,"请从灯具页面操作");return false;}
  return true;
}
bool setSetupApHidden(bool hidden,String& error) {
  wifi_config_t config={};
  if(esp_wifi_get_config(WIFI_IF_AP,&config)!=ESP_OK){error="无法读取热点配置";return false;}
  bool previous=config.ap.ssid_hidden!=0;
  if(previous==hidden&&setupApHidden==hidden)return true;
  config.ap.ssid_hidden=hidden?1:0;
  if(esp_wifi_set_config(WIFI_IF_AP,&config)!=ESP_OK){error="热点设置失败，请重试";return false;}
  bool saved=false;
  if(networkPrefs.begin("rhodes-net",false)) {
    saved=networkPrefs.putBool("ap-hidden",hidden)==sizeof(bool);
    networkPrefs.end();
  }
  if(!saved) {
    config.ap.ssid_hidden=previous?1:0;
    bool restored=esp_wifi_set_config(WIFI_IF_AP,&config)==ESP_OK;
    setupApHidden=restored?previous:hidden;
    error=restored?"保存失败，热点已恢复原设置":"保存失败，热点状态未能恢复，请重试";
    return false;
  }
  setupApHidden=hidden;
  return true;
}
void handleSetupAp() {
  // Credentials are returned only by this explicit request, never routine state or logs.
  if(!localWriteRequest())return;
  if(httpServer.method()==HTTP_POST) {
    String value=httpServer.arg("hidden");
    if(value!="0"&&value!="1"){httpError(400,"隐藏设置需为 0 或 1");return;}
    String error;
    if(!setSetupApHidden(value=="1",error)){httpError(503,error);return;}
  }
  wifi_config_t config={};
  if(esp_wifi_get_config(WIFI_IF_AP,&config)!=ESP_OK){httpError(503,"无法读取热点配置");return;}
  char ssid[33]={},password[65]={};
  memcpy(ssid,config.ap.ssid,32);memcpy(password,config.ap.password,64);
  String body="{\"ssid\":"+jsonQuote(ssid)+",\"password\":"+jsonQuote(password);
  body+=",\"hidden\":";body+=config.ap.ssid_hidden?"true":"false";
  body+=",\"ip\":"+jsonQuote(WiFi.softAPIP().toString())+"}";
  sendJson(200,body);
}
bool parsePercent(const String& value,int& result) {
  if(value.length()==0||value.length()>3)return false;
  for(size_t i=0;i<value.length();i++)if(value[i]<'0'||value[i]>'9')return false;
  result=value.toInt();return result<=100;
}
void handleLight() {
  if(!localWriteRequest())return;
  bool level=httpServer.hasArg("brightness"),power=httpServer.hasArg("on");int percent=0;
  if((!level&&!power)||(level&&!parsePercent(httpServer.arg("brightness"),percent))||
    (power&&httpServer.arg("on")!="0"&&httpServer.arg("on")!="1")){
    httpError(400,"亮度需为 0–100，开关需为 0 或 1");return;
  }
  if(touching||touchStableFrames>0){httpError(409,"正在触摸调光，请松手后重试");return;}
  if(level){if(percent==0)ledOn=false;else{brightness=(percent*PWM_MAX_DUTY+50)/100;ledOn=true;}}
  if(power)ledOn=httpServer.arg("on")=="1";
  if(ledOn&&brightness<=0)brightness=PWM_MAX_DUTY;
  startRemoteFade();markPrefsDirty();sendState();
}
void handleMode() {
  if(!localWriteRequest())return;
  String mode=httpServer.arg("mode");
  if(mode!="breathing"&&mode!="steady"){httpError(400,"模式无效");return;}
  if(touching){httpError(409,"请松开触摸条后切换模式");return;}
  if(mode=="breathing")startBreathing();else startRemoteFade();
  markPrefsDirty();sendState();
}
bool parseUInt(const String& value,int maxDigits,int& result) {
  if(value.length()==0||(int)value.length()>maxDigits)return false;
  for(size_t i=0;i<value.length();i++)if(value[i]<'0'||value[i]>'9')return false;
  result=value.toInt();return true;
}
// 呼吸函数参数：period=毫秒，floor=0–80（%），curve=50–300（指数×100）。缺省的参数保持不变。
void handleBreath() {
  if(!localWriteRequest())return;
  int period=breathPeriodMs,floorPct=breathFloorPct,curve=breathCurvePct;
  if((httpServer.hasArg("period")&&!parseUInt(httpServer.arg("period"),5,period))||
     (httpServer.hasArg("floor")&&!parseUInt(httpServer.arg("floor"),3,floorPct))||
     (httpServer.hasArg("curve")&&!parseUInt(httpServer.arg("curve"),3,curve))){
    httpError(400,"呼吸参数无效");return;
  }
  setBreathParams(period,floorPct,curve);markPrefsDirty();sendState();
}
void scheduleWifi(const String& ssid,const String& password,bool explicitRequest) {
  targetSsid=ssid;targetPassword=password;wifiError="";
  wifiChangePending=true;wifiDisconnectWaiting=false;explicitWifiAttempt=explicitRequest;
  lastDisconnectReason=0;wifiApplyAt=millis()+(explicitRequest?900:50);wifiAttemptId++;
  networkState=NET_CONNECTING;
}
void holdWifiSetup(bool interruptExplicit=false) {
  setupPriority.hold(millis());
  // The in-flight scan may finish for the setup page, but must not auto-connect.
  scanForAutoConnect=false;autoSelectPending=false;
  WiFi.setAutoReconnect(false);
  if((wifiChangePending||wifiDisconnectWaiting||networkState==NET_CONNECTING)&&
     (interruptExplicit||!explicitWifiAttempt)) {
    wifiChangePending=false;wifiDisconnectWaiting=false;explicitWifiAttempt=false;
    if(WiFi.status()!=WL_CONNECTED)WiFi.disconnect(false,false);
    networkState=WiFi.status()==WL_CONNECTED?NET_CONNECTED:NET_PORTAL;
    wifiError="";lastDisconnectReason=0;wifiLastRetryMs=millis();
  }
}
void cancelWifiScan() {
  bool running=scanActive&&!scanStarting;
  scanActive=false;scanStarting=false;scanError="";
  scanForAutoConnect=false;autoSelectPending=false;
  if(running){scanStopWaiting=true;scanStopAt=millis();esp_wifi_scan_stop();}
  scanCleanupPending=true;scanEventDone=false;
}
void handleSetupSession() {
  if(!localWriteRequest())return;
  if(httpServer.arg("action")=="release") {
    setupPriority.release();wifiLastRetryMs=millis()-60000;wifiTriedMask=0;
  } else holdWifiSetup();
  sendState();
}
void handleWifi() {
  if(!localWriteRequest())return;
  String ssid=httpServer.arg("ssid"),pass=httpServer.arg("password");
  int savedIndex=wifiProfiles.find(ssid.c_str());
  if(pass.length()==0&&httpServer.arg("use_saved")=="1"&&savedIndex>=0)pass=wifiProfiles.entries[savedIndex].password;
  if(savedIndex<0&&wifiProfiles.count==WifiProfiles::CAPACITY){httpError(409,"已保存 8 个网络，请先忘记一个");return;}
  if(ssid.length()==0||ssid.length()>32||pass.length()>64||(pass.length()>0&&pass.length()<8)){
    httpError(400,"请检查 Wi-Fi 名称与密码长度");return;
  }
  // A user-selected network supersedes both a scan and any old connection attempt.
  holdWifiSetup(true);cancelWifiScan();
  wifiTriedMask=0;
  scheduleWifi(ssid,pass,true);
  // HTTP 响应先送达；网络切换完全在后续 loop 内进行，不重启 AP 或 WebServer。
  sendJson(202,"{\"accepted\":true,\"attempt_id\":"+String(wifiAttemptId)+",\"ssid\":"+jsonQuote(ssid)+"}");
}
bool beginWifiScan() {
  // Scanning is a user action, so stop an uncompleted STA connection first.
  holdWifiSetup(true);
  if(scanActive)return true;
  WiFi.setAutoReconnect(false);
  if(WiFi.status()!=WL_CONNECTED)WiFi.disconnect(false,false);
  scanActive=true;scanStarting=true;scanError="";scanRequestedMs=millis();scanBeginAt=scanRequestedMs+300;
  bleScanGeneration++;
  return true;
}
bool startAutomaticWifiScan() {
  if(!wifiProfiles.count||!setupPriority.allowAutomatic(millis(),scanActive||scanStopWaiting))return false;
  autoConnectPlan=WifiAutoConnectPlan{};wifiTriedMask=0;autoSelectPending=false;
  autoScanDurationMs=0;wifiConnectedAtMs=0;
  WiFi.setAutoReconnect(false);
  if(WiFi.status()!=WL_CONNECTED)WiFi.disconnect(false,false);
  networkState=NET_PORTAL;wifiLastRetryMs=millis();
  scanForAutoConnect=true;scanActive=true;scanStarting=true;scanError="";
  scanRequestedMs=millis();scanBeginAt=scanRequestedMs+50;
  return true;
}
void finishScanSelection() {
  if(scanForAutoConnect)autoSelectPending=true;
  scanForAutoConnect=false;
}
void serviceWifiScan() {
  // Drain the canceled scan's event before a new scan/association can reuse it.
  if(scanStopWaiting&&uint32_t(millis()-scanStopAt)>=1500)scanStopWaiting=false;
  if(scanCleanupPending&&!scanStopWaiting){WiFi.scanDelete();scanCleanupPending=false;}
  if(!scanActive)return;
  if(scanStarting) {
    if(scanStopWaiting)return;
    if((int32_t)(millis()-scanBeginAt)<0)return;
    WiFi.scanDelete();scanStartedMs=millis();
    scanEventDone=false;scanEventStatus=0;
    wifi_scan_config_t config={};config.scan_type=WIFI_SCAN_TYPE_ACTIVE;
    config.scan_time.active.min=60;config.scan_time.active.max=100;
    config.home_chan_dwell_time=90; // 每扫一个信道返回热点信道，继续为 AP 客户端服务。
    esp_err_t result=esp_wifi_scan_start(&config,false);
    Serial.printf("SCAN start=%d\n",(int)result);
    if(result==ESP_ERR_WIFI_STATE&&uint32_t(millis()-scanRequestedMs)<2500){
      if(WiFi.status()!=WL_CONNECTED)WiFi.disconnect(false,false);
      scanBeginAt=millis()+200;return;
    }
    if(result!=ESP_OK){scanActive=false;scanError="扫描启动失败，请重试";finishScanSelection();return;}
    scanStarting=false;
  }
  if(!scanEventDone&&millis()-scanStartedMs<12000)return;
  int count=scanEventDone&&scanEventStatus==0?WiFi.scanComplete():WIFI_SCAN_FAILED;
  scanActive=false;wifiLastRetryMs=millis();
  Serial.printf("SCAN completed=%d\n",count);
  if(count<0){
    scanStopWaiting=true;scanStopAt=millis();esp_wifi_scan_stop();
    scanCleanupPending=true;scanError="扫描超时或中断，请重新扫描";finishScanSelection();return;
  }
  if(scanForAutoConnect){
    autoScanDurationMs=millis()-scanStartedMs;
    for(int i=0;i<count;i++){
      int savedIndex=wifiProfiles.find(WiFi.SSID(i).c_str());
      autoConnectPlan.seen(savedIndex,WiFi.channel(i),WiFi.RSSI(i));
    }
    Serial.printf("AUTO scan done at %lu ms: duration=%lu ms visibleMask=%u\n",
      (unsigned long)millis(),(unsigned long)(millis()-scanStartedMs),autoConnectPlan.visibleMask);
  }
  // 按信号排序后去重，保留至下次成功扫描；连接过程不清空网络名称。
  int order[64];int used=min(count,64);
  for(int i=0;i<used;i++)order[i]=i;
  for(int i=0;i<used;i++)for(int j=i+1;j<used;j++)if(WiFi.RSSI(order[j])>WiFi.RSSI(order[i])){int v=order[i];order[i]=order[j];order[j]=v;}
  scanResults="[";scanCount=0;bleNetworkCount=0;
  for(int n=0;n<used;n++) {
    int i=order[n];String ssid=WiFi.SSID(i);if(ssid.length()==0)continue;
    bool duplicate=false;for(int j=0;j<n;j++)if(WiFi.SSID(order[j])==ssid){duplicate=true;break;}
    if(duplicate)continue;
    if(bleNetworkCount<24)bleNetworks[bleNetworkCount++]={ssid,WiFi.RSSI(i),WiFi.encryptionType(i)!=WIFI_AUTH_OPEN};
    if(scanCount++)scanResults+=",";
    scanResults+="{\"ssid\":"+jsonQuote(ssid)+",\"rssi\":"+String(WiFi.RSSI(i))+",\"secure\":";
    scanResults+=WiFi.encryptionType(i)==WIFI_AUTH_OPEN?"false":"true";scanResults+="}";
  }
  scanResults+="]";WiFi.scanDelete();finishScanSelection();
}
String scanJson(){String out="{\"scanning\":";out+=scanActive?"true":"false";return out+",\"networks\":"+scanResults+",\"error\":"+jsonQuote(scanError)+"}";}
void handleScan() {
  if(httpServer.method()==HTTP_POST){if(!localWriteRequest())return;if(!beginWifiScan()){httpError(409,scanError);return;}}
  sendJson(200,scanJson());
}
// A complete priority round is attempted once, followed by a quiet retry interval.
bool scheduleNextSavedWifi() {
  if(!setupPriority.allowAutomatic(millis(),scanActive||scanStopWaiting))return false;
  int i=autoConnectPlan.next(wifiProfiles.count,wifiTriedMask);
  if(i>=0) {
    wifiTriedMask|=1u<<i;
    scheduleWifi(wifiProfiles.entries[i].ssid,wifiProfiles.entries[i].password,false);
    return true;
  }
  return false;
}
void handleProfiles() {
  if(httpServer.method()==HTTP_GET){sendJson(200,"{\"networks\":"+profilesJson()+"}");return;}
  if(!localWriteRequest())return;
  if(scanForAutoConnect||autoSelectPending||wifiChangePending||wifiDisconnectWaiting||networkState==NET_CONNECTING){httpError(409,"连接中，请稍后调整网络");return;}
  int index=wifiProfiles.find(httpServer.arg("ssid").c_str());
  if(index<0){httpError(404,"未保存此网络");return;}
  WifiProfiles next=wifiProfiles;
  String action=httpServer.arg("action");
  if(action=="forget")next.erase(index);
  else if(action=="move") {
    int priority=0;
    if(!parseUInt(httpServer.arg("priority"),1,priority)||priority<1||priority>next.count){httpError(400,"优先级无效");return;}
    next.move(index,priority-1);
  } else {httpError(400,"操作无效");return;}
  if(!saveWifiProfiles(next)){httpError(500,"保存失败，请重试");return;}
  // Forget only removes stored credentials; an active connection stays usable.
  wifiTriedMask=0;
  sendState();
}
void preserveCredentials() {
  int i=wifiProfiles.find(targetSsid.c_str());
  bool promote=explicitWifiAttempt;
  if(i>=0&&targetPassword==wifiProfiles.entries[i].password&&(!promote||i==0))return;
  WifiProfiles next=wifiProfiles;
  bool remembered=next.remember(targetSsid.c_str(),targetPassword.c_str(),promote);
  if(remembered&&!saveWifiProfiles(next))
    wifiError="已连接，但网络保存失败，请重新连接后再试";
}
// ESP-IDF handles mDNS queries in its own task. Retry initialization failures
// and republish after a STA address change; keep discovery available on the AP.
void serviceMdns(uint32_t now) {
  String station=WiFi.status()==WL_CONNECTED?WiFi.localIP().toString():String("");
  if(mdnsReady&&station!=mdnsStationAddress){MDNS.end();mdnsReady=false;}
  if(mdnsReady||(mdnsAttempted&&now-lastMdnsAttemptMs<2000))return;
  mdnsAttempted=true;lastMdnsAttemptMs=now;
  if(!MDNS.begin("rhodeslight")){
    MDNS.end();Serial.println("mDNS init failed; retry in 2s");return;
  }
  String mac=WiFi.macAddress();
  String id=mac;id.replace(":","");id.toLowerCase();
  MDNS.setInstanceName("RhodesLight "+id.substring(6));
  if(!MDNS.addService("http","tcp",80)){
    MDNS.end();Serial.println("mDNS service failed; retry in 2s");return;
  }
  MDNS.addServiceTxt("http","tcp","mac",mac);
  MDNS.addServiceTxt("http","tcp","device_id","rhodes-"+id);
  MDNS.addServiceTxt("http","tcp","model","RhodesLight");
  mdnsStationAddress=station;mdnsReady=true;
  Serial.printf("mDNS ready: rhodeslight.local LAN=%s AP=%s\n",
    station.c_str(),WiFi.softAPIP().toString().c_str());
}
void startHttpNetwork() {
  if(networkPrefs.begin("rhodes-net",true)) {
    setupApHidden=networkPrefs.getBool("ap-hidden",false);networkPrefs.end();
  }
  WiFi.persistent(false);WiFi.mode(WIFI_AP_STA);WiFi.setHostname("rhodeslight");WiFi.setAutoReconnect(false);
  WiFi.softAPConfig(IPAddress(192,168,4,1),IPAddress(192,168,4,1),IPAddress(255,255,255,0));
  bool apOk=WiFi.softAP(CONFIG_AP_NAME,AP_PASSWORD,1,setupApHidden?1:0);
  captiveDns.start(53,"*",WiFi.softAPIP());
  WiFi.onEvent([](WiFiEvent_t event,WiFiEventInfo_t info){
    if(event==ARDUINO_EVENT_WIFI_STA_DISCONNECTED)lastDisconnectReason=info.wifi_sta_disconnected.reason;
    if(event==ARDUINO_EVENT_WIFI_SCAN_DONE){scanEventStatus=info.wifi_scan_done.status;scanEventDone=true;scanStopWaiting=false;}
  });
  const char* headers[]={"X-Rhodes-Control"};httpServer.collectHeaders(headers,1);
  httpServer.on("/",HTTP_GET,[]{
    httpServer.sendHeader("Cache-Control","no-store, max-age=0");
    httpServer.send_P(200,"text/html; charset=utf-8",CONTROL_PAGE);
  });
  // 配网页：连上灯的热点时系统弹出的登录窗口会被重定向到这里。
  httpServer.on("/setup",HTTP_GET,[]{
    holdWifiSetup();
    httpServer.sendHeader("Cache-Control","no-store, max-age=0");
    httpServer.send_P(200,"text/html; charset=utf-8",SETUP_PAGE);
  });
  httpServer.on("/portal",HTTP_GET,[]{
    httpServer.sendHeader("Location","http://192.168.4.1/setup");httpServer.send(302,"text/plain","");
  });
  httpServer.on("/splash",HTTP_GET,[]{
    httpServer.sendHeader("Content-Encoding","gzip");httpServer.sendHeader("Cache-Control","no-store");
    httpServer.send_P(200,"text/html; charset=utf-8",(const char*)SPLASH_GZ,sizeof(SPLASH_GZ));
  });
  httpServer.on("/api/state",HTTP_GET,sendState);httpServer.on("/api/light",HTTP_POST,handleLight);
  httpServer.on("/api/mode",HTTP_POST,handleMode);httpServer.on("/api/breath",HTTP_POST,handleBreath);httpServer.on("/api/wifi",HTTP_POST,handleWifi);
  httpServer.on("/api/wifi/profiles",HTTP_GET,handleProfiles);
  httpServer.on("/api/wifi/profiles",HTTP_POST,handleProfiles);
  httpServer.on("/api/wifi/setup",HTTP_POST,handleSetupSession);
  httpServer.on("/api/wifi/ap",HTTP_GET,handleSetupAp);
  httpServer.on("/api/wifi/ap",HTTP_POST,handleSetupAp);
  httpServer.on("/api/wifi/ble",HTTP_GET,[]{if(localWriteRequest())sendJson(200,bleProvisionInfo());});
  httpServer.on("/api/wifi/ble",HTTP_POST,[]{
    if(!localWriteRequest())return;
    if(!enableBleProvision()){httpError(503,"蓝牙配网启动失败，AP 配网仍可使用");return;}
    sendJson(200,bleProvisionInfo());
  });
  httpServer.on("/api/scan",HTTP_GET,handleScan);httpServer.on("/api/scan",HTTP_POST,handleScan);
  httpServer.on("/favicon.ico",HTTP_GET,[]{httpServer.send(204);});
  httpServer.onNotFound([]{
    if(httpServer.uri().startsWith("/api/")){httpError(404,"接口不存在");return;}
    // 手机连上热点后的联网探测（generate_204、hotspot-detect 等）都落到这里 → 弹出配网页。
    httpServer.sendHeader("Location","http://192.168.4.1/setup");httpServer.send(302,"text/plain","");
  });
  httpServer.begin();httpReady=true;wifiManagerActive=apOk;
  networkPrefs.begin("rhodes-net",true);savedSsid=networkPrefs.getString("ssid","");savedPassword=networkPrefs.getString("pass","");networkPrefs.end();
  if(savedSsid.length()==0) {
    wifi_config_t previous={};
    if(esp_wifi_get_config(WIFI_IF_STA,&previous)==ESP_OK){
      char ssid[33]={},password[65]={};memcpy(ssid,previous.sta.ssid,32);memcpy(password,previous.sta.password,64);savedSsid=ssid;savedPassword=password;
    }
  }
  WifiProfiles loaded;
  networkPrefs.begin("rhodes-net",true);
  bool hasProfiles=networkPrefs.isKey("profiles");
  bool loadedOk=networkPrefs.getBytesLength("profiles")==sizeof loaded &&
    networkPrefs.getBytes("profiles",&loaded,sizeof loaded)==sizeof loaded && loaded.valid();
  networkPrefs.end();
  if(loadedOk){wifiProfiles=loaded;selectPrimaryWifi();}
  else if(!hasProfiles&&savedSsid.length()) {
    // Only migrate legacy data when no new profile blob exists (including empty lists).
    loaded.remember(savedSsid.c_str(),savedPassword.c_str());saveWifiProfiles(loaded);
  } else {wifiProfiles=WifiProfiles{};selectPrimaryWifi();}
  networkState=NET_PORTAL;
  startAutomaticWifiScan();
  Serial.printf("HTTP ready; AP=%s address=192.168.4.1 savedSSID=%s\n",apOk?"on":"FAILED",savedSsid.c_str());
}
void serviceNetwork() {
  if(DISABLE_NETWORK_TEST||!httpReady)return;
  serviceBleProvision();
  int command=Serial.available()?Serial.read():-1;
  // USB diagnostic only: '+' advances 1%, capped at 20%; 'x' stops.
  // Never persist test levels; stop automatically two seconds after the last step.
  if(command=='+') {
    int percent = ledOn ? (brightness*100+PWM_MAX_DUTY/2)/PWM_MAX_DUTY : 0;
    percent = min(20,percent+1);
    cancelRemoteFade();brightness=(percent*PWM_MAX_DUTY+50)/100;ledOn=true;
    applyLightOutput();serialTestOffAt=millis()+2000;
    Serial.print("PWM_TEST ");Serial.println(stateJson());
  }
  if(command=='x'||(serialTestOffAt&&(int32_t)(millis()-serialTestOffAt)>=0)) {
    serialTestOffAt=0;cancelRemoteFade();ledOn=false;applyLightOutput();
    Serial.print("PWM_TEST_STOP ");Serial.println(stateJson());
  }
  if(command=='s')beginWifiScan();
  if(command=='i'||command=='j'){Serial.print("STATE ");Serial.println(stateJson());}
  if(command=='n'){Serial.print("NETWORKS ");Serial.println(scanJson());}
  if(command=='c')startAutomaticWifiScan();
  captiveDns.processNextRequest();httpServer.handleClient();serviceWifiScan();
  uint32_t now=millis();
  if(autoSelectPending&&!touching&&setupPriority.allowAutomatic(now,scanActive||scanStopWaiting)){
    autoSelectPending=false;scheduleNextSavedWifi();
  }
  if(setupPriority.held&&!setupPriority.active(now)){
    setupPriority.release();wifiTriedMask=0;wifiLastRetryMs=now-60000;
  }
  if(wifiChangePending&&!touching&&!scanActive&&(int32_t)(now-wifiApplyAt)>=0) {
    wifiChangePending=false;WiFi.disconnect(false,false);wifiDisconnectWaiting=true;wifiApplyAt=now+(explicitWifiAttempt?350:100);
  }
  if(wifiDisconnectWaiting&&!scanActive&&!scanStopWaiting&&(int32_t)(now-wifiApplyAt)>=0) {
    wifiDisconnectWaiting=false;lastDisconnectReason=0;
    int savedIndex=wifiProfiles.find(targetSsid.c_str());
    int channel=(!explicitWifiAttempt&&savedIndex>=0)?autoConnectPlan.channels[savedIndex]:0;
    wl_status_t result=WiFi.begin(targetSsid.c_str(),targetPassword.c_str(),channel);
    wifiConnectStartMs=now;networkState=NET_CONNECTING;
    Serial.printf("WIFI connecting SSID=%s start=%d AP=%s\n",targetSsid.c_str(),(int)result,WiFi.softAPIP().toString().c_str());
  }
  if(!wifiChangePending&&!wifiDisconnectWaiting) {
    if(WiFi.status()==WL_CONNECTED) {
      if(networkState!=NET_CONNECTED){
        networkState=NET_CONNECTED;wifiConnectedAtMs=now;wifiError="";preserveCredentials();explicitWifiAttempt=false;wifiTriedMask=0;
        Serial.printf("WIFI connected SSID=%s LAN=%s AP=%s\n",WiFi.SSID().c_str(),WiFi.localIP().toString().c_str(),WiFi.softAPIP().toString().c_str());
      }
    } else if(networkState==NET_CONNECTED){
      networkState=NET_PORTAL;wifiError="家庭 Wi-Fi 已断开，灯具热点仍可使用";wifiLastRetryMs=now;
      startAutomaticWifiScan();
    } else if(networkState==NET_CONNECTING&&(now-wifiConnectStartMs>=25000||
      (!explicitWifiAttempt&&lastDisconnectReason==201&&now-wifiConnectStartMs>=1500))) {
      networkState=NET_PORTAL;
      uint16_t reason=lastDisconnectReason;
      wifiError=(reason==202||reason==15||reason==204)?"认证失败，请检查密码":reason==201?"未找到所选 2.4 GHz Wi-Fi":"连接超时，请检查密码、信号和路由器";
      WiFi.disconnect(false,false);wifiLastRetryMs=now;
      Serial.printf("WIFI failed reason=%u SSID=%s\n",reason,targetSsid.c_str());
      // Explicit password failures stay visible; automatic rounds try the next profile.
      if(!explicitWifiAttempt)scheduleNextSavedWifi();
    } else if(networkState==NET_PORTAL&&!explicitWifiAttempt&&savedSsid.length()&&setupPriority.allowAutomatic(now,scanActive||scanStopWaiting)&&!touching&&now-wifiLastRetryMs>=60000){
      startAutomaticWifiScan();
    }
  }
  serviceMdns(now);
  if(now-lastApCheckMs>=5000){
    lastApCheckMs=now;
    if(!(WiFi.getMode()&WIFI_AP)){WiFi.enableAP(true);WiFi.softAP(CONFIG_AP_NAME,AP_PASSWORD,1,setupApHidden?1:0);}
  }
}
