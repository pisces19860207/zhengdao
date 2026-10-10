// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.ui

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Agent 账本（[AgentLedger]）单测——`render` / `parse` 两个纯函数，以及「恢复全部」候选的筛选。
 *
 * 这份 JSON 是**重装 App 后"恢复全部"的唯一依据**，而它会躺在公共目录里被：
 * 用户在文件管理器里手改、云盘同步到半截、旧版本 App 写成别的结构。
 * 所以解析必须**永不抛异常**，读不懂就当没装过（宁可不恢复，也不能让主页崩）。
 */
class AgentLedgerTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `render 与 parse 来回一趟不丢信息`() {
        val list = listOf(
            AgentLedger.Entry("claude-code", "Claude Code", now - 1000, AgentLedger.STATE_INSTALLED),
            AgentLedger.Entry("hermes", "Hermes", now, AgentLedger.STATE_INSTALLING),
        )
        val json = AgentLedger.render(list, now)

        // 结构是给人和给旧版本 App 都能读的：schema + updatedAt + agents 数组
        val root = JSONObject(json)
        assertEquals(1, root.getInt("schema"))
        assertEquals(now, root.getLong("updatedAt"))
        assertEquals(2, root.getJSONArray("agents").length())

        assertEquals(list, AgentLedger.parse(json))
    }

    @Test
    fun `坏账本一律降级成空表而不是抛异常`() {
        assertEquals(emptyList<AgentLedger.Entry>(), AgentLedger.parse(null))
        assertEquals(emptyList<AgentLedger.Entry>(), AgentLedger.parse(""))
        assertEquals(emptyList<AgentLedger.Entry>(), AgentLedger.parse("   "))
        assertEquals(emptyList<AgentLedger.Entry>(), AgentLedger.parse("不是 JSON"))
        assertEquals(emptyList<AgentLedger.Entry>(), AgentLedger.parse("{\"agents\""))
        // 合法 JSON 但没有 agents 字段（比如用户手改成了别的结构）
        assertEquals(emptyList<AgentLedger.Entry>(), AgentLedger.parse("{\"schema\":1}"))
    }

    @Test
    fun `缺字段的条目被跳过或补默认值`() {
        // id 缺失 ⇒ 这条没法用来恢复，跳过；id 在但 name/state 缺失 ⇒ 用 id / installed 兜底
        val json = """
            {"schema":1,"updatedAt":$now,"agents":[
              {"name":"没有 id"},
              {"id":"hermes"},
              {"id":"agy","name":"Antigravity","at":123,"state":"installing"}
            ]}
        """.trimIndent()

        val list = AgentLedger.parse(json)

        assertEquals(2, list.size)
        assertEquals("hermes", list[0].id)
        assertEquals("hermes", list[0].name)
        assertEquals(AgentLedger.STATE_INSTALLED, list[0].state)
        assertEquals(0L, list[0].at)
        assertEquals(123L, list[1].at)
        assertEquals("installing", list[1].state)
    }

    @Test
    fun `账本里不写任何凭据类字段`() {
        // 用户拍板的边界：公共目录只放"装过什么"，API key / 配置留在私有 home。
        // 这条单测是防回归的：谁往 Entry 里加 key/token/config 字段，这里会红。
        // （`$stable` 是 Compose 编译器给本模块类生成的稳定性字段，不算业务字段，滤掉。）
        val fields = AgentLedger.Entry::class.java.declaredFields
            .map { it.name }
            .filterNot { it.startsWith("$") || it.startsWith("this$") }
            .toSet()
        assertEquals(setOf("id", "name", "at", "state"), fields)

        val json = AgentLedger.render(
            listOf(AgentLedger.Entry("hermes", "Hermes", now, AgentLedger.STATE_INSTALLED)),
            now,
        )
        assertTrue(!json.contains("key", ignoreCase = true))
        assertTrue(!json.contains("token", ignoreCase = true))
        assertTrue(!json.contains("secret", ignoreCase = true))
    }

    private fun agent(
        id: String,
        installed: Boolean = false,
        installCmd: String? = "curl -fsSL https://example.invalid/$id.sh | bash",
        restorable: Boolean = true,
    ) = AppState.AgentInfo(
        id = id,
        name = id,
        desc = "",
        launchCmd = id,
        installCmd = installCmd,
        installed = installed,
        restorable = restorable,
    )

    @Test
    fun `恢复候选只收「账本里有、现在探测不到、且有安装命令」的`() {
        val ledgerIds = setOf("claude-code", "hermes", "antigravity", "no-cmd")
        val agents = listOf(
            agent("claude-code"),                             // 账本里有 + 没装 + 有命令 + 可恢复 ⇒ 候选
            agent("hermes", restorable = false),              // 依赖链重（Python/uv/venv）⇒ 剔除，见 E-081
            agent("antigravity", restorable = false),         // 受限网络，用户拍板不恢复 ⇒ 剔除，见 E-040
            agent("not-in-ledger"),                          // 账本里没有 ⇒ 剔除
            agent("installed-one", installed = true),         // 现在装着 ⇒ 剔除
            agent("no-cmd", installCmd = null),               // 没有安装命令 ⇒ 剔除
        )

        assertEquals(
            listOf("claude-code"),
            AgentLedger.pickRestoreCandidates(ledgerIds, agents).map { it.id },
        )
    }

    @Test
    fun `关掉恢复入口的 Agent 仍然会出现在清单里（安装卡片不受影响）`() {
        // restorable 只影响「恢复全部」的候选：清单本身、安装命令、探测结果都不动。
        val agents = listOf(agent("hermes", restorable = false), agent("claude-code"))
        assertEquals(listOf("hermes", "claude-code"), agents.map { it.id })
        assertEquals(2, agents.count { it.installCmd != null })
        assertEquals(
            listOf("claude-code"),
            AgentLedger.pickRestoreCandidates(setOf("hermes", "claude-code"), agents).map { it.id },
        )
    }

    @Test
    fun `恢复候选按清单顺序而不是账本顺序`() {
        val agents = listOf(agent("a"), agent("b"), agent("c"))
        // 账本里是乱序集合，候选顺序必须跟 agents 走（= 用户当初安装的顺序）
        assertEquals(
            listOf("a", "b", "c"),
            AgentLedger.pickRestoreCandidates(setOf("c", "a", "b"), agents).map { it.id },
        )
    }
}
