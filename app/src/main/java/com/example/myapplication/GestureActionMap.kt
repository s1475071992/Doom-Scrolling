package com.example.myapplication

import android.content.Context
import org.json.JSONObject

/** Stored mappings; completely separate from accessibility permission handling. */
object GestureActionMap {
    const val NONE = "NONE"
    const val TAP = "TAP"
    const val DOUBLE_TAP = "DOUBLE_TAP"
    const val SWIPE_UP = "SWIPE_UP"
    const val SWIPE_DOWN = "SWIPE_DOWN"
    const val SWIPE_LEFT = "SWIPE_LEFT"
    const val SWIPE_RIGHT = "SWIPE_RIGHT"
    const val VOLUME_UP = "VOLUME_UP"
    const val VOLUME_DOWN = "VOLUME_DOWN"
    const val BACK = "BACK"
    const val HOME = "HOME"
    const val RECENTS = "RECENTS"
    const val SCROLL_UP = "SCROLL_UP"
    const val SCROLL_DOWN = "SCROLL_DOWN"

    val actions = listOf(
        NONE, TAP, DOUBLE_TAP, SWIPE_UP, SWIPE_DOWN, SWIPE_LEFT, SWIPE_RIGHT,
        VOLUME_UP, VOLUME_DOWN, BACK, HOME, RECENTS, SCROLL_UP, SCROLL_DOWN
    )
    private const val FILE = "our_gesture_action_map"
    private const val KEY = "mappings"

    private fun read(context: Context): MutableMap<String, String> {
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val raw = p.getString(KEY, null)
        if (raw.isNullOrBlank()) return mutableMapOf(
            "Thumb_Up" to DOUBLE_TAP,
            "Pointing_Up" to SWIPE_UP
        )
        return try {
            val o = JSONObject(raw)
            val map = mutableMapOf<String, String>()
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val a = o.optString(k, NONE)
                if (a in actions) {
                    val canonical = GestureCatalog.canonical(k)
                    // Prefer canonical key over a legacy alias if both exist.
                    if (canonical !in map || k == canonical) map[canonical] = a
                }
            }
            map
        } catch (_: Exception) { mutableMapOf() }
    }
    fun actionFor(context: Context, gesture: String): String =
        read(context)[GestureCatalog.canonical(gesture)] ?: NONE
    fun save(context: Context, gesture: String, action: String) {
        require(action in actions)
        val next = read(context)
        val name = GestureCatalog.canonical(gesture)
        if (action == NONE) next.remove(name) else next[name] = action
        val o = JSONObject()
        next.forEach { (key, value) -> o.put(key, value) }
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY, o.toString()).apply()
    }
}