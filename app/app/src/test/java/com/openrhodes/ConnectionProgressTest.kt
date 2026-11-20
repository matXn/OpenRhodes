package com.openrhodes

import com.openrhodes.data.*
import com.openrhodes.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ConnectionProgressTest {
    private val hardware=HardwareId.parse("02:00:00:00:00:44")!!
    private val peer=BlePeer("02:00:00:00:00:45","RhodesLight",-50)
    private inner class Link:ProvisionLink {
        override val supportsLightControl=true
        lateinit var report:(BleConnectionMilestone)->Unit
        val result=CompletableDeferred<JsonObject>()
        override suspend fun connect(peer:BlePeer,proof:String?):JsonObject=error("必须使用阶段回调")
        override suspend fun connect(peer:BlePeer,proof:String?,progress:(BleConnectionMilestone)->Unit):JsonObject {report=progress;return result.await()}
        override suspend fun scan(found:(BlePeer)->Unit)=Unit
        override suspend fun request(data:ByteArray)=Json.parseToJsonElement("""{"mac":"02:00:00:00:00:44","on":true,"brightness":56}""").jsonObject
        override fun close()=Unit
    }
    private fun gateway()=object:ProvisioningGateway {
        override val nearby=MutableStateFlow<Map<HardwareId,Endpoint>>(emptyMap())
        override fun name(hardware:HardwareId):String?=null
        override fun rescan()=Unit
        override suspend fun read(endpoint:Endpoint):LampState=error("不应调用局域网")
        override suspend fun enroll(hardware:HardwareId,endpoint:Endpoint,state:LampState,name:String):AddCompletion=error("不应保存设备")
        override suspend fun adoptBluetooth(link:ProvisionLink,peer:BlePeer,hardware:HardwareId,name:String)=
            AddCompletion("lamp",true,LampState(true,56,hardware=hardware),name)
        override suspend fun legacyProof(endpoint:Endpoint,expected:HardwareId?):String?=null
    }
    @Test fun waitsAtRealMilestonesAndOnlyValidatedIdentityReaches100()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined);val link=Link();val flow=ProvisioningFlow(scope,gateway(),{link})
        try {
            flow.select(SetupCandidate("ble","Lamp",hardware,null,peer))
            assertEquals(0,flow.ui.value.connection?.percent)
            for((stage,value) in listOf(BleConnectionMilestone.LINK_READY to 25,BleConnectionMilestone.SERVICES_READY to 45,BleConnectionMilestone.PROTOCOL_READY to 55,BleConnectionMilestone.SECURE_READY to 85)) {
                link.report(stage);assertEquals(value,flow.ui.value.connection?.percent)
                delay(5);assertEquals(value,flow.ui.value.connection?.percent)
                assertFalse(flow.ui.value.connection!!.complete)
            }
            link.result.complete(Json.parseToJsonElement("""{"mac":"02:00:00:00:00:44"}""").jsonObject)
            withTimeout(1000){flow.ui.first{it.phase==SetupPhase.DONE}}
            assertTrue(flow.ui.value.connection!!.complete);assertNotNull(flow.takeCompletion())
        }finally{flow.close();scope.cancel()}
    }
    @Test fun failureFreezesProgressAndOldCallbacksCannotAffectRetry()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined);val links=mutableListOf<Link>()
        val flow=ProvisioningFlow(scope,gateway(),{Link().also{links+=it}})
        try {
            val candidate=SetupCandidate("ble","Lamp",hardware,null,peer)
            flow.select(candidate);val first=links.last();first.report(BleConnectionMilestone.SECURE_READY)
            first.result.complete(Json.parseToJsonElement("""{"mac":"02:00:00:00:00:46"}""").jsonObject)
            withTimeout(1000){flow.ui.first{it.error!=null&&!it.busy}}
            assertEquals(85,flow.ui.value.connection?.percent);assertTrue(flow.ui.value.connection!!.failed)
            flow.select(candidate);val second=links.last();first.report(BleConnectionMilestone.SECURE_READY)
            assertEquals(0,flow.ui.value.connection?.percent)
            second.report(BleConnectionMilestone.LINK_READY);assertEquals(25,flow.ui.value.connection?.percent)
            flow.close();second.report(BleConnectionMilestone.SECURE_READY)
            assertEquals(25,flow.ui.value.connection?.percent)
        }finally{flow.close();scope.cancel()}
    }
    @Test fun completedConnectionOnlyClosesAndFadesTheRing() {
        assertEquals(1f,ConnectionCompletion.ringAlpha(0f))
        assertEquals(1f,ConnectionCompletion.ringAlpha(220f))
        assertTrue(ConnectionCompletion.ringAlpha(300f) in .01f.. .99f)
        assertEquals(0f,ConnectionCompletion.ringAlpha(380f))
        assertEquals(380f,ConnectionCompletion.DURATION)
    }
    @Test fun modelNeverCompletesFromAnEstimateOrRegresses() {
        val p=ConnectionProgress(1,ConnectionRoute.BLE).reached(55,"握手")
        assertEquals(p,p.reached(25,"过期回调"))
        assertFalse(p.reached(100,"估计").complete)
        assertEquals(99,p.reached(100,"估计").percent)
        assertEquals(55,p.stopped().reached(85,"迟到回调").percent)
    }
}
