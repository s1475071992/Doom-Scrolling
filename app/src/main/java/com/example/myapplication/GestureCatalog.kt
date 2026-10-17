package com.example.myapplication

/** 仅包含当前支持的六项普通模式动态手势；空鼠捏合单独处理。 */
object GestureCatalog {
    val known=listOf(
        "Index_Up", "Index_Down",
        "Two_Move_Up", "Two_Move_Down", "Two_Move_Left", "Two_Move_Right"
    )
    val labels=mapOf(
        "Index_Up" to "食指上翻",
        "Index_Down" to "食指下翻",
        "Two_Move_Up" to "双指向上移动",
        "Two_Move_Down" to "双指向下移动",
        "Two_Move_Left" to "双指向左移动",
        "Two_Move_Right" to "双指向右移动",
        "Thumb_Up" to "竖大拇指", "Thumb_Down" to "大拇指朝下",
        "Pointing_Up" to "食指朝上", "Closed_Fist" to "握拳",
        "Open_Palm" to "张开手掌", "Victory" to "剪刀手",
        "ILoveYou" to "我爱你", "OK" to "好的手势",
        "Shaka" to "六六六手势", "L_Shape" to "L形手势",
        "None" to "未识别", "No_Gesture" to "未识别"
    )
    val startingPoses=mapOf(
        "Index_Up" to "握拳保持约半秒，看到提示后仅伸直食指",
        "Index_Down" to "食指伸直，其余手指收拢，稳定保持后弯下食指",
        "Two_Move_Up" to "食指、中指伸直，其余手指收拢；稳定后整只手向上移动",
        "Two_Move_Down" to "食指、中指伸直，其余手指收拢；稳定后整只手向下移动",
        "Two_Move_Left" to "食指、中指伸直，其余手指收拢；稳定后整只手向左移动",
        "Two_Move_Right" to "食指、中指伸直，其余手指收拢；稳定后整只手向右移动"
    )
    fun label(key:String):String = labels[canonical(key)] ?: key
    fun canonical(name:String):String = when(name) {
        "Peace" -> "Victory"
        "Fist" -> "Closed_Fist"
        "OpenPalm" -> "Open_Palm"
        "Thumbs_Up" -> "Thumb_Up"
        else -> name
    }
}
