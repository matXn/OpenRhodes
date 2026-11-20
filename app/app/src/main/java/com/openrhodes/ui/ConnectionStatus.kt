package com.openrhodes.ui

import android.animation.ValueAnimator
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openrhodes.domain.*
import kotlin.math.*

/** 亮弧只跟随已完成节点；等待中的短光段仅改变位置和透明度，不增加完成度。 */
@Composable
internal fun ConnectionStatus(progress:ConnectionProgress?,status:String?=null,compact:Boolean=false) {
    val animations=ValueAnimator.areAnimatorsEnabled()
    val fraction=(progress?.percent ?: 0)/100f
    val animated by key(progress?.attempt) {animateFloatAsState(fraction,tween(if(animations)ConnectionCompletion.FILL_MS.toInt() else 0),label="connectionProgress")}
    val shown=if(progress?.failed==true)fraction else animated
    var elapsed by remember(progress?.attempt,progress?.complete,progress?.failed){mutableFloatStateOf(0f)}
    LaunchedEffect(progress?.attempt,progress?.complete,progress?.failed,animations) {
        if(!animations||progress?.failed==true)return@LaunchedEffect
        var start=withFrameNanos {it}
        while(progress?.complete!=true||elapsed<ConnectionCompletion.DURATION)withFrameNanos {elapsed=(it-start)/1_000_000f}
    }
    val label=progress?.label ?: status ?: "正在寻找灯具"
    val color=if(progress?.failed==true)Color(0xFFD28B76) else Color(0xFF30D158)
    Row(Modifier.fillMaxWidth().heightIn(min=if(compact)32.dp else 64.dp).semantics(mergeDescendants=true) {
        contentDescription=label
        progressBarRangeInfo=if(progress==null)ProgressBarRangeInfo.Indeterminate else ProgressBarRangeInfo(fraction,0f..1f)
        stateDescription=if(progress?.failed==true)"连接未完成" else if(progress?.complete==true)"连接已验证" else "等待当前阶段完成"
    },verticalAlignment=if(compact)Alignment.Top else Alignment.CenterVertically) {
        Box(if(compact)Modifier.size(24.dp) else Modifier.size(56.dp,48.dp),contentAlignment=Alignment.Center) {
            ConnectionIndicator(progress, shown, elapsed, animations, Modifier.size(if(compact)24.dp else 40.dp))
        }
        Text(label,color=if(progress?.failed==true)color else Muted,fontSize=if(compact)16.sp else 14.sp,modifier=Modifier.weight(1f).padding(start=8.dp,top=if(compact)1.dp else 0.dp))
    }
}

@Composable
internal fun ConnectionIndicator(progress:ConnectionProgress?,shown:Float,elapsed:Float,animations:Boolean,modifier:Modifier) {
    val color=if(progress?.failed==true)Color(0xFFD28B76) else Color(0xFF30D158)
    Canvas(modifier.graphicsLayer {
        alpha=if(progress?.complete==true && animations)ConnectionCompletion.ringAlpha(elapsed) else 1f
    }.semantics { contentDescription="连接进度圆环" }) {
                val unit=size.width/40;val radius=16*unit;val center=Offset(size.width/2,size.height/2)
                val bounds=Size(radius*2,radius*2);val top=center-Offset(radius,radius)
                val stroke=Stroke(GreenMarkStyle.RING*unit,cap=StrokeCap.Round)
                drawCircle(color.copy(alpha=.14f),radius,center,style=stroke)
                if(shown>0)drawArc(color,-90f,shown*360,false,top,bounds,style=stroke)
                if(progress?.complete!=true&&progress?.failed!=true&&animations) {
                    val cycle=(elapsed%1200)/1200
                    val remaining=(360-shown*360).coerceAtLeast(0f)
                    val length=minOf(24f,remaining)
                    val angle=-90+shown*360+(remaining-length)*cycle
                    val alpha=sin(cycle*PI).toFloat().pow(2)*.75f
                    drawArc(color.copy(alpha=alpha),angle,length,false,top,bounds,style=stroke)
                }
    }
}
