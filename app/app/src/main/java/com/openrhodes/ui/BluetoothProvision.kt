package com.openrhodes.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.openrhodes.RhodesApp
import com.openrhodes.data.*
import com.openrhodes.domain.*
import kotlinx.coroutines.*

@Composable
fun BluetoothProvision(
    app: RhodesApp,
    lamp: Lamp? = null,
    close: () -> Unit,
    gateway: ProvisioningGateway = remember(app) { AppProvisioningGateway(app) },
    linkFactory: (() -> ProvisionLink)? = null,
    added: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val flow =
        remember(app, lamp?.id) {
            ProvisioningFlow(
                scope,
                gateway,
                linkFactory ?: { BleProvisionClient(context.applicationContext) },
                lamp?.hardware,
                reconfigure = lamp != null,
            )
        }
    val state by flow.ui.collectAsState()
    var help by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var hiddenSsid by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }
    val permissions = remember {
        if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    fun permitted() = permissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            permissionDenied = !permitted()
            flow.search(permitted())
        }
    LaunchedEffect(Unit) {
        if (lamp != null) {
            try {
                val session = app.session(lamp.id)
                session.refresh(force = true)
                val observation = session.observation.value
                if (observation.error == null && observation.state?.hardware == lamp.hardware)
                    observation.endpoint?.let { flow.reopen(it) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                /* Discovery and AP recovery still work when the old network is gone. */
            }
        }
        if (permitted()) flow.search(true)
        else {
            flow.search(false)
            permissionLauncher.launch(permissions)
        }
    }
    LaunchedEffect(state.phase, state.completedId) {
        if (state.phase == SetupPhase.DONE) state.completedId?.let(added)
    }
    LaunchedEffect(state.network?.ssid, state.phase) {
        password = ""
        showPassword = false
    }
    DisposableEffect(flow) { onDispose { flow.close() } }
    fun back() {
        if (!flow.back()) close()
    }

    Dialog(
        onDismissRequest = ::back,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BackHandler { back() }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.safeDrawingPadding().imePadding()) {
                LampToolbar(
                    title =
                        when (state.phase) {
                            SetupPhase.DISCOVER -> if (lamp == null) "添加灯具" else "连接灯具"
                            SetupPhase.NETWORKS -> "选择 Wi-Fi"
                            SetupPhase.PASSWORD -> state.network?.ssid ?: "隐藏网络"
                            SetupPhase.JOINING -> "正在连接"
                            SetupPhase.WAITING -> "灯具已联网"
                            SetupPhase.DONE -> "连接成功"
                        },
                    subtitle =
                        when (state.phase) {
                            SetupPhase.DISCOVER -> "自动发现附近的灯具"
                            SetupPhase.NETWORKS,
                            SetupPhase.PASSWORD -> "为灯具选择 2.4 GHz 网络"
                            else -> null
                        },
                    back = ::back,
                    action = null,
                )
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (state.busy || state.searching)
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    state.status?.let { Text(it, color = Muted) }
                    when (state.phase) {
                        SetupPhase.DISCOVER -> {
                            if (state.candidates.isEmpty())
                                Text("将灯具通电并放在附近；已联网的灯具也会显示在这里。", color = Muted)
                            state.candidates.forEach { candidate ->
                                CandidateEntry(candidate, state.busy) { flow.select(candidate) }
                            }
                            TextButton(enabled = !state.busy, onClick = { help = !help }) {
                                Text("没有找到灯具？")
                            }
                            if (help) {
                                Text("请确认灯具已上电、手机蓝牙已开启。新灯具的蓝牙配网窗口为上电后 5 分钟。", color = Muted)
                                Button(
                                    enabled = !state.busy,
                                    onClick = {
                                        if (permitted()) flow.search(true)
                                        else permissionLauncher.launch(permissions)
                                    },
                                ) {
                                    Text("重新寻找灯具")
                                }
                                if (permissionDenied)
                                    TextButton(
                                        onClick = {
                                            context.startActivity(
                                                Intent(
                                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                    Uri.parse("package:${context.packageName}"),
                                                )
                                            )
                                        }
                                    ) {
                                        Text("打开权限设置")
                                    }
                                TextButton(
                                    onClick = {
                                        context.startActivity(
                                            Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                                        )
                                    }
                                ) {
                                    Text("打开蓝牙设置")
                                }
                                SettingsEntry("热点配网", "连接灯具热点后，通过网页设置网络") {
                                    context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                                }
                                Text(
                                    "在系统 Wi-Fi 中连接 RhodesLight-Setup，再打开配网页。完成后返回这里重新寻找灯具。",
                                    color = Muted,
                                )
                                TextButton(
                                    onClick = {
                                        context.startActivity(
                                            Intent(
                                                Intent.ACTION_VIEW,
                                                Uri.parse("http://192.168.4.1/setup"),
                                            )
                                        )
                                    }
                                ) {
                                    Text("打开配网页")
                                }
                                TextButton(onClick = { advanced = !advanced }) { Text("高级连接选项") }
                                if (advanced) {
                                    OutlinedTextField(
                                        address,
                                        { address = it },
                                        label = { Text("IP 或 rhodeslight.local") },
                                        enabled = !state.busy,
                                        singleLine = true,
                                        keyboardOptions =
                                            KeyboardOptions(keyboardType = KeyboardType.Uri),
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Button(
                                        enabled = !state.busy && address.isNotBlank(),
                                        onClick = { flow.manual(address) },
                                    ) {
                                        Text("验证并添加")
                                    }
                                    TextButton(enabled = !state.busy, onClick = { flow.legacy() }) {
                                        Text("旧固件兼容（需连接灯具热点）")
                                    }
                                }
                            }
                        }
                        SetupPhase.NETWORKS -> {
                            state.networks.forEach { network ->
                                TextButton(
                                    enabled = !state.busy,
                                    onClick = { flow.network(network) },
                                    modifier = Modifier.fillMaxWidth(),
                                    contentPadding =
                                        PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                                    shape = RoundedCornerShape(8.dp),
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(network.ssid, color = Cream)
                                        Text(
                                            if (network.saved) "已保存 · 点按即可连接"
                                            else if (!network.secure) "开放网络 · 点按即可连接" else "需要密码",
                                            color = Muted,
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                    Text(
                                        when {
                                            network.rssi >= -60 -> "信号强"
                                            network.rssi >= -75 -> "信号中"
                                            else -> "信号弱"
                                        },
                                        color = Muted,
                                    )
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                            TextButton(enabled = !state.busy, onClick = flow::scanNetworks) {
                                Text("重新扫描")
                            }
                            TextButton(enabled = !state.busy, onClick = flow::hidden) {
                                Text("添加隐藏网络")
                            }
                        }
                        SetupPhase.PASSWORD -> {
                            if (state.network == null)
                                OutlinedTextField(
                                    hiddenSsid,
                                    { hiddenSsid = it },
                                    label = { Text("网络名称") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            OutlinedTextField(
                                password,
                                { password = it },
                                label = { Text("Wi-Fi 密码") },
                                singleLine = true,
                                enabled = !state.busy,
                                modifier = Modifier.fillMaxWidth(),
                                keyboardOptions =
                                    KeyboardOptions(keyboardType = KeyboardType.Password),
                                visualTransformation =
                                    if (showPassword) VisualTransformation.None
                                    else PasswordVisualTransformation(),
                                trailingIcon = {
                                    TextButton(onClick = { showPassword = !showPassword }) {
                                        Text(if (showPassword) "隐藏" else "显示")
                                    }
                                },
                            )
                            Button(
                                enabled =
                                    !state.busy &&
                                        (state.network != null || hiddenSsid.isNotEmpty()),
                                onClick = {
                                    flow.join(
                                        password,
                                        if (state.network == null) hiddenSsid else null,
                                    )
                                    password = ""
                                },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                            ) {
                                Text("连接")
                            }
                        }
                        SetupPhase.WAITING -> {
                            Text("手机与灯具连接到同一网络后，会自动打开控制页。", color = Muted)
                            Button(
                                onClick = {
                                    context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                                }
                            ) {
                                Text("打开手机 Wi-Fi 设置")
                            }
                        }
                        SetupPhase.JOINING -> Text("正在等待灯具确认连接结果…", color = Muted)
                        SetupPhase.DONE -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun CandidateEntry(candidate: SetupCandidate, busy: Boolean, select: () -> Unit) {
    OutlinedButton(
        onClick = select,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(candidate.name, color = Cream)
            Text(
                if (candidate.endpoint != null) "已联网 · 点按连接" else "附近灯具 · 点按设置网络",
                color = Muted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text("连接")
    }
}
