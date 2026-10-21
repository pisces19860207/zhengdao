// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.terminal

import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 遗留记忆插件一次性迁移单测（第一百二十一批，[LegacyMemMigration]）。
 *
 * 派工单 §2 点名的四条的落地形状：
 * - 旧格式 → 新格式：只摘 `opencode-mem` 自己，其它插件/其它配置项原样保留；
 * - 已迁移不重复：同一文件跑第二次 ⇒ 零改动、不产生第二个备份；
 * - 新数据不被覆盖：非字符串项、别的键、别的插件一个都不许动；
 * - 负例「每次启动重复迁移 ⇒ 红」：文件级幂等（上面第二条）＋ `runOnce` 的
 *   SharedPreferences 短路（源码级门禁，见最后一个用例）两头钉。
 *
 * 另钉三条工程性质：无残留不动盘不备份、坏 JSON 不猜不改、
 * 改写中途失败原文件必须还在（备份可回滚）。
 */
class LegacyMemMigrationTest {

    private val dirs = mutableListOf<File>()

    private fun tempDir(): File =
        Files.createTempDirectory("legacy-mem-test").toFile().also { dirs += it }

    @After
    fun cleanup() {
        dirs.forEach { it.deleteRecursively() }
        dirs.clear()
    }

    private fun File.readJson(): JSONObject = JSONObject(readText())

    @Test
    fun `旧格式到新格式_只摘自己_别的原样保留`() {
        val dir = tempDir()
        val cfg = File(dir, ".config/opencode/opencode.json").apply { parentFile?.mkdirs() }
        cfg.writeText(
            """
            {
              "model": "provider/model",
              "plugin": ["opencode-wakatime", "opencode-mem", "@my-org/custom-plugin"],
              "theme": "dark"
            }
            """.trimIndent(),
        )
        val original = cfg.readText()

        assertTrue("有残留必须返回 true", LegacyMemMigration.migrateConfigFile(cfg, 12345L))

        val out = cfg.readJson()
        val plugin = out.getJSONArray("plugin")
        assertEquals(
            "只摘自己、原序保留",
            listOf("opencode-wakatime", "@my-org/custom-plugin"),
            (0 until plugin.length()).map { plugin.getString(it) },
        )
        assertEquals("别的键不许动", "provider/model", out.getString("model"))
        assertEquals("别的键不许动", "dark", out.getString("theme"))
        // 备份里必须是原件的逐字节拷贝（可回滚）
        val backup = File(cfg.parentFile, "opencode.json.bak-12345")
        assertTrue("改写前必须备份", backup.isFile)
        assertEquals("备份必须与原件逐字节一致", original, backup.readText())
    }

    @Test
    fun `数组里只有它时_删掉plugin键_别的键留着`() {
        val dir = tempDir()
        val cfg = File(dir, "opencode.json")
        cfg.writeText("""{"plugin":["opencode-mem","opencode-mem@latest"],"theme":"dark"}""")

        assertTrue(LegacyMemMigration.migrateConfigFile(cfg, 1L))

        val out = cfg.readJson()
        assertFalse("滤空就删键，不许留空数组", out.has("plugin"))
        assertEquals("dark", out.getString("theme"))
    }

    @Test
    fun `新数据不被覆盖_非字符串项原样保留`() {
        val dir = tempDir()
        val cfg = File(dir, "opencode.json")
        cfg.writeText("""{"plugin":["opencode-mem",42,{"nested":true}],"keep":"me"}""")

        assertTrue(LegacyMemMigration.migrateConfigFile(cfg, 1L))

        val arr = cfg.readJson().getJSONArray("plugin")
        assertEquals(2, arr.length())
        assertEquals(42, arr.getInt(0))
        assertTrue("嵌套对象也要原样留下", arr.getJSONObject(1).getBoolean("nested"))
        assertEquals("me", cfg.readJson().getString("keep"))
    }

    @Test
    fun `无残留不动盘不备份`() {
        val dir = tempDir()
        val cfg = File(dir, "opencode.json")
        cfg.writeText("""{"plugin":["opencode-wakatime"],"model":"m"}""")
        val before = cfg.readText()

        assertFalse("没有残留必须返回 false", LegacyMemMigration.migrateConfigFile(cfg, 1L))

        assertEquals("一个字节都不许动", before, cfg.readText())
        assertEquals("不许留下备份/临时文件", listOf("opencode.json"), dir.list()!!.sorted())
    }

    @Test
    fun `没有plugin键或不是数组或坏JSON_原样不动`() {
        listOf(
            """{"model":"m"}""", // 没有 plugin 键
            """{"plugin":"opencode-mem"}""", // 是字符串不是数组
            """{"plugin":{"opencode-mem":{}}}""", // 是对象不是数组
            """{"plugin":[]}""", // 空数组：没东西可摘
            """{ this is not json }""", // 坏 JSON：不猜、不改
        ).forEachIndexed { i, text ->
            val dir = tempDir()
            val cfg = File(dir, "opencode.json")
            cfg.writeText(text)
            assertFalse("用例 $i 不该动盘", LegacyMemMigration.migrateConfigFile(cfg, 1L))
            assertEquals("用例 $i 必须逐字节不变", text, cfg.readText())
            assertEquals("用例 $i 不许留备份", listOf("opencode.json"), dir.list()!!.sorted())
        }
    }

    @Test
    fun `文件不存在_返回false`() {
        val cfg = File(tempDir(), "nope/opencode.json")
        assertFalse(LegacyMemMigration.migrateConfigFile(cfg, 1L))
    }

    @Test
    fun `已迁移不重复_第二次零改动且不产生第二个备份`() {
        val dir = tempDir()
        val cfg = File(dir, "opencode.json")
        cfg.writeText("""{"plugin":["opencode-mem","opencode-wakatime"]}""")

        assertTrue("第一次必须动", LegacyMemMigration.migrateConfigFile(cfg, 1L))
        val afterFirst = cfg.readText()
        assertEquals("只留一个备份", listOf("opencode.json", "opencode.json.bak-1"), dir.list()!!.sorted())

        assertFalse("第二次必须零改动", LegacyMemMigration.migrateConfigFile(cfg, 1L))
        assertEquals("内容不许再变", afterFirst, cfg.readText())
        assertEquals("不许产生第二个备份", listOf("opencode.json", "opencode.json.bak-1"), dir.list()!!.sorted())
    }

    @Test
    fun `改写中途失败_原文件必须还在`() {
        val dir = tempDir()
        val cfg = File(dir, "opencode.json")
        cfg.writeText("""{"plugin":["opencode-mem"]}""")
        val original = cfg.readText()
        // 把备份路径占成一个目录 ⇒ copyTo 必失败（FileAlreadyExistsException，IOException 一族）
        File(dir, "opencode.json.bak-1").mkdirs()

        val thrown = runCatching { LegacyMemMigration.migrateConfigFile(cfg, 1L) }.exceptionOrNull()
        assertTrue("失败必须抛出去（交给上层记日志、不置标记）", thrown is java.io.IOException)

        assertEquals("原文件一个字节都不许少", original, cfg.readText())
        assertFalse("不许留下临时文件", File(dir, "opencode.json.tmp-1").exists())
    }

    @Test
    fun `备份名带时间戳_两个批次互不覆盖`() {
        val dir = tempDir()
        val cfg = File(dir, "opencode.json")
        cfg.writeText("""{"plugin":["opencode-mem"]}""")
        assertTrue(LegacyMemMigration.migrateConfigFile(cfg, 100L))
        cfg.writeText("""{"plugin":["opencode-mem"],"round":2}""")
        assertTrue(LegacyMemMigration.migrateConfigFile(cfg, 200L))
        assertTrue("两代备份都在（各自可回滚到那一代）", File(dir, "opencode.json.bak-100").isFile)
        assertTrue(File(dir, "opencode.json.bak-200").isFile)
    }

    // ── 接线门禁（源码文本级，先剥注释再断言） ──

    private fun code(rel: String): String =
        File(rel).let { if (it.isFile) it else File("app/$rel") }.readText()
            .replace("\r\n", "\n")
            .replace(Regex("(?s)/\\*.*?\\*/"), "")
            .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    @Test
    fun `runOnce有一次性标记_不重复迁移`() {
        val src = code("src/main/java/com/example/zhengdao/terminal/LegacyMemMigration.kt")
        val body = src.substringAfter("fun runOnce(ctx: Context)").substringBefore("\n    fun ")
        assertTrue("必须先看一次性标记（已迁移过就短路）", body.contains("getBoolean(KEY_MIGRATED, false)"))
        assertTrue("成功后必须置标记", body.contains("putBoolean(KEY_MIGRATED, true)"))
        assertTrue("标记键必须带版本号（换形状才知道要重迁）", src.contains("legacy_mem_plugin_migrated_v1"))
    }

    @Test
    fun `失败不置标记_由启动后台杂务线程兜住记日志`() {
        val app = code("src/main/java/com/example/zhengdao/ZhengdaoApp.kt")
        assertTrue("启动时要调用迁移", app.contains("LegacyMemMigration.runOnce(this)"))
        // 搭车既有 zd-auto-clean 后台杂务线：不新增裸 Thread（对账本已贴红线 32）
        val fn = app.substringAfter("private fun autoCleanJunk()").substringBefore("\n    }")
        assertTrue("迁移必须跑在后台线程里（不挡冷启动）", fn.contains("Thread {"))
        assertTrue("失败只记日志、不往外抛", fn.contains("onFailure"))
        assertFalse(
            "不许为迁移单开一条线程（红线 32 已贴满，搭车即可）",
            app.contains("zd-legacy-mem-migration"),
        )
    }
}
