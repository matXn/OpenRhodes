package com.openrhodes.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.openrhodes.R

/** SVG 源图位于 artwork/settings；Android 使用同路径的原生矢量资源。 */
internal enum class SettingsGlyph(@param:DrawableRes val resource: Int) {
    Name(R.drawable.settings_name),
    Connection(R.drawable.settings_connection),
    Information(R.drawable.settings_information),
    Licenses(R.drawable.settings_licenses),
    Bluetooth(R.drawable.settings_bluetooth),
    Wifi(R.drawable.settings_wifi),
    Hotspot(R.drawable.settings_hotspot),
}

@Composable
internal fun SettingsIcon(glyph: SettingsGlyph, modifier: Modifier = Modifier) {
    Image(painterResource(glyph.resource), contentDescription = null,
        modifier = modifier.size(24.dp), colorFilter = ColorFilter.tint(Muted))
}
