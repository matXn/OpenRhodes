package com.openrhodes

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
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
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w360dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AddSuccessVisualTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun prepare(fontScale: Float = 1f) {
        RuntimeEnvironment.setFontScale(fontScale)
        ShadowChoreographer.setPaused(false)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        compose.mainClock.autoAdvance = false
    }

    private fun capture(root: View, height: Int, name: String): Bitmap {
        val image = Bitmap.createBitmap(720, height, Bitmap.Config.ARGB_8888)
        repeat(3) {
            ShadowLooper.idleMainLooper(32, TimeUnit.MILLISECONDS)
            root.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 720, height)
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            root.draw(Canvas(image))
        }
        val file = File("build/reports/selection-v4/android/" + name + ".png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return image
    }

    @Test(timeout = 240000) fun framesPreserveStateAndFitLargeFont() = renderReview(1280)

    @Test(timeout = 60000) fun connectionHandoffKeepsTheNameAndTransferKeepsPanelStill() = verifyConnectionHandoff("Rhodes Light")

    @Test(timeout = 60000) fun twoLineNameKeepsTheSameNodeAcrossConnectionAndSuccess() = verifyConnectionHandoff("RhodesLight-EC2F3C")

    private fun verifyConnectionHandoff(displayName: String) {
        prepare()
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        var saved by mutableStateOf(false)
        var showing by mutableStateOf(true)
        var time by mutableFloatStateOf(AddSuccessTiming.SAVED_START_MS / AddSuccessTiming.DURATION)
        val candidate=SetupCandidate("handoff",displayName,null,null,null)
        val identity=ProvisionIdentityState().apply { select(candidate) }
        val state=SetupUi(phase=SetupPhase.DONE,candidates=listOf(candidate),
            connection=ConnectionProgress(1,ConnectionRoute.BLE,percent=100))
        try {
            controller.get().setContent { RhodesTheme { SharedAddIdentity {
                if(showing) Surface(Modifier.fillMaxSize(), color = Color.Black) {
                    if (!saved) ProvisionContainer(true, {}) { ProvisionIdentityScene(state,identity,select={}) {} }
                    else AddSuccessFrame(LampState(true, 35), displayName, Rect(54f, 232f, 666f, 692f), time)
                }
            } } }
            compose.mainClock.advanceTimeBy(1600)
            capture(controller.get().window.decorView, 1280, "connection-before-handoff").recycle()
            val originalName=compose.onNodeWithTag("provision-name-handoff").fetchSemanticsNode().boundsInRoot.center
            val originalNode=compose.onNodeWithTag("provision-name-handoff").fetchSemanticsNode().id
            compose.onAllNodes(isDialog()).assertCountEquals(0)
            assertEquals(1,identity.completedAttempt)
            compose.onNodeWithContentDescription("确认名称并添加").assertDoesNotExist()
            compose.runOnIdle { saved = true }
            val handoffImage=capture(controller.get().window.decorView, 1280, "connection-after-handoff")
            var ink=0
            for(y in 500 until 780) for(x in 100 until 620) {
                if(android.graphics.Color.red(handoffImage.getPixel(x,y))>180) ink++
            }
            assertTrue("交接后文字必须实际绘制，不能只有语义节点", ink>300)
            handoffImage.recycle()
            val name = compose.onNodeWithTag("add-success-name").fetchSemanticsNode().boundsInRoot.center
            compose.onAllNodes(hasTestTag("provision-name-handoff") or hasTestTag("add-success-name")).assertCountEquals(1)
            assertEquals("等待与成功出场必须复用同一个文字节点", originalNode,
                compose.onNodeWithTag("add-success-name").fetchSemanticsNode().id)
            assertEquals(originalName.x, name.x, 1f)
            assertEquals(originalName.y, name.y, 1f)
            compose.onNodeWithTag("add-success-check").assertDoesNotExist()
            var start: Rect? = null
            var panelId: Int? = null
            var previousTop = Float.POSITIVE_INFINITY
            for (ms in listOf(5200f, 5240f, 5280f, 5320f, 5360f, 5400f, 5440f, 5480f, 5520f, 5560f, 5580f)) {
                compose.runOnIdle { time = ms / AddSuccessTiming.DURATION }
                val image = capture(controller.get().window.decorView, 1280, "panel-slide-${ms.toInt()}")
                val node = compose.onNodeWithTag("add-success-panel").fetchSemanticsNode()
                val bounds = node.boundsInRoot
                if (start == null) { start = bounds; panelId = node.id }
                assertEquals("全程复用同一灯板", panelId, node.id)
                assertEquals(start!!.width, bounds.width, 1f)
                assertEquals(start!!.height, bounds.height, 1f)
                assertTrue("上滑不能反向或越过终点", bounds.top <= previousTop && bounds.top >= 232f)
                if (ms > 5200f && ms < 5560f) {
                    assertTrue("上滑必须经过中间位置", bounds.top < start!!.top && bounds.top > 232f)
                }
                val pixel = image.getPixel(bounds.center.x.toInt(), bounds.center.y.toInt())
                assertTrue("上滑时灯板不能淡没", android.graphics.Color.red(pixel) > 60)
                if (ms == 5580f) assertEquals(232f, bounds.top, 1f)
                previousTop = bounds.top
                image.recycle()
            }
            var settled: Rect? = null
            for (ms in listOf(5580f, 5700f, 6100f, 6380f)) {
                compose.runOnIdle { time = ms / AddSuccessTiming.DURATION }
                capture(controller.get().window.decorView, 1280, "fixed-panel-${ms.toInt()}").recycle()
                val panel = compose.onNodeWithTag("add-success-panel").fetchSemanticsNode().boundsInRoot
                if (settled == null) settled = panel else assertEquals(settled, panel)
            }
            compose.runOnIdle { showing=false }
            compose.mainClock.advanceTimeBy(32)
            compose.onNodeWithTag("add-success-name").assertDoesNotExist()
            compose.onNodeWithTag("provision-name-handoff").assertDoesNotExist()
        } finally { controller.pause().stop().destroy() }
    }

    @Test(timeout = 240000)
    @Config(qualifiers = "w360dp-h440dp-xhdpi")
    fun compactWindowKeepsSingleNameAndFixedPanelHeight() = renderReview(880)

    @Test(timeout = 60000) fun pendingLayoutDoesNotBlankOrDuplicateTheName() {
        prepare()
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        var target by mutableStateOf<Rect?>(null)
        try {
            controller.get().setContent { RhodesTheme {
                Surface(Modifier.fillMaxSize(), color = Color.Black) {
                    // 旧 awaiting/confirmed/confirmationMs 参数仍可调用，但不产生第二次确认。
                    AddSuccessFrame(LampState(true, 35), "Rhodes Light", target, AddSuccessTiming.SAVED_START_MS/AddSuccessTiming.DURATION,
                        awaiting = true, confirmed = { fail("不应出现第二次确认") }, confirmationMs = 0f)
                }
            } }
            compose.mainClock.advanceTimeBy(32)
            compose.waitForIdle()
            val image = capture(controller.get().window.decorView, 1280, "pending-target")
            compose.onAllNodesWithText("Rhodes Light", useUnmergedTree = true).assertCountEquals(1)
            val original = compose.onNodeWithTag("add-success-name", useUnmergedTree = true).fetchSemanticsNode().id
            val originalBounds=compose.onNodeWithTag("add-success-name").fetchSemanticsNode().boundsInRoot
            compose.onNodeWithTag("add-success-check").assertDoesNotExist()
            var visible = 0
            for (y in 576 until 704) for (x in 120 until 520) {
                val c = image.getPixel(x, y)
                if (android.graphics.Color.red(c) > 150 && android.graphics.Color.green(c) > 140) visible++
            }
            assertTrue("目标布局未完成时姓名仍可见", visible > 100)
            image.recycle()
            compose.runOnIdle { target = Rect(54f, 232f, 666f, 692f) }
            compose.mainClock.advanceTimeBy(64)
            compose.waitForIdle()
            capture(controller.get().window.decorView, 1280, "target-ready").recycle()
            compose.onAllNodesWithText("Rhodes Light", useUnmergedTree = true).assertCountEquals(1)
            assertEquals("布局就绪不能替换姓名节点", original,
                compose.onNodeWithTag("add-success-name", useUnmergedTree = true).fetchSemanticsNode().id)
            assertEquals("目标布局就绪不能挪动姓名",originalBounds,
                compose.onNodeWithTag("add-success-name").fetchSemanticsNode().boundsInRoot)
            compose.onNodeWithText("已添加").assertDoesNotExist()
            compose.onNodeWithContentDescription("确认名称并添加").assertDoesNotExist()
            compose.onNodeWithContentDescription("确认并进入灯具").assertDoesNotExist()
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test(timeout = 60000) fun hostFinishesOnceAfterPlaybackAndTargetChanges() {
        prepare()
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val snapshot = LampState(true, 35, hardware = HardwareId.parse("02:00:00:00:00:77"))
        var target by mutableStateOf<Rect?>(null)
        var finishes = 0
        var progress = 0f
        try {
            controller.get().setContent { RhodesTheme {
                AddSuccessTransition(AddCompletion("saved", true, snapshot), "Rhodes Light", target,
                    progress = { progress = it }, finished = { finishes++ })
            } }
            compose.mainClock.advanceTimeBy(1000)
            compose.waitForIdle()
            assertEquals(0, finishes)
            assertEquals(0f, progress, .001f)
            compose.onNodeWithTag("add-success-name").assertExists()
            compose.runOnIdle { target = Rect(54f, 232f, 666f, 692f) }
            compose.mainClock.advanceTimeBy(2200)
            compose.waitForIdle()
            val beforeResize = progress
            assertTrue(beforeResize > .2f)
            compose.runOnIdle { target = Rect(74f, 252f, 646f, 681f) }
            compose.mainClock.advanceTimeBy(64)
            compose.waitForIdle()
            assertTrue("更新布局终点不能重启动画", progress >= beforeResize)
            compose.mainClock.advanceTimeBy(5000)
            compose.waitForIdle()
            assertEquals(1, finishes)
            assertEquals(1f, progress, .001f)
            compose.mainClock.advanceTimeBy(1000)
            compose.waitForIdle()
            assertEquals(1, finishes)
        } finally { controller.pause().stop().destroy() }
    }

    @Test(timeout = 60000) fun backgroundFinishesWithoutTargetOrAnotherFrame() {
        prepare()
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        var finishes = 0
        var progress = 0f
        try {
            val snapshot = LampState(false, 35, hardware = HardwareId.parse("02:00:00:00:00:77"))
            controller.get().setContent { RhodesTheme {
                AddSuccessTransition(AddCompletion("saved", true, snapshot), "Rhodes Light", null,
                    progress = { progress = it }, finished = { finishes++ })
            } }
            compose.mainClock.advanceTimeBy(32)
            compose.waitForIdle()
            assertEquals(0, finishes)
            controller.pause().stop()
            assertEquals("后台完成不应等待帧时钟或 target", 1, finishes)
            assertEquals(1f, progress, .001f)
            controller.start().resume().visible()
            compose.mainClock.advanceTimeBy(64)
            compose.waitForIdle()
            assertEquals(1, finishes)
        } finally { controller.pause().stop().destroy() }
    }

    @Test(timeout = 60000) fun disabledAnimationsFinishWithoutTarget() {
        prepare()
        val previousScale = ReflectionHelpers.getStaticField<Float>(ValueAnimator::class.java, "sDurationScale")
        ReflectionHelpers.setStaticField(ValueAnimator::class.java, "sDurationScale", 0f)
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        var finishes = 0
        try {
            val snapshot = LampState(true, 35, hardware = HardwareId.parse("02:00:00:00:00:77"))
            controller.get().setContent { RhodesTheme {
                AddSuccessTransition(AddCompletion("saved", true, snapshot), "Rhodes Light", null,
                    progress = {}, finished = { finishes++ })
            } }
            compose.mainClock.advanceTimeBy(64)
            compose.waitForIdle()
            assertEquals(1, finishes)
        } finally {
            controller.pause().stop().destroy()
            ReflectionHelpers.setStaticField(ValueAnimator::class.java, "sDurationScale", previousScale)
        }
    }

    private fun renderReview(pixelHeight: Int) {
        prepare(1.3f)
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val initial = LampState(true, 35, hardware = HardwareId.parse("02:00:00:00:00:77"))
        var snapshot by mutableStateOf(initial)
        var name by mutableStateOf("Rhodes Light")
        var time by mutableFloatStateOf(0f)
        var target by mutableStateOf<Rect?>(null)
        try {
            controller.get().setContent { RhodesTheme {
                Surface(Modifier.fillMaxSize(), color = Color.Black) {
                    Box(Modifier.fillMaxSize().background(Color.Black)) {
                        val heightDp = (pixelHeight / 2).dp
                        val panelHeight = 230.dp.coerceAtMost(heightDp - 210.dp)
                        val panelWidth = panelHeight * 4 / 3
                        val panelTop = 116.3333.dp.coerceAtMost(heightDp - panelHeight - 100.dp)
                        Box(Modifier.offset(y = 40.dp).graphicsLayer { alpha = AddSuccessTiming.chrome(time) }) {
                            LampToolbar("灯具", null, {}, {}, largeTitle = true)
                        }
                        Box(Modifier.align(Alignment.TopCenter).offset(y = panelTop)
                            .width(panelWidth).height(panelHeight).onGloballyPositioned { target = it.boundsInRoot() })
                        Box(Modifier.padding(horizontal = 24.dp).offset(y = panelTop + panelHeight + 25.dp)
                            .graphicsLayer { alpha = AddSuccessTiming.chrome(time) }) { LightModeSelector(snapshot.breathing, true) {} }
                        AddSuccessFrame(snapshot, name, target, time)
                    }
                }
            } }
            val times = listOf(2000f,2300f,2500f,2700f,3100f,3400f,3800f,4000f,
                4200f,4300f,4400f,4700f,5000f,5200f,5360f,5580f,6380f,6800f)
            for (scenario in listOf("on", "off", "breath", "long")) {
                snapshot = initial.copy(on = scenario != "off", breathing = scenario == "breath")
                name = if (scenario == "long") "卧室窗边的 Rhodes Light 阅读灯" else "Rhodes Light"
                for (ms in times) {
                    time = ms / AddSuccessTiming.DURATION
                    compose.mainClock.advanceTimeBy(32)
                    compose.waitForIdle()
                    capture(controller.get().window.decorView, pixelHeight,
                        pixelHeight.toString() + "-" + scenario + "-" + ms.toInt()).recycle()
                    val panel = compose.onNodeWithTag("add-success-panel", useUnmergedTree = true).fetchSemanticsNode()
                    val end = requireNotNull(target)
                    assertEquals("展开期间本地高度应保持不变", end.height, panel.boundsInRoot.height, 1f)
                    if (ms <= 3800f) assertEquals("展开前保持方形", end.height, panel.boundsInRoot.width, 1f)
                    if (ms >= 4200f) assertEquals("展开后匹配详情宽度", end.width, panel.boundsInRoot.width, 1f)
                    val description = panel.config[SemanticsProperties.StateDescription]
                    if (!snapshot.on) assertTrue(description.contains("关灯"))
                    if (snapshot.breathing) assertTrue(description.contains("呼吸"))
                    if (AddSuccessTiming.caption(time)>0f) {
                        compose.onAllNodesWithText(name, useUnmergedTree = true).assertCountEquals(1)
                        val bounds = compose.onNodeWithTag("add-success-name", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                        assertEquals("名称下移期间始终居中", 360f, bounds.center.x, 1f)
                        assertTrue("姓名不得越出屏幕", bounds.left >= 0f && bounds.right <= 720f && bounds.bottom <= pixelHeight)
                        if(ms>=3100f)assertTrue("$pixelHeight/$scenario/${ms.toInt()} 多行文字上缘必须与灯板保留间距",
                            bounds.top>=panel.boundsInRoot.bottom+47f)
                    }
                    if(AddSuccessTiming.successCaption(time)>0f) {
                        compose.onNodeWithContentDescription("已添加 $name").assertIsDisplayed()
                        val caption=compose.onNodeWithTag("add-success-name").fetchSemanticsNode().boundsInRoot
                        assertTrue("成功说明与灯板保持间距",caption.top>=panel.boundsInRoot.bottom+47f)
                        assertTrue(caption.bottom<=pixelHeight)
                    }
                    if(AddSuccessTiming.successCheckAlpha(time)>0f) {
                        compose.onNodeWithTag("add-success-check").assertIsDisplayed().assertHasNoClickAction()
                        val check=compose.onNodeWithTag("add-success-check").fetchSemanticsNode().boundsInRoot
                        val caption=compose.onNodeWithTag("add-success-name").fetchSemanticsNode().boundsInRoot
                        assertTrue("勾位于成功说明下方",check.top>=caption.bottom+31f)
                        assertEquals(panel.boundsInRoot.center.x,check.center.x,1f)
                        assertTrue(check.bottom<=pixelHeight)
                    } else compose.onNodeWithTag("add-success-check").assertDoesNotExist()
                    compose.onNodeWithContentDescription("确认名称并添加").assertDoesNotExist()
                    compose.onNodeWithContentDescription("确认并进入灯具").assertDoesNotExist()
                }
            }
            assertEquals(35, snapshot.brightness)
        } finally {
            controller.pause().stop().destroy()
            RuntimeEnvironment.setFontScale(1f)
        }
    }
}
