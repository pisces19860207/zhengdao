// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百八十四批 · 上半 ①：把 340 的「数对不对」对账闸（[ValueCheck]）挂进收尾链。
//
// 接线前的事实（418 复核 ＋ 373 名册，两条独立读到同一结论）：
// 收尾闸 [ArtifactVerifier] 只验「有没有做」（存在／非空／合法 UTF-8／JSON 可解析），
// 而「它说的数对不对」这道闸（[ValueCheck]，340 交付）**零生产调用方**——只有测试在跑。
//
// 本文件只做**搬运**，不做判定（派工单纪律②：ValueCheck 的规则一个字不动）：
//   · 「数对不对」由 [ValueCheck.verifyAnswer] 判（本文件不重算、不比对、不改判）
//   · 「这一轮有没有数字」也由它判（`claimsIn` 空 ⇒ 它自己返回空表，本文件据此跳过）
//   · 本文件只把 `FAILED` 搬成收尾闸认的形状（[Finding]），让引擎既有的
//     「给一次按清单修」机会对数字错也生效（派工单纪律④：FAIL 不许私自降级）
//
// 三条口径（都有测试，见 ValueGateWiringTest）：
//   ① 数字错 ⇒ FAIL，且 detail 逐字保留 340 硬规矩④ 的「我说 X、实际 Y，差 D」；
//   ② 非数字终答（文本／图片／代码，没有算术自称）⇒ 不产 finding、只记一行「跳过」——**不许误报**；
//   ③ UNVERIFIABLE 不进 findings：口径立不住（裸完成率／来源不唯一）时逼模型改口径＝改判定，
//      那是 340 判据层的事；本层只把它记进读数行，不当成「未通过」去触发修复回合。
package com.example.zhengdao.baopu.verify

import com.example.zhengdao.core.RunLog
import java.io.File

object ValueGateWiring {

    /** findings 的 check 名。用**人话**：它会被拼进「自检未通过」清单给人看也给模型看。 */
    const val CHECK_VALUE = "值级对账"

    /** 一次值级对账的读数（results 逐条来自 340，本文件不改字段）。 */
    data class Verdict(val results: List<ValueCheck.Result>) {

        /** 对不上（要修）。 */
        val failed: List<ValueCheck.Result> = results.filter { it.status == ValueCheck.Status.FAILED }

        /** 口径立不住（不修，只记）。 */
        val unverifiable: List<ValueCheck.Result> =
            results.filter { it.status == ValueCheck.Status.UNVERIFIABLE }

        /** 这一轮到底跑没跑（没有算术自称 ⇒ false）。 */
        val ran: Boolean get() = results.isNotEmpty()
    }

    /** 只算不落：拿终答原文 + 工作区，问 340 要逐条结论。 */
    fun verdict(answerText: String, workspace: File, exclude: Set<String>): Verdict =
        Verdict(ValueCheck.verifyAnswer(answerText, workspace, exclude))

    /**
     * 引擎收尾链的入口：算一次 → 记一行读数 → 把 FAIL 搬成 findings。
     *
     * 为什么把「记一行」放在这里而不是引擎里：引擎那边的改动上限是 5 行（384 派工单纪律），
     * 而「跳过／通过／未过」三种读数都该留痕——留在本层，引擎只管接一条线。
     */
    fun findingsOf(answerText: String, workspace: File, exclude: Set<String>): List<Finding> {
        val v = verdict(answerText, workspace, exclude)
        note(line(v))
        return v.failed.map { r ->
            Finding(
                path = r.source.ifBlank { "（无来源）" },
                check = CHECK_VALUE,
                // detail 逐字来自 340：不给它换文案、不替它把 X 改成 Y（硬规矩④）
                detail = r.detail,
            )
        }
    }

    /** 一行读数（恒一行，没东西也照印——照 [QuietCardGate.stateLine] 的规矩）。 */
    fun line(v: Verdict): String {
        if (!v.ran) return "值级对账：本轮终答没有算术自称（非数字产物）⇒ 跳过"
        val verified = v.results.size - v.failed.size - v.unverifiable.size
        val head = "值级对账：核 ${v.results.size} 条（对 $verified／未过 ${v.failed.size}／判不了 ${v.unverifiable.size}）"
        val detail = v.failed.firstOrNull()?.detail
            ?: v.unverifiable.firstOrNull()?.let { "判不了：${it.source}" }
        return if (detail == null) head else "$head｜$detail"
    }

    /** 落一行运行日志：日志本身失败不许连累收尾（照 363 偏差账「记完就完」的纪律）。 */
    private fun note(msg: String) {
        runCatching { RunLog.log(msg) }
    }
}
