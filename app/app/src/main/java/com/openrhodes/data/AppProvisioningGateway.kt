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

    override suspend fun enroll(
        hardware: HardwareId,
        endpoint: Endpoint,
        state: LampState?,
    ): String {
        app.awaitReady()
        val existing = app.catalog.lamps.value.firstOrNull { it.hardware == hardware }
        if (existing != null)
            app.catalog.enroll(existing.copy(endpoints = listOf(endpoint) + existing.endpoints))
        else app.catalog.add(endpoint, LampState(false, 0, hardware = hardware), "RhodesLight")
        val id = requireNotNull(app.catalog.lamps.value.firstOrNull { it.hardware == hardware }).id
        if (state != null) app.session(id).acceptDiscovered(endpoint, state)
        return id
    }

    override suspend fun reachable(id: String): Boolean {
        return try {
            val session = app.session(id)
            session.refresh(force = true)
            val observation = session.observation.value
            observation.state?.hardware == app.catalog.find(id)?.hardware &&
                observation.state != null &&
                observation.error == null &&
                !observation.connecting
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            false
        }
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
