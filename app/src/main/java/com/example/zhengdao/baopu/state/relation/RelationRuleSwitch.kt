// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第九百二十一批 · 五条规则的**判据专用开关**。
// 生产默认恒为正确值；只有变异判据在单个 JVM 里临时翻转，跑完立即复位。
// 源码门禁钉死默认值与「生产路径零 ablate 调用」——不许把开关常关来骗绿。
package com.example.zhengdao.baopu.state.relation

internal object RelationRuleSwitch {
    @Volatile
    var treatMergedAsValid: Boolean = false
        private set

    @Volatile
    var enforceCeiling: Boolean = true
        private set

    @Volatile
    var requireEvent: Boolean = true
        private set

    @Volatile
    var rapportPromotesKind: Boolean = false
        private set

    @Volatile
    var enforceRelationSex: Boolean = true
        private set

    /** 只给 V1–V5 用；判完必须 reset。 */
    fun ablate(id: String) {
        when (id) {
            "V1" -> treatMergedAsValid = true
            "V2" -> enforceCeiling = false
            "V3" -> requireEvent = false
            "V4" -> rapportPromotesKind = true
            "V5" -> enforceRelationSex = false
            else -> throw IllegalArgumentException("未知变异：$id")
        }
    }

    fun reset() {
        treatMergedAsValid = false
        enforceCeiling = true
        requireEvent = true
        rapportPromotesKind = false
        enforceRelationSex = true
    }

    fun snapshot(): String = listOf(
        "V1=" + treatMergedAsValid,
        "V2=" + enforceCeiling,
        "V3=" + requireEvent,
        "V4=" + rapportPromotesKind,
        "V5=" + enforceRelationSex,
    ).joinToString(",")
}
