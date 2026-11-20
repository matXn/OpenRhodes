package com.openrhodes

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
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
@Config(sdk=[34],application=android.app.Application::class,qualifiers="w320dp-h440dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConnectionNameVisualTest {
    @get:Rule val compose=createEmptyComposeRule()

    @Test(timeout=90000) fun suffixWrapsAsAUnitAndCaptionStaysBelowThePanelAtLargeFont() {
        RuntimeEnvironment.setFontScale(1.5f)
        ShadowChoreographer.setPaused(false)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        compose.mainClock.autoAdvance=false
        val controller=Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val name="RhodesLight-EC2F3C"
        var ms by mutableFloatStateOf(AddSuccessTiming.SAVED_START_MS)
        try {
            controller.get().setContent { RhodesTheme {
                Surface(Modifier.fillMaxSize(),color=Color.Black) {
                    AddSuccessFrame(LampState(true,35),name,Rect(40f,160f,600f,580f),ms/AddSuccessTiming.DURATION)
                }
            } }
            fun frame(time:Float):Rect {
                compose.runOnIdle {ms=time}
                val bitmap=Bitmap.createBitmap(640,880,Bitmap.Config.ARGB_8888)
                val root=controller.get().window.decorView
                repeat(3) {
                    compose.mainClock.advanceTimeByFrame()
                    ShadowLooper.idleMainLooper(32,TimeUnit.MILLISECONDS)
                    root.measure(View.MeasureSpec.makeMeasureSpec(640,View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(880,View.MeasureSpec.EXACTLY))
                    root.layout(0,0,640,880)
                    root.draw(Canvas(bitmap))
                }
                compose.waitForIdle()
                val output=File("build/reports/name-layout-review/suffix-${time.toInt()}.png")
                output.parentFile!!.mkdirs()
                output.outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
                bitmap.recycle()
                return compose.onNodeWithTag("add-success-name").fetchSemanticsNode().boundsInRoot
            }
            val original=frame(2000f)
            compose.onNodeWithTag("add-success-check").assertDoesNotExist()
            val originalId=compose.onNodeWithTag("add-success-name").fetchSemanticsNode().id
            val layouts=mutableListOf<TextLayoutResult>()
            compose.onNodeWithTag("add-success-name").performSemanticsAction(SemanticsActions.GetTextLayoutResult) {it(layouts)}
            val layout=layouts.single()
            assertEquals(2,layout.lineCount)
            assertEquals("EC2F3C",layout.layoutInput.text.text.substring(layout.getLineStart(1)))
            assertFalse(layout.hasVisualOverflow)
            assertEquals((layout.getLineLeft(0)+layout.getLineRight(0))/2f,
                (layout.getLineLeft(1)+layout.getLineRight(1))/2f,1f)
            assertEquals(original.top,frame(2060f).top,1f)
            val moving=frame(2500f)
            val settled=frame(3100f)
            assertTrue("下拉过程应有连续的中间位置",moving.top>original.top && moving.top<settled.top)
            assertEquals(originalId,compose.onNodeWithTag("add-success-name").fetchSemanticsNode().id)
            for(time in listOf(3100f,3400f)) {
                val bounds=frame(time)
                val panel=compose.onNodeWithTag("add-success-panel").fetchSemanticsNode().boundsInRoot
                assertTrue("文字与灯板至少相隔 24dp",bounds.top>=panel.bottom+47f)
                assertEquals("说明文字与灯板水平居中",panel.center.x,bounds.center.x,1f)
                assertTrue(bounds.left>=0f && bounds.right<=640f && bounds.bottom<=880f)
            }
            val success=frame(4700f)
            compose.onNodeWithContentDescription("已添加 $name").assertIsDisplayed()
            compose.onNodeWithTag("add-success-check").assertIsDisplayed().assertHasNoClickAction()
            val check=compose.onNodeWithTag("add-success-check").fetchSemanticsNode().boundsInRoot
            assertTrue(check.top>=success.bottom+31f && check.bottom<=880f)
        } finally {
            controller.pause().stop().destroy()
            RuntimeEnvironment.setFontScale(1f)
        }
    }
}
