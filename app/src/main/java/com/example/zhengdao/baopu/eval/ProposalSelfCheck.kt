// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第五十批 · 运行时提案自检 v1（2026-10-19）：**落盘后的核对与回滚**。
//
// 分工：
//   `RuntimeEval`（Context 侧）负责「怎么跑」；
//   本文件负责「跑出来的结果怎么判、判坏了怎么退回去」——纯逻辑 + 闭包注入，
//   JVM 里用假 eval 与临时目录直测，不联网、不烧额度、不碰用户真实人设。
//
// 三条不许含糊的纪律（派工单 §2.4–§2.5）：
//   1. **红线是路径级的**：App 里没有 git，不假装跑 `git diff`——只核对「这次要改的文件」
//      是不是落在允许集合里、有没有踩 L2.5 的禁改前缀；踩了先回滚再报。
//   2. **回滚要逐文件核 sha256**：备份原语返回「成功」不算数，必须核到与落盘前一致；
//      核不上就说「回滚失败」并给路径，**不许**说「已回滚」。
//   3. **单飞**：同一时刻只跑一个自检；第二次进来直接回 Busy，不并行、不互相回滚。
package com.example.zhengdao.baopu.eval

import com.example.zhengdao.baopu.PersonaBackup
import com.example.zhengdao.baopu.PersonaBackupResult
import com.example.zhengdao.baopu.SkillStore
import com.example.zhengdao.baopu.SkillWriteResult
import com.example.zhengdao.rootfs.RunLog
import com.example.zhengdao.util.TimeFormats
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** 这次要被改的文件属于哪一类（决定回滚走哪个备份原语）。 */
internal enum class TargetKind { PERSONA, SKILL }

/**
 * 落盘前的快照：路径 + 当时的 sha256 + 当时存不存在。
 *
 * 「当时存不存在」是必需的：首次写入没有备份可恢复，正确回滚是**删掉新文件**，
 * 而不是拿一份更老的备份盖上去（那会把用户早就有的内容换成几天前的版本）。
 */
internal data class TargetSnapshot(
    val file: File,
    val kind: TargetKind,
    val sha256: String?,
    val existed: Boolean,
) {
    companion object {
        fun of(file: File, kind: TargetKind): TargetSnapshot =
            TargetSnapshot(file, kind, Sha256.of(file), file.isFile)
    }
}

internal object Sha256 {
    fun of(file: File): String? {
        if (!file.isFile) return null
        return runCatching {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull()
    }
}

/**
 * 红线：L2.5 的禁改前缀（派工单 §2.4 逐条点名）。
 *
 * 判据是**工作区相对路径的前缀**，不是「字符串里含 eval/」——`assets/eval/…` 不该误伤。
 */
internal object ProposalRedline {

    val FORBIDDEN_PREFIXES: List<String> = listOf(
        "terminal/",
        "rootfs/",
        "browser/",
        "baopu/agent/",
        "eval/",
        "清单/",
    )

    private fun canon(f: File): File = runCatching { f.canonicalFile }.getOrDefault(f.absoluteFile)

    private fun relOf(file: File, workspace: File): String? =
        runCatching { canon(file).relativeTo(canon(workspace)).invariantSeparatorsPath }.getOrNull()

    /**
     * @param allowedFiles 允许改的**具体文件**（人设 B/C —— B 在 App 私有目录、不在工作区里）
     * @param allowedDirs 允许改的**目录**（技能目录：正文与备份都在里面）
     */
    fun violationsOf(
        target: File,
        workspace: File,
        allowedFiles: List<File>,
        allowedDirs: List<File>,
    ): List<String> {
        val t = canon(target)
        val out = mutableListOf<String>()

        // ① 禁改前缀优先判（哪怕它落在允许目录里，也先按红线处理）
        val rel = relOf(target, workspace)
        if (rel != null) {
            val hit = FORBIDDEN_PREFIXES.firstOrNull { rel.startsWith(it) }
            if (hit != null) out += "红线：$rel 命中禁改前缀「$hit」"
        }

        // ② 必须在允许集合里
        val inFiles = allowedFiles.any { canon(it) == t }
        val inDirs = allowedDirs.any { dir ->
            val d = canon(dir)
            runCatching { t.startsWith(d) }.getOrDefault(false)
        }
        if (!inFiles && !inDirs) out += "红线：${target.absolutePath} 不在允许改的集合里（人设 B/C 或技能目录）"
        return out
    }
}

/** 回滚：每个快照走它那一类的既有备份原语；首次写入的文件靠删除回到「没有」。 */
internal object ProposalRestore {

    fun restore(target: TargetSnapshot, stamp: String): Boolean {
        val f = target.file
        if (!target.existed) {
            return runCatching { !f.exists() || f.delete() }.getOrDefault(false)
        }
        return when (target.kind) {
            TargetKind.PERSONA -> PersonaBackup.restore(f, stamp) is PersonaBackupResult.Done
            TargetKind.SKILL -> SkillStore.restore(f, stamp) is SkillWriteResult.Written
        }
    }
}

/** 自检结果（四种用户可见状态 + 单飞占位）。 */
internal sealed class SelfCheckOutcome {
    /** 全过：文件保留。 */
    data class Passed(val passed: Int, val total: Int, val elapsedMs: Long, val yuan: String) : SelfCheckOutcome()

    /** 没过（含红线命中）：已回滚且逐文件核过 sha256。 */
    data class FailedRolledBack(val reason: String, val paths: List<String>) : SelfCheckOutcome()

    /** 没过且回滚也没成功：**不许**说「已回滚」，把路径摊给用户。 */
    data class RollbackFailed(val reason: String, val paths: List<String>) : SelfCheckOutcome()

    /** 跑不了（无通道/无 key/用例读不到）：文件保留，既不算通过也不回滚。 */
    data class NotRun(val reason: String) : SelfCheckOutcome()

    /** 已经有一个在跑：本次不跑、不动文件。 */
    data object Busy : SelfCheckOutcome()
}

/** 结果 → 用户看到的那一句话（四种状态各有固定口径，测试钉住关键词）。 */
internal object SelfCheckText {

    fun of(o: SelfCheckOutcome): String? = when (o) {
        is SelfCheckOutcome.Passed ->
            "自检 ${o.passed}/${o.total} 通过（${o.elapsedMs / 1000}s，约 ¥${o.yuan}）"

        is SelfCheckOutcome.FailedRolledBack ->
            "自检失败（${o.reason}），已回滚（sha256 与落盘前一致）"

        is SelfCheckOutcome.RollbackFailed ->
            "自检失败（${o.reason}），回滚也没成功：${o.paths.joinToString("、")}——这几处可能还是新版，请手动核对"

        is SelfCheckOutcome.NotRun -> "自检未跑（${o.reason}）"

        // 单飞：已经有一句「自检中…」在屏上了，不再覆盖
        SelfCheckOutcome.Busy -> null
    }
}

/** `runEval` 的返回：跑了，或者没跑（要带原因，别拿 null 糊过去）。 */
internal sealed class EvalAttempt {
    data class Ran(val run: EvalRun) : EvalAttempt()
    data class Skip(val reason: String) : EvalAttempt()
}

/**
 * 落盘后的自检与回滚。
 *
 * @param runEval 真跑 eval（测试注入假实现）
 * @param restoreOne 单文件回滚（默认走既有备份原语）
 * @param sha256Of 现算摘要（核回滚是否真的一致性）
 */
internal class ProposalSelfCheck(
    private val runEval: () -> EvalAttempt,
    private val restoreOne: (TargetSnapshot, String) -> Boolean = { t, s -> ProposalRestore.restore(t, s) },
    private val sha256Of: (File) -> String? = { Sha256.of(it) },
    private val stamp: () -> String = { TimeFormats.fileStamp() },
    private val log: (String) -> Unit = { RunLog.log(it) },
) {

    /** 单飞闸：第二个并发调用直接 Busy（不清文件、不跑 eval）。 */
    private val running = AtomicBoolean(false)

    fun execute(
        scene: String,
        targets: List<TargetSnapshot>,
        redline: List<String> = emptyList(),
    ): SelfCheckOutcome {
        if (!running.compareAndSet(false, true)) return SelfCheckOutcome.Busy
        return try {
            if (redline.isNotEmpty()) {
                val reason = "红线命中：${redline.joinToString("；")}"
                log("[proposal-selfcheck] $scene 红线命中，先回滚：$reason")
                rollback(scene, reason, targets)
            } else {
                when (val a = runCatching { runEval() }.getOrElse {
                    EvalAttempt.Skip("自检自己崩了：${it.javaClass.simpleName}")
                }) {
                    is EvalAttempt.Skip -> {
                        log("[proposal-selfcheck] $scene 未跑：${a.reason}（文件保留，不回滚）")
                        SelfCheckOutcome.NotRun(a.reason)
                    }

                    is EvalAttempt.Ran -> {
                        val r = a.run
                        if (r.ok) {
                            log("[proposal-selfcheck] $scene 通过：${r.passed}/${r.total}，${r.elapsedMs}ms")
                            SelfCheckOutcome.Passed(
                                passed = r.passed,
                                total = r.total,
                                elapsedMs = r.elapsedMs,
                                yuan = EvalCost.yuan(r.tokensIn, r.tokensOut),
                            )
                        } else {
                            val why = "${r.passed}/${r.total} 通过，挂：${r.failedIds()}"
                            log("[proposal-selfcheck] $scene 失败，回滚：$why")
                            rollback(scene, why, targets)
                        }
                    }
                }
            }
        } finally {
            running.set(false)
        }
    }

    /** 回滚 → 逐文件核 sha256 → 结论。任一文件恢复失败或核对不上 ⇒ [SelfCheckOutcome.RollbackFailed]。 */
    private fun rollback(scene: String, reason: String, targets: List<TargetSnapshot>): SelfCheckOutcome {
        val s = stamp()
        val failed = mutableListOf<String>()
        for (t in targets) {
            val ok = runCatching { restoreOne(t, s) }.getOrDefault(false)
            if (!ok) failed += t.file.absolutePath
        }
        val mismatched = targets.filterNot { matches(it) }.map { it.file.absolutePath }
        val bad = (failed + mismatched).distinct()
        return if (bad.isEmpty()) {
            log("[proposal-selfcheck] $scene 已回滚，${targets.size} 个文件 sha256 与落盘前一致")
            SelfCheckOutcome.FailedRolledBack(reason, targets.map { it.file.absolutePath })
        } else {
            log("[proposal-selfcheck] $scene 回滚失败：${bad.joinToString("、")}")
            SelfCheckOutcome.RollbackFailed(reason, bad)
        }
    }

    private fun matches(t: TargetSnapshot): Boolean =
        if (!t.existed) !t.file.exists() else sha256Of(t.file) == t.sha256
}
