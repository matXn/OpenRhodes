"""Build the original OpenRhodes wordmark SVG and Android vector from path data.

These geometric paths are authored for OpenRhodes, not extracted from a font.
The underline is separate so the native toolbar can animate it independently.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GLYPHS = {
    "O": (72, "M36 0C16.12 0 0 16.12 0 36C0 55.88 16.12 72 36 72C55.88 72 72 55.88 72 36C72 16.12 55.88 0 36 0Z M36 16C24.95 16 16 24.95 16 36C16 47.05 24.95 56 36 56C47.05 56 56 47.05 56 36C56 24.95 47.05 16 36 16Z"),
    "p": (53, "M0 20H16V25C20 21 25 18 31 18C45 18 53 29 53 45C53 61 45 72 31 72C25 72 20 70 16 66V90H0Z M16 45C16 54 20 59 27 59C34 59 37 54 37 45C37 36 34 31 27 31C20 31 16 36 16 45Z"),
    "e": (53, "M52 47H16C18 55 22 59 30 59C37 59 42 56 46 53L53 62C46 69 39 72 29 72C10 72 0 61 0 45C0 29 11 18 28 18C45 18 54 31 52 47Z M16 37H38C36 31 33 29 28 29C22 29 18 32 16 37Z"),
    "n": (52, "M0 20H16V25C21 20 26 18 33 18C46 18 52 27 52 40V70H36V42C36 35 34 31 28 31C21 31 16 36 16 44V70H0Z"),
    "R": (62, "M0 2H31C50 2 59 11 59 25C59 35 54 42 46 45L63 70H44L29 48H17V70H0Z M17 16V35H30C38 35 42 32 42 25C42 19 38 16 30 16Z"),
    "h": (52, "M0 0H16V25C21 20 26 18 33 18C46 18 52 27 52 40V70H36V42C36 35 34 31 28 31C21 31 16 36 16 44V70H0Z"),
    "o": (54, "M27 18C10 18 0 29 0 45C0 61 10 72 27 72C44 72 54 61 54 45C54 29 44 18 27 18Z M27 31C34 31 38 36 38 45C38 54 34 59 27 59C20 59 16 54 16 45C16 36 20 31 27 31Z"),
    "d": (54, "M38 0H54V70H38V65C34 70 29 72 23 72C9 72 0 61 0 45C0 29 9 18 23 18C29 18 34 20 38 25Z M38 45C38 36 34 31 27 31C20 31 16 36 16 45C16 54 20 59 27 59C34 59 38 54 38 45Z"),
    "s": (48, "M46 25L39 35C34 31 29 29 23 29C18 29 15 31 15 34C15 37 20 38 27 40C40 43 48 47 48 56C48 67 38 72 24 72C13 72 5 69 0 64L7 53C12 57 18 60 25 60C30 60 33 58 33 55C33 52 29 51 21 49C8 46 1 42 1 33C1 23 10 18 23 18C33 18 41 20 46 25Z"),
}
paths=[]
x=0
for letter in "OpenRhodes":
    width,path=GLYPHS[letter]
    paths.append((x,path))
    x+=width+2
width=x-2
height=100
svg=[f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="100" viewBox="0 0 {width} 100" role="img" aria-labelledby="title">',
     '<title id="title">OpenRhodes</title>',
     '<desc>Original geometric path wordmark. No embedded font or external resource.</desc>',
     '<g fill="#f4e8d1" fill-rule="evenodd" transform="translate(0 4)">']
for x,path in paths:
    svg.append(f'<path transform="translate({x} 0)" d="{path}"/>')
svg+=['</g>',f'<path id="underline" fill="#f4e8d1" d="M0 92H{width}V97H0Z"/>','</svg>']
asset=ROOT/'artwork/openrhodes-wordmark.svg'
asset.parent.mkdir(parents=True,exist_ok=True)
asset.write_text('\n'.join(svg)+'\n',encoding='utf-8')
vector=[f'<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="{width}dp" android:height="100dp" android:viewportWidth="{width}" android:viewportHeight="100">',
        '  <group android:translateY="4">']
for x,path in paths:
    vector += [f'    <group android:translateX="{x}">',f'      <path android:fillColor="#FFF4E8D1" android:fillType="evenOdd" android:pathData="{path}"/>','    </group>']
vector+=['  </group>','</vector>']
(ROOT/'app/src/main/res/drawable/openrhodes_wordmark.xml').write_text('\n'.join(vector)+'\n',encoding='utf-8')
print('Wrote original SVG and matching native vector; underline stays in the toolbar.')
