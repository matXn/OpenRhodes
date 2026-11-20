package com.openrhodes

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.openrhodes.data.CatalogStore
import com.openrhodes.domain.*
import com.openrhodes.ui.RhodesRoot
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.time.Duration
import java.util.concurrent.TimeUnit
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = RhodesApp::class, qualifiers = "w393dp-h852dp-xhdpi",
    shadows = [PosixAtomicFileShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DeviceSettingsTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val hardware = requireNotNull(HardwareId.parse("02:00:00:00:00:44"))
    private val endpoint = requireNotNull(Endpoint.parse("192.0.2.10"))
    private val lamp = Lamp("settings-fixture", "Rhodes Light", hardware, listOf(endpoint), setOf("old-fixture"))
    private var viewportWidth = 393
    private var viewportHeight = 852

    private fun frame(root: View, name: String) {
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        ShadowLooper.idleMainLooper(200, TimeUnit.MILLISECONDS)
        val width = viewportWidth * 2
        val height = viewportHeight * 2
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, height)
        val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(image))
        ShadowLooper.idleMainLooper(240, TimeUnit.MILLISECONDS)
        compose.mainClock.advanceTimeByFrame()
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, height)
        root.draw(Canvas(image))
        val output = File("build/reports/settings-review/$name.png")
        output.parentFile!!.mkdirs()
        output.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    private fun withSettings(width: Int = 393, height: Int = 852, scale: Float = 1f, block: (RhodesApp, View) -> Unit) {
        viewportWidth = width
        viewportHeight = height
        RuntimeEnvironment.setQualifiers("w${width}dp-h${height}dp-xhdpi")
        RuntimeEnvironment.setFontScale(scale)
        ShadowChoreographer.setPaused(false)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        compose.mainClock.autoAdvance = false
        val app = RuntimeEnvironment.getApplication() as RhodesApp
        runBlocking { app.awaitReady(); app.catalog.enroll(lamp) }
        app.session(lamp.id).acceptDiscovered(endpoint, LampState(true, 56, hardware = hardware, firmwareVersion = "fixture-1.0"))
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            controller.get().setContent { RhodesRoot(app, MutableStateFlow(lamp.id)) }
            val root = controller.get().window.decorView
            frame(root, "detail")
            compose.onNodeWithContentDescription("设备设置").performClick()
            frame(root, "menu")
            block(app, root)
        } finally {
            controller.pause().stop().destroy()
            RuntimeEnvironment.setFontScale(1f)
            app.scope.cancel()
        }
    }

    @Test(timeout = 60000) fun appearanceFollowsSystemWhileEditing() = withSettings { app, root ->
        compose.onNodeWithText("灯具名称").performClick()
        for ((mode, label) in listOf("night" to "dark", "notnight" to "light")) {
            RuntimeEnvironment.setQualifiers("+$mode")
            app.onConfigurationChanged(app.resources.configuration)
            frame(root, "appearance-$label")
            assertEquals(mode == "notnight", com.openrhodes.ui.Appearance.light)
            compose.onNodeWithText("保存名称").assertIsDisplayed()
            compose.onNodeWithText("切换浅色").assertDoesNotExist()
            compose.onNodeWithText("切换深色").assertDoesNotExist()
        }
    }

    @Test(timeout = 60000) fun fourEntriesNavigateAndLicenseOpensFullDocumentDirectly() = withSettings { _, root ->
        for (label in listOf("灯具名称", "连接", "设备信息", "第三方开源许可")) compose.onNodeWithText(label).assertIsDisplayed()
        compose.onNodeWithText("控制方式").assertDoesNotExist()
        compose.onNodeWithText("连接帮助").assertDoesNotExist()
        compose.onNodeWithText("保存名称").assertDoesNotExist()
        compose.onNodeWithText("灯具名称").performClick()
        frame(root, "accordion-name")
        compose.onNodeWithText("保存名称").assertIsDisplayed()
        compose.onNodeWithText("设备信息").performClick()
        frame(root, "accordion-info")
        compose.onNodeWithText("保存名称").assertDoesNotExist()
        compose.onNodeWithText("MAC 地址").assertIsDisplayed()
        compose.onNodeWithText("连接").performClick()
        frame(root, "accordion-connection")
        compose.onNodeWithText("MAC 地址").assertDoesNotExist()
        compose.onNodeWithText("Wi-Fi 网络").assertIsDisplayed()
        compose.onNodeWithText("连接").performClick()
        frame(root, "accordion-closed")
        compose.onNodeWithText("Wi-Fi 网络").assertDoesNotExist()
        compose.onNodeWithText("第三方开源许可").performClick()
        compose.waitUntil(5000) {
            ShadowLooper.idleMainLooper(32, TimeUnit.MILLISECONDS)
            compose.mainClock.advanceTimeByFrame()
            compose.onAllNodes(hasText("androidx.activity", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        frame(root, "licenses")
        compose.onNode(hasText("androidx.activity", substring = true)).assertIsDisplayed()
        compose.onNodeWithText("关闭").assertDoesNotExist()
        compose.onNodeWithContentDescription("返回").performClick()
        frame(root, "menu-return")
        compose.onNodeWithText("灯具名称").assertIsDisplayed()
    }

    @Test(timeout = 60000) fun nameValidationAndSaveReturnToMenu() = withSettings { app, root ->
        compose.onNodeWithText("灯具名称").performClick()
        frame(root, "name")
        compose.onNodeWithContentDescription("灯具名称输入").performTextReplacement("   ")
        compose.onNodeWithText("保存名称").performClick()
        frame(root, "empty-name")
        compose.onNodeWithText("请填写灯具名称").assertIsDisplayed()
        compose.onNodeWithContentDescription("灯具名称输入").performTextReplacement("床头灯")
        compose.onNodeWithText("保存名称").performClick()
        compose.waitUntil(5000) { app.catalog.find(lamp.id)?.name == "床头灯" }
        frame(root, "renamed")
        compose.onNodeWithText("连接").assertIsDisplayed()
        assertEquals("床头灯", app.catalog.find(lamp.id)?.name)
    }

    @Test(timeout = 60000) fun deviceInformationIsReadOnlyAndHomeLongPressOwnsDeletion() = withSettings { app, root ->
        compose.onNodeWithText("设备信息").performClick()
        frame(root, "information")
        compose.onNodeWithText("02:00:00:00:00:44").assertIsDisplayed()
        compose.onNodeWithText("fixture-1.0").assertIsDisplayed()
        compose.onNodeWithText("192.0.2.10", substring = true).assertDoesNotExist()
        compose.onNodeWithText("连接记录").assertDoesNotExist()
        compose.onNodeWithText("删除灯具").assertDoesNotExist()
        repeat(3) { compose.onNodeWithContentDescription("返回").performClick(); frame(root, "back-$it") }
        compose.onNodeWithText(lamp.name).performTouchInput { longClick() }
        compose.onNodeWithText("删除灯具").performClick()
        frame(root, "delete-confirm-cancel")
        compose.onNodeWithText("取消").performClick()
        assertNotNull(app.catalog.find(lamp.id))
        compose.onNodeWithText(lamp.name).performTouchInput { longClick() }
        compose.onNodeWithText("删除灯具").performClick()
        frame(root, "delete-confirm")
        compose.onNodeWithText("删除", useUnmergedTree = true).performClick()
        compose.waitUntil(5000) { app.catalog.find(lamp.id) == null }
        frame(root, "deleted-home")
        compose.onNodeWithText("添加你的第一盏灯").assertIsDisplayed()
        assertNull(app.catalog.find("old-fixture"))
        runBlocking {
            val reopened = CatalogStore(app)
            reopened.load()
            assertTrue(reopened.lamps.value.isEmpty())
        }
    }

    @Test(timeout = 60000) fun narrowScreenAndLargeFontsKeepFourMenuTargetsVisible() = withSettings(320, 640, 1.3f) { _, root ->
        frame(root, "menu-small-large-font")
        for (label in listOf("灯具名称", "连接", "设备信息", "第三方开源许可")) compose.onNodeWithText(label).assertIsDisplayed()
        compose.onNodeWithContentDescription("GitHub 项目 OpenRhodes").assertIsDisplayed()
    }

    @Test(timeout = 60000) fun deletingAliasKeepsOtherLampAndEndsTheOldSession() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as RhodesApp
        try {
            app.awaitReady()
            app.catalog.enroll(lamp)
            val other = Lamp("other", "另一盏灯", HardwareId.parse("02:00:00:00:00:46"), listOf(endpoint))
            app.catalog.enroll(other)
            val oldSession = app.session(lamp.id)
            app.removeLamp("old-fixture")
            assertEquals(listOf(other), app.catalog.lamps.value)
            assertEquals("目录磁盘写入应与内存一致", com.openrhodes.data.CatalogCodec.encode(listOf(other)),
                File(app.filesDir, "rhodes-catalog.json").readText())
            val failure = runCatching { withTimeout(1000) { oldSession.execute(Command.Power(true)) } }.exceptionOrNull()
            assertTrue(failure is CancellationException && failure !is TimeoutCancellationException)
            val reopened = CatalogStore(app)
            reopened.load()
            assertEquals(listOf(other), reopened.lamps.value)
        } finally { app.scope.cancel() }
    }
}
