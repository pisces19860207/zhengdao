// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第七百二十七批 · 唯一出口：三输入 → 五输出 + event（对齐 731）。
// 出口前过 805 的 BodyPartGuard（长度/面积/百分比 + 诊断词），不出力值、不做行为指导。
// 同帧两击取更强一次并标 multi=true，绝不累加；恢复档随时间夹具落回 0。
package com.example.zhengdao.baopu.state.impact

import com.example.zhengdao.baopu.state.bodyparts.BodyPartGuard
import com.example.zhengdao.baopu.state.prepuce.SkinAmount

/** 分隔符：与 state 各包 line() 一致的机读竖线。 */
private const val SEP: Char = '|'

internal object ImpactSheet {

    /** 从输入算五输出。纯函数：无随机、无时钟、无 IO；同输入逐字一致。 */
    fun of(input: ImpactInput): ImpactResult {
        val primary = ImpactTable.impact(input.speed, input.mass, input.area)
        val impact = if (input.other == null) {
            primary
        } else {
            ImpactTable.stronger(primary, ImpactTable.impact(input.other.speed, input.other.mass, input.other.area))
        }
        val multi = input.other != null
        val recovery = ImpactTable.recoveryAt(impact, input.rounds)
        val (consequence, why) = subzoneConsequence(impact, input)
        val result = ImpactResult(
            impact = impact,
            displace = ImpactTable.displace(impact),
            flatten = ImpactTable.flatten(impact, input.area),
            recovery = recovery,
            words = ImpactTable.words(impact),
            event = ImpactEvent(
                justHit = impact.level > 0,
                cremasteric = input.cremasteric,
                recovery = recovery.level,
            ),
            site = input.site,
            multi = multi,
            consequence = consequence,
            why = why,
        )
        // 构造即守：出口文本与词表都过 805 同一把闸。
        BodyPartGuard.rejectMeasures(result.words.joinToString("、"))
        BodyPartGuard.rejectDiagnosis(result.words.joinToString("、"))
        BodyPartGuard.rejectDiagnosis(why)
        return result
    }

    /**
     * 下体子区后果：同一强度下敏感区（龟头 / 系带 / 冠沟）比柱身 / 根部高一档。
     * 只是观察后果档，不是伤情 / 疼痛分级。
     */
    private fun subzoneConsequence(impact: ImpactTier, input: ImpactInput): Pair<ConsequenceTier, String> {
        val site = input.site ?: return ConsequenceTier.of(impact.level.coerceAtMost(3)) to
            "◐ 未给子区：按冲击档直接取观察后果档，不做部位细分"
        val base = MATRIX_TIER.getValue(ForceKind.BLUNT)[site.ordinal].coerceAtLeast(0)
        val lift = if (site in SENSITIVE_SITES && site in BUFFERED_SITES) {
            val covered = input.coverage != null && input.coverage.order <= 2
            val ample = input.skin == SkinAmount.AMPLE
            if (!covered && !ample) 1 else 0
        } else {
            0
        }
        val tier = (base + lift).coerceAtMost(3)
        return ConsequenceTier.of(tier) to ImpactMatrix.basisOf(site)
    }

    /**
     * 唯一出口。字段序固定、逐字确定；供 731 直接取 event，供描写层取 line()。
     * 每行都过 BodyPartGuard 三道闸：长度/面积/百分比、绝对色号、诊断词。
     */
    fun line(r: ImpactResult): String {
        val text = buildString {
            append("冲击=").append(r.impact.level)
            append(SEP).append("位移=").append(r.displace.level)
            append(SEP).append("压扁=").append(r.flatten.level)
            append(SEP).append("恢复=").append(r.recovery.level)
            append(SEP).append("后果=").append(r.words.joinToString("、"))
            append(SEP).append("multi=").append(r.multi)
            append(SEP).append("event=").append(eventLine(r))
        }
        BodyPartGuard.rejectMeasures(text)
        BodyPartGuard.rejectAbsoluteColor(text)
        BodyPartGuard.rejectDiagnosis(text)
        return text
    }

    /** event 三字段：刚受击? / 提睾中? / 恢复档 0–3（731 §一原样字段）。 */
    fun eventLine(r: ImpactResult): String = buildString {
        append("刚受击=").append(if (r.event.justHit) 1 else 0)
        append(SEP).append("提睾中=").append(if (r.event.cremasteric) 1 else 0)
        append(SEP).append("恢复档=").append(r.event.recovery)
    }
}
