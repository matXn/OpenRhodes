package com.openrhodes

import android.Manifest
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
@Config(sdk=[34],application=RhodesApp::class,qualifiers="w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AutomaticAddVisualTest {
    @get:Rule val compose=createEmptyComposeRule()

    @Test(timeout=60000)
    fun fastBluetoothAutomaticallyHandsOffOnceWithoutAConfirmationPage() = review(false)

    @Test(timeout=60000)
    fun backgroundSuccessDoesNotWaitForThePausedAnimation() = review(true)

    private fun review(background: Boolean) {
        ShadowChoreographer.setPaused(false)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        compose.mainClock.autoAdvance=false
        val app=RuntimeEnvironment.getApplication() as RhodesApp
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT)
        val hardware=HardwareId.parse("02:00:00:00:00:44")!!
        val connection=CompletableDeferred<JsonObject>()
        val snapshot=LampState(false,56,hardware=hardware)
        var connects=0
        var saves=0
        var completions=0
        var result by mutableStateOf<AddCompletion?>(null)
        val gateway=object:ProvisioningGateway {
            override val nearby=MutableStateFlow<Map<HardwareId,Endpoint>>(emptyMap())
            override fun name(hardware:HardwareId):String?=null
            override fun rescan()=Unit
            override suspend fun read(endpoint:Endpoint):LampState=error("蓝牙直控不应读取 Wi-Fi")
            override suspend fun enroll(hardware:HardwareId,endpoint:Endpoint,state:LampState,name:String):AddCompletion=error("不应走局域网保存")
            override suspend fun adoptBluetooth(link:ProvisionLink,peer:BlePeer,hardware:HardwareId,name:String):AddCompletion {
                saves++
                return AddCompletion("saved",true,snapshot,name)
            }
            override suspend fun legacyProof(endpoint:Endpoint,expected:HardwareId?):String?=null
        }
        fun radio()=object:ProvisionLink {
            override val supportsLightControl=true
            override suspend fun scan(found:(BlePeer)->Unit) {
                found(BlePeer("02:00:00:00:00:45","Rhodes Light",-50))
                awaitCancellation()
            }
            override suspend fun connect(peer:BlePeer,proof:String?):JsonObject {
                connects++
                return connection.await()
            }
            override suspend fun request(data:ByteArray)=Json.parseToJsonElement(
                """{"mac":"02:00:00:00:00:44","on":false,"brightness":56}""").jsonObject
            override fun close()=Unit
        }
        val controller=Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            controller.get().setContent { RhodesTheme {
                val saved=result
                if(saved==null) BluetoothProvision(app,close={},gateway=gateway,linkFactory=::radio,added={
                    completions++
                    result=it
                })
                else Surface(Modifier.fillMaxSize(),color=Color.Black) {
                    AddSuccessFrame(saved.snapshot,saved.name,Rect(54f,232f,666f,692f),
                        AddSuccessTiming.SAVED_START_MS/AddSuccessTiming.DURATION)
                }
            } }
            fun advance(ms:Long,label:String) {
                compose.mainClock.advanceTimeBy(ms)
                ShadowLooper.idleMainLooper(ms,TimeUnit.MILLISECONDS)
                val image=Bitmap.createBitmap(720,1600,Bitmap.Config.ARGB_8888)
                repeat(3) {
                    compose.mainClock.advanceTimeByFrame()
                    ShadowLooper.idleMainLooper(16,TimeUnit.MILLISECONDS)
                    val dialog=ShadowDialog.getLatestDialog()
                    val root=if(dialog?.isShowing==true)dialog.window!!.decorView else controller.get().window.decorView
                    root.measure(View.MeasureSpec.makeMeasureSpec(720,View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(1600,View.MeasureSpec.EXACTLY))
                    root.layout(0,0,720,1600)
                    root.draw(Canvas(image))
                }
                compose.waitForIdle()
                val output=File("build/reports/auto-add-review/${if(background)"background" else "foreground"}-$label.png")
                output.parentFile!!.mkdirs()
                output.outputStream().use {image.compress(Bitmap.CompressFormat.PNG,100,it)}
                image.recycle()
            }
            advance(300,"discover")
            if (!background) {
                System.getenv("RHODES_SWEEP_PREVIEW")?.let { path ->
                    val dir = File(path).apply { mkdirs() }
                    val root = ShadowDialog.getLatestDialog().window!!.decorView
                    val bitmap = Bitmap.createBitmap(720, 240, Bitmap.Config.ARGB_8888)
                    val bounds = compose.onNodeWithText("Rhodes Light").fetchSemanticsNode().boundsInRoot
                    val canvas = Canvas(bitmap).apply { translate(0f, 160f - bounds.center.y) }
                    repeat(125) { frame ->
                        compose.mainClock.advanceTimeBy(32)
                        ShadowLooper.idleMainLooper(32,TimeUnit.MILLISECONDS)
                        bitmap.eraseColor(android.graphics.Color.BLACK)
                        root.draw(canvas)
                        File(dir, "%03d.png".format(frame)).outputStream().use {
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                    }
                    bitmap.recycle()
                }
                val ring = compose.onNodeWithContentDescription("连接进度圆环", useUnmergedTree = true)
                    .fetchSemanticsNode().boundsInRoot
                val advanced = compose.onNodeWithText("高级选项")
                assertEquals(ring.left + ring.width / 12f, advanced.fetchSemanticsNode().boundsInRoot.left, 1f)
                advanced.performClick()
                advance(250, "advanced-inline")
                compose.onAllNodes(isPopup()).assertCountEquals(0)
                advanced.performClick()
                compose.mainClock.advanceTimeBy(250)
                compose.onNodeWithText("手动地址").assertDoesNotExist()
                advanced.performClick()
                compose.mainClock.advanceTimeBy(250)
                compose.onNodeWithText("热点配网").performClick()
                compose.mainClock.advanceTimeBy(250)
                compose.onNodeWithText("打开 Wi-Fi 设置").assertIsDisplayed()
                compose.onNodeWithContentDescription("返回").performClick()
                compose.mainClock.advanceTimeBy(250)
                advanced.assertIsDisplayed()
                compose.onNodeWithText("手动地址").assertDoesNotExist()
            }
            compose.onNodeWithText("BLE").assertIsDisplayed()
            compose.onNodeWithText("Wi-Fi").assertIsDisplayed()
            assertEquals(0,connects)
            compose.onNodeWithText("Rhodes Light").performClick()
            advance(100,"connecting")
            if(background)controller.pause().stop()
            connection.complete(Json.parseToJsonElement("""{"mac":"02:00:00:00:00:44"}""").jsonObject)
            advance(300,"verified")
            assertEquals(1,connects)
            assertEquals(1,saves)
            if(!background) {
                compose.onNodeWithContentDescription("确认名称并添加").assertDoesNotExist()
                compose.onNodeWithText("连接方式").assertDoesNotExist()
                assertEquals("快速请求的界面应自动走完连接动效",0,completions)
                compose.onAllNodesWithText("Rhodes Light",useUnmergedTree=true).assertCountEquals(1)
                advance(3600,"handoff")
            }
            assertEquals(1,completions)
            assertNotNull(result)
            if(background) {
                controller.start().resume().visible()
                advance(100,"resumed")
            }
            compose.onNodeWithTag("add-success-name").assertExists()
            compose.onAllNodesWithText("Rhodes Light",useUnmergedTree=true).assertCountEquals(1)
            advance(1500,"settled")
            assertEquals(1,completions)
            assertEquals(1,saves)
        } finally {
            controller.pause().stop().destroy()
            app.scope.cancel()
        }
    }
}
