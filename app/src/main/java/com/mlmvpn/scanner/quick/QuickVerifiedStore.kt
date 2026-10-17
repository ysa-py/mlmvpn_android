package com.mlmvpn.scanner.quick

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Measured countries beat claimed ones.
 *
 * A node's flag is whatever the feed decided to call it. Once the traffic has actually gone
 * through it, we know better: the trace reports the country the far end really sees. When the
 * two disagree -- the feed says Germany, the exit is Sweden -- the measurement wins, and it is
 * remembered.
 *
 * Why persist rather than just fix the label on screen: the reason to pick a country is to come
 * out in it. A node filed under the wrong flag is not a cosmetic problem, it is the list lying
 * about the one thing it is for. So a verified node moves into its real country for good -- it
 * shows up when that country is picked, and stops showing up under the one it was falsely
 * claiming.
 *
 * Only trustworthy readings are stored:
 *   - `countryTrusted` must hold -- WARP reports the USER's country by design, so a WARP path
 *     would file every node under Iran. See [EgressTracer].
 *   - the reading must belong to the node that was actually connected at the time.
 *
 * Entries expire: server addresses get reassigned, and a year-old verdict about an IP is not
 * evidence about the machine answering on it today.
 *
 * Port of the `recordVerified` half of the desktop app's `quick-connect.js`. A plain JSON file
 * rather than SharedPreferences: this holds up to [MAX] entries and is rewritten wholesale,
 * which is exactly the access pattern prefs are worst at.
 */
object QuickVerifiedStore {

    private const val FILE_NAME = "quick-verified.json"
    private const val TTL_MS = 30L * 24 * 60 * 60 * 1000
    private const val MAX = 4000

    private data class Record(val code: String, val at: Long)

    private var cache: MutableMap<String, Record>? = null

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    @Synchronized
    private fun load(context: Context): MutableMap<String, Record> {
        cache?.let { return it }
        val kept = LinkedHashMap<String, Record>()
        try {
            val f = file(context)
            if (f.exists()) {
                val root = JSONObject(f.readText())
                val now = System.currentTimeMillis()
                for (id in root.keys()) {
                    val rec = root.optJSONObject(id) ?: continue
                    val code = rec.optString("code", "")
                    val at = rec.optLong("at", 0L)
                    if (code.isBlank() || at <= 0L) continue
                    if (now - at > TTL_MS) continue
                    kept[id] = Record(code, at)
                }
            }
        } catch (e: Exception) {
            kept.clear()
        }
        cache = kept
        return kept
    }

    @Synchronized
    private fun save(context: Context) {
        try {
            val root = JSONObject()
            for ((id, rec) in cache ?: return) {
                root.put(id, JSONObject().put("code", rec.code).put("at", rec.at))
            }
            file(context).writeText(root.toString())
        } catch (e: Exception) {
            // A store that cannot be written must not fail the connection.
        }
    }

    /** What [record] did, so the caller can tell the user a node just moved country. */
    data class Outcome(val ok: Boolean, val code: String? = null, val previous: String? = null, val moved: Boolean = false)

    /**
     * Remember what a node's exit really was. Caller must have checked `countryTrusted` first.
     */
    @Synchronized
    fun record(context: Context, nodeId: String?, rawCode: String?): Outcome {
        val code = GeoLabel.normalizeCode(rawCode)
        if (nodeId.isNullOrBlank() || code == null) return Outcome(ok = false)

        val store = load(context)
        val previous = store[nodeId]?.code

        // Oldest-first eviction, so a long-running install does not grow this file without end.
        if (!store.containsKey(nodeId) && store.size >= MAX) {
            val doomed = store.entries.sortedBy { it.value.at }.take(maxOf(1, MAX / 10)).map { it.key }
            for (id in doomed) store.remove(id)
        }

        store[nodeId] = Record(code, System.currentTimeMillis())
        save(context)
        return Outcome(ok = true, code = code, previous = previous, moved = previous != null && previous != code)
    }

    /** The stored reading for one node, or null. */
    @Synchronized
    fun codeFor(context: Context, nodeId: String): String? = load(context)[nodeId]?.code

    @Synchronized
    fun count(context: Context): Int = load(context).size

    @Synchronized
    fun forget(context: Context) {
        cache = LinkedHashMap()
        save(context)
    }

    /** Fold every stored reading into a freshly built list, in place. */
    @Synchronized
    fun applyTo(context: Context, nodes: List<QuickNode>) {
        val store = load(context)
        if (store.isEmpty()) return
        for (node in nodes) {
            val rec = store[node.id] ?: continue
            node.applyVerified(rec.code)
        }
    }
}
