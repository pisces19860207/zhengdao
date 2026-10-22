// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第一百二十二批 · 政策表 v1：安装/回退/审计的进程级语义。
//
// 三条要钉死的：
// 1. 表缺失 ⇒ 回**内置默认**（不是「保留上回那份」——缺文件本身就是异常）；
// 2. 表非法 ⇒ **整张拒绝**，保留当前这一份（启动时就是默认）；
// 3. 两种回退都留一行 `[policy] …` 审计，且一个换行都不许有。
package com.example.zhengdao.baopu.policy

import com.example.zhengdao.baopu.EvolutionRisk
import com.example.zhengdao.baopu.EvolutionRiskCategory
import com.example.zhengdao.baopu.agent.ToolGroup
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyRuntimeTest {

    @After
    fun tearDown() {
        PolicyRuntime.reset()
    }

    private fun stricter(): Policy {
        val risks = EvolutionRiskCategory.entries.associateWith { it.riskClass }.toMutableMap()
        risks[EvolutionRiskCategory.PROMPT_TWEAK] = EvolutionRisk.HIGH
        return Policy(
            version = Policy.VERSION,
            toolGroups = ToolGroupPolicy(setOf(ToolGroup.CORE, ToolGroup.COMPAT)),
            approval = ApprovalPolicy(groupReaskThreshold = 1, riskLevels = risks),
            eval = EvalPolicy(floor = 0.95, minEffectSamples = 5),
        )
    }

    @Test
    fun `初始状态是内置默认`() {
        assertEquals(Policy.DEFAULT, PolicyRuntime.current())
        assertEquals(PolicyState.Status.DEFAULT, PolicyRuntime.state().status)
        assertEquals(Policy.DEFAULT, PolicyRuntime.state().policy)
    }

    @Test
    fun `好表安装后current换成表里的值_审计写清版本与来源`() {
        val p = stricter()
        val line = PolicyRuntime.install(PolicyTable.Load.Loaded(p, "assets/baopu/policy_v1.json"))
        assertEquals(p, PolicyRuntime.current())
        assertEquals(PolicyState.Status.LOADED, PolicyRuntime.state().status)
        assertEquals("assets/baopu/policy_v1.json", PolicyRuntime.state().source)
        assertTrue("审计锚：$line", line.startsWith("[policy] "))
        assertTrue("版本要进账：$line", line.contains("v1 已加载"))
        assertTrue("来源要进账：$line", line.contains("assets/baopu/policy_v1.json"))
        assertTrue("生效值要能一眼看见：$line", line.contains("组闸=1"))
        assertTrue("eval 阈值要能一眼看见：$line", line.contains("eval=0.95/5"))
        assertFalse("审计一行一条：$line", line.contains("\n") || line.contains("\r"))
    }

    @Test
    fun `坏表整张拒绝_保留当前这一份`() {
        val good = stricter()
        PolicyRuntime.install(PolicyTable.Load.Loaded(good, "good"))
        val line = PolicyRuntime.install(PolicyTable.Load.Invalid("坏表\n第二行", "bad"))
        assertEquals("坏表不许把好表冲掉", good, PolicyRuntime.current())
        assertEquals(PolicyState.Status.INVALID, PolicyRuntime.state().status)
        assertEquals("bad", PolicyRuntime.state().source)
        assertTrue("审计必须说拒绝：$line", line.contains("拒绝"))
        assertTrue("原因要进账：$line", line.contains("坏表"))
        assertFalse("原因里的换行必须压平：$line", line.contains("\n") || line.contains("\r"))
    }

    @Test
    fun `缺表回内置默认_并明确说未放宽`() {
        PolicyRuntime.install(PolicyTable.Load.Loaded(stricter(), "good"))
        val line = PolicyRuntime.install(PolicyTable.Load.Missing("assets/baopu/policy_v1.json"))
        assertEquals(Policy.DEFAULT, PolicyRuntime.current())
        assertEquals(PolicyState.Status.MISSING, PolicyRuntime.state().status)
        assertTrue("审计必须说缺表：$line", line.contains("未找到"))
        assertTrue("必须说按默认执行：$line", line.contains("按内置默认执行"))
        assertTrue("必须说不许放宽：$line", line.contains("未放宽"))
        assertFalse(line.contains("\n") || line.contains("\r"))
    }

    @Test
    fun `启动时坏表等于默认_不会因为坏表起不来`() {
        val line = PolicyRuntime.install(PolicyTable.Load.Invalid("版本不认识", "assets/baopu/policy_v1.json"))
        assertEquals(Policy.DEFAULT, PolicyRuntime.current())
        assertEquals(PolicyState.Status.INVALID, PolicyRuntime.state().status)
        assertTrue(line.contains("按当前政策执行"))
        assertTrue(line.contains("未放宽"))
    }

    @Test
    fun `reset把状态清回默认`() {
        PolicyRuntime.install(PolicyTable.Load.Loaded(stricter(), "good"))
        PolicyRuntime.reset()
        assertEquals(Policy.DEFAULT, PolicyRuntime.current())
        assertEquals(PolicyState.Status.DEFAULT, PolicyRuntime.state().status)
        assertEquals(null, PolicyRuntime.state().source)
    }
}
