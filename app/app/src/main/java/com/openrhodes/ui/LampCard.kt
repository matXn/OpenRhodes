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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Rect
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

internal val LightAppearance: Boolean get() = Appearance.light
internal val PageColor: Color get() = if (LightAppearance) Color(0xFFF6F4F0) else Color.Black
internal val GroupColor: Color get() = if (LightAppearance) Color.White else Color(0xFF1B1917)
internal val LabelInk: Color get() = if (LightAppearance) Color(0xFF24211D) else Color.White
internal val Gold: Color get() = if (LightAppearance) Color(0xFF865600) else Color(0xFFEEAC43)
internal val Cream: Color get() = if (LightAppearance) LabelInk else Color(0xFFF4E8D1)
internal val Muted: Color get() = if (LightAppearance) Color(0xFF6C6459) else Color(0xFFA69981)

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
    modifier: Modifier = Modifier,
    interactionEnabled: Boolean = true,
    introProgress: Float? = null,
    cardAspectRatio: Float = 4f / 3f,
    introFinalWidthDp: Float? = null,
    displayName: String = "Rhodes Light",
    gestureHintAlpha: Float = 0f,
    sharedFrame: CardFrame? = null,
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
    LaunchedEffect(Unit) {
        while (true) withFrameMillis {
            now = SystemClock.elapsedRealtime()
        }
    }
    val drift by
        animateFloatAsState(
            if (state.on && state.breathing && online && !pressed) 1f else 0f,
            tween(if (pressed) 100 else 450),
            label = "breathCardDrift",
        )
    val presentation = remember { CardPresentation() }
    val displayNow = if (introProgress != null)
        state.sampledAt + (introProgress * AddSuccessTiming.DURATION).coerceAtMost(1000f).toLong()
    else now
    val frame = sharedFrame ?: presentation.sample(state, displayNow)
    val sway = sin(frame.phase).toFloat() * drift
    val lift = cos(frame.phase).toFloat() * drift
    val rx = if (introProgress != null) AddSuccessTiming.x(introProgress) + sway * 1.2f * AddSuccessTiming.chrome(introProgress) else touchRx + sway * 1.2f
    val ry = if (introProgress != null) AddSuccessTiming.y(introProgress) + lift * 1.1f * AddSuccessTiming.chrome(introProgress) else touchRy + lift * 1.1f
    val mix = introProgress?.let(AddSuccessTiming::chrome) ?: 0f
    val energy = if (introProgress == null) frame.energy else frame.energy * (1-mix) + BreathVisual.cardEnergy(state, now) * mix
    val lighting = CardLighting.surface(energy, state.on)
    val surface = Color.hsl(lighting.hue, lighting.saturation, lighting.body)
    val core = Color.hsl(lighting.hue, lighting.saturation, lighting.core)
    val inkColors = remember(energy) { cardInk(energy) }
    val animatedInkTop by animateColorAsState(inkColors[0], tween(120), label = "inkTop")
    val animatedInkBottom by animateColorAsState(inkColors[1], tween(120), label = "inkBottom")
    val inkTop=if(introProgress!=null)inkColors[0] else animatedInkTop
    val inkBottom=if(introProgress!=null)inkColors[1] else animatedInkBottom
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
    val reflectionStart=remember {System.currentTimeMillis()}
    val liveReflection=System.currentTimeMillis()
    val frozenReflection=reflectionStart+(introProgress?.times(AddSuccessTiming.DURATION) ?: 0f).coerceAtMost(1000f).toLong()
    val period=CardLighting.LIGHT_PERIOD_MS
    val reflectionDelta=((liveReflection-frozenReflection+period/2)%period+period)%period-period/2
    val reflectionNow=if(introProgress==null)liveReflection else frozenReflection+(reflectionDelta*mix).toLong()
    BoxWithConstraints(
        modifier.fillMaxWidth()
            .aspectRatio(cardAspectRatio)
            .semantics {
                contentDescription = "Rhodes Light"
                stateDescription =
                    (if (connecting) "正在连接，" else if (!online) "离线，" else "") +
                        (if (state.on) outputText else "关灯") +
                        (if (state.breathing) "，呼吸，上限 ${state.brightness}%" else "")
                if (!online || !interactionEnabled) disabled()
                progressBarRangeInfo =
                    ProgressBarRangeInfo(if (state.on) state.brightness.toFloat() else 0f, 0f..100f)
                setProgress {
                    if (online && interactionEnabled) change(it.roundToInt())
                    online && interactionEnabled
                }
                onClick {
                    if (online && interactionEnabled) tap()
                    online && interactionEnabled
                }
            }
            .drawWithContent {
                if (introProgress == null) drawContent() else {
                    // 显式 X→Y→Z 投影，硬件画布与离屏渲染共用同一几何模型。
                    val ax=Math.toRadians(rx.toDouble());val ay=Math.toRadians(ry.toDouble())
                    val az=Math.toRadians((AddSuccessTiming.z(introProgress)+sway*.6f*AddSuccessTiming.chrome(introProgress)).toDouble())
                    val source=floatArrayOf(0f,0f,size.width,0f,size.width,size.height,0f,size.height)
                    val dest=FloatArray(8);val distance=4*size.height
                    for(i in 0..3) {
                        val x=source[i*2]-size.width/2;val y=source[i*2+1]-size.height/2
                        val yy=y*cos(ax);val zz=y*sin(ax)
                        val xx=x*cos(ay)+zz*sin(ay);val z=-x*sin(ay)+zz*cos(ay)
                        val scale=distance/(distance-z)
                        dest[i*2]=(size.width/2+(xx*cos(az)-yy*sin(az))*scale).toFloat()
                        dest[i*2+1]=(size.height/2+(xx*sin(az)+yy*cos(az))*scale).toFloat()
                    }
                    val matrix=android.graphics.Matrix().apply { setPolyToPoly(source,0,dest,0,4) }
                    drawIntoCanvas { canvas ->
                        val native=canvas.nativeCanvas;native.save();native.concat(matrix)
                        drawContent();native.restore()
                    }
                }
            }
            .then(if (introProgress == null) Modifier.graphicsLayer {
                rotationX=rx;rotationY=ry;rotationZ=sway*.6f
                translationY=-lift*3.dp.toPx()
                cameraDistance=CardLighting.CAMERA_DISTANCE_DP*density
                shape=androidx.compose.foundation.shape.RoundedCornerShape(28.dp)
                clip=true
            } else Modifier.offset { androidx.compose.ui.unit.IntOffset(0,(-lift*3.dp.toPx()*AddSuccessTiming.chrome(introProgress)).roundToInt()) })
            .drawWithContent {
                if (introProgress == null) drawContent() else {
                    val outline=Path().apply { addRoundRect(RoundRect(Rect(Offset.Zero,size),CornerRadius(28.dp.toPx()))) }
                    clipPath(outline) { this@drawWithContent.drawContent() }
                }
            }
            .pointerInput(online, interactionEnabled) {
                if (!online || !interactionEnabled) return@pointerInput
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
                (reflectionNow % CardLighting.LIGHT_PERIOD_MS) /
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
        val normalSize = (((introFinalWidthDp ?: maxWidth.value) - 40f) / fontScale * .25f).coerceIn(32f, 62f)
        Row(
            Modifier.align(Alignment.TopStart).fillMaxWidth().padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                lampDisplayName(displayName),
                Modifier.weight(1f).wrapContentWidth(Alignment.Start)
                    .particleScan(introProgress?.let(AddSuccessTiming::transfer) ?: 1f).floatingInk(rx, ry, 26f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(brush = ink, fontSize = 24.sp, fontWeight = FontWeight.SemiBold),
            )
            GlassStatusLight(online, connecting, introProgress?.let(AddSuccessTiming::status) ?: 1f)
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
                    Modifier.weight(1f).leafLanding(introProgress, 1).floatingInk(rx, ry, 36f),
                    maxLines = 1,
                    softWrap = false,
                    style = numberStyle,
                )
                val hintAlpha = if(introProgress != null) AddSuccessTiming.leaf(introProgress, 2) else gestureHintAlpha
                if (!online || hintAlpha > 0f) {
                    Text(
                        if (online) "滑动调光\n轻点开关" else if (connecting) "正在连接" else "连接后调光",
                        maxLines = 2,
                        softWrap = false,
                        modifier = Modifier.padding(bottom = 6.dp)
                            .graphicsLayer { alpha = if(online) hintAlpha else 1f }
                            .floatingInk(rx, ry, 20f),
                        style = TextStyle(brush = ink, fontSize = 12.sp, lineHeight = 17.sp),
                    )
                }
            }
    }
}

// 整组文字沿衰减弧线落位，保留字形与字距，不逐字拆散。
private fun Modifier.leafLanding(progress: Float?, group: Int): Modifier =
    if(progress==null)this else fadeContent(AddSuccessTiming.leaf(progress,group))

/** Parallel text planes sit in front of the glass; the surface keeps its single reflection. */
private fun Modifier.floatingInk(tiltX: Float, tiltY: Float, depthDp: Float) = graphicsLayer {
    translationX = depthDp * density * tan(Math.toRadians(tiltY.toDouble())).toFloat()
    translationY = -depthDp * density * tan(Math.toRadians(tiltX.toDouble())).toFloat()
}

@Composable
private fun GlassStatusLight(online: Boolean, connecting: Boolean, opacity: Float = 1f) {
    val color = if (connecting) Gold else if (online) Color(0xFF89BC87) else Color(0xFFD28B76)
    Canvas(
        Modifier.size(28.dp).graphicsLayer { alpha = opacity }.semantics {
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
fun BreathPlot(state: LampState, sharedFrame: CardFrame? = null) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) withFrameMillis { now = SystemClock.elapsedRealtime() }
    }
    val presentation = remember { CardPresentation() }
    val frame = sharedFrame ?: presentation.sample(state, now)
    val currentFrame by rememberUpdatedState(frame)
    val parameters = frame.breath
    Spacer(
        Modifier.fillMaxWidth()
            .height(96.dp)
            .semantics { contentDescription = "呼吸曲线" }
            .drawWithCache {
                val window = BreathVisual.WINDOW_MS
                val radius = 4.dp.toPx()
                val width = size.width - 2 * radius
                val cycleWidth = width * parameters.periodMs / window
                fun y(level: Float) = size.height * (.94f - .86f * level)
                val path = Path()
                val cycles = ceil(window / parameters.periodMs).toInt() + 1
                for (i in 0..cycles * 128) {
                    val x = (i / 128.0 * cycleWidth).toFloat()
                    val py = y(parameters.wave(i / 128.0 * 2 * PI))
                    if (i == 0) path.moveTo(x, py) else path.lineTo(x, py)
                }
                // Cache the repeated wave; each frame only translates it beneath the fixed x=0 dot.
                onDrawBehind {
                    val phase = currentFrame.phase
                    val offset = (BreathVisual.scrollFraction(parameters, phase) * width).toFloat()
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
                            Offset(radius, y(parameters.wave(phase))),
                        )
                    }
                }
            }
    )
}
