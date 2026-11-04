// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu

import com.example.zhengdao.baopu.agent.ExperienceKind
import com.example.zhengdao.baopu.agent.ExperienceRow
import com.example.zhengdao.baopu.agent.JudgmentExperienceLedger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 第四百三十六批 A ③——判据与坑账**运行时包**的门禁测试。
 *
 * 四条判据逐条对上派工单原文：
 * - system 里能看到**版本号与条数**（[版本与条数进块]）；
 * - **同版本连续两轮不重复增长**（[同版本两轮不增长] ＋ [版本变了才换文本]）；
 * - **过期 ⇒ 「你的坑账是旧的」**（[过期提示] ＋ 空账负例 [空账不谎报旧账]）；
 * - **总长度有上限**，超了按最近/最相关截断（[长度上限从最不相关那头丢]）。
 *
 * 另两条是本批的边界自证：注入**只进 system**（[落点是system不是user]，源级钉），
 * 且**零拦截**（[零拦截_不碰守卫]）。
 */
class CriteriaPitPackTest {

    private val day = 86_400_000L

    private fun pit(atMs: Long, label: String = "弹卡", why: String = "同类卡被关三次还问") =
        PackPit(atMs = atMs, label = "violation $label", why = why)

    private fun input(
        pitTotal: Int = 12,
        pits: List<PackPit> = listOf(pit(10L * day)),
        lastUpdatedAtMs: Long? = 10L * day,
        fullPath: String? = "/data/user/0/com.example.zhengdao/files/抱朴记忆/判决经验账.jsonl",
        criteriaVersion: String = "写死三条，还没有进化表",
    ) = PackInput(
        criteriaVersion = criteriaVersion,
        criteria = listOf(
            PackCriterion("交付物必须真落盘才算完成（不许「声称完成」）", "L19"),
            PackCriterion("破坏性动作必须人确认", "L16"),
            PackCriterion("紧急卡必须人批（止损白名单除外）", "L21"),
        ),
        pitTotal = pitTotal,
        pits = pits,
        lastUpdatedAtMs = lastUpdatedAtMs,
        fullPath = fullPath,
    )

    // ── 判据①：版本号与条数 ────────────────────────────────────────────

    @Test
    fun `版本与条数进块`() {
        val text = CriteriaPitPack.render(input(), nowMs = 11L * day)
        assertTrue("块里要看得见包版本号：缺「运行时包 v1」", text.contains("运行时包 v${CriteriaPitPack.VERSION}"))
        assertTrue("块里要看得见判据条数", text.contains("判据 3 条"))
        assertTrue("块里要看得见坑账条数", text.contains("坑账 12 条"))
        assertTrue("块里要看得见「最后更新」", text.contains("最后更新"))
        assertTrue("三条判据逐条列出", text.contains("破坏性动作必须人确认"))
        assertTrue("全文路径要写出来（摘要不贴全文）", text.contains("判决经验账.jsonl"))
    }

    // ── 判据②：同版本两轮不重复增长（哈希去重）────────────────────────

    @Test
    fun `同版本两轮不增长`() {
        val req = input()
        val now = 11L * day
        val first = CriteriaPitPack.render(req, now)
        val second = CriteriaPitPack.render(req, now + 3_600_000L)
        assertEquals("同一个版本键 ⇒ 逐字节相同（不随轮次漂）", first, second)
        // 键里含**账龄（按天）**⇒ 同一天内两轮同键，跨天必须换键（不然「你的坑账是旧的」那句永远发不出去）。
        assertEquals(
            "同一天内（隔一小时）版本键不许变",
            CriteriaPitPack.versionKey(req, now),
            CriteriaPitPack.versionKey(req, now + 3_600_000L),
        )
        assertNotEquals(
            "跨一天账龄 +1 ⇒ 版本键要变（文本跟着换，不是每轮漂）",
            CriteriaPitPack.versionKey(req, now),
            CriteriaPitPack.versionKey(req, now + day),
        )
        // 第二轮不许"再加一份"：块里标题只出现一次
        assertEquals("标题只出现一次（不重复堆叠）", 1, first.split("## 规矩与坑账").size - 1)
    }

    @Test
    fun `版本变了才换文本`() {
        val now = 11L * day
        val before = CriteriaPitPack.versionKey(input(), now)
        val after = CriteriaPitPack.versionKey(input(pitTotal = 13, pits = input().pits), now)
        assertFalse("坑账多了一条 ⇒ 版本键必须变（否则新坑进不了 system）", before == after)
    }

    // ── 判据③：过期提示 ────────────────────────────────────────────────

    @Test
    fun `过期提示`() {
        val stale = input(lastUpdatedAtMs = 0L)
        val text = CriteriaPitPack.render(stale, nowMs = (CriteriaPitPack.STALE_DAYS + 5) * day)
        assertTrue("过期要说「你的坑账是旧的」（用户原话）", text.contains("你的坑账是旧的"))
        assertTrue("要把账龄天数说出来，不许只喊「旧」", text.contains("95 天"))
        assertTrue("过期的判据为真", CriteriaPitPack.stale(stale, (CriteriaPitPack.STALE_DAYS + 5) * day))
    }

    @Test
    fun `没过期不许谎报`() {
        val fresh = input(lastUpdatedAtMs = 0L)
        val text = CriteriaPitPack.render(fresh, nowMs = 30L * day)
        assertFalse("30 天 < 90 天阈值 ⇒ 不许说旧", text.contains("你的坑账是旧的"))
    }

    @Test
    fun `空账不谎报旧账`() {
        // 一条都没落账 ≠ 账旧了。把"空"说成"旧"是假话（用户最反感"看着像其实不是"）。
        val empty = input(pitTotal = 0, pits = emptyList(), lastUpdatedAtMs = null)
        val text = CriteriaPitPack.render(empty, nowMs = 1_000L * day)
        assertFalse("空账不许出现「是旧的」", text.contains("你的坑账是旧的"))
        assertTrue("空账要如实说没有", text.contains("0 条"))
        assertEquals("空账的账龄口径是 -1（不是「很大」）", -1L, CriteriaPitPack.ageDays(empty, 1_000L * day))
    }

    @Test
    fun `阈值边界是第九十天不报第九十一天报`() {
        val old = input(lastUpdatedAtMs = 0L)
        assertFalse(CriteriaPitPack.stale(old, CriteriaPitPack.STALE_DAYS * day))
        assertTrue(CriteriaPitPack.stale(old, (CriteriaPitPack.STALE_DAYS + 1) * day))
    }

    // ── 判据④：总长度上限，超了从最不相关那头丢 ───────────────────────

    @Test
    fun `长度上限从最不相关那头丢`() {
        val long = (0 until 40).map {
            pit(atMs = (10L - it) * day, label = "卡$it", why = "很长".repeat(40))
        }
        val text = CriteriaPitPack.render(input(pitTotal = 40, pits = long), nowMs = 11L * day)
        assertTrue("超上限了：${text.length} 字符（上限 ${CriteriaPitPack.MAX_CHARS}）", text.length <= CriteriaPitPack.MAX_CHARS)
        // 丢的是尾巴（第 5 条之后），留下的必须是排在前面的那几条
        assertTrue("最相关那条（卡0）要留在块里", text.contains("卡0"))
        assertTrue("判据三条不受截断影响", text.contains("紧急卡必须人批"))
        assertTrue("没列的要如实报数", text.contains("条没列"))
    }

    // ── 取数口径：先分等级（违规 > 归因 > 重复问 > 其余），同级里新的在前 ──

    @Test
    fun `取数口径先分是不是坑`() {
        val rows = listOf(
            ExperienceRow("r1", atMs = 100L * day, kind = ExperienceKind.SIGNAL, cardClass = "弹卡", key = "k", reason = "信号", evidence = listOf("e")),
            ExperienceRow("r2", atMs = 1L * day, kind = ExperienceKind.VIOLATION, cardClass = "学习器", key = "k", reason = "撞法条", evidence = listOf("e")),
            ExperienceRow("r3", atMs = 50L * day, kind = ExperienceKind.ATTRIBUTION, cardClass = "反思", key = "k", reason = "判错归因", evidence = listOf("e")),
        )
        val ordered = CriteriaPitPack.orderPits(rows)
        assertEquals("违规/归因排在信号之前（哪怕它更旧）", "violation 学习器", ordered.first().label)
        assertEquals("同为坑 ⇒ 新的在前", "attribution 反思", ordered[1].label)
        assertEquals("信号不是坑 ⇒ 垫底，它最新也不许进前两条", "signal 弹卡", ordered[2].label)
    }

    // ── 装配壳：真源 + 去重缓存 ────────────────────────────────────────

    @Test
    fun `装配壳同版本两轮返回同一串`() {
        JudgmentExperienceLedger.resetForTest()
        CriteriaPitPack.resetForTest()
        JudgmentExperienceLedger.append(
            ExperienceRow(
                id = "pack-test-1", atMs = System.currentTimeMillis(), kind = ExperienceKind.VIOLATION,
                cardClass = "同类卡", key = "k", reason = "被关三次还问", evidence = listOf("判决经验账"),
            ),
        )
        val first = CriteriaPitPack.systemSuffix()
        val second = CriteriaPitPack.systemSuffix()
        assertEquals("同版本第二轮不增长", first, second)
        assertTrue("壳出来的块也带版本号", first.contains("运行时包 v${CriteriaPitPack.VERSION}"))
        JudgmentExperienceLedger.append(
            ExperienceRow(
                id = "pack-test-2", atMs = System.currentTimeMillis() + 1, kind = ExperienceKind.REASK,
                cardClass = "重复问", key = "k", reason = "压错了要回补", evidence = listOf("判决经验账"),
            ),
        )
        val third = CriteriaPitPack.systemSuffix()
        assertTextChanged(first, third)
        JudgmentExperienceLedger.resetForTest()
        CriteriaPitPack.resetForTest()
    }

    /** 单独抽出来，免得把断言写成 void 却被读成"没断言"。 */
    private fun assertTextChanged(a: String, b: String) {
        assertFalse("坑账多了一条 ⇒ 版本变了 ⇒ 文本要换（不是继续发旧的那份）", a == b)
    }

    // ── 本批两条边界：只进 system、零拦截 ──────────────────────────────

    /** 包内源码（包目录可能从仓根或从模块目录起，照本仓 `repoFile` 那条口径两处都试）。 */
    private fun source(rel: String): String {
        val roots = listOf(
            File("src/main/java/com/example/zhengdao/baopu"),
            File("app/src/main/java/com/example/zhengdao/baopu"),
        )
        val dir = roots.firstOrNull { it.isDirectory } ?: error("找不到 baopu 包目录（cwd=${File("").absolutePath}）")
        val f = File(dir, rel)
        assertTrue("找不到源文件：${f.path}", f.isFile)
        return f.readText()
    }

    private fun stripped(src: String): String = src
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)//.*$"), "")

    @Test
    fun `落点是system不是user`() {
        val engine = source("agent/RunEngine.kt")
        val at = engine.indexOf("CriteriaPitPack.systemSuffix()")
        assertTrue("RunEngine 里没有运行时包的注入点（接线断了）", at > 0)
        val window = engine.substring(maxOf(0, at - 600), minOf(engine.length, at + 200))
        assertTrue("注入必须挂在 systemFinal 那条表达式上", window.contains("val systemFinal = if (prefix.isBlank())"))
        assertTrue("注入在 ROLE_SYSTEM 之前、PROMPT_CONTRACT 之后（尾巴追加）", window.contains("PROMPT_CONTRACT"))
        // 反向钉：不许把它塞进本轮 user 消息（LlmMessage ROLE_USER 的 content 是 rest）
        val userLine = engine.lines().firstOrNull { it.contains("role = LlmMessage.ROLE_USER") } ?: ""
        assertFalse("运行时包不许混进 user 消息", userLine.contains("CriteriaPitPack"))
    }

    @Test
    fun `零拦截_不碰守卫`() {
        // 先剥注释再断言：本批的说明文字里就写着"不 import GuardPipeline"，不剥会自己拦自己
        // （同 BaopuPresetGuardTest.stripped 的口径）。
        val code = stripped(source("CriteriaPitPack.kt"))
        listOf("GuardPipeline", "ConfirmFlow", "RiskGate", "Result.Rejected", "NeedsConfirm").forEach {
            assertFalse("运行时包引用了守卫/闸门（本批判据④：只加内容不拦任何东西）：$it", code.contains(it))
        }
        // 纯判据段零时钟：整本文件里 System.currentTimeMillis 只许出现一次（装配壳的默认参数）
        val clockReads = Regex("System\\.currentTimeMillis\\(\\)").findAll(code).count()
        assertEquals("零时钟口径：只有装配壳的默认参数读一次时钟", 1, clockReads)
    }

    @Test
    fun `负例_把阈值当等号会漏报`() {
        // 旧形态：`ageDays >= STALE_DAYS` 写成"永远不报"（把判据注掉）——本用例证明判据真的会红。
        val broken = input(lastUpdatedAtMs = 0L)
        assertFalse("负例自证：账龄 0 天本来就不该报旧", CriteriaPitPack.stale(broken, 0L))
        assertTrue("负例自证：账龄 91 天必须报旧", CriteriaPitPack.stale(broken, 91L * day))
    }
}
