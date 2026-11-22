package com.aktivesolutions.dalan.data

import android.content.Context
import android.content.SharedPreferences
import com.aktivesolutions.dalan.R
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem
import org.json.JSONArray
import org.json.JSONObject

/**
 * The user's content filters (spoiler / keyword / channel blocking), kept as JSON in the default shared
 * preferences. Call [init] once from the Application. Writes happen on the main thread; reads are safe anywhere.
 */
object Filters {
    const val KEY_RULES = "content_filters"
    const val KEY_ENABLED = "filters_enabled"
    const val KEY_CORRUPT = "content_filters_corrupt"
    const val BLOCKED_ID = "blocked_channels"

    private lateinit var appContext: Context
    private lateinit var prefs: SharedPreferences
    private var ready = false

    @Volatile var rules: List<FilterRule> = emptyList()
        private set
    @Volatile private var matcher: FilterMatcher = FilterMatcher(emptyList(), 0)
    @Volatile private var matcherDirty = true

    /** Bumped on every change (rules or the master switch): screens compare it in onResume to know when to re-filter. */
    @Volatile var version: Int = 0
        private set

    // SharedPreferences holds listeners weakly, so keep a strong reference.
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == KEY_ENABLED) version++
    }

    @Synchronized
    fun init(context: Context) {
        if (ready) return
        appContext = context.applicationContext
        prefs = Settings.get(appContext).prefs
        val raw = prefs.getString(KEY_RULES, null)
        val parsed = decode(raw)
        // Unreadable data: keep a copy, since the next save would overwrite it.
        if (parsed == null) prefs.edit().putString(KEY_CORRUPT, raw).apply()
        rules = parsed ?: emptyList()
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        ready = true
    }

    val enabled: Boolean get() = ready && prefs.getBoolean(KEY_ENABLED, true)

    /** Enabled, unexpired rules (what actually hides videos while the master switch is on). */
    fun activeCount(): Int {
        val now = System.currentTimeMillis()
        return rules.count { it.isActive(now) }
    }

    fun isExpired(r: FilterRule) = r.expiresAt != null && r.expiresAt <= System.currentTimeMillis()

    @Synchronized
    fun add(rule: FilterRule) = commit(rules + rule)

    @Synchronized
    fun update(rule: FilterRule) = commit(rules.map { if (it.id == rule.id) rule else it })

    @Synchronized
    fun delete(id: String) = commit(rules.filter { it.id != id })

    @Synchronized
    fun setEnabled(id: String, on: Boolean) = commit(rules.map { if (it.id == id) it.copy(enabled = on) else it })

    /** Adds the channel to the single auto-managed "Blocked channels" rule (created when missing). Returns that rule's name. */
    @Synchronized
    fun blockChannel(channelId: String, channelName: String?): String {
        val name = appContext.getString(R.string.filter_blocked_channels)
        val label = channelName?.takeIf { it.isNotBlank() } ?: channelId
        val existing = rules.firstOrNull { it.id == BLOCKED_ID }
        if (existing == null) {
            commit(rules + FilterRule(BLOCKED_ID, name, emptyList(), Scope.BOTH, mapOf(channelId to label)))
        } else {
            commit(rules.map { if (it.id == BLOCKED_ID) it.copy(channelIds = it.channelIds + (channelId to label), enabled = true, expiresAt = null) else it })
        }
        return name
    }

    private fun commit(newRules: List<FilterRule>) {
        rules = newRules
        matcherDirty = true
        prefs.edit().putString(KEY_RULES, encode(newRules)).apply()
        version++
    }

    private fun currentMatcher(): FilterMatcher {
        val now = System.currentTimeMillis()
        var m = matcher
        if (matcherDirty || now >= m.nextExpiryMs) {
            synchronized(this) {
                if (!matcherDirty && now >= matcher.nextExpiryMs) version++ // a rule expired: screens should re-filter
                matcherDirty = false
                m = FilterMatcher(rules, now)
                matcher = m
            }
        }
        return m
    }

    /** The rule hiding [item], or null. Always null while the master switch is off. */
    fun hiddenBy(item: MediaItem, titleOnly: Boolean = false): FilterRule? {
        if (!enabled) return null
        val m = currentMatcher()
        if (m.isEmpty()) return null
        return m.hiddenBy(item.title, item.author, item.channelId, titleOnly)
    }

    fun isHidden(item: MediaItem, titleOnly: Boolean = false): Boolean = hiddenBy(item, titleOnly) != null

    /** [items] without hidden ones; the very same list instance when nothing is hidden. */
    fun filter(items: List<MediaItem>, titleOnly: Boolean = false): List<MediaItem> {
        if (items.isEmpty() || !enabled) return items
        val m = currentMatcher()
        if (m.isEmpty()) return items
        var out: ArrayList<MediaItem>? = null
        for (i in items.indices) {
            val item = items[i]
            if (m.isHidden(item.title, item.author, item.channelId, titleOnly)) {
                if (out == null) out = ArrayList<MediaItem>(items.size).also { l -> for (j in 0 until i) l.add(items[j]) }
            } else out?.add(item)
        }
        return out ?: items
    }

    // ---- JSON ----

    fun encode(list: List<FilterRule>): String {
        val arr = JSONArray()
        for (r in list) {
            val o = JSONObject()
            o.put("id", r.id)
            o.put("name", r.name)
            o.put("terms", JSONArray(r.terms))
            o.put("scope", r.scope.name)
            val ch = JSONObject()
            for ((k, v) in r.channelIds) ch.put(k, v)
            o.put("channels", ch)
            o.put("enabled", r.enabled)
            if (r.expiresAt != null) o.put("expiresAt", r.expiresAt)
            arr.put(o)
        }
        return arr.toString()
    }

    /** The rules, an empty list for blank input, or null when [json] cannot be parsed. */
    fun decode(json: String?): List<FilterRule>? {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            val out = ArrayList<FilterRule>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val terms = ArrayList<String>()
                o.optJSONArray("terms")?.let { a -> for (j in 0 until a.length()) a.optString(j).takeIf { it.isNotBlank() }?.let { terms.add(it) } }
                val channels = LinkedHashMap<String, String>()
                o.optJSONObject("channels")?.let { c -> for (k in c.keys()) channels[k] = c.optString(k, k) }
                out.add(
                    FilterRule(
                        id = o.optString("id").ifEmpty { java.util.UUID.randomUUID().toString() },
                        name = o.optString("name"),
                        terms = terms,
                        scope = try { Scope.valueOf(o.optString("scope", "BOTH")) } catch (_: IllegalArgumentException) { Scope.BOTH },
                        channelIds = channels,
                        enabled = o.optBoolean("enabled", true),
                        expiresAt = if (o.has("expiresAt")) o.optLong("expiresAt") else null,
                    )
                )
            }
            out
        } catch (_: Exception) { null }
    }
}
