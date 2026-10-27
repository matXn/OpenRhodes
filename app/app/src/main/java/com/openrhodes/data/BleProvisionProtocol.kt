package com.openrhodes.data

import com.openrhodes.domain.*
import java.util.UUID
import kotlinx.serialization.json.*

object BleProvisionProtocol {
    class IncompleteVersion : IllegalStateException("蓝牙版本信息不完整，请重新连接灯具")

    val service: UUID = UUID.fromString("95e7b36c-43f7-4ad0-9e6f-104b0cb54e10")
    val session: UUID = UUID.fromString("95e7ff51-43f7-4ad0-9e6f-104b0cb54e10")
    val version: UUID = UUID.fromString("95e7ff52-43f7-4ad0-9e6f-104b0cb54e10")
    val config: UUID = UUID.fromString("95e7ff53-43f7-4ad0-9e6f-104b0cb54e10")

    /** No-code mode must be explicitly advertised; legacy devices still require their proof. */
    fun sessionProof(version: String, legacyProof: String? = null): String? {
        require(version.length <= 512) { "蓝牙版本响应过长" }
        val info =
            try {
                Json.parseToJsonElement(version) as? JsonObject ?: throw IncompleteVersion()
            } catch (_: IllegalArgumentException) {
                throw IncompleteVersion()
            }["rhodes"]
                as? JsonObject
        require(
            (info?.get("sec_ver") as? JsonPrimitive)?.intOrNull == 1 &&
                (info["ver"] as? JsonPrimitive)?.intOrNull == 1
        ) {
            "设备不支持加密配网"
        }
        if (
            (info["pop_required"] as? JsonPrimitive)?.let {
                !it.isString && it.booleanOrNull == false
            } == true
        )
            return null
        return legacyProof?.takeIf { it.isNotBlank() }
            ?: error("此灯具仍是旧版配网固件，请更新固件，或连接灯具热点后使用旧固件兼容入口")
    }

    /**
     * Older BLE transports may leave a partial read pending across sessions. Repeated version reads
     * are safe: they cannot change Wi-Fi or advance the secure stream.
     */
    suspend fun negotiateVersion(read: suspend () -> String, legacyProof: String? = null): String? {
        repeat(3) {
            try {
                return sessionProof(read(), legacyProof)
            } catch (_: IncompleteVersion) {
                // A fresh read drains the old transport's remaining-byte cursor.
            }
        }
        throw IncompleteVersion()
    }

    fun connectSaved(ssid: String): ByteArray {
        val name = ssid.toByteArray(Charsets.UTF_8)
        require(name.size in 1..32 && '\u0000' !in ssid) { "Wi-Fi 名称需为 1–32 字节" }
        return byteArrayOf(6, name.size.toByte()) + name
    }

    fun connect(ssid: String, password: String): ByteArray {
        val name = ssid.toByteArray(Charsets.UTF_8)
        val secret = password.toByteArray(Charsets.UTF_8)
        require(name.size in 1..32 && '\u0000' !in ssid) { "Wi-Fi 名称需为 1–32 字节" }
        require(
            secret.isEmpty() ||
                secret.size in 8..63 ||
                (secret.size == 64 && password.all { it in "0123456789abcdefABCDEF" })
        ) {
            "密码需为 8–63 字节，或 64 位十六进制密钥"
        }
        require('\u0000' !in password) { "密码包含无效字符" }
        return byteArrayOf(4, name.size.toByte(), secret.size.toByte()) + name + secret
    }

    fun decode(bytes: ByteArray): JsonObject {
        require(bytes.size <= 512) { "蓝牙配网响应过长" }
        val obj = parseObject(bytes.toString(Charsets.UTF_8), "蓝牙响应不完整，请重新连接灯具")
        obj["error"]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.takeIf { it.isNotEmpty() }
            ?.let {
                // Device connection errors are state, request errors do not have a version.
                if (!obj.containsKey("v") || obj["status"] == null) error(it)
            }
        require(obj["v"]?.jsonPrimitive?.intOrNull == 1) { "不支持的蓝牙配网版本" }
        return obj
    }

    private fun parseObject(text: String, message: String): JsonObject =
        try {
            Json.parseToJsonElement(text) as? JsonObject ?: error(message)
        } catch (_: IllegalArgumentException) {
            // Parser diagnostics include the raw payload; keep them out of the UI.
            throw IllegalStateException(message)
        }

    fun identity(state: JsonObject, expected: HardwareId? = null): HardwareId {
        val id =
            requireNotNull(HardwareId.parse(state["mac"]?.jsonPrimitive?.contentOrNull)) {
                "蓝牙设备未返回有效 MAC"
            }
        require(expected == null || expected == id) { "蓝牙设备与选中的灯具不一致" }
        return id
    }
}
