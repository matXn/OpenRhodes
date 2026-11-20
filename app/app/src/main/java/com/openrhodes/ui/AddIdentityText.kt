package com.openrhodes.ui

import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow

internal data class IdentityPresentation(val owner: Any, val layout: TextLayoutResult, val color: Color,
    val position: Offset, val alpha: Float, val tag: String, val description: String)
internal class AddIdentityLayer {
    var presentation by mutableStateOf<IdentityPresentation?>(null)
    fun clear(owner: Any) { if(presentation?.owner === owner) presentation=null }
}
internal val LocalAddIdentityLayer = staticCompositionLocalOf<AddIdentityLayer?> { null }

/** 文字始终留在窗口顶层，场景只交接布局数据，不跨子组合搬移或重建节点。 */
@Composable
internal fun SharedAddIdentity(content: @Composable () -> Unit) {
    val layer = remember { AddIdentityLayer() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    CompositionLocalProvider(LocalAddIdentityLayer provides layer) {
        Box(Modifier.fillMaxSize().onGloballyPositioned { origin=it.positionInRoot() }) {
            content()
            IdentityOverlay(layer, origin)
        }
    }
}

@Composable
private fun IdentityOverlay(layer: AddIdentityLayer, origin: Offset) {
    val density=LocalDensity.current
    layer.presentation?.let { visual ->
        IdentityLabel(visual.layout, visual.color, Modifier
            .absoluteOffset { IntOffset((visual.position.x-origin.x).roundToInt(),(visual.position.y-origin.y).roundToInt()) }
            .width(with(density){visual.layout.size.width.coerceAtLeast(1).toDp()})
            .testTag(visual.tag)
            .semantics { contentDescription=visual.description }
            .graphicsLayer { alpha=visual.alpha })
    }
}

@Composable
internal fun AddIdentityText(layout: TextLayoutResult, color: Color, modifier: Modifier, shared: Boolean = true,
    position: Offset = Offset.Zero, alpha: Float = 1f, tag: String = "", description: String = "") {
    val layer = LocalAddIdentityLayer.current.takeIf { shared }
    if (layer == null) IdentityLabel(layout, color, modifier)
    else {
        val owner = remember { Any() }
        SideEffect { layer.presentation=IdentityPresentation(owner, layout, color, position, alpha, tag, description) }
        DisposableEffect(layer, owner) { onDispose { layer.clear(owner) } }
    }
}

@Composable
private fun IdentityLabel(layout: TextLayoutResult, color: Color, modifier: Modifier) {
    Text(layout.layoutInput.text, color=color, style=layout.layoutInput.style,
        maxLines=2, overflow=TextOverflow.Ellipsis, modifier=modifier)
}
