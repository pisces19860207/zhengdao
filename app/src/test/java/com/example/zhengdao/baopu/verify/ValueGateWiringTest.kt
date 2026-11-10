// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百八十四批 · 上半 ①的判据测试（派工单「判据：①造一个数字错的产物 ⇒ 收尾报 FAIL 且
// 指出哪个数 ②非数字产物 ⇒ 跳过不误报」的 JVM 面）。
//
// 全程真文件真读数：临时工作区真落盘、[ValueCheck] 真重算——没有一分 .mock 的证据。
// 「接在收尾链上」这一半不在这里，在 ValueGateWiringGateTest（源码门禁）。
package com.example.zhengdao.baopu.verify

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.example.zhengdao.testkit.TempSandboxPool
import org.junit.After

class ValueGateWiringTest {
    // 573：临时目录统一走 testkit/TempSandbox（经 TempSandboxPool 登记，@After 收口）；断言零改动。
    @After
    fun zdCleanSandboxes() = TempSandboxPool.clean()


    private fun tmp(name: String): File = TempSandboxPool.create("baopu-384a-$name")

    private fun write(root: File, rel: String, body: String) {
        val f = File(root, rel)
        f.parentFile?.mkdirs()
        f.writeText(body)
    }

    /** 340 的考卷底稿：12 个数，真值 59220（与 ConductExam.numsSheet 同源）。 */
    private val nums = listOf(
        "407", "3859", "620", "9438", "5176", "280",
        "8094", "3745", "6102", "9538", "4671", "7290",
    )

    // ── 判据①：数字错的产物 ⇒ FAIL，且指出哪个数 ─────────────────────────────

    @Test
    fun `判据一_数字错的产物_报FAIL并逐字给出我说X实际Y`() {
        val root = tmp("wrong-sum")
        write(root, "notes/nums.txt", nums.joinToString("\n"))
        write(root, "sum.txt", "55220")

        val findings = ValueGateWiring.findingsOf(
            "12 个数字相加得 55220，已写入 sum.txt。",
            root,
            setOf("sum.txt"),
        )

        assertEquals("数字错必须报一条（不许静默放过）", 1, findings.size)
        assertEquals("check 名要能进「自检未通过」清单给人看", ValueGateWiring.CHECK_VALUE, findings[0].check)
        assertTrue("没指出哪个数：${findings[0].detail}", findings[0].detail.contains("我说 55220"))
        assertTrue("没给出实际值：${findings[0].detail}", findings[0].detail.contains("59220"))
    }

    @Test
    fun `判据一_数对_不产finding`() {
        val root = tmp("right-sum")
        write(root, "notes/nums.txt", nums.joinToString("\n"))
        write(root, "sum.txt", "59220")

        val findings = ValueGateWiring.findingsOf(
            "12 个数字相加得 59220，已写入 sum.txt。",
            root,
            setOf("sum.txt"),
        )

        assertTrue("数对还报错＝误报", findings.isEmpty())
    }

    // ── 判据②：非数字产物 ⇒ 跳过不误报 ───────────────────────────────────────

    @Test
    fun `判据二_非数字产物_跳过且不产finding`() {
        val root = tmp("prose")
        write(root, "notes.md", "整理要点：\n- 第一件事\n- 第二件事")

        val answer = "我把要点整理好了，见 notes.md。"
        val findings = ValueGateWiring.findingsOf(answer, root, setOf("notes.md"))

        assertTrue("散文终答被当成数字错＝误报", findings.isEmpty())
        assertTrue(
            "跳过也要留一行读数（判据②的「记一句」）",
            ValueGateWiring.line(ValueGateWiring.verdict(answer, root, setOf("notes.md"))).contains("跳过"),
        )
    }

    // ── 第三条口径：UNVERIFIABLE 不进 findings（口径立不住≠对不上）────────────

    @Test
    fun `判不了_不进findings_只记读数`() {
        val root = tmp("unverifiable")
        write(root, "notes/nums.txt", nums.joinToString("\n"))

        val answer = "这轮完成 85%。"
        val v = ValueGateWiring.verdict(answer, root, emptySet())

        assertTrue("判不了的被当成未通过＝逼模型改口径（改判定）", ValueGateWiring.findingsOf(answer, root, emptySet()).isEmpty())
        assertTrue("判不了要进读数行", ValueGateWiring.line(v).contains("判不了"))
    }

    @Test
    fun `读数行_恒一行_核了几条对几条都说清`() {
        val root = tmp("line")
        write(root, "notes/nums.txt", nums.joinToString("\n"))

        val v = ValueGateWiring.verdict("12 个数字相加得 59220。", root, emptySet())

        assertTrue(v.ran)
        val line = ValueGateWiring.line(v)
        assertTrue("读数行没给条数：$line", line.contains("核 1 条"))
        assertTrue("读数行没给通过数：$line", line.contains("对 1"))
    }
}
