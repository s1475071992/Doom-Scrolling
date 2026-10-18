package com.example.myapplication

/** 八项普通模式动态手势；空鼠捏合单独处理。 */
object GestureCatalog {
    val known=listOf(
        "Index_Up", "Index_Down",
        "Two_Move_Up", "Two_Move_Down", "Two_Move_Left", "Two_Move_Right",
        "Palm_To_Fist", "Fist_To_Palm"
    )
    val labels=mapOf(
        "Index_Up" to "食指上翻",
        "Index_Down" to "食指下翻",
        "Two_Move_Up" to "双指向上移动",
        "Two_Move_Down" to "双指向下移动",
        "Two_Move_Left" to "双指向左移动",
        "Two_Move_Right" to "双指向右移动",
        "Palm_To_Fist" to "张掌变握拳",
        "Fist_To_Palm" to "握拳变张掌",
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
        "Two_Move_Right" to "食指、中指伸直，其余手指收拢；稳定后整只手向右移动",
        "Palm_To_Fist" to "先张开手掌稳定约半秒，看到 ✋ 后收拢手指握拳；默认单击屏幕中心",
        "Fist_To_Palm" to "先握拳稳定约半秒，看到 ✊ 后张开手掌；默认双击屏幕中心"
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
