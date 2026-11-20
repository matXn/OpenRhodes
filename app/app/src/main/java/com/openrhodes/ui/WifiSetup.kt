package com.openrhodes.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openrhodes.RhodesApp
import com.openrhodes.data.WifiSettingsClient
import com.openrhodes.domain.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** 统一连接页；网络操作仅走经过 MAC 验证的 HTTP 通道。 */
@Composable
fun WifiSetup(app: RhodesApp, lamp: Lamp) {
    val scope = rememberCoroutineScope()
    val session = remember(lamp.id, lamp.hardware) { app.session(lamp.id) }
    val observation by session.observation.collectAsStateWithLifecycle()
    val client = remember(lamp.id, lamp.hardware) {
        WifiSettingsClient(app.api, lamp.hardware) {
            val current = session.observation.value
            (listOfNotNull(
                current.state?.deviceIp?.takeIf { it.isNotBlank() }?.let(Endpoint::parse),
                lamp.hardware?.let { app.nearby.value[it] },
                current.endpoint?.takeUnless { it.isBluetooth },
            ) + app.catalog.find(lamp.id)?.endpoints.orEmpty() +
                listOfNotNull(Endpoint.parse("192.168.4.1")))
        }
    }
    var state by remember(lamp.id) { mutableStateOf<JsonObject?>(null) }
    var message by remember(lamp.id) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var hidden by remember { mutableStateOf(false) }
    var networks by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var ssid by remember { mutableStateOf<String?>(null) }
    var password by remember { mutableStateOf("") }
    var forgetting by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<String?>(null) }
    var pendingAt by remember { mutableLongStateOf(0L) }
    val connecting = pending != null || state?.get("connecting")?.jsonPrimitive?.booleanOrNull == true

    fun accept(info: JsonObject) {
        state = info
        val connected = info["connected"]?.jsonPrimitive?.booleanOrNull == true
        val network = info["ssid"]?.jsonPrimitive?.contentOrNull
        val failure = info["wifi_error"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        if (pending != null && ((connected && network == pending) || failure != null)) pending = null
        message = null
    }
    fun action(connectTo: String? = null, request: suspend () -> JsonObject) {
        if (busy || scanning || connecting) return
        busy = true
        message = null
        scope.launch {
            try {
                val response = request()
                if (response.containsKey("on")) accept(response)
                if (connectTo != null) {
                    pending = connectTo
                    pendingAt = SystemClock.elapsedRealtime()
                    adding = false
                    password = ""
                    app.rescan()
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                message = failure.message ?: "网络设置暂时无法更新，请重试"
            } finally {
                busy = false
            }
        }
    }
    fun scan() {
        if (busy || scanning || connecting) return
        scanning = true
        message = null
        scope.launch {
            try {
                networks = client.scan()
                if (networks.isEmpty()) message = "未发现网络，可重新扫描或添加隐藏网络"
            } catch (timeout: TimeoutCancellationException) {
                message = "扫描超时，请重试"
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                message = failure.message ?: "暂时无法扫描网络"
            } finally {
                scanning = false
            }
        }
    }
    LaunchedEffect(client) {
        while (isActive) {
            if (!busy && !scanning) {
                try { accept(client.read()) }
                catch (cancel: CancellationException) { throw cancel }
                catch (failure: Exception) { message = failure.message ?: "暂时无法读取网络设置" }
            }
            if (pending != null && SystemClock.elapsedRealtime() - pendingAt > 60000) {
                pending = null
                message = "等待联网超时，请检查目标 Wi-Fi 或连接灯具配网热点"
            }
            delay(if (pending != null || state?.get("connecting")?.jsonPrimitive?.booleanOrNull == true) 1500 else 10000)
        }
    }
    DisposableEffect(client) {
        onDispose {
            app.scope.launch {
                try { client.release() }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { /* 灯具也会自动释放到期的设置租约。 */ }
            }
        }
    }

    SettingsSection(SettingsGlyph.Bluetooth, "蓝牙")
    val direct = observation.endpoint?.isBluetooth ?: (lamp.endpoints.firstOrNull()?.isBluetooth == true)
    val bluetoothStatus = when {
        direct && observation.connecting -> "正在连接"
        direct && observation.state != null && observation.error == null -> "已连接"
        direct -> "未连接"
        else -> "未使用"
    }
    Text(bluetoothStatus, color = Muted, fontSize = 14.sp)
    HorizontalDivider(Modifier.padding(top = 24.dp), color = MaterialTheme.colorScheme.outlineVariant)

    SettingsSection(SettingsGlyph.Wifi, "Wi-Fi 网络")
    val currentName = state?.get("ssid")?.jsonPrimitive?.contentOrNull.orEmpty()
    val joined = state?.get("connected")?.jsonPrimitive?.booleanOrNull == true
    Text(when {
        connecting -> "正在连接 ${pending ?: state?.get("target_ssid")?.jsonPrimitive?.contentOrNull.orEmpty()}"
        joined && currentName.isNotBlank() -> currentName
        state != null -> "尚未连接网络"
        else -> "正在读取网络设置…"
    }, fontSize = 16.sp)
    if (joined && !connecting) Text("已连接", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
    state?.get("wifi_error")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let {
        Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
    }
    if (state != null) {
        Text("已保存网络", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
        val profiles = state?.get("wifi_profiles")?.jsonArray.orEmpty()
        if (profiles.isEmpty()) Text("暂无保存的网络", color = Muted, fontSize = 13.sp)
        profiles.forEach { item ->
            val name = item.jsonObject["ssid"]?.jsonPrimitive?.contentOrNull.orEmpty()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    enabled = !busy && !scanning && !connecting,
                    onClick = { action(connectTo = name) { client.connect(name) } },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 10.dp),
                ) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(name, color = Cream, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                TextButton(enabled = !busy && !scanning && !connecting, onClick = { forgetting = name }) { Text("忘记") }
            }
        }
    }
    if (!adding) TextButton(
        enabled = !busy && !scanning && !connecting,
        contentPadding = PaddingValues(vertical = 12.dp),
        onClick = { adding = true; ssid = null; hidden = false; password = ""; scan() },
    ) { Text("添加网络") }
    ExpandingSection(adding) {
        Spacer(Modifier.height(14.dp))
        if (scanning) {
            ConnectionStatus(null, "正在扫描附近网络…", compact = true)
        }
        networks.forEach { network ->
            val name = network["ssid"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val rssi = network["rssi"]?.jsonPrimitive?.intOrNull ?: -100
            TextButton(
                enabled = !busy && !scanning && !connecting,
                onClick = { ssid = name; password = ""; hidden = false },
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 10.dp),
            ) {
                Text(name, Modifier.weight(1f), color = Cream, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (rssi >= -60) "信号强" else if (rssi >= -75) "信号良好" else "信号较弱", color = Muted, fontSize = 12.sp)
            }
        }
        Row {
            TextButton(enabled = !scanning && !busy, onClick = { scan() }) { Text("重新扫描") }
            TextButton(enabled = !scanning && !busy, onClick = { hidden = true; ssid = ""; password = "" }) { Text("隐藏网络") }
        }
        ExpandingSection(hidden) {
            ConnectionField(ssid.orEmpty(), { ssid = it }, enabled = !busy && !connecting,
                label = { Text("网络名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        if (!hidden) ssid?.let { Text(it, modifier = Modifier.padding(vertical = 12.dp), color = Cream) }
        ExpandingSection(ssid != null) {
            Spacer(Modifier.height(10.dp))
            ConnectionField(password, { password = it }, enabled = !busy && !connecting,
                label = { Text("Wi-Fi 密码") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth())
            TextButton(enabled = !busy && !scanning && !connecting && !ssid.isNullOrBlank(),
                onClick = { ssid?.let { name -> action(connectTo = name) { client.connect(name, password) } } }) { Text("连接并保存") }
        }
        TextButton(enabled = !busy, onClick = { adding = false; password = ""; ssid = null }) { Text("取消添加") }
    }
    if (busy) Text("正在更新网络设置…", color = Muted, fontSize = 13.sp)
    message?.let { Text(it, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 14.dp)) }

    HorizontalDivider(Modifier.padding(top = 24.dp), color = MaterialTheme.colorScheme.outlineVariant)
    SettingsSection(SettingsGlyph.Hotspot, "配网热点")
    if (state?.containsKey("ap_hidden") == true) {
        AccessPointSettings(client = client, lampId = lamp.id,
            enabled = !busy && !scanning && !connecting, onBusy = { busy = it })
    } else Text(
        if (state == null) "连接灯具所在 Wi-Fi 或配网热点后，可查看热点设置。"
        else "当前固件尚不支持在 App 中管理配网热点。",
        color = Muted, fontSize = 13.sp, lineHeight = 20.sp,
    )

    forgetting?.let { name ->
        AlertDialog(onDismissRequest = { forgetting = null },
            title = { DialogMotion(); Text("忘记网络？") }, text = { Text("移除灯具保存的「$name」网络资料。") },
            confirmButton = { TextButton(onClick = { forgetting = null; action { client.forget(name) } }) { Text("忘记") } },
            dismissButton = { TextButton(onClick = { forgetting = null }) { Text("取消") } })
    }
}
