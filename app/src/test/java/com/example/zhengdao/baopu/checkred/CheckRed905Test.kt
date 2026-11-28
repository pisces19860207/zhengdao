// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第九百零五批 · 判定函数「该红就红」负例夹具。
// 十一条夹具各只坏一处，断言精确到红分支的消息前缀/红码，避免被第二道锁顶替。
// 每条夹具配一次变异：把对应红分支关掉，该夹具必须红；判定逻辑在本单零改。
package com.example.zhengdao.baopu.checkred

import com.example.zhengdao.baopu.consequence.PreventionRules
import com.example.zhengdao.baopu.learn.DraftCheck
import com.example.zhengdao.baopu.learn.SkillDraft
import com.example.zhengdao.baopu.learn.SkillDraftRules
import com.example.zhengdao.baopu.loops.Loop
import com.example.zhengdao.baopu.loops.LoopReadingRow
import com.example.zhengdao.baopu.loops.SourceKind
import com.example.zhengdao.baopu.loops.Status
import com.example.zhengdao.baopu.loops.validate
import com.example.zhengdao.baopu.pack.FactPackWords
import com.example.zhengdao.baopu.pack.FactSlice
import com.example.zhengdao.baopu.preread.OrderIssue
import com.example.zhengdao.baopu.preread.PreReadRules
import com.example.zhengdao.baopu.preread.ReadFirstItem
import com.example.zhengdao.baopu.preread.ReadFirstKind
import com.example.zhengdao.baopu.preread.ReadFirstOrder
import com.example.zhengdao.baopu.state.cavity.CavityFixtures
import com.example.zhengdao.baopu.state.cavity.ProxyLegality
import com.example.zhengdao.baopu.state.geometry.Environment
import com.example.zhengdao.baopu.state.micro.Anchor
import com.example.zhengdao.baopu.state.micro.LinkagePair
import com.example.zhengdao.baopu.state.micro.LinkageSource
import com.example.zhengdao.baopu.state.micro.LinkageTable
import com.example.zhengdao.baopu.state.micro.MicroAnchors
import com.example.zhengdao.baopu.state.persona.SurfaceDeriver
import com.example.zhengdao.baopu.state.persona.TrackStateSnapshot
import com.example.zhengdao.baopu.state.thermo.CremasterTrigger
import com.example.zhengdao.baopu.state.thermo.ThermoChecks
import com.example.zhengdao.baopu.state.thermo.ThermoClock
import com.example.zhengdao.baopu.state.thermo.ThermoCodes
import com.example.zhengdao.baopu.verify.ValueCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckRed905Test {

    /** F01 · 730 真缺口：默认求解器三种恢复档都会回落，走不进红分支。
     *  这里从测试侧临时把恢复预算表改成 5000 拍（只上不落），用完立刻还原；生产判定零改。 */
    @Test
    fun `F01_提睾走完预算还悬着必须红`() {
        val field = ThermoClock::class.java.getDeclaredField("RECOVERY_BEATS")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val table = field.get(ThermoClock) as IntArray
        val saved = table.copyOf()
        try {
            table[0] = 5000
            val issues = ThermoChecks.recoilWorks(CremasterTrigger.COLD, 0)
            assertTrue(
                "恢复预算跑到五千拍还不落必须红：$issues",
                issues.any { it == ThermoCodes.RECOIL_STUCK + ":走完恢复预算未回落到松垂" },
            )
        } finally {
            System.arraycopy(saved, 0, table, 0, saved.size)
        }
    }

    /** F02 · 自查型：MicroAnchors.check() 读的是 ALL 常量，现有「负例」只造了副本，没喂进检查器。
     *  这里临时把一条挂靠声明的 mode 抹空，用完还原。 */
    @Test
    fun `F02_挂靠声明空mode必须红`() {
        val field = MicroAnchors::class.java.getDeclaredField("ALL")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val list = field.get(MicroAnchors) as MutableList<Anchor>
        val saved = list[0]
        try {
            list[0] = saved.copy(mode = "")
            val found = MicroAnchors.check()
            assertTrue(
                "空 mode 必须被 check 自己说出来：$found",
                found.any { it.message.startsWith("挂靠声明存在空 target/mode") },
            )
        } finally {
            list[0] = saved
        }
    }

    /** F03 · 代理体缺关节锚点：判据用 requireNotNull 抛错，文案在测试目录里一次都没出现过。 */
    @Test
    fun `F03_代理体缺关节锚点必须红`() {
        val chain = CavityFixtures.twoLinkChain()
        val proxies = listOf(CavityFixtures.sphereAtZ("a", 2.0))
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            ProxyLegality.check(
                proxies, Environment(0.0, 0.0), chain, chain.pose(listOf(0.0, 0.0)),
                emptyMap(), emptySet(),
            )
        }
        assertTrue(
            "缺锚点必须点名代理体：${thrown.message}",
            thrown.message!!.contains("代理体 a 缺关节锚点，限位项无从转写"),
        )
    }

    /** F04 · 任务单必读条目超过上限。 */
    @Test
    fun `F04_必读条目超上限必须红`() {
        val items = (1..13).map { i ->
            ReadFirstItem(target = "docs/f$i.md", kind = ReadFirstKind.FILE_LINE, why = "")
        }
        val issues = PreReadRules.orderIssues(ReadFirstOrder(taskKey = "905", items = items))
        assertTrue("十三条必读必须判废：$issues", issues.contains(OrderIssue.TOO_MANY_ITEMS))
    }

    /** F05 · 防再发条目整个缺失。 */
    @Test
    fun `F05_没有防再发条目必须红`() {
        val out = mutableListOf<String>()
        PreventionRules.checkEntry(null, out)
        assertTrue("缺条目必须红：$out", out.any { it.startsWith("③ 没有防再发条目") })
    }

    /** F06 · 表层快照轮数为负，入口 require 必须拒。 */
    @Test
    fun `F06_轮数为负必须红`() {
        val snapshot = TrackStateSnapshot(
            salience = 2, stanceLabel = "中立", dStage = 0, turns = -1,
            vetoActive = false, intoxication = 0, fatigue = 0,
        )
        val thrown = assertThrows(IllegalArgumentException::class.java) { SurfaceDeriver.validate(snapshot) }
        assertTrue("轮数为负必须点名：${thrown.message}", thrown.message!!.startsWith("轮数不得为负：-1"))
    }

    /** F07 · 资料片 source 没指回文件和行号；其余字段全合法，坏只坏一处。 */
    @Test
    fun `F07_资料片来源缺文件行号必须红`() {
        val good = FactSlice(
            id = "fact-a", title = "标题", body = "一句短正文", source = "docs/x.md#L1",
            asOf = "2026-11-16", words = listOf("词"),
        )
        val slices = (0 until FactPackWords.MIN_SLICES).map { i ->
            if (i == 0) good.copy(id = "fact-0", source = "没有来源") else good.copy(id = "fact-$i")
        }
        val issues = FactPackWords.validate(slices)
        assertTrue(
            "来源缺文件和行号必须红：$issues",
            issues.any { it.startsWith("fact-0 source 缺来源文件或行号：") },
        )
    }

    /** F08 · 五组联动里来源重复。 */
    @Test
    fun `F08_联动来源重复必须红`() {
        val groups = LinkageTable.GROUPS.toMutableList()
        groups[0] = groups[0].copy(
            source = LinkageSource.PAIN_UP,
            pairs = listOf(LinkagePair("呼吸", "浅而快"), LinkagePair("喉结", "吞咽"), LinkagePair("手指", "抓握")),
        )
        val found = LinkageTable.checkStructure(groups)
        assertTrue(
            "来源重复必须红：$found",
            found.any { it.message.contains("联动来源") && it.message.contains("重复") },
        )
    }

    /** F09 · 草案骨架为空（goal 与 params 都合法）。 */
    @Test
    fun `F09_草案骨架为空必须红`() {
        val draft = SkillDraft(
            id = "d905", goal = "查装车", targetSite = "example.test", params = emptyList(),
            skeleton = emptyList(), variableSteps = emptyList(), mergedFrom = 1,
            sourceSessions = listOf("905"),
        )
        val check = SkillDraftRules.validate(draft)
        assertTrue(
            "骨架为空必须红：$check",
            check is DraftCheck.Invalid && check.problems.any { it.startsWith("skeleton 不能为空") },
        )
    }

    /** F10 · 八环读数行的时间戳非正；其余键位全按 ok 行白名单。 */
    @Test
    fun `F10_时间戳非正必须红`() {
        val row = LoopReadingRow(
            0L, "753-演示", Loop.RETRIEVAL, Status.OK,
            mapOf("hits" to 3, "sources" to listOf("seg-1"), "ms" to 48),
            SourceKind.BIO, "BioLog:测试:第1行", null,
        )
        val problems = validate(row)
        assertTrue("ts 非正必须红：$problems", problems.any { it.startsWith("ts 必须是正的 epoch 毫秒") })
    }

    /** F11 · 文件操作数对账：账本比磁盘多一条，detail 必须逐字点名「只在账本」。 */
    @Test
    fun `F11_只在账本的文件必须红`() {
        val result = ValueCheck.checkFileOps(
            3, listOf("a.txt", "b.txt", "c.txt"), listOf("a.txt", "b.txt"),
        )
        assertEquals(ValueCheck.Status.FAILED, result.status)
        assertTrue("必须点名只在账本：${result.detail}", result.detail.contains("只在账本"))
    }
}
