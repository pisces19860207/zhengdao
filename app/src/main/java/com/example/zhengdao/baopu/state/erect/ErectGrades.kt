// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百一十批 · 五件档位：增长分型／增长档／角度方向／硬度 EHS／廓形变化。
//
// 五套枚举互不通兑：分型是质性归类、增长档是量级、角度是方向、硬度独立成账、
// 廓形是形状变化。把两套档揉成一个字段，编译期就进不来。
//
// 全档位、零数值：label 只有中文档名，level 只表先后不是物理量。
package com.example.zhengdao.baopu.state.erect

/** ① 增长分型三型。只描述两态相对变化，不含倍数与尺寸。 */
enum class GrowthType(val label: String, val level: Int) {
    INCREASE("增长型", 0),   // 疲软小、勃起明显
    FLAT("平缓型", 1),        // 疲软已较大、变化小
    MIDDLE("中间型", 2),      // 介于两者之间
    ;

    companion object {
        const val COUNT = 3

        init {
            require(entries.size == COUNT) { "增长分型必须恰好三型" }
            require(entries.map { it.level } == (0 until COUNT).toList()) {
                "增长分型档序必须 0..$COUNT 连续"
            }
        }

        fun of(level: Int): GrowthType =
            entries.firstOrNull { it.level == level }
                ?: throw IllegalArgumentException("增长分型档序越界：$level（只有 0..${COUNT - 1}）")
    }
}

/** ② 增长档 0–3：给档不给倍数。level 是档序，不是任何物理量。 */
enum class GrowthTier(val label: String, val level: Int) {
    NONE("几乎不变", 0),
    SLIGHT("略增", 1),
    CLEAR("明显增", 2),
    LARGE("大幅增", 3),
    ;

    companion object {
        const val COUNT = 4
        const val LAST = 3

        init {
            require(entries.size == COUNT) { "增长档必须恰好四档" }
            require(entries.map { it.level } == (0..LAST).toList()) {
                "增长档档序必须 0..$LAST 连续"
            }
        }

        /** 越界即抛，不静默夹取。 */
        fun of(level: Int): GrowthTier =
            entries.firstOrNull { it.level == level }
                ?: throw IllegalArgumentException("增长档档序越界：$level（只有 0..$LAST）")
    }
}

/** ③ 角度方向档。只表方向，不出度数或角度数值。疲软与勃起各记一列（见 AnglePair）。 */
enum class ErectAngle(val label: String, val level: Int) {
    UP("上翘", 0),
    SLIGHT_UP("略上", 1),
    FLAT("平", 2),
    SLIGHT_DOWN("略下", 3),
    ;

    companion object {
        const val COUNT = 4
        const val LAST = 3

        init {
            require(entries.size == COUNT) { "角度方向必须恰好四档" }
            require(entries.map { it.level } == (0..LAST).toList()) {
                "角度方向档序必须 0..$LAST 连续"
            }
        }

        fun of(level: Int): ErectAngle =
            entries.firstOrNull { it.level == level }
                ?: throw IllegalArgumentException("角度方向档序越界：$level（只有 0..$LAST）")
    }
}

/**
 * ④ 硬度档 EHS 四级。对齐 facts.json 的 fb.hardness_ehs（0＝无反应不属本单，本单只管勃起四级）。
 * 字符串引用口径见 [HARDNESS_REF]；不 import 660 类型、不在本包重立刻度。
 * 硬度与尺寸独立：这里只描述硬度本身，不说大小。
 */
enum class HardnessTier(val label: String, val ehs: Int) {
    EHS1("稍硬", 1),
    EHS2("可插入", 2),
    EHS3("坚挺", 3),
    EHS4("极硬", 4),
    ;

    companion object {
        const val COUNT = 4

        init {
            require(entries.size == COUNT) { "硬度档必须恰好四级" }
            require(entries.map { it.ehs } == (1..4).toList()) {
                "硬度档必须对齐 EHS 1..4（0＝无反应不属本单）"
            }
        }

        fun of(ehs: Int): HardnessTier =
            entries.firstOrNull { it.ehs == ehs }
                ?: throw IllegalArgumentException("EHS 档越界：$ehs（本单只有 1..4）")
    }
}

/**
 * ⑤ 勃起廓形变化四档。只记变化方向，不定义截面（814 的账）。
 * 冠更外扩档的截面细节在对照表里写 [PENDING_SECTION]，子区引用写 [PENDING_SUBZONE]。
 */
enum class ErectProfile(val label: String, val level: Int) {
    STRAIGHT("变直", 0),
    CURVE("弯曲更显", 1),
    CORONA("冠更外扩", 2),
    SAME("不变", 3),
    ;

    companion object {
        const val COUNT = 4
        const val LAST = 3

        init {
            require(entries.size == COUNT) { "廓形变化必须恰好四档" }
            require(entries.map { it.level } == (0..LAST).toList()) {
                "廓形变化档序必须 0..$LAST 连续"
            }
        }

        fun of(level: Int): ErectProfile =
            entries.firstOrNull { it.level == level }
                ?: throw IllegalArgumentException("廓形变化档序越界：$level（只有 0..$LAST）")
    }
}
