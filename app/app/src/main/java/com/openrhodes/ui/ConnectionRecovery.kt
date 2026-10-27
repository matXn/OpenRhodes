package com.openrhodes.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openrhodes.RhodesApp
import com.openrhodes.data.localAddressSummary
import com.openrhodes.domain.*
import kotlinx.coroutines.*

@Composable
fun ConnectionRecovery(
    app: RhodesApp,
    lamp: Lamp,
    observation: Observation,
    management: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var editingAddress by remember(lamp.id) { mutableStateOf(false) }
    var advanced by remember(lamp.id) { mutableStateOf(false) }
    var provisioning by remember(lamp.id) { mutableStateOf(false) }
    val healthy = observation.state != null && observation.error == null && !observation.connecting
    if (healthy && !management) return
    if (observation.connecting && !management) {
        if (observation.state == null) {
            Spacer(Modifier.height(20.dp))
            Text("正在确认连接…", color = Muted, fontSize = 13.sp)
        }
        return
    }
    Spacer(Modifier.height(20.dp))
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (observation.connecting) {
            LinearProgressIndicator(
                Modifier.fillMaxWidth().height(2.dp),
                color = Gold,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Text("正在连接灯具…", color = Muted, fontSize = 13.sp)
        } else if (!healthy) {
            Text("灯具暂时无法连接", color = Cream, fontSize = 15.sp)
            Text("正在按灯具身份查找连接，请确认手机与灯具处于同一网络。", color = Muted, fontSize = 13.sp)
        } else Text("连接管理", color = Muted, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                enabled = !observation.connecting,
                onClick = {
                    app.rescan()
                    scope.launch { app.session(lamp.id).refresh(force = true) }
                },
            ) {
                Text(if (healthy) "检查连接" else "重新连接")
            }
            if (!healthy) TextButton(onClick = { provisioning = true }) { Text("重新配网") }
        }
        if (!healthy && !observation.connecting)
            TextButton(
                onClick = { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) },
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                Text("打开手机 Wi-Fi 设置")
            }
    }
    TextButton(onClick = { advanced = !advanced }) { Text("高级连接信息") }
    if (advanced) {
        observation.error?.let { Text(it, color = Muted, fontSize = 12.sp) }
        Text(localAddressSummary(context), color = Muted, fontSize = 13.sp)
        TextButton(onClick = { editingAddress = true }) { Text("手动更新地址") }
    }
    if (provisioning)
        BluetoothProvision(app, lamp, close = { provisioning = false }) { provisioning = false }
    if (editingAddress) AddressDialog(app, lamp) { editingAddress = false }
}

@Composable
private fun AddressDialog(app: RhodesApp, lamp: Lamp, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) close() },
        title = { Text("更新灯具地址") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("输入灯具当前 IP。验证 MAC 后更新原设备，小组件绑定会保留。", color = Muted)
                OutlinedTextField(
                    address,
                    { address = it },
                    enabled = !busy,
                    label = { Text("IP 或 rhodeslight.local") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && address.isNotBlank(),
                onClick = {
                    val endpoint = Endpoint.parse(address)
                    if (endpoint == null) {
                        error = "请输入有效的局域网地址"
                        return@TextButton
                    }
                    busy = true
                    scope.launch {
                        try {
                            val expected = requireNotNull(lamp.hardware) { "请先在设备信息中确认旧记录的硬件身份" }
                            val state = app.api.read(endpoint)
                            require(state.hardware == expected) { "此地址对应另一盏灯，原记录未更改" }
                            app.discovered(endpoint, state)
                            close()
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (failure: Exception) {
                            error = failure.message ?: "无法连接，请检查网络和地址"
                        } finally {
                            busy = false
                        }
                    }
                },
            ) {
                Text(if (busy) "正在验证" else "验证并更新")
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = close) { Text("取消") } },
    )
}
