// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第十九批 §2.1（两层技能＋一句索引）与 §4 判据 K1/K2/K8。
// 房型照 `MemoryRules`（baopu/Memory.kt）：全部「算得清」的逻辑在这，壳只做读写。
package com.example.zhengdao.baopu

import java.io.File

/**
 * 技能沉淀（第十九批）的**纯判据**——两层路径、一句索引的渲染/解析/增删、总量上限。
 *
 * 三条铁律（派工单定稿，全部在这层钉死）：
 * 1. 两层落点：全局 `<App私有>/抱朴记忆/技能/<名字>/SKILL.md`（跟 App）、
 *    项目 `<工作区>/技能/<名字>/SKILL.md`（跟工作区）；**查找项目优先、全局兜底**；
 * 2. 索引一句一条写进人设（全局→B 层、项目→C 层），**两层合计上限 20 条**——
 *    第 21 条**拒绝并提示先删旧的**，不许靠截断兜底（截断是异常信号，不是常态入口）；
 * 3. 写盘只走 `PersonaFiles.applyProposal`（确认制）；本类不做任何 I/O 写入。
 */
internal object SkillRules {

    /** 技能目录名（工作区与抱朴记忆下同名）。 */
    const val SKILL_DIR = "技能"

    /** 技能正文文件名（约定式单文件，不引入新格式——母单红线 4）。 */
    const val SKILL_FILE = "SKILL.md"

    /** 索引行首标记（人设文本里按行识别）。 */
    const val INDEX_MARK = "【技能】"

    /** 索引总量上限（两层合计；第 21 条拒绝，派工单 §2.1）。 */
    const val INDEX_CAP = 20

    /** 全局技能目录：`<App私有>/抱朴记忆/技能/`。 */
    fun globalSkillDir(globalRoot: File): File = File(globalRoot, SKILL_DIR)

    /** 项目技能目录：`<工作区>/技能/`。 */
    fun projectSkillDir(projectRoot: File): File = File(projectRoot, SKILL_DIR)

    /** 一份技能正文的落点（含所属层）。 */
    data class SkillFile(val file: File, val scope: MemoryScope)

    /**
     * 两层查找（K1）：**项目优先、全局兜底**——同名时项目赢（用户定稿）。
     * 两边都没有 ⇒ null（调用方据此说「没有这个技能」）。
     */
    fun resolveSkillFile(projectRoot: File, globalRoot: File, name: String): SkillFile? {
        val p = File(File(projectRoot, SKILL_DIR), "$name/$SKILL_FILE")
        if (p.isFile) return SkillFile(p, MemoryScope.PROJECT)
        val g = File(File(globalRoot, SKILL_DIR), "$name/$SKILL_FILE")
        if (g.isFile) return SkillFile(g, MemoryScope.GLOBAL)
        return null
    }

    /**
     * 一句索引：技能名 ＋ 适用场景一句话。scope 决定它进哪层（全局→B、项目→C）。
     */
    data class IndexLine(val scope: MemoryScope, val name: String, val scenario: String)

    /**
     * 渲染一句索引（派工单 §2.1 原文房型）：
     * `【技能】整理书稿：遇到拆章／转编码／导出书稿类任务，先 read_file \`技能/整理书稿/SKILL.md\` 按它做。`
     */
    fun renderIndexLine(line: IndexLine): String =
        "$INDEX_MARK${line.name}：${line.scenario}，先 read_file `$SKILL_DIR/${line.name}/$SKILL_FILE` 按它做。"

    /**
     * 解析人设文本里的索引行（[scope] 由调用方按所在层给——文本本身不带层）。
     * 非索引行忽略；名字或场景为空的残行忽略（手改容错，不抛）。
     */
    fun parseIndexLines(text: String?, scope: MemoryScope): List<IndexLine> {
        if (text.isNullOrBlank()) return emptyList()
        val out = mutableListOf<IndexLine>()
        for (raw in text.lines()) {
            val s = raw.trim()
            if (!s.startsWith(INDEX_MARK)) continue
            val rest = s.removePrefix(INDEX_MARK)
            val idx = rest.indexOf('：')
            if (idx <= 0) continue
            val name = rest.substring(0, idx).trim()
            var scenario = rest.substring(idx + 1).trim()
            // 尾部固定的 read_file 提示是渲染时拼的，解析时剥掉（手改路径不采信）
            val tailIdx = scenario.indexOf("，先 read_file")
            if (tailIdx > 0) scenario = scenario.substring(0, tailIdx).trim().trimEnd('，', ',')
            if (name.isEmpty() || scenario.isEmpty()) continue
            out += IndexLine(scope, name, scenario)
        }
        return out
    }

    /** 两层合计条数（K2 的计数口径：全局 B 层＋项目 C 层一起算）。 */
    fun totalIndexCount(globalText: String?, projectText: String?): Int =
        parseIndexLines(globalText, MemoryScope.GLOBAL).size +
            parseIndexLines(projectText, MemoryScope.PROJECT).size

    /** 上限判定（K2 的靶子）：超了就是超了，**没有**「截断一点塞进去」这条路。 */
    sealed interface CapResult {
        data object Ok : CapResult
        data class Over(val total: Int, val cap: Int) : CapResult
    }

    fun capCheck(totalAfterAdd: Int, cap: Int = INDEX_CAP): CapResult =
        if (totalAfterAdd <= cap) CapResult.Ok else CapResult.Over(totalAfterAdd, cap)

    /** 增删结果：Ok 带新文本；OverCap 带账目；没有可动的行 ⇒ NotFound。 */
    sealed interface EditResult {
        data class Ok(val newText: String) : EditResult
        data class OverCap(val total: Int, val cap: Int) : EditResult
        data object NotFound : EditResult
    }

    /**
     * 往一层人设文本里加一句索引。
     *
     * - 同名已有 ⇒ **替换**该行（场景更新，updatedAt 由提案时间体现）；
     * - 场景与既有某条近似（Jaccard ≥ 0.9，照 `MemoryRules` 口径）⇒ 也算同名重复，替换；
     * - 其余 ⇒ 追加到末尾；
     * - [otherLayerCount] ＋ 本层条数（加完之后）超 [cap] ⇒ **OverCap，文本原样不动**。
     */
    fun addIndexLine(
        currentText: String?,
        line: IndexLine,
        otherLayerCount: Int = 0,
        cap: Int = INDEX_CAP,
    ): EditResult {
        val existing = parseIndexLines(currentText, line.scope)
        val nearDuplicate = existing.firstOrNull {
            it.name == line.name || MemoryRules.bodySimilarity(it.scenario, line.scenario) >= MemoryRules.DUPLICATE_SIMILARITY
        }
        val newTotal = otherLayerCount + if (nearDuplicate == null) existing.size + 1 else existing.size
        when (val capr = capCheck(newTotal, cap)) {
            is CapResult.Over -> return EditResult.OverCap(capr.total, capr.cap)
            is CapResult.Ok -> Unit
        }
        val rendered = renderIndexLine(line)
        val newText = if (nearDuplicate != null) {
            // 替换：同名/近似行原位更新（保持索引小节稳定，不抖动顺序）
            currentText.orEmpty().lines().joinToString("\n") { raw ->
                if (raw.trim().startsWith(INDEX_MARK) &&
                    raw.trim().removePrefix(INDEX_MARK).startsWith("${nearDuplicate.name}：")
                ) rendered else raw
            }
        } else {
            currentText.orEmpty().trimEnd('\n') + "\n" + rendered + "\n"
        }
        return EditResult.Ok(newText)
    }

    /**
     * 从一层人设文本里删掉一句索引（按名字）。行不在 ⇒ [EditResult.NotFound]。
     * **只动索引行**——技能正文文件不归这里管（删文件须用户在文件管理器自己来，§2.5）。
     */
    fun removeIndexLine(currentText: String, name: String): EditResult {
        val lines = currentText.lines()
        var removed = false
        val kept = lines.filter { raw ->
            val hit = !removed && raw.trim().startsWith(INDEX_MARK) &&
                raw.trim().removePrefix(INDEX_MARK).startsWith("$name：")
            if (hit) removed = true
            !hit
        }
        if (!removed) return EditResult.NotFound
        return EditResult.Ok(kept.joinToString("\n"))
    }
}
