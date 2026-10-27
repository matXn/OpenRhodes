package com.openrhodes.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.*
import android.net.Uri
import android.os.Build
import android.util.SizeF
import android.widget.RemoteViews
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.stringPreferencesKey
import com.openrhodes.MainActivity
import com.openrhodes.R
import com.openrhodes.RhodesApp
import com.openrhodes.domain.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Kept names are Android component identifiers, not inherited implementations. */
private val providers =
    listOf(
        "com.pearlnode.ui.widget.ShellyDeviceWidgetReceiver",
        "com.pearlnode.ui.widget.ShellyWidgetReceiver",
        "com.openrhodes.widget.SquareLampWidgetReceiver",
    )

object WidgetHost {
    private val migrations = ConcurrentHashMap<Int, Mutex>()

    private fun prefs(context: Context) =
        context.getSharedPreferences("rhodes-widgets", Context.MODE_PRIVATE)

    fun bind(context: Context, id: Int, lampId: String) {
        check(prefs(context).edit().putString("lamp.$id", lampId).commit()) { "小组件配置保存失败" }
    }

    suspend fun selected(context: Context, id: Int): String? =
        migrations
            .computeIfAbsent(id) { Mutex() }
            .withLock {
                prefs(context).getString("lamp.$id", null)?.let {
                    return@withLock it
                }
                val legacy = File(context.filesDir, "datastore/appWidget-$id.preferences_pb")
                if (!legacy.exists()) return@withLock null
                val job = SupervisorJob()
                try {
                    val store =
                        PreferenceDataStoreFactory.create(
                            scope = CoroutineScope(job + Dispatchers.IO),
                            produceFile = { legacy },
                        )
                    store.data.first()[stringPreferencesKey("selectedDeviceId")]?.also {
                        bind(context, id, it)
                    }
                } finally {
                    job.cancelAndJoin()
                }
            }

    fun forget(context: Context, id: Int) {
        prefs(context).edit().remove("lamp.$id").remove("status.$id").remove("stage.$id").apply()
    }

    fun pin(context: Context, lampId: String) {
        val manager = AppWidgetManager.getInstance(context)
        if (!manager.isRequestPinAppWidgetSupported) return
        val callback =
            Intent(context, PinResult::class.java)
                .setData(Uri.parse("openrhodes://pin/$lampId"))
                .putExtra("deviceId", lampId)
        val pending =
            PendingIntent.getBroadcast(
                context,
                lampId.hashCode(),
                callback,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
        manager.requestPinAppWidget(ComponentName(context, providers.first()), null, pending)
    }

    suspend fun render(context: Context, id: Int, refresh: Boolean = true, cycle: Boolean = false) {
        val app = context.applicationContext as RhodesApp
        app.awaitReady()
        val selection = selected(context, id)
        val lamp = selection?.let(app.catalog::find)
        val session = lamp?.let { app.session(it.id) }
        fun drawLayout(layout: Int, error: String?): RemoteViews {
            val state = session?.observation?.value?.state
            val view = RemoteViews(context.packageName, layout)
            view.setTextViewText(R.id.widget_name, lamp?.name ?: "选择灯具")
            view.setTextViewText(
                R.id.widget_mac,
                lamp?.hardware?.displayTag ?: context.getString(R.string.widget_mac_pending),
            )
            val failed = error != null || session?.observation?.value?.error != null
            val (dot, description) =
                when {
                    failed -> R.drawable.widget_dot_offline to R.string.widget_connection_offline
                    state != null ->
                        R.drawable.widget_dot_online to R.string.widget_connection_online
                    else -> R.drawable.widget_dot_unknown to R.string.widget_connection_unknown
                }
            view.setImageViewResource(R.id.widget_connection, dot)
            view.setContentDescription(R.id.widget_connection, context.getString(description))
            val cached = prefs(context).getString("status.$id", null)
            val status =
                when {
                    failed -> "暂时离线 · 点按重试"
                    state == null -> cached ?: "点按刷新"
                    state.on -> "已开启"
                    else -> "已关闭"
                }
            view.setTextViewText(R.id.widget_status, status)
            val stage =
                state?.let { s ->
                    if (!s.on) 0 else listOf(5, 20, 50, 100).count { it <= s.brightness }
                } ?: prefs(context).getInt("stage.$id", 0)
            view.setTextViewText(
                R.id.widget_stages,
                (1..4).joinToString(" ") { if (it <= stage) "●" else "·" },
            )
            if (state != null && !failed)
                prefs(context)
                    .edit()
                    .putString("status.$id", status)
                    .putInt("stage.$id", stage)
                    .apply()
            val open =
                if (lamp == null)
                    Intent()
                        .setClassName(
                            context,
                            "com.pearlnode.ui.widget.ShellyDeviceWidgetConfigActivity",
                        )
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                else
                    Intent(context, MainActivity::class.java)
                        .putExtra("deviceId", lamp.id)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            open.data = Uri.parse("openrhodes://widget/$id/open")
            val openLamp =
                PendingIntent.getActivity(
                    context,
                    id,
                    open,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            view.setOnClickPendingIntent(R.id.widget_name, openLamp)
            view.setOnClickPendingIntent(R.id.widget_mac, openLamp)
            for ((target, action) in
                listOf(R.id.widget_cycle to "cycle", R.id.widget_status to "refresh")) {
                val intent =
                    Intent()
                        .setComponent(ComponentName(context, providers.first()))
                        .setAction("com.openrhodes.$action")
                        .setData(Uri.parse("openrhodes://widget/$id/$action"))
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                view.setOnClickPendingIntent(
                    target,
                    if (lamp == null)
                        PendingIntent.getActivity(
                            context,
                            id,
                            open,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        )
                    else
                        PendingIntent.getBroadcast(
                            context,
                            id,
                            intent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        ),
                )
            }
            return view
        }
        fun draw(error: String? = null) {
            val manager = AppWidgetManager.getInstance(context)
            val square =
                manager.getAppWidgetInfo(id)?.provider?.className ==
                    SquareLampWidgetReceiver::class.java.name
            val view =
                if (Build.VERSION.SDK_INT >= 31) {
                    // The host chooses against the actual size, including rotation and split
                    // screen.
                    RemoteViews(
                        mapOf(
                            (if (square) SizeF(110f, 110f) else SizeF(160f, 64f)) to
                                drawLayout(
                                    if (square) R.layout.lamp_widget_square
                                    else R.layout.lamp_widget_compact,
                                    error,
                                ),
                            SizeF(180f, 110f) to drawLayout(R.layout.lamp_widget, error),
                        )
                    )
                } else {
                    val options = manager.getAppWidgetOptions(id)
                    val width =
                        options.getInt(
                            AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,
                            if (square) 110 else 180,
                        )
                    val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110)
                    drawLayout(
                        if (square && (width < 180 || height < 110)) R.layout.lamp_widget_square
                        else if (width < 180 || height < 110) R.layout.lamp_widget_compact
                        else R.layout.lamp_widget,
                        error,
                    )
                }
            manager.updateAppWidget(id, view)
        }
        draw()
        if (session == null) return
        try {
            if (cycle) session.execute(Command.Cycle)
            else if (refresh) session.refresh(force = true)
            draw()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            draw(failure.message ?: "连接失败")
        }
    }

    suspend fun refreshAll(context: Context, refresh: Boolean = true) = coroutineScope {
        val manager = AppWidgetManager.getInstance(context)
        providers
            .flatMap { manager.getAppWidgetIds(ComponentName(context, it)).toList() }
            .forEach { id -> launch { render(context, id, refresh = refresh) } }
    }
}

open class LampWidgetReceiver : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in listOf("com.openrhodes.cycle", "com.openrhodes.refresh")) {
            val id =
                intent.getIntExtra(
                    AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID,
                )
            if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return
            work(context) {
                WidgetHost.render(context, id, cycle = intent.action == "com.openrhodes.cycle")
            }
        } else super.onReceive(context, intent)
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        work(context) {
            coroutineScope { ids.forEach { id -> launch { WidgetHost.render(context, id) } } }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        id: Int,
        options: android.os.Bundle,
    ) {
        work(context) { WidgetHost.render(context, id, refresh = false) }
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        ids.forEach { WidgetHost.forget(context, it) }
    }

    private fun work(context: Context, block: suspend () -> Unit) {
        val pending = goAsync()
        (context.applicationContext as RhodesApp).scope.launch {
            try {
                withTimeout(9000) { block() }
            } catch (cancel: CancellationException) {
                if (cancel !is TimeoutCancellationException) throw cancel
            } catch (failure: Exception) {
                android.util.Log.w("OpenRhodes", "Widget update failed", failure)
            } finally {
                pending.finish()
            }
        }
    }
}

class PinResult : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id =
            intent.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID,
            )
        val selection = intent.getStringExtra("deviceId") ?: return
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return
        WidgetHost.bind(context, id, selection)
        val pending = goAsync()
        (context.applicationContext as RhodesApp).scope.launch {
            try {
                withTimeout(9000) { WidgetHost.render(context, id) }
            } finally {
                pending.finish()
            }
        }
    }
}

class SquareLampWidgetReceiver : LampWidgetReceiver()
