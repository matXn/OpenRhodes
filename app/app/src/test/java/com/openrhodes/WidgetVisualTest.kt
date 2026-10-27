package com.openrhodes

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import android.widget.TextView
import com.openrhodes.domain.*
import com.openrhodes.widget.SquareLampWidgetReceiver
import com.openrhodes.widget.WidgetHost
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

class WidgetPreviewProvider : AppWidgetProvider()

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = RhodesApp::class, qualifiers = "w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetVisualTest {
    @Test(timeout = 60000)
    fun sameNameWidgetsKeepFullMacAndControlsAtMinimumSizeAndLargeFont() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as RhodesApp
        try {
            app.awaitReady()
            val endpoint = requireNotNull(Endpoint.parse("127.0.0.1"))
            val lamps =
                listOf("02:00:00:00:00:10", "02:00:00:00:00:20").mapIndexed { i, mac ->
                    Lamp(
                        "widget-$i",
                        "RhodesLight",
                        requireNotNull(HardwareId.parse(mac)),
                        listOf(endpoint),
                    )
                }
            lamps.forEach { lamp ->
                app.catalog.enroll(lamp)
                app.session(lamp.id)
                    .acceptDiscovered(endpoint, LampState(true, 20, hardware = lamp.hardware))
            }
            val manager = Shadows.shadowOf(AppWidgetManager.getInstance(app))
            val labels = mutableSetOf<String>()
            val cases =
                listOf(
                        Triple(160, 64, 1f),
                        Triple(160, 64, 1.3f),
                        Triple(180, 80, 1f),
                        Triple(180, 110, 1f),
                        Triple(180, 110, 1.3f),
                        Triple(260, 160, 1f),
                        Triple(360, 320, 1f),
                    )
                    .map { it to false } +
                    listOf(
                        Triple(110, 110, 1f) to true,
                        Triple(110, 110, 1.3f) to true,
                        Triple(144, 144, 1f) to true,
                        Triple(180, 110, 1f) to true,
                    )
            for ((index, case) in cases.withIndex()) {
                val (settings, squareProvider) = case
                val (widthDp, heightDp, scale) = settings
                RuntimeEnvironment.setFontScale(scale)
                val id =
                    manager.createWidget(WidgetPreviewProvider::class.java, R.layout.lamp_widget)
                if (squareProvider)
                    manager.putWidgetInfo(
                        id,
                        AppWidgetProviderInfo().apply {
                            provider = ComponentName(app, SquareLampWidgetReceiver::class.java)
                        },
                    )
                val lamp = lamps[index % lamps.size]
                WidgetHost.bind(app, id, lamp.id)
                WidgetHost.render(app, id, refresh = false)
                // The Robolectric host does not pass widget sizes to RemoteViews.apply.
                // Use its recorded update with Android's real responsive-size selector.
                val entry =
                    ReflectionHelpers.getField<Map<Int, Any>>(manager, "widgetInfos").getValue(id)
                val update = ReflectionHelpers.getField<RemoteViews>(entry, "lastRemoteViews")
                val chosen =
                    ReflectionHelpers.callInstanceMethod<RemoteViews>(
                        update,
                        "getRemoteViewsToApply",
                        ClassParameter.from(Context::class.java, app),
                        ClassParameter.from(
                            SizeF::class.java,
                            SizeF(widthDp.toFloat(), heightDp.toFloat()),
                        ),
                    )
                val square = squareProvider && (widthDp < 180 || heightDp < 110)
                val compact = !square && (widthDp < 180 || heightDp < 110)
                assertEquals(
                    if (square) R.layout.lamp_widget_square
                    else if (compact) R.layout.lamp_widget_compact else R.layout.lamp_widget,
                    chosen.layoutId,
                )
                val view = chosen.apply(app, null)
                val density = app.resources.displayMetrics.density
                val width = (widthDp * density).roundToInt()
                val height = (heightDp * density).roundToInt()
                repeat(2) {
                    view.measure(
                        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
                    )
                    view.layout(0, 0, width, height)
                }
                val mac = view.findViewById<TextView>(R.id.widget_mac)
                assertEquals(lamp.hardware!!.displayTag, mac.text.toString())
                assertEquals("Full MAC was truncated", 0, mac.layout.getEllipsisCount(0))
                assertTrue("MAC should open the bound lamp", mac.hasOnClickListeners())
                labels += mac.text.toString()
                val cycle = view.findViewById<View>(R.id.widget_cycle)
                assertEquals((48 * density).roundToInt(), cycle.height)
                assertEquals("Control must remain circular", cycle.width, cycle.height)
                if (square) {
                    assertTrue(
                        "Square header overlaps button",
                        (mac.parent as View).bottom <= cycle.top,
                    )
                } else if (compact) {
                    val text = mac.parent as View
                    assertTrue("MAC/header overlaps control", text.right <= cycle.left)
                } else {
                    val controls = cycle.parent as View
                    val header = (mac.parent as View).parent as View
                    assertTrue("MAC/header overlaps controls", header.bottom <= controls.top)
                }
                val controls = if (compact || square) cycle else cycle.parent as View
                assertTrue(
                    "Controls overflow compact widget",
                    controls.bottom <= height - view.paddingBottom,
                )
                val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(image))
                val target =
                    File(
                        "build/reports/widget-review/${if (squareProvider) "square-" else ""}${widthDp}x${heightDp}-font-$scale.png"
                    )
                requireNotNull(target.parentFile).mkdirs()
                target.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                image.recycle()
            }
            assertEquals(2, labels.size)
        } finally {
            RuntimeEnvironment.setFontScale(1f)
            app.scope.cancel()
        }
    }
}
