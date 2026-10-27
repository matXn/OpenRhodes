package com.openrhodes.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import com.openrhodes.domain.*
import java.net.Inet4Address
import kotlin.coroutines.resume
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** Restart discovery when a local link changes, including Wi-Fi without Internet. */
fun localNetworkChanges(context: Context) =
    callbackFlow<Set<String>> {
            val manager = context.getSystemService(ConnectivityManager::class.java)
            fun publish(lost: Network? = null) {
                val snapshot =
                    manager.allNetworks
                        .filter { network ->
                            val caps = manager.getNetworkCapabilities(network)
                            network != lost &&
                                caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == false &&
                                (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
                        }
                        .map { network ->
                            val properties = manager.getLinkProperties(network)
                            val addresses =
                                properties
                                    ?.linkAddresses
                                    .orEmpty()
                                    .filter { it.address is Inet4Address }
                                    .map { it.toString() }
                                    .sorted()
                            "${network.networkHandle}:${properties?.interfaceName}:${addresses.joinToString(",")}"
                        }
                        .toSet()
                trySend(snapshot)
            }
            val callback =
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        publish()
                    }

                    override fun onLost(network: Network) {
                        publish(network)
                    }

                    override fun onLinkPropertiesChanged(
                        network: Network,
                        properties: LinkProperties,
                    ) {
                        publish()
                    }
                }
            val request =
                NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                    .build()
            manager.registerNetworkCallback(request, callback)
            publish()
            awaitClose { manager.unregisterNetworkCallback(callback) }
        }
        .distinctUntilChanged()

data class Advertisement(val name: String, val endpoint: Endpoint)

@Suppress("DEPRECATION")
fun discoverLamps(context: Context) = callbackFlow {
    val manager = context.getSystemService(NsdManager::class.java)
    val requests = Channel<NsdServiceInfo>(Channel.BUFFERED)
    val worker = launch {
        for (service in requests) {
            val resolved =
                withTimeoutOrNull(4000) {
                    suspendCancellableCoroutine<NsdServiceInfo?> { wait ->
                        val listener =
                            object : NsdManager.ResolveListener {
                                override fun onResolveFailed(info: NsdServiceInfo, error: Int) {
                                    if (wait.isActive) wait.resume(null)
                                }

                                override fun onServiceResolved(info: NsdServiceInfo) {
                                    if (wait.isActive) wait.resume(info)
                                }
                            }
                        wait.invokeOnCancellation {
                            if (Build.VERSION.SDK_INT >= 34)
                                runCatching { manager.stopServiceResolution(listener) }
                        }
                        try {
                            manager.resolveService(service, listener)
                        } catch (_: RuntimeException) {
                            if (wait.isActive) wait.resume(null)
                        }
                    }
                } ?: continue
            if (resolved.port != 80) continue
            val host =
                if (Build.VERSION.SDK_INT >= 34) {
                    resolved.hostAddresses.firstOrNull { it is Inet4Address }
                } else resolved.host
            val endpoint = Endpoint.parse(host?.hostAddress ?: continue) ?: continue
            trySend(Advertisement(resolved.serviceName, endpoint))
        }
    }
    val listener =
        object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = Unit

            override fun onDiscoveryStopped(type: String) {
                close()
            }

            override fun onServiceLost(info: NsdServiceInfo) = Unit

            override fun onStartDiscoveryFailed(type: String, error: Int) {
                close(IllegalStateException("mDNS 启动失败 ($error)，可按 IP 添加"))
            }

            override fun onStopDiscoveryFailed(type: String, error: Int) {
                close(IllegalStateException("mDNS 停止失败 ($error)"))
            }

            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceName.contains("rhodes", true)) requests.trySend(info)
            }
        }
    try {
        manager.discoverServices("_http._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
    } catch (failure: RuntimeException) {
        close(failure)
    }
    awaitClose {
        runCatching { manager.stopServiceDiscovery(listener) }
        requests.close()
        worker.cancel()
    }
}

/** Prefer addresses reachable through the current link before historical networks. */
fun orderForNetwork(context: Context, lamp: Lamp): Lamp {
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    val links =
        connectivity.allNetworks
            .filter { network ->
                val caps = connectivity.getNetworkCapabilities(network)
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == false &&
                    (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
            }
            .flatMap { connectivity.getLinkProperties(it)?.linkAddresses.orEmpty() }
            .filter { it.address is Inet4Address }
    fun bytes(host: String) =
        host.split('.').mapNotNull(String::toIntOrNull).takeIf { it.size == 4 }
    fun reachable(endpoint: Endpoint): Boolean {
        val address = bytes(endpoint.host) ?: return false
        return links.any { link ->
            val subnet = link.address.address.map { it.toInt() and 255 }
            (0 until link.prefixLength).all { bit ->
                val mask = 1 shl (7 - bit % 8)
                address[bit / 8] and mask == subnet[bit / 8] and mask
            }
        }
    }
    val local = lamp.endpoints.filter(::reachable)
    // Keep full history in the catalog. Probe only this link's addresses when available.
    return lamp.copy(endpoints = local.ifEmpty { lamp.endpoints.take(2) })
}

fun localAddressSummary(context: Context): String {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    val addresses =
        manager.allNetworks
            .filter { network ->
                val caps = manager.getNetworkCapabilities(network)
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == false &&
                    (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
            }
            .flatMap { manager.getLinkProperties(it)?.linkAddresses.orEmpty() }
            .filter { it.address is Inet4Address }
            .mapNotNull { it.address.hostAddress }
            .distinct()
    return if (addresses.isEmpty()) "手机尚未连接局域网 Wi-Fi"
    else "手机局域网 IP：${addresses.joinToString(" / ")}"
}
