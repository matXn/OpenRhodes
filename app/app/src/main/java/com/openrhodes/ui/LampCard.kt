package com.openrhodes.ui

import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openrhodes.domain.*
import kotlin.math.*

internal val Gold = Color(0xFFEEAC43)
internal val Cream = Color(0xFFF4E8D1)
internal val Muted = Color(0xFFA69981)

/** Two full swipes; 60% of travel is reserved for the bottom 30% of light. */
fun slideBrightness(start: Int, travel: Float): Int {
    val b = start / 100f
    val origin = if (b <= .3f) b * 2 else .6f + (b - .3f) * .4f / .7f
    val s = (origin + travel).coerceIn(0f, 1f)
    return ((if (s <= .6f) s * .5f else .3f + (s - .6f) * .7f / .4f) * 100)
        .roundToInt()
        .coerceIn(0, 100)
}

@Composable
fun LampCard(
    state: LampState,
    online: Boolean,
    level: (Int) -> Unit,
    toggle: () -> Unit,
    connecting: Boolean = false,
) {
    var pressed by remember { mutableStateOf(false) }
    var x by remember { mutableFloatStateOf(0f) }
    var y by remember { mutableFloatStateOf(0f) }
    val response =
        if (pressed) tween<Float>(60, easing = LinearOutSlowInEasing)
        else spring<Float>(stiffness = 420f, dampingRatio = 1f)
    val touchRx by
        animateFloatAsState(
            if (pressed) -y * CardLighting.MAX_TILT else 0f,
            response,
            label = "tiltX",
        )
    val touchRy by
        animateFloatAsState(
            if (pressed) x * CardLighting.MAX_TILT else 0f,
            response,
            label = "tiltY",
        )
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val current by rememberUpdatedState(state)
    var breathPhase by remember { mutableDoubleStateOf(state.phaseAt(now)) }
    LaunchedEffect(Unit) {
        while (true) withFrameMillis {
            now = SystemClock.elapsedRealtime()
            if (current.on && current.breathing) breathPhase = current.phaseAt(now)
        }
    }
    val drift by
        animateFloatAsState(
            if (state.on && state.breathing && online && !pressed) 1f else 0f,
            tween(if (pressed) 100 else 450),
            label = "breathCardDrift",
        )
    val sway = sin(breathPhase).toFloat() * drift
    val lift = cos(breathPhase).toFloat() * drift
    val rx = touchRx + sway * 1.2f
    val ry = touchRy + lift * 1.1f
    val presentation = remember { CardPresentation() }
    val frame = presentation.sample(state, now)
    val energy = frame.energy
    val lighting = CardLighting.surface(energy, state.on)
    val surface = Color.hsl(lighting.hue, lighting.saturation, lighting.body)
    val core = Color.hsl(lighting.hue, lighting.saturation, lighting.core)
    val inkColors = remember(energy) { cardInk(energy) }
    val inkTop by animateColorAsState(inkColors[0], tween(120), label = "inkTop")
    val inkBottom by animateColorAsState(inkColors[1], tween(120), label = "inkBottom")
    val ink =
        remember(inkTop, inkBottom) {
            Brush.linearGradient(listOf(inkTop, inkBottom))
        }
    val change by rememberUpdatedState(level)
    val tap by rememberUpdatedState(toggle)
    val live = state.on && state.breathing
    val outputText =
        if (!state.on) "关"
        else if (live) "${frame.output.roundToInt()}%" else "${state.brightness}%"
    BoxWithConstraints(
        Modifier.fillMaxWidth()
            .aspectRatio(4f / 3f)
            .semantics {
                contentDescription = "Rhodes Light"
                stateDescription =
                    (if (connecting) "正在连接，" else if (!online) "离线，" else "") +
                        (if (state.on) outputText else "关灯") +
                        (if (state.breathing) "，呼吸，上限 ${state.brightness}%" else "")
                if (!online) disabled()
                progressBarRangeInfo =
                    ProgressBarRangeInfo(if (state.on) state.brightness.toFloat() else 0f, 0f..100f)
                setProgress {
                    if (online) change(it.roundToInt())
                    online
                }
                onClick {
                    if (online) tap()
                    online
                }
            }
            .graphicsLayer {
                rotationX = rx
                rotationY = ry
                rotationZ = sway * .6f
                translationY = -lift * 3.dp.toPx()
                cameraDistance = CardLighting.CAMERA_DISTANCE_DP * density
                shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp)
                clip = true
            }
            .pointerInput(online) {
                if (!online) return@pointerInput
                awaitEachGesture {
                    val first = awaitFirstDown(requireUnconsumed = false)
                    first.consume()
                    val start = first.position
                    val initial = if (current.on) current.brightness else 0
                    var maximum = 0f
                    var last = initial
                    var released = false
                    var releaseTime = first.uptimeMillis
                    x = (start.x / size.width * 2 - 1).coerceIn(-1f, 1f)
                    y = (start.y / size.height * 2 - 1).coerceIn(-1f, 1f)
                    pressed = true
                    try {
                        do {
                            val event = awaitPointerEvent()
                            val pointer = event.changes.firstOrNull { it.id == first.id } ?: break
                            // ACTION_CANCEL arrives as an already-consumed synthetic release.
                            if (pointer.isConsumed) break
                            x = (pointer.position.x / size.width * 2 - 1).coerceIn(-1f, 1f)
                            y = (pointer.position.y / size.height * 2 - 1).coerceIn(-1f, 1f)
                            val delta = pointer.position - start
                            maximum = max(maximum, delta.getDistance())
                            if (maximum > viewConfiguration.touchSlop) {
                                val next =
                                    slideBrightness(initial, (delta.x - delta.y) / (size.width * 2))
                                if (next != last) {
                                    last = next
                                    change(next)
                                }
                            }
                            pointer.consume()
                            if (!pointer.pressed) {
                                released = true
                                releaseTime = pointer.uptimeMillis
                            }
                        } while (pointer.pressed)
                        if (
                            released &&
                                maximum <= viewConfiguration.touchSlop &&
                                releaseTime - first.uptimeMillis < 600
                        )
                            tap()
                    } finally {
                        pressed = false
                    }
                }
            }
    ) {
        Canvas(Modifier.matchParentSize()) {
            drawRect(Brush.radialGradient(listOf(core, surface)))
            val orbit =
                (System.currentTimeMillis() % CardLighting.LIGHT_PERIOD_MS) /
                    CardLighting.LIGHT_PERIOD_MS.toDouble() * 2 * PI
            val lightX = .5f * sin(orbit).toFloat()
            val lightY = -.18f + .36f * cos(orbit).toFloat()
            val reflected = CardLighting.reflection(rx, ry, lightX, lightY)
            val specular = reflected.alpha * if (state.on) 1f else .35f
            val reflectionCenter =
                Offset(
                    (.42f + lightX * .22f + reflected.shiftX) * size.width,
                    (.5f + lightY * .15f + reflected.shiftY) * size.height,
                )
            val angle = Math.toRadians(reflected.angle.toDouble())
            val direction =
                Offset(cos(angle).toFloat(), sin(angle).toFloat()) *
                    sqrt(size.width * size.width + size.height * size.height) *
                    .8f
            val gradient =
                Brush.linearGradient(
                    0f to Color.Transparent,
                    .28f to Color.Transparent,
                    .42f to Color.White.copy(alpha = specular * .1f),
                    .48f to Color.White.copy(alpha = specular * .4f),
                    .5f to Color.White.copy(alpha = specular),
                    .53f to Color.White.copy(alpha = specular * .3f),
                    .7f to Color.Transparent,
                    1f to Color.Transparent,
                    start = reflectionCenter - direction,
                    end = reflectionCenter + direction,
                )
            // Reflection and body share one clipped plane, including the release animation.
            drawRect(gradient)
            drawRoundRect(
                Color(0xFFD8AD65).copy(alpha = .25f),
                cornerRadius = CornerRadius(28.dp.toPx()),
                style = Stroke(1.dp.toPx()),
            )
        }
        val fontScale = LocalDensity.current.fontScale
        val normalSize = ((maxWidth.value - 40f) / fontScale * .25f).coerceIn(32f, 62f)
        Row(
            Modifier.align(Alignment.TopStart).fillMaxWidth().padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Rhodes Light",
                Modifier.weight(1f).floatingInk(rx, ry, 26f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(brush = ink, fontSize = 24.sp, fontWeight = FontWeight.SemiBold),
            )
            GlassStatusLight(online, connecting)
        }
        if (!state.breathing)
            Row(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(20.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val numberStyle =
                    TextStyle(
                        brush = ink,
                        fontSize = normalSize.sp,
                        fontFamily = BrightnessFont,
                        fontWeight = FontWeight.ExtraLight,
                        fontFeatureSettings = "tnum",
                    )
                Text(
                    outputText,
                    Modifier.weight(1f).floatingInk(rx, ry, 36f),
                    maxLines = 1,
                    softWrap = false,
                    style = numberStyle,
                )
                Text(
                    if (online) "滑动调光\n轻点开关" else if (connecting) "正在连接" else "连接后调光",
                    maxLines = 2,
                    softWrap = false,
                    modifier = Modifier.padding(bottom = 6.dp).floatingInk(rx, ry, 20f),
                    style = TextStyle(brush = ink, fontSize = 12.sp, lineHeight = 17.sp),
                )
            }
    }
}

/** Parallel text planes sit in front of the glass; the surface keeps its single reflection. */
private fun Modifier.floatingInk(tiltX: Float, tiltY: Float, depthDp: Float) = graphicsLayer {
    translationX = depthDp * density * tan(Math.toRadians(tiltY.toDouble())).toFloat()
    translationY = -depthDp * density * tan(Math.toRadians(tiltX.toDouble())).toFloat()
}

@Composable
private fun GlassStatusLight(online: Boolean, connecting: Boolean) {
    val color = if (connecting) Gold else if (online) Color(0xFF89BC87) else Color(0xFFD28B76)
    Canvas(
        Modifier.size(28.dp).semantics {
            contentDescription = if (connecting) "正在连接" else if (online) "已连接" else "未连接"
        }
    ) {
        // Every layer fades to zero alpha before its radius; no hard rim or disc edge.
        drawCircle(
            Brush.radialGradient(
                listOf(color.copy(alpha = .24f), color.copy(alpha = .07f), Color.Transparent),
                center = center,
                radius = 14.dp.toPx(),
            ),
            radius = 14.dp.toPx(),
        )
        drawCircle(
            Brush.radialGradient(
                0f to Color.White.copy(alpha = .14f),
                .55f to Cream.copy(alpha = .10f),
                .82f to Cream.copy(alpha = .04f),
                1f to Cream.copy(alpha = 0f),
                center = center,
                radius = 10.5.dp.toPx(),
            ),
            radius = 10.5.dp.toPx(),
        )
        // Grain diffuses the light without a platform blur or an extra offscreen surface.
        for (i in 0 until 20) {
            val angle = i * 2.3999632f
            val radius = sqrt((i + .5f) / 20f) * 8.dp.toPx()
            drawCircle(
                Color.White.copy(alpha = .07f * (1f - radius / 8.dp.toPx())),
                radius = .22.dp.toPx(),
                center = center + Offset(cos(angle), sin(angle)) * radius,
            )
        }
        drawCircle(
            Brush.radialGradient(
                0f to color,
                .35f to color.copy(alpha = .96f),
                .6f to color.copy(alpha = .70f),
                .82f to color.copy(alpha = .24f),
                1f to color.copy(alpha = 0f),
                center = center,
                radius = 6.dp.toPx(),
            ),
            radius = 6.dp.toPx(),
        )
        drawCircle(
            Brush.radialGradient(
                listOf(Color.White.copy(alpha = .16f), Color.White.copy(alpha = 0f)),
                center = center - Offset(1.dp.toPx(), 1.dp.toPx()),
                radius = 1.6.dp.toPx(),
            ),
            radius = 1.6.dp.toPx(),
            center = center - Offset(1.dp.toPx(), 1.dp.toPx()),
        )
    }
}

@Composable
fun BreathPlot(state: LampState) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) withFrameMillis { now = SystemClock.elapsedRealtime() }
    }
    Spacer(
        Modifier.fillMaxWidth()
            .height(96.dp)
            .semantics { contentDescription = "呼吸曲线" }
            .drawWithCache {
                val window = BreathVisual.WINDOW_MS
                val radius = 4.dp.toPx()
                val width = size.width - 2 * radius
                val cycleWidth = width * state.breath.periodMs / window
                fun y(level: Float) = size.height * (.94f - .86f * level)
                val path = Path()
                val cycles = ceil(window / state.breath.periodMs).toInt() + 1
                for (i in 0..cycles * 128) {
                    val x = (i / 128.0 * cycleWidth).toFloat()
                    val py = y(state.breath.wave(i / 128.0 * 2 * PI))
                    if (i == 0) path.moveTo(x, py) else path.lineTo(x, py)
                }
                // Cache the repeated wave; each frame only translates it beneath the fixed x=0 dot.
                onDrawBehind {
                    val time = if (state.on) now else state.sampledAt
                    val offset = (BreathVisual.scrollFraction(state, time) * width).toFloat()
                    drawLine(
                        Muted.copy(alpha = .25f),
                        Offset(radius, size.height * .94f),
                        Offset(size.width - radius, size.height * .94f),
                    )
                    clipRect(left = radius, right = size.width - radius) {
                        translate(left = radius - offset) {
                            drawPath(path, Gold, style = Stroke(2.dp.toPx()))
                        }
                    }
                    if (state.on) {
                        drawCircle(
                            Cream,
                            radius,
                            Offset(radius, y(state.breath.wave(state.phaseAt(time)))),
                        )
                    }
                }
            }
    )
}
