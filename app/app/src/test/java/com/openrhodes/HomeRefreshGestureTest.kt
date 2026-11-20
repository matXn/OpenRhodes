package com.openrhodes

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.openrhodes.ui.homeConnectionColor
import com.openrhodes.ui.HomeRefreshFrame
import com.openrhodes.ui.RhodesTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w393dp-h852dp-xhdpi")
class HomeRefreshGestureTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun tapsDoNotTakeOverRefreshOrReturnButRealPullStillWorks() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        var refreshing by mutableStateOf(false)
        var dragging by mutableStateOf(false)
        lateinit var state: PullToRefreshState
        var starts = 0
        var requests = 0
        var clicks = 0
        try {
            controller.get().setContent { RhodesTheme {
                state = rememberPullToRefreshState()
                HomeRefreshFrame(true, Modifier.fillMaxSize().testTag("frame"), refreshing, dragging, state,
                    onRefresh = { requests++; refreshing = true },
                    onDragging = { if (it && !dragging) starts++; dragging = it }) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        Text("内容", Modifier.testTag("content").clickable { clicks++ })
                    }
                }
            } }
            compose.waitForIdle()
            val homeTop = compose.onNodeWithTag("content").fetchSemanticsNode().boundsInRoot.top
            // 模拟刷新停靠的残留距离。点击不能将其映射回内容位移。
            compose.runOnIdle { refreshing = true }
            compose.waitForIdle()
            compose.runOnIdle { runBlocking { state.snapTo(1f) } }
            compose.onNodeWithTag("frame").performTouchInput { down(Offset(center.x, center.y)); moveBy(Offset(1f, 1f)) }
            compose.waitForIdle()
            assertFalse(dragging)
            assertEquals(0, starts)
            assertEquals(homeTop, compose.onNodeWithTag("content").fetchSemanticsNode().boundsInRoot.top, 1f)
            compose.onNodeWithTag("frame").performTouchInput { up() }
            compose.onNodeWithTag("content").performTouchInput { click() }
            assertEquals("普通点击仍交给内容", 1, clicks)
            assertEquals(0, requests)

            compose.runOnIdle { refreshing = false }
            compose.waitForIdle()
            compose.onNodeWithTag("frame").performTouchInput {
                swipe(Offset(center.x, 120f), Offset(center.x, 800f), 500)
            }
            compose.waitForIdle()
            assertEquals("真实下拉仍触发一次刷新", 1, requests)
            assertEquals(1, starts)
            assertFalse(dragging)

            // 刷新中仅按下，不能重新启动拖动。
            compose.onNodeWithTag("frame").performTouchInput { down(center) }
            compose.waitForIdle()
            assertFalse(dragging)
            assertEquals(1, starts)
            compose.onNodeWithTag("frame").performTouchInput { up() }
            assertEquals(1, requests)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun realPullDuringRefreshDoesNotRequestAgainWhenNetworkFinishesBeforeLift() {
        withHome { home ->
            compose.mainClock.autoAdvance = false
            val top = contentTop()
            compose.runOnIdle { home.refreshing = true; runBlocking { home.state.snapTo(1f) } }
            compose.mainClock.advanceTimeBy(32)
            beginPull(600f)
            assertTrue("刷新中的真实下拉可以接手", home.dragging)
            assertEquals(1, home.starts)
            assertTrue(contentTop() > top + 20f)
            compose.runOnIdle { home.refreshing = false }
            compose.mainClock.advanceTimeBy(32)
            assertTrue("网络完成不能取消正在跟随的手势", home.dragging)
            compose.onNodeWithTag("frame").performTouchInput { up() }
            compose.mainClock.advanceTimeBy(400)
            compose.waitForIdle()
            assertFalse(home.dragging)
            assertEquals("刷新期间开始的手势不会重复请求", 0, home.requests)
            assertEquals(top, contentTop(), 1f)
        }
    }

    @Test fun realPullCatchesVisibleReturnWhileTapAndJitterLeaveItAlone() {
        withHome { home ->
            compose.mainClock.autoAdvance = false
            val top = contentTop()
            beginPull(180f)
            compose.onNodeWithTag("frame").performTouchInput { up() }
            compose.mainClock.advanceTimeBy(48)
            // 停靠残值与可见回弹位置不同，接手必须使用后者。
            compose.runOnIdle { runBlocking { home.state.snapTo(1f) } }
            compose.onNodeWithTag("frame").performTouchInput {
                down(Offset(center.x, 120f)); moveBy(Offset(1f, 1f)); up()
            }
            compose.mainClock.advanceTimeBy(16)
            assertFalse(home.dragging)
            assertEquals("回弹中的抖动没有成为第二次拖动", 1, home.starts)
            val before = contentTop()
            assertTrue("确实仍处在可见回弹途中", before > top + 1f)
            beginPull(80f)
            val caught = contentTop()
            assertTrue(home.dragging)
            assertEquals(2, home.starts)
            assertTrue("接手没有重置到原点", caught >= before - 1f)
            assertTrue("接手没有跳到刷新停靠距离", caught <= before + 80f * .675f + 2f)
            compose.onNodeWithTag("frame").performTouchInput { up() }
            compose.mainClock.advanceTimeBy(400)
            compose.waitForIdle()
            assertFalse(home.dragging)
            assertEquals(0, home.requests)
            assertEquals(top, contentTop(), 1f)
        }
    }

    @Test fun offlineDotStaysGrayAcrossReleaseAndReconnect() {
        withHome { home ->
            compose.mainClock.autoAdvance = false
            beginPull(600f)
            compose.mainClock.advanceTimeBy(64)
            val gray = androidx.compose.ui.graphics.Color(0xFF767676)
            val green = androidx.compose.ui.graphics.Color(0xFF89BC87)
            assertEquals(gray, home.offlineColor)
            assertEquals(green, home.onlineColor)
            compose.onNodeWithTag("frame").performTouchInput { up() }
            repeat(24) { frame ->
                if (frame == 2) compose.runOnIdle { home.connecting = true }
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                assertEquals("松手第 "+frame+" 帧", gray, home.offlineColor)
                assertEquals(green, home.onlineColor)
            }
            compose.runOnIdle { home.refreshing = false; home.connecting = false }
            repeat(32) {
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
            }
            compose.waitForIdle()
            assertEquals(androidx.compose.ui.graphics.Color(0xFFD28B76), home.offlineColor)
        }
    }

    private class Home {
        var connecting by mutableStateOf(false)
        var offlineColor = androidx.compose.ui.graphics.Color.Unspecified
        var onlineColor = androidx.compose.ui.graphics.Color.Unspecified
        var refreshing by mutableStateOf(false)
        var dragging by mutableStateOf(false)
        lateinit var state: PullToRefreshState
        var starts = 0
        var requests = 0
    }

    private fun withHome(block: (Home) -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val home = Home()
        try {
            controller.get().setContent { RhodesTheme {
                home.state = rememberPullToRefreshState()
                HomeRefreshFrame(true, Modifier.fillMaxSize().testTag("frame"), home.refreshing,
                    home.dragging, home.state,
                    onRefresh = { home.requests++; home.refreshing = true },
                    onDragging = { if (it && !home.dragging) home.starts++; home.dragging = it }) { pull ->
                    val offline = homeConnectionColor(home.connecting, !home.connecting, home.dragging, home.refreshing, pull).value
                    val online = homeConnectionColor(false, false, home.dragging, home.refreshing, pull).value
                    SideEffect { home.offlineColor = offline; home.onlineColor = online }
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        Text("内容", Modifier.testTag("content").clickable {})
                    }
                }
            } }
            compose.waitForIdle()
            block(home)
        } finally {
            compose.mainClock.autoAdvance = true
            controller.pause().stop().destroy()
        }
    }

    private fun contentTop() = compose.onNodeWithTag("content").fetchSemanticsNode().boundsInRoot.top

    private fun beginPull(distance: Float) {
        compose.onNodeWithTag("frame").performTouchInput {
            down(Offset(center.x, 120f)); moveBy(Offset(0f, distance))
        }
        compose.mainClock.advanceTimeBy(32)
        compose.waitForIdle()
    }
}
