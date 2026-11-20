package com.openrhodes

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import com.openrhodes.domain.*
import com.openrhodes.ui.*
import java.io.File
import java.time.Duration
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import org.robolectric.shadows.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=android.app.Application::class,qualifiers="w360dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConnectionProgressVisualTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Test(timeout=180000) fun weightedArcWaitsAndClosesIntoTheCheck()=render(false)
    @Test(timeout=180000) fun wifiProgressReplaysTheCompleteCheckAfterFilling()=render(true)
    private fun render(wifi:Boolean) {
        ShadowChoreographer.setPaused(false);ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        compose.mainClock.autoAdvance=false
        val activity=Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        var state by mutableStateOf(ConnectionProgress(1,if(wifi)ConnectionRoute.WIFI else ConnectionRoute.BLE,label=if(wifi)"正在提交网络设置" else "正在连接灯具"))
        try {
            activity.get().setContent {RhodesTheme {
                Column(Modifier.fillMaxSize().background(Color.Black)) {
                    LampToolbar(if(wifi)"连接 Wi-Fi" else "连接灯具",null,{},null)
                    Column(Modifier.padding(20.dp)){ConnectionStatus(state)}
                }
            }}
            val folder=File(if(wifi)"build/reports/connection-replay/wifi" else "build/reports/connection-replay/ble");folder.mkdirs()
            for(i in 0 until 168) {
                if(wifi)when(i) {
                    30 -> state=state.reached(ConnectionWeights.WIFI_ACCEPTED,"正在等待灯具接入 Wi-Fi")
                    90 -> state=state.reached(ConnectionWeights.WIFI_ONLINE,"正在建立局域网连接")
                    108 -> state=state.reached(ConnectionWeights.WIFI_READ,"正在验证灯具身份")
                    120 -> state=state.verified()
                } else when(i) {
                    30 -> state=state.reached(25,"正在读取蓝牙服务")
                    48 -> state=state.reached(45,"正在协商连接协议")
                    66 -> state=state.reached(55,"正在建立安全连接")
                    96 -> state=state.reached(85,"正在验证灯具身份")
                    120 -> state=state.verified()
                }
                compose.mainClock.advanceTimeBy(32);compose.waitForIdle()
                val root=activity.get().window.decorView
                val image=Bitmap.createBitmap(720,1280,Bitmap.Config.ARGB_8888)
                repeat(2) {
                    ShadowLooper.idleMainLooper(16,TimeUnit.MILLISECONDS)
                    root.measure(View.MeasureSpec.makeMeasureSpec(720,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1280,View.MeasureSpec.EXACTLY))
                    root.layout(0,0,720,1280);root.draw(Canvas(image))
                }
                if(!wifi&&i in 97..119)assertEquals(85,state.percent)
                if(wifi&&i in 91..107)assertEquals(75,state.percent)
                assertEquals(android.graphics.Color.BLACK,image.getPixel(5,1000))
                File(folder,"frame-${i.toString().padStart(4,'0')}.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}
                image.recycle()
            }
            compose.onNode(hasProgressBarRangeInfo(androidx.compose.ui.semantics.ProgressBarRangeInfo(1f,0f..1f))).assertExists()
            compose.runOnIdle {state=ConnectionProgress(2,ConnectionRoute.BLE).reached(55,"正在建立安全连接").stopped()}
            repeat(3) {
                compose.mainClock.advanceTimeBy(32);compose.waitForIdle()
                ShadowLooper.idleMainLooper(40,TimeUnit.MILLISECONDS)
                val root=activity.get().window.decorView
                root.measure(View.MeasureSpec.makeMeasureSpec(720,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1280,View.MeasureSpec.EXACTLY))
                root.layout(0,0,720,1280)
                val failed=Bitmap.createBitmap(720,1280,Bitmap.Config.ARGB_8888);root.draw(Canvas(failed));failed.recycle()
            }
            compose.onNodeWithContentDescription("连接未完成").assertExists()
            assertEquals(55,state.percent)
        }finally{activity.pause().stop().destroy()}
    }
}
