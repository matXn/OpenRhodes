# 按原图整体描摹的 Open Rhodes 字标

唯一输入：用户提供的 `openrhodes-source.png`，原尺寸 1536×420。
`tools/build_wordmark.py` 直接描摹整张图的亚像素轮廓，不读取字体文件，不拆字，
不使用任何字距或词距参数重排。

- `openrhodes-from-image.svg`：保持原画布尺寸与原图位置。
- `openrhodes-wordmark.svg`：App 用版本，仅裁去外侧空白，内部所有坐标不变。
- 字标与下划线分层，App 高光与刷新条分别绘制。
- 下划线起点、位置与 17px 原图厚度也由图片提取。
- 源图哈希、轮廓数、裁剪范围与尺寸记录在 `wordmark-image-trace.json`。

本地生成工具需要 Python、Pillow、NumPy、OpenCV 和 Matplotlib；App 运行不依赖它们。
生成后自动回渲染并检查二值轮廓重合度，报告位于忽略的 build 目录。

## 界面字体

- 设备标题：Manrope Light 300，作为 Avenir Next 的风格替代。
- MAC 与地址：Jost Light 300，作为 Futura Now Headline 的风格替代。
- 亮度：Outfit ExtraLight 200。

三种字体均从 Google Fonts 官方仓库的变量字体生成静态实例，
来源、实例权重及 SHA-256 见 `interface-fonts.json`，OFL 原文随 APK 打包。
不依赖在线字体或设备本地安装；中文由系统字体回退。

首页使用左侧设备名、下方状态点与 MAC、右侧整数亮度的布局。
尚无状态时亮度显示破折号；不会把未知状态显示为零。
