package com.openrhodes

import android.Manifest
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.openrhodes.data.*
import com.openrhodes.domain.*
import com.openrhodes.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import org.robolectric.shadows.*
import java.time.Duration
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=RhodesApp::class,qualifiers="w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProvisionDiscoveryLifecycleTest {
    @get:Rule val compose=createEmptyComposeRule()

    @Test(timeout=60000)
    fun openingSearchesImmediatelyAndForegroundReturnRecoversFailedFirstScan() {
        ShadowChoreographer.setPaused(false)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        compose.mainClock.autoAdvance=false
        val app=RuntimeEnvironment.getApplication() as RhodesApp
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT)
        var scans=0;var active=0;var peak=0
        val gateway=object:ProvisioningGateway {
            override val nearby=MutableStateFlow<Map<HardwareId,Endpoint>>(emptyMap())
            override fun name(hardware:HardwareId):String?=null
            override fun rescan()=Unit
            override suspend fun read(endpoint:Endpoint):LampState=error("不应发起控制请求")
            override suspend fun enroll(hardware:HardwareId,endpoint:Endpoint,state:LampState,name:String):AddCompletion=error("不应添加")
            override suspend fun legacyProof(endpoint:Endpoint,expected:HardwareId?):String?=null
        }
        fun radio()=object:ProvisionLink {
            override suspend fun scan(found:(BlePeer)->Unit) {
                scans++;active++;peak=maxOf(peak,active)
                try {
                    if(scans==1)error("蓝牙尚未就绪")
                    found(BlePeer("02:00:00:00:00:45","RhodesLight",-50))
                    awaitCancellation()
                } finally {active--}
            }
            override suspend fun connect(peer:BlePeer,proof:String?):JsonObject=error("不应自动连接")
            override suspend fun request(data:ByteArray):JsonObject=error("不应发送请求")
            override fun close()=Unit
        }
        val controller=Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            controller.get().setContent {RhodesTheme {BluetoothProvision(app,close={},gateway=gateway,linkFactory=::radio,added={fail("未点选不应添加")})}}
            fun advance(ms:Long) {
                compose.mainClock.advanceTimeBy(ms)
                ShadowLooper.idleMainLooper(ms,TimeUnit.MILLISECONDS)
                val root=ShadowDialog.getLatestDialog().window!!.decorView
                repeat(2) {
                    root.measure(View.MeasureSpec.makeMeasureSpec(720,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1600,View.MeasureSpec.EXACTLY))
                    root.layout(0,0,720,1600)
                    compose.mainClock.advanceTimeByFrame()
                    ShadowLooper.idleMainLooper(16,TimeUnit.MILLISECONDS)
                }
                compose.waitForIdle()
            }
            advance(250)
            assertEquals("打开添加页必须自动发起首轮扫描",1,scans)
            controller.pause().stop()
            advance(80)
            assertEquals(0,active)
            controller.start().resume().visible()
            advance(400)
            assertEquals("返回前台无需再点击重新寻找",2,scans)
            compose.onAllNodesWithText("RhodesLight").assertCountEquals(1)
            compose.onNodeWithText("RhodesLight").assertIsDisplayed()
            assertEquals(1,peak)
        } finally {controller.pause().stop().destroy();app.scope.cancel()}
    }
}
