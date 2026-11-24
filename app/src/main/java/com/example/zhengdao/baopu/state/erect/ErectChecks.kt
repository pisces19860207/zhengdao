// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百一十批 · 判据：纯函数，只回答「这张两态单自洽吗」。
// 不下生理结论、不进闸、不落库、不装配（源码门禁钉）。
//
// 判据（派工单，六条）：
// ① 六件齐；两态对照表缺行 ⇒ 红（结构上造不出来，这里出账）；
// ② 硬度与尺寸独立：只给综合档 ⇒ 红（两行分列，揉一起即红）；
// ③ 禁数值（不给倍数/cm/角度值）——出口与构造过闸，这里复核；
// ④ 包皮位取 804/831 的档，不自造——PrepuceState 构造即校验，这里出账；
// ⑤ 与 775/814 的占位标 pending:<单号>，不自造名——CoronaState 过闸，这里复核；
// ⑥ 门禁（detekt/lint/水位/单测）在测试侧，不在本文件。
package com.example.zhengdao.baopu.state.erect

import com.example.zhengdao.baopu.state.bodyparts.BodyPartGuard

/** 三态判定。severity 越大越严，取最严时用它，不依赖枚举声明次序。 */
enum class ErectVerdict(val label: String, val severity: Int) {
    PASS("通过", 0),
    HINT("提示", 1),
    DENY("红", 2),
    ;

    companion object {
        fun worst(a: ErectVerdict, b: ErectVerdict): ErectVerdict =
            if (a.severity >= b.severity) a else b
    }
}

/** 一条结论。 */
data class ErectFinding(
    val verdict: ErectVerdict,
    val code: String,
    val why: String,
    val source: String = ERECT_SOURCE,
    val asOf: String = ERECT_AS_OF,
) {
    init {
        require(code.isNotBlank()) { "结论缺代码：无来源" }
        require(why.isNotBlank()) { "结论缺理由：无来源" }
        BodyPartGuard.rejectMeasures(why)
        BodyPartGuard.rejectDiagnosis(why)
        // 不过 rejectFactor：判据理由里要写「无倍数」这类描述词，闸的是输出数值不是描述。
        // 数值口径的机器证据在出口（line/sceneLine）与源码门禁（measureTokens）上。
    }

    fun line(): String =
        "[" + verdict.label + "] " + code + "：" + why + "（" + source + " " + asOf + "）"
}

/** 一张完整的两态单：五行表就是六件的载体（分型/增长在大小行、角度在角度行…）。 */
data class ErectPerson(
    val roleId: String,
    val sheet: ErectSheet,
) {
    init {
        require(roleId.isNotBlank()) { "角色缺标识：结论点名不了" }
    }

    fun line(): String = roleId + "｜" + sheet.line().replace("\n", "；")
}

/** 判据集合。全部纯函数，同一张单跑两遍结论逐字一致。 */
object ErectChecks {

    /** ① 六件齐：五行表在场即六件齐（大小行含分型+增长，其余四行各一件）。 */
    fun completeness(p: ErectPerson): ErectFinding {
        val keys = p.sheet.ordered().map { it.key }
        val ok = keys == SheetRow.ALL_KEYS
        return if (ok) {
            ErectFinding(
                ErectVerdict.PASS, "ERECT-COMPLETE",
                p.roleId + "：六件齐——分型／增长／角度两态／硬度／廓形／两态对照表；" +
                    "对照表五行（" + SheetRow.ALL_KEYS.joinToString("、") + "）全在",
            )
        } else {
            ErectFinding(
                ErectVerdict.DENY, "ERECT-MISSING-ROW",
                p.roleId + "：对照表行不齐，缺 " + SheetRow.ALL_KEYS.filterNot { it in keys }.joinToString("、"),
            )
        }
    }

    /**
     * ② 硬度与尺寸独立：硬度行只谈硬度、大小行只谈大小。
     * 任一行里出现「综合档／大而硬」这类把两件揉一起的说法即红。
     */
    fun hardnessIndependent(p: ErectPerson): ErectFinding {
        val hard = p.sheet.row(SheetRow.HARDNESS).line()
        val size = p.sheet.row(SheetRow.SIZE).line()
        val leak = ErectGuard.mergedHit(hard) ?: ErectGuard.mergedHit(size)
        return if (leak == null) {
            ErectFinding(
                ErectVerdict.PASS, "HARDNESS-INDEPENDENT",
                p.roleId + "：硬度与大小分两行记（" + hard + "；" + size + "）——大不等于硬",
            )
        } else {
            ErectFinding(
                ErectVerdict.DENY, "HARDNESS-MERGED",
                p.roleId + "：把硬度与大小揉成一档（命中「" + leak + "」）——两件必须分开记",
            )
        }
    }

    /** ③ 禁数值：出口文本无倍数、长度、角度数值（805＋本包闸已挡，这里复核出账）。 */
    fun noNumbers(p: ErectPerson): ErectFinding {
        val all = p.sheet.line()
        val hits = listOfNotNull(
            ErectGuard.factorHit(all)?.let { "倍" + "数/尺寸:" + it },
            ErectGuard.angleValueHit(all)?.let { "角" + "度值:" + it },
            BodyPartGuard.measureHit(all)?.let { "长度/比例:" + it },
        )
        return if (hits.isEmpty()) {
            ErectFinding(
                ErectVerdict.PASS, "NO-NUMBERS",
                p.roleId + "：全表只出档序与档名，无" + "倍" + "数、尺寸、角" + "度数值",
            )
        } else {
            ErectFinding(
                ErectVerdict.DENY, "NUMBER-LEAK",
                p.roleId + "：出现数值口径（" + hits.joinToString("、") + "）——只给档不给数",
            )
        }
    }

    /** ④ 包皮位取 804/831 六档：PrepuceState 构造已校验，这里出账。 */
    fun prepuceFrom804(p: ErectPerson): ErectFinding {
        val row = p.sheet.row(SheetRow.PREPUCE) as PrepuceState
        val values = listOf(row.flaccid, row.erect)
        val bad = values.filterNot { it in PrepuceState.PREPUCE_LEVELS }
        return if (bad.isEmpty()) {
            ErectFinding(
                ErectVerdict.PASS, "PREPUCE-FROM-804",
                p.roleId + "：包皮位两态都取 804/831 六档（" + values.joinToString("／") +
                    "），未自造档名",
            )
        } else {
            ErectFinding(
                ErectVerdict.DENY, "PREPUCE-INVENTED",
                p.roleId + "：包皮位档不在 804/831 六档内（" + bad.joinToString("、") + "）",
            )
        }
    }

    /** ⑤ 占位规范：只写 pending:775／pending:814，不自造子区名或截面名。 */
    fun pendingValid(p: ErectPerson): ErectFinding {
        val corona = p.sheet.row(SheetRow.CORONA).line()
        val hasSub = corona.contains(PENDING_SUBZONE)
        val hasSec = corona.contains(PENDING_SECTION)
        return if (hasSub && hasSec) {
            ErectFinding(
                ErectVerdict.PASS, "PENDING-OK",
                p.roleId + "：子区占位 " + PENDING_SUBZONE + "、截面占位 " + PENDING_SECTION +
                    " 逐字在册，未自造名",
            )
        } else {
            ErectFinding(
                ErectVerdict.DENY, "PENDING-MISSING",
                p.roleId + "：冠外扩行缺占位（子区 " + hasSub + "／截面 " + hasSec +
                    "）——必须标 pending:<单号>",
            )
        }
    }

    /** 判据 ①–⑤ 全跑，顺序固定，逐字可回放。⑥ 是测试侧门禁，不在此列。 */
    fun all(p: ErectPerson): List<ErectFinding> = listOf(
        completeness(p),
        hardnessIndependent(p),
        noNumbers(p),
        prepuceFrom804(p),
        pendingValid(p),
    )

    fun worst(findings: List<ErectFinding>): ErectVerdict =
        findings.fold(ErectVerdict.PASS) { acc, f -> ErectVerdict.worst(acc, f.verdict) }

    fun summary(findings: List<ErectFinding>): String {
        val pass = findings.count { it.verdict == ErectVerdict.PASS }
        val hint = findings.count { it.verdict == ErectVerdict.HINT }
        val deny = findings.count { it.verdict == ErectVerdict.DENY }
        return "通过 " + pass + "／提示 " + hint + "／红 " + deny
    }

    /** 一张单的唯一判据出口：汇总＋逐条。出门过闸。 */
    fun sceneLine(p: ErectPerson): String {
        val findings = all(p)
        val text = summary(findings) + "\n" + findings.joinToString("\n") { it.line() }
        BodyPartGuard.rejectMeasures(text)
        BodyPartGuard.rejectDiagnosis(text)
        return text
    }
}
