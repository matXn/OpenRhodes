package com.openrhodes.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.ui.graphics.Brush
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Velocity
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
    var success by remember { mutableStateOf<AddCompletion?>(null) }
    var firstAddHint by remember { mutableStateOf<String?>(null) }
    var successProgress by remember { mutableFloatStateOf(0f) }
    var headerBounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    var cardBounds by remember { mutableStateOf<Rect?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var settingsPage by rememberSaveable { mutableStateOf<DeviceSettingsPage?>(null) }
    var wordmarkEntered by rememberSaveable { mutableStateOf(false) }
    val pullState = rememberPullToRefreshState()
    var refreshing by remember { mutableStateOf(false) }
    var refreshRequest by remember { mutableIntStateOf(0) }
    var homeDragging by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(requestedId) {
        if (requestedId != null) {
            selected = requestedId
            settingsPage = null
            requested.value = null
        }
    }
    val lamp = selected?.let { id -> CatalogRules.resolve(lamps, id) }
    LaunchedEffect(firstAddHint, selected, settingsPage, success) {
        if (firstAddHint != null) {
            if (selected != firstAddHint || settingsPage != null) firstAddHint = null
            else if (success == null) {
                delay(3000)
                firstAddHint = null
            }
        }
    }
    LaunchedEffect(selected, lamp?.id, startup) {
        if (selected != null && app.catalog.find(selected!!) == null && startup == null) {
            selected = null
            settingsPage = null
            success = null
        }
    }
    val refreshHome: () -> Unit = {
        if (lamp == null && startup == null && !refreshing) {
            refreshRequest++
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
                    try {
                        // 空列表可能同帧完成。先让 Material3 观察到刷新状态，再触发回弹；
                        // 网络结果不等待这里的两帧，也不人为延长光带动画。
                        withFrameNanos {}
                        withFrameNanos {}
                    } finally {
                        refreshing = false
                    }
                }
            }
        }
    }
    fun back() {
        when {
            success != null -> { success = null; selected = null }
            settingsPage != null -> settingsPage = settingsPage?.back()
            else -> selected = null
        }
    }
    // 添加页处理自己的返回。控制与设置页共享同一个返回导航入口。
    if (!adding && (selected != null || settingsPage != null)) {
        BackHandler(onBack = ::back)
    }
    val page = Triple(lamp?.id, settingsPage != null, adding)
    val depth = when {
        adding -> 1
        lamp == null -> 0
        settingsPage == null -> 1
        else -> 2
    }
    RhodesTheme {
      SharedAddIdentity {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize().clipToBounds()
                .pageArrival(page, depth, enabled = success == null)) {
            val safe=WindowInsets.safeDrawing.asPaddingValues()
            val cardWidthLimit=detailCardWidthLimit(LocalConfiguration.current.screenHeightDp.dp,safe.calculateTopPadding()+safe.calculateBottomPadding(),LocalDensity.current.fontScale)
            Column(Modifier.safeDrawingPadding().graphicsLayer { alpha=if(adding)0f else 1f }
                .then(if(adding)Modifier.clearAndSetSemantics {} else Modifier)) {
                Box(Modifier.graphicsLayer { alpha = if(success == null) 1f else AddSuccessTiming.chrome(successProgress) }) {
                LampToolbar(
                    title =
                        when {
                            lamp == null -> "Open Rhodes"
                            settingsPage != null -> DeviceSettingsPage.Menu.title
                            else -> lampDisplayName(lamp.name)
                        },
                    subtitle = null,
                    largeTitle = lamp != null && settingsPage == null,
                    onTitleBounds={headerBounds=it},
                    back = if (lamp == null) null else ::back,
                    action =
                        if (lamp == null)
                            ({
                                adding = true
                            })
                        else if (settingsPage == null && success == null) ({ settingsPage = DeviceSettingsPage.Menu }) else null,
                    actionGlyph = if (lamp == null) NavGlyph.Add else NavGlyph.Settings,
                    actionDescription = if (lamp == null) "添加灯具" else "设备设置",
                    prominent = lamp == null,
                    underlineEntrance = { if (wordmarkEntered) 3f else 0f },
                    onUnderlineEntered = { wordmarkEntered = true },
                    refreshPull = { if (homeDragging) pullState.distanceFraction else 0f },
                    refreshRequest = refreshRequest,
                    refreshing = refreshing,
                    onTitleClick = if (lamp == null && startup == null) refreshHome else null,
                )
                }
                key(lamp?.id, settingsPage != null) {
                    HomeRefreshFrame(
                        enabled = lamp == null && startup == null,
                        modifier = Modifier.weight(1f),
                        refreshing = refreshing,
                        dragging = homeDragging,
                        state = pullState,
                        onRefresh = refreshHome,
                        onDragging = { homeDragging = it },
                    ) { pullFraction ->
                        Column(
                            Modifier.fillMaxSize()
                                .verticalScroll(rememberScrollState(), enabled = success == null)
                                .padding(horizontal = if(lamp!=null&&settingsPage == null)26.6667.dp else 20.dp)
                        ) {
                            Spacer(Modifier.height(if (lamp == null) 4.dp else 16.dp))
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
                                    LampRow(app, row, pullFraction, homeDragging, refreshing, open = { selected = row.id }, settings = {
                                        selected = row.id
                                        settingsPage = DeviceSettingsPage.Menu
                                    })
                                    Spacer(Modifier.height(14.dp))
                                }
                            } else if (settingsPage != null)
                                DeviceSettings(app, lamp, settingsPage!!,
                                    navigate = { settingsPage = it })
                            else LampControl(app, lamp,
                                cardModifier = Modifier.widthIn(max=cardWidthLimit).align(Alignment.CenterHorizontally).onGloballyPositioned { cardBounds = it.boundsInRoot() }
                                    .graphicsLayer { alpha = if (success == null) 1f else 0f },
                                transition = success != null,
                                showGestureHint = firstAddHint == lamp.id,
                                connectionSettings = { settingsPage = DeviceSettingsPage.Connection },
                                controlsAlpha = if (success == null) 1f else AddSuccessTiming.chrome(successProgress))
                            Spacer(Modifier.height(24.dp))
                        }
                    }
                }
                Box(Modifier.graphicsLayer { alpha = if(success == null) 1f else AddSuccessTiming.chrome(successProgress) }) {
                    ProjectCredit()

                }
            }
        if (adding)
            BluetoothProvision(app, close = { adding = false }, embedded = true) { result ->
                adding = false
                selected = result.deviceId
                firstAddHint = result.deviceId.takeIf { result.firstAdd }
                cardBounds = null
                successProgress = AddSuccessTiming.SAVED_START_MS / AddSuccessTiming.DURATION
                success = result.takeIf { it.firstAdd && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
            }
            val pending = success
            if (pending != null && lamp != null) AddSuccessTransition(pending, lamp.name, cardBounds,headerTarget=headerBounds,
                progress = { successProgress = it }, finished = { success = null })
            }
        }
      }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeRefreshFrame(
    enabled: Boolean,
    modifier: Modifier,
    refreshing: Boolean,
    dragging: Boolean,
    state: PullToRefreshState,
    onRefresh: () -> Unit,
    onDragging: (Boolean) -> Unit,
    content: @Composable (pullFraction: () -> Float) -> Unit,
) {
    val currentDragging by rememberUpdatedState(onDragging)
    if (enabled) {
        val currentRefreshing by rememberUpdatedState(refreshing)
        val currentRefresh by rememberUpdatedState(onRefresh)
        val scope = rememberCoroutineScope()
        val threshold = 64.dp
        val thresholdPx = with(LocalDensity.current) { threshold.toPx() }
        var pullDistance by remember { mutableFloatStateOf(0f) }
        // 内容和光带共用拖动距离；新下拉从此刻可见的回弹位置接手。
        val contentOffset by
            animateFloatAsState(
                targetValue = if (dragging) pullDistance * 1.35f else 0f,
                animationSpec =
                    if (dragging) snap() else tween(320, easing = LinearOutSlowInEasing),
                label = "homePullReturn",
            )
        val offsetAtThreshold = thresholdPx * 1.35f
        val currentOffset by rememberUpdatedState(contentOffset)
        val connection = remember(state, thresholdPx) {
            object : NestedScrollConnection {
                var pulling = false
                var refreshBlocked = false

                fun consume(delta: Float): Offset {
                    if (!pulling) {
                        pulling = true
                        // 本次手势即使跨过网络完成，也不能重复请求。
                        refreshBlocked = currentRefreshing
                        pullDistance = currentOffset / 1.35f
                        currentDragging(true)
                    }
                    val before = pullDistance
                    pullDistance = (before + delta * .5f).coerceIn(0f, thresholdPx * 1.25f)
                    val fraction = pullDistance / thresholdPx
                    scope.launch { state.snapTo(fraction) }
                    return Offset(0f, if (delta > 0f) delta else (pullDistance - before) / .5f)
                }

                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
                    if (source == NestedScrollSource.UserInput && pulling && available.y < 0f)
                        consume(available.y)
                    else Offset.Zero

                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset =
                    // 子滚动容器先判定方向和 touch slop，并先滚动到顶部。
                    if (source == NestedScrollSource.UserInput && available.y > 0f)
                        consume(available.y)
                    else Offset.Zero

                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (!pulling) return Velocity.Zero
                    pulling = false
                    val consumed = if (pullDistance > 0f && available.y > 0f) available.y else 0f
                    currentDragging(false)
                    if (pullDistance > thresholdPx && !refreshBlocked && !currentRefreshing) currentRefresh()
                    state.snapTo(0f)
                    return Velocity(0f, consumed)
                }
            }
        }
        DisposableEffect(connection) {
            onDispose { currentDragging(false) }
        }
        Box(
            modifier
                .clipToBounds()
                .nestedScroll(connection)
        ) {
            Box(Modifier.fillMaxSize().graphicsLayer { translationY = contentOffset }) {
                content { (contentOffset / offsetAtThreshold).coerceIn(0f, 1f) }
            }
        }
    } else {
        Box(modifier) { content { 0f } }
    }
}

@Composable
internal fun homeConnectionColor(
    pending: Boolean,
    offline: Boolean,
    dragging: Boolean,
    refreshing: Boolean,
    pullFraction: () -> Float,
): State<Color> {
    val gray = Color(0xFF767676)
    val followPull = offline && dragging && !refreshing
    val target = when {
        pending || (offline && refreshing) -> gray
        followPull -> lerp(Color(0xFFD28B76), gray, pullFraction())
        offline -> Color(0xFFD28B76)
        else -> Color(0xFF89BC87)
    }
    return animateColorAsState(target, if (followPull) snap() else tween(320), label = "connection color")
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun LampRow(
    app: RhodesApp, lamp: Lamp, pullFraction: () -> Float,
    dragging: Boolean, refreshing: Boolean, open: () -> Unit, settings: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }
    val session = remember(lamp.id, lamp.hardware) { app.session(lamp.id) }
    val observation by session.observation.collectAsStateWithLifecycle()
    val macLabel = lamp.hardware?.displayTag?.removePrefix("#")?.let { "Mac:$it" } ?: "Mac:—"
    val pending = observation.connecting || (observation.error == null && observation.state == null)
    val offline = !pending && observation.error != null
    val statusColor by homeConnectionColor(pending, offline, dragging, refreshing, pullFraction)
    val statusDescription =
        when {
            observation.connecting -> "正在连接"
            observation.error != null -> "未连接"
            observation.state != null -> "已连接"
            else -> "尚未确认连接"
        }
    Surface(
        Modifier.fillMaxWidth().combinedClickable(onClick = open, onLongClick = { menu = true }),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("设备设置") }, onClick = { menu = false; settings() })
                DropdownMenuItem(text = { Text("删除灯具", color = MaterialTheme.colorScheme.error) },
                    onClick = { menu = false; confirmDelete = true })
            }
            val compact = maxWidth < 320.dp
            Row(
                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        lampDisplayName(lamp.name),
                        color = LabelInk,
                        fontFamily = DeviceTitleFont,
                        fontWeight = FontWeight.Light,
                        fontSize = if (compact) 28.sp else 34.sp,
                        letterSpacing = (-.8).sp,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        val glint = if (pending) rememberInfiniteTransition(label = "pending").animateFloat(
                            initialValue = .15f, targetValue = .85f,
                            animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
                            label = "reflection",
                        ) else null
                        Canvas(
                            Modifier.size(10.dp).semantics {
                                contentDescription = statusDescription
                            }
                        ) {
                            drawCircle(statusColor)
                            glint?.let {
                                drawCircle(Brush.radialGradient(
                                    listOf(Color.White.copy(alpha = .44f), Color.Transparent),
                                    center = Offset(size.width * it.value, size.height * .36f),
                                    radius = size.width * .58f,
                                ))
                            }
                        }
                        Text(
                            macLabel,
                            color = if (LightAppearance) Muted else Color(0xFFC6C3BB),
                            fontFamily = AddressFont,
                            fontWeight = FontWeight.Light,
                            fontSize = 12.sp,
                            letterSpacing = (-.35).sp,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                }
                val reading = observation.state
                Box(Modifier.clipToBounds(), contentAlignment = Alignment.CenterEnd) {
                    if (reading == null) Text("—", color = Muted, fontSize = if (compact) 36.sp else 40.sp)
                    androidx.compose.animation.AnimatedVisibility(
                        visible = reading != null,
                        enter = slideInVertically(tween(260)) { it / 2 } + fadeIn(tween(220)),
                    ) {
                        Text(
                            reading?.let { "${if (it.on) it.brightness else 0}%" }.orEmpty(),
                            color = LabelInk,
                            fontFamily = BrightnessFont,
                            fontWeight = FontWeight.ExtraLight,
                            fontSize = if (compact) 36.sp else 40.sp,
                            letterSpacing = (-2).sp,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { if (!deleting) confirmDelete = false },
        title = { DialogMotion(); Text("删除灯具？") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("从 App 中移除「${lamp.name}」及其本地设备记录。")
            deleteError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(enabled = !deleting, onClick = {
            deleting = true
            scope.launch {
                try { app.removeLamp(lamp.id) }
                catch (cancel: CancellationException) { throw cancel }
                catch (failure: Exception) { deleteError = failure.message ?: "删除失败，请重试" }
                finally { deleting = false }
            }
        }) { Text(if (deleting) "正在删除…" else "删除") } },
        dismissButton = { TextButton(enabled = !deleting, onClick = { confirmDelete = false }) { Text("取消") } },
    )
}

@Composable
private fun LampControl(app: RhodesApp, lamp: Lamp, cardModifier: Modifier = Modifier,
    transition: Boolean = false, controlsAlpha: Float = 1f, showGestureHint: Boolean = false,
    connectionSettings: () -> Unit) {
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
    // 灯板和状态球消费同一帧，拖动预览、设备回报与呼吸参数都只计算一次。
    val lightPresentation = remember(lamp.id) { CardPresentation() }
    val latestShown by rememberUpdatedState(shown)
    // 拖动预览不是设备相位观测。确认前只按本地周期推进，确认后再缓慢校准。
    val phaseObservation = if (breathEditor.value.dirty || breathEditor.value.dragging) null else actual
    val latestPhaseObservation by rememberUpdatedState(phaseObservation)
    var sharedLightFrame by remember(lamp.id) {
        mutableStateOf(lightPresentation.sample(shown, android.os.SystemClock.elapsedRealtime()))
    }
    LaunchedEffect(lamp.id) {
        while (true) withFrameMillis {
            sharedLightFrame = lightPresentation.sample(latestShown,
                android.os.SystemClock.elapsedRealtime(), latestPhaseObservation)
        }
    }
    val hintAlpha by animateFloatAsState(if(showGestureHint) 1f else 0f, tween(250), label="firstAddHint")
    LampCard(
        shown,
        canControl,
        displayName=lampDisplayName(lamp.name),
        modifier = cardModifier,
        interactionEnabled = !transition,
        gestureHintAlpha = hintAlpha,
        sharedFrame = sharedLightFrame,
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
    Column(Modifier.graphicsLayer { alpha = controlsAlpha }) {
    Spacer(Modifier.height(24.dp))
    LightModeSelector(actual.breathing, canControl && !transition, sharedLightFrame.energy) { session.submit(Command.Mode(it)) }
    AnimatedVisibility(
        visible = actual.breathing && canControl && !transition,
        enter = expandVertically(tween(320, easing = CubicBezierEasing(.22f, .61f, .36f, 1f))) +
            fadeIn(tween(180, delayMillis = 100)),
        exit = shrinkVertically(tween(320, easing = CubicBezierEasing(.22f, .61f, .36f, 1f))) +
            fadeOut(tween(100)),
    ) {
        Column {
            key(lamp.id) {
                BreathControls(session, actual, breathEditor,
                    actual.breathing && canControl && !transition, sharedFrame = sharedLightFrame)
            }
        }
    }
    if (!transition && !canControl && !(observation.connecting && observation.state != null)) {
        Spacer(Modifier.height(20.dp))
        Text(if(observation.connecting) "正在确认连接…" else "灯具暂时无法连接", color = Muted, fontSize = 13.sp)
        TextButton(onClick = connectionSettings) { Text("连接设置") }
    }
    }
}

