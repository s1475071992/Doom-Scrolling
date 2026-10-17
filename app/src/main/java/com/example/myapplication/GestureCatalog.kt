package com.example.myapplication

/** Original GestureControl gesture keys, including aliases used by its library. */
object GestureCatalog {
    val known = listOf(
        "Thumb_Up", "Pointing_Up", "Thumb_Down", "Closed_Fist", "Open_Palm",
        "Victory", "ILoveYou", "Peace", "Fist", "OK", "Shaka", "L_Shape"
    )
    val labels = mapOf(
        "Thumb_Up" to "👍 Thumbs Up",
        "Pointing_Up" to "☝️ Pointing Up",
        "Thumb_Down" to "👎 Thumbs Down",
        "Closed_Fist" to "✊ Closed Fist",
        "Open_Palm" to "🖐️ Open Palm",
        "Victory" to "✌️ Victory",
        "ILoveYou" to "🤟 I Love You",
        "Peace" to "✌️ Peace (geometry)",
        "Fist" to "✊ Fist (geometry)",
        "OK" to "👌 OK",
        "Shaka" to "🤙 Shaka",
        "L_Shape" to "🤟 L Shape"
    )
}