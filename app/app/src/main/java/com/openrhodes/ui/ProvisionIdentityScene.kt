package com.openrhodes.ui

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.drawText
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import com.openrhodes.domain.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

private val DeviceIdleInk: Color get() = LabelInk
private val DeviceSweepEasing = CubicBezierEasing(.42f, 0f, .58f, 1f)

@Composable
private fun deviceNameSweep(layout: TextLayoutResult): Modifier {
    val sweep = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            sweep.animateTo(1f, tween(1400, easing = DeviceSweepEasing))
            kotlinx.coroutines.delay(2500)
            sweep.snapTo(0f)
        }
    }
    return Modifier.drawWithContent {
        val p = sweep.value
        if (p <= 0f || p >= 1f) {
            drawContent()
        } else {
            val radius = (size.width * .30f).coerceIn(16.dp.toPx(), 58.dp.toPx())
            val x = -radius + (size.width + 2 * radius) * p
            val gold = Color(0xFFFFBD51)
            val band = Brush.horizontalGradient(
                0f to DeviceIdleInk, .30f to gold, .46f to Color.White,
                .57f to Color.White, .72f to gold, 1f to DeviceIdleInk,
                startX = x - radius, endX = x + radius,
            )
            drawText(layout, brush = band)
        }
    }
}

/** 行中只保留几何占位；设备名始终由场景内同一个带 key 的 Text 绘制。 */
internal class ProvisionIdentityState {
    var selected by mutableStateOf<SetupCandidate?>(null)
    var active by mutableStateOf(false)
    var fromList by mutableStateOf(true)
    var completedAttempt by mutableStateOf<Int?>(null)
    val anchors = mutableStateMapOf<String, Rect>()
    val visibleAnchors = mutableStateMapOf<String, Rect>()
    fun select(candidate: SetupCandidate) { selected = candidate }
}

@Composable
internal fun CandidateNameSlot(candidate: SetupCandidate, identity: ProvisionIdentityState) {
    val density = LocalDensity.current
    val measure = rememberTextMeasurer()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val layout = measureConnectionName(measure,candidate.name,
            with(density){maxWidth.roundToPx()},ConnectionIdentityTextStyle.copy(fontSize=16.sp,lineHeight=20.sp))
        Spacer(Modifier.fillMaxWidth().height(with(density){layout.size.height.toDp()}.coerceAtLeast(48.dp))
            .onGloballyPositioned {
            val pos=it.positionInRoot()
            identity.anchors[candidate.key]=Rect(pos.x,pos.y,pos.x+it.size.width,pos.y+it.size.height)
            identity.visibleAnchors[candidate.key]=it.boundsInRoot()
        })
    }
}

/** 连接时钟只负责位置。完成度与错误仍全部来自 ProvisioningFlow。 */
@Composable
internal fun ProvisionIdentityScene(
    state: SetupUi,
    identity: ProvisionIdentityState,
    showDiscoveryRows: Boolean = true,
    select: (SetupCandidate) -> Unit,
    content: @Composable (Float) -> Unit,
) {
    val animations = ValueAnimator.areAnimatorsEnabled()
    val enter = remember { Animatable(0f) }
    val chrome = remember { Animatable(1f) }
    val exitAlpha = remember { Animatable(1f) }
    val completion = remember { Animatable(0f) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val latest by rememberUpdatedState(state)
    LaunchedEffect(state.connection?.attempt) {
        val attempt=state.connection?.attempt
        if (attempt != null && state.connection?.failed != true) {
            identity.fromList = identity.selected?.key?.let { identity.anchors.containsKey(it) } == true
            identity.active = true
            identity.completedAttempt = null
            enter.snapTo(0f)
            completion.snapTo(0f)
            exitAlpha.snapTo(1f)
            coroutineScope {
                launch { chrome.animateTo(0f, tween(if (animations) ProvisionIdentityMotion.CHROME_MS else 0, easing=LinearEasing)) }
                launch { enter.animateTo(1f, tween(if (animations) ProvisionIdentityMotion.ENTRY_MS else 0, easing=LinearEasing)) }
            }
            snapshotFlow { latest }.first {
                it.connection?.attempt != attempt || it.error != null ||
                    it.connection?.failed == true || (it.connection?.complete == true && it.phase == SetupPhase.DONE)
            }
            if (latest.error != null || latest.connection?.failed == true) {
                coroutineScope {
                    launch { enter.animateTo(0f, tween(if (animations) 250 else 0)) }
                    launch { chrome.animateTo(1f, tween(if (animations) 250 else 0)) }
                }
            } else {
                completion.animateTo(1f,tween(if(animations)ConnectionCompletion.DURATION.toInt() else 0,easing=LinearEasing))
                // 圆环淡出后保持姓名，等待导航接续下拉动作。
                identity.completedAttempt = attempt
                return@LaunchedEffect
            }
            identity.active = false
            enter.snapTo(0f)
            if (latest.phase == SetupPhase.DISCOVER) identity.selected = null
        } else if(identity.active) {
            enter.snapTo(0f)
            chrome.snapTo(1f)
            identity.active=false
        }
    }

    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
        content(chrome.value)
        if(identity.active)Box(Modifier.fillMaxSize().clickable(
            interactionSource=remember { MutableInteractionSource() },indication=null,onClick={}))
        if (!showDiscoveryRows && !identity.active && state.phase == SetupPhase.DISCOVER) return@BoxWithConstraints
        val selected = identity.selected
        val candidates = (listOfNotNull(selected) + state.candidates.filter { row ->
            showDiscoveryRows && (selected == null || row.key == selected.key || row.peer?.address != selected.peer?.address || row.peer == null)
        }).distinctBy { it.key }
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val margin = with(density) { 20.dp.toPx() }
        val safe = WindowInsets.safeDrawing.asPaddingValues()
        val safeLeft = with(density){safe.calculateLeftPadding(direction).toPx()}
        val safeRight = with(density){safe.calculateRightPadding(direction).toPx()}
        val safeArea=Rect(safeLeft,with(density){safe.calculateTopPadding().toPx()},
            widthPx-safeRight,heightPx-with(density){safe.calculateBottomPadding().toPx()})
        val textWidth=(safeArea.width-106f*density.density).toInt().coerceAtLeast(1)
        candidates.forEach { candidate -> key(candidate.key) {
            val selectedHere = candidate.key == selected?.key
            val source = identity.anchors[candidate.key]?.translate(-origin)
            val sourceClip=identity.visibleAnchors[candidate.key]?.translate(-origin)
            val sourceLayout = measureConnectionName(measurer,candidate.name,
                source?.width?.toInt() ?: textWidth,ConnectionIdentityTextStyle.copy(fontSize=16.sp,lineHeight=20.sp))
            val waiting=connectionWaitingName(measurer,candidate.name,safeArea,density.density)
            val focusedLayout=waiting.layout
            val waitingLeft=waiting.bounds.left
            val safeCenterY=waiting.bounds.center.y
            val fromList = source != null && (!selectedHere || identity.fromList)
            val elapsed=if(selectedHere&&identity.active)enter.value*ProvisionIdentityMotion.ENTRY_MS else 0f
            val entered = !fromList || IdentityFade.atTarget(elapsed)
            val completionMs = completion.value*ConnectionCompletion.DURATION
            val layout = if(identity.active && selectedHere && entered)focusedLayout else sourceLayout
            val left = when {
                !identity.active || !selectedHere || !entered -> source?.left ?: waitingLeft
                else -> waitingLeft
            }
            val center = when {
                !identity.active || !selectedHere || !entered -> source?.center?.y ?: safeCenterY
                else -> safeCenterY
            }
            val top = center-layout.size.height/2f
            val entryAlpha = if(fromList)IdentityFade.alpha(elapsed) else AddSuccessTiming.ease(elapsed/IdentityFade.IN_MS)
            val visible = when {
                selectedHere && identity.active -> exitAlpha.value*entryAlpha
                !identity.active && source != null && sourceClip!=null && sourceClip.width>0f && sourceClip.height>0f &&
                    (state.phase == SetupPhase.DISCOVER ||
                        (selectedHere && state.phase in listOf(SetupPhase.NETWORKS,SetupPhase.PASSWORD))) -> 1f
                else -> 0f
            }
            val canSelect = visible > 0f && state.phase == SetupPhase.DISCOVER && !identity.active && !state.busy
            val nameModifier = Modifier.requiredWidth(with(density) { layout.size.width.coerceAtLeast(1).toDp() })
                    .absoluteOffset { IntOffset(left.roundToInt(),top.roundToInt()) }
                    .testTag("provision-name-${candidate.key}")
                    .semantics {contentDescription=candidate.name}
                    .graphicsLayer {
                        alpha = visible
                    }
                    .drawWithContent {
                        if(identity.active)drawContent()
                        else sourceClip?.let { bounds ->
                            clipRect(bounds.left-left,bounds.top-top,bounds.right-left,bounds.bottom-top){this@drawWithContent.drawContent()}
                        }
                    }
                    .then(if (visible <= 0f) Modifier.clearAndSetSemantics {} else Modifier)
                    .then(if (canSelect && animations) deviceNameSweep(layout) else Modifier)
                    .then(if (canSelect)
                        Modifier.clickable { identity.select(candidate); select(candidate) } else Modifier)
            val nameColor = when {
                identity.active && entered -> LabelInk
                canSelect -> DeviceIdleInk
                else -> Cream
            }
            AddIdentityText(layout, nameColor, nameModifier, shared=selectedHere && identity.active,
                position=origin+Offset(left,top), alpha=visible,
                tag="provision-name-${candidate.key}", description=candidate.name)
            if (selectedHere && identity.active) {
                val progress = state.connection?.let { if (enter.value < 1f && it.complete) it.copy(percent = 99) else it }
                val showingCompletion=progress?.complete==true && enter.value>=1f
                val statusTop=safeCenterY+focusedLayout.size.height/2f+with(density){12.dp.toPx()}
                if(showingCompletion) {
                    Box(Modifier.offset(with(density){waitingLeft.toDp()},with(density){statusTop.toDp()}).size(24.dp)
                        .graphicsLayer {alpha=exitAlpha.value}) {
                        ConnectionIndicator(progress,1f,completionMs,animations,Modifier.fillMaxSize())
                    }
                }
                Box(Modifier.offset(with(density) { waitingLeft.toDp() }, with(density) { statusTop.toDp() })
                    .width(with(density) { (safeArea.right - waitingLeft - margin).coerceAtLeast(1f).toDp() })
                    .graphicsLayer {
                        val incoming = if(fromList)IdentityFade.incomingAlpha(elapsed) else entryAlpha
                        alpha = incoming*exitAlpha.value*if(showingCompletion)ConnectionCompletion.ringAlpha(completionMs) else 1f
                    }) {
                    if(showingCompletion)Text(progress?.label.orEmpty(),color=Muted,fontSize=16.sp,modifier=Modifier.padding(start=32.dp))
                    else ConnectionStatus(progress, state.status, compact = true)
                }
            }
        } }
    }
}
