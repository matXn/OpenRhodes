package com.openrhodes

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.openrhodes.ui.RhodesRoot
import java.io.File
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = RhodesApp::class, qualifiers = "w393dp-h852dp-night-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeLogoRefreshTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test(timeout = 60000)
    fun repeatedLogoClicksJoinTheCurrentRefreshAndCanRefreshAgainAfterCompletion() = withHome { app, _ ->
        val logo = compose.onNodeWithContentDescription("刷新灯具状态").assertHasClickAction()
        val before = app.discoveryRevision.value
        val click = requireNotNull(logo.fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        // 同一事件批次确保刷新尚未完成，覆盖点击期间真实重叠的请求。
        compose.runOnIdle { repeat(3) { click() } }
        compose.runOnIdle { assertEquals("重复点击只发起一次刷新", before + 1, app.discoveryRevision.value) }
        compose.mainClock.advanceTimeBy(80)
        compose.waitForIdle()
        logo.performClick()
        compose.runOnIdle { assertEquals("完成后允许再次刷新", before + 2, app.discoveryRevision.value) }
    }

    @Test(timeout = 60000)
    fun pressingLogoWarmsItWithoutRefreshingOrMovingTheTitle() = withHome { app, root ->
        val logo = compose.onNodeWithContentDescription("刷新灯具状态")
        val bounds = logo.fetchSemanticsNode().boundsInRoot
        val before = app.discoveryRevision.value
        val idle = warmPixels(root, bounds, "rest")
        logo.performTouchInput { down(center); moveBy(androidx.compose.ui.geometry.Offset(1f, 1f)) }
        compose.mainClock.advanceTimeBy(120)
        compose.waitForIdle()
        val pressed = warmPixels(root, bounds, "pressed")
        assertTrue("按住字标应出现柔和暖光", pressed > idle + 100)
        assertEquals("按住或轻微移动不刷新", before, app.discoveryRevision.value)
        assertEquals("按压反馈不改变字标布局", bounds, logo.fetchSemanticsNode().boundsInRoot)
        logo.performTouchInput { cancel() }
        compose.mainClock.advanceTimeBy(240)
        compose.waitForIdle()
        assertEquals("取消后暖光收回", idle, warmPixels(root, bounds, "released"))
        assertEquals("取消手势不刷新", before, app.discoveryRevision.value)
    }

    @Test(timeout = 60000)
    fun logoRefreshAndPullShareOneRequest() = withHome { app, _ ->
        val before = app.discoveryRevision.value
        compose.onNodeWithContentDescription("刷新灯具状态").performClick()
        val scrolling = compose.onNode(hasScrollAction())
        scrolling.performTouchInput {
            swipe(androidx.compose.ui.geometry.Offset(centerX, 120f),
                androidx.compose.ui.geometry.Offset(centerX, 800f), 500)
        }
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        assertEquals("点击刷新期间的下拉合并到同一次请求", before + 1, app.discoveryRevision.value)
        scrolling.performTouchInput {
            swipe(androidx.compose.ui.geometry.Offset(centerX, 120f),
                androidx.compose.ui.geometry.Offset(centerX, 800f), 500)
        }
        compose.mainClock.advanceTimeBy(80)
        compose.waitForIdle()
        assertEquals("刷新结束后的下拉仍可触发刷新", before + 2, app.discoveryRevision.value)
    }

    private fun withHome(block: (RhodesApp, View) -> Unit) {
        ShadowChoreographer.setPaused(false)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        compose.mainClock.autoAdvance = false
        val app = RuntimeEnvironment.getApplication() as RhodesApp
        runBlocking { withTimeout(5000) { app.awaitReady() } }
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            controller.get().setContent { RhodesRoot(app, MutableStateFlow(null)) }
            val root = controller.get().window.decorView
            root.measure(View.MeasureSpec.makeMeasureSpec(786, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1704, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 786, 1704)
            ShadowLooper.idleMainLooper(32, TimeUnit.MILLISECONDS)
            compose.mainClock.advanceTimeBy(32)
            compose.waitForIdle()
            block(app, root)
        } finally {
            controller.pause().stop().destroy()
            app.scope.cancel()
        }
    }

    private fun warmPixels(root: View, bounds: Rect, name: String): Int {
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val left = bounds.left.toInt().coerceAtLeast(0)
        val top = bounds.top.toInt().coerceAtLeast(0)
        val right = bounds.right.toInt().coerceAtMost(bitmap.width)
        val bottom = bounds.bottom.toInt().coerceAtMost(bitmap.height)
        var count = 0
        for (y in top until bottom) for (x in left until right) {
            val pixel = bitmap.getPixel(x, y)
            if (android.graphics.Color.red(pixel) > 24 &&
                android.graphics.Color.red(pixel) - android.graphics.Color.blue(pixel) >= 8) count++
        }
        val preview = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
        val file = File("build/reports/visual-review/home-logo-$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { preview.compress(Bitmap.CompressFormat.PNG, 100, it) }
        preview.recycle()
        bitmap.recycle()
        return count
    }
}
