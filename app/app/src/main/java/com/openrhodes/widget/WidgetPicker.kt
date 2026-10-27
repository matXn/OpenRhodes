package com.openrhodes.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.openrhodes.R
import com.openrhodes.RhodesApp
import com.openrhodes.ui.Cream
import com.openrhodes.ui.Muted
import com.openrhodes.ui.RhodesTheme
import kotlinx.coroutines.launch

open class WidgetPicker : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        val id =
            intent.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID,
            )
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        val app = application as RhodesApp
        setContent {
            val lamps by app.catalog.lamps.collectAsStateWithLifecycle()
            RhodesTheme {
                Surface(Modifier.fillMaxSize()) {
                    LazyColumn(Modifier.safeDrawingPadding().padding(24.dp)) {
                        item { Text("选择灯具", style = MaterialTheme.typography.headlineMedium) }
                        if (lamps.isEmpty()) item { Text("请先在 OpenRhodes 中添加灯具。") }
                        items(lamps, key = { it.id }) { lamp ->
                            TextButton(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    WidgetHost.bind(this@WidgetPicker, id, lamp.id)
                                    lifecycleScope.launch {
                                        WidgetHost.render(this@WidgetPicker, id, refresh = false)
                                        setResult(
                                            RESULT_OK,
                                            Intent()
                                                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
                                        )
                                        finish()
                                    }
                                },
                            ) {
                                Column(
                                    Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(lamp.name, color = Cream)
                                    Text(
                                        lamp.hardware?.displayTag
                                            ?: getString(R.string.widget_mac_pending),
                                        color = Muted,
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
