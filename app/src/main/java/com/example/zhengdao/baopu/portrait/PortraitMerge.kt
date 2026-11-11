// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百九十七批（2026-11-12）：appearance 草稿**进卡**的判定层（判据③/④）。
//
// 落点：酒馆角色卡 `data.extensions.appearance`（字符串）——v3 标准 14 字段没有外貌位，
// extensions 是酒馆自己的扩展区（TavernFields 的 EXTENSIONS 同一处），放这里不动标准字段。
//
// 两条硬规矩（负例各有测试）：
// 1. **不覆盖已有外貌**：`data.extensions.appearance` 已有非空值 ⇒ 拒，一个字都不动；
// 2. **只进角色卡**：没有 `data` 对象（世界书/正则/预设）⇒ 拒——外貌是角色的属性，
//   不是世界书的属性。
//
// 纯函数：喂 JSONObject 出结论，写盘归工具层（CONFIRM 弹卡在管线，人批在那里）。
package com.example.zhengdao.baopu.portrait

import org.json.JSONObject

internal object PortraitMerge {

    /** 判定结论。 */
    sealed interface Verdict {
        /** 可以进：带**改好的卡对象**（工具层只管落盘）。 */
        data class Ok(val card: JSONObject) : Verdict

        /** 拒：给模型/人看的理由（逐字，测试断言）。 */
        data class Refuse(val reason: String) : Verdict
    }

    const val FIELD = "appearance"
    const val REFUSE_NO_DATA = "这不是带 data 的角色卡（世界书/正则/预设没有外貌位），拒。"
    const val REFUSE_HAS_APPEARANCE = "卡里已有外貌：不覆盖（人批只批「补空」，不批「改写」）。"

    /**
     * 进卡判定（顺序即法条：先验卡型、再查已有外貌、最后写）。
     * 传入的对象不改——Ok 里回的是**拷贝后改好**的对象。
     */
    fun apply(card: JSONObject, appearance: String): Verdict {
        val data = card.optJSONObject("data") ?: return Verdict.Refuse(REFUSE_NO_DATA)
        val existing = data.optJSONObject("extensions")?.optString(FIELD).orEmpty().trim()
        if (existing.isNotEmpty()) return Verdict.Refuse(REFUSE_HAS_APPEARANCE)
        val out = JSONObject(card.toString())
        val dataOut = out.getJSONObject("data")
        val ext = dataOut.optJSONObject("extensions") ?: JSONObject().also { dataOut.put("extensions", it) }
        ext.put(FIELD, appearance)
        return Verdict.Ok(out)
    }
}
