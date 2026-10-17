package com.example.myapplication

/** One canonical identifier for each visually distinct hand pose. */
object GestureCatalog {
    val known = listOf(
        "Thumb_Up", "Thumb_Down", "Pointing_Up", "Closed_Fist",
        "Open_Palm", "Victory", "ILoveYou", "OK", "Shaka", "L_Shape"
    )
    val labels = mapOf(
        "Thumb_Up" to "👍 Thumbs Up",
        "Thumb_Down" to "👎 Thumbs Down",
        "Pointing_Up" to "☝️ Pointing Up",
        "Closed_Fist" to "✊ Fist",
        "Open_Palm" to "🖐️ Open Palm",
        "Victory" to "✌️ Peace / Victory",
        "ILoveYou" to "🤟 I Love You",
        "OK" to "👌 OK",
        "Shaka" to "🤙 Shaka",
        "L_Shape" to "🤟 L Shape"
    )
    /** Backwards compatibility: old mapping values are migrated to one name. */
    fun canonical(name: String): String = when (name) {
        "Peace" -> "Victory"
        "Fist" -> "Closed_Fist"
        "OpenPalm" -> "Open_Palm"
        "Thumbs_Up" -> "Thumb_Up"
        else -> name
    }
}