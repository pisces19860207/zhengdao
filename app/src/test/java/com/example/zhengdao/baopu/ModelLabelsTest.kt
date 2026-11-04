// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百四十三批：模型名映射的判据②③④⑤⑥ + 表结构 + 徽标 + 严格解析负例。
// 表是随包资产，测试用 [ModelLabels.sourceFile] 读**同一份**源码树文件（JVM 无 AssetManager）。
package com.example.zhengdao.baopu

import com.example.zhengdao.ui.baopu.BaopuTopBarRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class ModelLabelsTest {

    @Before
    fun installBundledTable() {
        ModelLabels.install(ModelLabels.loadFromSource())
    }

    private fun src(rel: String): String {
        val f = File("src/main/java/com/example/zhengdao/" + rel)
            .let { if (it.exists()) it else File("app/src/main/java/com/example/zhengdao/" + rel) }
        assertTrue("读不到 $rel（cwd=${File("").absolutePath}）", f.exists())
        return f.readText()
    }

    // ── 判据② 表规模 / 厂商覆盖 / 每条 source + asOf ─────────────────────────

    @Test
    fun 表不少于10条且覆盖4个厂商_每条都有source和asOf() {
        val labels = ModelLabels.loadFromSource()
        assertTrue("表里要 ≥10 条，实得 ${labels.size}", labels.size >= 10)
        val vendors = labels.map { it.vendor }.distinct()
        assertTrue("要覆盖 ≥4 个厂商，实得 $vendors", vendors.size >= 4)
        labels.forEach { l ->
            assertTrue("${l.modelId} 缺 source", l.source.isNotBlank())
            assertTrue("${l.modelId} 缺 asOf", l.asOf.isNotBlank())
            assertTrue("${l.modelId} 缺 displayName", l.displayName.isNotBlank())
            assertTrue("${l.modelId} status 只能 active/legacy", l.status == "active" || l.status == ModelLabels.LEGACY)
            assertFalse("${l.modelId} 显示名不许带 8 位日期构建号", Regex("-\\d{8}").containsMatchIn(l.displayName))
            assertFalse("${l.modelId} 显示名不许带四位构建号", Regex("-\\d{4}\\b").containsMatchIn(l.displayName))
        }
        assertEquals("modelId 不许重复", labels.size, labels.map { it.modelId.lowercase() }.distinct().size)
    }

    @Test
    fun 用户给定的映射逐字对上_DeepSeek按现场裁定用V4_1_Flash() {
        val expected = mapOf(
            "deepseek-flash" to "V4.1 Flash",
            "deepseek-v4-flash" to "V4.1 Flash",
            "deepseek-v4-pro" to "V4 pro",
            "gpt-6.1-sol" to "GPT 6.1 Sol",
            "claude-opus-5.5" to "Opus 5.5",
            "claude-fable-5" to "Fable 5",
            "gemini-3.5-flash" to "Gemini 3.5 Flash",
            "glm-5.2" to "GLM 5.2",
            "glm-5.3-coding" to "GLM 5.3 coding",
            "qwen3.7-max" to "Qwen3.7 Max",
        )
        expected.forEach { (id, name) ->
            assertEquals("$id 的显示名", name, ModelLabels.displayName(id))
            assertEquals("$id 顶栏显示名", name, BaopuTopBarRules.topBarModelName(id, "DeepSeek"))
        }
        // 大小写不敏感（端点偶尔写全大写）
        assertEquals("GPT 6.1 Sol", ModelLabels.displayName("GPT-6.1-SOL"))
    }

    // ── 判据③ 表外 ID 原样，绝不猜、绝不美化 ────────────────────────────────

    @Test
    fun 表外ID原样显示_美化函数不再参与显示名() {
        val unknown = "mystery-model-9901"
        assertEquals(unknown, ModelLabels.displayName(unknown))
        assertEquals(unknown, ModelPickerRules.displayName(unknown))
        assertEquals(unknown, BaopuTopBarRules.topBarModelName(unknown, "DeepSeek"))
        val r = ModelLabels.resolve(unknown)
        assertFalse(r.inTable)
        assertFalse(r.legacy)
        assertNull(r.aliasOf)
        assertEquals(unknown, r.requestId)
        // 负例自证：prettify 会把这串美化成别的字 ⇒ 若它还在显示链路上，本条必红。
        assertEquals("Mystery Model 9901", ModelPickerRules.prettify(unknown))
        assertTrue("prettify 保留为纯函数但不许进显示名",
            ModelPickerRules.prettify(unknown) != ModelPickerRules.displayName(unknown))
        // 端点给的人类名是事实，不是猜 ⇒ 仍是第 1 级
        assertEquals("厂商给的名字", ModelPickerRules.displayName(unknown, "厂商给的名字"))
    }

    // ── 判据④ 已配旧版仍显示并标「旧版」，不隐藏 ────────────────────────────

    @Test
    fun legacy模型仍显示并标旧版_不隐藏() {
        val legacyIds = listOf("gpt-4o-mini", "claude-sonnet-4-5", "gemini-2.5-flash")
        legacyIds.forEach { id ->
            val r = ModelLabels.resolve(id)
            assertTrue("$id 要在表里", r.inTable)
            assertTrue("$id 要标 legacy", r.legacy)
            val shown = ModelLabels.displayName(id)
            assertTrue("$id 要带「旧版」：$shown", shown.contains("旧版"))
            assertFalse("$id 不许被隐藏成空串", shown.isBlank())
            assertTrue("$id 旧版名仍要能看见本体", shown.contains(r.displayName))
        }
        assertEquals("GPT-4o mini · 旧版", BaopuTopBarRules.topBarModelName("gpt-4o-mini", "OpenAI"))
        // 顶栏模型名单：当前用的旧 ID 不在短名单里也要补在最后（434 既有逻辑，443 不许退化成隐藏）
        val rows = BaopuTopBarRules.modelRows(listOf("gpt-6.1-sol"), "gpt-4o-mini", "OpenAI")
        val old = rows.firstOrNull { it.id == "gpt-4o-mini" }
        assertTrue("已配的旧版模型不许被名单过滤掉", old != null)
        assertTrue("名单里那一行也要标旧版", old!!.name.contains("旧版"))
    }

    // ── 判据⑤ -coding / 日期后缀：显示去后缀，请求仍用原 ID ──────────────────

    @Test
    fun 变体后缀显示去后缀_但请求ID原样不变() {
        val cases = listOf(
            "deepseek-v4-flash-20261104" to "V4.1 Flash",
            "deepseek-v4-flash-coding" to "V4.1 Flash",
            "glm-5.2-coding" to "GLM 5.2",
            "gpt-6.1-sol-20261104" to "GPT 6.1 Sol",
        )
        cases.forEach { (id, shown) ->
            assertEquals("$id 显示去后缀", shown, ModelLabels.displayName(id))
            val r = ModelLabels.resolve(id)
            assertEquals("$id 请求仍用原 ID", id, r.requestId)
            assertTrue("$id 要命中表", r.inTable)
            assertFalse("$id 显示名不许把后缀带回来", r.displayName.contains("20261104"))
        }
        // 表内精确条目优先：glm-5.3-coding 逐字按用户给定映射显示
        assertEquals("GLM 5.3 coding", ModelLabels.displayName("glm-5.3-coding"))
        // 去后缀后仍不在表里 ⇒ 原样 ID，绝不猜
        assertEquals("glm-9.9-20261104", ModelLabels.displayName("glm-9.9-20261104"))
    }

    @Test
    fun 显示层没有渗进请求链路() {
        listOf("baopu/LlmClient.kt", "baopu/wire/OpenAiWire.kt", "baopu/wire/GeminiWire.kt").forEach { rel ->
            assertFalse("$rel 不许引用显示层", src(rel).contains("ModelLabels"))
        }
        // 预设只钉真实 ID；显示名解析不改变 Provider.model
        assertEquals("deepseek-flash", Providers.DEEPSEEK.model)
        assertEquals("V4.1 Flash", BaopuTopBarRules.topBarModelName(Providers.DEEPSEEK.model, "DeepSeek"))
        assertEquals("deepseek-flash", Providers.DEEPSEEK.model)
    }

    // ── 判据⑥ 预设无下线 ID；别名可解析 ────────────────────────────────────

    @Test
    fun 预设列表不存在下线ID_deepseek_flash不许单独作preset_model() {
        val dead = setOf("gpt-4o-mini", "claude-sonnet-4-5", "gemini-2.5-flash")
        val allIds = Providers.all().flatMap { listOf(it.model) + ModelPickerRules.presetModels(it) }
        assertTrue("预设里还留着下线 ID：${allIds.filter { it in dead }}", allIds.none { it in dead })
        assertFalse("别名不许单独作 preset.model", Providers.all().any { it.model == "deepseek-v4-flash" })
        assertFalse("别名不许进预设短名单",
            Providers.all().flatMap { ModelPickerRules.presetModels(it) }.contains("deepseek-v4-flash"))
        // OpenRouter 的 :free ID 不在表里是**正确**的：443 规则表外原样显示，不强求入表。
        assertEquals("nvidia/nemotron-3.5-super-vl:free",
            ModelLabels.displayName("nvidia/nemotron-3.5-super-vl:free"))
    }

    @Test
    fun 别名写成alias_of_真实ID且能解析() {
        // 443 现场裁定（2026-11-04）：DeepSeek 没有 V4 flash 这个模型
        // ⇒ 真 ID = deepseek-flash（434 实测 = DeepSeek-V4.1-Flash），
        //   deepseek-v4-flash 只作别名，用于解析已存旧配置。
        val alias = ModelLabels.loadFromSource().first { it.modelId == "deepseek-v4-flash" }
        assertEquals("deepseek-flash", alias.aliasOf)
        assertTrue("source 要逐字写明 alias of", alias.source.contains("alias of deepseek-flash"))
        val r = ModelLabels.resolve("deepseek-v4-flash")
        assertEquals("deepseek-flash", r.aliasOf)
        assertEquals("V4.1 Flash", r.displayName)
        assertEquals("deepseek-v4-flash", r.requestId)
        assertEquals("D", r.badgeLetter)
        assertEquals("真 ID 自己解析也是同一个显示名", "V4.1 Flash", ModelLabels.displayName("deepseek-flash"))
    }

    // ── 徽标：自绘字母 D/O/A/G/Z/Q/K/豆，不用官方 logo ──────────────────────

    @Test
    fun 徽标是自绘字母八颗_不用官方logo() {
        val expected = mapOf(
            "DeepSeek" to "D", "OpenAI" to "O", "Anthropic" to "A",
            "Google" to "G", "Zhipu" to "Z", "Alibaba" to "Q",
            "Kimi" to "K", "豆包" to "豆",
        )
        expected.forEach { (vendor, letter) ->
            assertEquals("$vendor 的徽标", letter, ModelLabels.badgeOf(vendor))
            assertEquals("$vendor 顶栏徽标", letter, BaopuTopBarRules.badgeLetterOf(vendor))
        }
        val area = src("ui/baopu/BaopuModelArea.kt")
        assertFalse("不许用官方 logo 资源", area.contains("painterResource("))
        assertFalse("不许用图标库", area.contains("androidx.compose.material3.Icon"))
        assertTrue("徽标是自绘 Text 字母", area.contains("private fun LetterTile("))
    }

    // ── 严格解析负例：缺 source / asOf、status 越界都必须红 ─────────────────

    @Test
    fun 缺source或asOf或status越界_严格解析必须抛() {
        val base = """{"modelId":"x","displayName":"X","vendor":"V","tier":"t","source":"s","asOf":"2026-11-04","status":"active"}"""
        val bad = listOf(
            base.replace("\"source\":\"s\",", ""),
            base.replace("\"asOf\":\"2026-11-04\",", ""),
            base.replace("\"status\":\"active\"", "\"status\":\"deprecated\""),
            base.replace("\"displayName\":\"X\",", ""),
        )
        bad.forEach { json ->
            assertThrows(IllegalArgumentException::class.java) { ModelLabels.parse("[$json]") }
        }
        assertEquals(1, ModelLabels.parse("[$base]").size)
    }
}
