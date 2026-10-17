package com.mlmvpn.scanner.quick

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Servers the user has thrown away.
 *
 * A dead server is not just noise on screen -- it costs a slot in every future sweep, and the
 * feeds republish it every fifteen minutes. So "delete" has to mean deleted: an id in here is
 * dropped while the feed is being parsed, before it can reach the list, the tester, or the
 * cache. Refreshing the list does not bring it back.
 *
 * Identity is the same endpoint key the rest of the module de-duplicates on, so a server the
 * user removed stays removed even when a feed republishes it under a different name.
 *
 * Nothing expires here on a timer, because the user's decision is not a measurement that goes
 * stale. [clear] is the way back, and the UI offers it.
 */
object QuickBlocklist {

    private const val FILE_NAME = "quick-blocklist.json"

    // A phone-side cap so a user who bulk-deletes the dead half of a 10,000-entry pool a few
    // times over does not grow this file without end. Oldest-first, same as the verified store.
    private const val MAX = 20000

    private var cache: LinkedHashSet<String>? = null

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    @Synchronized
    fun ids(context: Context): Set<String> = mutableIds(context)

    @Synchronized
    private fun mutableIds(context: Context): LinkedHashSet<String> {
        cache?.let { return it }
        val set = LinkedHashSet<String>()
        try {
            val f = file(context)
            if (f.exists()) {
                val arr = JSONObject(f.readText()).optJSONArray("ids") ?: JSONArray()
                for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let { set.add(it) }
            }
        } catch (e: Exception) {
            set.clear()
        }
        cache = set
        return set
    }

    @Synchronized
    private fun save(context: Context) {
        try {
            val arr = JSONArray()
            for (id in cache ?: return) arr.put(id)
            file(context).writeText(JSONObject().put("ids", arr).toString())
        } catch (e: Exception) {
            // A blocklist that cannot be written must not fail the fetch.
        }
    }

    @Synchronized
    fun add(context: Context, ids: Collection<String>) {
        if (ids.isEmpty()) return
        val set = mutableIds(context)
        set.addAll(ids)
        while (set.size > MAX) {
            val oldest = set.iterator()
            if (!oldest.hasNext()) break
            oldest.next()
            oldest.remove()
        }
        save(context)
    }

    @Synchronized
    fun contains(context: Context, id: String): Boolean = ids(context).contains(id)

    @Synchronized
    fun count(context: Context): Int = ids(context).size

    @Synchronized
    fun clear(context: Context) {
        cache = LinkedHashSet()
        save(context)
    }
}
