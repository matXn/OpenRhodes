package com.openrhodes.ui

import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openrhodes.domain.*
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.*

@Composable
fun BreathControls(
    session: LampSession,
    actual: LampState,
    editorState: MutableState<BreathEdit>,
    enabled: Boolean = true,
    sharedFrame: CardFrame? = null,
) {
    val scope = rememberCoroutineScope()
    var editor by editorState
    var request by remember { mutableStateOf<Job?>(null) }
    var delivered by remember { mutableStateOf<LampState?>(null) }
    var deliveredRevision by remember { mutableIntStateOf(-1) }
    var transmittingRevision by remember { mutableStateOf<Int?>(null) }
    val latestActual by rememberUpdatedState(actual)
    LaunchedEffect(enabled) {
        if (!enabled) request?.cancel()
    }

    fun send(immediate: Boolean) {
        if (immediate && request?.isActive == true && transmittingRevision == editor.revision)
            return
        request?.cancel()
        val revision = editor.revision
        val parameters = editor.preview.breath
        request = scope.launch {
            if (!immediate) delay(150)
            transmittingRevision = revision
            try {
                val response = session.execute(Command.Parameters(parameters))
                delivered = response
                deliveredRevision = revision
                editor = editor.settle(revision, response)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                // The session exposes the failure; release the draft only if it is still current.
                editor = editor.settle(revision, latestActual)
            } finally {
                if (transmittingRevision == revision) transmittingRevision = null
            }
        }
    }
    fun change(parameters: Breath) {
        if (parameters == editor.preview.breath) return
        editor = editor.change(parameters, SystemClock.elapsedRealtime())
        send(false)
    }
    fun finish() {
        editor = editor.release()
        val acknowledged = delivered
        if (
            acknowledged != null &&
                deliveredRevision == editor.revision &&
                acknowledged.breath == editor.preview.breath
        ) {
            request?.cancel()
            editor = editor.settle(editor.revision, acknowledged)
        } else send(true)
    }

    val parameters = editor.preview.breath
    Spacer(Modifier.height(16.dp))
    Text("呼吸函数", color = Muted, fontSize = 13.sp)
    Spacer(Modifier.height(8.dp))
    BreathPlot(editor.preview.copy(on = actual.on), sharedFrame)
    Spacer(Modifier.height(8.dp))
    ParameterRow(
        "周期",
        parameters.periodMs.toFloat(),
        2000f..20000f,
        null,
        String.format(Locale.ROOT, "%.1f s", parameters.periodMs / 1000f),
        { change(parameters.copy(periodMs = it.roundToInt())) },
        ::finish,
        enabled,
    )
    ParameterRow(
        "最暗",
        parameters.floor.toFloat(),
        0f..80f,
        1f,
        "${parameters.floor} %",
        { change(parameters.copy(floor = it.roundToInt())) },
        ::finish,
        enabled,
    )
    ParameterRow(
        "曲线",
        parameters.curve.toFloat(),
        50f..300f,
        5f,
        String.format(Locale.ROOT, "%.2f", parameters.curve / 100f),
        { change(parameters.copy(curve = it.roundToInt())) },
        ::finish,
        enabled,
    )
}

/** 紧凑三列对齐，滑条的暖色光带与选中底块使用同一色系。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ParameterRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float?,
    display: String,
    change: (Float) -> Unit,
    done: () -> Unit,
    enabled: Boolean,
) {
    val fontScale = LocalDensity.current.fontScale
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    var pointerPressed by remember { mutableStateOf(false) }
    val dragged by interaction.collectIsDraggedAsState()
    val active = enabled && (pointerPressed || pressed || dragged)
    val spread by animateFloatAsState(
        if (active) 1f else 0f,
        tween(if (active) 140 else 220), label = "parameterGlowSpread",
    )
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            label,
            Modifier.width((36 * fontScale).dp),
            color = Muted,
            fontSize = 14.sp,
            maxLines = 1,
            softWrap = false,
        )
        Slider(
            interactionSource = interaction,
            enabled = enabled,
            value = value,
            onValueChange = {
                change(
                    if (step == null) it.coerceIn(range)
                    else
                        (range.start + ((it - range.start) / step).roundToInt() * step).coerceIn(
                            range
                        )
                )
            },
            onValueChangeFinished = done,
            valueRange = range,
            steps =
                if (step == null) 0
                else ((range.endInclusive - range.start) / step).roundToInt() - 1,
            modifier = Modifier.weight(1f).testTag("parameter-$label")
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    // 观察按下到抬起的完整过程，让静止按住也能展开，不消耗调值手势。
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        pointerPressed = true
                        try {
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                            } while (event.changes.any { it.id == down.id && it.pressed })
                        } finally {
                            pointerPressed = false
                        }
                    }
                },
            thumb = {
                Canvas(Modifier.size(width = 28.dp, height = 20.dp)) {
                    // 测量尺寸固定，仅让半透明光晕在按压时展开，保持数值映射稳定。
                    val halfWidth = (14 + 16 * spread).dp.toPx()
                    val haloRadius = halfWidth * 1.25f
                    val haloHeight = (5 + 3 * spread).dp.toPx()
                    val opacity = if (enabled) 1f else .35f
                    scale(1f, haloHeight / haloRadius, pivot = center) {
                        drawCircle(
                            Brush.radialGradient(
                                0f to Gold.copy(alpha = .28f * opacity),
                                .45f to Gold.copy(alpha = .13f * opacity),
                                1f to Color.Transparent,
                                center = center,
                                radius = haloRadius,
                            ),
                            radius = haloRadius,
                        )
                    }
                    val coreHeight = (2.5f + 1.5f * spread).dp.toPx()
                    scale(1f, coreHeight / halfWidth, pivot = center) {
                        drawCircle(
                            Brush.radialGradient(
                                0f to Color(0xFFFFEBC4).copy(alpha = .72f * opacity),
                                .22f to Color(0xFFFFDEA0).copy(alpha = .53f * opacity),
                                .58f to Gold.copy(alpha = .22f * opacity),
                                1f to Color.Transparent,
                                center = center,
                                radius = halfWidth,
                            ),
                            radius = halfWidth,
                        )
                    }
                }
            },
            track = {
                Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                    drawLine(
                        Cream.copy(alpha = if (enabled) .10f else .04f),
                        Offset(0f, size.height / 2),
                        Offset(size.width, size.height / 2),
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
            },
        )
        Text(
            display,
            Modifier.width((60 * fontScale).dp),
            color = Cream,
            fontSize = 14.sp,
            maxLines = 1,
            softWrap = false,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            style = TextStyle(fontFeatureSettings = "tnum"),
        )
    }
}
