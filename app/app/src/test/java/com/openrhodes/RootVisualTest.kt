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
import com.openrhodes.ui.WordmarkGeometry
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
            val heading =
                compose.onAllNodesWithText("Open Rhodes", useUnmergedTree = true).onFirst()
            val headingBounds = heading.fetchSemanticsNode().boundsInRoot
            val unit = headingBounds.width / WordmarkGeometry.Width
            val baseline = headingBounds.top + WordmarkGeometry.Baseline * unit
            fun isInk(x: Int, y: Int): Boolean {
                val color = homeImage.getPixel(x, y)
                return android.graphics.Color.red(color) > 150 &&
                    android.graphics.Color.green(color) > 150 &&
                    android.graphics.Color.blue(color) > 150
            }
            // 对照原始字形的自然宽高比检查像素，不能仅检查布局容器。
            val oPixels = mutableListOf<Pair<Int, Int>>()
            for (y in
                (headingBounds.top + WordmarkGeometry.OTop * unit).toInt() until
                    (headingBounds.top + WordmarkGeometry.OBottom * unit).toInt()) {
                for (x in
                    (headingBounds.left + WordmarkGeometry.OLeft * unit).toInt() until
                        (headingBounds.left +
                                (WordmarkGeometry.OLeft + WordmarkGeometry.OWidth) * unit)
                            .toInt()) {
                    if (isInk(x, y)) oPixels.add(x to y)
                }
            }
            val oWidth = oPixels.maxOf { it.first } - oPixels.minOf { it.first } + 1
            val oHeight = oPixels.maxOf { it.second } - oPixels.minOf { it.second } + 1
            assertEquals(
                "O 应保持原始字形的自然比例",
                oHeight * WordmarkGeometry.OWidth /
                    (WordmarkGeometry.OBottom - WordmarkGeometry.OTop),
                oWidth.toFloat(),
                3f,
            )
            val ruleLeft = headingBounds.left.toInt() + 2
            val ruleRight = headingBounds.right.toInt() - 2
            val ruleY =
                ((headingBounds.top + WordmarkGeometry.RuleTop * unit).toInt()..(headingBounds.top +
                                (WordmarkGeometry.RuleTop + 5f) * unit)
                            .toInt())
                    .firstOrNull { y ->
                        (ruleLeft until ruleRight).count { x -> isInk(x, y) } >
                            (ruleRight - ruleLeft) * .95
                    }
            assertNotNull("Wordmark underline was not rendered", ruleY)
            val pLeft = headingBounds.left.toInt()
            val pRight = (headingBounds.left + WordmarkGeometry.OpenEnd * unit).toInt()
            val joinedColumns =
                (pLeft until pRight).count { x ->
                    // 分数像素缩放后，接缝允许一个抗锯齿像素，不能形成可见间隙。
                    ((baseline + 1).toInt()..requireNotNull(ruleY)).count { y ->
                        android.graphics.Color.red(homeImage.getPixel(x, y)) < 70
                    } <= 1
                }
            assertTrue("Underline is detached from the p descender", joinedColumns >= 2)
            val spaceX =
                (headingBounds.left +
                        (WordmarkGeometry.OpenEnd + WordmarkGeometry.RhodesStart) * .5f * unit)
                    .toInt()
            for (y in
                headingBounds.top.toInt() until
                    (headingBounds.top + (WordmarkGeometry.Baseline + 8f) * unit).toInt()) {
                assertEquals(
                    "Open 与 Rhodes 之间应有清晰空格",
                    android.graphics.Color.BLACK,
                    homeImage.getPixel(spaceX, y),
                )
            }
            homeImage.recycle()
            assertTrue(
                "Vector title must fit the viewport",
                headingBounds.right < viewportWidth * 2,
            )
            val expectedPlusCenter = headingBounds.top + WordmarkGeometry.CapCenter * unit
            val plusBounds =
                compose.onNodeWithContentDescription("添加灯具").fetchSemanticsNode().boundsInRoot
            assertEquals(
                "Plus is not centered on the title's letter face",
                expectedPlusCenter,
                plusBounds.center.y,
                2f,
            )
            compose.onNodeWithText("本地灯具").assertDoesNotExist()
            compose.onNodeWithText("Mac:02:00:00:00:00:10").assertIsDisplayed()
            val deviceBounds =
                compose
                    .onNodeWithText(name, useUnmergedTree = true)
                    .fetchSemanticsNode()
                    .boundsInRoot
            val macBounds =
                compose
                    .onNodeWithText("Mac:02:00:00:00:00:10", useUnmergedTree = true)
                    .fetchSemanticsNode()
                    .boundsInRoot
            val levelBounds =
                compose
                    .onNodeWithText("56%", useUnmergedTree = true)
                    .fetchSemanticsNode()
                    .boundsInRoot
            assertTrue("状态点和 MAC 应在设备名下方", macBounds.top >= deviceBounds.bottom)
            assertTrue("亮度应位于设备信息右侧", levelBounds.left >= deviceBounds.right)
            assertTrue("亮度不能越过页面右侧边距", levelBounds.right <= (viewportWidth - 19) * 2f)

            compose.onNodeWithText("未连接 · 点开恢复连接").assertDoesNotExist()
            compose.onNodeWithText("等待连接").assertDoesNotExist()
            compose.onNodeWithText("离线").assertDoesNotExist()
            val headingLeft =
                compose
                    .onAllNodesWithText("Open Rhodes", useUnmergedTree = true)
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
            val nameTop =
                compose
                    .onNodeWithText(name, useUnmergedTree = true)
                    .fetchSemanticsNode()
                    .boundsInRoot
                    .top
            assertTrue("首页字标下方留白不应超过 45dp", nameTop - requireNotNull(ruleY) < 90f)
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
            val heading =
                compose.onAllNodesWithText("Open Rhodes", useUnmergedTree = true).onFirst()
            val bounds = heading.fetchSemanticsNode().boundsInRoot
            val unit = bounds.width / WordmarkGeometry.Width
            val y = (bounds.top + WordmarkGeometry.RuleTop * unit + 2).toInt()
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
            fun glintCenter(image: Bitmap): Float {
                var sum = 0L
                var count = 0
                for (py in
                    bounds.top.toInt() until
                        (bounds.top + (WordmarkGeometry.Baseline + 8f) * unit).toInt()) {
                    for (px in left until right) {
                        val color = image.getPixel(px, py)
                        if (
                            android.graphics.Color.green(color) > 235 &&
                                android.graphics.Color.blue(color) > 225
                        ) {
                            sum += px
                            count++
                        }
                    }
                }
                assertTrue("No visible highlight within the glyphs", count > 20)
                return sum.toFloat() / count
            }
            assertTrue(
                "Highlight must travel right",
                glintCenter(darkPass) > glintCenter(firstPass) + 30f,
            )
            val returning = frame(root, "wordmark-sheen-repeat-start", 1100)
            val returned = frame(root, "wordmark-sheen-repeat-advanced", 500)
            assertTrue(
                "高光的新一轮仍应向右移动",
                glintCenter(returned) > glintCenter(returning) + 30f,
            )
            for (image in listOf(firstPass, darkPass, returning, returned)) {
                assertEquals(
                    "Highlight leaked into the O counter",
                    android.graphics.Color.BLACK,
                    image.getPixel(
                        (bounds.left + WordmarkGeometry.OCounterX * unit).toInt(),
                        (bounds.top + WordmarkGeometry.OCounterY * unit).toInt(),
                    ),
                )
                assertTrue(
                    "Highlight changed the idle underline",
                    countInk(finished) > (right - left) * .95,
                )
            }
            listOf(
                    first,
                    middle,
                    firstPass,
                    darkPass,
                    darkFinished,
                    finalBright,
                    finished,
                    returning,
                    returned,
                )
                .forEach { it.recycle() }
            val before = app.discoveryRevision.value
            val marker = compose.onNodeWithText("添加你的第一盏灯", useUnmergedTree = true)
            val originalTop = marker.fetchSemanticsNode().boundsInRoot.top
            val footer = compose.onNodeWithContentDescription("GitHub 项目 OpenRhodes")
            val originalFooter = footer.fetchSemanticsNode().boundsInRoot
            val scrolling = compose.onNode(hasScrollAction())
            scrolling.performTouchInput {
                down(Offset(centerX, height * .1f))
                moveBy(Offset(0f, 80f), 120)
            }
            val partialPull = frame(root, "home-pull-partial", 32)
            val partialOffset = marker.fetchSemanticsNode().boundsInRoot.top - originalTop
            assertTrue("列表应跟随未达阈值的下拉手势", partialOffset in 10f..90f)
            val progress =
                (left until right)
                    .count { px ->
                        android.graphics.Color.green(partialPull.getPixel(px, y)) > 210
                    }
                    .toFloat() / (right - left)
            assertEquals("横条进度应与列表下移距离匹配", partialOffset / 128f, progress, .10f)
            assertEquals("下拉时底部署名应固定", originalFooter, footer.fetchSemanticsNode().boundsInRoot)
            assertEquals("下拉时标题应固定", bounds, heading.fetchSemanticsNode().boundsInRoot)
            assertEquals("达到阈值前不能刷新", before, app.discoveryRevision.value)
            scrolling.performTouchInput { up() }
            frame(root, "home-pull-cancelled", 800).recycle()
            assertEquals(
                "短距离下拉松手应回位",
                originalTop,
                marker.fetchSemanticsNode().boundsInRoot.top,
                2f,
            )
            assertEquals("短距离松手不能刷新", before, app.discoveryRevision.value)
            scrolling.performTouchInput {
                down(Offset(centerX, height * .1f))
                moveBy(Offset(0f, 400f), 200)
            }
            val armedPull = frame(root, "home-pull-armed", 32)
            val armedOffset = marker.fetchSemanticsNode().boundsInRoot.top - originalTop
            assertTrue("达到阈值后仍保留有上限的阻尼位移", armedOffset in 120f..162f)
            assertTrue("达到刷新阈值横条应填满", countInk(armedPull) > (right - left) * .95)
            assertEquals("尚未松手时不能刷新", before, app.discoveryRevision.value)
            scrolling.performTouchInput { up() }
            frame(root, "underline-after-pull").recycle()
            partialPull.recycle()
            armedPull.recycle()
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
