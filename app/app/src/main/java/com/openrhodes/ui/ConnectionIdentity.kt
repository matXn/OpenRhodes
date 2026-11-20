package com.openrhodes.ui

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Constraints

internal val ConnectionIdentityTextStyle = TextStyle(fontSize=31.35.sp,lineHeight=38.sp,
    letterSpacing=0.sp,textAlign=TextAlign.Start)

private val deviceSuffix = Regex("^(.+[-_·]\\s*)([0-9a-fA-F]{4,12})$")

/** 原始名称只用于身份与可访问标签，显示换行不会写回设备记录。 */
internal fun measureConnectionName(
    measurer: TextMeasurer,
    name: String,
    maxWidth: Int,
    style: TextStyle = ConnectionIdentityTextStyle,
): TextLayoutResult {
    val width = maxWidth.coerceAtLeast(1)
    fun singleLine(value: String, textStyle: TextStyle) = measurer.measure(
        AnnotatedString(value), textStyle, maxLines = 1, softWrap = false)
    fun wrapped(value: String, textStyle: TextStyle) = measurer.measure(
        AnnotatedString(value), textStyle, maxLines = 2, overflow = TextOverflow.Ellipsis,
        constraints = Constraints(maxWidth = width))
    if (singleLine(name, style).size.width <= width) return wrapped(name, style)
    val suffix = deviceSuffix.matchEntire(name) ?: return wrapped(name, style)
    val prefix = suffix.groupValues[1].trimEnd()
    val identifier = suffix.groupValues[2]
    val longest = maxOf(singleLine(prefix, style).size.width, singleLine(identifier, style).size.width)
    val prefixFontSize = (style.fontSize.value * (width-1).coerceAtLeast(1) / longest)
        .coerceIn(minOf(22f, style.fontSize.value), style.fontSize.value)
    val suffixFontSize = style.fontSize.value*(width-1).coerceAtLeast(1)/singleLine(identifier,style).size.width
    val fontSize = minOf(prefixFontSize,suffixFontSize)
    val fitted = style.copy(fontSize = fontSize.sp,
        lineHeight = (style.lineHeight.value * fontSize / style.fontSize.value).sp)
    var shownPrefix = prefix
    if (singleLine(shownPrefix, fitted).size.width > width) {
        do { shownPrefix = shownPrefix.substring(0,shownPrefix.offsetByCodePoints(shownPrefix.length,-1)) }
        while (shownPrefix.isNotEmpty() && singleLine("$shownPrefix…", fitted).size.width > width)
        shownPrefix += "…"
    }
    return wrapped("$shownPrefix\n$identifier", fitted)
}

/** 两个场景使用相同的窗口坐标，名字从等待位置连续接入下拉动作。 */
internal data class ConnectionNamePose(val layout: TextLayoutResult,val bounds: Rect)

internal fun connectionWaitingName(
    measurer: TextMeasurer,
    name: String,
    safeArea: Rect,
    density: Float,
): ConnectionNamePose {
    val available=(safeArea.width-106f*density).toInt().coerceAtLeast(1)
    val layout=measureConnectionName(measurer,name,available,
        ConnectionIdentityTextStyle.copy(textAlign=TextAlign.Center))
    val left = safeArea.center.x - layout.size.width / 2f
    val centerY=safeArea.center.y
    return ConnectionNamePose(layout,Rect(left,centerY-layout.size.height/2f,
        left+layout.size.width,centerY+layout.size.height/2f))
}

/** 矢量圆环与几何勾共享添加动画时钟，无图片、字体字形或外部光晕。 */
@Composable
internal fun GreenSuccessMark(elapsedMs:Float?=null,modifier:Modifier=Modifier,fromProgress:Boolean=false,holdFinal:Boolean=false) {
    val green=Color(0xFF30D158)
    androidx.compose.foundation.Canvas(modifier.size(40.dp).graphicsLayer {
        if(elapsedMs!=null){scaleX=com.openrhodes.domain.ConfirmMotion.scale(elapsedMs);scaleY=scaleX;alpha=if(holdFinal)1f else com.openrhodes.domain.ConfirmMotion.alpha(elapsedMs)}
    }) {
        val unit=size.width/40
        val motion=com.openrhodes.domain.ConfirmMotion
        val idle=elapsedMs==null;val ms=elapsedMs ?: 0f
        val ring=if(idle)1f else motion.ring(ms)
        val radius=16f*unit
        val center=androidx.compose.ui.geometry.Offset(20*unit,20*unit)
        drawCircle(green.copy(alpha=if(idle).035f else .035f+.065f*ring),radius,center)
        val baseline=if(idle).38f else (if(fromProgress)1f else .38f)*(1-com.openrhodes.domain.AddSuccessTiming.ease(ms/100))
        drawCircle(green.copy(alpha=baseline),radius,center,style=androidx.compose.ui.graphics.drawscope.Stroke(GreenMarkStyle.RING*unit,cap=androidx.compose.ui.graphics.StrokeCap.Round))
        if(!idle&&ring>0)drawArc(green,-90f,ring*360,false,
            topLeft=center-androidx.compose.ui.geometry.Offset(radius,radius),size=androidx.compose.ui.geometry.Size(radius*2,radius*2),
            style=androidx.compose.ui.graphics.drawscope.Stroke(GreenMarkStyle.RING*unit,cap=androidx.compose.ui.graphics.StrokeCap.Round))
        drawGreenTick(1f,green,if(idle)1f else if(fromProgress)0f else motion.oldTick(ms));if(!idle)drawGreenTick(motion.tick(ms),green)

    }
}
