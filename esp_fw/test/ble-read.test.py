"""Exercise the actual vendored ATT read callback, including interrupted reads."""
from pathlib import Path
import subprocess
import sys

root = Path(__file__).resolve().parents[1]
source = Path(sys.argv[1]).read_text() if len(sys.argv) > 1 else (
    root / 'src/vendor/espidf_protocomm/protocomm_ble.c').read_text()
start = source.index('static void transport_simple_ble_read(')
brace = source.index('{', start)
depth, end = 1, brace + 1
while depth:
    depth += (source[end] == '{') - (source[end] == '}')
    end += 1
callback = source[start:end]
code = r'''
#include <cstdint>
#include <cstring>
#include <algorithm>
#include <string>
#include <cstdio>
#include <cstdlib>
using esp_gatts_cb_event_t=int; using esp_gatt_if_t=int;
using esp_gatt_status_t=int; using esp_err_t=int;
constexpr int ESP_OK=0, ESP_GATT_OK=0, ESP_GATT_INVALID_OFFSET=7, ESP_GATT_AUTH_REQ_NONE=0;
#define MIN(a,b) std::min<int>(a,b)
#define ESP_LOGD(...) ((void)0)
#define ESP_LOGE(...) ((void)0)
struct esp_ble_gatts_cb_param_t {struct {uint16_t handle,offset,conn_id,trans_id;} read;};
struct esp_gatt_rsp_t {struct {uint16_t handle,offset,len,auth_req;uint8_t value[600];} attr_value;};
struct {uint16_t gatt_mtu=23;} config;
auto protoble_internal=&config;
std::string attribute, received; int sentStatus;
int esp_ble_gatts_get_attr_value(uint16_t,uint16_t* len,const uint8_t** data){
 *len=attribute.size();*data=(const uint8_t*)attribute.data();return 0;
}
int esp_ble_gatts_send_response(int,int,int,int status,esp_gatt_rsp_t* r){
 sentStatus=status;received.assign((char*)r->attr_value.value,r->attr_value.len);return 0;
}
void check(bool ok,const char* name){if(!ok){puts(name);exit(1);}}
'''+callback+r'''
int main(){
 const std::string version=R"({"rhodes":{"ver":1,"sec_ver":1,"pop_required":false}})";
 esp_ble_gatts_cb_param_t p={{10,0,1,1}};
 attribute=version;config.gatt_mtu=23;
 transport_simple_ble_read(0,0,&p);
 check(received==version.substr(0,22),"First fragment mismatch");
 // Abort before read-blob, disconnect, reconnect and negotiate a larger MTU.
 config.gatt_mtu=517;p.read.conn_id=2;
 transport_simple_ble_read(0,0,&p);
 check(received==version,"BUG: interrupted prior read truncates next version response");
 // New characteristic and binary encrypted payload, including embedded NULs.
 attribute=std::string(512,'x');attribute[19]=0;p.read.handle=20;config.gatt_mtu=23;
 std::string joined;
 for(int offset=0;offset<512;offset+=22){
  p.read.offset=offset;transport_simple_ble_read(0,0,&p);
  check(sentStatus==0,"Fragment rejected");joined+=received;
 }
 check(joined==attribute,"Long binary reply mismatch");
 // Re-reading an offset is idempotent; end-of-value is a valid empty fragment.
 p.read.offset=22;transport_simple_ble_read(0,0,&p);auto saved=received;
 transport_simple_ble_read(0,0,&p);check(received==saved,"Duplicate read changed data");
 p.read.offset=512;transport_simple_ble_read(0,0,&p);check(received.empty()&&sentStatus==0,"End read failed");
 p.read.offset=513;transport_simple_ble_read(0,0,&p);check(sentStatus==7,"Bad offset accepted");
 p.read.offset=0;attribute="new";transport_simple_ble_read(0,0,&p);
 check(received=="new"&&sentStatus==0,"New response inherited old state");
 puts("PASS: interrupted/reconnected, long, repeated, terminal and invalid-offset ATT reads");
}
'''
out = root / 'test/http_preview'
out.mkdir(exist_ok=True)
cpp, exe = out / 'ble-read-harness.cpp', out / 'ble-read-test.exe'
cpp.write_text(code, encoding='utf-8')
subprocess.run(['cl', '/nologo', '/EHsc', '/std:c++17', '/utf-8',
                f'/Fe:{exe}', f'/Fo:{out / "ble-read-test.obj"}', str(cpp),
                '/link', '/MANIFEST:EMBED', "/MANIFESTUAC:level='asInvoker' uiAccess='false'"], check=True)
subprocess.run([str(exe)], check=True)
