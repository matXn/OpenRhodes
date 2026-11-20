package com.openrhodes.domain

/** 阶段权重表示工作完成度，不是耗时或剩余秒数。100% 只能由身份验证成功触发。 */
enum class ConnectionRoute { BLE, WIFI, LOCAL, BLE_CONTROL }
object ConnectionWeights {
    const val WIFI_ACCEPTED=15
    const val WIFI_ONLINE=75
    const val WIFI_READ=90
    const val CONTROL_READ=80
}
enum class BleConnectionMilestone(val percent:Int,val nextLabel:String) {
    LINK_READY(25,"正在读取蓝牙服务"),
    SERVICES_READY(45,"正在协商连接协议"),
    PROTOCOL_READY(55,"正在建立安全连接"),
    SECURE_READY(85,"正在验证灯具身份"),
}
data class ConnectionProgress(
    val attempt:Int,val route:ConnectionRoute,val percent:Int=0,
    val label:String="正在连接灯具",val failed:Boolean=false,
) {
    val complete get()=percent==100&&!failed
    fun reached(value:Int,next:String)=if(failed||complete||value<percent)this else copy(percent=value.coerceIn(0,99),label=next)
    fun verified()=if(failed)this else copy(percent=100,label="灯具已连接")
    fun stopped()=copy(failed=true,label="连接未完成")
}

/** 连接阶段只补满并淡出圆环，成功勾由添加完成画面绘制。 */
object ConnectionCompletion {
    const val FILL_MS=220f
    const val DURATION=FILL_MS+IdentityFade.OUT_MS
    fun ringAlpha(elapsed:Float)=IdentityFade.outgoingAlpha(elapsed-FILL_MS)
}
