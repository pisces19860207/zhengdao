// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百七十六批 §2 · 插话半引擎接线门禁（房型照 StepBudgetGateTest）：runLoop 要真
// SQLite ＋ 网络（本仓无 Robolectric ⇒ 归真机），「接没接上」钉在源码结构上——判据
// 出处、接线顺序、禁用调用，逐条可 grep。队列语义（FIFO/不丢不重/空白不收）在
// InterjectionsTest 直测；安全点位置（循环头最前、先于组装）在 StepBudgetGateTest。
// 本文件只钉 176 新增的四件事：**可撤销**、**镜像同步**（界面看得见待生效）、
// **按会话绑定**（四个开局口过户，不跨会话漏），以及两条负例（插话不碰 189 的
// 预算/熔断账本；停止不清插话）。
package com.example.zhengdao.baopu.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class InterjectWiringTest {

    private fun repoRel(rel: String): File =
        File(rel).let { if (it.exists()) it else File("app/$rel") }

    /** 源码（剥注释后比对——注释里的旧字面不许参与断言）。 */
    private fun code(rel: String): String {
        val raw = repoRel(rel).readText()
        return raw.lines()
            .filterNot { l ->
                val t = l.trimStart()
                t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
            }
            .joinToString("\n")
    }

    private fun engine() = code("src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt")

    /** 取「fromMarker 起到下一个 toMarker 止」的源码片（marker 本身从切片头跳过）。 */
    private fun fnBody(src: String, fromMarker: String, toMarker: String): String {
        val s = src.indexOf(fromMarker)
        assertTrue("找不到锚点：$fromMarker", s >= 0)
        val bodyStart = s + fromMarker.length
        val e = src.indexOf(toMarker, bodyStart)
        return src.substring(bodyStart, if (e >= 0) e else src.length)
    }

    // ── ① 可撤销（176 §2「可编辑/撤销未生效插话」） ─────────────────────

    @Test
    fun `撤销入口存在_撤不到如实返回false`() {
        val src = engine()
        assertTrue(
            "引擎必须有 revokeInterjection 入口（UI 半的 chip 调它）",
            src.contains("fun revokeInterjection(text: String): Boolean"),
        )
        val body = fnBody(
            src,
            "fun revokeInterjection(text: String): Boolean",
            "fun interject(text: String): Boolean",
        )
        assertTrue("撤销结果如实返回（撤不到就说没撤到，不许假装）", body.contains("return removed"))
    }

    @Test
    fun `队列有按值撤销与快照两个方法`() {
        val src = code("src/main/java/com/example/zhengdao/baopu/agent/Interjections.kt")
        assertTrue("remove（按原文撤第一枪）", src.contains("fun remove(text: String): Boolean"))
        assertTrue("snapshot（界面快照，FIFO 拷贝）", src.contains("fun snapshot(): List<String>"))
    }

    // ── ② 镜像同步：三个写入口都按队列重读 ─────────────────────────────

    @Test
    fun `三个写入口都同步镜像`() {
        val src = engine()
        val sync = "_interjections.value = interjections.snapshot()"
        val onInterject = fnBody(src, "fun interject(text: String): Boolean", "private fun adoptInterjectionsFor")
        assertTrue("interject 后同步镜像（界面立刻看见待生效）", onInterject.contains(sync))
        val onDrain = fnBody(src, "private fun drainInterjections", "fun interjectionsFor")
        assertTrue("drain 后同步镜像（注入后界面收回 chip）", onDrain.contains(sync))
        val onRevoke = fnBody(
            src,
            "fun revokeInterjection(text: String): Boolean",
            "fun interject(text: String): Boolean",
        )
        assertTrue("撤销后同步镜像", onRevoke.contains(sync))
    }

    @Test
    fun `镜像按会话过滤_别的会话的积压不露面`() {
        val src = engine()
        assertTrue(
            "interjectionsFor 按会话给表",
            src.contains("fun interjectionsFor(sessionId: Long): List<String>"),
        )
        val body = fnBody(
            src,
            "fun interjectionsFor(sessionId: Long): List<String>",
            "fun revokeInterjection",
        )
        assertTrue("归属不一致必须给空表（停了 A 去看 B，B 不显示 A 的话）", body.contains("else emptyList()"))
    }

    @Test
    fun `镜像先读后过滤_保证Compose订阅`() {
        // 陷阱（本批自己踩过一版）：归属不符就**不读** _interjections.value ⇒ Compose 不订阅，
        // 归属从 null→本会话那一刻界面收不到通知，chip 行得靠别处碰巧重组才出现。
        val src = engine()
        val body = fnBody(
            src,
            "fun interjectionsFor(sessionId: Long): List<String>",
            "fun revokeInterjection",
        )
        val readAt = body.indexOf("_interjections.value")
        val filterAt = body.indexOf("if (interjectOwner == sessionId)")
        assertTrue("必须读镜像状态（Compose 只有读到才订阅）", readAt >= 0)
        assertTrue("找不到归属判断", filterAt >= 0)
        assertTrue("读镜像必须早于归属判断（无条件先读、读完再过滤）", readAt < filterAt)
    }

    // ── ③ 按会话绑定：四个开局口过户，且在起跑之前 ─────────────────────

    @Test
    fun `四个开局口都过户插话队列`() {
        val src = engine()
        // send 的 STARTED / resume / approveRestoredPending / rejectRestoredPending
        val n = Regex("adoptInterjectionsFor\\(sessionId\\)").findAll(src).count()
        assertTrue(
            "四个开局口都要 adoptInterjectionsFor（send/resume/approve/reject），实际 $n 处",
            n == 4,
        )
    }

    @Test
    fun `过户在起跑之前`() {
        val src = engine()
        val adopt = src.indexOf("adoptInterjectionsFor(sessionId)")
        val start = src.indexOf("startDrain(sessionId)")
        assertTrue("找不到开局过户接线", adopt >= 0 && start >= 0)
        assertTrue(
            "开局过户必须在起跑之前（循环头第一个安全点看到的必是过户后的队列）",
            adopt < start,
        )
    }

    @Test
    fun `过户_同会话带着_换会话丢弃并记日志`() {
        val src = engine()
        val body = fnBody(src, "private fun adoptInterjectionsFor", "private fun assemble")
        assertTrue("同会话不丢（原样带着：「继续」与待生效的话语境相连）", body.contains("if (owner == sessionId) return"))
        assertTrue("换会话丢弃要有日志（不静默吞）", body.contains("log("))
        assertTrue("日志写清丢了几条", body.contains("dropped.size"))
    }

    // ── ④ 负例：插话与 189 预算/熔断共存，不绕过 ───────────────────────

    @Test
    fun `注入不碰189账本_负例`() {
        val src = engine()
        val body = fnBody(src, "private fun drainInterjections", "fun interjectionsFor")
        listOf(
            "noProgressSteps", // §1.3 无进展熔断计数
            "seenStepHashes", // §1.3 已见结果集合
            "stepResultTexts", // §1.3 记账缓冲
            "runToolCalls", // §1.2 段复盘工具数
            "lastLoggedSegment", // §1.2 段号
            "isStepExhausted", // §1.1 步数刹车判据
        ).forEach {
            assertFalse("插话注入不许动 $it（与 189 分段预算/熔断共存，不绕过）", body.contains(it))
        }
    }

    @Test
    fun `停止不清插话_负例`() {
        // 176 §2「插话不丢」：停止只清 117 的回合队列；插话留着（可撤），
        // 下一轮同会话开局还在第一个安全点生效。
        val src = engine()
        val body = fnBody(src, "fun stop() {", "fun stopRequestedNow")
        assertFalse("停止不许顺手清插话队列", body.contains("interjections"))
        assertFalse("停止不许清插话镜像", body.contains("_interjections"))
    }
}
