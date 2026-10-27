package com.openrhodes.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextGeometricTransform
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openrhodes.RhodesApp
import com.openrhodes.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun RhodesRoot(app: RhodesApp, requested: MutableStateFlow<String?>) {
    val lamps by app.catalog.lamps.collectAsStateWithLifecycle()
    val startup by app.startup.collectAsStateWithLifecycle()
    val requestedId by requested.collectAsStateWithLifecycle()
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var information by rememberSaveable { mutableStateOf(false) }
    var setup by rememberSaveable { mutableStateOf(false) }
    var showNotices by remember { mutableStateOf(false) }
    var wordmarkEntered by rememberSaveable { mutableStateOf(false) }
    val underlineEntrance = remember { Animatable(if (wordmarkEntered) 3f else 0f) }
    val pullState = rememberPullToRefreshState()
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        if (!wordmarkEntered) {
            for (pass in 1..3) {
                underlineEntrance.animateTo(
                    pass.toFloat(),
                    tween(800, easing = FastOutSlowInEasing),
                )
            }
            wordmarkEntered = true
        }
    }
    LaunchedEffect(requestedId) {
        if (requestedId != null) {
            selected = requestedId
            setup = false
            information = false
            requested.value = null
        }
    }
    val lamp = selected?.let { id -> CatalogRules.resolve(lamps, id) }
    val refreshHome: () -> Unit = {
        if (lamp == null && startup == null && !refreshing) {
            refreshing = true
            scope.launch {
                try {
                    app.foregrounded()
                    app.rescan()
                    coroutineScope {
                        app.catalog.lamps.value.forEach { saved ->
                            launch { app.session(saved.id).refresh(force = true) }
                        }
                    }
                } finally {
                    refreshing = false
                }
            }
        }
    }
    BackHandler(selected != null || setup || information) {
        when {
            setup -> setup = false
            information -> information = false
            else -> selected = null
        }
    }
    RhodesTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.safeDrawingPadding()) {
                LampToolbar(
                    title =
                        when {
                            lamp == null -> "OpenRhodes"
                            setup -> "网络设置"
                            information -> "设备设置"
                            else -> lamp.name
                        },
                    subtitle = if (lamp != null && (setup || information)) lamp.name else null,
                    largeTitle = lamp != null && !setup && !information,
                    back =
                        if (lamp == null) null
                        else
                            ({
                                when {
                                    setup -> setup = false
                                    information -> information = false
                                    else -> selected = null
                                }
                            }),
                    action =
                        if (lamp == null)
                            ({
                                adding = true
                            })
                        else if (!setup && !information) ({ information = true }) else null,
                    actionGlyph = if (lamp == null) NavGlyph.Add else NavGlyph.Settings,
                    actionDescription = if (lamp == null) "添加灯具" else "设备设置",
                    prominent = lamp == null,
                    underlineEntrance = { underlineEntrance.value },
                    refreshPull = { pullState.distanceFraction },
                    refreshing = refreshing,
                )
                key(lamp?.id, setup, information) {
                    HomeRefreshFrame(
                        enabled = lamp == null && startup == null,
                        modifier = Modifier.weight(1f),
                        refreshing = refreshing,
                        state = pullState,
                        onRefresh = refreshHome,
                    ) {
                        Column(
                            Modifier.fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 20.dp)
                        ) {
                            Spacer(Modifier.height(16.dp))
                            if (startup != null)
                                Text(
                                    startup!!,
                                    color = Muted,
                                    modifier = Modifier.padding(vertical = 30.dp),
                                )
                            else if (lamp == null) {
                                if (lamps.isEmpty()) {
                                    Text(
                                        "添加你的第一盏灯",
                                        fontSize = 22.sp,
                                        modifier = Modifier.padding(top = 64.dp),
                                    )
                                    Text(
                                        "将灯具通电，App 会自动寻找附近的灯具。",
                                        color = Muted,
                                        modifier = Modifier.padding(vertical = 18.dp),
                                    )
                                }
                                if (lamps.isEmpty())
                                    Button(onClick = { adding = true }) { Text("添加灯具") }
                                lamps.forEach { row ->
                                    LampRow(app, row) { selected = row.id }
                                    Spacer(Modifier.height(14.dp))
                                }
                            } else if (setup) WifiSetup(app, lamp)
                            else if (information)
                                LampInformation(
                                    app,
                                    lamp,
                                    onSetup = { setup = true },
                                    onNotices = { showNotices = true },
                                )
                            else LampControl(app, lamp)
                            Spacer(Modifier.height(24.dp))
                        }
                    }
                }
                if (lamp == null || (!information && !setup)) ProjectCredit()
            }
        }
        if (adding)
            BluetoothProvision(app, close = { adding = false }) { id ->
                adding = false
                selected = id
            }
        if (showNotices) ThirdPartyNotices { showNotices = false }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeRefreshFrame(
    enabled: Boolean,
    modifier: Modifier,
    refreshing: Boolean,
    state: PullToRefreshState,
    onRefresh: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (enabled) {
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = onRefresh,
            state = state,
            indicator = {},
            modifier = modifier,
        ) {
            content()
        }
    } else {
        Box(modifier) { content() }
    }
}

@Composable
private fun ThirdPartyNotices(close: () -> Unit) {
    val context = LocalContext.current
    val text by
        produceState("正在读取许可") {
            value =
                withContext(Dispatchers.IO) {
                    context.assets.open("third-party-notices.txt").bufferedReader().use {
                        it.readText()
                    }
                }
        }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("第三方开源许可") },
        text = {
            LazyColumn(Modifier.heightIn(max = 440.dp)) {
                items(text.split("\n\n")) { Text(it, fontSize = 12.sp) }
            }
        },
        confirmButton = { TextButton(onClick = close) { Text("关闭") } },
    )
}

@Composable
private fun LampRow(app: RhodesApp, lamp: Lamp, open: () -> Unit) {
    val session = remember(lamp.id, lamp.hardware) { app.session(lamp.id) }
    val observation by session.observation.collectAsStateWithLifecycle()
    val macLabel = lamp.hardware?.displayTag ?: "#MAC 待确认"
    val statusColor =
        when {
            observation.connecting -> Gold
            observation.error != null -> Color(0xFFD28B76)
            observation.state != null -> Color(0xFF89BC87)
            else -> Muted
        }
    val statusDescription =
        when {
            observation.connecting -> "正在连接"
            observation.error != null -> "未连接"
            observation.state != null -> "已连接"
            else -> "尚未确认连接"
        }
    Surface(
        Modifier.fillMaxWidth().clickable(onClick = open),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(Modifier.padding(vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    lamp.name,
                    fontSize = 20.sp,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    macLabel,
                    color = Muted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = (-.3).sp,
                    style =
                        TextStyle(textGeometricTransform = TextGeometricTransform(scaleX = .96f)),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(10.dp).semantics { contentDescription = statusDescription }) {
                    drawCircle(statusColor)
                }
            }
        }
    }
}

@Composable
private fun LampControl(app: RhodesApp, lamp: Lamp) {
    val session = remember(lamp.id, lamp.hardware) { app.session(lamp.id) }
    val observation by session.observation.collectAsStateWithLifecycle()
    var localLevel by remember { mutableStateOf<Int?>(null) }
    val actual = observation.state ?: LampState(false, 0)
    val canControl =
        observation.state != null && observation.error == null && !observation.connecting
    val breathEditor = remember(session) { mutableStateOf(BreathEdit(actual)) }
    LaunchedEffect(actual, canControl) {
        breathEditor.value =
            if (!canControl || !actual.breathing) BreathEdit(actual)
            else breathEditor.value.observe(actual)
    }
    LaunchedEffect(observation) {
        if (observation.error != null || localLevel == observation.state?.brightness)
            localLevel = null
    }
    val visual =
        breathEditor.value.preview.let { preview ->
            if (actual.breathing && canControl)
                actual.copy(
                    breath = preview.breath,
                    phase = preview.phase,
                    sampledAt = preview.sampledAt,
                )
            else actual
        }
    val shown = localLevel?.let { visual.copy(on = it > 0, brightness = it) } ?: visual
    LampCard(
        shown,
        canControl,
        connecting = observation.connecting,
        level = {
            if (canControl) {
                localLevel = it
                session.submit(Command.Level(it))
            }
        },
        toggle = {
            if (canControl) {
                localLevel = null
                session.submit(Command.Power(!shown.on))
            }
        },
    )
    Spacer(Modifier.height(24.dp))
    LightModeSelector(actual.breathing, canControl) { session.submit(Command.Mode(it)) }
    if (actual.breathing && canControl)
        key(lamp.id) { BreathControls(session, actual, breathEditor) }
    ConnectionRecovery(app, lamp, observation)
}

@Composable
private fun LampInformation(
    app: RhodesApp,
    lamp: Lamp,
    onSetup: () -> Unit,
    onNotices: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by remember(lamp.id) { mutableStateOf(lamp.name) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    val session = remember(lamp.id, lamp.hardware) { app.session(lamp.id) }
    val observation by session.observation.collectAsStateWithLifecycle()
    SettingsEntry("Wi-Fi 与配网", "保存的网络、热点与蓝牙配网", onSetup)
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(Modifier.height(20.dp))
    BasicTextField(
        name,
        { name = it },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = Cream),
        cursorBrush = SolidColor(Gold),
        modifier =
            Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 8.dp).semantics {
                contentDescription = "名称"
            },
        decorationBox = { input ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("名称", color = Muted, fontSize = 13.sp)
                input()
            }
        },
    )
    TextButton(
        contentPadding = PaddingValues(vertical = 8.dp),
        onClick = {
            scope.launch {
                try {
                    app.catalog.rename(lamp.id, name)
                    message = "已保存"
                } catch (e: Exception) {
                    message = e.message
                }
            }
        },
    ) {
        Text("保存名称")
    }
    SettingsEntry("设备详情", "硬件身份与历史连接地址") { details = !details }
    if (details) {
        Text("硬件身份", color = Muted, modifier = Modifier.padding(top = 16.dp))
        Text(lamp.hardware?.value ?: "旧记录，尚未确认")
        Text("保存的地址", color = Muted, modifier = Modifier.padding(top = 20.dp))
        lamp.endpoints.forEach {
            Text(
                it.host + if (it == observation.endpoint) " · 上次成功连接" else " · 历史地址",
                modifier = Modifier.padding(vertical = 5.dp),
            )
        }
    }
    if (lamp.hardware == null) {
        Text("请确认当前首选地址确实是这盏灯。确认后将按完整 MAC 合并记录，并恢复控制。", color = Muted)
        TextButton(
            enabled = !busy,
            onClick = {
                busy = true
                scope.launch {
                    try {
                        val endpoint = lamp.endpoints.first()
                        val state = app.api.read(endpoint)
                        val hardware = requireNotNull(state.hardware) { "固件尚未返回完整 MAC" }
                        app.catalog.enroll(lamp.copy(hardware = hardware))
                        message = "已确认 ${hardware.value}"
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (e: Exception) {
                        message = e.message
                    } finally {
                        busy = false
                    }
                }
            },
        ) {
            Text("确认当前地址对应此灯具")
        }
    }
    message?.let { Text(it, color = Gold) }
    Spacer(Modifier.height(12.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    ConnectionRecovery(app, lamp, observation, management = true)
    SettingsEntry("第三方开源许可", "查看所用依赖的许可", onNotices)
}
