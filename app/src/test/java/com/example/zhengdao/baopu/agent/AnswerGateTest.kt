// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百二十七批 §0.5 的判据：三条程序判据给样本句 → 期望命中/不命中；
// 外加一条负例门禁（不许引模型裁判、不许打分）与创作模式豁免。
package com.example.zhengdao.baopu.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AnswerGateTest {

    // ── ① 数字必须来自表 ───────────────────────────────────────────────

    @Test
    fun `判据1_没读过的数字命中_读过的数字不命中`() {
        val hit = AnswerGate.check("本月一共 1,234 元。", ground = "", workMode = true)
        assertEquals(1, hit.count { it.check == AnswerGate.Check.NUMBER_NOT_FROM_TABLE })
        assertEquals("数字可能不是来自表：「1,234」", hit.first().text)

        val miss = AnswerGate.check("本月一共 1,234 元。", ground = "表：本月 1234 元", workMode = true)
        assertTrue("读过的数字不该可疑", miss.none { it.check == AnswerGate.Check.NUMBER_NOT_FROM_TABLE })
    }

    @Test
    fun `判据1_千分位与小数归一后算同一个数`() {
        val miss = AnswerGate.check("合计 1,234.00 元", ground = "1234", workMode = true)
        assertTrue(miss.none { it.check == AnswerGate.Check.NUMBER_NOT_FROM_TABLE })

        val hit = AnswerGate.check("合计 1,234.50 元", ground = "1234", workMode = true)
        assertTrue(hit.any { it.check == AnswerGate.Check.NUMBER_NOT_FROM_TABLE })
    }

    @Test
    fun `判据1_日期与时间不是表里的读数`() {
        val out = AnswerGate.check("2026-10-26 12:30 共 5 条", ground = "5", workMode = true)
        assertTrue("日期不该被当成可疑数字", out.none { it.check == AnswerGate.Check.NUMBER_NOT_FROM_TABLE })
    }

    @Test
    fun `判据1_一位数没有数据单位不算具体数字`() {
        val out = AnswerGate.check("建议分 3 步走", ground = "", workMode = true)
        assertTrue("「第 3 步」不是表里的读数", out.none { it.check == AnswerGate.Check.NUMBER_NOT_FROM_TABLE })
    }

    // ── ② 缺字段不许推算 ───────────────────────────────────────────────

    @Test
    fun `判据2_推测口吻加数字命中`() {
        val out = AnswerGate.check("大概是 3 万左右", ground = "3", workMode = true)
        assertTrue(out.any { it.text == "疑似推算，不是读数" })
    }

    @Test
    fun `判据2_纯读数不命中`() {
        val out = AnswerGate.check("表里写的是 30000 元", ground = "30000", workMode = true)
        assertTrue(out.none { it.check == AnswerGate.Check.SPECULATION })
    }

    @Test
    fun `判据2_约定不是推测口吻`() {
        val out = AnswerGate.check("按约定 3 条处理", ground = "3", workMode = true)
        assertTrue("「约定」不该命中", out.none { it.check == AnswerGate.Check.SPECULATION })
    }

    // ── ③ 查不到就说查不到 ─────────────────────────────────────────────

    @Test
    fun `判据3_没找到与结论同时出现命中`() {
        val out = AnswerGate.check("没找到这条记录，但结论就是 3 条", ground = "3", workMode = true)
        assertTrue(out.any { it.text == "结论与\"没找到\"矛盾" })
    }

    @Test
    fun `判据3_只有一边不命中`() {
        assertTrue(
            AnswerGate.check("没找到这条记录", ground = "", workMode = true)
                .none { it.check == AnswerGate.Check.NOT_FOUND_CONTRADICTION },
        )
        assertTrue(
            AnswerGate.check("结论是 3 条", ground = "3", workMode = true)
                .none { it.check == AnswerGate.Check.NOT_FOUND_CONTRADICTION },
        )
    }

    // ── 工作模式 / 页顶一句 / 负例门禁 ─────────────────────────────────

    @Test
    fun `创作模式一条都不报`() {
        val out = AnswerGate.check("大概 12345 元，没找到但结论就是 9 条", ground = "", workMode = false)
        assertTrue(out.isEmpty())
    }

    @Test
    fun `页顶只报一句_N条可疑`() {
        val findings = AnswerGate.check("一共 1,234 元，大概是 3 万", ground = "3", workMode = true)
        val line = AnswerGate.notice(findings)
        assertNotNull(line)
        assertTrue(line!!.startsWith("这轮有 ${findings.size} 条可疑："))
        assertFalse("页顶不许换行", line.contains('\n'))
        assertNull(AnswerGate.notice(emptyList()))
    }

    @Test
    fun `负例_答案自检不引模型裁判也不打分`() {
        val src = repoFile("src/main/java/com/example/zhengdao/baopu/agent/AnswerGate.kt").readText()
        listOf("Llm", "score", "OpenAI", "provider").forEach {
            assertFalse("答案自检里不许出现 $it（程序判据，不判卷）", src.contains(it))
        }
    }

    @Test
    fun `判据_本轮回灌有上限且不抛`() {
        val sb = StringBuilder()
        AnswerGate.appendGround(sb, "字".repeat(AnswerGate.MAX_GROUND_CHARS + 100))
        assertTrue(sb.length <= AnswerGate.MAX_GROUND_CHARS + 1)
        AnswerGate.appendGround(sb, "再来一段")
        assertTrue(sb.length <= AnswerGate.MAX_GROUND_CHARS + 1)
    }

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.exists()) it else File("app/$rel") }
}
