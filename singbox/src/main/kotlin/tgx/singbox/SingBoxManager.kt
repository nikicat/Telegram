package tgx.singbox

import android.util.Log
import bridge.Bridge
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

object SingBoxManager {
    private const val TAG = "SingBoxManager"
    private val lock = ReentrantLock()

    // TuicConfig identity (server:port:uuid) -> local SOCKS port
    private val portMap = ConcurrentHashMap<String, Int>()

    fun isRunning(): Boolean = Bridge.isRunning()

    fun getPort(config: TuicConfig): Int = portMap[config.identity()] ?: 0

    /**
     * Start sing-box with all given TUIC configs.
     * Each gets its own local SOCKS inbound, routed to its TUIC outbound.
     * Replaces any running instance.
     */
    fun startAll(configs: List<TuicConfig>) = lock.withLock {
        if (configs.isEmpty()) {
            stop()
            return@withLock
        }

        // Skip restart if already running with the same configs
        val newIdentities = configs.map { it.identity() }.toSet()
        if (Bridge.isRunning() && portMap.keys == newIdentities) {
            return@withLock
        }

        if (Bridge.isRunning()) {
            Bridge.stop()
        }
        portMap.clear()

        val entries = configs.map { config ->
            val port = findAvailablePort()
            portMap[config.identity()] = port
            ConfigEntry(config, port)
        }

        val json = buildConfigJson(entries)
        Bridge.start(json)
        Log.i(TAG, "Started ${entries.size} proxy(ies): ${entries.joinToString { "127.0.0.1:${it.port}" }}")
    }

    fun stop() = lock.withLock {
        if (Bridge.isRunning()) {
            Bridge.stop()
            Log.i(TAG, "Stopped")
        }
        portMap.clear()
    }

    private data class ConfigEntry(val config: TuicConfig, val port: Int)

    private fun buildConfigJson(entries: List<ConfigEntry>): String {
        val inbounds = entries.mapIndexed { i, e ->
            """{"type":"socks","tag":"socks-$i","listen":"127.0.0.1","listen_port":${e.port}}"""
        }
        val outbounds = entries.mapIndexed { i, e ->
            val c = e.config
            """{"type":"tuic","tag":"tuic-$i","server":${c.server.jsonEscape()},"server_port":${c.port},"uuid":${c.uuid.jsonEscape()},"password":${c.password.jsonEscape()},"congestion_control":${c.congestionControl.jsonEscape()},"tls":{"enabled":true,"insecure":${c.tlsInsecure}}}"""
        }
        val rules = entries.mapIndexed { i, _ ->
            """{"inbound":["socks-$i"],"outbound":"tuic-$i"}"""
        }
        return """{"log":{"level":"warn"},"inbounds":[${inbounds.joinToString(",")}],"outbounds":[${outbounds.joinToString(",")}],"route":{"rules":[${rules.joinToString(",")}]}}"""
    }

    private fun findAvailablePort(): Int {
        ServerSocket(0).use { return it.localPort }
    }

    private fun String.jsonEscape(): String {
        val escaped = this.replace("\\", "\\\\").replace("\"", "\\\"")
        return "\"$escaped\""
    }
}
