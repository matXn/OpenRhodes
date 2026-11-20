package com.openrhodes.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
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
import com.openrhodes.domain.UnderlineMotion

@Composable
internal fun RhodesTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme =
            if (LightAppearance) lightColorScheme(
                primary = Gold, onPrimary = Color.White, primaryContainer = Color(0xFFFFDEAA),
                onPrimaryContainer = LabelInk, secondary = Muted, background = PageColor, surface = PageColor,
                surfaceContainer = GroupColor, surfaceContainerLow = GroupColor, surfaceContainerHigh = GroupColor,
                surfaceContainerHighest = Color(0xFFEAE6DF), onSurface = LabelInk, onSurfaceVariant = Muted,
                onBackground = LabelInk, outline = Muted, outlineVariant = Color(0xFFD8D3CA),
            ) else darkColorScheme(
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
                surfaceBright = Color(0xFF39352F),
                surfaceContainer = Color(0xFF211E1A),
                surfaceContainerLow = Color(0xFF191714),
                surfaceContainerLowest = Color(0xFF0D0C0B),
                surfaceContainerHigh = Color(0xFF2B2823),
                surfaceContainerHighest = Color(0xFF35312B),
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

@OptIn(ExperimentalMaterial3Api::class)
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
    onTitleBounds: (androidx.compose.ui.geometry.Rect)->Unit = {},
    underlineEntrance: () -> Float = { 3f },
    onUnderlineEntered: () -> Unit = {},
    refreshPull: () -> Float = { 0f },
    refreshing: Boolean = false,
    refreshRequest: Int = 0,
    onTitleClick: (() -> Unit)? = null,
) {
    if (prominent) {
        HomeBrandToolbar(
            title,
            action,
            actionGlyph,
            actionDescription,
            underlineEntrance,
            onUnderlineEntered,
            refreshPull,
            refreshing,
            refreshRequest,
            onTitleClick,
        )
        return
    }
    if (!largeTitle) {
        TopAppBar(
            title = {
                Column {
                    Text(title, style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.onGloballyPositioned { onTitleBounds(it.boundsInRoot()) })
                    if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            },
            navigationIcon = { if (back != null) NavIcon(NavGlyph.Back, "返回", back) },
            actions = { if (action != null) NavIcon(actionGlyph, actionDescription, action) },
            windowInsets = WindowInsets(0, 0, 0, 0),
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
                modifier=Modifier.onGloballyPositioned {onTitleBounds(it.boundsInRoot())},
                fontSize = if (largeTitle) 32.sp else 21.sp,
                fontWeight = if (largeTitle) FontWeight.Light else FontWeight.Medium,
                fontFamily = if (largeTitle) DeviceTitleFont else null,
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
    onUnderlineEntered: () -> Unit,
    refreshPull: () -> Float,
    refreshing: Boolean,
    refreshRequest: Int,
    onTitleClick: (() -> Unit)?,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressLight by animateFloatAsState(
        if (pressed && onTitleClick != null) 1f else 0f,
        tween(if (pressed) 80 else 180), label = "homeLogoPressLight",
    )
    val titleInteraction = if (onTitleClick == null) Modifier else Modifier
        .semantics { contentDescription = "刷新灯具状态" }
        .clickable(interactionSource = interaction, indication = null,
            role = Role.Button, onClickLabel = "刷新灯具状态", onClick = onTitleClick)
    val density = LocalDensity.current
    val motion = remember {
        UnderlineMotion(entered = underlineEntrance() >= 3f, initialRequest = refreshRequest)
    }
    val currentEntered by rememberUpdatedState(onUnderlineEntered)
    val currentPull by rememberUpdatedState(refreshPull)
    val currentRequest by rememberUpdatedState(refreshRequest)
    val currentRefreshing by rememberUpdatedState(refreshing)
    var rule by remember { mutableStateOf(motion.frame) }
    LaunchedEffect(motion) {
        var previous = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                rule =
                    motion.advance(
                        (now - previous) / 1_000_000_000f,
                        currentPull(),
                        currentRefreshing,
                        request = currentRequest,
                        animations =
                            (coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f) > 0f,
                    )
                if (motion.entered) currentEntered()
                previous = now
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        val brandWidth = (maxWidth - 56.dp).coerceAtLeast(1.dp).coerceAtMost(444.dp)
        val brandHeight = brandWidth * (WordmarkGeometry.Height / WordmarkGeometry.Width)
        val titleWidth = with(density) { brandWidth.toPx() }
        val ruleTop =
            with(density) { brandHeight.toPx() } *
                (WordmarkGeometry.RuleTop / WordmarkGeometry.Height)
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.weight(1f).heightIn(min = 48.dp).then(titleInteraction)
                    .drawBehind {
                        if (pressLight > 0f) {
                            val glowCenter = Offset(size.width * .5f, size.height * .45f)
                            scale(1f, .24f, pivot = glowCenter) {
                                drawCircle(Brush.radialGradient(
                                    listOf(Gold.copy(alpha = .16f * pressLight), Color.Transparent),
                                    center = glowCenter, radius = size.width * .6f,
                                ), radius = size.width * .6f, center = glowCenter)
                            }
                        }
                    },
                contentAlignment = Alignment.CenterStart,
            ) {
                Image(
                    painter = painterResource(R.drawable.openrhodes_wordmark),
                    colorFilter = ColorFilter.tint(LabelInk),
                    contentDescription = null,
                    modifier =
                        Modifier.width(brandWidth)
                            .height(brandHeight)
                            .semantics {
                                text = AnnotatedString(title)
                                heading()
                            }
                            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                            .drawWithContent {
                                drawContent()
                                // 按住时字面轻微染暖，与背后的柔光一起反馈触摸。
                                if (pressLight > 0f) {
                                    drawRect(Gold.copy(alpha = .22f * pressLight),
                                        blendMode = BlendMode.SrcAtop)
                                }
                                val center = titleWidth * rule.sheenPosition
                                val bandWidth = titleWidth * .30f
                                drawRect(
                                    brush =
                                        Brush.linearGradient(
                                            0f to Color.Transparent,
                                            .5f to (if (LightAppearance) Muted else Color.Black).copy(alpha = .48f * rule.sheenAlpha),
                                            1f to Color.Transparent,
                                            start = Offset(center - bandWidth / 2f, size.height),
                                            end = Offset(center + bandWidth / 2f, 0f),
                                        ),
                                    blendMode = BlendMode.SrcAtop,
                                )
                                val height =
                                    size.height *
                                        (WordmarkGeometry.RuleHeight / WordmarkGeometry.Height)
                                // 厚度向上扩展，保持布局高度与下边缘不变。
                                val lineHeight = height * rule.thickness
                                val lineTop = ruleTop + height - lineHeight
                                clipRect(0f, lineTop, titleWidth, ruleTop + height) {
                                    drawRect(
                                        LabelInk.copy(alpha = rule.base),
                                        Offset(0f, lineTop),
                                        Size(titleWidth, lineHeight),
                                    )
                                    val bandLeft = titleWidth * rule.left
                                    val bandRight = titleWidth * rule.right
                                    if (bandRight > bandLeft) {
                                        val flowing =
                                            rule.mode == UnderlineMotion.Mode.Pull ||
                                                rule.mode == UnderlineMotion.Mode.Release
                                        drawRect(
                                            Brush.horizontalGradient(
                                                0f to
                                                    LabelInk.copy(
                                                        alpha = if (flowing) 0f else rule.bandAlpha
                                                    ),
                                                .28f to LabelInk.copy(alpha = rule.bandAlpha),
                                                1f to LabelInk.copy(alpha = rule.bandAlpha),
                                                startX = bandLeft,
                                                endX = bandRight,
                                            ),
                                            Offset(bandLeft, lineTop),
                                            Size(bandRight - bandLeft, lineHeight),
                                        )
                                    }
                                    fun glint(center: Float, width: Float, alpha: Float) {
                                        if (alpha <= 0f) return
                                        val start = titleWidth * (center - width / 2f)
                                        val end = titleWidth * (center + width / 2f)
                                        drawRect(
                                            Brush.horizontalGradient(
                                                listOf(
                                                    Color.Transparent,
                                                    Color.White.copy(alpha = alpha),
                                                    Color.Transparent,
                                                ),
                                                startX = start,
                                                endX = end,
                                            ),
                                            Offset(start, lineTop),
                                            Size(end - start, lineHeight),
                                        )
                                    }
                                    glint(rule.glint, .18f, rule.glintAlpha)
                                    clipRect(
                                        left = titleWidth * rule.left,
                                        right = titleWidth * rule.right,
                                    ) {
                                        glint(rule.right - .035f, .07f, rule.headAlpha)
                                    }
                                }
                            },
                )
            }
            Spacer(Modifier.width(8.dp))
            // 加号与矢量字标的大写字面中心对齐。
            Box(
                Modifier.offset(
                    y = brandHeight * (WordmarkGeometry.CapCenter / WordmarkGeometry.Height - .5f)
                )
            ) {
                if (action != null) NavIcon(actionGlyph, actionDescription, action)
                else Spacer(Modifier.size(48.dp))
            }
        }
    }
}

@Composable
internal fun SettingsEntry(title: String, subtitle: String? = null, expanded: Boolean? = null, action: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = action)
            .heightIn(min = 48.dp)
            .padding(vertical = if (subtitle == null) 4.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, color = Cream)
            if (subtitle != null) {
                Spacer(Modifier.height(4.dp))
                Text(subtitle, fontSize = 12.sp, color = Muted)
            }
        }
        Canvas(Modifier.padding(start = 16.dp).size(18.dp).graphicsLayer { rotationZ = if (expanded == true) 90f else 0f }) {
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

/** 矮窗口为两行成功说明及确认键预留空间，详情与过渡共用卡片尺寸。 */
internal fun detailCardWidthLimit(height:androidx.compose.ui.unit.Dp,insets:androidx.compose.ui.unit.Dp,fontScale:Float):androidx.compose.ui.unit.Dp {
    val header=maxOf(72.dp,(48*fontScale+12).dp)+16.dp
    val reserved=(80+60*fontScale).dp
    return maxOf(120.dp,(height-insets-header-reserved)*4/3)
}
