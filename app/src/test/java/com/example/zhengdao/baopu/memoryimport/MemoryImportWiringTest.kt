// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.memoryimport

import com.example.zhengdao.baopu.FreeTextMemoryImport
import com.example.zhengdao.baopu.MemoryLayout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 455 批判据的接线侧：451 的 `importFromFiles` 开始有生产调用方之后，
 * 日闸／幂等／不覆盖人工版／失败不崩不编造都要在**接线层**再钉一遍——
 * 451 的测试管解析与落盘本身，这里管「生产怎么调它」。
 *
 * 与平台的分工：Context 胶水（runOnce）不可 JVM 直测（本仓无 Robolectric），
 * 判据全部收在 [FreeTextMemoryWiring.runImportOnce]（纯文件进出）与
 * [MemoryImportRules]（纯函数）；Context 侧只做路径解析与落戳。
 */
class MemoryImportWiringTest {

    private val tmp: MutableList<File> = mutableListOf()

    private fun tempDir(): File = Files.createTempDirectory("zd455").toFile().also { tmp += it }

    @After
    fun clean() {
        tmp.forEach { runCatching { it.deleteRecursively() } }
        // Rig 在 companion 里建临时目录（嵌套类摸不到实例的 tmp），一并收干净，不留 %TEMP% 垃圾。
        leftOvers.forEach { runCatching { it.deleteRecursively() } }
        leftOvers.clear()
    }

    // ── 一件测试家当：两份源 ＋ 两个真目录 ＋ 暂存根 ─────────────────────────────

    private class Rig(val logs: MutableList<String> = mutableListOf()) {
        val root = Files.createTempDirectory("zd455-rig").toFile().also { leftOvers += it }
        val globalSrc = File(root, "全局记忆.md").apply { writeText(GLOBAL_TEXT) }
        val projectSrc = File(root, "项目记忆.md").apply { writeText(PROJECT_TEXT) }
        val globalOut = File(root, "global-out")
        val projectOut = File(root, "project-out")
        val staging = File(root, "staging")

        fun run(readOk: (File) -> Boolean = { f -> runCatching { f.readText(Charsets.UTF_8) }.isSuccess }) =
            FreeTextMemoryWiring.runImportOnce(
                globalSource = globalSrc,
                projectSource = projectSrc,
                globalOut = globalOut,
                projectOut = projectOut,
                stagingRoot = staging,
                log = { logs += it },
                readOk = readOk,
            )

        fun snapshot(): Map<String, String> =
            listOf(globalOut, projectOut).flatMap { dir ->
                dir.listFiles().orEmpty().filter { it.isFile }.map { "${dir.name}/${it.name}" to it.readText(Charsets.UTF_8) }
            }.toMap()
    }

    // ── 日闸（判据①的前半句：上次导入早于今天） ────────────────────────────────

    @Test
    fun `日闸_从没导过和昨天导过都该跑_今天导过就不跑`() {
        val now = 1_799_000_000_000L // 任意时刻；边界都用相对关系断言，不写死时区
        val today0 = MemoryImportRules.startOfDay(now)
        assertTrue("从没导过 ⇒ 该扫", MemoryImportRules.dueByDay(0L, now))
        assertTrue("昨天 23:59:59.999 ⇒ 该扫", MemoryImportRules.dueByDay(today0 - 1, now))
        assertFalse("今天零点整 ⇒ 不扫（已经算今天导过）", MemoryImportRules.dueByDay(today0, now))
        assertFalse("刚刚导过 ⇒ 不扫", MemoryImportRules.dueByDay(now, now))
        assertTrue("早于今天零点一毫秒都算「早于今天」", MemoryImportRules.dueByDay(today0 - 1, now))
    }

    @Test
    fun `零点用日历字段清零_不是24小时减法`() {
        val now = 1_799_000_000_000L
        val today0 = MemoryImportRules.startOfDay(now)
        assertTrue("零点必须 ≤ now", today0 <= now)
        assertTrue("零点之后一天之内", now - today0 < 24 * 60 * 60 * 1000L)
        assertEquals("零点重复计算要稳定（幂等）", today0, MemoryImportRules.startOfDay(today0))
    }

    // ── 幂等（判据：连跑两次幂等） ──────────────────────────────────────────────

    @Test
    fun `两连跑_真目录字节逐字不变_第二次全部原样`() {
        val rig = Rig()
        val r1 = rig.run()
        val first = rig.snapshot()
        assertTrue("第一轮要真写出条目", r1.written > 0)
        assertEquals("第一轮不该有保留/冲突", 0, r1.kept)
        val r2 = rig.run()
        assertEquals("连跑两次目录内容逐字相同（幂等）", first, rig.snapshot())
        assertEquals("第二轮一个新文件都不写", 0, r2.written)
        assertEquals("第二轮全部逐字一致", r1.written, r2.unchanged)
        assertEquals("两轮收编读数一致", r1.entries, r2.entries)
        assertTrue("第一轮两份回执都要落盘", rig.snapshot().keys.count { it.endsWith(FreeTextMemoryImport.RECEIPT_NAME) } == 2)
    }

    @Test
    fun `行数对账_源总行数等于收编加跳过`() {
        val rig = Rig()
        val r = rig.run()
        assertEquals(
            "一条都不许吞：${r.sourceLines} = ${r.entries} + ${r.skippedLines}",
            r.sourceLines, r.entries + r.skippedLines,
        )
        assertTrue("真实形状总有收编也有跳过（乱行/空行）", r.entries > 0 && r.skippedLines > 0)
    }

    @Test
    fun `作用域路由_全局条目进全局目录_项目条目进项目目录`() {
        val rig = Rig()
        rig.run()
        val globalFiles = rig.globalOut.listFiles().orEmpty().filter { it.extension == "md" }
        val projectFiles = rig.projectOut.listFiles().orEmpty().filter { it.extension == "md" }
        assertTrue("两边都要有条目", globalFiles.isNotEmpty() && projectFiles.isNotEmpty())
        for (f in globalFiles) assertTrue("全局目录里混进了别的 scope：${f.name}", f.readText().contains("scope: global"))
        for (f in projectFiles) assertTrue("项目目录里混进了别的 scope：${f.name}", f.readText().contains("scope: project"))
    }

    // ── 不覆盖人工版（红线：覆盖＝自动改掉用户记忆） ─────────────────────────────

    @Test
    fun `人工确认过的条目不被重导入冲回PENDING_保留并记账`() {
        val rig = Rig()
        rig.run()
        val victim = rig.globalOut.listFiles().orEmpty().first { it.extension == "md" }
        val confirmed = victim.readText(Charsets.UTF_8).replace("status: pending", "status: confirmed")
        assertTrue("前置：模拟确认成功", confirmed != victim.readText(Charsets.UTF_8))
        victim.writeText(confirmed, Charsets.UTF_8)

        val logs = rig.logs
        val r2 = rig.run()
        assertEquals("人工版要被保留", 1, r2.kept)
        assertTrue("文件里还是确认态", victim.readText(Charsets.UTF_8).contains("status: confirmed"))
        assertTrue("要落一行账：${logs.lastOrNull()}", logs.any { it.contains("保留人工版") && it.contains(victim.nameWithoutExtension) })
    }

    // ── 失败不崩不弹（判据：失败只落诊断） ─────────────────────────────────────

    @Test
    fun `读不动的源只落诊断_另一份照收_不算成功不落戳`() {
        val rig = Rig()
        val r = rig.run(readOk = { f -> !f.name.contains("全局") && runCatching { f.readText(Charsets.UTF_8) }.isSuccess })
        assertEquals("读不动的那份要报出来", listOf("全局记忆.md"), r.unreadable)
        assertTrue("另一份照常收编", r.entries > 0)
        assertTrue("不崩：没有失败账（读不动是诊断不是异常）", r.failures.isEmpty())
        assertFalse("读不动就不许落「上次导入」戳（下次冷启要重试）", MemoryImportRules.shouldStamp(r))
        assertTrue("诊断要落日志", rig.logs.any { it.contains("全局记忆.md") && it.contains("读不动") })
    }

    @Test
    fun `暂存写不进去_如实记账不发空话_不崩`() {
        val rig = Rig()
        val blocker = File(rig.root, "staging").apply {
            // 把暂存根变成一个普通文件：下面的子目录建不出来，importInto 写盘必失败
            writeText("not a dir", Charsets.UTF_8)
        }
        assertTrue(blocker.isFile)
        val r = FreeTextMemoryWiring.runImportOnce(
            globalSource = rig.globalSrc,
            projectSource = rig.projectSrc,
            globalOut = rig.globalOut,
            projectOut = rig.projectOut,
            stagingRoot = blocker,
            log = { rig.logs += it },
        )
        assertTrue("写盘失败要进失败账", r.failures.isNotEmpty())
        assertEquals("一个条目都不许报成功", 0, r.entries)
        assertFalse("失败轮不许落戳", MemoryImportRules.shouldStamp(r))
        assertFalse("真目录不许被碰", rig.globalOut.exists() || rig.projectOut.exists())
    }

    @Test
    fun `两份源都不在_零读数_不建目录也不报错`() {
        val rig = Rig()
        val r = FreeTextMemoryWiring.runImportOnce(
            globalSource = null,
            projectSource = null,
            globalOut = rig.globalOut,
            projectOut = rig.projectOut,
            stagingRoot = rig.staging,
            log = { rig.logs += it },
        )
        assertEquals(0, r.entries)
        assertEquals(0, r.sourceLines)
        assertEquals(0, r.skippedLines)
        assertTrue("「没有」不是失败", r.failures.isEmpty() && r.unreadable.isEmpty())
        assertFalse("不该建任何目录", rig.globalOut.exists() || rig.projectOut.exists())
        assertTrue("两份都不在也算导过（如实读数）", MemoryImportRules.shouldStamp(r))
    }

    // ── 不编造 asOf（判据：缺 asOf 留空不编） ──────────────────────────────────

    @Test
    fun `收编文件缺asOf一律待补_不写当下时刻`() {
        val rig = Rig()
        val before = System.currentTimeMillis()
        rig.run()
        for (dir in listOf(rig.globalOut, rig.projectOut)) {
            for (f in dir.listFiles().orEmpty().filter { it.extension == "md" }) {
                val text = f.readText(Charsets.UTF_8)
                assertTrue("${f.name} 要写着没记", text.contains("as_of: 0"))
                assertTrue("${f.name} 出处要标待补", text.contains("source_session: 待补") && text.contains("source_file: 待补"))
                assertTrue("${f.name} 机器收编不自动确认", text.contains("status: pending"))
                assertFalse("${f.name} 不许编造当前时刻", text.contains("as_of: $before"))
            }
        }
    }

    // ── 判据要的日志行（导入 N 行 / 跳过 M / 耗时） ─────────────────────────────

    @Test
    fun `日志行带导入跳过耗时与收编计数`() {
        val rig = Rig()
        val r = rig.run()
        val line = MemoryImportRules.summaryLine(FreeTextMemoryWiring.TRIGGER_COLD_START, r)
        assertTrue("判据原词：$line", line.contains("导入 ${r.entries} 行"))
        assertTrue("判据原词：$line", line.contains("跳过 ${r.skippedLines} 行"))
        assertTrue("判据原词：$line", line.contains("耗时 ${r.elapsedMs} ms"))
        assertTrue("触发方要能看出来：$line", line.contains("[冷启]"))
    }

    // ── 源文件只读（451 纪律，接线层再验一遍） ─────────────────────────────────

    @Test
    fun `接线只读源文件_两份源字节前后一致`() {
        val rig = Rig()
        val gBefore = rig.globalSrc.readBytes()
        val pBefore = rig.projectSrc.readBytes()
        rig.run()
        rig.run()
        assertTrue("全局记忆.md 一个字节没被碰", gBefore.contentEquals(rig.globalSrc.readBytes()))
        assertTrue("项目记忆.md 一个字节没被碰", pBefore.contentEquals(rig.projectSrc.readBytes()))
    }

    // ── 项目记忆源的新旧位置回退 ────────────────────────────────────────────────

    @Test
    fun `项目源_新位置优先_旧根只读回退_都没有为null`() {
        val ws = tempDir()
        assertNull("都没有 ⇒ null", FreeTextMemoryWiring.pickProjectSource(ws))
        val legacy = MemoryLayout.legacyProjectMemoryFile(ws)
        legacy.parentFile?.mkdirs()
        legacy.writeText("口径：旧根的旧账\n", Charsets.UTF_8)
        assertEquals("旧根回退", legacy, FreeTextMemoryWiring.pickProjectSource(ws))
        val fresh = MemoryLayout.projectMemoryFile(ws)
        fresh.parentFile?.mkdirs()
        fresh.writeText("口径：新位置的账\n", Charsets.UTF_8)
        assertEquals("新位置优先", fresh, FreeTextMemoryWiring.pickProjectSource(ws))
    }

    companion object {

        /** Rig 建的临时目录（@After 统一收）。 */
        private val leftOvers: MutableList<File> = mutableListOf()

        /** 真实形状（人手写的自由文本：标题、列表、混排、乱行、空行都在）。 */
        private val GLOBAL_TEXT = """
            # 全局记忆（手写，什么形状都有）

            - 偏好：回答先给结论，再给两三条依据
            * 画像：用户是独立开发者，主力机是安卓
            这行没有键值，是随手写的备忘

            1. 禁止：把密钥写进日志
            术语：抱朴 = 本 App 的 agent 名字
        """.trimIndent() + "\n"

        private val PROJECT_TEXT = """
            规矩：报告只落证道设计报告目录
            口径：行数对账不许四舍五入

            - 要求：每次交付带清洁回执
        """.trimIndent() + "\n"
    }
}
