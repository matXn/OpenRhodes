package com.openrhodes.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openrhodes.R

@Composable
internal fun RhodesTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme =
            darkColorScheme(
                primary = Gold,
                onPrimary = Color(0xFF251806),
                primaryContainer = Color(0xFF49351B),
                onPrimaryContainer = Cream,
                secondary = Gold,
                onSecondary = Color(0xFF251806),
                secondaryContainer = Color(0xFF33291C),
                onSecondaryContainer = Cream,
                tertiary = Gold,
                background = Color.Black,
                surface = Color.Black,
                surfaceDim = Color.Black,
                surfaceBright = Color.Black,
                surfaceContainer = Color.Black,
                surfaceContainerLow = Color.Black,
                surfaceContainerLowest = Color.Black,
                surfaceContainerHigh = Color.Black,
                surfaceContainerHighest = Color.Black,
                surfaceTint = Color.Transparent,
                surfaceVariant = Color(0xFF202020),
                onSurface = Cream,
                onSurfaceVariant = Muted,
                onBackground = Cream,
                outline = Color(0xFF514638),
                outlineVariant = Color(0xFF302A22),
            ),
        content = content,
    )
}

internal enum class NavGlyph {
    Back,
    Settings,
    Add,
    Info,
}

/** Code-native icons with fixed 48dp hit targets; no icon font or extra dependency. */
@Composable
internal fun NavIcon(glyph: NavGlyph, description: String, action: () -> Unit) {
    IconButton(
        onClick = action,
        modifier = Modifier.size(48.dp).semantics { contentDescription = description },
    ) {
        Canvas(Modifier.size(24.dp)) {
            val unit = size.width / 24f
            val stroke = 1.7f * unit
            fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
                drawLine(
                    Cream,
                    Offset(x1 * unit, y1 * unit),
                    Offset(x2 * unit, y2 * unit),
                    stroke,
                    StrokeCap.Round,
                )
            when (glyph) {
                NavGlyph.Back -> {
                    line(19f, 12f, 5f, 12f)
                    line(5f, 12f, 11f, 6f)
                    line(5f, 12f, 11f, 18f)
                }
                NavGlyph.Add -> {
                    line(5f, 12f, 19f, 12f)
                    line(12f, 5f, 12f, 19f)
                }
                NavGlyph.Info -> {
                    drawCircle(Cream, 8.5f * unit, style = Stroke(stroke))
                    drawCircle(Cream, unit, Offset(12f * unit, 8f * unit))
                    line(12f, 11f, 12f, 17f)
                }
                NavGlyph.Settings -> {
                    line(4f, 7f, 20f, 7f)
                    line(4f, 17f, 20f, 17f)
                    drawCircle(Color(0xFF0D0C0A), 2.8f * unit, Offset(9f * unit, 7f * unit))
                    drawCircle(
                        Cream,
                        2.8f * unit,
                        Offset(9f * unit, 7f * unit),
                        style = Stroke(stroke),
                    )
                    drawCircle(Color(0xFF0D0C0A), 2.8f * unit, Offset(15f * unit, 17f * unit))
                    drawCircle(
                        Cream,
                        2.8f * unit,
                        Offset(15f * unit, 17f * unit),
                        style = Stroke(stroke),
                    )
                }
            }
        }
    }
}

@Composable
internal fun LampToolbar(
    title: String,
    subtitle: String?,
    back: (() -> Unit)?,
    action: (() -> Unit)?,
    actionGlyph: NavGlyph = NavGlyph.Settings,
    actionDescription: String = "设备设置",
    prominent: Boolean = false,
    largeTitle: Boolean = false,
    underlineEntrance: () -> Float = { 3f },
    refreshPull: () -> Float = { 0f },
    refreshing: Boolean = false,
) {
    if (prominent) {
        HomeBrandToolbar(
            title,
            action,
            actionGlyph,
            actionDescription,
            underlineEntrance,
            refreshPull,
            refreshing,
        )
        return
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).heightIn(min = 60.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (back != null) NavIcon(NavGlyph.Back, "返回", back) else Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(
                title,
                fontSize = if (largeTitle) 32.sp else 21.sp,
                fontWeight = if (largeTitle) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    subtitle,
                    color = Muted,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (action != null) NavIcon(actionGlyph, actionDescription, action)
        else Spacer(Modifier.width(48.dp))
    }
}

@Composable
private fun HomeBrandToolbar(
    title: String,
    action: (() -> Unit)?,
    actionGlyph: NavGlyph,
    actionDescription: String,
    underlineEntrance: () -> Float,
    refreshPull: () -> Float,
    refreshing: Boolean,
) {
    val density = LocalDensity.current
    val sweep = remember { Animatable(0f) }
    val busyMix by
        animateFloatAsState(if (refreshing) 1f else 0f, tween(180), label = "underlineRefresh")
    LaunchedEffect(refreshing) {
        sweep.snapTo(0f)
        if (refreshing) sweep.animateTo(1f, infiniteRepeatable(tween(1000, easing = LinearEasing)))
    }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
        val brandWidth = (maxWidth - 56.dp).coerceAtLeast(1.dp).coerceAtMost(342.dp)
        val brandHeight = brandWidth * (100f / 440f)
        val titleWidth = with(density) { brandWidth.toPx() }
        val ruleTop = with(density) { brandHeight.toPx() } * .92f
        Row(
            Modifier.fillMaxWidth().heightIn(min = 80.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                Image(
                    painter = painterResource(R.drawable.openrhodes_wordmark),
                    contentDescription = null,
                    modifier =
                        Modifier.width(brandWidth)
                            .height(brandHeight)
                            .semantics {
                                text = AnnotatedString(title)
                                heading()
                            }
                            .drawWithContent {
                                drawContent()
                                val entrance = underlineEntrance().coerceIn(0f, 3f)
                                val pull = refreshPull().coerceIn(0f, 1f)
                                val height = 3.5.dp.toPx()
                                val active = maxOf(pull, busyMix)
                                clipRect(
                                    left = 0f,
                                    top = ruleTop,
                                    right = titleWidth,
                                    bottom = ruleTop + height,
                                ) {
                                    if (entrance < 3f && active == 0f) {
                                        // Bright reveal, dark wipe, final bright reveal: every edge
                                        // travels right.
                                        val pass = entrance.toInt()
                                        val edge = titleWidth * (entrance - pass)
                                        val left = if (pass == 1) edge else 0f
                                        val width = if (pass == 1) titleWidth - edge else edge
                                        drawRect(Cream, Offset(left, ruleTop), Size(width, height))
                                    } else {
                                        drawRect(
                                            Cream.copy(alpha = 1f - .78f * active),
                                            Offset(0f, ruleTop),
                                            Size(titleWidth, height),
                                        )
                                        if (pull > 0f)
                                            drawRect(
                                                Cream.copy(alpha = pull * (1f - busyMix)),
                                                Offset(0f, ruleTop),
                                                Size(titleWidth * pull, height),
                                            )
                                        if (busyMix > 0f) {
                                            val width = titleWidth * .3f
                                            val start = (titleWidth + width) * sweep.value - width
                                            drawRect(
                                                Brush.horizontalGradient(
                                                    listOf(
                                                        Cream.copy(alpha = 0f),
                                                        Cream.copy(alpha = busyMix),
                                                        Cream.copy(alpha = 0f),
                                                    ),
                                                    startX = start,
                                                    endX = start + width,
                                                ),
                                                Offset(start, ruleTop),
                                                Size(width, height),
                                            )
                                        }
                                    }
                                }
                            },
                )
            }
            Spacer(Modifier.width(8.dp))
            // The vector's uppercase face is centered at y=40 in its 100-unit viewport.
            Box(Modifier.offset(y = brandHeight * -.10f)) {
                if (action != null) NavIcon(actionGlyph, actionDescription, action)
                else Spacer(Modifier.size(48.dp))
            }
        }
    }
}

@Composable
internal fun LightModeSelector(breathing: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(4.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        listOf(false to "常亮", true to "呼吸").forEach { (mode, label) ->
            val selected = mode == breathing
            Box(
                Modifier.weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (selected) Gold.copy(alpha = if (enabled) .16f else .06f)
                        else Color.Transparent
                    )
                    .selectable(selected, enabled, Role.RadioButton) { change(mode) }
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = (if (selected) Cream else Muted).copy(alpha = if (enabled) 1f else .5f),
                    fontSize = 15.sp,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
internal fun SettingsEntry(title: String, subtitle: String, action: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = action)
            .padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, color = Cream)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, fontSize = 12.sp, color = Muted)
        }
        Canvas(Modifier.padding(start = 16.dp).size(18.dp)) {
            val path =
                Path().apply {
                    moveTo(size.width * .35f, size.height * .2f)
                    lineTo(size.width * .65f, size.height * .5f)
                    lineTo(size.width * .35f, size.height * .8f)
                }
            drawPath(path, Muted, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}
