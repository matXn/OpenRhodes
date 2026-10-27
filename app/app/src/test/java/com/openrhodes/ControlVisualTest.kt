package com.openrhodes

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import com.openrhodes.domain.LampState
import com.openrhodes.ui.*
import java.io.File
import java.time.Duration
import java.util.concurrent.TimeUnit
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
import org.robolectric.shadows.ShadowSystemClock

/** Render the actual Compose components through Android/Skia, not an HTML facsimile. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ControlVisualTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun capture(
        name: String,
        brightness: Int,
        fontScale: Float = 1f,
        width: Int = 393,
        breathing: Boolean = false,
        gestures: Boolean = false,
        live: Boolean = false,
    ) {
        RuntimeEnvironment.setQualifiers("w${width}dp-h852dp-xhdpi")
        RuntimeEnvironment.setFontScale(fontScale)
        compose.mainClock.autoAdvance = false
        ShadowChoreographer.setPaused(false)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        var taps = 0
        val levels = mutableListOf<Int>()
        val modes = mutableListOf<Boolean>()
        var pageScroll: androidx.compose.foundation.ScrollState? = null
        val fixture =
            mutableStateOf(
                LampState(
                    true,
                    brightness,
                    breathing = breathing,
                    sampledAt = android.os.SystemClock.elapsedRealtime(),
                )
            )
        activity.setContent {
            RhodesTheme {
                Surface(
                    Modifier.fillMaxSize(),
                    color = androidx.compose.material3.MaterialTheme.colorScheme.background,
                ) {
                    Column {
                        LampToolbar("RhodesLight", "192.168.3.229", {}, {})
                        val scroll = rememberScrollState()
                        pageScroll = scroll
                        Column(
                            Modifier.weight(1f).verticalScroll(scroll).padding(horizontal = 20.dp)
                        ) {
                            Spacer(Modifier.height(16.dp))
                            LampCard(
                                fixture.value,
                                true,
                                { levels += it },
                                { taps++ },
                            )
                            Spacer(Modifier.height(24.dp))
                            if (live) BreathPlot(fixture.value)
                            LightModeSelector(breathing, true) { modes += it }
                            if (gestures) Spacer(Modifier.height(700.dp))
                        }
                    }
                }
            }
        }
        ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
        val root = activity.window.decorView
        root.measure(
            View.MeasureSpec.makeMeasureSpec(width * 2, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(852 * 2, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, width * 2, 852 * 2)
        ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val file = File("build/reports/visual-review/$name.png")
        requireNotNull(file.parentFile).mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("Empty render", bitmap.getPixel(width, 400) != bitmap.getPixel(width, 1600))
        bitmap.recycle()
        if (live) {
            val card = compose.onNodeWithContentDescription("Rhodes Light")
            compose.runOnUiThread {
                fixture.value =
                    fixture.value.copy(
                        breathing = true,
                        phase = Math.PI / 2,
                        sampledAt = android.os.SystemClock.elapsedRealtime(),
                    )
            }
            compose.mainClock.advanceTimeBy(16)
            compose.waitForIdle()
            assertTrue(
                card
                    .fetchSemanticsNode()
                    .config[SemanticsProperties.StateDescription]
                    .contains("上限 56%")
            )
            ShadowSystemClock.advanceBy(Duration.ofMillis(800))
            compose.mainClock.advanceTimeBy(800)
            compose.waitForIdle()
            val firstLive = card.fetchSemanticsNode().config[SemanticsProperties.StateDescription]
            ShadowSystemClock.advanceBy(Duration.ofMillis(1000))
            compose.mainClock.advanceTimeBy(1000)
            compose.waitForIdle()
            val nextLive = card.fetchSemanticsNode().config[SemanticsProperties.StateDescription]
            assertFalse(
                "Live percentage still has decimals",
                Regex("\\d+\\.\\d+%").containsMatchIn(nextLive),
            )
            assertNotEquals("Breathing output label stayed fixed", firstLive, nextLive)
            assertEquals("Live display changed the control ceiling", 56, fixture.value.brightness)
            compose.onNodeWithContentDescription("呼吸引导").assertDoesNotExist()
            compose.onAllNodesWithText("%", substring = true).assertCountEquals(0)
            compose.onNodeWithText("滑动调光\n轻点开关").assertDoesNotExist()
            compose.onNodeWithText("Rhodes Light").assertIsDisplayed()
            val restingBounds = card.fetchSemanticsNode().boundsInRoot
            val plotMarkers = mutableListOf<Offset>()
            fun cardTop(phase: Double): Int {
                compose.runOnUiThread {
                    fixture.value =
                        fixture.value.copy(
                            phase = phase,
                            sampledAt = android.os.SystemClock.elapsedRealtime(),
                        )
                }
                compose.mainClock.advanceTimeBy(32)
                compose.waitForIdle()
                val rendered = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                root.draw(Canvas(rendered))
                val x = restingBounds.center.x.toInt()
                val top =
                    ((restingBounds.top.toInt() - 24)..(restingBounds.top.toInt() + 24)).first { y
                        ->
                        rendered.getPixel(x, y) != android.graphics.Color.BLACK
                    }
                val plot =
                    compose.onNodeWithContentDescription("呼吸曲线").fetchSemanticsNode().boundsInRoot
                val pixels = mutableListOf<Offset>()
                for (py in plot.top.toInt() until plot.bottom.toInt()) {
                    for (px in plot.left.toInt() until plot.right.toInt()) {
                        if (
                            rendered.getPixel(px, py) == android.graphics.Color.rgb(244, 232, 209)
                        ) {
                            pixels += Offset(px.toFloat(), py.toFloat())
                        }
                    }
                }
                assertTrue("Plot marker was not drawn", pixels.isNotEmpty())
                plotMarkers +=
                    Offset(
                        pixels.map { it.x }.average().toFloat(),
                        pixels.map { it.y }.average().toFloat(),
                    )
                val phaseName = if (phase == 0.0) "raised" else "lowered"
                File("build/reports/visual-review/breathing-card-$phaseName.png")
                    .outputStream()
                    .use {
                        rendered.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                rendered.recycle()
                return top
            }
            val raised = cardTop(0.0)
            val lowered = cardTop(Math.PI)
            assertTrue("Card did not float with the breathing phase", lowered - raised >= 6)
            assertEquals("Marker moved horizontally", plotMarkers[0].x, plotMarkers[1].x, .5f)
            assertTrue(
                "Marker did not follow wave height",
                plotMarkers[1].y - plotMarkers[0].y > 50,
            )

            val liveImage = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(liveImage))
            File("build/reports/visual-review/breathing-live.png").outputStream().use {
                liveImage.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            liveImage.recycle()
            compose.runOnUiThread { fixture.value = fixture.value.copy(breathing = false) }
            compose.mainClock.advanceTimeBy(16)
            compose.waitForIdle()
            ShadowSystemClock.advanceBy(Duration.ofMillis(500))
            compose.mainClock.advanceTimeBy(500)
            compose.waitForIdle()
            compose.onNodeWithText("56%").assertIsDisplayed()
            compose.runOnUiThread { fixture.value = fixture.value.copy(on = false) }
            compose.mainClock.advanceTimeBy(16)
            compose.waitForIdle()
            compose.onNodeWithText("关").assertIsDisplayed()
            assertFalse(
                card.fetchSemanticsNode().config[SemanticsProperties.StateDescription].contains('%')
            )
            compose.runOnUiThread { fixture.value = fixture.value.copy(on = true) }
            compose.mainClock.advanceTimeBy(16)
            compose.waitForIdle()
            compose.onNodeWithText("56%").assertIsDisplayed()
        }
        if (gestures) {
            val card = compose.onNodeWithContentDescription("Rhodes Light")
            card.performTouchInput {
                down(center)
                moveBy(Offset(0f, -120f))
                moveBy(Offset(0f, -80f))
            }
            compose.mainClock.advanceTimeBy(100)
            compose.waitForIdle()
            val pressedImage = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(pressedImage))
            File("build/reports/visual-review/drag-tilt.png").outputStream().use {
                pressedImage.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            pressedImage.recycle()
            card.performTouchInput { up() }
            compose.mainClock.advanceTimeBy(100)
            compose.waitForIdle()
            assertTrue("Drag did not dim", levels.isNotEmpty() && levels.last() > brightness)
            assertEquals("Drag triggered power", 0, taps)
            assertEquals("Card drag scrolled the page", 0, requireNotNull(pageScroll).value)
            card.performTouchInput { click(center) }
            assertEquals("Tap did not toggle exactly once", 1, taps)
            card.performTouchInput {
                down(center)
                cancel()
            }
            assertEquals("Cancelled gesture toggled power", 1, taps)
            card.performTouchInput {
                down(center)
                advanceEventTime(700)
                up()
            }
            assertEquals("Holding the card toggled power", 1, taps)
            val previousChanges = levels.size
            card.performTouchInput {
                swipe(center, Offset(centerX, centerY - 160f), 300)
            }
            compose.mainClock.advanceTimeBy(300)
            compose.waitForIdle()
            assertTrue("Continuous small-move swipe was ignored", levels.size > previousChanges)
            assertEquals("Continuous swipe toggled power", 1, taps)
            assertEquals(
                "Continuous card swipe scrolled the page",
                0,
                requireNotNull(pageScroll).value,
            )
            compose.mainClock.advanceTimeBy(500)
            compose.waitForIdle()
            val releasedImage =
                Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(releasedImage))
            File("build/reports/visual-review/drag-released.png").outputStream().use {
                releasedImage.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            releasedImage.recycle()
            compose.onNodeWithText("呼吸").performClick()
            assertEquals(listOf(true), modes)
            compose.onNode(hasScrollAction()).performTouchInput {
                swipe(Offset(centerX, height * .9f), Offset(centerX, height * .7f), 300)
            }
            compose.mainClock.advanceTimeBy(300)
            compose.waitForIdle()
            assertTrue(
                "Outside-card scrolling is blocked; scroll range=${requireNotNull(pageScroll).maxValue}",
                requireNotNull(pageScroll).value > 0,
            )
        }
        controller.pause().stop().destroy()
    }

    @Test fun steady56() = capture("steady-56", 56)

    @Test fun dim1() = capture("dim-1", 1)

    @Test fun full100() = capture("full-100", 100)

    @Test fun smallLargeFont() = capture("small-large-font", 100, 1.3f, 320)

    @Test fun breathing56() = capture("breathing-56", 56, breathing = true)

    @Test(timeout = 60000)
    fun liveBreathingCardAndModeReturn() = capture("before-breath", 56, live = true)

    @Test(timeout = 60000)
    fun cardDragTapCancelAndPageScroll() = capture("gesture-start", 56, gestures = true)
}
