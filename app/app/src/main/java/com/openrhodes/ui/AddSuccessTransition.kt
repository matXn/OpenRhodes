package com.openrhodes.ui

import android.animation.ValueAnimator
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.openrhodes.domain.*
import kotlin.math.roundToInt

/** 纯展示过渡：没有通信对象，没有可发送灯光指令的回调。 */
@Composable
internal fun AddSuccessTransition(
    result: AddCompletion,
    name: String,
    target: Rect?,
    headerTarget: Rect? = null,
    progress: (Float) -> Unit,
    finished: () -> Unit,
) {
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val clock = remember(result.deviceId) { AddPlayback(ValueAnimator.areAnimatorsEnabled()) }
    var frame by remember(clock) { mutableStateOf(clock.frame) }
    var delivered by remember(clock) { mutableStateOf(false) }
    val finish by rememberUpdatedState(finished)
    val update by rememberUpdatedState(progress)
    val ready by rememberUpdatedState(target != null && target.width > 0f && target.height > 0f)
    fun finishOnce() {
        if (delivered) return
        delivered = true
        clock.background()
        frame = clock.frame
        update(1f)
        finish()
    }
    DisposableEffect(lifecycle, clock) {
        val observer = LifecycleEventObserver { _, event ->
            // 后台不依赖下一帧，也不等待目标卡片完成布局。
            if (event == Lifecycle.Event.ON_STOP) finishOnce()
        }
        lifecycle.addObserver(observer)
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) finishOnce()
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(clock, lifecycle) {
        while (!ready && clock.frame.stage != AddStage.DETAIL) withFrameNanos {}
        if (clock.frame.stage != AddStage.DETAIL) {
            var previous = withFrameNanos { it }
            while (clock.frame.stage != AddStage.DETAIL) {
                withFrameNanos { now ->
                    if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
                        clock.advance(((now - previous) / 1_000_000f).coerceIn(0f, 40f))
                    previous = now
                    frame = clock.frame
                    update(frame.elapsed / AddSuccessTiming.DURATION)
                }
            }
        }
        finishOnce()
    }
    AddSuccessFrame(result.snapshot, name, target, frame.elapsed / AddSuccessTiming.DURATION,
        headerTarget = headerTarget)
}

// 旧调用参数继续可用，完成反馈由时间轴自动播放。
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun AddSuccessFrame(
    snapshot: LampState,
    name: String,
    target: Rect?,
    time: Float,
    headerTarget: Rect? = null,
    awaiting: Boolean = false,
    confirmed: () -> Unit = {},
    confirmationMs: Float = ConfirmMotion.DURATION,
) {
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val measure = rememberTextMeasurer()
    var origin by remember { mutableStateOf(Offset.Zero) }
    val nameStyle = ConnectionIdentityTextStyle
    BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
        val safe = WindowInsets.safeDrawing.asPaddingValues()
        val safeLeft = with(density) { safe.calculateLeftPadding(direction).toPx() }
        val safeRight = with(density) { safe.calculateRightPadding(direction).toPx() }
        val safeArea=Rect(safeLeft,with(density){safe.calculateTopPadding().toPx()},
            constraints.maxWidth-safeRight,constraints.maxHeight-with(density){safe.calculateBottomPadding().toPx()})
        val rect = target?.takeIf { it.width > 0f && it.height > 0f }?.translate(-origin)
        val waiting=connectionWaitingName(measure,name,safeArea,density.density)
        val nameMeasure=waiting.layout
        val captionWidth = minOf(rect?.width ?: constraints.maxWidth.toFloat(),
            constraints.maxWidth-safeLeft-safeRight-with(density){40.dp.toPx()})
        val successText="已添加 $name"
        val successLayout=measureConnectionName(measure,successText,captionWidth.roundToInt(),
            nameStyle.copy(fontSize=14.sp,lineHeight=20.sp,textAlign=TextAlign.Center))
        val p = time.coerceIn(0f, 1f)
        val elapsed=p*AddSuccessTiming.DURATION
        val captionGap = with(density){28.dp.toPx()}
        val checkGap=with(density){16.dp.toPx()}
        val checkSize=with(density){32.dp.toPx()}
        val initialTop = rect?.let {
            val lastBottom = constraints.maxHeight-with(density){safe.calculateBottomPadding().toPx()+12.dp.toPx()}
            val feedbackHeight=successLayout.size.height+checkGap+checkSize
            val reservedHeight=maxOf(nameMeasure.size.height.toFloat(),feedbackHeight)
            val maximumTop = lastBottom-it.height-captionGap-reservedHeight
            minOf(it.top+it.height*(66f/246f),maximumTop)
                .coerceAtLeast(with(density){safe.calculateTopPadding().toPx()})
        }
        if (rect != null && initialTop != null) {
            // 本地高度固定为真实详情高度，仅宽度展开；继续使用实际 LampCard 材质与投影。
            val height = rect.height
            val width = height + (rect.width - height) * AddSuccessTiming.expansion(p)
            val top = initialTop + (rect.top-initialTop)*AddSuccessTiming.panelTravel(p)
            LampCard(snapshot, true, {}, {}, interactionEnabled = false, introProgress = p,
                displayName = if (AddSuccessTiming.transfer(p) > 0f) name else "",
                cardAspectRatio = width / height,
                introFinalWidthDp = with(density) { rect.width.toDp().value },
                modifier = Modifier.offset { IntOffset((rect.center.x - width / 2f).roundToInt(), top.roundToInt()) }
                    .requiredSize(with(density) { width.toDp() }, with(density) { height.toDp() })
                    .testTag("add-success-panel")
                    .drawWithContent {
                        drawContext.canvas.saveLayer(Rect(-size.width, -size.height, size.width * 2f, size.height * 2f),
                            Paint().apply { alpha = AddSuccessTiming.arrival(p) })
                        drawContent()
                        drawContext.canvas.restore()
                    })
        }

        val captionTop=if(rect!=null && initialTop!=null)initialTop+rect.height+captionGap else waiting.bounds.top
        val nameCenterY=waiting.bounds.center.y+(captionTop+nameMeasure.size.height/2f-waiting.bounds.center.y)*AddSuccessTiming.down(p)
        val isSuccess=elapsed>=AddSuccessTiming.SUCCESS_START_MS
        val shownLayout=if(isSuccess)successLayout else nameMeasure
        val nameAlpha=if(isSuccess)AddSuccessTiming.successCaption(p) else AddSuccessTiming.caption(p)
        val nameLeft = safeArea.center.x - shownLayout.size.width / 2f
        val nameTop=if(isSuccess)captionTop else nameCenterY-nameMeasure.size.height/2f
        if (nameAlpha > 0f) {
            AddIdentityText(shownLayout, LabelInk,
                modifier = Modifier.offset { IntOffset(nameLeft.roundToInt(),
                    nameTop.roundToInt()) }
                    .width(with(density) { shownLayout.size.width.coerceAtLeast(1).toDp() })
                    .testTag("add-success-name")
                    .semantics { contentDescription = if(isSuccess)successText else name }
                    .graphicsLayer { alpha = nameAlpha },
                position=origin+Offset(nameLeft,nameTop), alpha=nameAlpha,
                tag="add-success-name", description=if(isSuccess)successText else name)
        }
        val checkAlpha=AddSuccessTiming.successCheckAlpha(p)
        if(rect!=null && checkAlpha>0f) {
            Box(Modifier.offset { IntOffset((rect.center.x-checkSize/2f).roundToInt(),
                (captionTop+successLayout.size.height+checkGap).roundToInt()) }
                .size(32.dp).testTag("add-success-check")
                .semantics { contentDescription="添加成功" }
                .graphicsLayer { alpha=checkAlpha }) {
                GreenSuccessMark(AddSuccessTiming.checkReplay(p),fromProgress=true,holdFinal=true,
                    modifier=Modifier.fillMaxSize())
            }
        }
    }
}
