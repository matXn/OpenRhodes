package com.openrhodes.domain

object AddSuccessTiming {
    const val DURATION = 6800f
    // 连接圆环已经闭合并淡出，名字从等待位置接入下拉出场。
    const val SAVED_START_MS = 2000f
    const val SUCCESS_START_MS = 4200f
    const val CHECK_START_MS = SUCCESS_START_MS + 200f
    const val PANEL_RELOCATE_MS = CHECK_START_MS + ConfirmMotion.DURATION + 200f
    const val PANEL_TRAVEL_MS = 380f
    const val TRANSFER_START_MS = PANEL_RELOCATE_MS + PANEL_TRAVEL_MS
    fun ease(value: Float): Float = value.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
    fun bezier(value: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val t = value.coerceIn(0f, 1f)
        if (t == 0f || t == 1f) return t
        fun at(u: Float, a: Float, b: Float) = 3*(1-u)*(1-u)*u*a + 3*(1-u)*u*u*b + u*u*u
        var lo = 0f; var hi = 1f
        repeat(24) { val u = (lo+hi)/2; if (at(u,x1,x2)<t) lo=u else hi=u }
        return at((lo+hi)/2,y1,y2)
    }
    // selection-v4：以保存成功为零点，不包含保存前的连接或命名确认。
    fun namingChrome(p:Float)=1-ease(p*DURATION/300)
    private fun track(time:Float,start:Float,values:FloatArray):Float {
        val ms=time-start
        if(ms<=0)return values[0];if(ms>=(values.size-1)*100)return values.last()
        val i=(ms/100).toInt().coerceIn(0,values.size-2)
        fun slope(j:Int):Float {
            if(j==0)return (values[1]-values[0])/100
            if(j==values.size-1)return 0f
            val l=(values[j]-values[j-1])/100;val r=(values[j+1]-values[j])/100
            return if(l*r<=0)0f else 2*l*r/(l+r)
        }
        val t=(ms-i*100)/100;val t2=t*t;val t3=t2*t
        return (2*t3-3*t2+1)*values[i]+(t3-2*t2+t)*100*slope(i)+(-2*t3+3*t2)*values[i+1]+(t3-t2)*100*slope(i+1)
    }
    fun nameTravel(p:Float)=(track(p*DURATION,0f,floatArrayOf(230f,247f,277f,315f,349f,370f,381f))-230f)/151f
    fun nameScale(p:Float)=1+.025f*nameTravel(p)
    fun nameRecenter(p:Float)=bezier((p*DURATION-SAVED_START_MS)/300f,.22f,.61f,.36f,1f)
    fun down(p:Float)=bezier((p*DURATION-2400f)/700f,.22f,.61f,.36f,1f)
    fun prefix(p:Float)=successCaption(p)
    fun prefixErase(p:Float)=1f
    fun caption(p:Float)=IdentityFade.outgoingAlpha(p*DURATION-3600f)
    fun successCaption(p:Float)=ease((p*DURATION-SUCCESS_START_MS)/200f)*IdentityFade.outgoingAlpha(p*DURATION-PANEL_RELOCATE_MS)
    fun successCheckAlpha(p:Float)=if(p*DURATION<CHECK_START_MS)0f else IdentityFade.outgoingAlpha(p*DURATION-PANEL_RELOCATE_MS)
    fun ringScale(p:Float)=track(p*DURATION,0f,floatArrayOf(62f,63f,71f,88f,106f,116f,118f))/105f
    fun ringRight(p:Float)=(track(p*DURATION,0f,floatArrayOf(250f,250f,250.5f,252f,254f,255f,255f))-230f)/3f
    fun ringBelow(p:Float)=(track(p*DURATION,0f,floatArrayOf(1045f,1044.5f,1043.5f,1033f,1003f,975f,968f))-960f)/3f
    fun ringAlpha(p:Float)=track(p*DURATION,2000f,floatArrayOf(1f,.45f,0f))
    fun ringFill(p:Float)=.85f+.15f*ease((p*DURATION-1300f)/100f)
    fun checkReplay(p:Float)=(p*DURATION-CHECK_START_MS).coerceIn(0f,ConfirmMotion.DURATION)
    fun transfer(p:Float)=((p*DURATION-TRANSFER_START_MS)/800).coerceIn(0f,1f)
    fun expansion(p:Float)=(track(p*DURATION,3800f,floatArrayOf(690f,810f,884f,912f,920f))-690f)/230f
    // 同一灯板连续上滑，起止速度为零；到位后再显现详情文字。
    fun panelTravel(p:Float)=ease((p*DURATION-PANEL_RELOCATE_MS)/PANEL_TRAVEL_MS)
    fun arrival(p:Float)=track(p*DURATION,2700f,floatArrayOf(0f,.087f,.183f,.413f,.556f,.675f,.802f,.937f,.984f,1f))
    fun chrome(p:Float)=ease((p*DURATION-TRANSFER_START_MS)/200f)
    fun x(p:Float)=16*(1-bezier((p*DURATION-2700)/625,.22f,.70f,.24f,1f))
    fun y(p:Float)=-24*(1-bezier((p*DURATION-2700)/700,.35f,.05f,.18f,1f))
    fun z(p:Float)=-9*(1-bezier((p*DURATION-2700)/550,.16f,.85f,.30f,1f))
    fun status(p:Float)=.3f+.7f*ease((p*DURATION-2700)/700)
    fun particleAlpha(p: Float,x: Float,noise: Float) = ease((p*1.28f-.14f-x+(noise-.5f)*.16f)/.06f+.5f)
    fun leaf(p: Float, group: Int) = ease((p*DURATION-6400)/350)
}

enum class AddStage { INTRO, SUCCESS, TRANSFER, DETAIL }
data class AddPlaybackFrame(val stage:AddStage,val elapsed:Float,val confirmation:Float=ConfirmMotion.DURATION)

object ConfirmMotion {
    const val DURATION=600f
    fun ring(ms:Float)=AddSuccessTiming.bezier(ms/180,.22f,.61f,.36f,1f)
    fun tick(ms:Float)=AddSuccessTiming.bezier((ms-100)/180,.22f,.61f,.36f,1f)
    fun oldTick(ms:Float)=1-AddSuccessTiming.ease(ms/70)
    fun alpha(ms:Float)=1-AddSuccessTiming.ease((ms-460)/140)
    fun scale(ms:Float)=when {
        ms<80 -> 1-.1f*AddSuccessTiming.ease(ms/80)
        ms<300 -> .9f+.14f*AddSuccessTiming.bezier((ms-80)/220,.22f,.61f,.36f,1f)
        else -> 1+.04f*(1-AddSuccessTiming.ease((ms-300)/140))
    }
}
/** 保存后直接启动 INTRO；后台和关闭动画直接显示详情。 */
class AddPlayback(private val animations:Boolean=true) {
    var frame=if(animations)AddPlaybackFrame(AddStage.INTRO,AddSuccessTiming.SAVED_START_MS) else AddPlaybackFrame(AddStage.DETAIL,AddSuccessTiming.DURATION)
        private set
    fun advance(delta:Float) {
        if(frame.stage==AddStage.DETAIL)return
        val next=(frame.elapsed+delta.coerceAtLeast(0f)).coerceAtMost(AddSuccessTiming.DURATION)
        val stage=when {
            next>=AddSuccessTiming.DURATION -> AddStage.DETAIL
            next>=AddSuccessTiming.TRANSFER_START_MS -> AddStage.TRANSFER
            next>=AddSuccessTiming.SUCCESS_START_MS -> AddStage.SUCCESS
            else -> AddStage.INTRO
        }
        frame=AddPlaybackFrame(stage,next)
    }
    fun confirm():Boolean=false
    fun background() { frame=AddPlaybackFrame(AddStage.DETAIL,AddSuccessTiming.DURATION) }
}
