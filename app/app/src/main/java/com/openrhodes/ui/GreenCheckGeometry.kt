package com.openrhodes.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import kotlin.math.sqrt

internal object GreenMarkStyle {const val RING=3f;const val TICK=3.6f;const val TICK_Y_OFFSET=1.5f}

/** 连接完成和命名确认共用的几何勾。坐标以 40dp 画布为基准。 */
internal fun DrawScope.drawGreenTick(progress:Float,color:Color,alpha:Float=1f) {
    if(progress<=0||alpha<=0)return
    val unit=size.width/40
    val y=GreenMarkStyle.TICK_Y_OFFSET
    val a=Offset(12*unit,(20+y)*unit);val b=Offset(17*unit,(25+y)*unit);val c=Offset(28*unit,(13+y)*unit)
    val split=sqrt(50f)/(sqrt(50f)+sqrt(265f))
    val path=Path().apply {
        moveTo(a.x,a.y)
        if(progress<split){val p=a+(b-a)*(progress/split);lineTo(p.x,p.y)}
        else {lineTo(b.x,b.y);val p=b+(c-b)*((progress-split)/(1-split)).coerceAtMost(1f);lineTo(p.x,p.y)}
    }
    drawPath(path,color.copy(alpha=alpha),style=Stroke(GreenMarkStyle.TICK*unit,cap=StrokeCap.Round,join=StrokeJoin.Round))
}
