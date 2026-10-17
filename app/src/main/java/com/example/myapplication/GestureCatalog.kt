package com.example.myapplication

/** 对外只展示中文名称；内部键保留英文，兼容旧数据和自训练模型。 */
object GestureCatalog {
    val known = listOf(
        "Index_Up", "Index_Down", "Two_Up", "Two_Down",
        "Pinch1_Up", "Pinch1_Down", "Pinch1_Left", "Pinch1_Right",
        "Pinch2_Up", "Pinch2_Down", "Pinch2_Left", "Pinch2_Right"
    )
    val labels = mapOf(
        "Index_Up" to "食指上翻", "Index_Down" to "食指下翻",
        "Two_Up" to "双指上翻", "Two_Down" to "双指下翻",
        "Pinch1_Up" to "单指捏合上移", "Pinch1_Down" to "单指捏合下移",
        "Pinch1_Left" to "单指捏合左移", "Pinch1_Right" to "单指捏合右移",
        "Pinch2_Up" to "双指捏合上移", "Pinch2_Down" to "双指捏合下移",
        "Pinch2_Left" to "双指捏合左移", "Pinch2_Right" to "双指捏合右移",
        "Thumb_Up" to "竖大拇指", "Thumb_Down" to "大拇指朝下",
        "Pointing_Up" to "食指朝上", "Closed_Fist" to "握拳",
        "Open_Palm" to "张开手掌", "Victory" to "剪刀手",
        "ILoveYou" to "我爱你", "OK" to "好的手势",
        "Shaka" to "六六六手势", "L_Shape" to "L形手势",
        "None" to "未识别", "No_Gesture" to "未识别"
    )
    val startingPoses = mapOf(
        "Index_Up" to "先握拳，识别提示后只伸直食指",
        "Index_Down" to "先伸直食指，中指握紧；再弯下食指",
        "Two_Up" to "先握拳，识别提示后同时伸直食指和中指",
        "Two_Down" to "食指、中指先伸直；再一起弯下",
        "Pinch1_Up" to "拇指＋食指捏合，保持捏合后向上移动手掌",
        "Pinch1_Down" to "拇指＋食指捏合，保持捏合后向下移动手掌",
        "Pinch1_Left" to "拇指＋食指捏合，保持捏合后向左移动手掌",
        "Pinch1_Right" to "拇指＋食指捏合，保持捏合后向右移动手掌",
        "Pinch2_Up" to "拇指＋食指＋中指汇聚，保持捏合后向上移动",
        "Pinch2_Down" to "拇指＋食指＋中指汇聚，保持捏合后向下移动",
        "Pinch2_Left" to "拇指＋食指＋中指汇聚，保持捏合后向左移动",
        "Pinch2_Right" to "拇指＋食指＋中指汇聚，保持捏合后向右移动"
    )
    fun label(key: String) = labels[canonical(key)] ?: key
    fun canonical(name: String) = when (name) {
        "Peace" -> "Victory"
        "Fist" -> "Closed_Fist"
        "OpenPalm" -> "Open_Palm"
        "Thumbs_Up" -> "Thumb_Up"
        else -> name
    }
}
