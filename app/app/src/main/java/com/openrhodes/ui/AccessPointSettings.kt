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
import com.openrhodes.RhodesApp
import com.openrhodes.data.SetupAccessPoint
import com.openrhodes.domain.Endpoint
import kotlinx.coroutines.*

@Composable
fun AccessPointSettings(
    app: RhodesApp,
    lampId: String,
    enabled: Boolean,
    target: suspend () -> Endpoint,
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
            accessPoint = app.api.setupAccessPoint(target(), hidden)
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
        if (sensitive) {
            clip.description.extras =
                PersistableBundle().apply {
                    putBoolean("android.content.extra.IS_SENSITIVE", true)
                }
        }
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(
            clip
        )
        copied = "已复制$label"
    }

    LaunchedEffect(lampId) { request() }

    HorizontalDivider(Modifier.padding(vertical = 20.dp))
    Text("灯具配网热点", style = MaterialTheme.typography.titleMedium)
    val ap = accessPoint
    if (ap != null) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("隐藏 SSID")
                Text(
                    if (ap.hidden) "名称已隐藏，热点仍可连接" else "在附近网络列表中显示名称",
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = ap.hidden,
                enabled = enabled && !working,
                onCheckedChange = { hidden -> scope.launch { request(hidden) } },
            )
        }
        if (ap.hidden) {
            Text(
                "在手机 Wi-Fi 设置中手动添加网络，选择隐藏网络及 WPA2-Personal。",
                color = Muted,
                style = MaterialTheme.typography.bodySmall,
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("SSID", color = Muted, style = MaterialTheme.typography.labelMedium)
                    Text(ap.ssid)
                }
                TextButton(onClick = { copy("SSID", ap.ssid) }) { Text("复制 SSID") }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("热点密码", color = Muted, style = MaterialTheme.typography.labelMedium)
                    Text(if (showPassword) ap.password.ifEmpty { "无密码" } else "••••••••")
                }
                TextButton(onClick = { showPassword = !showPassword }) {
                    Text(if (showPassword) "隐藏" else "显示")
                }
                TextButton(onClick = { copy("密码", ap.password, sensitive = true) }) {
                    Text("复制密码")
                }
            }
            Text("配网页：${ap.ip}", color = Muted, style = MaterialTheme.typography.bodySmall)
        } else {
            Text(
                "隐藏后可在这里复制热点名称和密码；关闭“隐藏 SSID”即可恢复广播。",
                color = Muted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    if (working) Text("正在更新热点设置…", color = Muted)
    error?.let {
        Text(it, color = MaterialTheme.colorScheme.error)
        TextButton(enabled = enabled && !working, onClick = { scope.launch { request() } }) {
            Text("重新读取热点设置")
        }
    }
    copied?.let { Text(it, color = Gold, style = MaterialTheme.typography.bodySmall) }
    HorizontalDivider(Modifier.padding(vertical = 20.dp))
}
