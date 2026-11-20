package com.openrhodes

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import com.openrhodes.domain.*
import com.openrhodes.ui.*
import java.io.File
import java.time.Duration
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowChoreographer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ModeSelectorVisualTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test(timeout = 60000)
    fun interruptedSwitchKeepsOneLightAndCorrectSelection() = render(393, 1f)

    @Test(timeout = 60000)
    fun compactWindowWithLargeFont() = render(320, 1.3f)

    private fun render(width: Int, fontScale: Float) {
        RuntimeEnvironment.setQualifiers("w${width}dp-h852dp-xhdpi")
        RuntimeEnvironment.setFontScale(fontScale)
        compose.mainClock.autoAdvance = false
        ShadowChoreographer.setPaused(false)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val lamp = Lamp("preview", "Rhodes Light", null, emptyList())
        val session = LampSession(owner, { lamp }, object : LampTransport {
            override suspend fun read(endpoint: Endpoint): LampState = error("预览不连接灯具")
            override suspend fun write(endpoint: Endpoint, command: Command, before: LampState): LampState =
                error("预览不发送命令")
        })
        var breathing by mutableStateOf(false)
        var enabled by mutableStateOf(true)
        val changes = mutableListOf<Boolean>()
        try {
            controller.get().setContent {
                RhodesTheme {
                    Surface(Modifier.fillMaxSize(), color = Color.Black) {
                        val actual = LampState(
                            true, 56, breathing,
                            sampledAt = android.os.SystemClock.elapsedRealtime(),
                        )
                        val editor = remember { mutableStateOf(BreathEdit(actual)) }
                        Column(Modifier.fillMaxSize()) {
                            LampToolbar("RhodesLight", null, {}, {}, largeTitle = true)
                            Column(Modifier.padding(horizontal = 20.dp)) {
                                Spacer(Modifier.height(16.dp))
                                LampCard(actual, true, {}, {})
                                Spacer(Modifier.height(24.dp))
                                LightModeSelector(breathing, enabled) {
                                    changes += it
                                    breathing = it
                                }
                                if (breathing) BreathControls(session, actual, editor)
                            }
                        }
                    }
                }
            }
            val root = controller.get().window.decorView
            root.measure(
                View.MeasureSpec.makeMeasureSpec(width * 2, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(852 * 2, View.MeasureSpec.EXACTLY),
            )
            root.layout(0, 0, width * 2, 852 * 2)
            compose.mainClock.advanceTimeBy(400)
            compose.waitForIdle()
            fun capture(mode: String): Int {
                var glowWidth = 0
                val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                root.draw(Canvas(bitmap))
                val file = File("build/reports/visual-review/acrylic-$width-$mode.png")
                file.parentFile!!.mkdirs()
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                if (mode == "breath" || mode == "pressed" || mode == "released") {
                    val first = compose.onNodeWithTag("parameter-周期").fetchSemanticsNode().boundsInRoot
                    val last = compose.onNodeWithTag("parameter-曲线").fetchSemanticsNode().boundsInRoot
                    val top = first.top.toInt().coerceAtLeast(0)
                    val bottom = last.bottom.toInt().coerceAtMost(bitmap.height)
                    // 只量轨道中暖色光带的宽度，排除轨道和两侧文字。
                    val y = first.center.y.toInt().coerceIn(0, bitmap.height - 1)
                    glowWidth = (first.left.toInt().coerceAtLeast(0) until
                        first.right.toInt().coerceAtMost(bitmap.width)).count { x ->
                        val pixel = bitmap.getPixel(x, y)
                        android.graphics.Color.red(pixel) > 48 &&
                            android.graphics.Color.red(pixel) - android.graphics.Color.blue(pixel) > 8
                    }
                    val controls = Bitmap.createBitmap(bitmap, 0, top, bitmap.width, bottom - top)
                    File("build/reports/visual-review/sliders-$width-$mode.png").outputStream().use {
                        controls.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    controls.recycle()
                }
                bitmap.recycle()
                return glowWidth
            }
            capture("steady")
            val firstLight = compose.onNodeWithTag("mode-light").fetchSemanticsNode().boundsInRoot
            compose.onNodeWithText("常亮").performClick()
            assertTrue(changes.isEmpty())
            compose.onNodeWithText("呼吸").performClick()
            compose.mainClock.advanceTimeBy(48)
            compose.onAllNodesWithTag("mode-light").assertCountEquals(1)
            compose.onNodeWithText("常亮").performClick()
            compose.mainClock.advanceTimeBy(48)
            compose.onNodeWithText("呼吸").performClick()
            compose.mainClock.advanceTimeBy(400)
            compose.waitForIdle()
            compose.onNodeWithText("呼吸").assertIsSelected()
            compose.onNodeWithText("常亮").assertIsNotSelected()
            compose.onAllNodesWithTag("mode-light").assertCountEquals(1)
            val finalLight = compose.onNodeWithTag("mode-light").fetchSemanticsNode().boundsInRoot
            assertEquals("切换模式时状态圆应固定在左侧", firstLight, finalLight)
            val capsule = compose.onNodeWithTag("mode-capsule").fetchSemanticsNode().boundsInRoot
            assertTrue("状态圆与模式胶囊分开", finalLight.right < capsule.left)
            assertEquals(finalLight.center.y, capsule.center.y, 1f)
            compose.onNodeWithContentDescription("当前模式：呼吸").assertExists()
            assertEquals(listOf(true, false, true), changes)
            for (label in listOf("周期", "最暗", "曲线")) {
                compose.onNodeWithText(label).performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
                    val layouts = mutableListOf<TextLayoutResult>()
                    it(layouts)
                    assertEquals("参数标签应保持单行", 1, layouts.single().lineCount)
                }
            }
            val restingGlowWidth = capture("breath")
            val slider = compose.onNodeWithTag("parameter-周期")
            val restingBounds = slider.fetchSemanticsNode().boundsInRoot
            slider.performTouchInput { down(center) }
            compose.mainClock.advanceTimeBy(200)
            compose.waitForIdle()
            assertEquals("按压展开不应改变滑条布局或触摸范围", restingBounds,
                slider.fetchSemanticsNode().boundsInRoot)
            val pressedGlowWidth = capture("pressed")
            assertTrue("静止按住应明显展开光晕", pressedGlowWidth > restingGlowWidth * 1.5f)
            slider.performTouchInput { cancel() }
            compose.mainClock.advanceTimeBy(300)
            compose.waitForIdle()
            val releasedGlowWidth = capture("released")
            assertEquals("取消或松手后光晕应收回", restingGlowWidth, releasedGlowWidth)
            assertEquals(restingBounds, slider.fetchSemanticsNode().boundsInRoot)
            compose.runOnIdle { enabled = false }
            compose.mainClock.advanceTimeBy(32)
            compose.waitForIdle()
            compose.onNodeWithText("常亮").assertIsNotEnabled().performClick()
            assertEquals(listOf(true, false, true), changes)
        } finally {
            controller.pause().stop().destroy()
            owner.cancel()
            RuntimeEnvironment.setFontScale(1f)
        }
    }
}
