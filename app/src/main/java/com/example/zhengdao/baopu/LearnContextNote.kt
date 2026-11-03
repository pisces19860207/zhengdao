// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百零五批 · 模型上下文读经验（400 的留白半）：400 把「最近同类经验」拼进了御案卡
// 证据区（卡面随卡走），模型那一边的 prompt 一直读不到经验——派工单 405 原话：
// 「RunEngine 拼上下文处读最近同类经验，只加一行『上次同类：<做法> ⇒ <结果>』（≤2 行）」。
//
// 三条铁律（派工单 405 原文，判据全在本文件＋测试里）：
//   1. **无经验一个字不加**：账上没有合格经验 ⇒ 返回 null ⇒ extraNotes 一个字节不增，
//      prompt 与接线前逐字一致（LearnContextNoteTest 判据②）；
//   2. **读不许改三态**：对判决经验账只读（IO 壳只调 [LearnRead.rows]＝replay，一个写口
//      都没有，`LearnContextNoteGateTest` G1 源级扫）；右相验收三态（PASS/FAIL/UNVERIFIABLE）
//      与本文件**零接触**——这一行只进 prompt 文本，判卷的输入与判据一个字不改（G2）；
//   3. **样本 <5 不生效**：某类的窗内信号行不足 [LearnReadJudge.MIN_SAMPLES] 条 ⇒ 该类当
//      "没学过"——直接复用 400 的 [LearnReadJudge.recent]（同一把尺，不抄第二份，G4）。
//
// 「同类」的选法：账上**最近一个合格类**——类按最近出场排（[LearnWiring.replay] 是新→旧），
// 取第一个 ①窗内信号过地板（[LearnReadJudge.recent] 非空）且 ②账上出现过 ≥2 次
// （判据「同类型第 2 次出现该行」——第 1 次出现没有"上次"可说；地板 ≥5 条信号本就蕴含它，
// 显式写出是让判据可读、且将来地板尺子若改这里仍守得住）的类；行取该类最新一行
// （种类不限——signal/ask/suppress/attribution 的理由都算经验，400 读口③同一口径）。
//
// 房型照 400/283：判据（[LearnContextNoteJudge]，纯函数零 IO 零时钟）与 IO 壳
// （[LearnContextNote]）分离；引擎在轮开跑调一次 [LearnContextNote.roundNote]，
// 工具循环每步同一份（前缀判据），续跑不带（同 turnImages/kbNote 口径）。
package com.example.zhengdao.baopu

import com.example.zhengdao.baopu.agent.ExperienceKind
import com.example.zhengdao.baopu.agent.ExperienceRow

/** 上下文经验行的判据（纯函数：零 IO、零时钟——行与 `now` 都由调用方给）。 */
internal object LearnContextNoteJudge {

    /** 一行的长度上限（≤2 行＝单行不折行；超长截断——AuditHint.fit 同一手法）。 */
    const val MAX_CHARS = 160

    /**
     * 最近同类经验那一行；null ＝ 账上没有合格经验（**一个字不加**）。
     *
     * @param rows 判决经验账全本（新→旧，即 [LearnWiring.replay] 的顺序）
     * @param now 地板窗口的对照时刻（调用方给；纯函数不读时钟）
     */
    fun line(rows: List<ExperienceRow>, now: Long): String? {
        for (cls in rows.map { it.cardClass }.distinct()) {
            if (rows.count { it.cardClass == cls } < 2) continue
            val row = LearnReadJudge.recent(rows, cls, 1, now).firstOrNull() ?: continue
            return lineOf(row)
        }
        return null
    }

    /** 一行的拼法：`上次同类：<类>·<做法> ⇒ <结果>`——结果就是账上那句理由（依据随行可查）。 */
    private fun lineOf(row: ExperienceRow): String {
        val raw = "上次同类：${row.cardClass}·${actionOf(row)} ⇒ ${row.reason}"
        val one = raw.replace(Regex("""\s+"""), " ").trim()
        return if (one.length <= MAX_CHARS) one else one.take(MAX_CHARS - 1) + "…"
    }

    /** 「做法」那一格（动作短语；种类不限，理由列本身作「结果」）。 */
    private fun actionOf(row: ExperienceRow): String = when (row.kind) {
        ExperienceKind.SIGNAL -> when (row.key) {
            "signal:approve" -> "弹卡问过，用户批了"
            "signal:reject" -> "弹卡问过，用户驳了"
            "signal:defer" -> "弹卡问过，用户搁置"
            else -> "弹卡问过，用户关掉"
        }

        ExperienceKind.ASK -> "弹卡问过"
        ExperienceKind.SUPPRESS -> "学习判了不打扰"
        ExperienceKind.REASK -> "压下后用户重问"
        ExperienceKind.VIOLATION -> "学习想压、撞法条作废"
        ExperienceKind.DRAFT_VETO -> "学到的结论被法典判据否掉"
        ExperienceKind.ATTRIBUTION -> "反思归因"
    }
}

/** IO 壳（只调 [LearnRead.rows] 读全本，一个写口都没有；读账失败当没经验——账是留痕不是闸门）。 */
internal object LearnContextNote {

    /**
     * 轮开跑算一次（工具循环每步同一份——前缀判据）。
     *
     * @param resumed 是否续跑：续跑 ⇒ null（原轮已注过的那份不重算——中途换批＝打破前缀，
     *   与 turnImages／知识库自动注入「续跑不带」同一口径）
     */
    fun roundNote(resumed: Boolean, now: Long = System.currentTimeMillis()): String? {
        if (resumed) return null
        return runCatching { LearnContextNoteJudge.line(LearnRead.rows(), now) }.getOrNull()
    }
}
