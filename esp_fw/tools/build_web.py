from pathlib import Path
import gzip,base64
root=Path(__file__).resolve().parents[1]
# 标题字体子集（tools/make_title_font.py 生成），内嵌为 base64
OUTFIT=base64.b64encode((root/'web/fonts/outfit-title.woff').read_bytes()).decode()
page=(root/'web/index.html').read_text(encoding='utf-8').replace('/*CONTROLLER*/',(root/'web/control.js').read_text(encoding='utf-8')).replace('/*OUTFIT_WOFF*/',OUTFIT)
assert ')rhodes"' not in page
(root/'src/control_page.h').write_text('#pragma once\nconst char CONTROL_PAGE[] PROGMEM = R"rhodes('+page+')rhodes";\n',encoding='utf-8')
splash=(root/'web/splash.html').read_bytes()
packed=gzip.compress(splash,compresslevel=9,mtime=0)
(root/'src/splash_asset.h').write_text('#pragma once\nconst uint8_t SPLASH_GZ[] PROGMEM = {\n'+','.join(str(b) for b in packed)+'\n};\n',encoding='utf-8')
(root/'src/splash.html').write_bytes(splash)
preview=root/'test/http_preview';preview.mkdir(parents=True,exist_ok=True)
(preview/'index.html').write_text(page,encoding='utf-8');(preview/'splash.html').write_bytes(splash)
print('Page',len(page.encode()),'bytes; splash gzip',len(packed),'bytes')

portal=(root/'web/portal.html').read_text(encoding='utf-8')
(root/'src/portal_page.h').write_text('#pragma once\nconst char PORTAL_PAGE[] PROGMEM=R"portal('+portal+')portal";\n',encoding='utf-8')

setup=(root/'web/setup.html').read_text(encoding='utf-8').replace('/*SETUP*/',(root/'web/setup.js').read_text(encoding='utf-8')).replace('/*OUTFIT_WOFF*/',OUTFIT)
assert ')setup"' not in setup
(root/'src/setup_page.h').write_text('#pragma once\nconst char SETUP_PAGE[] PROGMEM = R"setup('+setup+')setup";\n',encoding='utf-8')
(preview/'setup.html').write_text(setup,encoding='utf-8')
print('Setup',len(setup.encode()),'bytes')
