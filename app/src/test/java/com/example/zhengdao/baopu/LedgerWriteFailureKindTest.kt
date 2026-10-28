// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百五十九批 B 段第 6 条（257 审计 A-P2-16）的复现用例。
//
// 缺陷原文：`markDeletedInLedger` 第③步「写后校验」是
// `val after = runCatching { ledger.readText() }.getOrNull(); if (after == text) 成功 else "concurrent"`
// —— `after == null`（台账明明写成了、就是读不出＝存储 IO 故障）也落进 else，
// 于是界面把「读不出」翻成「台账被别人同时改了，请重试」：用户被告知去查并发，该查的是盘。
//
// 「文件在、就是读不出」在测试环境里造不出来（能造的只有权限/盘满，Windows 上 File.setReadable
// 又不生效），所以读这一步做成了可注入的缝（ArtifactDeleteRules 的 4 参 internal 重载）：
// 生产走默认读法，本类喂失败——走的仍是真实的「写盘 → 校验 → 重试」全路径。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class LedgerWriteFailureKindTest {

    @Rule
    @JvmField
    val tmp: TemporaryFolder = TemporaryFolder()

    private val ledger = """
        # 产物台账

        ## 2026-10-23 批次（10:00 落盘）
        - ☐ 20261023_微头条_1_楼下便利店老板娘.md（612 字）
        - ✔ 20261024_家庭_2_中年失业第三个月.txt（655 字）
    """.trimIndent()

    private val a = "20261023_微头条_1_楼下便利店老板娘.md"
    private val stamp = 1_760_000_000_000L

    private fun tmpLedger(): File {
        val f = File(tmp.newFolder("产物"), "台账.md")
        f.writeText(ledger, Charsets.UTF_8)
        return f
    }

    /** 判据本身：三种读数三种答案（**改前没有这个函数**，`null` 与「内容不同」共用一个 else）。 */
    @Test
    fun `判据_读不回是io_内容不同才是concurrent`() {
        assertNull(ArtifactDeleteRules.verifyAfterRead("同一份", "同一份"))
        assertEquals("io", ArtifactDeleteRules.verifyAfterRead(null, "同一份"))
        assertEquals("concurrent", ArtifactDeleteRules.verifyAfterRead("别人改过的", "同一份"))
    }

    /**
     * 全路径复现：① 写前读得到 → 真的写进盘了 → ③ 校验读不回。
     *
     * **改前表现（这条就是复现原缺陷的那一条）**：`why == "concurrent"`——文件其实已经写成，
     * 只是校验时读不出，却被报成「台账被别人同时改了」。改后必须 `why == "io"`，
     * 并且**不许**把已经写成的那份说成没写（下一批仍看得见这一条标记）。
     */
    @Test
    fun `写后读不回报io_不报错成并发冲突`() {
        val f = tmpLedger()
        var calls = 0
        val r = ArtifactDeleteRules.markDeletedInLedger(f, listOf(a), stamp) {
            calls++
            // 奇数次＝① 写前重读（永远喂改前那一份，验证的是判据不是缓存）；偶数次＝③ 写后校验，读不出
            if (calls % 2 == 0) throw IOException("存储读不出")
            ledger
        }
        assertFalse("校验没通过就不算写成（与改前一致）", r.ok)
        assertEquals("读不回必须报 io 而不是 concurrent", "io", r.why)
        assertTrue("两次重试都用同一条判据（①②③各读一次 ⇒ 4 次）", calls >= 4)
        assertTrue("盘上其实已经写成了标记", f.readText(Charsets.UTF_8).contains("🗑 已删 $a（"))
    }

    /** 真并发仍然报 concurrent（改后不许把并发也一并说成 IO——那是反向错报）。 */
    @Test
    fun `真并发仍报concurrent_重试次数没动`() {
        val f = tmpLedger()
        var calls = 0
        val r = ArtifactDeleteRules.markDeletedInLedger(f, listOf(a), stamp) {
            calls++
            if (calls % 2 == 0) "别人在这一刻改了台账" else ledger
        }
        assertFalse(r.ok)
        assertEquals("concurrent", r.why)
        assertEquals("重试恰好两轮＝4 次读（老行为：repeat(2) 没改）", 4, calls)
    }

    /**
     * 默认读法仍是真实文件读（生产路径没被缝换掉）：同样一条删除，走 3 参重载要写成。
     * **改前表现**：这条也绿——它是回归护栏，证明我只加了判据与缝，没动成功路径。
     */
    @Test
    fun `默认读法仍能写成_缝不是新真相`() {
        val f = tmpLedger()
        val r = ArtifactDeleteRules.markDeletedInLedger(f, listOf(a), stamp)
        assertTrue("写成：${r.why}", r.ok)
        assertEquals(listOf(a), r.marked)
        assertTrue(ArtifactDeleteRules.deletedNames(f.readText(Charsets.UTF_8)).contains(a))
    }

    /** 台账不存在仍报 no_ledger（本批没顺手改其它分支——负例 3「为通过用例删错误分支」的反证）。 */
    @Test
    fun `没有台账仍报no_ledger`() {
        val missing = File(tmp.newFolder("空目录"), "台账.md")
        val r = ArtifactDeleteRules.markDeletedInLedger(missing, listOf(a), stamp)
        assertFalse(r.ok)
        assertEquals("no_ledger", r.why)
    }
}
