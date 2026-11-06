// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百零八批 · 反思归因的**装配壳**（503 清点 §3.4 的「接上」）：
// 283 把判据（[ReflectionAttributor]）与消费端（`ExperienceKind.ATTRIBUTION` 的三个读数口：
// CriteriaPitPack 的配重／LearnContextNote 的标签／本包枚举）都造好了，但生产侧零调用——
// `LearnWiring.kt:66` 那条 `[ReflectAttribution]` 只是 KDoc 链接（且指向不存在的符号）。
// 本文件把收工链补上这一句：**只装配，不改判据**（判据与 IO 的分工在原文件头写着）。
//
// 装配壳只做两件判据之外的事：
// 1. **同一配对不重复入账**：attribute() 是"整本档案重判"的形状——每次调用把档案里**全部**
//    「提出→否决」配对重新落账（行 id 带时钟）。直接挂收工链＝每轮收工把旧账重记一遍。
//    装配壳在 append 口按「类＋机器键＋证据引用」去重：档案是 append-only，行号锚稳定 ⇒
//    同一对判出的三件恒等；已入账的行原样保留（append-only 不破坏），只是同一事实不记两遍。
// 2. **读数一行**：账上归因行按 [MisjudgeWhy.ofWire] 认回（认不出的键不冒充别的类），
//    收工日志出一句账面读数——「归因落一行可查」里"查"的那一面。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.core.RunLog
import java.io.File

internal object AttributionWiring {

    /** 归因行 key 的机器键前缀（`ReflectionMisjudgeJudge` 落账时的拼法，别处不许再抄一份）。 */
    private const val KEY_PREFIX = "attribution:"

    /**
     * 收工归因（挂在引擎收工反思同列；档案不存在／读失败 ⇒ 空结果，一个字节不写）。
     *
     * 判据里的两条纪律在这里原样生效：进化档案只读不写；缺证据的配对按 L17 只出日志。
     * 「本轮判得」是 attribute() 判过的行数（可能被下面的去重挡住不落账）；账面实有行数以
     * [readout] 读回的为准——两个数分得开，「同一配对不重记」才看得见。
     */
    fun onSettled(wsRoot: File, log: (String) -> Unit = { RunLog.log(it) }) {
        val result = ReflectionAttributor(wsRoot, DedupStore, log = log).attribute()
        log(
            "抱朴归因：本轮判得 ${result.persisted.size} 行、缺证据跳过 ${result.skipped.size} 行（L17）；" +
                readout(),
        )
    }

    /** 归因账的读数一行（[MisjudgeWhy.ofWire] 的生产调用点：机器键认回人话，认不出的单列，不冒充）。 */
    internal fun readout(rows: List<ExperienceRow> = JudgmentExperienceLedger.readAll()): String {
        val attr = rows.filter { it.kind == ExperienceKind.ATTRIBUTION }
        if (attr.isEmpty()) return "账上 0 行"
        val byWhy = attr.groupBy { MisjudgeWhy.ofWire(it.key.removePrefix(KEY_PREFIX)) }
        val detail = MisjudgeWhy.entries.mapNotNull { w ->
            byWhy[w]?.takeIf { it.isNotEmpty() }?.let { "${w.cn}×${it.size}" }
        }.joinToString("／")
        val unknown = attr.count { MisjudgeWhy.ofWire(it.key.removePrefix(KEY_PREFIX)) == null }
        val tail = if (unknown > 0) "；认不出 $unknown 行" else ""
        return "账上 ${attr.size} 行（$detail$tail）"
    }

    /**
     * 同一配对不重复入账的账口：按「kind＋类＋机器键＋证据引用」查重，命中即跳过
     * （首账原样在账上——append-only 的纪律归账本管，这里只挡重复记账）。
     * 跳过留一行日志：不声不响的挡 = 看不见的账。
     */
    private object DedupStore : JudgmentExperienceStore {
        override fun append(row: ExperienceRow) {
            val seen = JudgmentExperienceLedger.readAll().any {
                it.kind == row.kind && it.cardClass == row.cardClass &&
                    it.key == row.key && it.evidence == row.evidence
            }
            if (seen) {
                RunLog.log("抱朴归因：同一配对已在账，不重记（${row.cardClass}｜${row.key}）")
                return
            }
            JudgmentExperienceLedger.append(row)
        }

        override fun readAll(): List<ExperienceRow> = JudgmentExperienceLedger.readAll()
    }
}
