"""Run actual startup/fade/calibration code with a deterministic clock and touch input.

Run from the project root using test\run-startup.cmd (MSVC environment).
Generated harness and executable stay in ignored test/http_preview/.
"""
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
source = (root / 'src/main.cpp').read_text(encoding='utf-8-sig')


def function(signature):
    start = source.index(signature)
    brace = source.index('{', start)
    depth = 1
    end = brace + 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]


setup = function('void setup()')
startup = setup[setup.index('  loadPrefs();'):setup.index('  bootRecalibrated = false;')]
fade_globals = source[source.index('bool breathingActive = false;'):source.index('// 启动渐变（手机端调节用）')]
code = r'''
#include <algorithm>
#include <cassert>
#include <cstdint>
#include <cstdio>
#include <vector>
using std::min; using std::max;
template<class T> T constrain(T v,T lo,T hi){return min(max(v,lo),hi);}
constexpr int PWM_MIN=0, PWM_MAX_DUTY=2048, REMOTE_FADE_MS=400, PWM_RES=12;
constexpr int NUM_PADS=10;
constexpr bool RAIL_POWER_GATING=false;
int TOUCH_PINS[NUM_PADS]={0,1,2,3,4,5,6,7,8,9};
bool ledOn=true, touchFilterReady=true, railEnabled=true, bootLightTracking=false;
int brightness=1024, currentOutputDuty=0;
uint32_t clockMs=0, bootLightOnMs=0, baseline[NUM_PADS]={0};
std::vector<int> output;
uint32_t firstSample=0; int samples=0;
uint32_t millis(){return clockMs;}
void delay(uint32_t ms){clockMs+=ms;}
void setPwmDuty(int value){currentOutputDuty=value;output.push_back(value);
 if(bootLightTracking && !bootLightOnMs && value>0) bootLightOnMs=millis();}
void applyLightOutput(){setPwmDuty(ledOn?brightness:0);}
void loadPrefs(){}
int railPadLevel(){return 1;}
struct {template<class...T>void printf(const char*,T...){}
template<class T>void print(T){} template<class T>void println(T){} } Serial;
uint32_t readTouchPad(int pin){
 if(!samples) firstSample=millis();
 int round=samples++/NUM_PADS;
 return 10000+pin+((round%2)?100:-100);
}
'''
import re
resolution = int(re.search(r'#define PWM_RES\s+(\d+)', source)[1])
limit = int(re.search(r'#define PWM_OUTPUT_LIMIT_PERCENT\s+(\d+)', source)[1])
code = code.replace(
    'constexpr int PWM_MIN=0, PWM_MAX_DUTY=2048, REMOTE_FADE_MS=400, PWM_RES=12;',
    f'constexpr int PWM_MIN=0, PWM_RES={resolution}, PWM_FULL_DUTY=1<<PWM_RES, '
    f'PWM_OUTPUT_LIMIT_PERCENT={limit}, '
    'PWM_MAX_DUTY=PWM_FULL_DUTY*PWM_OUTPUT_LIMIT_PERCENT/100, REMOTE_FADE_MS=400;')
duration = re.search(r'#define STARTUP_FADE_MS\s+(\d+)', source)[1]
code += f'constexpr uint32_t STARTUP_FADE_MS={duration};\n'
code += fade_globals
for signature in ('void startRemoteFade(', 'void serviceRemoteFade()',
                  'void delayWithLightOutput(', 'void calibrate()'):
    code += function(signature) + '\n'
code += r'''
void initTouchHardwareFilter(){
 for(int i=0;i<NUM_PADS;++i){delay(20);serviceRemoteFade();}
 delayWithLightOutput(20);
}
void boot(){uint32_t actualPwmHz=19000;
'''+startup+r'''
}
void reset(){clockMs=100;bootLightOnMs=0;currentOutputDuty=0;
 output.clear();samples=0;firstSample=0;remoteFadeActive=false;}
int main(){
 for(int saved: {1,20,1024,PWM_MAX_DUTY}){
  reset();ledOn=true;brightness=saved;touchFilterReady=true;boot();
  assert(currentOutputDuty==saved && !remoteFadeActive);
  assert(bootLightOnMs>100 && bootLightOnMs<100+STARTUP_FADE_MS);
  assert(firstSample>=100+STARTUP_FADE_MS+50);
  assert(samples==40*NUM_PADS && millis()==100+STARTUP_FADE_MS+50+200);
  assert(std::is_sorted(output.begin(),output.end()));
  for(int i=0;i<NUM_PADS;++i) assert(baseline[i]==10000+i);
 }
 reset();ledOn=false;boot();
 assert(currentOutputDuty==0 && bootLightOnMs==0);
 assert(millis()==100+220+50+200); // no fade delay for saved-off boot
 reset();touchFilterReady=false;calibrate();
 assert(samples==80*NUM_PADS && millis()==500);
 // Unsigned elapsed-time accounting must also survive millis rollover.
 reset();ledOn=true;brightness=1024;clockMs=0xfffffff0u;
 startRemoteFade(STARTUP_FADE_MS);delayWithLightOutput(STARTUP_FADE_MS);
 assert(!remoteFadeActive && currentOutputDuty==brightness);
 puts("Startup fade and calibration tests passed");
}
'''
out = root / 'test/http_preview'
out.mkdir(exist_ok=True)
cpp = out / 'startup-harness.cpp'
cpp.write_text(code, encoding='utf-8')
exe = out / 'startup-test.exe'
subprocess.run(['cl', '/nologo', '/EHsc', '/std:c++17', '/utf-8',
                f'/Fe:{exe}', f'/Fo:{out / "startup-test.obj"}', str(cpp),
                '/link', '/MANIFEST:EMBED',
                "/MANIFESTUAC:level='asInvoker' uiAccess='false'"], check=True)
subprocess.run([str(exe)], check=True)
