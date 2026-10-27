"""从同一份轮廓数据生成 SVG、Android vector 与共用的布局尺寸。"""
from pathlib import Path
import json

ROOT = Path(__file__).resolve().parents[1]
data = json.loads((ROOT / "artwork/wordmark-paths.json").read_text(encoding="utf-8"))
width, height = data["width"], data["height"]
svg = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}" role="img" aria-labelledby="title">',
       f'<title id="title">{data["label"]}</title>',
       '<desc>Outfit ExtraBold outlines; SIL Open Font License 1.1. Natural proportions, no embedded font.</desc>',
       '<g fill="#f4e8d1" fill-rule="nonzero">']
svg += [f'<path d="{glyph["path"]}"/>' for glyph in data["glyphs"]]
svg += ['</g>', f'<path id="underline" fill="#f4e8d1" d="M0 {data["ruleTop"]}H{width}V97H0Z"/>', '</svg>']
(ROOT / "artwork/openrhodes-wordmark.svg").write_text("\n".join(svg) + "\n", encoding="utf-8")
vector = [f'<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="{width}dp" android:height="{height}dp" android:viewportWidth="{width}" android:viewportHeight="{height}">']
vector += [f'  <path android:fillColor="#FFF4E8D1" android:fillType="nonZero" android:pathData="{glyph["path"]}"/>' for glyph in data["glyphs"]]
vector += ['</vector>']
(ROOT / "app/src/main/res/drawable/openrhodes_wordmark.xml").write_text("\n".join(vector) + "\n", encoding="utf-8")
o = data["glyphs"][0]
metrics = {"Width": width, "Height": height, "Baseline": data["baseline"], "RuleTop": data["ruleTop"], "CapCenter": data["capCenter"],
           "OWidth": o["xMax"], "OTop": o["yMin"], "OBottom": o["yMax"], "OCounterX": o["xMax"] / 2, "OCounterY": (o["yMax"] + o["yMin"]) / 2,
           "OpenEnd": data["glyphs"][3]["xMax"], "RhodesStart": data["glyphs"][4]["xMin"]}
code = ["package com.openrhodes.ui", "", "// 由 tools/build_wordmark.py 生成，避免 SVG 与原生布局尺寸漂移。", "internal object WordmarkGeometry {"]
code += [f"    const val {key} = {value:.4f}f" for key, value in metrics.items()]
code += ["}"]
(ROOT / "app/src/main/java/com/openrhodes/ui/WordmarkGeometry.kt").write_text("\n".join(code) + "\n", encoding="utf-8")
print(f'字标：{data["label"]}，视口 {width}x{height}，字距 {data["letterGap"]}，词距 {data["wordGap"]}；未使用单轴缩放。')
