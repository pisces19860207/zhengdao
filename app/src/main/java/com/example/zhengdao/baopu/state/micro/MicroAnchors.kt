// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百二十一批 · 挂靠锚点：判据⑥「与既有单不冲突」的机器可读声明。
// 本包只加旁路元数据（速率/联动/词表），不改任何既有档位定义、不装配、不进闸。
package com.example.zhengdao.baopu.state.micro

/** 一个挂靠关系：本单的哪一块挂到哪张既有单的哪个落账口。 */
data class Anchor(val block: String, val target: String, val mode: String)

/**
 * 判据⑥的三条挂靠声明：
 * · 速率 → 731（13 区此刻态载体）/ 810（勃起动态）—— 只加 rate/edge，档位本体仍归 form 包；
 * · 联动 → 658（轻生理）/ 730（昼夜温湿，锚未落地）—— 只记牵动对，不改生理档；
 * · 词表 → 669（词表与误写）—— 共用 docs/facts/anatomy-zones.json，不复制第二份。
 */
object MicroAnchors {
    val ALL: List<Anchor> = listOf(
        Anchor("速率", "731/810", "旁路元数据·不改档位"),
        Anchor("联动", "658/730", "牵动对·730 仅留来源锚"),
        Anchor("词表", "669", "共用 anatomy-zones.json·不复制"),
    )

    /** 三块都在、mode 非空即合规；缺块或空 mode ⇒ 红。 */
    fun check(): List<MicroFinding> {
        val out = mutableListOf<MicroFinding>()
        val blocks = ALL.map { it.block }.toSet()
        for (need in listOf("速率", "联动", "词表")) {
            if (need !in blocks) {
                out += MicroFinding("X1", MicroSeverity.ERROR, null, "挂靠声明缺 $need 块")
            }
        }
        if (ALL.any { it.target.isBlank() || it.mode.isBlank() }) {
            out += MicroFinding("X1", MicroSeverity.ERROR, null, "挂靠声明存在空 target/mode")
        }
        return out
    }
}
