package com.openrhodes

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.openrhodes.data.discoverLamps
import com.openrhodes.data.localNetworkChanges
import com.openrhodes.ui.RhodesRoot
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
open class MainActivity : ComponentActivity() {
    private val app
        get() = application as RhodesApp

    private val requestedId = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestedId.value =
            intent.getStringExtra("deviceId") ?: savedInstanceState?.getString("deviceId")
        setContent { RhodesRoot(app, requestedId) }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                try {
                    app.awaitReady()
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    return@repeatOnLifecycle
                }
                app.foregrounded()
                launch {
                    var firstRound = true
                    while (isActive) {
                        // Each round completes before the next. Different lamps remain concurrent.
                        supervisorScope {
                            app.catalog.lamps.value.forEach { lamp ->
                                launch { app.session(lamp.id).refresh(force = firstRound) }
                            }
                        }
                        firstRound = false
                        delay(5000)
                    }
                }
                launch {
                    localNetworkChanges(this@MainActivity).debounce(350).collect { snapshot ->
                        if (app.observeNetwork(snapshot)) {
                            app.rescan()
                            app.catalog.lamps.value.forEach { lamp ->
                                launch { app.session(lamp.id).refresh(force = true) }
                            }
                        }
                    }
                }
                launch {
                    app.discoveryRevision.collectLatest {
                        app.nearby.value = emptyMap()
                        while (isActive) {
                            app.discoveryStatus.value = "正在搜索局域网灯具…"
                            try {
                                discoverLamps(this@MainActivity).collect { ad ->
                                    try {
                                        // Advertisement names are hints. A response establishes
                                        // the full MAC.
                                        val state = app.api.read(ad.endpoint)
                                        state.hardware?.let { hardware ->
                                            app.discovered(ad.endpoint, state)
                                            app.discoveryStatus.value =
                                                "已发现 ${app.nearby.value.size} 盏灯"
                                        }
                                    } catch (cancel: CancellationException) {
                                        throw cancel
                                    } catch (_: Exception) {
                                        /* Other HTTP services are not lamps. */
                                    }
                                }
                            } catch (cancel: CancellationException) {
                                throw cancel
                            } catch (failure: Exception) {
                                android.util.Log.w("OpenRhodes", "Discovery retry", failure)
                                app.discoveryStatus.value = "发现暂不可用，正在重试；也可按 IP 添加"
                            }
                            delay(2000)
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedId.value = intent.getStringExtra("deviceId")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("deviceId", requestedId.value)
        super.onSaveInstanceState(outState)
    }

    fun openSetup(host: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://$host/setup")))
    }
}
