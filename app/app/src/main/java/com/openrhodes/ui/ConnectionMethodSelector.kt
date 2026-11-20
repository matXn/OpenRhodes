package com.openrhodes.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp

import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.cos

private val bleGlyph = listOf(
    "....#....",
    "....##...",
    "....#.#..",
    ".#..#..#.",
    "..#.#.#..",
    "...###...",
    "....#....",
    "...###...",
    "..#.#.#..",
    ".#..#..#.",
    "....#.#..",
    "....##...",
    "....#....",
)

private val bleDots = buildList {
    bleGlyph.forEachIndexed { y, row ->
        row.forEachIndexed { x, cell ->
            if (cell == '#') add(Offset((x - 4) * .071f, (y - 6) * .058f))
        }
    }
}

// 两端使用同一组圆点；按行配对，避免切换时大量轨迹交叉。
private val wifiDots = buildList {
    for ((radius, count) in listOf(.53f to 13, .365f to 9, .20f to bleDots.size - 23)) {
        repeat(count) { index ->
            val angle = (-140 + 100f * index / (count - 1)) * PI / 180
            add(Offset(radius * cos(angle).toFloat(), .255f + radius * sin(angle).toFloat()))
        }
    }
    add(Offset(0f, .255f))
}.sortedWith(compareBy({ it.y }, { it.x }))

@Composable
internal fun ConnectionMethodSelector(wifi: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    val phase by animateFloatAsState(if (wifi) 1f else 0f, tween(760, easing = FastOutSlowInEasing),
        label = "connectionSignal")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(56.dp)) {
            val t = phase
            val tint = lerp(Color(0xFF65ACFF), (if (LightAppearance) LabelInk else Color(0xFFFFFCF4)), t)
            val travel = 4f * t * (1f - t)
            bleDots.forEachIndexed { index, from ->
                val to = wifiDots[index]
                val bend = Offset(.051f * sin(index * 2.4f), .045f * cos(index * 1.7f))
                val position = from + (to - from) * t + bend * travel
                drawCircle(tint, size.minDimension * .0215f,
                    center + position * size.minDimension, alpha = 1f - .22f * travel)
            }
        }
        GlassSegmentSelector("BLE", "Wi-Fi", wifi, enabled, Modifier.weight(1f), touchBloom = true, change = change)
    }
}
