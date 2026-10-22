// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第一百二十二批 · 政策表 v1：读表器/typed 模型的边界与负例。
//
// 这份用例只做一件事：把「表里写错一个字就整张拒绝」钉死。
// 尤其是三条负例——越权默认可见、风险降级、未知键——它们各自对应一种
// 「看着能用、其实已经把 A 的边界放宽了」的坏表。
package com.example.zhengdao.baopu.policy

import com.example.zhengdao.baopu.EvolutionRisk
import com.example.zhengdao.baopu.EvolutionRiskCategory
import com.example.zhengdao.baopu.agent.ToolGroup
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PolicyTableTest {

    private val assetPath = "src/main/assets/baopu/policy_v1.json"

    private fun assetText(): String =
        File(assetPath).takeIf { it.isFile }?.readText(Charsets.UTF_8)
            ?: File("app/$assetPath").readText(Charsets.UTF_8)

    private fun mutate(block: JSONObject.() -> Unit): String =
        JSONObject(assetText()).apply(block).toString()

    private fun reject(text: String, mustContain: String) {
        val e = assertThrows(IllegalArgumentException::class.java) { PolicyTable.parse(text) }
        val message = e.message.orEmpty()
        assertTrue("拒绝原因要能看懂：$message", message.contains(mustContain))
    }

    // ── 正例：落盘表与内置默认逐字段一致 ──────────────────────────────────

    @Test
    fun `落盘表能读通且逐字段等于内置默认`() {
        val p = PolicyTable.parse(assetText())
        assertEquals(Policy.DEFAULT, p)
        assertEquals(1, p.version)
        assertEquals(setOf(ToolGroup.CORE, ToolGroup.EDIT, ToolGroup.COMPAT), p.toolGroups.defaultVisible)
        assertEquals(3, p.approval.groupReaskThreshold)
        assertEquals(0.8, p.eval.floor, 0.0)
        assertEquals(3, p.eval.minEffectSamples)
        EvolutionRiskCategory.entries.forEach { c ->
            assertEquals("${c.name} 的默认等级必须与代码一致", c.riskClass, p.riskOf(c))
        }
    }

    @Test
    fun `load三态_好表是Loaded`() {
        val result = PolicyTable.load("test/asset") { assetText() }
        assertTrue("好表必须是 Loaded", result is PolicyTable.Load.Loaded)
        assertEquals(Policy.DEFAULT, (result as PolicyTable.Load.Loaded).policy)
        assertEquals("test/asset", result.source)
    }

    @Test
    fun `load三态_读不到是Missing`() {
        val result = PolicyTable.load("test/absent") { null }
        assertTrue("读不到必须是 Missing", result is PolicyTable.Load.Missing)
        assertEquals("test/absent", result.source)
    }

    @Test
    fun `load三态_读抛是不合格而不是Missing`() {
        val result = PolicyTable.load("test/throw") { error("盘坏了") }
        assertTrue("读取失败必须按不合格处理", result is PolicyTable.Load.Invalid)
        assertTrue((result as PolicyTable.Load.Invalid).reason.contains("读取失败"))
    }

    @Test
    fun `load三态_坏JSON不抛而是Invalid`() {
        val result = PolicyTable.load("test/bad") { "{ 这不是 JSON" }
        assertTrue("坏 JSON 必须返回 Invalid，不能把异常抛给启动链", result is PolicyTable.Load.Invalid)
        assertTrue((result as PolicyTable.Load.Invalid).reason.contains("不是合法 JSON"))
    }

    // ── 身份：schema / version / 未知键 ───────────────────────────────────

    @Test
    fun `schema不对整张拒绝`() {
        reject(mutate { put("schema", "some-other-policy") }, "schema 必须是")
    }

    @Test
    fun `版本不认整张拒绝`() {
        reject(mutate { put("version", 2) }, "版本不认识")
    }

    @Test
    fun `版本必须是整数_字符串1也不行`() {
        reject(mutate { put("version", "1") }, "必须是整数")
    }

    @Test
    fun `缺schema整张拒绝`() {
        reject(mutate { remove("schema") }, "缺失")
    }

    @Test
    fun `未知顶层键整张拒绝_不扩DSL`() {
        reject(mutate { put("guardBypass", true) }, "不认识的键")
    }

    @Test
    fun `未知分段键也拒绝`() {
        reject(mutate { getJSONObject("approval").put("autoApprove", true) }, "不认识的键")
        reject(mutate { getJSONObject("eval").put("ignoreFailures", true) }, "不认识的键")
    }

    @Test
    fun `description可省但写了必须是字符串`() {
        val noDescription = JSONObject(assetText()).apply { remove("description") }.toString()
        assertEquals(Policy.DEFAULT, PolicyTable.parse(noDescription))
        reject(mutate { put("description", 42) }, "必须是字符串")
    }

    // ── 工具组：默认可见的硬底线 ─────────────────────────────────────────

    @Test
    fun `默认可见缺core拒绝_capabilities会跟着消失`() {
        reject(
            mutate {
                getJSONObject("toolGroups").put("defaultVisible", JSONArray(listOf("edit", "compat")))
            },
            "必须含 core",
        )
    }

    @Test
    fun `默认可见缺compat拒绝_免费档会403`() {
        reject(
            mutate {
                getJSONObject("toolGroups").put("defaultVisible", JSONArray(listOf("core", "edit")))
            },
            "必须含 compat",
        )
    }

    @Test
    fun `默认可见塞进browser或mcp拒绝_工具组不是表能开的`() {
        listOf("browser", "files", "library", "card", "skill", "preview", "mcp", "agent").forEach { key ->
            reject(
                mutate {
                    getJSONObject("toolGroups").put("defaultVisible", JSONArray(listOf("core", "compat", key)))
                },
                "不许默认可见",
            )
        }
    }

    @Test
    fun `默认可见重复组拒绝`() {
        reject(
            mutate {
                getJSONObject("toolGroups").put("defaultVisible", JSONArray(listOf("core", "core", "compat")))
            },
            "重复组",
        )
    }

    @Test
    fun `默认可见可以收紧到core加compat_去掉edit是允许的`() {
        val p = PolicyTable.parse(
            mutate {
                getJSONObject("toolGroups").put("defaultVisible", JSONArray(listOf("core", "compat")))
            },
        )
        assertEquals(setOf(ToolGroup.CORE, ToolGroup.COMPAT), p.toolGroups.defaultVisible)
    }

    // ── 组闸阈值 ─────────────────────────────────────────────────────────

    @Test
    fun `组闸阈值边界1和10收下_0和11拒绝`() {
        listOf(1, 10).forEach { n ->
            val p = PolicyTable.parse(mutate { getJSONObject("approval").put("groupReaskThreshold", n) })
            assertEquals(n, p.approval.groupReaskThreshold)
        }
        listOf(0, 11).forEach { n ->
            reject(mutate { getJSONObject("approval").put("groupReaskThreshold", n) }, "越界")
        }
    }

    @Test
    fun `组闸阈值必须是整数_3点5拒绝`() {
        reject(
            mutate { getJSONObject("approval").put("groupReaskThreshold", 3.5) },
            "必须是整数",
        )
    }

    // ── 风险分级：只能收紧，不能放宽 ─────────────────────────────────────

    @Test
    fun `高风险类别降到MEDIUM拒绝`() {
        listOf("PERSONA_CORE", "TOOL_PERMISSION", "ROOTFS_SYSTEM").forEach { category ->
            reject(
                mutate {
                    getJSONObject("approval").getJSONObject("riskLevels").put(category, "MEDIUM")
                },
                "低于代码底线",
            )
        }
    }

    @Test
    fun `中风险类别降到LOW拒绝`() {
        listOf("SKILL_BODY_STRUCTURE", "CROSS_SKILL_IMPACT").forEach { category ->
            reject(
                mutate {
                    getJSONObject("approval").getJSONObject("riskLevels").put(category, "LOW")
                },
                "低于代码底线",
            )
        }
    }

    @Test
    fun `往上抬是允许的_低风险可以变高风险`() {
        val p = PolicyTable.parse(
            mutate {
                getJSONObject("approval").getJSONObject("riskLevels").put("PROMPT_TWEAK", "HIGH")
            },
        )
        assertEquals(EvolutionRisk.HIGH, p.riskOf(EvolutionRiskCategory.PROMPT_TWEAK))
        assertEquals(EvolutionRisk.HIGH, p.riskOf(EvolutionRiskCategory.PERSONA_CORE))
    }

    @Test
    fun `风险映射必须七类齐全_缺一类拒绝`() {
        reject(
            mutate {
                getJSONObject("approval").getJSONObject("riskLevels").remove("PROMPT_TWEAK")
            },
            "缺失",
        )
    }

    @Test
    fun `风险映射不许认不得的类别`() {
        reject(
            mutate {
                getJSONObject("approval").getJSONObject("riskLevels").put("TOOL_PERMISSION_X", "HIGH")
            },
            "不认识的键",
        )
    }

    @Test
    fun `风险等级只认三个大写词`() {
        reject(
            mutate {
                getJSONObject("approval").getJSONObject("riskLevels").put("PROMPT_TWEAK", "high")
            },
            "不是合法等级",
        )
    }

    // ── eval / 效果阈值 ──────────────────────────────────────────────────

    @Test
    fun `eval过线边界_0点5和1收下_0点49和1点01拒绝`() {
        listOf(0.5, 1.0).forEach { v ->
            val p = PolicyTable.parse(mutate { getJSONObject("eval").put("floor", v) })
            assertEquals(v, p.eval.floor, 0.0)
        }
        listOf(0.49, 1.01).forEach { v ->
            reject(mutate { getJSONObject("eval").put("floor", v) }, "越界")
        }
    }

    @Test
    fun `效果样本下限边界_3和100收下_2和101拒绝`() {
        listOf(3, 100).forEach { n ->
            val p = PolicyTable.parse(mutate { getJSONObject("eval").put("minEffectSamples", n) })
            assertEquals(n, p.eval.minEffectSamples)
        }
        listOf(2, 101).forEach { n ->
            reject(mutate { getJSONObject("eval").put("minEffectSamples", n) }, "越界")
        }
    }

    @Test
    fun `eval字段类型写错拒绝`() {
        reject(mutate { getJSONObject("eval").put("floor", "0.8") }, "必须是数字")
        reject(mutate { getJSONObject("eval").put("minEffectSamples", "3") }, "必须是整数")
    }
}
