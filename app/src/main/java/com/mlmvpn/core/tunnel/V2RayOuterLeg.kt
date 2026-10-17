package com.mlmvpn.core.tunnel

import android.content.Context
import android.util.Log
import com.mlmvpn.core.warp.XrayCore
import com.mlmvpn.scanner.data.NodeManager
import com.mlmvpn.scanner.models.VpnNode
import com.mlmvpn.scanner.utils.VpnConfig
import com.mlmvpn.scanner.utils.XrayJsonGenerator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong

/**
 * V2Ray outer leg for Tor-over-V2Ray: an in-process Xray controller on fd=0
 * that exposes a SOCKS5 listener on chainSocksPort for Tor to dial through.
 *
 * One controller at a time; start/stop serialized by a lock and a generation
 * counter so a late start cannot resurrect a cancelled leg.
 */
object V2RayOuterLeg {

    private const val TAG = "V2RayOuterLeg"
    private const val OUTER_STOP_GRACE_MS = 6000L
    private const val PROBE_CONNECT_MS = 4000
    private const val PROBE_READ_MS = 5000
    private val PROBE_HOSTS = listOf("connectivitycheck.gstatic.com", "cp.cloudflare.com", "detectportal.firefox.com")

    sealed class Status {
        object Idle : Status()
        data class Starting(val node: VpnNode) : Status()
        data class Ready(val node: VpnNode) : Status()
        data class Failed(val reason: String) : Status()
    }

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status

    private val lock = Any()
    private val generation = AtomicLong(0)
    @Volatile private var controller: libv2ray.CoreController? = null
    @Volatile private var activeNode: VpnNode? = null
    @Volatile private var currentPort: Int = 0

    fun maskedHostPort(host: String, port: Int): String {
        val masked = if (host.length <= 4) "***" else host.take(2) + "***" + host.takeLast(2)
        return "$masked:$port"
    }

    fun isEligible(node: VpnNode, ownPorts: Set<Int>): Boolean {
        val cfg = VpnConfig.parseUri(node.uri) ?: return false
        if (cfg.address.isBlank() || cfg.port <= 0) return false
        if (isLoopbackOrUnspecified(cfg.address)) return false
        if (cfg.port in ownPorts) return false
        return true
    }

    private fun isLoopbackOrUnspecified(host: String): Boolean {
        val lower = host.lowercase()
        if (lower == "localhost" || lower == "127.0.0.1" || lower == "::1") return true
        return try {
            val addr = InetAddress.getByName(host)
            addr.isLoopbackAddress || addr.isAnyLocalAddress || addr.isLinkLocalAddress
        } catch (_: Exception) { false }
    }

    /**
     * Resolve ordered candidates: pinned → winner → best-tested → insertion order.
     * Returns null when no eligible node exists; [pinnedMissing] true when a pinned
     * id was requested but no longer exists/parseable.
     */
    data class Candidates(val ordered: List<VpnNode>, val pinnedMissing: Boolean, val pinnedId: String?)

    fun resolveCandidates(
        context: Context,
        eligible: List<VpnNode>,
        pinnedId: String?,
    ): Candidates {
        if (pinnedId != null && pinnedId != "auto") {
            val pinned = eligible.firstOrNull { it.id == pinnedId }
            if (pinned != null) return Candidates(listOf(pinned), false, pinnedId)
            // pinned id gone or ineligible — hard failure, no fallback
            val exists = NodeManager(context).nodes.any { it.id == pinnedId }
            if (!exists) return Candidates(emptyList(), true, pinnedId)
            // exists but not parseable → treat as pinned failure
            return Candidates(emptyList(), true, pinnedId)
        }
        // auto: winner first
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val winnerId = prefs.getString(CoreConfig.CHAIN_OUTER_V2RAY_WINNER_PREF, "")?.trim().orEmpty()
        val winner = winnerId.takeIf { it.isNotEmpty() }?.let { id -> eligible.firstOrNull { it.id == id } }
        if (winner != null && isWinnerValid(context, winner)) {
            val rest = eligible.filter { it.id != winner.id }
            val sorted = rest.sortedWith(compareBy(
                { delayValue(it) ?: Int.MAX_VALUE },
                { it.addedAt }
            ))
            return Candidates(listOf(winner) + sorted, false, null)
        }
        // no valid winner — order by delay then insertion
        val sorted = eligible.sortedWith(compareBy(
            { delayValue(it) ?: Int.MAX_VALUE },
            { it.addedAt }
        ))
        return Candidates(sorted, false, null)
    }

    private fun delayValue(node: VpnNode): Int? {
        val d = node.delay.trim()
        if (d.equals("Test", true) || d.isEmpty()) return null
        return d.filter { it.isDigit() }.toIntOrNull()?.takeIf { it > 0 }
    }

    private fun isWinnerValid(context: Context, node: VpnNode): Boolean {
        if (VpnConfig.parseUri(node.uri) == null) return false
        return true
    }

    fun readPinnedNodeId(context: Context): String {
        return context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getString(CoreConfig.CHAIN_OUTER_V2RAY_NODE_PREF, "auto")?.trim().orEmpty().ifBlank { "auto" }
    }

    fun ownPorts(context: Context): Set<Int> {
        val base = com.mlmvpn.scanner.utils.NetworkSettings.localPort(context)
        return setOf(base, base + 1, base + 2, base + 10000, 20810, SocksFront.FRONT_PORT)
    }

    fun start(context: Context, node: VpnNode, socksPort: Int): Boolean {
        val gen = synchronized(lock) { generation.incrementAndGet() }
        val cfg = VpnConfig.parseUri(node.uri) ?: run {
            _status.value = Status.Failed("parse failed")
            return false
        }
        _status.value = Status.Starting(node)
        activeNode = node
        currentPort = socksPort
        val json = try {
            XrayJsonGenerator.generateChainOuterConfig(cfg, socksPort)
        } catch (e: Exception) {
            _status.value = Status.Failed(e.message ?: "config failed")
            return false
        }
        val c: libv2ray.CoreController
        try {
            c = XrayCore.newController(context)
        } catch (e: Exception) {
            _status.value = Status.Failed(e.message ?: "init failed")
            return false
        }
        synchronized(lock) {
            if (generation.get() != gen) {
                try { c.stopLoop() } catch (_: Exception) {}
                return false
            }
            controller = c
        }
        return try {
            val t = Thread({
                try { c.startLoop(json, 0) } catch (t: Throwable) {
                    Log.e(TAG, "startLoop threw", t)
                }
            }, "chain-outer-v2ray")
            t.isDaemon = true
            t.start()
            // wait briefly for listener to appear
            val deadline = android.os.SystemClock.elapsedRealtime() + 4000
            var ok = false
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                if (probePort(socksPort)) { ok = true; break }
                Thread.sleep(200)
            }
            if (!ok) {
                stopInternal(gen)
                _status.value = Status.Failed("SOCKS did not open")
                return false
            }
            // data-path probe — real bytes through the leg
            if (!dataPathProbe(socksPort)) {
                stopInternal(gen)
                _status.value = Status.Failed("data path failed")
                return false
            }
            if (generation.get() != gen) {
                stopInternal(gen)
                return false
            }
            _status.value = Status.Ready(node)
            // remember winner
            context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
                .putString(CoreConfig.CHAIN_OUTER_V2RAY_WINNER_PREF, node.id).apply()
            context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
                .putInt(CoreConfig.CHAIN_OUTER_PREF, CoreConfig.CHAIN_OUTER_LADDER.indexOf("v2ray")).apply()
            ConnectionLog.record("Chain: V2Ray (${node.name}, ${maskedHostPort(cfg.address, cfg.port)}) is carrying the outer leg")
            true
        } catch (e: Exception) {
            stopInternal(gen)
            _status.value = Status.Failed(e.message ?: "start failed")
            false
        }
    }

    fun stop() {
        val gen = synchronized(lock) { generation.incrementAndGet() }
        stopInternal(gen)
        _status.value = Status.Idle
    }

    private fun stopInternal(expectedGen: Long) {
        val c = synchronized(lock) {
            val cur = controller
            controller = null
            cur
        }
        try { c?.stopLoop() } catch (_: Exception) {}
        // wait for port release
        val deadline = android.os.SystemClock.elapsedRealtime() + OUTER_STOP_GRACE_MS
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (!probePort(currentPort)) break
            try { Thread.sleep(200) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
        }
        activeNode = null
    }

    fun isReady(port: Int): Boolean = probePort(port) && dataPathProbe(port)

    private fun probePort(port: Int): Boolean = try {
        Socket().use { s -> s.connect(InetSocketAddress("127.0.0.1", port), 800); true }
    } catch (_: Exception) { false }

    private fun dataPathProbe(socksPort: Int): Boolean {
        for (host in PROBE_HOSTS) {
            if (socksHttpProbe(socksPort, host)) return true
        }
        return false
    }

    private fun socksHttpProbe(socksPort: Int, host: String): Boolean = try {
        val sock = Socket()
        sock.soTimeout = PROBE_READ_MS
        sock.connect(InetSocketAddress("127.0.0.1", socksPort), PROBE_CONNECT_MS)
        val out = sock.getOutputStream()
        val inp = sock.getInputStream()
        out.write(byteArrayOf(0x05, 0x01, 0x00)); out.flush()
        val r1 = ByteArray(2); inp.read(r1)
        if (r1[0] != 0x05.toByte() || r1[1] != 0x00.toByte()) { sock.close(); return false }
        val hb = host.toByteArray(Charsets.US_ASCII)
        val req = ByteArray(4 + 1 + hb.size + 2)
        req[0]=0x05; req[1]=0x01; req[2]=0x00; req[3]=0x03
        req[4]=hb.size.toByte(); System.arraycopy(hb,0,req,5,hb.size)
        req[5+hb.size]=0x00; req[6+hb.size]=0x50
        out.write(req); out.flush()
        val r2 = ByteArray(10); inp.read(r2)
        if (r2[1] != 0x00.toByte()) { sock.close(); return false }
        val http = "GET / HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n"
        out.write(http.toByteArray(Charsets.US_ASCII)); out.flush()
        val buf = ByteArray(512)
        val n = inp.read(buf)
        sock.close()
        n != null && n > 0 && String(buf, 0, n.coerceAtLeast(0), Charsets.US_ASCII).contains("HTTP/")
    } catch (_: Exception) { false }
}
