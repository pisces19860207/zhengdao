// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百六十批 · 形轨.形体参数：硬误写守卫。校验的是**建出来的模型**，不只是表。
// 命中即报 vocab_error（指名区与正确说法），只报不改写——改写归调用方。
package com.example.zhengdao.baopu.state.form

/** 六条硬误写＋紫红防漂锁。表里每条不变式都必须在模型读数上成立。 */
object ZoneGuard {

    fun check(model: GenitalModel, table: ZoneTable, names: Map<String, String>): List<VocabError> {
        val out = mutableListOf<VocabError>()
        for (inv in table.invariants) {
            if (!holds(model, table, inv)) out.add(vocabError(inv, names))
        }
        if (!purpleOk(model, table)) out.add(purpleError(table, names))
        return out.toList()
    }

    private fun holds(model: GenitalModel, table: ZoneTable, inv: ZoneInvariant): Boolean {
        val instances = model.zones.filter { it.zoneId == inv.zoneId }
        require(instances.isNotEmpty()) { "不变式 ${inv.id} 指向未建出的区 ${inv.zoneId}" }
        val spec = table.zone(inv.zoneId)
        return instances.all { fieldOf(it, spec, inv.field) == inv.expected } && behavior(model, inv)
    }

    private fun fieldOf(z: GenitalZone, spec: ZoneSpec, field: String): String? = when (field) {
        "side" -> z.side
        "form" -> z.form
        "surface" -> z.surfaceRule
        "temp_linked" -> spec.tempLinked.toString()
        "left_lower" -> spec.leftLower.toString()
        else -> throw IllegalArgumentException("不变式字段 $field 无对应读数，拒收")
    }

    /** 结构字段之外还要证行为：光滑一档／颜色深一档／左低／阴囊真带着温度。 */
    private fun behavior(model: GenitalModel, inv: ZoneInvariant): Boolean = when (inv.zoneId) {
        "glans" -> smoother(model) && darker(model)
        "testis" -> leftLower(model)
        "scrotum" -> model.scrotum.tempC == model.tempC
        else -> true
    }

    private fun smoother(model: GenitalModel): Boolean =
        one(model, "glans", "mid").surfaceRank > one(model, "shaft", "mid").surfaceRank

    private fun darker(model: GenitalModel): Boolean =
        luma(one(model, "glans", "mid").colorHex) < luma(one(model, "shaft", "mid").colorHex)

    private fun leftLower(model: GenitalModel): Boolean =
        one(model, "testis", "left").centerCm.y < one(model, "testis", "right").centerCm.y

    private fun one(model: GenitalModel, zoneId: String, side: String): GenitalZone =
        model.zones.first { it.zoneId == zoneId && it.sideLabel == side }

    private fun purpleOk(model: GenitalModel, table: ZoneTable): Boolean =
        !model.constricted || !table.purple.requiresTempDrop ||
            model.tempC < table.domain("scrotum_ref_temp_c").default

    private fun vocabError(inv: ZoneInvariant, names: Map<String, String>): VocabError = VocabError(
        ruleId = inv.id, zoneId = inv.zoneId, zoneName = names.getValue(inv.zoneId),
        correction = inv.correction, source = inv.source,
    )

    private fun purpleError(table: ZoneTable, names: Map<String, String>): VocabError = VocabError(
        ruleId = "VG-PURPLE", zoneId = "shaft", zoneName = names.getValue("shaft"),
        correction = table.purple.caliber, source = table.purple.source,
    )

    /** BT.601 亮度系数：判"深一档"的方向，不判具体色差（色差归呈现侧）。 */
    private fun luma(hex: String): Double {
        val s = hex.removePrefix("#")
        require(s.length == 6) { "色值须 6 位 hex 才能比深浅，得 $hex" }
        val r = s.substring(0, 2).toInt(16)
        val g = s.substring(2, 4).toInt(16)
        val b = s.substring(4, 6).toInt(16)
        return 0.299 * r + 0.587 * g + 0.114 * b
    }
}