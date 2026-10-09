// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 旧依赖代清理单测（[HermesEnv.prunableGenerations] / [HermesEnv.pruneGenerations]，Issue #8 A）。
 *
 * 这一刀省的是真机上最大的一块死重量（实测 4 代只用到 1 代，约 734M），但删错就是把
 * hermes 的依赖环境删没了 —— 所以这里每一条断言都在钉"**什么情况下不许删**"：
 * 记录读不出来不删、保留里没有完整代不删、不像代目录的不删。
 */
class HermesEnvPruneTest {

    private val hash = "8a4017c4cabfe15f"

    /**
     * 假 hermes 家目录 = `<tmp>/home/.hermes`。
     *
     * ⚠️ 必须保留 `home` 这一层：`HermesEnv.toHostFile()` 把 guest 的 `/root/x` 映射到
     * **`root.parentFile`** 下的 `x`（真机上 `root` = `files/home/.hermes`，`/root` = `files/home`）。
     * 直接把 `root` 当 tmp 根，映射会落到 tmp 的兄弟目录去，断言就全成了"保留集为空"。
     */
    private fun tmpRoot(): File {
        val base = File.createTempFile("zd-hermes", "")
        base.delete()
        val root = File(File(base, "home"), ".hermes")
        root.mkdirs()
        return root
    }

    /** 造一代；[complete] 为假时缺 `workspace/uv.lock`（hermes 眼里的残代）。 */
    private fun gen(root: File, name: String, complete: Boolean = true, mtime: Long = 0L): File {
        val g = File(root, "installs/$hash/environments/$name")
        File(g, "venv").mkdirs()
        File(g, "venv/pyvenv.cfg").writeText("home = x\n")
        File(g, "workspace").mkdirs()
        if (complete) File(g, "workspace/uv.lock").writeText("lock\n")
        // 塞点体积，让"释放了多少"能是真的
        File(g, "venv/blob.bin").writeBytes(ByteArray(4096))
        if (mtime > 0) g.setLastModified(mtime)
        return g
    }

    /** 写 facts.json，指向 [target]（guest 视角，和 hermes 自己的写法一致）。 */
    private fun facts(root: File, target: String) {
        val env = "/root/.hermes/installs/$hash/environments/$target/venv"
        val lock = "/root/.hermes/installs/$hash/environments/$target/workspace/uv.lock"
        File(root, "installs/$hash").mkdirs()
        File(root, "installs/$hash/facts.json").writeText(
            """{"packages":{"venv":{"environment":"$env","resolved_lock":"$lock"}}}"""
        )
    }

    @Test
    fun `当前代与最新代保留，只删更老的`() {
        val root = tmpRoot()
        val a = gen(root, "genA", mtime = 1_000)
        val b = gen(root, "genB", mtime = 2_000)
        val c = gen(root, "genC", mtime = 3_000)
        facts(root, "genA")                       // 当前记录指向最老的那个
        val prunable = HermesEnv.prunableGenerations(root).map { it.name }
        assertEquals(listOf("genB"), prunable)    // genA=当前、genC=最新(keepExtra=1)
        assertTrue(b.exists())
    }

    @Test
    fun `记录读不出来时一个都不删`() {
        val root = tmpRoot()
        gen(root, "genA", mtime = 1_000)
        gen(root, "genB", mtime = 2_000)
        // 没有 facts.json（刚 clone 完还没建环境 / 记录被删）⇒ 全部保留
        assertTrue(HermesEnv.prunableGenerations(root).isEmpty())
    }

    @Test
    fun `保留里没有完整代时不删任何东西`() {
        val root = tmpRoot()
        gen(root, "genA", complete = false, mtime = 1_000)
        gen(root, "genB", complete = true, mtime = 2_000)
        gen(root, "genC", complete = false, mtime = 3_000)
        facts(root, "genB")                        // 当前这一代是完整的 ⇒ 可以删掉 genA/genC 吗？
        // genB（当前，完整）+ genC（最新，keepExtra）都在保留里 ⇒ 保留里确有完整代 ⇒ genA 可删
        assertEquals(listOf("genA"), HermesEnv.prunableGenerations(root).map { it.name })

        // 反过来：当前代是残代、最新代也是残代 ⇒ 保留里没有完整代 ⇒ 一个都不删
        val root2 = tmpRoot()
        gen(root2, "genA", complete = false, mtime = 1_000)
        gen(root2, "genB", complete = false, mtime = 3_000)
        facts(root2, "genA")
        assertTrue(HermesEnv.prunableGenerations(root2).isEmpty())
    }

    @Test
    fun `deadGenerationsMb 只算死代，pruneGenerations 真删并报数`() {
        val root = tmpRoot()
        gen(root, "genA", mtime = 1_000)
        val b = gen(root, "genB", mtime = 2_000)
        gen(root, "genC", mtime = 3_000)
        facts(root, "genA")
        // 每代 4KB 的 blob，向上取整到 MB 会是 0 ⇒ 这里只验"删了 1 代、字节数 > 0"
        val (count, freed) = HermesEnv.pruneGenerations(root)
        assertEquals(1, count)
        assertTrue("释放字节应当 > 0", freed > 0)
        assertTrue("死代已删", !b.exists())
        assertTrue("当前代还在", File(root, "installs/$hash/environments/genA/venv/pyvenv.cfg").isFile)
        assertTrue("最新代还在", File(root, "installs/$hash/environments/genC/venv/pyvenv.cfg").isFile)
        // 再跑一次是幂等的：没有死代可删
        assertEquals(0, HermesEnv.pruneGenerations(root).first)
    }

    @Test
    fun `不像代目录的东西不碰`() {
        val root = tmpRoot()
        gen(root, "genA", mtime = 1_000)
        gen(root, "genC", mtime = 3_000)
        facts(root, "genA")
        val junk = File(root, "installs/$hash/environments/notes")   // 没有 venv / workspace
        junk.mkdirs()
        File(junk, "README").writeText("别删我\n")
        HermesEnv.pruneGenerations(root)
        assertTrue("非代目录必须留着", File(junk, "README").isFile)
    }

    @Test
    fun `三代 mtime 打平时按名字倒序兜底，仍保住一代`() {
        val root = tmpRoot()
        val a = gen(root, "genA")
        val b = gen(root, "genB")
        val c = gen(root, "genC")
        // 真机实况（2026-10-09）：安装脚本在同一秒里连建三代，目录 mtime 打平 ⇒ 只按 mtime 排
        // 会按目录序取到 genA（恰好是当前代），"额外留一代"就白设了、三代只活一代。
        val same = 1_700_000_000_000L
        listOf(a, b, c).forEach { it.setLastModified(same) }
        facts(root, "genA")
        // 当前代 genA + 名字倒序第一个 genC 保留 ⇒ 只删 genB
        assertEquals(listOf("genB"), HermesEnv.prunableGenerations(root).map { it.name })
        assertTrue(c.exists())
    }
}
