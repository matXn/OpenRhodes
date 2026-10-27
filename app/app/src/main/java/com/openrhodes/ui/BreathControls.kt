package com.openrhodes.ui

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openrhodes.domain.*
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.*

@Composable
fun BreathControls(session: LampSession, actual: LampState, editorState: MutableState<BreathEdit>) {
    val scope = rememberCoroutineScope()
    var editor by editorState
    var request by remember { mutableStateOf<Job?>(null) }
    var delivered by remember { mutableStateOf<LampState?>(null) }
    var deliveredRevision by remember { mutableIntStateOf(-1) }
    var transmittingRevision by remember { mutableStateOf<Int?>(null) }
    val latestActual by rememberUpdatedState(actual)

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
    Spacer(Modifier.height(20.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("呼吸函数", color = Muted, fontSize = 13.sp)
        Text(
            String.format(Locale.ROOT, "%.1f s / 次", parameters.periodMs / 1000f),
            color = Muted,
            fontSize = 13.sp,
        )
    }
    Spacer(Modifier.height(12.dp))
    BreathPlot(editor.preview.copy(on = actual.on))
    Spacer(Modifier.height(8.dp))
    ParameterRow(
        "周期",
        parameters.periodMs.toFloat(),
        2000f..20000f,
        null,
        String.format(Locale.ROOT, "%.1f s", parameters.periodMs / 1000f),
        { change(parameters.copy(periodMs = it.roundToInt())) },
        ::finish,
    )
    ParameterRow(
        "最暗",
        parameters.floor.toFloat(),
        0f..80f,
        1f,
        "${parameters.floor} %",
        { change(parameters.copy(floor = it.roundToInt())) },
        ::finish,
    )
    ParameterRow(
        "曲线",
        parameters.curve.toFloat(),
        50f..300f,
        5f,
        String.format(Locale.ROOT, "%.2f", parameters.curve / 100f),
        { change(parameters.copy(curve = it.roundToInt())) },
        ::finish,
    )
}

/** Same layout as the HTML: 44px label, thin neutral rail, round thumb, right-aligned value. */
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
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(label, Modifier.width(44.dp), color = Muted, fontSize = 13.sp)
        Slider(
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
            modifier = Modifier.weight(1f),
            thumb = {
                Box(
                    Modifier.size(20.dp)
                        .background(MaterialTheme.colorScheme.background, CircleShape)
                        .padding(3.dp)
                        .background(Gold, CircleShape)
                )
            },
            track = {
                Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                    drawLine(
                        Color(0xFF2D2923),
                        Offset(0f, size.height / 2),
                        Offset(size.width, size.height / 2),
                        strokeWidth = 4.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
            },
        )
        Text(
            display,
            Modifier.width(58.dp),
            color = Cream,
            fontSize = 13.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            style = TextStyle(fontFeatureSettings = "tnum"),
        )
    }
}
