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
    val displayNames = mapOf(
        NONE to "不执行", TAP to "点击屏幕", DOUBLE_TAP to "双击屏幕",
        SWIPE_UP to "手指向上滑", SWIPE_DOWN to "手指向下滑",
        SWIPE_LEFT to "手指向左滑", SWIPE_RIGHT to "手指向右滑",
        VOLUME_UP to "音量增加", VOLUME_DOWN to "音量降低",
        BACK to "返回", HOME to "返回桌面", RECENTS to "最近任务",
        SCROLL_UP to "向上翻页", SCROLL_DOWN to "向下翻页"
    )
    fun label(action:String): String = displayNames[action] ?: "未设置动作"

    private val defaults = mapOf(
        "Index_Up" to SCROLL_UP,
        "Index_Down" to SCROLL_DOWN,
        "Two_Up" to SCROLL_UP,
        "Two_Down" to SCROLL_DOWN,
        "Pinch1_Up" to SCROLL_UP,
        "Pinch1_Down" to SCROLL_DOWN,
        "Pinch1_Left" to SWIPE_LEFT,
        "Pinch1_Right" to SWIPE_RIGHT,
        "Pinch2_Up" to SCROLL_UP,
        "Pinch2_Down" to SCROLL_DOWN,
        "Pinch2_Left" to SWIPE_LEFT,
        "Pinch2_Right" to SWIPE_RIGHT
    )
    private const val FILE = "our_gesture_action_map"
    private const val KEY = "mappings"

    private fun read(context: Context): MutableMap<String, String> {
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val raw = p.getString(KEY, null)
        if (raw.isNullOrBlank()) return defaults.toMutableMap()
        return try {
            val o = JSONObject(raw)
            val map = defaults.toMutableMap()
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
        } catch (_: Exception) { defaults.toMutableMap() }
    }
    fun actionFor(context: Context, gesture: String): String =
        read(context)[GestureCatalog.canonical(gesture)] ?: NONE
    fun save(context: Context, gesture: String, action: String) {
        require(action in actions)
        val next = read(context)
        val name = GestureCatalog.canonical(gesture)
        // 存储 NONE 而不是删除，防止重新落到默认动作。
        next[name] = action
        val o = JSONObject()
        next.forEach { (key, value) -> o.put(key, value) }
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY, o.toString()).apply()
    }
}