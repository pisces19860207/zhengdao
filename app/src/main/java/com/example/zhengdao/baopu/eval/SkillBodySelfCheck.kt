// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第八十四批 · 运行时提案自检 v2（派工单 §0 第 1 条）：**技能正文级**自检与回滚。
//
// v1 只覆盖「文本层提案」（人设 B/C 与技能索引）——技能正文本身写成功没成功、
// 写得像不像一个技能，v1 是不看的（v1 的 KDoc 里明说了这件事）。v2 补这一块。
//
// 四条判据（派工单 §0.1 逐条点名），全部**纯函数**，不碰 Context / IO / 文件：
//   1. **结构**：标题段 + 非空正文段——技能是给自己读的，没有标题就等于目录里一堆无主文本；
//   2. **必需段**：标题必须带技能名，正文不许太短（一句话不算技能）；
//   3. **禁用内容**：命中提权/覆盖系统提示类话术 ⇒ 拒绝（技能正文是喂给模型的，不是给人看的）；
//   4. **回滚可用性**：回滚走哪条路（备份恢复 / 删除新文件 / 没有路）必须**事前**判出来，
//      不能等回滚失败了才说「回不了」。
//
// 与 v1 的边界：本文件只管「刚写下去的这份正文该不该留」，**不**再跑一次 eval
// （那要调模型、烧额度、还要 key）。跑 eval 是 v1 的 `ProposalSelfCheck` 的事。
package com.example.zhengdao.baopu.eval

import com.example.zhengdao.rootfs.RunLog
import java.io.File

/** 回滚走哪条路。判据只有一条：备份在不在、文件是不是首写。 */
internal enum class RollbackVia {
    /** 有旧版备份 ⇒ `SkillStore.restore`（恢复本身也会先备份当前版，不覆盖）。 */
    RESTORE,

    /** 首写（落盘前文件不存在）⇒ 删掉新文件回到「没有」。 */
    DELETE,

    /** 既没有备份、又不是首写（文件在、但没有可恢复版本）⇒ 没有路。 */
    NONE,
}

/**
 * 一次正文检查的输入（调用方现读，别缓存）。
 *
 * @param backups `SkillStore.backupsOf(file).size`——有几份可恢复备份
 * @param existed 落盘前这个技能文件在不在（决定首写的回滚是删除还是恢复）
 */
internal data class SkillBodyInput(
    val name: String,
    val body: String,
    val backups: Int,
    val existed: Boolean,
)

/** 结论：过了；或者没过（附全部原因，不许只说第一条）。 */
internal sealed class SkillBodyVerdict {
    data object Ok : SkillBodyVerdict()
    data class Bad(val issues: List<String>) : SkillBodyVerdict()
}

/** 执行结果（三种用户可见状态；与 v1 的 `SelfCheckOutcome` 同构，便签渲染可复用）。 */
internal sealed class SkillBodyOutcome {
    /** 四条判据全过：正文保留。 */
    data object Passed : SkillBodyOutcome()

    /** 没过：已回滚且核过 sha256（首写 ⇒ 文件已删）。 */
    data class FailedRolledBack(val reason: String, val paths: List<String>) : SkillBodyOutcome()

    /** 没过且回滚没成功：**不许**说「已回滚」。 */
    data class RollbackFailed(val reason: String, val paths: List<String>) : SkillBodyOutcome()
}

/**
 * 正文自检的**纯判据层**。
 *
 * 为什么禁用话术表这么短：表越长假阳性越多（技能正文里出现「系统」二字太正常）。
 * 只收「自我提权」这一类——它的共同点是让模型**不再听原来的指令**，
 * 正常技能没有一个需要写这种话。
 */
internal object SkillBodySelfCheck {

    /** 正文下限：少于这么多字符不算一个技能（标题行都不止）。 */
    const val MIN_BODY_CHARS = 80

    /**
     * 禁用话术（**大小写不敏感**的原样包含，`contains(it, ignoreCase = true)`）。
     *
     * 只收"让模型摆脱系统提示/覆盖指令"这一类。反面例子故意不收：
     * 「系统」二字本身、`sudo`、`rm -rf`（那是技能要教的事，不是提权）。
     */
    val FORBIDDEN_PHRASES: List<String> = listOf(
        "忽略以上",
        "忽略上面的",
        "忽略之前的所有",
        "忽略系统提示",
        "无视系统提示",
        "覆盖系统提示",
        "绕过系统提示",
        "不再受系统提示",
        "ignore previous instructions",
        "ignore the system prompt",
        "disregard your instructions",
        "jailbreak",
    )

    /** 回滚走哪条路（事前判，回滚失败时不许现找理由）。 */
    fun rollbackVia(backups: Int, existed: Boolean): RollbackVia = when {
        backups > 0 -> RollbackVia.RESTORE
        !existed -> RollbackVia.DELETE
        else -> RollbackVia.NONE
    }

    /** 四条判据；返回空列表 = 过。**不**短路——一次把话说全，用户少改几轮。 */
    fun check(input: SkillBodyInput): SkillBodyVerdict {
        val issues = mutableListOf<String>()
        val body = input.body

        // ① 结构：标题段 + 非空正文段
        val lines = body.lines()
        val titleIdx = lines.indexOfFirst { it.isNotBlank() }
        val title = if (titleIdx >= 0) lines[titleIdx].trim() else ""
        if (!title.startsWith("#")) {
            issues += "缺标题段：正文第一行得是 `# 技能名`（目录里一堆无主文本，自己下次也读不懂）"
        } else {
            // ② 必需段：标题必须带技能名
            val inTitle = title.removePrefix("#").trim()
            if (input.name.isNotBlank() && !inTitle.contains(input.name)) {
                issues += "标题里没有技能名「${input.name}」（现在写的是「$inTitle」）"
            }
        }
        // 标题行**之后**的一切（去掉紧随的空行）都算正文。
        // 别用 `dropWhile { it.isNotBlank() }` 找正文起点——它会一直吃到第一个空行为止，
        // 把标题后紧跟的正文行一起吞掉（`# 技能名\n正文…` 会被误判成「只有标题」）。
        val rest = if (titleIdx >= 0) {
            lines.drop(titleIdx + 1).dropWhile { it.isBlank() }.joinToString("\n").trim()
        } else {
            ""
        }
        if (rest.isEmpty()) {
            issues += "正文只有标题、没有内容"
        } else if (rest.length < MIN_BODY_CHARS) {
            issues += "正文太短（${rest.length} 字符 < $MIN_BODY_CHARS）：一句话不算一个技能"
        }

        // ③ 禁用内容：自我提权类话术
        val hits = FORBIDDEN_PHRASES.filter { body.contains(it, ignoreCase = true) }
        if (hits.isNotEmpty()) {
            issues += "正文里有让模型摆脱系统提示的话（${hits.joinToString("、")}）——技能是喂给模型的，不许这么写"
        }

        // ④ 回滚可用性
        when (rollbackVia(input.backups, input.existed)) {
            RollbackVia.NONE ->
                issues += "这份正文没有可回滚的路（没有旧版备份、文件又不是首写）——先手动备份再写"
            else -> Unit
        }

        return if (issues.isEmpty()) SkillBodyVerdict.Ok else SkillBodyVerdict.Bad(issues)
    }
}

/**
 * 正文自检的执行壳（IO / 回滚在这层，判据在 [SkillBodySelfCheck]）。
 *
 * 与 v1 的分工：**不跑 eval**（那要 key、要烧钱、要 120s）。这里只做本地四条判据 +
 * 失败回滚，所以它可以同步跑、可以默认挂在「模型提案采纳」这条路上。
 *
 * 回滚复用 v1 的原语（`ProposalRestore` / `TargetSnapshot` / `Sha256`），
 * **不新增第二套备份/回滚通道**（派工单 §2 红线）。
 */
internal class SkillBodyGuard(
    private val sha256Of: (File) -> String? = { Sha256.of(it) },
    private val restoreOne: (TargetSnapshot, String) -> Boolean = { t, s -> ProposalRestore.restore(t, s) },
    private val stamp: () -> String = { com.example.zhengdao.util.TimeFormats.fileStamp() },
    private val log: (String) -> Unit = { RunLog.log(it) },
) {

    /**
     * @param target **落盘前**建好的快照（记当时的 sha256 与"文件在不在"）
     * @param input 现读的正文状态（正文内容 + 备份份数 + 落盘前在不在）
     */
    fun execute(scene: String, target: TargetSnapshot, input: SkillBodyInput): SkillBodyOutcome {
        val verdict = runCatching { SkillBodySelfCheck.check(input) }
            .getOrElse { SkillBodyVerdict.Bad(listOf("自检自己崩了：${it.javaClass.simpleName}")) }
        return when (verdict) {
            is SkillBodyVerdict.Ok -> {
                log("[skill-body-selfcheck] $scene 通过")
                SkillBodyOutcome.Passed
            }

            is SkillBodyVerdict.Bad -> {
                val reason = verdict.issues.joinToString("；")
                log("[skill-body-selfcheck] $scene 不过（$reason），回滚")
                rollback(scene, reason, target)
            }
        }
    }

    private fun rollback(scene: String, reason: String, target: TargetSnapshot): SkillBodyOutcome {
        val s = stamp()
        // 首写 ⇒ 删除即回到"没有"；直接删，别去 restore（恢复要备份，会把删除也存一份）
        val ok = runCatching {
            if (!target.existed) {
                !target.file.exists() || target.file.delete()
            } else {
                restoreOne(target, s)
            }
        }.getOrDefault(false)

        val good = runCatching {
            if (!target.existed) !target.file.exists() else sha256Of(target.file) == target.sha256
        }.getOrDefault(false)

        return if (ok && good) {
            log("[skill-body-selfcheck] $scene 已回滚，sha256 与落盘前一致（${target.file.name}）")
            SkillBodyOutcome.FailedRolledBack(reason, listOf(target.file.absolutePath))
        } else {
            log("[skill-body-selfcheck] $scene 回滚失败：${target.file.absolutePath}")
            SkillBodyOutcome.RollbackFailed(reason, listOf(target.file.absolutePath))
        }
    }
}
