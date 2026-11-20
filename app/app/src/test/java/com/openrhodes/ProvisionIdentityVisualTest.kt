package com.openrhodes

import android.view.View
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import com.openrhodes.domain.*
import com.openrhodes.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.shadows.ShadowLooper
import java.time.Duration
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=android.app.Application::class,qualifiers="w360dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProvisionIdentityVisualTest {
    @get:Rule val compose=createEmptyComposeRule()

    @Test(timeout=90000)
    fun sameNameFadesAtFixedPositionsThenStaysStillAndReturnsOnFailure() {
        ShadowChoreographer.setPaused(false)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        compose.mainClock.autoAdvance=false
        val row=SetupCandidate("test","Rhodes Lite",null,null,null)
        val identity=ProvisionIdentityState()
        var state by mutableStateOf(SetupUi(candidates=listOf(row),searching=true))
        val controller=Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            controller.get().setContent {
                RhodesTheme { Surface(Modifier.fillMaxSize(),color=Color.Black) {
                    ProvisionIdentityScene(state,identity,select={}) { alpha ->
                        Column(Modifier.fillMaxSize().graphicsLayer {this.alpha=alpha}.padding(20.dp)) {
                            Text("添加灯具")
                            Spacer(Modifier.height(100.dp))
                            CandidateNameSlot(row,identity)
                        }
                    }
                } }
            }
            fun advance(ms:Long) {
                compose.mainClock.advanceTimeBy(ms)
                ShadowLooper.idleMainLooper(ms,TimeUnit.MILLISECONDS)
                val root=controller.get().window.decorView
                val image=Bitmap.createBitmap(720,1280,Bitmap.Config.ARGB_8888)
                repeat(2) {
                    compose.mainClock.advanceTimeByFrame()
                    ShadowLooper.idleMainLooper(16,TimeUnit.MILLISECONDS)
                    root.measure(View.MeasureSpec.makeMeasureSpec(720,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1280,View.MeasureSpec.EXACTLY))
                    root.layout(0,0,720,1280)
                    root.draw(Canvas(image))
                }
                image.recycle()
                compose.waitForIdle()
            }
            advance(80)
            compose.onAllNodesWithText(row.name).assertCountEquals(1)
            val source=compose.onNodeWithText(row.name).fetchSemanticsNode().boundsInRoot
            val originalNode=compose.onNodeWithText(row.name).fetchSemanticsNode().id
            compose.runOnIdle {
                identity.select(row)
                state=state.copy(busy=true,searching=false,connection=ConnectionProgress(1,ConnectionRoute.BLE))
            }
            advance(40)
            assertEquals("淡出期间必须停在原位置",source,compose.onNodeWithText(row.name).fetchSemanticsNode().boundsInRoot)
            advance(160)
            compose.onAllNodesWithText(row.name).assertCountEquals(1)
            val appeared=compose.onNodeWithText(row.name).fetchSemanticsNode().boundsInRoot
            advance(2300)
            val held=compose.onNodeWithText(row.name).fetchSemanticsNode().boundsInRoot
            assertEquals("淡入期间必须停在目标位置",appeared,held)
            assertEquals(originalNode,compose.onNodeWithText(row.name).fetchSemanticsNode().id)
            assertTrue("连接名称应显示在等待位置",held.top>source.top)
            advance(1400)
            assertEquals("等待时间不应使名字漂移",held,compose.onNodeWithText(row.name).fetchSemanticsNode().boundsInRoot)
            compose.runOnIdle {state=state.copy(connection=state.connection!!.reached(85,"正在验证灯具身份"))}
            advance(300)
            assertEquals("状态文案变化不应推动设备名",held,compose.onNodeWithText(row.name).fetchSemanticsNode().boundsInRoot)
            compose.runOnIdle {state=state.copy(busy=false,error="连接中断",connection=state.connection!!.stopped())}
            advance(600)
            compose.onAllNodesWithText(row.name).assertCountEquals(1)
            assertFalse(identity.active)
            val returned=compose.onNodeWithText(row.name).fetchSemanticsNode().boundsInRoot
            assertEquals(source.left,returned.left,.5f)
            assertEquals(source.top,returned.top,.5f)
        } finally {controller.pause().stop().destroy()}
    }
}
