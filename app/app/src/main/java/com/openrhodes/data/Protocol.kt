package com.openrhodes.data

import com.openrhodes.domain.*
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import okhttp3.*

data class SetupAccessPoint(
    val ssid: String,
    val password: String,
    val hidden: Boolean,
    val ip: String,
) {
    companion object {
        fun decode(obj: JsonObject): SetupAccessPoint =
            SetupAccessPoint(
                requireNotNull(obj["ssid"]?.jsonPrimitive?.contentOrNull) { "热点响应缺少名称" }
                    .also { require(it.isNotBlank()) { "热点名称为空" } },
                requireNotNull(obj["password"]?.jsonPrimitive?.contentOrNull) { "热点响应缺少密码" },
                requireNotNull(obj["hidden"]?.jsonPrimitive?.booleanOrNull) { "热点响应缺少隐藏状态" },
                requireNotNull(obj["ip"]?.jsonPrimitive?.contentOrNull) { "热点响应缺少地址" },
            )
    }

    // Keep credentials out of incidental diagnostics and exception messages.
    override fun toString(): String = "SetupAccessPoint(hidden=$hidden)"
}

object StateCodec {
    fun decode(text: String, sampledAt: Long): LampState {
        val obj = Json.parseToJsonElement(text).jsonObject
        fun number(key: String, default: Int) = obj[key]?.jsonPrimitive?.intOrNull ?: default
        val on = requireNotNull(obj["on"]?.jsonPrimitive?.booleanOrNull) { "设备响应缺少开关状态" }
        val brightness = requireNotNull(obj["brightness"]?.jsonPrimitive?.intOrNull) { "设备响应缺少亮度" }
        require(brightness in 0..100) { "设备亮度超出范围" }
        val hardware =
            sequenceOf("mac", "sta_mac", "device_id")
                .mapNotNull { HardwareId.parse(obj[it]?.jsonPrimitive?.contentOrNull) }
                .firstOrNull()
        val phase = obj["breath_phase"]?.jsonPrimitive?.doubleOrNull ?: 0.0
        require(phase.isFinite())
        val defaults = Breath()
        return LampState(
            on,
            brightness,
            obj["breathing"]?.jsonPrimitive?.booleanOrNull ?: false,
            Breath(
                    number("breath_period", defaults.periodMs),
                    number("breath_floor", defaults.floor),
                    number("breath_curve", defaults.curve),
                )
                .normalized(),
            phase,
            sampledAt,
            hardware,
            obj["ssid"]?.jsonPrimitive?.contentOrNull,
            obj["ip"]?.jsonPrimitive?.contentOrNull,
        )
    }

    fun form(command: Command, before: LampState): Pair<String, Map<String, String>> =
        when (command) {
            is Command.Level ->
                "/api/light" to mapOf("brightness" to command.percent.coerceIn(0, 100).toString())
            is Command.Power ->
                "/api/light" to
                    buildMap {
                        put("on", if (command.on) "1" else "0")
                        if (command.on)
                            put(
                                "brightness",
                                before.brightness.takeIf { it > 0 }?.toString() ?: "5",
                            )
                    }
            is Command.Mode ->
                "/api/mode" to mapOf("mode" to if (command.breathing) "breathing" else "steady")
            is Command.Parameters ->
                command.breath.normalized().let {
                    "/api/breath" to
                        mapOf(
                            "period" to it.periodMs.toString(),
                            "floor" to it.floor.toString(),
                            "curve" to it.curve.toString(),
                        )
                }
            Command.Cycle ->
                "/api/light" to mapOf("brightness" to nextBrightness(before).toString())
        }
}

class LocalApi(
    private val clock: () -> Long,
    private val http: OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(4, TimeUnit.SECONDS)
            .followRedirects(false)
            .retryOnConnectionFailure(false)
            .build(),
) : LampTransport {
    suspend fun json(
        endpoint: Endpoint,
        path: String,
        fields: Map<String, String>? = null,
        control: Boolean = false,
    ): JsonObject {
        val builder = Request.Builder().url(endpoint.url + path).header("Cache-Control", "no-store")
        if (fields != null || control) builder.header("X-Rhodes-Control", "1")
        if (fields != null)
            builder
                .header("X-Rhodes-Control", "1")
                .post(
                    FormBody.Builder()
                        .apply { fields.forEach { (key, value) -> add(key, value) } }
                        .build()
                )
        val call = http.newCall(builder.build())
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (!continuation.isCancelled) continuation.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        try {
                            val body = response.use {
                                require(it.body.contentLength() <= 262144) { "设备响应过大" }
                                val source = it.body.source()
                                source.request(262145)
                                require(source.buffer.size <= 262144) { "设备响应过大" }
                                val obj = Json.parseToJsonElement(source.readUtf8()).jsonObject
                                check(it.isSuccessful) {
                                    obj["error"]?.jsonPrimitive?.contentOrNull ?: "HTTP ${it.code}"
                                }
                                obj
                            }
                            if (!continuation.isCancelled) continuation.resume(body)
                        } catch (failure: Exception) {
                            if (!continuation.isCancelled) continuation.resumeWithException(failure)
                        }
                    }
                }
            )
        }
    }

    suspend fun setupAccessPoint(endpoint: Endpoint, hidden: Boolean? = null): SetupAccessPoint =
        SetupAccessPoint.decode(
            json(
                endpoint,
                "/api/wifi/ap",
                hidden?.let { mapOf("hidden" to if (it) "1" else "0") },
                control = true,
            )
        )

    override suspend fun read(endpoint: Endpoint): LampState {
        val started = clock()
        return StateCodec.decode(
            json(endpoint, "/api/state").toString(),
            started + (clock() - started) / 2,
        )
    }

    override suspend fun write(endpoint: Endpoint, command: Command, before: LampState): LampState {
        val (path, fields) = StateCodec.form(command, before)
        val started = clock()
        return StateCodec.decode(
            json(endpoint, path, fields).toString(),
            started + (clock() - started) / 2,
        )
    }
}
