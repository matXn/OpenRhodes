package com.openrhodes.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openrhodes.data.SetupAccessPoint
import com.openrhodes.data.WifiSettingsClient
import kotlinx.coroutines.*

@Composable
fun AccessPointSettings(
    client: WifiSettingsClient,
    lampId: String,
    enabled: Boolean,
    onBusy: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var accessPoint by remember(lampId) { mutableStateOf<SetupAccessPoint?>(null) }
    var working by remember(lampId) { mutableStateOf(false) }
    var error by remember(lampId) { mutableStateOf<String?>(null) }
    var copied by remember(lampId) { mutableStateOf<String?>(null) }
    var showPassword by remember(lampId) { mutableStateOf(false) }

    suspend fun request(hidden: Boolean? = null) {
        if (working) return
        working = true
        onBusy(true)
        error = null
        copied = null
        try {
            accessPoint = client.accessPoint(hidden)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            error = failure.message ?: "热点设置暂时无法读取，请重试"
        } finally {
            working = false
            onBusy(false)
        }
    }

    fun copy(label: String, value: String, sensitive: Boolean = false) {
        val clip = ClipData.newPlainText(label, value)
        if (sensitive) clip.description.extras = PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
        copied = "已复制$label"
    }
    LaunchedEffect(client, lampId) { request() }

    val ap = accessPoint
    if (ap != null) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("隐藏热点名称")
                Text(if (ap.hidden) "名称已隐藏，热点仍可连接" else "在附近网络列表中显示名称",
                    color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
            }
            Switch(checked = ap.hidden, enabled = enabled && !working,
                onCheckedChange = { hidden -> scope.launch { request(hidden) } })
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text("热点名称", color = Muted, fontSize = 12.sp)
                Text(ap.ssid, modifier = Modifier.padding(top = 5.dp))
            }
            TextButton(onClick = { copy("热点名称", ap.ssid) }) { Text("复制") }
        }
        Column {
            Text("热点密码", color = Muted, fontSize = 12.sp)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (showPassword) ap.password.ifEmpty { "无密码" } else "••••••••", Modifier.weight(1f))
                TextButton(onClick = { showPassword = !showPassword }) { Text(if (showPassword) "隐藏" else "显示") }
                TextButton(onClick = { copy("热点密码", ap.password, sensitive = true) }) { Text("复制密码") }
            }
        }
        if (ap.hidden) Text("在手机 Wi-Fi 设置中手动添加网络，选择隐藏网络及 WPA2-Personal。",
            color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
    }
    if (working) Text("正在更新热点设置…", color = Muted, fontSize = 13.sp)
    error?.let {
        Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
        TextButton(enabled = enabled && !working, onClick = { scope.launch { request() } }) { Text("重新读取") }
    }
    copied?.let { Text(it, color = Gold, fontSize = 12.sp) }
}
