package com.openrhodes

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import com.openrhodes.ui.ConnectionMethodSelector
import com.openrhodes.ui.RhodesTheme
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConnectionMethodVisualTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun switchBothDirections() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            compose.mainClock.autoAdvance = false
            activity.get().setContent {
                RhodesTheme {
                    var wifi by remember { mutableStateOf(false) }
                    Box(Modifier.fillMaxSize().background(Color.Black).padding(horizontal = 20.dp),
                        contentAlignment = Alignment.Center) {
                        ConnectionMethodSelector(wifi, true) { wifi = it }
                    }
                }
            }
            val root = activity.get().window.decorView
            root.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 720, 1600)
            compose.mainClock.advanceTimeBy(300)
            compose.waitForIdle()
            val button = compose.onNodeWithText("BLE")
            button.assertIsSelected()
            val export = System.getenv("RHODES_SIGNAL_FRAMES")?.let { File(it).apply { mkdirs() } }
            val location = button.fetchSemanticsNode().boundsInWindow
            val bitmap = Bitmap.createBitmap(720, 240, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap).apply { translate(0f, 120f - location.center.y) }
            repeat(144) { frame ->
                val wifiButton = compose.onNodeWithText("Wi-Fi")
                if (frame == 17) wifiButton.performTouchInput { down(centerRight - center * .37f) }
                if (frame == 24) wifiButton.performTouchInput { up() }
                if (frame == 81) button.performTouchInput { down(centerLeft + center * .37f) }
                if (frame == 88) button.performTouchInput { up() }
                compose.mainClock.advanceTimeBy(32)
                if (frame == 63) compose.onNodeWithText("Wi-Fi").assertIsSelected()
                if (export != null) {
                    compose.waitForIdle()
                    root.draw(canvas)
                    File(export, "signal-%03d.png".format(frame)).outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
            }
            bitmap.recycle()
            button.assertIsSelected()
        } finally {
            activity.pause().stop().destroy()
        }
    }
}
