package com.openrhodes.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import com.openrhodes.domain.AddSuccessTiming
import kotlin.math.roundToInt

/** 固定空间噪声：擦除和显现使用同一条扫描前沿，已完成部分保持稳定。 */
internal fun Modifier.particleScan(progress:Float,inverse:Boolean=false):Modifier=drawWithContent {
    val p=progress.coerceIn(0f,1f)
    if((p==1f&&!inverse)||(p==0f&&inverse)) drawContent()
    else if(!((p==0f&&!inverse)||(p==1f&&inverse))) {
        val mask=android.graphics.Bitmap.createBitmap(size.width.toInt().coerceAtLeast(1),size.height.toInt().coerceAtLeast(1),android.graphics.Bitmap.Config.ARGB_8888)
        val mc=android.graphics.Canvas(mask)
        val ink=android.graphics.Paint().apply {color=android.graphics.Color.WHITE}
        val cell=1.6.dp.toPx()
        for(row in 0..(size.height/cell).toInt()) for(col in 0..(size.width/cell).toInt()) {
            val noise=((col*73+row*151+col*row*17)%101)/100f
            val a=AddSuccessTiming.particleAlpha(p,col*cell/size.width.coerceAtLeast(1f),noise)
            ink.alpha=(255*(if(inverse)1-a else a)).roundToInt()
            mc.drawRect(col*cell,row*cell,(col+1)*cell+.1f,(row+1)*cell+.1f,ink)
        }
        val canvas=drawContext.canvas
        canvas.saveLayer(Rect(Offset.Zero,size),Paint());drawContent()
        val blend=android.graphics.Paint().apply {xfermode=android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)}
        canvas.nativeCanvas.drawBitmap(mask,0f,0f,blend);canvas.restore()
    }
}

/** 界面淡出与原生离屏渲染使用一致的透明图层。 */
internal fun Modifier.fadeContent(alpha:Float):Modifier=drawWithContent {
    if(alpha>=1f)drawContent() else if(alpha>0f) {
        val canvas=drawContext.canvas
        canvas.saveLayer(Rect(-size.width,-size.height,size.width*2,size.height*2),Paint().apply {this.alpha=alpha})
        drawContent();canvas.restore()
    }
}
