package com.openrhodes.ui

import android.Manifest
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.bluetooth.BluetoothAdapter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.openrhodes.RhodesApp
import com.openrhodes.data.*
import com.openrhodes.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

private val discoveryIndent = 32.dp

@Composable
fun BluetoothProvision(
    app: RhodesApp,
    lamp: Lamp? = null,
    close: () -> Unit,
    gateway: ProvisioningGateway = remember(app) { AppProvisioningGateway(app) },
    linkFactory: (() -> ProvisionLink)? = null,
    embedded: Boolean = false,
    added: (AddCompletion) -> Unit,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val lifecycle=LocalLifecycleOwner.current.lifecycle
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
    val identity = remember(lamp?.id) { ProvisionIdentityState().apply {
        if(lamp!=null) selected=SetupCandidate(lamp.id,lamp.name,lamp.hardware,lamp.endpoints.firstOrNull(),null)
    } }
    val deviceName = "Rhodes Light"
    var controlMethod by rememberSaveable { mutableStateOf(SetupControlMethod.BLUETOOTH) }
    val onAdded by rememberUpdatedState(added)
    var otherAdd by remember { mutableStateOf<String?>(null) }
    var address by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var hiddenSsid by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }
    var discoveryPrepared by remember {mutableStateOf(false)}
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
            if(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))flow.resumeDiscovery(permitted())
            else {flow.pauseDiscovery();flow.search(permitted())}
        }
    LaunchedEffect(Unit) {
        // 局域网先开始寻找，不等待旧蓝牙会话或旧固件信息读取。
        flow.search(false)
        app.bluetooth.pauseForProvisioning()
        discoveryPrepared=true
        if(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))flow.resumeDiscovery(permitted())
        else flow.pauseDiscovery()
        if(!permitted())permissionLauncher.launch(permissions)
        if (lamp != null && lamp.endpoints.firstOrNull()?.isBluetooth != true) {
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
    }
    LaunchedEffect(flow,lifecycle) {
        // 后台收取真实结果不依赖被暂停的界面帧时钟。
        flow.ui.collect { current ->
            if(current.phase==SetupPhase.DONE && !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
                flow.takeCompletion()?.let(onAdded)
        }
    }
    LaunchedEffect(state.phase, state.completedId, identity.completedAttempt) {
        if (state.phase == SetupPhase.DONE &&
            (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) || identity.completedAttempt == state.connection?.attempt)) {
            flow.takeCompletion()?.let(onAdded)
        }
    }
    LaunchedEffect(state.network?.ssid, state.phase) {
        password = ""
        showPassword = false
    }
    DisposableEffect(context,flow,lifecycle) {
        val observer=LifecycleEventObserver {_,event ->
            if(event==Lifecycle.Event.ON_RESUME&&discoveryPrepared)flow.resumeDiscovery(permitted())
            if(event==Lifecycle.Event.ON_STOP) {
                flow.pauseDiscovery()
                if(flow.ui.value.phase==SetupPhase.DONE)flow.takeCompletion()?.let(onAdded)
            }
        }
        val receiver=object:BroadcastReceiver() {
            override fun onReceive(context:Context?,intent:Intent?) {
                if(intent?.action!=BluetoothAdapter.ACTION_STATE_CHANGED||!discoveryPrepared||
                    !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))return
                when(intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,BluetoothAdapter.ERROR)) {
                    BluetoothAdapter.STATE_ON -> flow.search(permitted(),restart=true)
                    BluetoothAdapter.STATE_OFF,BluetoothAdapter.STATE_TURNING_OFF -> flow.search(false,restart=true)
                }
            }
        }
        lifecycle.addObserver(observer)
        ContextCompat.registerReceiver(context,receiver,IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),ContextCompat.RECEIVER_EXPORTED)
        onDispose {
            lifecycle.removeObserver(observer)
            runCatching {context.unregisterReceiver(receiver)}
            flow.close()
            app.bluetooth.resumeAfterProvisioning()
            app.foregrounded()
        }
    }
    fun back() {
        if(state.phase==SetupPhase.DISCOVER && otherAdd!=null && !state.busy) {
            otherAdd = null
            return
        }
        if (!flow.back()) close()
    }

    ProvisionContainer(embedded, ::back) {
        BackHandler { back() }
        val page = state.phase to otherAdd.takeIf { state.phase == SetupPhase.DISCOVER }
        val depth = when (state.phase) {
            SetupPhase.DISCOVER -> if (otherAdd == null) 0 else 1
            SetupPhase.NETWORKS -> 1
            SetupPhase.PASSWORD -> 2
            SetupPhase.JOINING, SetupPhase.WAITING, SetupPhase.DONE -> 3
        }
        Surface(Modifier.fillMaxSize().pageArrival(
            page, depth, enabled = !identity.active && state.phase != SetupPhase.DONE,
        ), color = MaterialTheme.colorScheme.background) {
          ProvisionIdentityScene(state,identity,showDiscoveryRows = otherAdd == null,select={flow.select(it,controlMethod)}) { chromeAlpha ->
           Box(Modifier.fillMaxSize().graphicsLayer {alpha=chromeAlpha}
               .then(if(identity.active)Modifier.clearAndSetSemantics {} else Modifier)) {
            if(state.phase!=SetupPhase.DONE) Column(Modifier.safeDrawingPadding().imePadding()) {
                LampToolbar(
                    title =
                        when (state.phase) {
                            SetupPhase.DISCOVER -> when(otherAdd) {
                                "manual" -> "手动地址"
                                "hotspot" -> "热点配网"
                                else -> "连接"
                            }
                            SetupPhase.NETWORKS -> "选择 Wi-Fi"
                            SetupPhase.PASSWORD -> state.network?.ssid ?: "隐藏网络"
                            SetupPhase.JOINING -> "正在连接"
                            SetupPhase.WAITING -> "灯具已联网"
                            SetupPhase.DONE -> "连接成功"
                        },
                    subtitle =
                        when (state.phase) {
                            SetupPhase.DISCOVER -> null
                            SetupPhase.NETWORKS,
                            SetupPhase.PASSWORD -> "为灯具选择 2.4 GHz 网络"
                            else -> null
                        },
                    back = ::back,
                    action = null,
                )
                val discoveryList = state.phase == SetupPhase.DISCOVER && otherAdd == null
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(if (discoveryList) 0.dp else 8.dp),
                ) {
                    if (discoveryList) {
                        ConnectionMethodSelector(controlMethod == SetupControlMethod.WIFI, !state.busy) { wifi ->
                            controlMethod = if (wifi) SetupControlMethod.WIFI else SetupControlMethod.BLUETOOTH
                        }
                        Spacer(Modifier.height(16.dp))
                        if (!identity.active) {
                            if (state.searching || state.busy || state.connection != null)
                                ConnectionStatus(state.connection, state.status, compact = true)
                            else state.status?.let { Text(it, modifier = Modifier.padding(start = discoveryIndent), color = Muted) }
                        }
                    } else if(!identity.active) {
                        if(state.phase==SetupPhase.NETWORKS || state.phase==SetupPhase.PASSWORD)
                            identity.selected?.let { CandidateNameSlot(it,identity) }
                        if(state.connection!=null)ConnectionStatus(state.connection)
                        else if(state.busy || (state.searching && state.phase != SetupPhase.DISCOVER)) ConnectionStatus(null,state.status)
                    }
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if(state.phase!=SetupPhase.DISCOVER&&((state.connection==null&&!state.busy&&!state.searching)||state.phase==SetupPhase.WAITING))
                        state.status?.let { Text(it, color = Muted) }
                    when (state.phase) {
                        SetupPhase.DISCOVER -> {
                            if(otherAdd==null) {
                            if (state.candidates.isEmpty())
                                Text("将灯具通电并放在附近；已联网的灯具也会显示在这里。", Modifier.padding(start = discoveryIndent), color = Muted)
                            state.candidates.forEach { candidate ->
                                CandidateEntry(candidate, state.busy, identity) {
                                    identity.select(candidate)
                                    flow.select(candidate,controlMethod)
                                }
                            }
                                if (!state.busy) {
                                    Spacer(Modifier.height(12.dp))
                                    var expanded by rememberSaveable { mutableStateOf(false) }
                                    Text(
                                        "高级选项", color = Muted, style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.padding(start = 2.dp).fillMaxWidth()
                                            .clickable(role = Role.Button) { expanded = !expanded }
                                            .semantics { stateDescription = if (expanded) "已展开" else "已折叠" }
                                            .heightIn(min = 48.dp).wrapContentHeight(Alignment.CenterVertically),
                                    )
                                    ExpandingSection(expanded) {
                                        listOf("手动地址" to "manual", "热点配网" to "hotspot").forEach { (label, page) ->
                                            Text(label, color = Cream, style = MaterialTheme.typography.bodyMedium,
                                                modifier = Modifier.padding(start = discoveryIndent).fillMaxWidth()
                                                    .clickable(role = Role.Button) { expanded = false; otherAdd = page }
                                                    .heightIn(min = 48.dp).wrapContentHeight(Alignment.CenterVertically))
                                        }
                                    }
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
                            } else when(otherAdd) {
                                "manual" -> {
                                    ConnectionField(
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
                                        onClick = {
                                            focusManager.clearFocus()
                                            keyboard?.hide()
                                            identity.select(SetupCandidate("manual",deviceName,null,null,null))
                                            flow.manual(address)
                                        },
                                    ) {
                                        Text("验证并添加")
                                    }
                                }
                                "hotspot" -> {
                                    Text("在系统 Wi-Fi 中连接灯具的 RhodesLight-Setup 热点，再打开配网页。",color=Muted)
                                    TextButton(contentPadding = PaddingValues(vertical = 8.dp), onClick={context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))}) {Text("打开 Wi-Fi 设置")}
                                    TextButton(contentPadding = PaddingValues(vertical = 8.dp), onClick={context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("http://192.168.4.1/setup")))}) {Text("打开配网页")}
                                    TextButton(contentPadding = PaddingValues(vertical = 8.dp), enabled=!state.busy,onClick={
                                        identity.select(SetupCandidate("hotspot",deviceName,null,null,null))
                                        flow.manual("192.168.4.1")
                                    }) {
                                        Text("验证灯具")
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
                                        PaddingValues(vertical = 8.dp),
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
                                ConnectionField(
                                    hiddenSsid,
                                    { hiddenSsid = it },
                                    label = { Text("网络名称") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            ConnectionField(
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
                                    focusManager.clearFocus()
                                    keyboard?.hide()
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
    }
}

@Composable
internal fun ProvisionContainer(embedded: Boolean, back: () -> Unit, content: @Composable () -> Unit) {
    if (embedded) content()
    else Dialog(onDismissRequest=back, properties=DialogProperties(usePlatformDefaultWidth=false)) {
        DialogMotion()
        CompositionLocalProvider(LocalAddIdentityLayer provides null, content=content)
    }
}

@Composable
private fun CandidateEntry(candidate: SetupCandidate, busy: Boolean, identity:ProvisionIdentityState, select: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(start = discoveryIndent).clickable(enabled = !busy, onClick = select)) {
        CandidateNameSlot(candidate, identity)
    }
}
