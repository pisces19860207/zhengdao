// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 599a 判据（2026-11-11 改口径）：
//   ⑧ 改上限配置 ⇒ 预检阈值随之变；⑨ 默认裸数组／开关整包（同一份代码）；
//   超限报"夜语 1000/20000，风月 2000/40000；当前 N 条/M 字"，不截断；
//   无平台名 if：差异只进 profile 数据（源码闸钉住）。
package com.example.zhengdao.baopu.card

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebPlatformBookTest {

    private fun entry(key: String, value: String, uid: String? = null): JSONObject {
        val e = JSONObject().put("key", key).put("value", value).put("group", "").put("enable", true)
        uid?.let { e.put("uid", it) }
        return e
    }

    private fun parse(text: String): Any = CardJson.parseObject(text) ?: JSONArray(text)

    // ── 判据⑨：同一份代码，两种外壳 ─────────────────────────────────────

    @Test
    fun `export_default_is_bare_array_and_keeps_entry_fields`() {
        val out = WebPlatformBook.export(listOf(entry("_or_甲@wb@乙", "示例正文", uid = "占位-0")))
        val arr = parse(out) as JSONArray
        assertEquals(1, arr.length())
        assertEquals("示例正文", arr.optJSONObject(0)!!.optString("value"))
        assertEquals("占位-0", arr.optJSONObject(0)!!.optString("uid"))
        assertTrue("裸数组不裹壳", out.trimStart().startsWith("["))
    }

    @Test
    fun `export_switch_writes_whole_package_with_only_whitelisted_fields`() {
        val fields = JSONObject()
            .put("pre_text", "前置文本")
            .put("post_text", "后置文本")
            .put("_comment", "白名单外：不许写")
        val out = WebPlatformBook.export(
            listOf(entry("_or_甲", "示例正文")),
            wholePackage = true,
            packageFields = fields,
        )
        val o = parse(out) as JSONObject
        assertTrue("world_book 数组", o.optJSONArray("world_book")!!.length() == 1)
        assertEquals("前置文本", o.optString("pre_text"))
        assertEquals("后置文本", o.optString("post_text"))
        assertFalse("白名单外的键不写", o.has("_comment"))
        // 没给的整包字段不写（有则写、无则不写）
        assertFalse(o.has("pre_prompt"))
        assertFalse(o.has("opening_statement"))
    }

    @Test
    fun `package_fields_of_picks_whitelist_only`() {
        val src = JSONObject()
            .put("pre_text", "有")
            .put("category", 3)
            .put("非白名单", 1)
            .put("opening_statement", JSONObject.NULL)
        val picked = WebPlatformBook.packageFieldsOf(src)!!
        assertTrue(picked.has("pre_text") && picked.has("category"))
        assertFalse("JSON null 不算「有」", picked.has("opening_statement"))
        assertFalse(picked.has("非白名单"))
        assertEquals(null, WebPlatformBook.packageFieldsOf(JSONObject()))
    }

    // ── 判据⑧：上限可配 ⇒ 阈值随之变 ────────────────────────────────────

    @Test
    fun `precheck_default_is_family_strictest_1000_20000`() {
        val p = WebPlatformProfile()
        assertEquals(1000, p.maxEntries)
        assertEquals(20000, p.maxValueChars)
        // 1000 条 × 20 字 ＝ 20000 字：正好压线，通过
        val full = (1..1000).map { entry("_or_词$it", "字".repeat(20)) }
        WebPlatformBook.precheck(full, p)
        // 1001 条：报条数；消息带族内事实与当前数（1000×20＋1＝20001 字）
        val err = runCatching { WebPlatformBook.precheck(full + entry("_or_多", "字"), p) }.exceptionOrNull()!!
        assertTrue(err.message!!.contains("夜语 1000/20000，风月 2000/40000"))
        assertTrue(err.message!!.contains("当前 1001 条/20001 字"))
    }

    @Test
    fun `precheck_thresholds_follow_the_config`() {
        val strict = WebPlatformProfile().withLimits(entries = 2, chars = 10)
        WebPlatformBook.precheck(listOf(entry("_or_甲", "字".repeat(5)), entry("_or_乙", "字".repeat(5))), strict)
        // 字数超：3 条共 12 字 > 2 条/10 字 ⇒ 报当前 3 条/12 字
        val err = runCatching {
            WebPlatformBook.precheck(
                listOf(entry("_or_甲", "字".repeat(4)), entry("_or_乙", "字".repeat(4)), entry("_or_丙", "字".repeat(4))),
                strict,
            )
        }.exceptionOrNull()!!
        assertTrue(err.message!!.contains("当前 3 条/12 字"))
        assertTrue(err.message!!.contains("本档预检 2 条/10 字"))
    }

    @Test
    fun `precheck_rejects_non_positive_limits`() {
        val err = runCatching { WebPlatformProfile(maxEntries = 0) }.exceptionOrNull()
        assertTrue(err is IllegalArgumentException)
    }

    @Test
    fun `export_throws_precheck_error_and_returns_nothing_when_over`() {
        val err = runCatching {
            WebPlatformBook.export(
                (1..1001).map { entry("_or_词$it", "字") },
            )
        }.exceptionOrNull()!!
        assertTrue(err is WebPlatformBook.PrecheckError)
        assertTrue(err.message!!.contains("当前 1001 条"))
    }

    // ── 无平台名 if（总判据③）：差异只进 profile 数据 ────────────────────

    @Test
    fun `platform_names_live_only_in_profile_data_not_in_branches`() {
        val banned = listOf("风月", "夜语", "FENGYUE", "YEYE")
        val files = listOf(
            "src/main/java/com/example/zhengdao/baopu/card/WebPlatformBook.kt",
            "src/main/java/com/example/zhengdao/baopu/card/CardBuild.kt",
            "src/main/java/com/example/zhengdao/baopu/card/CardFormat.kt",
            "src/main/java/com/example/zhengdao/baopu/card/CardModel.kt",
            "src/main/java/com/example/zhengdao/baopu/card/CardAssetParser.kt",
            "src/main/java/com/example/zhengdao/baopu/agent/tools/CardTools.kt",
        )
        for (rel in files) {
            val f = File(rel).let { if (it.exists()) it else File("app/$rel") }
            // 先剥块注释（拍板引语在 KDoc 里会提到平台名），再剥行注释，最后逐行查
            val code = f.readText()
                .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
                .split("\n").joinToString("\n") { it.substringBefore("//") }
            for ((i, line) in code.lines().withIndex()) {
                val hit = banned.filter { line.contains(it, ignoreCase = true) }
                if (hit.isEmpty()) continue
                assertTrue(
                    "${f.name}:${i + 1} 平台名只许出现在 FAMILY_LIMITS_NOTE 数据行（不许进 if/when 分支）：$line",
                    line.contains("FAMILY_LIMITS_NOTE"),
                )
            }
        }
    }
}
