// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百一十批 · 许愿流水线 §一⑥：灰度→转正（473）／不达标→回滚（470）。
// 473/482 尚未落地，本件按派工单口径在流水线内实现灰度判定（纯函数），
// 转正/回滚的写盘仍走既有 SkillStore/SkillPromote/PostAdoptionRollback。
// 阈值复用 SkillStore.PROMOTE_USES = 3（用满 3 次转正，同口径）。
package com.example.zhengdao.baopu.wish

import com.example.zhengdao.baopu.SkillStore

/** 灰度状态（三态）。 */
enum class GrayState { GRAY, PROMOTED, ROLLED_BACK }

/** 灰度记录（一次许愿的灰度阶段上下文）。 */
data class GrayRecord(
    val wishId: String,
    val product: WishProduct,
    val state: GrayState = GrayState.GRAY,
    val uses: Int = 0,
    val rollbackReason: String? = null,
)

/** 灰度判定（纯函数；判据⑥：达标自动转正、不达标自动回滚）。 */
internal object WishGrayRelease {

    /** 转正阈值＝草稿试用期那把尺的别名（不自定；用例 `阈值与SkillStore同口径` 钉二者同源）。 */
    const val PROMOTE_THRESHOLD = SkillStore.PROMOTE_USES

    /** 回滚条件（命中任何一条 ⇒ 回滚）。 */
    fun shouldRollback(selfCheckPassed: Boolean, redLineHit: Boolean, realFileChanged: Boolean): Boolean =
        !selfCheckPassed || redLineHit || realFileChanged

    /** 评估灰度状态（纯函数；不写盘）。 */
    fun evaluate(record: GrayRecord, selfCheckPassed: Boolean, redLineHit: Boolean = false, realFileChanged: Boolean = false): GrayRecord {
        if (shouldRollback(selfCheckPassed, redLineHit, realFileChanged)) {
            val reason = rollbackReason(selfCheckPassed, redLineHit, realFileChanged)
            return record.copy(state = GrayState.ROLLED_BACK, rollbackReason = reason)
        }
        if (record.uses >= PROMOTE_THRESHOLD) {
            return record.copy(state = GrayState.PROMOTED)
        }
        return record.copy(state = GrayState.GRAY)
    }

    /** 记一次使用（灰度期内每次成功使用 +1）。 */
    fun recordUse(record: GrayRecord): GrayRecord =
        record.copy(uses = record.uses + 1)

    private fun rollbackReason(selfCheckPassed: Boolean, redLineHit: Boolean, realFileChanged: Boolean): String {
        val parts = mutableListOf<String>()
        if (!selfCheckPassed) parts += "自检不达标"
        if (redLineHit) parts += "命中红线"
        if (realFileChanged) parts += "真文件被改"
        return parts.joinToString("＋")
    }
}