package com.openrhodes

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.openrhodes.data.*
import com.openrhodes.domain.*
import com.openrhodes.ui.*
import java.io.File
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import org.robolectric.shadows.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = RhodesApp::class, qualifiers = "w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SetupVisualTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val hardware = HardwareId.parse("02:00:00:00:00:44")!!

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test(timeout = 60000)
    fun setupHasBlackBackgroundAndOnlyCurrentStepAtLargeFont() {
        RuntimeEnvironment.setFontScale(1.3f)
        ShadowChoreographer.setPaused(false)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        compose.mainClock.autoAdvance = false
        val app = RuntimeEnvironment.getApplication() as RhodesApp
        Shadows.shadowOf(app)
            .grantPermissions(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        val gateway =
            object : ProvisioningGateway {
                override val nearby = MutableStateFlow<Map<HardwareId, Endpoint>>(emptyMap())

                override fun name(hardware: HardwareId): String? = null

                override fun rescan() = Unit

                override suspend fun read(endpoint: Endpoint): LampState = error("offline")

                override suspend fun enroll(
                    hardware: HardwareId,
                    endpoint: Endpoint,
                    state: LampState?,
                ) = "lamp"

                override suspend fun reachable(id: String) = false

                override suspend fun legacyProof(
                    endpoint: Endpoint,
                    expected: HardwareId?,
                ): String? = null
            }
        fun radio() =
            object : ProvisionLink {
                override suspend fun scan(found: (BlePeer) -> Unit) {
                    found(BlePeer("02:00:00:00:00:45", "RhodesLight", -55))
                }

                override suspend fun connect(peer: BlePeer, proof: String?) =
                    json("""{"v":1,"mac":"02:00:00:00:00:44","status":"ready"}""")

                override suspend fun request(data: ByteArray) =
                    if (data[0].toInt() == 3)
                        json(
                            """{"v":1,"generation":1,"next":-1,"networks":[{"ssid":"Home Network","secure":true,"rssi":-50,"saved":false}]}"""
                        )
                    else json("""{"v":1}""")

                override fun close() = Unit
            }
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            controller.get().setContent {
                RhodesTheme {
                    BluetoothProvision(
                        app,
                        close = {},
                        gateway = gateway,
                        linkFactory = ::radio,
                        added = {},
                    )
                }
            }
            fun capture(name: String) {
                compose.mainClock.advanceTimeBy(350)
                compose.waitForIdle()
                val root = requireNotNull(ShadowDialog.getLatestDialog().window).decorView
                val image = Bitmap.createBitmap(720, 1600, Bitmap.Config.ARGB_8888)
                repeat(3) {
                    ShadowLooper.idleMainLooper(120, TimeUnit.MILLISECONDS)
                    root.measure(
                        View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY),
                    )
                    root.layout(0, 0, 720, 1600)
                    root.draw(Canvas(image))
                }
                assertEquals(
                    "Page background must be black",
                    android.graphics.Color.BLACK,
                    image.getPixel(10, 1200),
                )
                val file = File("build/reports/visual-review/$name.png")
                file.parentFile!!.mkdirs()
                file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                image.recycle()
            }
            capture("setup-discover")
            compose.onNodeWithText("RhodesLight · 0044").assertIsDisplayed().performClick()
            capture("setup-networks")
            compose.onNodeWithText("选择 Wi-Fi").assertIsDisplayed()
            compose.onNodeWithText("Wi-Fi 密码").assertDoesNotExist()
            compose.onNodeWithText("Home Network").performClick()
            capture("setup-password")
            compose.onNodeWithText("Wi-Fi 密码").assertIsDisplayed()
            compose.onNodeWithText("IP 或 rhodeslight.local").assertDoesNotExist()
            compose.onNodeWithText("旧固件兼容（需连接灯具热点）").assertDoesNotExist()
            compose.onNodeWithContentDescription("返回").performClick()
            capture("setup-returned")
            compose.onNodeWithText("选择 Wi-Fi").assertIsDisplayed()
            compose.onNodeWithText("Wi-Fi 密码").assertDoesNotExist()
        } finally {
            controller.pause().stop().destroy()
            app.scope.cancel()
        }
    }
}
