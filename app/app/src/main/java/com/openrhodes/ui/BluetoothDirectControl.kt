package com.openrhodes.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.openrhodes.RhodesApp
import com.openrhodes.data.*
import com.openrhodes.domain.*
import kotlinx.coroutines.*

/** 仅在用户选择直连时申请权限；不要求 Wi-Fi，也不进入 SSID 表单。 */
@Composable
fun BluetoothDirectControl(
    app: RhodesApp,
    lamp: Lamp? = null,
    initialPeer: BlePeer? = null,
    close: () -> Unit,
    connected: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var peers by remember { mutableStateOf<List<BlePeer>>(emptyList()) }
    var scanJob by remember { mutableStateOf<Job?>(null) }
    var connectJob by remember { mutableStateOf<Job?>(null) }
    val permissions = remember {
        if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    fun permitted() = permissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
    fun connect(peer: BlePeer) {
        if (busy) return
        busy = true
        error = null
        connectJob = scope.launch {
            try {
                scanJob?.cancelAndJoin()
                val id = withTimeout(22000) { app.connectBluetooth(peer, lamp?.hardware) }
                connected(id)
            } catch (cancel: TimeoutCancellationException) {
                error = "蓝牙连接超时，请靠近灯具后重试"
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                error = failure.message ?: "无法连接灯具蓝牙"
            } finally {
                busy = false
            }
        }
    }
    fun search() {
        if (busy) return
        error = null
        scanJob?.cancel()
        scanJob = scope.launch {
            searching = true
            val scanner = BleProvisionClient(context.applicationContext)
            try {
                scanner.scan { peer ->
                    val suffix = lamp?.hardware?.value?.removePrefix("mac:")?.takeLast(6)
                    if (suffix == null || peer.name.endsWith(suffix, true))
                        peers =
                            (peers.filterNot { it.address == peer.address } + peer)
                                .sortedByDescending { it.rssi }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                error = failure.message ?: "无法搜索蓝牙灯具"
            } finally {
                scanner.close()
                searching = false
            }
        }
    }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (permitted()) {
                if (initialPeer != null) connect(initialPeer) else search()
            } else error = "需要允许附近设备权限才能使用 BLE"
        }
    LaunchedEffect(Unit) {
        if (!permitted()) launcher.launch(permissions)
        else if (initialPeer != null) connect(initialPeer) else search()
    }
    DisposableEffect(Unit) {
        onDispose {
            scanJob?.cancel()
            connectJob?.cancel()
        }
    }
    Dialog(
        onDismissRequest = close,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        DialogMotion()
        BackHandler(onBack = close)
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.safeDrawingPadding()) {
                LampToolbar("BLE", "无需 Wi-Fi", close, null)
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text("开关、亮度和呼吸模式均通过加密蓝牙控制。", color = Muted)
                    if (busy) {
                        ConnectionStatus(null, "正在连接灯具…", compact = true)
                    }
                    if (searching && peers.isEmpty() && !busy) ConnectionStatus(null, "正在寻找附近灯具…", compact = true)
                    if (
                        !searching &&
                            peers.isEmpty() &&
                            initialPeer == null &&
                            !busy &&
                            error == null
                    )
                        Text("未找到灯具，请靠近后重新寻找。", color = Muted)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    (if (initialPeer != null) listOf(initialPeer) else peers).forEach { peer ->
                        TextButton(
                            onClick = { connect(peer) },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(peer.name, Modifier.weight(1f), color = Cream)

                        }
                    }
                    TextButton(
                        enabled = !busy,
                        onClick = { if (permitted()) search() else launcher.launch(permissions) },
                    ) {
                        Text("重新寻找灯具")
                    }
                }
            }
        }
    }
}

@Composable
internal fun ConnectionMethod(app: RhodesApp, lamp: Lamp) {
    val scope = rememberCoroutineScope()
    var direct by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val bluetooth = lamp.endpoints.firstOrNull()?.isBluetooth == true
    Text("连接方式：" + if (bluetooth) "BLE" else "局域网", color = Muted)
    if (!bluetooth) TextButton(onClick = { direct = true }) { Text("改用 BLE") }
    else if (lamp.endpoints.any { !it.isBluetooth })
        TextButton(
            onClick = {
                scope.launch {
                    try {
                        app.preferLocal(lamp)
                    } catch (failure: Exception) {
                        error = failure.message
                    }
                }
            }
        ) {
            Text("改用局域网控制")
        }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (direct) BluetoothDirectControl(app, lamp, close = { direct = false }) { direct = false }
}
