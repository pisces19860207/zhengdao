// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百四十三批（2026-11-14）· 学习链第⑦环的**纯规则层**：教出来的脚本够不够格变成
// 一条技能候选（只判、不写盘、不落账、零时钟——同 233/255 的纯层纪律）。
//
// 派工单 §一·③ 把生成条件写死成三句，本文件逐句对应：
//   · **同版本成功回放 ≥3 次**——样本自带脚本版本（`AcceptanceSample.version`＝脚本规范
//     序列化的 sha256 前 12 位），只数**版本对得上且成功**的那几条；没标版本的旧样本不计
//     （判不了就不猜，255 的老口径）；
//   · **校验点全部命中**——回放器的 `ok` 只在「每步 expect 都满足＋终态校验点也满足」时为真
//     （TeachExecutor 收尾处唯一一处 `ok = true`），所以「成功一次」＝「这一次的校验点全命中」；
//     另外脚本本身不许有**没有 expect 的步骤**（那种步跑过了也证明不了什么＝参考校验跳过）；
//   · **不含不可逆动作**——词表与判定复用 622 的 `TeachScriptExchange.irreversibleFindings`
//     （与端口/示教层同一份 `PortIrreversible`），命中即「脚本不允许转正」。
//
// 边界（派工单 §三）：自进化的对象是「动作序列与校验点」，**不是判定逻辑**——本文件不读
// 档位、不判红线、不改任何闸；档位与红线归 568-A 的 `LowRiskGate`（[ScriptEvo] 只转手）。
package com.example.zhengdao.baopu.evolution.script

import com.example.zhengdao.baopu.AcceptanceSample
import com.example.zhengdao.baopu.TeachReplayStore
import com.example.zhengdao.baopu.TeachScriptExchange
import com.example.zhengdao.baopu.TeachSkill

/**
 * 一条脚本候选（派工单 §一·① 点名的五个字段，一个不多一个不少）。
 *
 * 它不是新造的候选类型：四域与技能域共用 `EvoCandidate`（568-A 的候选形状）＋既有技能写口；
 * 本类只是**脚本这一域的证据包**——进灰度时把 [line] 塞进 `EvoCandidate.body`，
 * 账行里就能看出「是哪个脚本、哪一版、跑成什么样」。
 *
 * @param scriptId 脚本身份＝`项目技能名`（本仓技能没有独立 id，名字就是键，85 批同一口径）
 * @param version 脚本版本＝规范序列化 sha256 前 12（622 的「技能未变 ⇒ 哈希不变」）
 * @param okReplays 同版本成功回放次数（≥[ScriptEvoRules.MIN_OK_REPLAYS] 才够格）
 * @param totalReplays 同版本回放总数（成功率的分子分母；含失败）
 * @param steps 步数
 * @param checkpoints 校验点数（每步 expect 数＋终态 expect 数）
 */
internal data class ScriptCandidate(
    val scriptId: String,
    val version: String,
    val okReplays: Int,
    val totalReplays: Int,
    val steps: Int,
    val checkpoints: Int,
) {
    /** 成功率（%）：没有样本时算 0（这里恒有 ≥3 条成功样本，走不到那支）。 */
    val successRatePct: Int get() = if (totalReplays <= 0) 0 else okReplays * 100 / totalReplays

    /** 候选 id（既有 `SkillAudit.record(candidateId=…)` 那一格的取值；同版本同值 ⇒ 幂等键）。 */
    val id: String get() = "script:$scriptId@$version"

    /** 一句话读数（人读／日志／候选账行的 reason 三处共用同一句）。 */
    fun line(): String =
        "脚本候选 $scriptId@$version：同版本成功 $okReplays/$totalReplays 次（$successRatePct%）" +
            " · $steps 步 · $checkpoints 个校验点"
}

/** 判定结论（**闭集三态**：够格／还没跑够／不许转正——"还没跑够"与"不许"不许混成一句）。 */
internal sealed interface ScriptCandidacy {

    /** 一句话读数（日志、提示、账行共用）。 */
    val note: String

    /** 够格：可以进灰度（进不进得去由 568-A 的门说，不在这层判）。 */
    data class Ready(val candidate: ScriptCandidate) : ScriptCandidacy {
        override val note: String get() = candidate.line()
    }

    /** 还没跑够：条件会随时间满足（同版本成功次数不够）。**不落账**（还没资格被判决）。 */
    data class NotYet(override val note: String) : ScriptCandidacy

    /** 不许转正：脚本本身不合格（含不可逆动作／有步骤没有校验点）——再跑多少次也不行。 */
    data class Refused(override val note: String) : ScriptCandidacy
}

/** 脚本候选的纯判据（零 IO：输入全是调用方读好的对象）。 */
internal object ScriptEvoRules {

    /** 生成候选的成功回放门槛（派工单 §一·③ 写死 3，不读配置、不读模型自述）。 */
    const val MIN_OK_REPLAYS = 3

    /** 「脚本不允许转正」的定稿说法（判据③ 断言到这四个字上，改字＝改判据）。 */
    const val NOT_PROMOTABLE = "脚本不允许转正"

    /** 脚本版本（规范序列化 sha256 前 12 位；与 622 的「技能未变 ⇒ 哈希不变」同源）。 */
    fun versionOf(skill: TeachSkill): String = TeachReplayStore.sha256Of(skill).take(12)

    /** 校验点数：每步 expect 数 ＋ 终态快照 expect 数（脚本层"证明得了"的量）。 */
    fun checkpointsOf(skill: TeachSkill): Int =
        skill.steps.sumOf { it.expect.size } + (skill.finalSnapshot?.expects?.size ?: 0)

    /**
     * 判一次（纯函数）。
     *
     * @param samples 该脚本的验收样本（`技能/<名>/验收.json` 里那些；不筛窗口——「同版本」
     *   已经由版本号管住，重教一次样本就清空，255 的 `onRelearned`）
     * @param version 当前盘上脚本的版本（[versionOf]，由调用方算一次传来，免得本函数读盘）
     */
    fun evaluate(skill: TeachSkill, samples: List<AcceptanceSample>, version: String): ScriptCandidacy {
        refusedOf(skill)?.let { return it }
        val sameVersion = samples.filter { it.version.isNotBlank() && it.version == version }
        val okRuns = sameVersion.count { it.ok && it.fingerprintOk }
        if (okRuns < MIN_OK_REPLAYS) {
            return ScriptCandidacy.NotYet(
                "同版本成功回放 $okRuns/$MIN_OK_REPLAYS 次（版本 $version" +
                    staleNoteOf(samples, sameVersion.size) + "）——再跑几次稳的才生成候选",
            )
        }
        return ScriptCandidacy.Ready(
            ScriptCandidate(
                scriptId = skill.name,
                version = version,
                okReplays = okRuns,
                totalReplays = sameVersion.size,
                steps = skill.steps.size,
                checkpoints = checkpointsOf(skill),
            ),
        )
    }

    /** 脚本本身不合格的两条（不可逆／有步骤没有校验点）——命中即「不许转正」，不问样本。 */
    private fun refusedOf(skill: TeachSkill): ScriptCandidacy.Refused? {
        val irreversible = TeachScriptExchange.irreversibleFindings(skill)
        if (irreversible.isNotEmpty()) {
            return ScriptCandidacy.Refused(
                "$NOT_PROMOTABLE：含不可逆动作（${irreversible.joinToString("；")}）——" +
                    "脚本层就拒，回放更不会执行到它",
            )
        }
        val blank = skill.steps.filter { it.expect.isEmpty() }.map { it.index }
        if (blank.isNotEmpty()) {
            return ScriptCandidacy.Refused(
                "$NOT_PROMOTABLE：第 ${blank.joinToString("、")} 步没有校验点" +
                    "（参考校验跳过不算命中）——补上断言再教一遍",
            )
        }
        return null
    }

    /** 没标版本／别的版本的样本有多少条（不许静默丢掉：读数里要能看见"没算它们"）。 */
    private fun staleNoteOf(samples: List<AcceptanceSample>, sameVersion: Int): String {
        val other = samples.size - sameVersion
        return if (other > 0) "；另有 $other 条旧版本/没标版本的样本不计" else ""
    }
}
