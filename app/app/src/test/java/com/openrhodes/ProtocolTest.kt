package com.openrhodes

import com.openrhodes.data.LocalApi
import com.openrhodes.data.SetupAccessPoint
import com.openrhodes.domain.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

@Suppress("DEPRECATION")
class ProtocolTest {
    private val body = """{"on":true,"brightness":20,"mac":"E8:3D:C1:EB:CE:5C"}"""

    private fun client(server: MockWebServer) =
        LocalApi(
            { 123 },
            OkHttpClient.Builder()
                .followRedirects(false)
                .retryOnConnectionFailure(false)
                .addInterceptor { chain ->
                    chain.proceed(
                        chain
                            .request()
                            .newBuilder()
                            .url(server.url(chain.request().url.encodedPath))
                            .build()
                    )
                }
                .build(),
        )

    @Test
    fun lightRequestUsesFirmwareFormAndControlHeader() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(body))
            val api = client(server)
            val response =
                api.write(Endpoint.parse("192.168.4.1")!!, Command.Level(20), LampState(false, 5))
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("POST", request.method)
            assertEquals("/api/light", request.path)
            assertEquals("1", request.getHeader("X-Rhodes-Control"))
            assertEquals("brightness=20", request.body.readUtf8())
            assertEquals(20, response.brightness)
        }
    }

    @Test
    fun wifiPasswordAndSsidAreFormEncoded() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(body))
            client(server)
                .json(
                    Endpoint.parse("192.168.4.1")!!,
                    "/api/wifi",
                    mapOf("ssid" to "Bed & Lamp", "password" to "a+b&12345"),
                )
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            val decoded =
                request.body.readUtf8().split('&').associate { part ->
                    val (key, value) = part.split('=', limit = 2)
                    java.net.URLDecoder.decode(key, "UTF-8") to
                        java.net.URLDecoder.decode(value, "UTF-8")
                }
            assertEquals(mapOf("ssid" to "Bed & Lamp", "password" to "a+b&12345"), decoded)
        }
    }

    @Test
    fun firmwareErrorIsReportedWithoutTreatingItAsLightState() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(409).setBody("""{"error":"touch busy"}""")
            )
            val failure = runCatching {
                client(server).read(Endpoint.parse("192.168.4.1")!!)
            }
                .exceptionOrNull()
            assertEquals("touch busy", failure?.message)
        }
    }

    @Test
    fun accessPointCredentialsRequireExplicitControlRequestAndPreserveExactText() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setBody(
                        """{"ssid":"灯具 & Setup","password":" +s&cret ","hidden":true,"ip":"192.168.4.1"}"""
                    )
            )
            val ap = client(server).setupAccessPoint(Endpoint.parse("192.168.4.1")!!)
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("GET", request.method)
            assertEquals("/api/wifi/ap", request.path)
            assertEquals("1", request.getHeader("X-Rhodes-Control"))
            assertEquals("no-store", request.getHeader("Cache-Control"))
            assertEquals("灯具 & Setup", ap.ssid)
            assertEquals(" +s&cret ", ap.password)
            assertTrue(ap.hidden)
            assertFalse(ap.toString().contains(ap.password))
        }
    }

    @Test
    fun accessPointToggleUsesExplicitBooleanFormAndConfirmedResponse() = runBlocking {
        MockWebServer().use { server ->
            for (hidden in listOf(true, false)) {
                server.enqueue(
                    MockResponse()
                        .setBody(
                            """{"ssid":"RhodesLight-Setup","password":"RhodesLight","hidden":$hidden,"ip":"192.168.4.1"}"""
                        )
                )
                val ap = client(server).setupAccessPoint(Endpoint.parse("192.168.4.1")!!, hidden)
                val request = server.takeRequest(2, TimeUnit.SECONDS)!!
                assertEquals("POST", request.method)
                assertEquals("/api/wifi/ap", request.path)
                assertEquals("1", request.getHeader("X-Rhodes-Control"))
                assertEquals("hidden=${if (hidden) 1 else 0}", request.body.readUtf8())
                assertEquals(hidden, ap.hidden)
            }
        }
    }

    @Test
    fun missingHotspotFieldsCannotProduceInventedCredentialsOrVisibility() {
        assertTrue(
            runCatching {
                SetupAccessPoint.decode(
                    Json.parseToJsonElement(
                            """{"ssid":"RhodesLight-Setup","hidden":true,"ip":"192.168.4.1"}"""
                        )
                        .jsonObject
                )
            }
                .isFailure
        )
        assertTrue(
            runCatching {
                SetupAccessPoint.decode(
                    Json.parseToJsonElement(
                            """{"ssid":"RhodesLight-Setup","password":"RhodesLight","ip":"192.168.4.1"}"""
                        )
                        .jsonObject
                )
            }
                .isFailure
        )
    }

    @Test
    fun captivePortalHtmlIsRejected() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("<html>Sign in</html>"))
            assertTrue(
                runCatching { client(server).read(Endpoint.parse("192.168.4.1")!!) }.isFailure
            )
        }
    }
}
