// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第五十七批 §1.2：过期判据（纯函数）＋「待重验」旁挂 JSON。
//
// 边界（测试照它钉）：判据**只出提示不处置**——不降级、不删技能、不改 SKILL.md；
// 证据不够（没记录 / 窗口不足）⇒ 不判，宁可漏标不冤枉。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SkillExpiryTest {

    private val now = 1_760_000_000_000L          // 固定「现在」
    private val day = 24L * 60 * 60 * 1000

    // ── E1：判据三组（派工单 §2：久未用 / 高失败率 / 都未命中）────────────────

    @Test
    fun `E1_久未用_超过14天标待重验`() {
        val last = now - (SkillRefine.SkillExpiry.STALE_DAYS + 1) * day
        val v = SkillRefine.expiryVerdict(last, emptyList(), now)
        assertEquals(SkillRefine.ExpiryVerdict.STALE_UNUSED, v)
        assertTrue(v.needsRecheck)
        assertTrue("要有能看懂的原因：${SkillRefine.reasonOf(v)}", SkillRefine.reasonOf(v).contains("14 天"))
    }

    @Test
    fun `E1_高失败率_最近5次失败过半标待重验`() {
        val last = now - day  // 刚用过
        val v = SkillRefine.expiryVerdict(last, listOf(false, false, false, true, true), now)
        assertEquals(SkillRefine.ExpiryVerdict.STALE_FAILING, v)
        assertTrue(v.needsRecheck)
    }

    @Test
    fun `E1_都未命中_不算待重验`() {
        val last = now - day
        val v = SkillRefine.expiryVerdict(last, listOf(true, true, true, true, false), now)
        assertEquals(SkillRefine.ExpiryVerdict.FRESH, v)
        assertFalse(v.needsRecheck)
        assertEquals("没标就不该有红字原因", "", SkillRefine.reasonOf(v))
    }

    // ── E1 边界：证据不够不判、阈值取严不取松 ──────────────────────────────────

    @Test
    fun `E1_没记录不判_记录上线前没用过的技能不冤枉`() {
        val v = SkillRefine.expiryVerdict(null, emptyList(), now)
        assertEquals(SkillRefine.ExpiryVerdict.FRESH, v)
    }

    @Test
    fun `E1_窗口不足5次不判失败率`() {
        val last = now - day
        val v = SkillRefine.expiryVerdict(last, listOf(false, false, false, false), now)
        assertEquals("四连败也不够一窗（宁可漏标）", SkillRefine.ExpiryVerdict.FRESH, v)
    }

    @Test
    fun `E1_恰好14天与恰好阈值都不标_严格大于才算`() {
        val exactly = now - SkillRefine.SkillExpiry.STALE_MS
        assertEquals(SkillRefine.ExpiryVerdict.FRESH, SkillRefine.expiryVerdict(exactly, emptyList(), now))
        // 3/5 = 60% 恰好达阈值 ⇒ 标（≥）；2/5 不标
        val last = now - day
        assertEquals(
            SkillRefine.ExpiryVerdict.STALE_FAILING,
            SkillRefine.expiryVerdict(last, listOf(false, false, false, true, true), now),
        )
        assertEquals(
            SkillRefine.ExpiryVerdict.FRESH,
            SkillRefine.expiryVerdict(last, listOf(false, false, true, true, true), now),
        )
    }

    @Test
    fun `E1_又久未用又老失败_两条都命中`() {
        val last = now - (SkillRefine.SkillExpiry.STALE_DAYS + 1) * day
        val v = SkillRefine.expiryVerdict(last, listOf(false, false, false, false, false), now)
        assertEquals(SkillRefine.ExpiryVerdict.STALE_BOTH, v)
        assertTrue(SkillRefine.reasonOf(v).contains("且"))
    }

    @Test
    fun `E1_窗口取最近几次_多余条目不影响`() {
        val last = now - day
        // 前 5 次全败、最近 5 次全成 ⇒ 判 FRESH（判「最近」，不翻旧账）
        val oks = List(5) { false } + List(5) { true }
        assertEquals(SkillRefine.ExpiryVerdict.FRESH, SkillRefine.expiryVerdict(last, oks, now))
    }

    // ── E2：旁挂 JSON（幂等、坏 JSON 当空、SKILL.md 不动）─────────────────────

    @Test
    fun `E2_编码按键排序_同一状态写两次字节一致`() {
        val m1 = linkedMapOf("乙技能" to "超过 14 天没用过", "甲技能" to "最近 5 次里失败≥60%")
        val m2 = linkedMapOf("甲技能" to "最近 5 次里失败≥60%", "乙技能" to "超过 14 天没用过")
        val a = SkillRefine.encodeRecheck(m1)
        val b = SkillRefine.encodeRecheck(m2)
        assertEquals("插入顺序不同也要产出同一份字节（幂等）", a, b)
        // sorted() 按 Unicode 码位：「乙」U+4E59 < 「甲」U+7532 ⇒ 乙在前
        assertTrue("按名排序（码位序）", a.indexOf("乙技能") < a.indexOf("甲技能"))
    }

    @Test
    fun `E2_解码往返一致_坏JSON当空表不抛`() {
        val m = mapOf("做卡流程" to "超过 14 天没用过", "查资料" to "最近 5 次里失败≥60%")
        assertEquals(m, SkillRefine.decodeRecheck(SkillRefine.encodeRecheck(m)))
        assertEquals("空白当空表", emptyMap<String, String>(), SkillRefine.decodeRecheck("  \n"))
        assertEquals("null 当空表", emptyMap<String, String>(), SkillRefine.decodeRecheck(null))
        assertEquals("非对象当空表", emptyMap<String, String>(), SkillRefine.decodeRecheck("[1,2]"))
        assertEquals("嵌套当空表", emptyMap<String, String>(), SkillRefine.decodeRecheck("""{"a":{"b":1}}"""))
        assertEquals("没闭合当空表", emptyMap<String, String>(), SkillRefine.decodeRecheck("""{"a": "b"""))
        assertEquals("值必须是字符串", emptyMap<String, String>(), SkillRefine.decodeRecheck("""{"a": 1}"""))
    }

    @Test
    fun `E2_旁挂文件不叫SKILL_md_判据文件名钉死`() {
        // 红线：SKILL.md 一个字节不动。旁挂文件名必须与 36 批 _分类.json 同族（下划线开头）。
        assertEquals("_重验.json", SkillRefine.RECHECK_FILE)
        assertTrue(SkillRefine.RECHECK_FILE.startsWith("_"))
        assertFalse(SkillRefine.RECHECK_FILE.equals(SkillRules.SKILL_FILE, ignoreCase = true))
    }

    // ── E3：源码门禁（纯函数文件不许碰盘；设置页只写 _重验.json）────────────────

    private fun source(rel: String): String =
        File(rel).let { if (it.isFile) it else File("../$rel") }
            .readText(Charsets.UTF_8).replace("\r\n", "\n")

    @Test
    fun `E3_SkillRefine是纯函数文件_一行写盘语句都没有`() {
        val src = source("app/src/main/java/com/example/zhengdao/baopu/SkillRefine.kt")
        assertFalse("判据文件里不许有 writeText（写盘只许在壳层）", src.contains("writeText"))
        assertFalse("判据文件里不许有 mkdirs", src.contains("mkdirs"))
        assertFalse("判据文件里不许有 delete（不自动删技能）", src.contains(".delete()"))
        assertFalse("判据文件里不许有 renameTo（不自动归档技能）", src.contains("renameTo"))
    }

    @Test
    fun `E3_设置页零写盘_待重验写盘只在数据层refreshRecheck`() {
        // 门禁意图（36 批 T5 原判据）：设置页一个字写盘语句都不许有。
        // 本批把「算表＋写 _重验.json」全收进数据层函数 refreshRecheck（BaopuSkillCategory.kt）。
        val settings = source("app/src/main/java/com/example/zhengdao/ui/baopu/BaopuSettingsScreen.kt")
        assertEquals(
            "设置页不许有 writeText（写全在数据层与分类函数里）",
            0,
            Regex("\\.writeText\\(").findAll(settings).count(),
        )
        val layer = source("app/src/main/java/com/example/zhengdao/ui/baopu/BaopuSkillCategory.kt")
        assertTrue("写盘只落 RECHECK_FILE（落点唯一）", layer.contains("SkillRefine.RECHECK_FILE"))
        assertTrue("判据必须走纯函数 expiryVerdict（不许 UI 自己数）", layer.contains("SkillRefine.expiryVerdict"))
        assertTrue("编码必须走 encodeRecheck（不许自己拼 JSON）", layer.contains("SkillRefine.encodeRecheck"))
        assertTrue("写失败只吞（提示不依赖它）", layer.contains("runCatching {"))
    }

    @Test
    fun `E3_use答复里的提醒只来自过期判据_不拦不降级`() {
        val src = source("app/src/main/java/com/example/zhengdao/baopu/BaopuRuntime.kt")
        val fn = src.substringAfter("private fun recordSkillUse")
        assertTrue("提醒要过 expiryVerdict（不许另立判据）", fn.contains("SkillRefine.expiryVerdict"))
        assertTrue("只有 needsRecheck 才带提醒", fn.contains("if (v.needsRecheck)"))
        assertTrue("提醒是拼一句话，不是改技能/拦调用", fn.contains("先用起来验证"))
    }
}
