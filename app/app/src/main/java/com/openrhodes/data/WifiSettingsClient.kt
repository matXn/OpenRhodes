package com.openrhodes.data

import com.openrhodes.domain.Endpoint
import com.openrhodes.domain.HardwareId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*

/** 网络管理独立验证局域网通道，不切换灯光控制通道，也不进入蓝牙配网。 */
class WifiSettingsClient(
    private val api: LocalApi,
    private val hardware: HardwareId?,
    private val candidates: () -> List<Endpoint>,
) {
    private val lock = Mutex()
    private var verified: Endpoint? = null

    private suspend fun target(): Endpoint {
        val expected = requireNotNull(hardware) { "灯具身份尚未确认，暂时无法管理网络" }
        val options = (listOfNotNull(verified) + candidates()).filterNot { it.isBluetooth }.distinct()
        var mismatch = false
        for (endpoint in options) {
            try {
                val state = api.read(endpoint)
                if (state.hardware != expected) {
                    mismatch = true
                    continue
                }
                verified = endpoint
                return endpoint
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                // 地址只是线索，只有匹配完整 MAC 的响应才允许后续操作。
            }
        }
        verified = null
        error(if (mismatch) "网络上的设备身份不匹配，请确认连接的是这盏灯"
            else "请让手机连接灯具所在的 Wi-Fi 或配网热点，再读取网络设置")
    }

    suspend fun read(): JsonObject = lock.withLock {
        api.json(target(), "/api/wifi/setup", mapOf("action" to "hold"))
    }

    suspend fun connect(ssid: String, password: String? = null): JsonObject = lock.withLock {
        require(ssid.isNotBlank()) { "请选择网络或填写隐藏网络名称" }
        api.json(target(), "/api/wifi", buildMap {
            put("ssid", ssid)
            if (password == null) put("use_saved", "1") else put("password", password)
        })
    }

    suspend fun move(ssid: String, priority: Int): JsonObject = lock.withLock {
        require(priority >= 1)
        api.json(target(), "/api/wifi/profiles", mapOf(
            "ssid" to ssid, "action" to "move", "priority" to priority.toString(),
        ))
    }

    suspend fun forget(ssid: String): JsonObject = lock.withLock {
        api.json(target(), "/api/wifi/profiles", mapOf("ssid" to ssid, "action" to "forget"))
    }

    suspend fun scan(): List<JsonObject> = lock.withLock {
        val endpoint = target()
        var result = api.json(endpoint, "/api/scan", emptyMap())
        withTimeout(15000) {
            while (result["scanning"]?.jsonPrimitive?.booleanOrNull == true) {
                delay(450)
                result = api.json(endpoint, "/api/scan")
            }
        }
        result["networks"]?.jsonArray.orEmpty().map { it.jsonObject }
            .filter { !it["ssid"]?.jsonPrimitive?.contentOrNull.isNullOrBlank() }
            .distinctBy { it["ssid"]?.jsonPrimitive?.content }
    }

    suspend fun accessPoint(hidden: Boolean? = null): SetupAccessPoint = lock.withLock {
        api.setupAccessPoint(target(), hidden)
    }

    suspend fun release() = lock.withLock {
        verified?.let { api.json(it, "/api/wifi/setup", mapOf("action" to "release")) }
        Unit
    }
}
