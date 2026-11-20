package com.openrhodes.data

import com.openrhodes.RhodesApp
import com.openrhodes.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

class AppProvisioningGateway(private val app: RhodesApp) : ProvisioningGateway {
    override val nearby
        get() = app.nearby

    override fun name(hardware: HardwareId) =
        app.catalog.lamps.value.firstOrNull { it.hardware == hardware }?.name

    override fun rescan() = app.rescan()

    override suspend fun read(endpoint: Endpoint) = app.api.read(endpoint)

    override suspend fun enroll(hardware: HardwareId, endpoint: Endpoint, state: LampState, name: String): AddCompletion {
        app.awaitReady()
        require(state.hardware == hardware)
        return app.completeConnectedAdd(endpoint, state, name)
    }

    override suspend fun adoptBluetooth(link: ProvisionLink, peer: BlePeer, hardware: HardwareId, name: String): AddCompletion =
        app.completeBluetooth(peer, hardware, link, name)

    override suspend fun verifyBluetooth(link: ProvisionLink, hardware: HardwareId): LampState {
        val raw = link.request(byteArrayOf(0x10))
        BleProvisionProtocol.identity(raw, hardware)
        return StateCodec.decode(raw.toString(), android.os.SystemClock.elapsedRealtime())
    }

    override suspend fun legacyProof(endpoint: Endpoint, expected: HardwareId?): String? {
        val state = app.api.read(endpoint)
        require(state.hardware != null && (expected == null || state.hardware == expected)) {
            "该热点不属于选中的灯具"
        }
        val info = app.api.json(endpoint, "/api/wifi/ble", emptyMap())
        check(info["ready"]?.jsonPrimitive?.booleanOrNull == true) { "蓝牙配网尚未开启" }
        return info["pop"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    }
}
