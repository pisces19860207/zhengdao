// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第九百四十批 · 情绪动作个体化：人物私有动作簿 + 段落事件。
// habitGestures 只在本包聚合，不进 SurfaceState.tags；gestureId 可空，「她没动」是合法写法。
package com.example.zhengdao.baopu.state.persona

/** 一条人物私有习惯动作。四要素可空是为了让判据能报出缺了哪几项，而不是构造时抛异常。 */
internal data class HabitGesture(
    val id: String,
    val characterId: String,
    val action: String,
    val part: GesturePart? = null,
    val direction: GestureDirection? = null,
    val amplitude: GestureAmplitude? = null,
    val durationMs: Int? = null,
    val source: GestureSourceRef? = null,
) {
    /** 动作键：跨人物复用判定只看动作本身，参数不同也按同一个动作算。 */
    val actionKey: String get() = action.trim().lowercase()
}

/**
 * 一段里的动作事件。gestureId=null ⇒ 这一段没有外化动作（可以只有情绪、语气或沉默）。
 * text 是该段的叙述文字，位置判定只把台词部分遮掉，不把台词里的词当动作。
 */
internal data class GestureBeat(
    val paragraph: Int,
    val characterId: String,
    val gestureId: String? = null,
    val text: String = "",
    val emotion: Emotion? = null,
)

internal data class PersonaGestureProfile(
    val characterId: String,
    val habitGestures: List<HabitGesture>,
    val beats: List<GestureBeat> = emptyList(),
)
