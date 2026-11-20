package com.openrhodes.ui

import android.content.res.Configuration
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal object Appearance {
    var light by mutableStateOf(false)
        private set

    fun update(configuration: Configuration) {
        light = (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_YES
    }
}
