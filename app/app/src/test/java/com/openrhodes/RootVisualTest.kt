package com.openrhodes

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.openrhodes.domain.*
import com.openrhodes.ui.RhodesRoot
import java.io.File
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import org.robolectric.shadows.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = RhodesApp::class, qualifiers = "w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RootVisualTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var viewportWidth = 393

    private fun frame(root: View, name: String, advanceMs: Long = 2800): Bitmap {
        compose.mainClock.advanceTimeBy(advanceMs)
        compose.waitForIdle()
        ShadowLooper.idleMainLooper(120, TimeUnit.MILLISECONDS)
        root.measure(
            View.MeasureSpec.makeMeasureSpec(viewportWidth * 2, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1704, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, viewportWidth * 2, 1704)
        ShadowLooper.idleMainLooper(120, TimeUnit.MILLISECONDS)
        val image = Bitmap.createBitmap(viewportWidth * 2, 1704, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(image))
        // The first Android traversal commits composition; then run its launch effects.
        ShadowLooper.idleMainLooper(240, TimeUnit.MILLISECONDS)
        root.measure(
            View.MeasureSpec.makeMeasureSpec(viewportWidth * 2, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1704, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, viewportWidth * 2, 1704)
        root.draw(Canvas(image))
        val file = File("build/reports/visual-review/$name.png")
        requireNotNull(file.parentFile).mkdirs()
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return image
    }

    private fun review(
        name: String,
        offline: Boolean = false,
        navigate: Boolean = false,
        width: Int = 393,
        fontScale: Float = 1f,
    ) {
        viewportWidth = width
        RuntimeEnvironment.setQualifiers("w${width}dp-h852dp-xhdpi")
        RuntimeEnvironment.setFontScale(fontScale)
        ShadowChoreographer.setPaused(false)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        compose.mainClock.autoAdvance = false
        val app = RuntimeEnvironment.getApplication() as RhodesApp
        val endpoint = requireNotNull(Endpoint.parse("192.0.2.10"))
        val hardware = requireNotNull(HardwareId.parse("02:00:00:00:00:10"))
        val lamp = Lamp("visual-fixture", name, hardware, listOf(endpoint))
        runBlocking {
            withTimeout(5000) {
                app.awaitReady()
                app.catalog.enroll(lamp)
            }
        }
        app.session(lamp.id).acceptDiscovered(endpoint, LampState(true, 56, hardware = hardware))
        if (offline) app.session(lamp.id).networkChanged()
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            val requested = MutableStateFlow<String?>(null)
            controller.get().setContent { RhodesRoot(app, requested) }
            val root = controller.get().window.decorView
            val homeImage = frame(root, "root-home")
            val heading = compose.onAllNodesWithText("OpenRhodes", useUnmergedTree = true).onFirst()
            val headingBounds = heading.fetchSemanticsNode().boundsInRoot
            val unit = headingBounds.width / 440f
            val baseline = headingBounds.top + 74f * unit
            fun isInk(x: Int, y: Int): Boolean {
                val color = homeImage.getPixel(x, y)
                return android.graphics.Color.red(color) > 150 &&
                    android.graphics.Color.green(color) > 150 &&
                    android.graphics.Color.blue(color) > 150
            }
            val ruleLeft = headingBounds.left.toInt() + 2
            val ruleRight = headingBounds.right.toInt() - 2
            val ruleY =
                ((headingBounds.top + 92f * unit).toInt()..(headingBounds.top + 97f * unit).toInt())
                    .firstOrNull { y ->
                        (ruleLeft until ruleRight).count { x -> isInk(x, y) } >
                            (ruleRight - ruleLeft) * .95
                    }
            assertNotNull("Wordmark underline was not rendered", ruleY)
            val pLeft = (headingBounds.left + 66f * .78f * unit).toInt()
            val pRight = (headingBounds.left + 82f * .78f * unit).toInt()
            val joinedColumns =
                (pLeft until pRight).count { x ->
                    ((baseline + 1).toInt()..requireNotNull(ruleY)).all { y -> isInk(x, y) }
                }
            assertTrue("Underline is detached from the p descender", joinedColumns >= 2)
            homeImage.recycle()
            assertTrue(
                "Vector title must fit the viewport",
                headingBounds.right < viewportWidth * 2,
            )
            val expectedPlusCenter = headingBounds.top + headingBounds.height * .40f
            val plusBounds =
                compose.onNodeWithContentDescription("添加灯具").fetchSemanticsNode().boundsInRoot
            assertEquals(
                "Plus is not centered on the title's letter face",
                expectedPlusCenter,
                plusBounds.center.y,
                2f,
            )
            compose.onNodeWithText("本地灯具").assertDoesNotExist()
            compose.onNodeWithText("#02:00:00:00:00:10").assertIsDisplayed()
            compose.onNodeWithText("未连接 · 点开恢复连接").assertDoesNotExist()
            compose.onNodeWithText("等待连接").assertDoesNotExist()
            compose.onNodeWithText("离线").assertDoesNotExist()
            val headingLeft =
                compose
                    .onAllNodesWithText("OpenRhodes", useUnmergedTree = true)
                    .onFirst()
                    .fetchSemanticsNode()
                    .boundsInRoot
                    .left
            val nameLeft =
                compose
                    .onNodeWithText(name, useUnmergedTree = true)
                    .fetchSemanticsNode()
                    .boundsInRoot
                    .left
            assertEquals("Home title and device name must align", headingLeft, nameLeft, 1f)
            compose.onNodeWithText("自动发现").assertDoesNotExist()
            compose.onNodeWithText("重新搜索").assertDoesNotExist()
            compose.onNodeWithContentDescription("GitHub 项目 OpenRhodes").assertIsDisplayed()
            val footerBounds =
                compose
                    .onNodeWithContentDescription("GitHub 项目 OpenRhodes")
                    .fetchSemanticsNode()
                    .boundsInRoot
            assertTrue("Footer did not stay at the viewport bottom", footerBounds.bottom >= 1660f)
            if (navigate) {
                compose.onNodeWithContentDescription("添加灯具").performClick()
                compose.mainClock.advanceTimeBy(300)
                compose.waitForIdle()
                frame(requireNotNull(ShadowDialog.getLatestDialog().window).decorView, "root-add")
                    .recycle()
                compose.onNodeWithText("自动发现附近的灯具").assertIsDisplayed()
                compose.onNodeWithText("没有找到灯具？").assertIsDisplayed()
                compose.onNodeWithText("IP 或 rhodeslight.local").assertDoesNotExist()
                compose.onNodeWithText("旧固件兼容（需连接灯具热点）").assertDoesNotExist()
                compose.onNodeWithContentDescription("返回").performClick()
                frame(root, "root-home-returned").recycle()
                compose.onNodeWithText("重新搜索").assertDoesNotExist()
            }
            compose.onNodeWithText(name).performClick()
            val main =
                frame(
                    root,
                    if (offline) "root-checking"
                    else if (navigate) "root-control" else "root-long-name",
                )
            if (navigate) {
                val cardBounds =
                    compose
                        .onNodeWithContentDescription("Rhodes Light")
                        .fetchSemanticsNode()
                        .boundsInRoot
                compose.onNodeWithContentDescription("设备设置").performClick()
                val settings = frame(root, "root-settings")
                compose.onNodeWithText("Wi-Fi 与配网").assertIsDisplayed()
                assertFalse("Settings action did not navigate", main.sameAs(settings))
                compose.onNodeWithContentDescription("返回").performClick()
                val back = frame(root, "root-returned")
                compose.onNodeWithContentDescription("设备设置").assertIsDisplayed()
                compose.onNodeWithText("重新连接").assertDoesNotExist()
                compose.onNodeWithText("56%").assertIsDisplayed()
                assertEquals(
                    footerBounds,
                    compose
                        .onNodeWithContentDescription("GitHub 项目 OpenRhodes")
                        .fetchSemanticsNode()
                        .boundsInRoot,
                )
                assertEquals(
                    cardBounds,
                    compose
                        .onNodeWithContentDescription("Rhodes Light")
                        .fetchSemanticsNode()
                        .boundsInRoot,
                )
                settings.recycle()
                back.recycle()
            }
            if (offline) {
                compose.onNodeWithText("正在确认连接…").assertDoesNotExist()
                compose.onNodeWithText("灯具暂时无法连接").assertDoesNotExist()
                compose.onNodeWithText("重新配网").assertDoesNotExist()
            }
            main.recycle()
        } finally {
            controller.pause().stop().destroy()
            RuntimeEnvironment.setFontScale(1f)
            app.scope.cancel()
        }
    }

    @Test(timeout = 60000)
    fun startupRuleRevealsLeftToRightAndHomePullStillRefreshes() {
        ShadowChoreographer.setPaused(false)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        compose.mainClock.autoAdvance = false
        val app = RuntimeEnvironment.getApplication() as RhodesApp
        runBlocking { app.awaitReady() }
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            controller.get().setContent { RhodesRoot(app, MutableStateFlow(null)) }
            val root = controller.get().window.decorView
            val first = frame(root, "underline-entry-start", 0)
            val heading = compose.onAllNodesWithText("OpenRhodes", useUnmergedTree = true).onFirst()
            val bounds = heading.fetchSemanticsNode().boundsInRoot
            val unit = bounds.width / 440f
            val y = (bounds.top + 92f * unit + 2).toInt()
            val left = bounds.left.toInt()
            val right = bounds.right.toInt()
            fun countInk(image: Bitmap) =
                (left until right).count { x ->
                    android.graphics.Color.red(image.getPixel(x, y)) > 150
                }
            val initial = countInk(first)
            val middle = frame(root, "underline-entry-middle", 180)
            val partial = countInk(middle)
            val firstPass = frame(root, "underline-entry-first-pass", 620)
            val firstPassInk = countInk(firstPass)
            val darkPass = frame(root, "underline-entry-dark-pass", 400)
            val darkFinished = frame(root, "underline-entry-dark-finished", 400)
            val finalBright = frame(root, "underline-entry-final-bright-pass", 250)
            val finished = frame(root, "underline-entry-finished", 650)
            val complete = countInk(finished)
            fun brightAt(image: Bitmap, fraction: Float) =
                android.graphics.Color.red(
                    image.getPixel(left + ((right - left) * fraction).toInt(), y)
                ) > 150
            assertTrue("Underline was visible before entry", initial < 30)
            assertTrue(
                "First reveal did not advance",
                partial > initial + 50 && partial < complete - 50,
            )
            assertTrue("First bright sweep did not finish", firstPassInk > complete * .95)
            assertFalse("Dark wipe must clear from the left", brightAt(darkPass, .25f))
            assertTrue("Dark wipe must retain its bright right end", brightAt(darkPass, .95f))
            assertTrue("Dark sweep did not finish", countInk(darkFinished) < 30)
            assertTrue("Final bright sweep must begin on the left", brightAt(finalBright, .2f))
            assertFalse(
                "Final bright sweep must still be moving right",
                brightAt(finalBright, .95f),
            )
            assertTrue(
                "Final bright sweep did not remain complete",
                complete > (right - left) * .95,
            )
            listOf(first, middle, firstPass, darkPass, darkFinished, finalBright, finished)
                .forEach { it.recycle() }
            val before = app.discoveryRevision.value
            compose.onNode(hasScrollAction()).performTouchInput {
                swipe(Offset(centerX, height * .1f), Offset(centerX, height * .75f), 350)
            }
            frame(root, "underline-after-pull").recycle()
            compose.waitUntil(5000) { app.discoveryRevision.value == before + 1 }
            compose
                .onAllNodes(
                    hasProgressBarRangeInfo(
                        androidx.compose.ui.semantics.ProgressBarRangeInfo.Indeterminate
                    )
                )
                .assertCountEquals(0)
        } finally {
            controller.pause().stop().destroy()
            app.scope.cancel()
        }
    }

    @Test(timeout = 60000)
    fun controlAndSettingsNavigation() = review("RhodesLight", navigate = true)

    @Test(timeout = 60000)
    fun longNameKeepsToolbarBounded() =
        review("床头的 RhodesLight 装饰灯 — 很长的设备名称", width = 320, fontScale = 1.3f)

    @Test(timeout = 60000)
    fun networkCheckDoesNotShowOfflineRecovery() = review("RhodesLight", offline = true)

    @Test(timeout = 60000)
    fun initialAndRepeatedNetworkAnnouncementsDoNotInvalidateAConfirmedLamp() {
        val app = RuntimeEnvironment.getApplication() as RhodesApp
        try {
            val endpoint = requireNotNull(Endpoint.parse("192.0.2.10"))
            val hardware = requireNotNull(HardwareId.parse("02:00:00:00:00:10"))
            val lamp = Lamp("network-fixture", "RhodesLight", hardware, listOf(endpoint))
            runBlocking {
                app.awaitReady()
                app.catalog.enroll(lamp)
            }
            val session = app.session(lamp.id)
            session.acceptDiscovered(endpoint, LampState(true, 56, hardware = hardware))
            assertFalse(app.observeNetwork(setOf("wifi:192.0.2.2/24")))
            assertFalse(session.observation.value.connecting)
            app.foregrounded()
            assertFalse(app.observeNetwork(setOf("wifi:192.0.2.2/24")))
            assertFalse(session.observation.value.connecting)
            assertTrue(app.observeNetwork(setOf("wifi:192.168.2.2/24")))
            assertTrue(session.observation.value.connecting)
            assertNull(session.observation.value.error)
        } finally {
            app.scope.cancel()
        }
    }
}
