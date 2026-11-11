// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单 599 改口径（2026-11-11 用户拍板「风月与夜语不分家，统一网络平台」）：
//   · 只有一份 WEB_PLATFORM profile：上限默认按**族内最严**预检（1000 条/20000 字），可配；
//   · 超限报数（"夜语 1000/20000，风月 2000/40000；当前 N 条/M 字"），**不许静默截断**；
//   · 常驻等可选字段：有则写、无则不写，**不按平台分支**（常驻字段名未实测 ⇒ 不列、不猜）；
//   · 外壳：默认**裸数组**；整包（world_book 对象）用**布尔开关**（不是平台判断）；
//   · **不许平台名 if**：差异只进 profile 数据。
// 对标事实：`_zd-private/docs/对标-风月与夜语平台格式-2026-11-11.md`（真件只读，内容不进仓）。
package com.example.zhengdao.baopu.card

import org.json.JSONArray
import org.json.JSONObject

/**
 * 网络平台族导出 profile（**当前一份**；2026-11-11 拍板）。
 *
 * 上限默认取族内最严：族内两家实测上限不一致（1000 条/20000 字 与 2000 条/40000 字），
 * 不按平台猜 ⇒ 先按最严的挡；要放宽就改这份数据（或经母档 entry_limit／value_char_limit 覆盖）。
 */
internal data class WebPlatformProfile(
    val maxEntries: Int = 1000,
    val maxValueChars: Int = 20000,
) {
    init {
        require(maxEntries > 0) { "maxEntries 必须是正整数（给的是 $maxEntries）" }
        require(maxValueChars > 0) { "maxValueChars 必须是正整数（给的是 $maxValueChars）" }
    }

    /** 上限可配（判据⑧）：改配置 ⇒ 预检阈值随之变；null ＝ 沿用本档现值。 */
    fun withLimits(entries: Int?, chars: Int?): WebPlatformProfile = WebPlatformProfile(
        maxEntries = entries ?: maxEntries,
        maxValueChars = chars ?: maxValueChars,
    )

    companion object {
        /** 族内上限不一致的事实（拍板话术的数据源；报超限时原文带出，**不猜谁是导出目标**）。 */
        const val FAMILY_LIMITS_NOTE = "夜语 1000/20000，风月 2000/40000"

        /**
         * 整包可选字段（对标 2026-11-11 实测的顶层键；差异只进这份数据）。
         * **可选**：源里有就写、没有就不写，不按平台分支；
         * 「常驻」开关的字段名未实测到 ⇒ 不在本列、**不猜**。
         */
        val OPTIONAL_PACKAGE_FIELDS = listOf(
            "pre_text",
            "post_text",
            "pre_prompt",
            "opening_statement",
            "suggested_questions",
            "preset_chats",
            "category",
        )
    }
}

/**
 * 网络平台族世界书导出：**同一份代码**出两种外壳（裸数组／整包），上限预检在这里把门。
 *
 * 两条纪律（拍板原文）：
 * 1. 超限报数、整次拒绝，**不许静默截断**；
 * 2. 可选字段有则写、无则不写——本函数不补任何字段、不改任何条目内容。
 */
internal object WebPlatformBook {

    /** 预检失败：整次拒绝（调用方不得写半个文件），话术带「当前 N 条/M 字」。 */
    class PrecheckError(message: String) : Exception(message)

    /**
     * 导出一份网络平台族世界书。
     *
     * @param entries 条目（调用方组好字段；原样写出）。
     * @param wholePackage 外壳布尔开关：false＝裸数组（默认）；true＝对象包 `world_book`。
     * @param packageFields 整包可选字段的原值；只写 [WebPlatformProfile.OPTIONAL_PACKAGE_FIELDS]
     *   白名单内的键（有则写、无则不写）。
     * @param profile 上限预检配置（默认族内最严）。
     */
    fun export(
        entries: List<JSONObject>,
        wholePackage: Boolean = false,
        packageFields: JSONObject? = null,
        profile: WebPlatformProfile = WebPlatformProfile(),
    ): String {
        precheck(entries, profile)
        val arr = JSONArray()
        entries.forEach { arr.put(it) }
        if (!wholePackage) return arr.toString(2) + "\n"
        return JSONObject()
            .apply { putPackageFields(packageFields) }
            .put("world_book", arr)
            .toString(2) + "\n"
    }

    /** 白名单内的键有则写（JSON null 一律跳过）；无则什么都不写。 */
    private fun JSONObject.putPackageFields(packageFields: JSONObject?) {
        packageFields ?: return
        for (k in WebPlatformProfile.OPTIONAL_PACKAGE_FIELDS) {
            if (packageFields.has(k) && !CardJson.isJsonNull(packageFields, k)) put(k, packageFields.opt(k))
        }
    }

    /**
     * 容量预检（条数 ＋ 正文字数）。**字 ＝ 全部条目正文字符数之和**（「N 条/M 字」按并算口径：
     * 1000 条/20000 字 ≈ 平均 20 字/条的联合预算；并算 ≥ 单条最大值 ⇒ 取更严的一种）。
     * 超限 ⇒ [PrecheckError]：报族内上限不一致的事实 ＋ 当前 N 条/M 字 ＋ 本档阈值。
     */
    fun precheck(entries: List<JSONObject>, profile: WebPlatformProfile = WebPlatformProfile()) {
        val n = entries.size
        val m = entries.sumOf { CardJson.stringOf(it, "value")?.length ?: 0 }
        if (n <= profile.maxEntries && m <= profile.maxValueChars) return
        throw PrecheckError(
            "网络平台族上限不一致：${WebPlatformProfile.FAMILY_LIMITS_NOTE}；当前 $n 条/$m 字" +
                "（本档预检 ${profile.maxEntries} 条/${profile.maxValueChars} 字）。" +
                "超限不截断，已整次拒绝；要放宽请改上限配置。",
        )
    }

    /** 从源对象里挑整包可选字段（白名单内、有则收；一个都没有 ⇒ null）。 */
    fun packageFieldsOf(source: JSONObject?): JSONObject? {
        source ?: return null
        val out = JSONObject()
        for (k in WebPlatformProfile.OPTIONAL_PACKAGE_FIELDS) {
            if (source.has(k) && !CardJson.isJsonNull(source, k)) out.put(k, source.opt(k))
        }
        return if (out.length() == 0) null else out
    }
}
