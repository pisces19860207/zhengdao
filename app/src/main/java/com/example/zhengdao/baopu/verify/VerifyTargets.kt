// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 「本轮到底写过哪些文件」的唯一来源：工具调用本身。
// 为什么从调用参数取、而不是从工具返回文本里抠：文本是给人看的，格式随文案变；
// 参数是契约（Schema 里写着），稳定。
//
// 只认**真正会落盘**的工具（写文件/改文件/转码/搬文件）；读类工具不进本表
// ——读了不存在的东西该由工具自己报错，不该让收尾闸去验「读过的文件还在不在」。
package com.example.zhengdao.baopu.verify

import org.json.JSONObject

object VerifyTargets {

    const val ARG_PATH = "path"
    const val ARG_TO = "to"

    /** 写类工具 ⇒ 参数里哪个键是「最终写入的路径」。 */
    private val WRITE_TOOLS = mapOf(
        "write_file" to ARG_PATH,
        "edit_file" to ARG_PATH,
        "convert_encoding" to ARG_PATH,
        "move_path" to ARG_TO,
    )

    /**
     * 从一次工具调用里取它写入的相对路径。
     * @return 空列表 = 这条调用不需要验收（读类/不认识的工具/参数缺键）
     */
    fun pathsOf(name: String, args: JSONObject?): List<String> {
        val key = WRITE_TOOLS[name] ?: return emptyList()
        val raw = args?.optString(key).orEmpty().trim()
        if (raw.isEmpty()) return emptyList()
        return listOf(raw)
    }
}
