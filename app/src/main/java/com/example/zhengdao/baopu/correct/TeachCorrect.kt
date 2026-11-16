// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百二十四批 C（阶段三前段）· 纠正落成**单步编辑**（230-D 的「就地改单步」）。
//
// 模型在暂停报告＋用户那句纠正之后，调 teach_correct_apply 提出一个**单步编辑**；
// 工具权限＝CONFIRM（人批卡片就是「改脚本必须先确认」的那道闸，判据④前半句），
// 人批后才落 [TeachScriptHistory.applyCorrection]（备份＋留痕＋可回滚，判据④后半句）。
//
// 编辑闭集（231 切片 D 的四件，**没有**自由 JSON）：
//   setValue      换第 N 步的输入值（回放时填错/换单号——最常见纠正）
//   setLocator    换第 N 步的一档定位（kind+value，替换同档或追加）
//   setExpect     给第 N 步加一条校验点（urlChanged/rowCountChange 两种，观察证据同源）
//   deleteStep    删第 N 步（后续步号重排）
//   moveStep      第 N 步挪到第 toIndex 位（改顺序；其余步号重排）
// 拒收口径：step 越界／op 不在闭集／必填参数缺——**原样拒绝并说人话**，不猜。
// 重录在这里结构上不存在：编辑的是既有脚本的既有步，没有任何一条路会去启 TeachRecorder。
package com.example.zhengdao.baopu.correct

import com.example.zhengdao.baopu.TeachAction
import com.example.zhengdao.baopu.TeachExpect
import com.example.zhengdao.baopu.TeachExpectKind
import com.example.zhengdao.baopu.TeachLocator
import com.example.zhengdao.baopu.TeachLocatorKind
import com.example.zhengdao.baopu.TeachSkill
import java.io.File

/** 一次纠正编辑（工具入参的形状；op 是闭集，没有「其它」兜底）。 */
internal data class CorrectEdit(
    val name: String,
    val step: Int,
    val op: String,
    val value: String? = null, // setValue 的值／setLocator 的定位值／moveStep 忽略
    val locatorKind: String? = null, // setLocator 的定位档 wire（十级）
    val expect: String? = null, // setExpect 的校验点 wire
    val toIndex: Int? = null, // moveStep 的目标位（1 起）
    val reason: String, // 这句纠正的人话（进账行 reason）
) {
    companion object {
        /** 编辑闭集（判据⑤同款纪律：没有字符串动作名的「其它」兜底）。 */
        val OPS: Set<String> = setOf("setValue", "setLocator", "setExpect", "deleteStep", "moveStep")
    }
}

internal object TeachCorrect {

    /** 应用一条编辑：校验＋按 op 分发（每个 op 一个小函数，本函数不攒复杂度）。 */
    fun apply(draft: TeachSkill, edit: CorrectEdit): ApplyOutcome {
        if (edit.op !in CorrectEdit.OPS) {
            return ApplyOutcome.Refused("op「${edit.op}」不在编辑闭集（setValue/setLocator/setExpect/deleteStep/moveStep）")
        }
        draft.steps.firstOrNull { it.index == edit.step }
            ?: return ApplyOutcome.Refused("第 ${edit.step} 步不存在（这份技能共 ${draft.steps.size} 步）")
        val outcome = when (edit.op) {
            "setValue" -> applySetValue(draft.steps, edit)
            "setLocator" -> applySetLocator(draft.steps, edit)
            "setExpect" -> applySetExpect(draft.steps, edit)
            "deleteStep" -> StepEdit.Ok(renumber(draft.steps.filterNot { it.index == edit.step }))
            "moveStep" -> applyMoveStep(draft.steps, edit)
            else -> StepEdit.Bad("闭集已挡，穷尽性兜底")
        }
        return when (val r = outcome) {
            is StepEdit.Bad -> ApplyOutcome.Refused(r.reason)
            is StepEdit.Ok ->
                if (r.steps.isEmpty()) ApplyOutcome.Refused("编辑后没有步骤了——删光等于删技能，另教一份")
                else ApplyOutcome.Applied(draft.copy(steps = r.steps))
        }
    }

    /** 单步编辑的内部产物（新步骤序列，或一句人话拒收）。 */
    private sealed interface StepEdit {
        data class Ok(val steps: List<com.example.zhengdao.baopu.TeachStep>) : StepEdit
        data class Bad(val reason: String) : StepEdit
    }

    private fun applySetValue(
        steps: List<com.example.zhengdao.baopu.TeachStep>,
        edit: CorrectEdit,
    ): StepEdit {
        val v = edit.value?.takeIf { it.isNotBlank() }
            ?: return StepEdit.Bad("setValue 缺 value（要填成什么，一句话里得有）")
        return StepEdit.Ok(steps.map { if (it.index == edit.step) it.copy(value = v) else it })
    }

    private fun applySetLocator(
        steps: List<com.example.zhengdao.baopu.TeachStep>,
        edit: CorrectEdit,
    ): StepEdit {
        val kind = TeachLocatorKind.fromWire(edit.locatorKind ?: "")
            ?: return StepEdit.Bad("setLocator 缺合法的 locatorKind（十级定位 wire，如 roleName/xpath）")
        val v = edit.value?.takeIf { it.isNotBlank() }
            ?: return StepEdit.Bad("setLocator 缺 locatorValue")
        return StepEdit.Ok(
            steps.map {
                if (it.index != edit.step) it
                else {
                    val kept = it.locators.filterNot { l -> l.kind == kind }
                    it.copy(locators = (kept + TeachLocator(kind, v)).sortedBy { l -> l.tier })
                }
            },
        )
    }

    private fun applySetExpect(
        steps: List<com.example.zhengdao.baopu.TeachStep>,
        edit: CorrectEdit,
    ): StepEdit {
        val kind = TeachExpectKind.fromWire(edit.expect ?: "")
            ?: return StepEdit.Bad("setExpect 缺合法的 expect（urlChanged/rowCountChange/textAppears/elementGone）")
        return StepEdit.Ok(
            steps.map {
                if (it.index != edit.step) it
                else it.copy(expect = (it.expect + TeachExpect(kind, null)).distinctBy { e -> e.kind.wire })
            },
        )
    }

    /** 步号重排（1..n 连续；删步/挪步后必须重排）。 */
    private fun renumber(steps: List<com.example.zhengdao.baopu.TeachStep>): List<com.example.zhengdao.baopu.TeachStep> =
        steps.sortedBy { it.index }.mapIndexed { i, s -> s.copy(index = i + 1) }

    private fun applyMoveStep(
        steps: List<com.example.zhengdao.baopu.TeachStep>,
        edit: CorrectEdit,
    ): StepEdit {
        val to = edit.toIndex ?: return StepEdit.Bad("moveStep 缺 toIndex（挪到第几位）")
        if (to < 1 || to > steps.size) return StepEdit.Bad("toIndex=$to 越界（1–${steps.size}）")
        val ordered = steps.toMutableList()
        val moving = ordered.firstOrNull { it.index == edit.step } ?: return StepEdit.Bad("第 ${edit.step} 步不存在")
        ordered.remove(moving)
        ordered.add((to - 1).coerceIn(0, ordered.size), moving)
        return StepEdit.Ok(renumber(ordered)) // 挪步后步号重排连续（删步同款；测试揪出过这处遗漏）
    }

    /** 应用产物（闭集两态）。 */
    sealed interface ApplyOutcome {
        data class Applied(val skill: TeachSkill) : ApplyOutcome
        data class Refused(val reason: String) : ApplyOutcome
    }
}

/** 纠正编辑的落盘入口（CONFIRM 工具批后调；写盘细节在 [TeachScriptHistory]）。 */
internal object TeachCorrectApply {

    fun apply(
        projectRoot: File,
        globalRoot: File,
        personaFiles: com.example.zhengdao.baopu.PersonaFiles,
        name: String,
        edit: CorrectEdit,
        nowMs: Long,
    ): String {
        val current = com.example.zhengdao.baopu.TeachReplayStore.load(projectRoot, name)
            ?: return "没有这个回放技能：$name"
        return when (val o = TeachCorrect.apply(current, edit)) {
            is TeachCorrect.ApplyOutcome.Applied -> TeachScriptHistory.applyCorrection(
                projectRoot = projectRoot,
                globalRoot = globalRoot,
                personaFiles = personaFiles,
                name = name,
                edited = o.skill,
                reason = edit.reason,
                nowMs = nowMs,
            )
            is TeachCorrect.ApplyOutcome.Refused -> o.reason
        }
    }
}
