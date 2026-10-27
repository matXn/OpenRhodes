package com.openrhodes.ui

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.openrhodes.R

// 三种开放字体按角色打包，中文字符交由系统回退。
internal val DeviceTitleFont = FontFamily(Font(R.font.manrope_light, FontWeight.Light))
internal val AddressFont = FontFamily(Font(R.font.jost_light, FontWeight.Light))
internal val BrightnessFont = FontFamily(Font(R.font.outfit_extralight, FontWeight.ExtraLight))
