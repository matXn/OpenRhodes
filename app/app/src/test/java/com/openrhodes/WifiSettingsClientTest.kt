package com.openrhodes

import com.openrhodes.data.LocalApi
import com.openrhodes.data.WifiSettingsClient
import com.openrhodes.domain.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

@Suppress("DEPRECATION")
class WifiSettingsClientTest {
    private val hardware = requireNotNull(HardwareId.parse("02:00:00:00:00:44"))
    private val endpoint = requireNotNull(Endpoint.parse("192.0.2.10"))
    private val state = """{"on":true,"brightness":56,"mac":"02:00:00:00:00:44","connected":true,"ssid":"Home"}"""

    private fun client(server: MockWebServer, candidates: List<Endpoint> = listOf(endpoint)): WifiSettingsClient {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
        }.build()
        return WifiSettingsClient(LocalApi({ 0L }, http), hardware) { candidates }
    }

    @Test fun bluetoothPreferenceIsSkippedAndIdentityVerifiedBeforeHoldingNetworkSettings() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(state))
            server.enqueue(MockResponse().setBody(state))
            val options = listOf(Endpoint.bluetooth(hardware), endpoint)
            client(server, options).read()
            assertEquals("/api/state", server.takeRequest(2, TimeUnit.SECONDS)!!.path)
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("/api/wifi/setup", request.path)
            assertEquals("action=hold", request.body.readUtf8())
            assertEquals("1", request.getHeader("X-Rhodes-Control"))
            assertTrue(options.first().isBluetooth)
        }
    }

    @Test fun wrongMacNeverReceivesNetworkWrites() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(state.replace("00:44", "00:46")))
            val failure = runCatching { client(server).connect("Private", "secret") }.exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("身份不匹配"))
            assertEquals("/api/state", server.takeRequest(2, TimeUnit.SECONDS)!!.path)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun networkSelectionAndPrioritiesUseExistingHttpProtocol() = runBlocking {
        MockWebServer().use { server ->
            repeat(8) { server.enqueue(MockResponse().setBody(state)) }
            val client = client(server)
            client.connect("Home")
            client.connect("Guest", "pass+word&")
            client.move("Guest", 1)
            client.forget("Guest")
            val forms = listOf(
                "ssid=Home&use_saved=1",
                "ssid=Guest&password=pass%2Bword%26",
                "ssid=Guest&action=move&priority=1",
                "ssid=Guest&action=forget",
            )
            for ((index, form) in forms.withIndex()) {
                assertEquals("/api/state", server.takeRequest(2, TimeUnit.SECONDS)!!.path)
                val request = server.takeRequest(2, TimeUnit.SECONDS)!!
                assertEquals(if (index < 2) "/api/wifi" else "/api/wifi/profiles", request.path)
                assertEquals(form, request.body.readUtf8())
            }
        }
    }

    @Test fun scanCompletesBeforeShowingNetworksAndReleaseUsesHeldEndpoint() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(state))
            server.enqueue(MockResponse().setBody("""{"scanning":true}"""))
            server.enqueue(MockResponse().setBody("""{"scanning":false,"networks":[{"ssid":"Home"},{"ssid":""},{"ssid":"Home"},{"ssid":"Guest"}]}"""))
            server.enqueue(MockResponse().setBody(state))
            val client = client(server)
            val networks = client.scan()
            assertEquals(2, networks.size)
            client.release()
            assertEquals("/api/state", server.takeRequest(2, TimeUnit.SECONDS)!!.path)
            val start = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("POST", start.method)
            assertEquals("/api/scan", start.path)
            val poll = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("GET", poll.method)
            assertEquals("/api/scan", poll.path)
            assertEquals("action=release", server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8())
        }
    }
}
