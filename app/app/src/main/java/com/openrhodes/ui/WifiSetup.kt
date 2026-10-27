package com.openrhodes.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.openrhodes.RhodesApp
import com.openrhodes.domain.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

@Composable
fun WifiSetup(app: RhodesApp, lamp: Lamp) {
    val scope = rememberCoroutineScope()
    var endpoint by remember(lamp.id) { mutableStateOf<Endpoint?>(null) }
    var state by remember { mutableStateOf<JsonObject?>(null) }
    var networks by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var ssid by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var bluetoothSetup by remember { mutableStateOf(false) }
    var hotspotSettings by remember { mutableStateOf(false) }
    var manual by remember { mutableStateOf(false) }
    val connecting = state?.get("connecting")?.jsonPrimitive?.booleanOrNull == true

    suspend fun target(): Endpoint {
        val session = app.session(lamp.id)
        session.refresh(force = true)
        val current = session.observation.value
        check(current.error == null && current.state != null) { current.error ?: "灯具暂时无法连接" }
        val found = requireNotNull(current.endpoint) { "灯具暂时无法连接" }
        require(lamp.hardware != null && current.state.hardware == lamp.hardware) {
            "灯具身份未确认"
        }
        endpoint = found
        return found
    }
    fun action(path: String, fields: Map<String, String>) {
        busy = true
        scope.launch {
            try {
                state = app.api.json(target(), path, fields)
                message = null
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                message = e.message
            } finally {
                busy = false
            }
        }
    }
    LaunchedEffect(lamp.id) {
        while (isActive) {
            try {
                if (!busy && !scanning) {
                    state = app.api.json(target(), "/api/wifi/setup", mapOf("action" to "hold"))
                    message = null
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                message = "连接暂时中断，正在查找灯具的新地址；请确认手机已连接目标 Wi-Fi"
            }
            delay(10000)
        }
    }
    LaunchedEffect(connecting) {
        if (connecting) {
            val completed =
                withTimeoutOrNull(60000) {
                    while (isActive) {
                        delay(1500)
                        try {
                            state = app.api.json(target(), "/api/state")
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (e: Exception) {
                            message = "灯具正在切换网络，请将手机连接到目标 Wi-Fi；发现新地址后会恢复"
                        }
                    }
                }
            if (completed == null) message = "等待连接超时，请检查目标 Wi-Fi 或连接灯具热点重新配网"
        }
    }
    DisposableEffect(lamp.id) {
        onDispose {
            endpoint?.let { host ->
                app.scope.launch {
                    try {
                        app.api.json(host, "/api/wifi/setup", mapOf("action" to "release"))
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        /* Firmware lease also expires after 45 seconds. */
                    }
                }
            }
        }
    }
    Button(onClick = { bluetoothSetup = true }) { Text("添加 Wi-Fi 网络") }
    if (bluetoothSetup)
        BluetoothProvision(app, lamp, close = { bluetoothSetup = false }) {
            bluetoothSetup = false
        }
    state?.let { info ->
        Text(
            if (connecting) "正在连接 ${info["target_ssid"]?.jsonPrimitive?.contentOrNull.orEmpty()}"
            else "当前网络：${info["ssid"]?.jsonPrimitive?.contentOrNull.orEmpty()}",
            color = Muted,
            modifier = Modifier.padding(vertical = 12.dp),
        )
        info["wifi_error"]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.takeIf { it.isNotEmpty() }
            ?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    Text("已保存网络", color = Muted, modifier = Modifier.padding(top = 20.dp))
    val profiles = state?.get("wifi_profiles")?.jsonArray.orEmpty()
    profiles.forEachIndexed { index, item ->
        val name = item.jsonObject["ssid"]?.jsonPrimitive?.content.orEmpty()
        Row(Modifier.fillMaxWidth()) {
            TextButton(
                enabled = !busy && !connecting,
                onClick = { action("/api/wifi", mapOf("ssid" to name, "use_saved" to "1")) },
                modifier = Modifier.weight(1f),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text(
                    "${index+1}. $name",
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            if (index > 0)
                TextButton(
                    enabled = !busy && !connecting,
                    onClick = {
                        action(
                            "/api/wifi/profiles",
                            mapOf(
                                "ssid" to name,
                                "action" to "move",
                                "priority" to index.toString(),
                            ),
                        )
                    },
                ) {
                    Text("上移")
                }
        }
    }
    SettingsEntry("灯具配网热点", "隐藏热点名称、查看连接信息") { hotspotSettings = !hotspotSettings }
    if (hotspotSettings) {
        if (state?.containsKey("ap_hidden") == true) {
            AccessPointSettings(
                app = app,
                lampId = lamp.id,
                enabled = !busy && !scanning && !connecting,
                target = { target() },
                onBusy = { busy = it },
            )
        } else if (state != null) {
            Text(
                "升级灯具固件后可隐藏配网热点名称。",
                color = Muted,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        }
    }
    SettingsEntry("其他配网方式", "通过当前连接扫描并添加网络") { manual = !manual }
    if (manual) {
        TextButton(
            enabled = !busy && !scanning && !connecting,
            onClick = {
                scanning = true
                scope.launch {
                    try {
                        val host = target()
                        var scan = app.api.json(host, "/api/scan", emptyMap())
                        withTimeout(15000) {
                            while (scan["scanning"]?.jsonPrimitive?.booleanOrNull == true) {
                                delay(450)
                                scan = app.api.json(host, "/api/scan")
                            }
                        }
                        networks = scan["networks"]?.jsonArray?.map { it.jsonObject }.orEmpty()
                        message = if (networks.isEmpty()) "未发现网络，可重新扫描或输入隐藏网络" else null
                    } catch (cancel: TimeoutCancellationException) {
                        message = "扫描超时，请重试"
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (e: Exception) {
                        message = e.message
                    } finally {
                        scanning = false
                    }
                }
            },
        ) {
            Text(if (scanning) "正在扫描" else "扫描附近网络")
        }
        networks.forEach { network ->
            val networkSsid = network["ssid"]?.jsonPrimitive?.contentOrNull.orEmpty()
            TextButton(
                enabled = !busy && !connecting,
                onClick = {
                    ssid = networkSsid
                    password = ""
                },
            ) {
                Text(
                    "$networkSsid   ${network["rssi"]?.jsonPrimitive?.contentOrNull.orEmpty()} dBm"
                )
            }
        }
        OutlinedTextField(
            ssid,
            { ssid = it },
            enabled = !busy && !connecting,
            label = { Text("选择网络或输入隐藏 SSID") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            password,
            { password = it },
            enabled = !busy && !connecting,
            label = { Text("密码") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(
            enabled = !busy && !connecting && ssid.isNotBlank(),
            onClick = {
                action("/api/wifi", mapOf("ssid" to ssid, "password" to password))
                password = ""
            },
        ) {
            Text("连接并优先保存")
        }
    }
    message?.let { Text(it, color = Gold, modifier = Modifier.padding(vertical = 12.dp)) }
}
