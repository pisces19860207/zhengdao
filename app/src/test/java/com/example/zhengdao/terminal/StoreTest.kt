// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 公共存放区（[Store]）单测。
 *
 * 用户 2026-10-08 定稿：日志、包缓存、Agent 安装脚本与账本都要落在 `Download/证道/` 下，
 * 重装 App 后"像装环境一样瞬间装好"。落地时最怕的不是路径写错，而是**搬家把数据搬丢**：
 * 私有 `files/` 与公共 `/sdcard` 可能不是同一个文件系统（`renameTo` 会失败）、公共目录里
 * 可能已经有用户手动放的同名文件（绝不能覆盖）。所以 [Store.adoptDir] / [Store.adoptFile]
 * 的每条分支都在这里锁住——它们每次启动都会被调用，是幂等的。
 */
class StoreTest {

    private fun dir(name: String): File =
        File.createTempFile(name, "").let { it.delete(); it.mkdirs(); it }

    private fun File.child(name: String, content: String = "x"): File =
        File(this, name).apply { parentFile?.mkdirs(); writeText(content) }

    // ── 名字校验（cacheDir 用它挡住 `../` 之类的花活）────────────────────────

    @Test
    fun `合法的缓存类别名放行`() {
        assertTrue(Store.isLegalKind("npm"))
        assertTrue(Store.isLegalKind("uv"))
        assertTrue(Store.isLegalKind("pip"))
        assertTrue(Store.isLegalKind("node-compile_cache"))
    }

    @Test
    fun `路径花活一律不放行`() {
        assertFalse(Store.isLegalKind(""))
        assertFalse(Store.isLegalKind("../x"))
        assertFalse(Store.isLegalKind("a/b"))
        assertFalse(Store.isLegalKind(".."))
        assertFalse(Store.isLegalKind("a b"))
        // 中文/符号也不行：guest 侧 bash 路径不要转义
        assertFalse(Store.isLegalKind("缓存"))
    }

    @Test
    fun `guest 侧脚本路径与目录约定不要漂`() {
        // AgentInstaller 里写死的安装命令依赖这两个约定：公共区被 bind 到 `/opt/zhengdao`
        // （刻意不挂在工作区下面——工作区可能是用户自己的内容目录），agents/scripts 是
        // 公共区子目录。改了这里就必须同步改命令与 ProotLauncher 的 bind，所以锁进单测。
        assertEquals("/opt/zhengdao/agents/scripts", Store.GUEST_SCRIPTS_DIR)
        assertEquals("/opt/zhengdao", Store.GUEST_ROOT)
        assertEquals("/storage/emulated/0/Download/证道", Store.PUBLIC_ROOT)
        assertEquals("installed.json", Store.LEDGER_NAME)
        assertEquals("logs", Store.DIR_LOGS)
        assertEquals("cache", Store.DIR_CACHE)
        assertEquals("agents", Store.DIR_AGENTS)
    }

    // ── 搬家 ────────────────────────────────────────────────────────────────

    @Test
    fun `adoptDir 把旧目录里的条目搬过去并删掉空壳`() {
        val pub = dir("pub")
        val old = dir("old")
        old.child("a-install.sh", "aaa")
        old.child("installed.json", "{}")
        File(old, "sub").apply { mkdirs() }.child("b.sh", "bbb")

        val moved = Store.adoptDir(pub, old)

        assertEquals(3, moved)
        assertEquals("aaa", File(pub, "a-install.sh").readText())
        assertEquals("bbb", File(pub, "sub/b.sh").readText())
        assertFalse("搬空之后旧目录不该留下空壳", old.exists())
    }

    @Test
    fun `adoptDir 绝不覆盖目标已有的同名文件`() {
        val pub = dir("pub")
        val old = dir("old")
        pub.child("installed.json", "新账本")
        old.child("installed.json", "旧账本")
        old.child("other.sh", "keep")

        val moved = Store.adoptDir(pub, old)

        assertEquals(1, moved)
        assertEquals("新账本", File(pub, "installed.json").readText())
        assertEquals("keep", File(pub, "other.sh").readText())
        // 被跳过的那个还留在源目录 ⇒ 源目录不能删（否则等于静默丢数据）
        assertEquals("旧账本", File(old, "installed.json").readText())
        assertTrue(old.isDirectory)
    }

    @Test
    fun `adoptDir 对不存在的旧目录是无操作`() {
        val pub = dir("pub")
        assertEquals(0, Store.adoptDir(pub, File(pub, "不存在")))
        // 旧路径是个文件（历史上真出现过同名文件）也不能炸
        val file = pub.child("not-a-dir")
        assertEquals(0, Store.adoptDir(pub, file))
    }

    @Test
    fun `adoptDir 可重复调用（幂等）`() {
        val pub = dir("pub")
        val old = dir("old")
        old.child("x.sh", "x")
        assertEquals(1, Store.adoptDir(pub, old))
        assertEquals(0, Store.adoptDir(pub, old))
        assertEquals("x", File(pub, "x.sh").readText())
    }

    @Test
    fun `adoptFile 搬单文件且不覆盖已有目标`() {
        val pub = dir("pub")
        val old = dir("old")
        val src = old.child("session.json", "内容")
        assertTrue(Store.adoptFile(File(pub, "session.json"), src))
        assertEquals("内容", File(pub, "session.json").readText())
        assertFalse(src.exists())

        // 目标已存在：原样保留目标，返回 false，源文件不动
        val src2 = old.child("session.json", "更新的内容")
        assertFalse(Store.adoptFile(File(pub, "session.json"), src2))
        assertEquals("内容", File(pub, "session.json").readText())
        assertTrue(src2.exists())

        // 源不存在：无操作
        assertFalse(Store.adoptFile(File(pub, "nope.json"), File(old, "nope.json")))
    }
}
