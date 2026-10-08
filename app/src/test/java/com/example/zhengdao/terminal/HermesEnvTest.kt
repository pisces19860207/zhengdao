package com.example.zhengdao.terminal

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Hermes 依赖环境的宿主侧判定与修复（ERRATA E-025 的回归锁）。
 *
 * 真机事故：装好 Hermes 后恢复"搬家包"，再敲 `hermes` 只剩三行依赖环境错误，
 * 关 App 重进也一样。搬家包**有意不带** `installs/<hash>/environments/` 整个目录，却带了旧机的
 * `installs/<hash>/facts.json` —— 记录指向旧机的依赖代，本机不存在。
 *
 * 本测试锁住三件容易改坏的事：
 * 1. **修得上就要修**：盘上有完整代时，宿主侧改指针即可（一键修正）；
 * 2. **修不上就别装作能修**：一代都没有 / 缺源码锁 / 压根没记录 ⇒ 必须走终端脚本，
 *    否则界面给个"修复"按钮点了没反应，比不修更糟；
 * 3. 判定**只读不写**，且遇到坏 `facts.json` 不抛异常（体检不能因为一个坏文件炸掉）。
 */
class HermesEnvTest {

    private val tmp: File = Files.createTempDirectory("zd-hermes-env").toFile()

    @After
    fun cleanup() {
        tmp.deleteRecursively()
    }

    /** `.hermes` 根。 */
    private fun root(): File = File(tmp, ".hermes").apply { mkdirs() }

    /** 一个**完整**的依赖代：venv/pyvenv.cfg + workspace/uv.lock 都在。 */
    private fun generation(install: File, name: String): File {
        val gen = File(install, "environments/$name")
        File(gen, "venv").mkdirs()
        File(gen, "venv/pyvenv.cfg").writeText("home = /root/.hermes/tools/python-3.14.7\n")
        File(gen, "workspace").mkdirs()
        File(gen, "workspace/uv.lock").writeText("version = 1\nrevision = 3\n")
        return gen
    }

    /** 写 `facts.json` 的依赖环境记录。 */
    private fun facts(install: File, environment: String?, resolvedLock: String?): File {
        val packages = JSONObject()
        val venv = JSONObject()
        if (environment != null) venv.put("environment", environment)
        if (resolvedLock != null) venv.put("resolved_lock", resolvedLock)
        packages.put("venv", venv)
        val json = JSONObject().put("packages", packages)
        return File(install, "facts.json").apply { writeText(json.toString(2)) }
    }

    @Test
    fun `没装 Hermes 不算问题`() {
        val st = HermesEnv.inspect(root())

        assertFalse(st.installed)
        assertTrue("没装就不能报警", st.ok)
        assertFalse(st.canRepairOnHost)
    }

    @Test
    fun `记录指向不存在的代_盘上有完整代则可一键修正`() {
        val install = File(root(), "installs/8a4017c4cabfe15f").apply { mkdirs() }
        val good = generation(install, "34aa9d1ad79c46e19c8222a032404c59")
        val f = facts(
            install,
            File(install, "environments/3c17878dccdc4a2c89c75675056b0cb5/venv").absolutePath,
            File(install, "environments/3c17878dccdc4a2c89c75675056b0cb5/workspace/uv.lock").absolutePath,
        )

        val before = HermesEnv.inspect(root())
        assertTrue(before.installed)
        assertFalse("记录坏掉必须报红", before.ok)
        assertTrue("有完整代 ⇒ 宿主侧修得上", before.canRepairOnHost)
        assertTrue(before.detail.contains("可一键修正"))

        assertTrue("修复要报告改动了东西", HermesEnv.repairOnHost(root()))

        val after = HermesEnv.inspect(root())
        assertTrue("指针改指完整代后应转绿", after.ok)
        assertEquals(File(good, "venv").absolutePath, after.recordedEnv)
        assertFalse(after.canRepairOnHost)
        assertTrue(
            "改之前要留备份",
            install.listFiles()!!.any { it.name.startsWith("facts.json.bak-证道") },
        )
        assertTrue("facts.json 本身不能被删", f.isFile)
    }

    @Test
    fun `一代都没有_删掉失效记录并转为终端修复`() {
        val install = File(root(), "installs/8a4017c4cabfe15f").apply { mkdirs() }
        val f = facts(
            install,
            File(install, "environments/dead/venv").absolutePath,
            File(install, "environments/dead/workspace/uv.lock").absolutePath,
        )

        val before = HermesEnv.inspect(root())
        assertFalse(before.ok)
        assertFalse("没有可指的代 ⇒ 只能进终端重建", before.canRepairOnHost)
        assertTrue(before.detail.contains("终端"))

        assertTrue(HermesEnv.repairOnHost(root()))

        assertFalse("留着会让 pm repair 拒绝重建，必须删掉", f.isFile)
        assertTrue(
            "删之前要留备份",
            install.listFiles()!!.any { it.name.startsWith("facts.json.bak-证道") },
        )
        val after = HermesEnv.inspect(root())
        assertFalse(after.ok)
        assertFalse("没记录了仍然只能进终端", after.canRepairOnHost)
    }

    @Test
    fun `更新中断留下的标记可一键清理_记录本身不动`() {
        val install = File(root(), "installs/8a4017c4cabfe15f").apply { mkdirs() }
        val good = generation(install, "gen-ok")
        val f = facts(install, File(good, "venv").absolutePath, File(good, "workspace/uv.lock").absolutePath)
        val original = f.readText()
        File(install, ".recovery.lock").writeText("")
        File(install, ".repair-incomplete").writeText("")

        val before = HermesEnv.inspect(root())
        assertFalse(before.ok)
        assertEquals(2, before.staleLocks)
        assertTrue("只是清标记 ⇒ 宿主侧修得上", before.canRepairOnHost)

        assertTrue(HermesEnv.repairOnHost(root()))

        val after = HermesEnv.inspect(root())
        assertTrue(after.ok)
        assertEquals(0, after.staleLocks)
        assertEquals("有效记录不该被碰", original, f.readText())
    }

    @Test
    fun `源码锁缺失要进终端_宿主侧改不了 git`() {
        val install = File(root(), "installs/8a4017c4cabfe15f").apply { mkdirs() }
        val good = generation(install, "gen-ok")
        facts(install, File(good, "venv").absolutePath, File(good, "workspace/uv.lock").absolutePath)
        File(root(), "hermes-agent").mkdirs() // 有检出目录但没有 uv.lock

        val st = HermesEnv.inspect(root())

        assertFalse(st.ok)
        assertTrue(st.sourceLockMissing)
        assertFalse("git checkout 只能在 guest 里做", st.canRepairOnHost)
    }

    @Test
    fun `坏掉的 facts_json 不抛异常`() {
        val install = File(root(), "installs/8a4017c4cabfe15f").apply { mkdirs() }
        File(install, "facts.json").writeText("{ 这不是 json")

        val st = HermesEnv.inspect(root())

        assertTrue(st.installed)
        assertFalse(st.ok)
        assertFalse("读不动就交给终端脚本处理", st.canRepairOnHost)
        assertNotNull(st.detail)
        assertFalse("读不动就别乱改文件", HermesEnv.repairOnHost(root()))
    }
}
