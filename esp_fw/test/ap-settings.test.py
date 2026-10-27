"""Fault-injection test of the actual AP visibility setter, compiled with MSVC."""
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
source = (root / 'src/http_control.h').read_text(encoding='utf-8-sig')
start = source.index('bool setSetupApHidden(')
brace = source.index('{', start)
depth, end = 1, brace + 1
while depth:
    depth += (source[end] == '{') - (source[end] == '}')
    end += 1
setter = source[start:end]
code = r'''
#include <cassert>
#include <cstdio>
#include <string>
using String=std::string;
constexpr int ESP_OK=0, WIFI_IF_AP=1;
struct wifi_config_t { struct { int ssid_hidden=0; int channel=6; int key=123; } ap; } current;
bool readOk=true, beginOk=true, writeOk=true, persisted=false, setupApHidden=false;
int failSetAt=0, sets=0, writes=0, ends=0;
int esp_wifi_get_config(int,wifi_config_t* out){if(!readOk)return -1;*out=current;return ESP_OK;}
int esp_wifi_set_config(int,wifi_config_t* in){if(++sets==failSetAt)return -1;current=*in;return ESP_OK;}
struct {
 bool begin(const char*,bool){return beginOk;}
 size_t putBool(const char*,bool value){writes++;if(!writeOk)return 0;persisted=value;return sizeof(bool);}
 void end(){ends++;}
} networkPrefs;
'''
code += setter
code += r'''
void reset(){current={};readOk=beginOk=writeOk=true;persisted=setupApHidden=false;
 failSetAt=sets=writes=ends=0;}
int main(){String error;
 reset();assert(setSetupApHidden(true,error));
 assert(current.ap.ssid_hidden==1&&setupApHidden&&persisted);
 assert(current.ap.channel==6&&current.ap.key==123); // No channel/key reset.
 assert(setSetupApHidden(true,error));assert(sets==1&&writes==1); // No flash wear on retries.
 assert(setSetupApHidden(false,error));assert(!setupApHidden&&!persisted&&!current.ap.ssid_hidden);
 reset();readOk=false;assert(!setSetupApHidden(true,error));assert(sets==0&&writes==0);
 reset();failSetAt=1;assert(!setSetupApHidden(true,error));assert(!setupApHidden&&writes==0);
 reset();writeOk=false;assert(!setSetupApHidden(true,error));
 assert(!setupApHidden&&!persisted&&!current.ap.ssid_hidden&&sets==2&&ends==1);
 reset();beginOk=false;assert(!setSetupApHidden(true,error));
 assert(!setupApHidden&&!current.ap.ssid_hidden&&sets==2&&writes==0);
 reset();writeOk=false;failSetAt=2;assert(!setSetupApHidden(true,error));
 assert(setupApHidden&&current.ap.ssid_hidden&&!persisted); // Report actual state if rollback fails.
 puts("PASS: hide/unhide, persistence, idempotency, preserved AP config, read/apply/NVS failures and rollback");
}
'''
out = root / 'test/http_preview'
out.mkdir(parents=True, exist_ok=True)
cpp = out / 'ap-settings.cpp'
cpp.write_text(code, encoding='utf-8')
exe = out / 'ap-settings.exe'
subprocess.run(['cl', '/nologo', '/EHsc', '/std:c++17', '/utf-8',
                '/Fe:' + str(exe), '/Fo:' + str(out / 'ap-settings.obj'), str(cpp),
                '/link', '/MANIFEST:EMBED', "/MANIFESTUAC:level='asInvoker' uiAccess='false'"], check=True)
subprocess.run([str(exe)], check=True)
