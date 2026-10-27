# Open Rhodes 字标

字标轮廓来自 Outfit ExtraBold（800），以 SIL Open Font License 1.1 发布。
版权：2021 The Outfit Project Authors。
官方来源：https://github.com/google/fonts/tree/main/ofl/outfit

`wordmark-paths.json` 保存最终路径、字形自然边界、来源文件 SHA-256 和布局参数。
`python tools/build_wordmark.py` 从这一份数据生成 SVG、Android vector 和
`WordmarkGeometry.kt`。正常构建与生成不依赖网络或字体库。

- 字形只做统一比例变换，x/y 比例相同，不重新手画各个字母。
- Open 与 Rhodes 之间使用 16 个设计单位的空格。
- 单词内部按实际字形边界排版，间距仅 0.4 个设计单位，接近贴合但不故意重叠。
- SVG 与原生视口相同，字标与下划线分别绘制，以保留高光、入场和下拉刷新。
- 字体文件仅用于本地临时轮廓提取，不进入仓库或 APK；许可保留在
  `LICENSE-Outfit.txt` 与 App 的第三方说明中。
