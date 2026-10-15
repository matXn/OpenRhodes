#pragma once
#include <esp_wifi.h>
#include "control_page.h"
#include "splash_asset.h"
#include "portal_page.h"

WebServer httpServer(80);
DNSServer captiveDns;
Preferences networkPrefs;
bool httpReady=false, mdnsReady=false;
const char* AP_PASSWORD="RhodesLight";
String savedSsid, savedPassword, targetSsid, targetPassword, wifiError;
bool wifiChangePending=false, wifiDisconnectWaiting=false, explicitWifiAttempt=false;
uint32_t wifiApplyAt=0, wifiAttemptId=0, wifiLastRetryMs=0, lastApCheckMs=0;
volatile uint16_t lastDisconnectReason=0;
bool scanActive=false, scanStarting=false;
volatile bool scanEventDone=false;
volatile uint32_t scanEventStatus=0;
uint32_t scanBeginAt=0, scanStartedMs=0;
String scanResults="[]", scanError;
int scanCount=0;

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
  String phase=connected?"connected":(wifiChangePending||wifiDisconnectWaiting||networkState==NET_CONNECTING)?"connecting":wifiError.length()?"failed":"idle";
  String name=connected?WiFi.SSID():targetSsid.length()?targetSsid:savedSsid;
  String body="{\"on\":";body+=ledOn?"true":"false";
  body+=",\"brightness\":"+String((brightness*100+PWM_MAX_DUTY/2)/PWM_MAX_DUTY);
  body+=",\"breathing\":";body+=breathingActive?"true":"false";
  body+=",\"connected\":";body+=connected?"true":"false";
  body+=",\"connecting\":";body+=phase=="connecting"?"true":"false";
  body+=",\"ssid\":"+jsonQuote(name)+",\"target_ssid\":"+jsonQuote(targetSsid)+",\"saved_ssid\":"+jsonQuote(savedSsid);
  body+=",\"wifi_phase\":"+jsonQuote(phase)+",\"wifi_error\":"+jsonQuote(wifiError);
  body+=",\"attempt_id\":"+String(wifiAttemptId);
  body+=",\"ip\":"+jsonQuote(connected?WiFi.localIP().toString():String(""));
  body+=",\"ap_ip\":"+jsonQuote(WiFi.softAPIP().toString());
  body+=",\"ap_ssid\":"+jsonQuote(WiFi.softAPSSID());
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
void scheduleWifi(const String& ssid,const String& password,bool explicitRequest) {
  targetSsid=ssid;targetPassword=password;wifiError="";
  wifiChangePending=true;wifiDisconnectWaiting=false;explicitWifiAttempt=explicitRequest;
  lastDisconnectReason=0;wifiApplyAt=millis()+900;wifiAttemptId++;
  networkState=NET_CONNECTING;
}
void handleWifi() {
  if(!localWriteRequest())return;
  String ssid=httpServer.arg("ssid"),pass=httpServer.arg("password");
  if(pass.length()==0&&httpServer.arg("use_saved")=="1"&&ssid==savedSsid&&savedSsid.length())pass=savedPassword;
  if(ssid.length()==0||ssid.length()>32||pass.length()>64||(pass.length()>0&&pass.length()<8)){
    httpError(400,"请检查 Wi-Fi 名称与密码长度");return;
  }
  if(scanActive){httpError(409,"正在扫描，请扫描完成后再连接");return;}
  scheduleWifi(ssid,pass,true);
  // HTTP 响应先送达；网络切换完全在后续 loop 内进行，不重启 AP 或 WebServer。
  sendJson(202,"{\"accepted\":true,\"attempt_id\":"+String(wifiAttemptId)+",\"ssid\":"+jsonQuote(ssid)+"}");
}
bool beginWifiScan() {
  if(scanActive)return true;
  if(wifiChangePending||wifiDisconnectWaiting||networkState==NET_CONNECTING){scanError="正在连接 Wi-Fi，请稍后扫描";return false;}
  WiFi.setAutoReconnect(false);
  if(WiFi.status()!=WL_CONNECTED)WiFi.disconnect(false,false);
  scanActive=true;scanStarting=true;scanError="";scanBeginAt=millis()+300;
  return true;
}
void serviceWifiScan() {
  if(!scanActive)return;
  if(scanStarting) {
    if((int32_t)(millis()-scanBeginAt)<0)return;
    scanStarting=false;WiFi.scanDelete();scanStartedMs=millis();
    scanEventDone=false;scanEventStatus=0;
    wifi_scan_config_t config={};config.scan_type=WIFI_SCAN_TYPE_ACTIVE;
    config.scan_time.active.min=60;config.scan_time.active.max=100;
    config.home_chan_dwell_time=90; // 每扫一个信道返回热点信道，继续为 AP 客户端服务。
    esp_err_t result=esp_wifi_scan_start(&config,false);
    Serial.printf("SCAN start=%d\n",(int)result);
    if(result!=ESP_OK){scanActive=false;scanError="扫描启动失败，请重试";return;}
  }
  if(!scanEventDone&&millis()-scanStartedMs<12000)return;
  int count=scanEventDone&&scanEventStatus==0?WiFi.scanComplete():WIFI_SCAN_FAILED;
  scanActive=false;wifiLastRetryMs=millis();
  Serial.printf("SCAN completed=%d\n",count);
  if(count<0){esp_wifi_scan_stop();scanError="扫描超时或中断，请重新扫描";WiFi.scanDelete();return;}
  // 按信号排序后去重，保留至下次成功扫描；连接过程不清空网络名称。
  int order[64];int used=min(count,64);
  for(int i=0;i<used;i++)order[i]=i;
  for(int i=0;i<used;i++)for(int j=i+1;j<used;j++)if(WiFi.RSSI(order[j])>WiFi.RSSI(order[i])){int v=order[i];order[i]=order[j];order[j]=v;}
  scanResults="[";scanCount=0;
  for(int n=0;n<used;n++) {
    int i=order[n];String ssid=WiFi.SSID(i);if(ssid.length()==0)continue;
    bool duplicate=false;for(int j=0;j<n;j++)if(WiFi.SSID(order[j])==ssid){duplicate=true;break;}
    if(duplicate)continue;
    if(scanCount++)scanResults+=",";
    scanResults+="{\"ssid\":"+jsonQuote(ssid)+",\"rssi\":"+String(WiFi.RSSI(i))+",\"secure\":";
    scanResults+=WiFi.encryptionType(i)==WIFI_AUTH_OPEN?"false":"true";scanResults+="}";
  }
  scanResults+="]";WiFi.scanDelete();
}
String scanJson(){String out="{\"scanning\":";out+=scanActive?"true":"false";return out+",\"networks\":"+scanResults+",\"error\":"+jsonQuote(scanError)+"}";}
void handleScan() {
  if(httpServer.method()==HTTP_POST){if(!localWriteRequest())return;if(!beginWifiScan()){httpError(409,scanError);return;}}
  sendJson(200,scanJson());
}
void preserveCredentials() {
  if(savedSsid==targetSsid&&savedPassword==targetPassword)return;
  networkPrefs.begin("rhodes-net",false);
  networkPrefs.putString("ssid",targetSsid);networkPrefs.putString("pass",targetPassword);networkPrefs.end();
  savedSsid=targetSsid;savedPassword=targetPassword;
}
void startHttpNetwork() {
  WiFi.persistent(false);WiFi.mode(WIFI_AP_STA);WiFi.setHostname("rhodeslight");WiFi.setAutoReconnect(false);
  WiFi.softAPConfig(IPAddress(192,168,4,1),IPAddress(192,168,4,1),IPAddress(255,255,255,0));
  bool apOk=WiFi.softAP(CONFIG_AP_NAME,AP_PASSWORD);
  captiveDns.start(53,"*",WiFi.softAPIP());
  WiFi.onEvent([](WiFiEvent_t event,WiFiEventInfo_t info){
    if(event==ARDUINO_EVENT_WIFI_STA_DISCONNECTED)lastDisconnectReason=info.wifi_sta_disconnected.reason;
    if(event==ARDUINO_EVENT_WIFI_SCAN_DONE){scanEventStatus=info.wifi_scan_done.status;scanEventDone=true;}
  });
  const char* headers[]={"X-Rhodes-Control"};httpServer.collectHeaders(headers,1);
  httpServer.on("/",HTTP_GET,[]{
    httpServer.sendHeader("Cache-Control","no-store, max-age=0");
    httpServer.send_P(200,"text/html; charset=utf-8",CONTROL_PAGE);
  });
  httpServer.on("/portal",HTTP_GET,[]{
    httpServer.sendHeader("Location","http://192.168.4.1/");httpServer.send(302,"text/plain","");
  });
  httpServer.on("/splash",HTTP_GET,[]{
    httpServer.sendHeader("Content-Encoding","gzip");httpServer.sendHeader("Cache-Control","no-store");
    httpServer.send_P(200,"text/html; charset=utf-8",(const char*)SPLASH_GZ,sizeof(SPLASH_GZ));
  });
  httpServer.on("/api/state",HTTP_GET,sendState);httpServer.on("/api/light",HTTP_POST,handleLight);
  httpServer.on("/api/mode",HTTP_POST,handleMode);httpServer.on("/api/wifi",HTTP_POST,handleWifi);
  httpServer.on("/api/scan",HTTP_GET,handleScan);httpServer.on("/api/scan",HTTP_POST,handleScan);
  httpServer.on("/favicon.ico",HTTP_GET,[]{httpServer.send(204);});
  httpServer.onNotFound([]{
    if(httpServer.uri().startsWith("/api/")){httpError(404,"接口不存在");return;}
    httpServer.sendHeader("Location","http://192.168.4.1/");httpServer.send(302,"text/plain","");
  });
  httpServer.begin();httpReady=true;wifiManagerActive=apOk;
  networkPrefs.begin("rhodes-net",true);savedSsid=networkPrefs.getString("ssid","");savedPassword=networkPrefs.getString("pass","");networkPrefs.end();
  if(savedSsid.length()==0) {
    wifi_config_t previous={};
    if(esp_wifi_get_config(WIFI_IF_STA,&previous)==ESP_OK){
      char ssid[33]={},password[65]={};memcpy(ssid,previous.sta.ssid,32);memcpy(password,previous.sta.password,64);savedSsid=ssid;savedPassword=password;
    }
  }
  networkState=NET_PORTAL;
  if(savedSsid.length())scheduleWifi(savedSsid,savedPassword,false);
  Serial.printf("HTTP ready; AP=%s address=192.168.4.1 savedSSID=%s\n",apOk?"on":"FAILED",savedSsid.c_str());
}
void serviceNetwork() {
  if(DISABLE_NETWORK_TEST||!httpReady)return;
  int command=Serial.available()?Serial.read():-1;
  if(command=='s')beginWifiScan();
  if(command=='i'||command=='j'){Serial.print("STATE ");Serial.println(stateJson());}
  if(command=='n'){Serial.print("NETWORKS ");Serial.println(scanJson());}
  if(command=='c'&&savedSsid.length())scheduleWifi(savedSsid,savedPassword,false);
  captiveDns.processNextRequest();httpServer.handleClient();serviceWifiScan();
  uint32_t now=millis();
  if(wifiChangePending&&!touching&&!scanActive&&(int32_t)(now-wifiApplyAt)>=0) {
    wifiChangePending=false;WiFi.disconnect(false,false);wifiDisconnectWaiting=true;wifiApplyAt=now+350;
  }
  if(wifiDisconnectWaiting&&(int32_t)(now-wifiApplyAt)>=0) {
    wifiDisconnectWaiting=false;lastDisconnectReason=0;
    wl_status_t result=WiFi.begin(targetSsid.c_str(),targetPassword.c_str());
    wifiConnectStartMs=now;networkState=NET_CONNECTING;
    Serial.printf("WIFI connecting SSID=%s start=%d AP=%s\n",targetSsid.c_str(),(int)result,WiFi.softAPIP().toString().c_str());
  }
  if(!wifiChangePending&&!wifiDisconnectWaiting) {
    if(WiFi.status()==WL_CONNECTED) {
      if(networkState!=NET_CONNECTED){
        networkState=NET_CONNECTED;wifiError="";preserveCredentials();
        Serial.printf("WIFI connected SSID=%s LAN=%s AP=%s\n",WiFi.SSID().c_str(),WiFi.localIP().toString().c_str(),WiFi.softAPIP().toString().c_str());
        if(!mdnsReady){mdnsReady=MDNS.begin("rhodeslight");if(mdnsReady)MDNS.addService("http","tcp",80);}
      }
    } else if(networkState==NET_CONNECTED){
      networkState=NET_PORTAL;wifiError="家庭 Wi-Fi 已断开，灯具热点仍可使用";wifiLastRetryMs=now;
    } else if(networkState==NET_CONNECTING&&now-wifiConnectStartMs>=25000) {
      networkState=NET_PORTAL;
      uint16_t reason=lastDisconnectReason;
      wifiError=(reason==202||reason==15||reason==204)?"认证失败，请检查密码":reason==201?"未找到所选 2.4 GHz Wi-Fi":"连接超时，请检查密码、信号和路由器";
      WiFi.disconnect(false,false);wifiLastRetryMs=now;
      Serial.printf("WIFI failed reason=%u SSID=%s\n",reason,targetSsid.c_str());
    } else if(networkState==NET_PORTAL&&!explicitWifiAttempt&&savedSsid.length()&&!scanActive&&!touching&&now-wifiLastRetryMs>=60000){
      wifiLastRetryMs=now;scheduleWifi(savedSsid,savedPassword,false);
    }
  }
  if(now-lastApCheckMs>=5000){
    lastApCheckMs=now;
    if(!(WiFi.getMode()&WIFI_AP)){WiFi.enableAP(true);WiFi.softAP(CONFIG_AP_NAME,AP_PASSWORD);}
  }
}
