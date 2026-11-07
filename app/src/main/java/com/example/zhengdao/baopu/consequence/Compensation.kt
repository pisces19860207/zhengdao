// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百零六批（2026-11-07）· 472 赔付——四件缺一不算，且「赔不了」必须直说。
//
// 派工单 §一 的四件在这里各有一句代码兜着：
//   ① 可恢复 ⇒ [RecoveryVerdict]：能还原就还原；还原不了的出 [LossItem] 清单（文件／大小／最后修改）
//   ② 说明   ⇒ [PlainNote]：≤5 行白话，三栏（发生了什么／为什么／你现在该做什么）缺一栏即缺陷
//   ③ 防再发 ⇒ [Prevention]：同类错误必须触发一条门禁或规矩（进 ERRATA 或总账），第二次必须是**机检**
//   ④ 留证   ⇒ [ReplayTrail]：四个阶段少一个就点名少哪个；**赔不了要直说**见 [CANNOT_PAY_MARK]
//
// 「四件缺一不算」不是一句口号：[Compensation.defects] 会逐件回答哪一件不合规，
// 装配层拿到非空清单就**不许**把这次赔付报成完成（判据口径：缺项＝未完成，不是扣分）。
//
// 关于「复用 470」（2026-11-07 两次实测，别照抄这一段的旧版本）：
// **开工时**基线 `9ffd9227` 上 `git ls-tree -r --name-only origin/main` 查不到 `baopu/safety/`
// ——那座按次快照库当时还挂在 504 的 `feat/safety-trio`（`2fdb73e3`）上，没进 main；
// **交付时**基线已推进到 `b866aa2f`（504 的三件连同验收接管已并入），
// `baopu/safety/RollbackVault.kt` 现在**在 main 里**了（`stash(...)` 存、`rollback(RollbackScope, nowMs)` 取）。
// 即便如此本文件仍**不 import** 它：工单没给装配租约，且 472 只判「有没有路、试没试过、赔不赔得起」，
// **动手还原的仍是 470 那一路**——注入点是 main 已有的那道通用回滚口，装配批一行 lambda 就能接：
//   · `DecisionRollbacks.run(target, root, handler, atMs, actor)`（`DecisionLedger.kt:515`「回滚执行＋留痕」那段），
//     handler 的形状是 `DecisionRollbackHandler.rollback(DecisionRecord): Boolean`（`DecisionLedger.kt:509`）
//     ⇒ 把 `RollbackVault.rollback(RollbackScope.ByOp(opId), nowMs)` 的结果折成 Boolean 注进来，**本文件一行不改**；
//   · 各族现成的还原路由：`PersonaBackup.restore`／`SkillStore.restore`／回收站（`DeletePathTool`）／
//     `备份/` 里的 .bak 原件（`WriteFileTool`、`EditFileTool`）。
// 这条依赖（工单引用了当时未合并的件）写在交付报告 §〇，并在制度总账登记为 **T-43**。
//
// 房型同本包其余文件：零 IO、零时钟、零 Android。时刻与路径由调用方给。
package com.example.zhengdao.baopu.consequence

/**
 * 还原判定（**闭集**三态）。
 *
 * [UNRECOVERABLE] 的中文名就是派工单要的那三个字——「赔不了」。
 * 它必须出现在说明的第一行（[PlainNote] 钉这一条），不许委婉成「可能无法完全恢复」。
 */
internal enum class RecoveryVerdict(val wire: String, val cn: String) {
    RESTORED("restored", "已还原"),
    PARTIAL("partial", "部分还原"),
    UNRECOVERABLE("unrecoverable", "赔不了"),
    ;

    companion object {
        /** 派工单 §一·④ 点名的那三个字（稳定键：说明第一行必须含它）。 */
        const val CANNOT_PAY_MARK: String = "赔不了"

        fun ofWire(wire: String): RecoveryVerdict? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * 永久损失清单的一行（文件／大小／最后修改，派工单 §一·① 点名的三样）。
 *
 * 为什么强调「最后修改」：出清单的人下一步要问的是「我还能从哪儿弄回这一份」，
 * 修改时刻直接决定哪一天的备份对它有效。少了这一列，清单就只是一张「丢了什么」的安慰剂。
 */
internal data class LossItem(val path: String, val sizeBytes: Long, val lastModifiedMs: Long) {

    /** 清单上的一行（时刻由装配层按本地时区格式化后再传进来，本层不看时钟）。 */
    fun line(lastModifiedText: String): String = "$path　$sizeBytes B　最后修改 $lastModifiedText"

    /** 形状合不合格：路径空、大小为负、时刻为 0 都是「半条清单」。 */
    fun isWellFormed(): Boolean = path.isNotBlank() && sizeBytes >= 0L && lastModifiedMs > 0L
}

/** 防再发条目的落点（派工单 §一·③：进 ERRATA 或总账）。 */
internal enum class PreventionTarget(val wire: String, val cn: String) {
    ERRATA("errata", "坑账 ERRATA"),
    RULES_LEDGER("rules_ledger", "制度总账"),
    ;

    companion object {
        fun ofWire(wire: String): PreventionTarget? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * 一条防再发规矩。
 *
 * @param entryId 账上的编号（`E-xxx`／`T-xx`）——**只写「以后注意」不算一条**
 * @param mechanism 现在靠什么挡住复发（门禁脚本/测试/路径）；空＝纯文字规矩
 */
internal data class Prevention(
    val target: PreventionTarget,
    val entryId: String,
    val mechanism: String = "",
) {
    /** 这条是不是**机检**（有真实路径可查），还是只有文字。 */
    val isMachineChecked: Boolean
        get() = entryId.isNotBlank() && mechanism.isNotBlank() && MECHANISM_SHAPE.matches(mechanism)

    companion object {
        /**
         * 机制列要长成**仓内路径**的样子（门禁脚本／测试／配置；自由文字不算机制）。
         *
         * 为什么整串比对（`Regex.matches` 本来就是全串锚定）：早先这里写成
         * `^(tools/|app/|…)`——看着像前缀判，其实 `matches` 要求**整条**匹配，
         * 于是 `tools/check-rules-ledger.py` 这类真路径**一条都不算机检**，
         * 判据②「第二次起必须机检」就永远只能靠人自觉（测试 `判据2` 把它钉红了）。
         * 现在这条是「已知目录打头，后面接一段不带空格的路径」——纯文字的规矩仍然过不来。
         */
        private val MECHANISM_SHAPE = Regex(
            "(?:tools/|app/|policy/|core/|native-core/|config/|gradle/|docs/|licenses/|\\.github/|[.\\\\])\\S.*",
        )
    }
}

/** 留证的阶段（派工单 §一·④「全过程可回放」：这四段拼起来才是全过程）。 */
internal enum class TrailStage(val wire: String, val cn: String) {
    DETECT("detect", "发现"),
    JUDGE("judge", "判定"),
    ACT("act", "处置"),
    VERIFY("verify", "复核"),
    ;

    companion object {
        fun ofWire(wire: String): TrailStage? = entries.firstOrNull { it.wire == wire }
    }
}

/** 留证的一条（哪个阶段、什么时候、留了什么）。 */
internal data class TrailStep(val stage: TrailStage, val atMs: Long, val note: String)

/** 回放链（缺哪段就点名缺哪段，不许用「已留证」三个字糊过去）。 */
internal data class ReplayTrail(val steps: List<TrailStep>) {

    /** 四段都在吗；缺的段按中文名列出。 */
    fun missingStages(): List<String> =
        TrailStage.entries.filter { s -> steps.none { it.stage == s } }.map { it.cn }

    val replayable: Boolean get() = missingStages().isEmpty()

    /** 按时刻排好序的回放行（装配层直接打这张表）。 */
    fun replayLines(): List<String> =
        steps.sortedBy { it.atMs }.map { "[${it.stage.cn}] ${it.atMs} ${it.note}" }
}

/**
 * 一次损坏的事实（**全部由调用方算好**递进来）。
 *
 * @param sameTypeOccurrence 同型错误第几次（判据②的分母；1＝第一次）
 * @param restoreRoutesKnown 这一族**有没有**还原路（470 的通道／.bak／回收站／备份名）
 * @param restoreRoutesTried 试过了吗——「有路没试就喊赔不了」是本文件唯一一条一票否决
 * @param restoredPaths 真还原回来的路径
 * @param lostItems 还原不了的清单（① 要求的那张）
 */
internal data class DamageFacts(
    val opId: String,
    val tool: String,
    val whatHappened: String,
    val why: String,
    val nextStepForUser: String,
    val sameTypeOccurrence: Int = 1,
    val restoreRoutesKnown: Boolean = false,
    val restoreRoutesTried: Boolean = false,
    val restoredPaths: List<String> = emptyList(),
    val lostItems: List<LossItem> = emptyList(),
    val trail: ReplayTrail = ReplayTrail(emptyList()),
    val prevention: Prevention? = null,
)

/** 一句话缺陷清单的容器（①②③④ 各归各的检查函数，最后汇到 [Compensation.defects]）。 */
internal data class PlainNote(val lines: List<String>) {

    /** 超过 5 行就不叫「≤5 行白话」。 */
    val tooLong: Boolean get() = lines.size > MAX_LINES

    val blank: Boolean get() = lines.any { it.isBlank() } || lines.isEmpty()

    companion object {
        /** 派工单 §一·② 的上限。 */
        const val MAX_LINES = 5

        /** 第一行必须含 [RecoveryVerdict.CANNOT_PAY_MARK] 吗（赔不了时才钉）。 */
        fun mustSayCannotPay(verdict: RecoveryVerdict, lines: List<String>): Boolean =
            verdict != RecoveryVerdict.UNRECOVERABLE ||
                lines.firstOrNull().orEmpty().contains(RecoveryVerdict.CANNOT_PAY_MARK)
    }
}

/**
 * 一次赔付（四件的成品）。
 *
 * 拿到它之后装配层只做两件事：**落账**（[ConsequenceLedger]）＋ 按 [defects] 决定能不能报完成。
 * 本文件不写盘、不发通知——「赔不了」要说给人听，那是 L3 放宽口径里唯一还许打扰人的那一格
 * （派工单 §四：能自动恢复的自动恢复不打扰；只有赔不了／永久损失才报人）。
 */
internal data class Compensation(
    val opId: String,
    val tool: String,
    val verdict: RecoveryVerdict,
    val note: PlainNote,
    val lossList: List<LossItem>,
    val prevention: Prevention?,
    val trail: ReplayTrail,
    /** 同型错误第几次（[DamageFacts.sameTypeOccurrence] 原样带过来；判据②的分母）。 */
    val occurrence: Int = 1,
) {

    /**
     * 是不是要把这件事说给人听（§四 的「只有赔不了／**永久损失**才报人」）。
     *
     * 两个条件而不是一个：部分还原但还剩两样捞不回来的，用户必须知道——
     * 「赔不了」只是永久损失里最重的那一档，§四 原文点的是两件事。
     */
    val mustReportToHuman: Boolean get() = verdict == RecoveryVerdict.UNRECOVERABLE || lossList.isNotEmpty()

    /** 四件逐件检查：空清单＝四件齐（不算缺）。四件**各自查各自的**，不许一件挡掉后面三件。 */
    fun defects(): List<String> {
        val out = mutableListOf<String>()
        if (note.tooLong) out += "② 说明超了 ${PlainNote.MAX_LINES} 行（现 ${note.lines.size} 行）"
        if (note.blank) out += "② 说明有空白行（三栏缺一不可）"
        verdict.checkRecoverability(lossList, out)
        PreventionRules.checkEntry(prevention, out)
        // 只有第二次起才追加这一句：第一次缺条目已经被 checkEntry 点名了，
        // 同一次赔付报两条讲的是同一件事的缺陷，读账的人会以为踩了两个坑。
        if (occurrence > 1 && !PreventionRules.sufficientAt(occurrence, prevention)) {
            out += PreventionRules.MACHINE_CHECK_NEEDED
        }
        if (!trail.replayable) out += "④ 留证缺阶段：${trail.missingStages().joinToString("、")}"
        if (!PlainNote.mustSayCannotPay(verdict, note.lines)) {
            out += "④ 判了「${RecoveryVerdict.CANNOT_PAY_MARK}」却没在第一行直说"
        }
        return out
    }

    /** 四件齐不齐（判据口径：缺项＝未完成，不是扣分）。 */
    val complete: Boolean get() = defects().isEmpty()
}

/**
 * 判据①：「赔不了」必须配一张永久损失清单，清单必须三列齐。
 *
 * 为什么「清单为空」也算缺陷：判了赔不了却没有清单，等于告诉用户「东西没了」而不告诉他**没了什么**——
 * 那一句比不说更糟，因为他连该去哪儿再找一遍都不知道。
 */
private fun RecoveryVerdict.checkRecoverability(lossList: List<LossItem>, out: MutableList<String>) {
    if (this != RecoveryVerdict.UNRECOVERABLE) return
    if (lossList.isEmpty()) out += "① 判了「${RecoveryVerdict.CANNOT_PAY_MARK}」却没有永久损失清单"
    if (lossList.any { !it.isWellFormed() }) out += "① 永久损失清单里有半条（缺路径／大小／最后修改）"
}

/**
 * 防再发规矩（判据②的牙）。
 *
 * 分档写在代码里，不写在文档里：
 *   · 第一次 ⇒ 认一条**规矩**（总账/坑账里有编号即算）；
 *   · 第二次起 ⇒ 规矩不许还是纯文字，**必须有机检**（门禁脚本或测试路径）。
 * 为什么第二次要变严：同一个坑换了措辞再踩一遍，说明文字那条没挡住——
 * 这正是本仓制度总账 T-02 记的那类返工。
 */
internal object PreventionRules {

    /** 同型第二次起要求的「机检」缺失时说的话。 */
    const val MACHINE_CHECK_NEEDED: String = "同型第二次 ⇒ 防再发必须升级成机检（门禁脚本/测试路径），光有文字不算"

    /** ③：条目本身齐不齐（有没有、编号有没有）。**升级不够格**由 [sufficientAt] 判，两件事不混。 */
    fun checkEntry(p: Prevention?, out: MutableList<String>) {
        if (p == null) {
            out += "③ 没有防再发条目（同类错误必须进 ERRATA 或制度总账）"
            return
        }
        if (p.entryId.isBlank()) {
            out += "③ 防再发条目没编号（总账的 T-xx／坑账的 E-xxx）"
        }
    }

    /**
     * 这条防再发在「第 [occurrence] 次」这个位置上够不够格。
     *
     * 与 [ok] 分开是因为 [Compensation] 建好之后编号已经定了，而**要不要升级**取决于次数——
     * 这一句是给装配层「登记前先问一句够不够」用的。
     */
    fun sufficientAt(occurrence: Int, p: Prevention?): Boolean {
        if (p == null) return false
        if (occurrence <= 1) return p.entryId.isNotBlank()
        return p.isMachineChecked
    }
}

/** 赔付计划器：把事实折成成品，并且**不许在有路没试的时候喊赔不了**。 */
internal object CompensationPlanner {

    /**
     * 判这次损坏的还原档（①）。
     *
     * 「有路没试」这一档**不在这里出现**——它由 [refusalOf] 一票否决，走不到这一步。
     * 剩下四种按「还原回来多少」排：全回来＝已还原，部分＝部分还原，
     * 一条没回来但有清单＝赔不了；**走到 else 就是判不清**，按最坏的一档报人（fail-closed），
     * 由 [Compensation.defects] 用「没有清单」把它顶回去重做——不许拿「不知道」当「没事」。
     */
    fun verdictOf(f: DamageFacts): RecoveryVerdict = when {
        f.restoredPaths.isNotEmpty() && f.lostItems.isEmpty() -> RecoveryVerdict.RESTORED
        f.restoredPaths.isNotEmpty() -> RecoveryVerdict.PARTIAL
        else -> RecoveryVerdict.UNRECOVERABLE
    }

    /**
     * 这次赔付能不能开（null＝可以开）。
     *
     * 两条一票否决：
     *   · **有路没试**就想喊「赔不了」——④ 要的是「赔不了要**直说**」，不是「赔不了要**早说**」；
     *   · 既没还原回来、也没给出损失清单——那是不齐的事实，判出来的任何档都是猜。
     */
    fun refusalOf(f: DamageFacts): String? = when {
        f.opId.isBlank() -> "赔付缺 opId——不知道是哪一次动作，四件都没法定位到行"
        f.restoreRoutesKnown && !f.restoreRoutesTried ->
            "还原路存在却没试过（470 的通道/备份/回收站），先试再判「${RecoveryVerdict.CANNOT_PAY_MARK}」"
        f.restoredPaths.isEmpty() && f.lostItems.isEmpty() ->
            "事实不齐：既没还原本、也没损失清单（真没伤到东西就别开赔付）"
        else -> null
    }

    /**
     * 拼 ≤5 行白话说明（②）。
     *
     * 三栏各一行打底，最多再补两行。**这里不截断**：超行是缺陷，要由 [Compensation.defects] 说出来，
     * 悄悄砍掉第 6 行等于把「说超了」这件事一起砍掉——下次照样写超。
     */
    fun noteOf(verdict: RecoveryVerdict, f: DamageFacts): PlainNote {
        val head = if (verdict == RecoveryVerdict.UNRECOVERABLE) {
            "${RecoveryVerdict.CANNOT_PAY_MARK}：${f.whatHappened}"
        } else {
            "${verdict.cn}：${f.whatHappened}"
        }
        val lines = mutableListOf(head, "为什么：${f.why}", "你现在该做的：${f.nextStepForUser}")
        if (f.lostItems.isNotEmpty()) {
            lines += "永久损失 ${f.lostItems.size} 项，清单见赔付记录文件"
        }
        f.prevention?.let { lines += "防再发：${it.target.cn} ${it.entryId}" }
        return PlainNote(lines)
    }

    /** 一次赔付的全部四件（先过 [refusalOf]，被拒不产出成品）。 */
    fun plan(f: DamageFacts): Compensation? {
        if (refusalOf(f) != null) return null
        val verdict = verdictOf(f)
        return Compensation(
            opId = f.opId,
            tool = f.tool,
            verdict = verdict,
            note = noteOf(verdict, f),
            // 清单**跟着事实走**，不跟着档位走：部分还原也照样有捞不回来的那几项，
            // 按档位抹掉它就等于把「还缺什么」这句藏起来（§一·① 的原意是别藏）。
            lossList = f.lostItems,
            prevention = f.prevention,
            trail = f.trail,
            occurrence = f.sameTypeOccurrence,
        )
    }
}
