package com.openrhodes.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import com.openrhodes.R

private class PreviousPage(var depth: Int, var displayed: Boolean = false)

@Composable
internal fun Modifier.pageArrival(page: Any, depth: Int, enabled: Boolean = true): Modifier {
    val previous = remember { PreviousPage(depth) }
    val direction = remember(page) { if (depth < previous.depth) -1f else 1f }
    // 首次显示可直接使用，随后只在导航目标变化时播放。旧页面立即退出交互与组合。
    val animation = remember(page) { Animatable(if (previous.displayed && enabled) 0f else 1f) }
    SideEffect {
        previous.depth = depth
        previous.displayed = true
    }
    LaunchedEffect(animation, enabled) {
        if (enabled) animation.animateTo(1f, tween(220, easing = FastOutSlowInEasing))
        else animation.snapTo(1f)
    }
    val distance = with(LocalDensity.current) { 22.dp.toPx() }
    val layoutSign = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1
    return graphicsLayer {
        val progress = if (enabled) animation.value else 1f
        translationX = distance * direction * layoutSign * (1f - progress)
        alpha = .35f + .65f * progress
    }
}

@Composable
internal fun ExpandingSection(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(tween(200)) + fadeIn(tween(160)),
        exit = shrinkVertically(tween(160)) + fadeOut(tween(120)),
    ) {
        Column(Modifier.fillMaxWidth().then(
            if (visible) Modifier else Modifier.clearAndSetSemantics {}.pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                    }
                }
            },
        )) { content() }
    }
}

@Composable
internal fun DialogMotion() {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    SideEffect {
        if (window != null && window.attributes.windowAnimations != R.style.OpenRhodesDialogMotion) {
            window.setWindowAnimations(R.style.OpenRhodesDialogMotion)
        }
    }
}
