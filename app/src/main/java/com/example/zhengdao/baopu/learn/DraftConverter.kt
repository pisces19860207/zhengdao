// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百二十四批 ⑥（阶段二）· 草案 → zl-teach/2 技能（接 643 候选入口的唯一桥）。
//
// 「阶段二产物能进 643 的候选入口」＝本类把草案落成 `TeachSkill`（zl-teach/2）之后，
// 643 那条链原样能吃：`ScriptEvoRules.versionOf`／`checkpointsOf`／`evaluate` 直接可用，
// 五字段（脚本 id／版本／成功率＝回放样本／步数／校验点）全部可算——本类不碰灰度账，
// 候选行的写入仍是 643 的 `ScriptEvo`（人批回放 ≥3 次成功那条链，一字未改）。
//
// 四条硬边界：
//   · **冲突草案不转**：有候选并存待裁的步 ⇒ 拒（231「不擅自选」，阶段二没有代人选的授权）；
//     672 起这条边界有了出口：判据本体在 [SkillDraftRules.checkForPromotion]——人裁完
//     （A／B 各一套上位）才放行，裁 none ⇒ 拒并说「回重新教这一步」；转换器**不自己选**；
//   · **定位不足两条不转**：TeachStep 要求每步 ≥2 档定位（622 学习器保证）；
//   · **参数步不带字面量**：v2 口径步骤里只有 `${name}` 占位，参数默认值由**人批确认时**
//     填进 [TeachParam.default]（观察数据里没有值，草案里也不造值——判据④的结构保证）；
//   · **指纹留空如实**：观察面没有起点页的关键表头/按钮集合，指纹锚为空——回放的
//     fingerprintOk 判据怎么办是 643/255 链的事，转换层不造假锚。
package com.example.zhengdao.baopu.learn

import com.example.zhengdao.baopu.TeachAction
import com.example.zhengdao.baopu.TeachElementSnapshot
import com.example.zhengdao.baopu.TeachExpect
import com.example.zhengdao.baopu.TeachExpectKind
import com.example.zhengdao.baopu.TeachFingerprint
import com.example.zhengdao.baopu.TeachLocator
import com.example.zhengdao.baopu.TeachLocatorKind
import com.example.zhengdao.baopu.TeachParam
import com.example.zhengdao.baopu.TeachParamShape
import com.example.zhengdao.baopu.TeachParamSource
import com.example.zhengdao.baopu.TeachSkill
import com.example.zhengdao.baopu.TeachSlot
import com.example.zhengdao.baopu.TeachSlotField
import com.example.zhengdao.baopu.TeachStep

internal object DraftConverter {

    /** 转换产物（或拒绝原因——闭集两态）。 */
    sealed interface Outcome {
        data class Skill(val skill: TeachSkill) : Outcome
        data class Refused(val reason: String) : Outcome
    }

    /**
     * 草案 → zl-teach/2。[paramDefaults] 是**人批确认时**填的参数默认值（键＝参数名；
     * 没填的参数 default 为 null——执行端参数替换链照常工作，空值跑不了在回放侧如实失败，
     * 转换层不造假值）。
     *
     * [adjudications] 是人裁记录（672）：冲突步未裁／裁 none ⇒ 拒，理由照
     * [SkillDraftRules.checkForPromotion] 的原话（「有冲突未裁」「回重新教这一步」）；
     * 裁了 A／B ⇒ 先把选定那套落到主位再转。**本层没有任何代选逻辑**。
     */
    fun toSkill(
        draft: SkillDraft,
        name: String,
        paramDefaults: Map<String, String>,
        nowMs: Long,
        adjudications: List<DraftAdjudication> = emptyList(),
    ): Outcome {
        val resolved = when (val applied = DraftAdjudications.apply(draft, adjudications)) {
            is DraftAdjudications.Applied.Blocked -> return Outcome.Refused(applied.problems.joinToString("；"))
            is DraftAdjudications.Applied.Ready -> applied.draft
        }
        if (name.isBlank()) return Outcome.Refused("技能名不能为空")
        if (resolved.skeleton.isEmpty()) return Outcome.Refused("草案没有可回放的步骤")
        val steps = ArrayList<TeachStep>(resolved.skeleton.size)
        val paramSteps = HashMap<String, MutableList<Int>>()
        resolved.skeleton.forEach { step ->
            val action = TeachAction.fromWire(step.kind) ?: return Outcome.Refused("第 ${step.index} 步动作「${step.kind}」不可回放")
            val locators = tiersOf(step.locator)
            if (locators.size < 2) {
                return Outcome.Refused(
                    "第 ${step.index} 步定位不足两条（${locators.size}/2）——观察身份面不够定位，回放无法自愈",
                )
            }
            val value = step.param?.takeIf { action == TeachAction.TYPE || action == TeachAction.SELECT }
                ?.let { "\${$it}" } // v2 口径：参数步只有 ${name} 占位，字面量在结构上不存在
            if (step.param != null) paramSteps.getOrPut(step.param) { mutableListOf() }.add(step.index)
            steps += TeachStep(
                index = step.index,
                action = action,
                url = step.url,
                locators = locators,
                value = value,
                snapshot = TeachElementSnapshot(
                    tag = step.locator.tag,
                    role = step.locator.role.takeIf { it.isNotEmpty() },
                    name = step.locator.name.takeIf { it.isNotEmpty() },
                    text = null, // 观察面没有正文（阶段一红线），快照不给 text
                    inputType = step.locator.inputType.takeIf { it.isNotEmpty() },
                ),
                expect = step.expect.mapNotNull { w ->
                    TeachExpectKind.fromWire(w)?.let { kind -> TeachExpect(kind, null) }
                },
            )
        }
        val skill = TeachSkill(
            name = name,
            purpose = resolved.goal,
            createdAtMs = nowMs,
            startUrl = steps.first().url,
            allowedDomains = listOf(resolved.targetSite).filter { it.isNotBlank() },
            clickTagWhitelist = emptyList(),
            fingerprint = TeachFingerprint(emptyList()),
            steps = steps,
            params = resolved.params.map { p ->
                TeachParam(
                    name = p.name,
                    label = p.name,
                    source = TeachParamSource.INPUT,
                    shape = TeachParamShape.CODE,
                    slots = (paramSteps[p.name] ?: emptyList()).map { TeachSlot(it, TeachSlotField.VALUE) },
                    default = paramDefaults[p.name],
                )
            },
            targetSite = resolved.targetSite,
            finalSnapshot = null,
        )
        return Outcome.Skill(skill)
    }

    /**
     * 身份面 → 两档以上定位器（622 十级定位的草案侧半边；按 tier 升序）。
     * ROLE_NAME 的主判别值是 `role|name`（与录制端学习器同格式）。
     */
    private fun tiersOf(f: DraftLocator): List<TeachLocator> {
        val out = ArrayList<TeachLocator>(4)
        if (f.role.isNotEmpty() && f.name.isNotEmpty()) {
            out += TeachLocator(TeachLocatorKind.ROLE_NAME, f.role + "|" + f.name)
        }
        if (f.testid.isNotEmpty()) out += TeachLocator(TeachLocatorKind.TESTID, f.testid)
        if (f.aria.isNotEmpty()) out += TeachLocator(TeachLocatorKind.ARIA_LABEL, f.aria)
        if (f.id.isNotEmpty()) out += TeachLocator(TeachLocatorKind.ELEM_ID, f.id)
        if (f.inputName.isNotEmpty()) out += TeachLocator(TeachLocatorKind.INPUT_NAME, f.inputName)
        if (f.placeholder.isNotEmpty()) out += TeachLocator(TeachLocatorKind.PLACEHOLDER, f.placeholder)
        if (f.xp.isNotEmpty()) out += TeachLocator(TeachLocatorKind.XPATH, f.xp)
        return out.sortedBy { it.tier }
    }
}
