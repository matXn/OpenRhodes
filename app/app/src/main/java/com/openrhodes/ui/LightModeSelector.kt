package com.openrhodes.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.sin
import kotlin.math.cos

private val ModeEasing = CubicBezierEasing(.22f, .61f, .36f, 1f)
private val ModeText: Color get() = if (LightAppearance) LabelInk else Color(0xFFFFEBC8)
private val LampYellow = Color(0xFFFFAE10)

@Composable
internal fun LightModeSelector(breathing: Boolean, enabled: Boolean, lightEnergy: Float = 1f, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ModeStatusOrb(breathing, enabled, lightEnergy)
        GlassSegmentSelector("常亮", "呼吸", breathing, enabled,
            modifier = Modifier.weight(1f).testTag("mode-capsule"), lightEnergy = lightEnergy, change = change)
    }
}

@Composable
internal fun GlassSegmentSelector(
    firstLabel: String, secondLabel: String, secondSelected: Boolean, enabled: Boolean,
    modifier: Modifier = Modifier, lightEnergy: Float = 1f, touchBloom: Boolean = false, change: (Boolean) -> Unit,
) {
    val position by animateFloatAsState(if (secondSelected) 1f else 0f,
        tween(320, easing = ModeEasing), label = "modeGlassPosition")
    val shape = RoundedCornerShape(28.dp)
    BoxWithConstraints(modifier.height(56.dp).clip(shape)
        .background(LampYellow.copy(alpha = .055f)).selectableGroup()) {
        val half = maxWidth / 2
        Box(Modifier.offset(x = 2.dp + half * position, y = 2.dp)
            .width(half - 4.dp).height(52.dp).clip(shape)
            .graphicsLayer { alpha = if (enabled) .35f + .65f * lightEnergy.coerceIn(0f, 1f) else .25f }) {
            Canvas(Modifier.fillMaxSize()) {
                drawRect(Brush.linearGradient(listOf(
                    LampYellow.copy(alpha = .46f), LampYellow.copy(alpha = .19f)),
                    Offset.Zero, Offset(size.width, size.height)))
                // 只有宽而柔和的表面反光，不沿轮廓描边或增加边缘光圈。
                glassReflection(.16f)
            }
        }
        Row(Modifier.fillMaxSize()) {
            listOf(false to firstLabel, true to secondLabel).forEach { (mode, label) ->
                val selected = mode == secondSelected
                val interactions = remember { MutableInteractionSource() }
                val pressed by interactions.collectIsPressedAsState()
                val focused by interactions.collectIsFocusedAsState()
                Box(Modifier.weight(1f).fillMaxHeight().clip(shape)
                    .background(if (enabled && ((pressed && !touchBloom) || focused)) ModeText.copy(alpha = .10f) else Color.Transparent)
                    .then(if (touchBloom && enabled) pressBloom(interactions) else Modifier)
                    .selectable(selected = selected, interactionSource = interactions,
                        indication = null, enabled = enabled, role = Role.RadioButton) {
                        if (!selected) change(mode)
                    }, contentAlignment = Alignment.Center) {
                    Text(label, color = (if (selected) ModeText else Muted)
                        .copy(alpha = if (enabled) 1f else .5f),
                        fontSize = 16.sp, fontWeight = FontWeight.Normal,
                        maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

@Composable
private fun pressBloom(interactions: MutableInteractionSource): Modifier {
    var active by remember { mutableStateOf<PressInteraction.Press?>(null) }
    val expansion = remember { Animatable(0f) }
    LaunchedEffect(interactions) {
        interactions.interactions.filterIsInstance<PressInteraction>().collectLatest { event ->
            when (event) {
                is PressInteraction.Press -> {
                    expansion.snapTo(0f)
                    active = event
                }
                is PressInteraction.Release -> if (event.press === active) {
                    expansion.animateTo(1f, tween(160, easing = LinearOutSlowInEasing))
                    active = null
                }
                is PressInteraction.Cancel -> active = null
            }
        }
    }
    return Modifier.drawBehind {
        val press = active ?: return@drawBehind
        val origin = if (press.pressPosition.isValid()) press.pressPosition else center
        val progress = expansion.value
        val radius = 24.dp.toPx() + size.width * .6f * progress
        drawCircle(Brush.radialGradient(listOf(
            ModeText.copy(alpha = .43f), LampYellow.copy(alpha = .18f),
            LampYellow.copy(alpha = 0f)), origin, radius),
            radius, origin, alpha = 1f - progress)
    }
}

@Composable
private fun ModeStatusOrb(breathing: Boolean, enabled: Boolean, lightEnergy: Float) {
    val mixture by animateFloatAsState(if (breathing) 1f else 0f,
        tween(380, easing = ModeEasing), label = "modeFluidBlend")
    // 相位持续积累，切换加速只改变流速，不重置液体位置。
    val boost = remember { Animatable(0f) }
    var previousMode by remember { mutableStateOf(breathing) }
    LaunchedEffect(breathing) {
        if (previousMode != breathing) {
            previousMode = breathing
            boost.snapTo(1f)
            boost.animateTo(0f, tween(1000, easing = LinearOutSlowInEasing))
        }
    }
    var phase by remember { mutableFloatStateOf(0f) }
    val flowing by rememberUpdatedState(enabled && (breathing || mixture > .001f))
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) withFrameNanos { now ->
            if (last != 0L && flowing) {
                val dt = ((now-last)/1_000_000_000f).coerceIn(0f, .05f)
                phase = (phase + dt * .65f * (1f + boost.value * 3f)) % (Math.PI.toFloat()*20f)
            }
            last = now
        }
    }
    Box(Modifier.size(56.dp).clip(CircleShape).background(LampYellow.copy(alpha = .075f))
        .testTag("mode-light").semantics {
            contentDescription = if (breathing) "当前模式：呼吸" else "当前模式：常亮"
        }.graphicsLayer { alpha = if (enabled) 1f else .45f }) {
        // 球体在玻璃后方，透明边缘让常亮光源平滑融入圆形罩片。
        Canvas(Modifier.fillMaxSize()
            .graphicsLayer { alpha = lightEnergy.coerceIn(0f, 1f) }
            .blur(4.dp, BlurredEdgeTreatment.Unbounded)) {
            val c = center
            val r = size.minDimension * .47f
            drawCircle(Brush.radialGradient(
                0f to Color(0xFFFFE7A6),
                .22f to Color(0xFFFFD16B),
                .50f to LampYellow.copy(alpha = .62f),
                .78f to LampYellow.copy(alpha = .14f),
                1f to Color.Transparent,
                center = c, radius = r), r, c, alpha = 1f-mixture)
            if (mixture > 0f) {
                drawCircle(Brush.radialGradient(
                    0f to Color(0xFFFFCF49), .68f to LampYellow,
                    .91f to LampYellow.copy(alpha=.75f), 1f to Color.Transparent,
                    center=c, radius=r), r, c, alpha=mixture)
                // 大块半透明乳白色团互相穿流；径向透明渐变保证低版本也没有硬边。
                for (i in 0..2) {
                    val t = phase + i * 2.094f
                    val location = Offset(
                        c.x + sin(t) * r * .43f,
                        c.y + cos(t * 1.1f + i) * r * .38f)
                    val radius = r * (.65f + .12f * sin(t * 1.3f))
                    drawCircle(Brush.radialGradient(
                        0f to Color(0xFFFFF5DC).copy(alpha=.96f),
                        .32f to Color(0xFFFFEBC0).copy(alpha=.82f),
                        .68f to Color(0xFFFFE6A0).copy(alpha=.34f),
                        1f to Color.Transparent,
                        center=location, radius=radius), radius, location, alpha=mixture)
                }
            }
        }
        Canvas(Modifier.fillMaxSize()) { glassReflection(.09f) }
    }
}

private fun DrawScope.glassReflection(opacity: Float) {
    drawRect(Brush.linearGradient(
        0f to Color.Transparent,
        .25f to Color(0xFFFFF2CF).copy(alpha=opacity*.35f),
        .42f to Color(0xFFFFF2CF).copy(alpha=opacity),
        .65f to Color(0xFFFFF2CF).copy(alpha=opacity*.25f),
        1f to Color.Transparent,
        start=Offset.Zero, end=Offset(size.width,size.height)))
}
