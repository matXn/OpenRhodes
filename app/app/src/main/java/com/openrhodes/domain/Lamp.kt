package com.openrhodes.domain

import java.net.URI
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.round

@JvmInline
value class HardwareId private constructor(val value: String) {
    val displayTag: String
        get() = "#" + value.removePrefix("mac:").chunked(2).joinToString(":").uppercase(Locale.ROOT)

    companion object {
        fun parse(input: String?): HardwareId? {
            val digits =
                input
                    ?.trim()
                    ?.lowercase()
                    ?.removePrefix("mac:")
                    ?.removePrefix("rhodes-")
                    ?.replace(":", "")
                    ?.replace("-", "") ?: return null
            if (
                !digits.matches(Regex("[0-9a-f]{12}")) ||
                    digits.all { it == '0' } ||
                    digits.all { it == 'f' }
            )
                return null
            // Wi-Fi station identities must be unicast.
            if (digits.substring(0, 2).toInt(16) and 1 != 0) return null
            return HardwareId("mac:$digits")
        }
    }
}

@JvmInline
value class Endpoint private constructor(val host: String) {
    val url: String
        get() = "http://$host"

    companion object {
        fun parse(text: String): Endpoint? = runCatching {
            val raw = text.trim()
            val uri = URI(if (raw.contains("://")) raw else "http://$raw")
            require(
                uri.scheme == "http" &&
                    uri.userInfo == null &&
                    uri.query == null &&
                    uri.fragment == null
            )
            require(uri.path.isNullOrEmpty() || uri.path == "/")
            require(uri.port == -1 || uri.port == 80)
            val host = requireNotNull(uri.host).lowercase()
            require(
                host == "localhost" ||
                    host.endsWith(".local") ||
                    host.matches(Regex("[0-9]+(\\.[0-9]+){3}"))
            )
            if (host.first().isDigit()) require(host.split('.').all { it.toIntOrNull() in 0..255 })
            Endpoint(host)
        }
            .getOrNull()
    }
}

data class Lamp(
    val id: String,
    val name: String,
    val hardware: HardwareId?,
    val endpoints: List<Endpoint>,
    val aliases: Set<String> = emptySet(),
)

data class Breath(val periodMs: Int = 6800, val floor: Int = 13, val curve: Int = 60) {
    fun normalized() =
        Breath(periodMs.coerceIn(2000, 20000), floor.coerceIn(0, 80), curve.coerceIn(50, 300))

    fun wave(phase: Double): Float =
        (floor / 100.0 +
                (1 - floor / 100.0) *
                    (0.5 + 0.5 * cos(phase)).coerceIn(0.0, 1.0).pow(curve / 100.0))
            .toFloat()
}

data class LampState(
    val on: Boolean,
    val brightness: Int,
    val breathing: Boolean = false,
    val breath: Breath = Breath(),
    val phase: Double = 0.0,
    val sampledAt: Long = 0,
    val hardware: HardwareId? = null,
    val wifiName: String? = null,
    val deviceIp: String? = null,
) {
    fun phaseAt(now: Long) = phase + (now - sampledAt).coerceAtLeast(0) * 2 * PI / breath.periodMs

    /** Firmware reports modulo 2π; keep the graph cursor travelling across all cycles. */
    fun continuing(previous: LampState?): LampState {
        if (
            previous == null ||
                !breathing ||
                !previous.breathing ||
                breath.periodMs != previous.breath.periodMs
        )
            return this
        val turns = round((previous.phaseAt(sampledAt) - phase) / (2 * PI))
        return copy(phase = phase + turns * 2 * PI)
    }
}

sealed interface Command {
    data class Level(val percent: Int) : Command

    data class Power(val on: Boolean) : Command

    data class Mode(val breathing: Boolean) : Command

    data class Parameters(val breath: Breath) : Command

    data object Cycle : Command
}

fun nextBrightness(state: LampState): Int =
    if (!state.on) 5 else listOf(5, 20, 50, 100).firstOrNull { it > state.brightness } ?: 0

/** Identity is established only by a verified response or explicit legacy confirmation. */
object CatalogRules {
    fun resolve(lamps: List<Lamp>, id: String) = lamps.firstOrNull {
        it.id == id || id in it.aliases
    }

    fun enroll(lamps: List<Lamp>, candidate: Lamp): List<Lamp> {
        require(candidate.endpoints.isNotEmpty())
        require(
            lamps.none {
                it.id != candidate.id &&
                    it.hardware != null &&
                    candidate.hardware != null &&
                    it.id in candidate.aliases &&
                    it.hardware != candidate.hardware
            }
        ) {
            "Identity conflict"
        }
        val matches = lamps.filter {
            it.id == candidate.id ||
                (candidate.hardware != null && it.hardware == candidate.hardware)
        }
        require(
            matches.none {
                it.hardware != null &&
                    candidate.hardware != null &&
                    it.hardware != candidate.hardware
            }
        )
        val canonical = matches.firstOrNull() ?: candidate
        val merged =
            canonical.copy(
                hardware = candidate.hardware ?: canonical.hardware,
                endpoints =
                    (candidate.endpoints + matches.flatMap { it.endpoints }).distinct().take(16),
                aliases =
                    (candidate.aliases + matches.flatMap { it.aliases + it.id } + candidate.id) -
                        canonical.id,
                name = if (canonical.id == candidate.id) candidate.name else canonical.name,
            )
        return if (matches.isEmpty()) lamps + merged
        else
            lamps.mapNotNull {
                when {
                    it.id == canonical.id -> merged
                    it in matches -> null
                    else -> it
                }
            }
    }
}
