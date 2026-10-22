// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第一百二十二批 · 政策表 v1：**源码门禁**——表只能喂参数，不能长成第二条权力链。
//
// 三条负例在这里逐条变红：
// 1. 表能绕过 Guard／权限／确认 ⇒ 这些文件根本读不到 PolicyRuntime/PolicyTable；
// 2. 远程未签名更新 ⇒ policy 包除资产壳外零网络，生产里读资产只有一处；
// 3. 模型可写政策 ⇒ 工具实现、设置页都没有写入口；安装点只有启动装配那一处。
package com.example.zhengdao.baopu.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PolicySourceGateTest {

    private fun mainRoot(): File =
        File("src/main").takeIf { it.isDirectory } ?: File("app/src/main")

    private fun allMain(): List<Pair<String, String>> {
        val root = mainRoot()
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(root).path.replace('\\', '/') to it.readText(Charsets.UTF_8) }
            .sortedBy { it.first }
            .toList()
    }

    private fun sitesOf(token: String): List<String> =
        allMain().filter { (_, text) -> text.contains(token) }.map { it.first }

    private fun policyFiles(): List<Pair<String, String>> =
        allMain().filter { (path, _) -> path.startsWith("java/com/example/zhengdao/baopu/policy/") }

    // ── 单一入口／单一安装点 ─────────────────────────────────────────────

    @Test
    fun `全仓只有一个读表入口和一个安装点`() {
        assertEquals(
            "PolicyTable.load 只许 PolicyAssets 调",
            listOf("java/com/example/zhengdao/baopu/policy/PolicyAssets.kt"),
            sitesOf("PolicyTable.load("),
        )
        assertEquals(
            "PolicyRuntime.install 只许启动装配调（设置页/工具/引擎都不许）",
            listOf("java/com/example/zhengdao/baopu/BaopuRuntime.kt"),
            sitesOf("PolicyRuntime.install("),
        )
        assertEquals(
            "读资产只许 PolicyAssets.load 被装配层调用",
            listOf("java/com/example/zhengdao/baopu/BaopuRuntime.kt"),
            sitesOf("PolicyAssets.load("),
        )
    }

    @Test
    fun `政策只被四个执行点读_没有第五处`() {
        assertEquals(
            "PolicyRuntime.current 的调用点必须就是那四个执行点（多一处就是新权力）",
            listOf(
                "java/com/example/zhengdao/baopu/EvolutionPolicy.kt",
                "java/com/example/zhengdao/baopu/agent/ToolGroupGate.kt",
                "java/com/example/zhengdao/baopu/agent/ToolRegistry.kt",
            ),
            sitesOf("PolicyRuntime.current("),
        )
    }

    // ── 负例一：表不能绕过 Guard／权限／确认 ─────────────────────────────

    @Test
    fun `Guard与确认链一个字都不读政策`() {
        listOf(
            "java/com/example/zhengdao/baopu/Guard.kt",
            "java/com/example/zhengdao/baopu/agent/GuardPipeline.kt",
            "java/com/example/zhengdao/baopu/agent/ConfirmFlow.kt",
            "java/com/example/zhengdao/baopu/agent/RunEngine.kt",
        ).forEach { path ->
            val text = allMain().first { it.first == path }.second
            assertFalse("$path 不许读 PolicyRuntime", text.contains("PolicyRuntime"))
            assertFalse("$path 不许读 PolicyTable", text.contains("PolicyTable"))
        }
    }

    @Test
    fun `政策包不引用权限与确认类`() {
        val forbidden = listOf(
            "import com.example.zhengdao.baopu.agent.GuardPipeline",
            "import com.example.zhengdao.baopu.agent.ConfirmFlow",
            "import com.example.zhengdao.baopu.agent.ToolPermission",
            "import com.example.zhengdao.baopu.agent.RunEngine",
            "import com.example.zhengdao.baopu.agent.ToolRunner",
        )
        policyFiles().forEach { (path, text) ->
            forbidden.forEach { token ->
                assertFalse("$path 不许出现 $token——表只喂参数，不碰权力", text.contains(token))
            }
        }
    }

    @Test
    fun `可见性判据仍有三道闸_政策只喂默认那一项`() {
        val reg = allMain().first { it.first.endsWith("baopu/agent/ToolRegistry.kt") }.second
        val body = reg.substringAfter("internal fun isVisibleTool(").substringBefore("\n}")
        assertTrue("单工具开关仍在", body.contains("spec.name !in settings.disabledNames"))
        assertTrue("组开关仍在", body.contains("spec.group !in settings.disabledGroups"))
        assertTrue(
            "默认可见只是三项并集里的一项，政策改不到另外两项",
            body.contains("ToolGroup.DEFAULT_VISIBLE + settings.approvedGroups + unlockedGroups"),
        )
    }

    // ── 负例二：没有远程未签名更新 ───────────────────────────────────────

    @Test
    fun `policy包除资产壳外零Android零IO零网络`() {
        val forbidden = listOf(
            "import android.", "import androidx.", "import java.io.", "import java.net.",
            "HttpURLConnection", "OkHttp", "Socket(", "URL(",
            "FileOutputStream", "FileWriter", "writeText(", "File(",
        )
        policyFiles()
            .filterNot { (path, _) -> path.endsWith("PolicyAssets.kt") }
            .forEach { (path, text) ->
                forbidden.forEach { token ->
                    assertFalse("$path 不许出现 $token（政策判据必须 JVM 直测、只读）", text.contains(token))
                }
            }
    }

    @Test
    fun `资产壳只读assets_没有网络也没有写盘`() {
        val text = policyFiles().first { it.first.endsWith("PolicyAssets.kt") }.second
        assertTrue("只许从 assets 读", text.contains("context.assets.open(PATH)"))
        listOf("http://", "https://", "URL(", "Socket(", "HttpURLConnection", "OkHttp", "FileOutputStream", "FileWriter")
            .forEach { token ->
                assertFalse("资产壳不许出现 $token", text.contains(token))
            }
        assertFalse("资产壳不写盘", text.contains("writeText(") || text.contains(".edit()"))
    }

    // ── 负例三：模型/设置页不能写政策 ───────────────────────────────────

    @Test
    fun `工具实现与设置页都没有政策写入口`() {
        val offenders = allMain()
            .filter { (path, _) ->
                path.contains("/agent/tools/") ||
                    path.contains("/ui/") ||
                    path.contains("/baopu/agent/")
            }
            .filter { (path, _) ->
                // 两个执行点（ToolRegistry/ToolGroupGate）是合法的读点，不在本条的写入口范围
                !path.endsWith("baopu/agent/ToolRegistry.kt") &&
                    !path.endsWith("baopu/agent/ToolGroupGate.kt")
            }
            .filter { (_, text) ->
                text.contains("PolicyRuntime.install(") ||
                    text.contains("PolicyTable.load(") ||
                    text.contains("PolicyAssets.load(")
            }
            .map { it.first }
        assertEquals("工具/设置页/其它 agent 文件不许安装或读政策资产：$offenders", emptyList<String>(), offenders)
    }

    @Test
    fun `政策资产路径只在资产壳里出现一次`() {
        assertEquals(
            "policy_v1.json 只许 PolicyAssets.PATH 一处引用（工具/设置页拿不到它）",
            listOf("java/com/example/zhengdao/baopu/policy/PolicyAssets.kt"),
            sitesOf("baopu/policy_v1.json"),
        )
    }

    @Test
    fun `policy包没有SharedPreferences也没有用户写口`() {
        policyFiles().forEach { (path, text) ->
            listOf("SharedPreferences", "getSharedPreferences", "putString(", "putBoolean(", "putInt(")
                .forEach { token ->
                    assertFalse("$path 不许出现 $token（v1 不做用户编辑）", text.contains(token))
                }
        }
    }
}
