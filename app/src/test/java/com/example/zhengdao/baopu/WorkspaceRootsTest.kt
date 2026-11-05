// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：492 工单 A 段①②＋「RO 写拒」的 JVM 直测——[WorkspaceRootRules] 是纯函数，
// 工单判据（根序、歧义列候选、单根 0 回归、RO 拒写）在这里钉死，真机只验接线。
package com.example.zhengdao.baopu

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceRootsTest {

    private fun tmp(name: String): File = Files.createTempDirectory("ws492-$name").toFile()

    // ── A①：根清单 ──────────────────────────────────────────────────────────

    @Test
    fun `清单_主根恒第0条且RW`() {
        val primary = tmp("primary")
        val m = WorkspaceRootRules.manifest(primary, emptyList())
        assertEquals(1, m.size)
        assertEquals(primary, m[0].dir)
        assertEquals(RootMode.RW, m[0].mode)
        assertEquals(WorkspaceRootRules.LABEL_PRIMARY, m[0].label)
    }

    @Test
    fun `清单_附加根保序_重复根只留第一条`() {
        val primary = tmp("primary")
        val a = WsRoot(tmp("a"), RootMode.RO, "甲")
        val dup = WsRoot(File(primary.absolutePath + "/"), RootMode.RO, "主根别名")
        val b = WsRoot(tmp("b"), RootMode.RW, "乙")
        val m = WorkspaceRootRules.manifest(primary, listOf(a, dup, b))
        assertEquals(listOf("甲", "乙"), m.drop(1).map { it.label })
    }

    @Test
    fun `清单_最多5条_超限丢弃先到先得`() {
        val primary = tmp("primary")
        val extras = (1..7).map { WsRoot(tmp("x$it"), RootMode.RO, "根$it") }
        val m = WorkspaceRootRules.manifest(primary, extras)
        assertEquals(WorkspaceRootRules.MAX_ROOTS, m.size)
        assertEquals((1..4).map { "根$it" }, m.drop(1).map { it.label })
    }

    // ── A②：相对路径按根序解析 ───────────────────────────────────────────────

    @Test
    fun `解析_绝对路径原样`() {
        val abs = tmp("abs").absolutePath
        val r = WorkspaceRootRules.resolve(listOf(WsRoot(tmp("p"), RootMode.RW, "工作区")), abs)
        assertEquals(WorkspaceRootRules.Resolution.Absolute(File(abs)), r)
    }

    @Test
    fun `解析_唯一命中按根序给出`() {
        val primary = tmp("primary")
        val ro = tmp("ro")
        File(ro, "99_速记/笔记.md").let { f -> f.parentFile.mkdirs(); f.writeText("x") }
        val roots = listOf(WsRoot(primary, RootMode.RW, "工作区"), WsRoot(ro, RootMode.RO, "资料"))
        val r = WorkspaceRootRules.resolve(roots, "99_速记/笔记.md")
        assertTrue("唯一命中 ⇒ Single", r is WorkspaceRootRules.Resolution.Single)
        assertEquals(RootMode.RO, (r as WorkspaceRootRules.Resolution.Single).root.mode)
    }

    @Test
    fun `解析_新文件落在唯一有该父目录的根_写拒才有得拒`() {
        val primary = tmp("primary")
        val ro = tmp("ro")
        File(ro, "99_速记").mkdirs()
        val roots = listOf(WsRoot(primary, RootMode.RW, "工作区"), WsRoot(ro, RootMode.RO, "资料"))
        val r = WorkspaceRootRules.resolve(roots, "99_速记/新.md")
        assertTrue("父目录只在 RO 根里 ⇒ 解析到 RO 根", r is WorkspaceRootRules.Resolution.Single)
        // 负例（2026-11-06 实测抓到）：祖先只用来选根——结果必须是 根+完整path，
        // 不许拿祖先的 File 再拼一遍（会拼出 根/99_速记/99_速记/新.md 的嵌套重路）。
        assertEquals(File(ro, "99_速记/新.md").absolutePath, (r as WorkspaceRootRules.Resolution.Single).file.absolutePath)
        assertEquals(RootMode.RO, r.root.mode)
    }

    @Test
    fun `解析_哪里都不存在_回主根`() {
        val primary = tmp("primary")
        val ro = tmp("ro")
        val roots = listOf(WsRoot(primary, RootMode.RW, "工作区"), WsRoot(ro, RootMode.RO, "资料"))
        val r = WorkspaceRootRules.resolve(roots, "没有/新文件.md")
        assertTrue("不存在的相对路径回主根（写新文件的落点）", r is WorkspaceRootRules.Resolution.Fallback)
        assertEquals(File(primary, "没有/新文件.md"), (r as WorkspaceRootRules.Resolution.Fallback).file)
    }

    @Test
    fun `解析_同名歧义_报错列候选_不许猜`() {
        val primary = tmp("primary")
        val ro = tmp("ro")
        // 同名条目在两个根里都存在 ⇒ Ambiguous（工单 A②原文：报错列候选）
        listOf(primary, ro).forEach { root ->
            File(root, "99_速记").mkdirs()
        }
        val roots = listOf(WsRoot(primary, RootMode.RW, "工作区"), WsRoot(ro, RootMode.RO, "资料"))
        val r = WorkspaceRootRules.resolve(roots, "99_速记")
        assertTrue("同名歧义不许猜", r is WorkspaceRootRules.Resolution.Ambiguous)
        val msg = WorkspaceRootRules.ambiguityMessage(r as WorkspaceRootRules.Resolution.Ambiguous, roots)
        assertTrue("候选要列出两个根", msg.contains(primary.name) && msg.contains(ro.name))
        assertTrue("候选要带模式", msg.contains("RO") && msg.contains("RW"))
    }

    @Test
    fun `A4_单根_解析逐字等于旧算式`() {
        // ④ 的判据：没配附加根时，resolve 的每条出口都折回 File(主根, raw)／File(raw)
        val primary = tmp("primary")
        File(primary, "在.md").writeText("x")
        val roots = listOf(WsRoot(primary, RootMode.RW, "工作区"))
        val exists = WorkspaceRootRules.resolve(roots, "在.md")
        val missing = WorkspaceRootRules.resolve(roots, "不在.md")
        assertEquals(File(primary, "在.md"), (exists as WorkspaceRootRules.Resolution.Single).file)
        assertEquals(File(primary, "不在.md"), (missing as WorkspaceRootRules.Resolution.Fallback).file)
    }

    // ── RO 写拒 ─────────────────────────────────────────────────────────────

    @Test
    fun `RO写拒_RO根内目标给原文_RW根内null`() {
        val primary = tmp("primary")
        val ro = tmp("ro")
        val roots = listOf(WsRoot(primary, RootMode.RW, "工作区"), WsRoot(ro, RootMode.RO, "资料"))
        val inside = File(ro, "99_速记/新.md")
        val denial = WorkspaceRootRules.writeDenial(roots, inside)
        assertTrue("RO 根内写要拒", denial!!.contains("只读根"))
        assertNull("RW 主根内写不拒", WorkspaceRootRules.writeDenial(roots, File(primary, "产物/a.md")))
        assertNull("清单无 RO 根时永远 null（单根 0 回归）", WorkspaceRootRules.writeDenial(listOf(roots[0]), inside))
    }

    @Test
    fun `RO写拒_更深的RW根盖过RO根`() {
        val ro = tmp("ro")
        val rwInside = File(ro, "可写区").apply { mkdirs() }
        val roots = listOf(
            WsRoot(tmp("primary"), RootMode.RW, "工作区"),
            WsRoot(ro, RootMode.RO, "资料"),
            WsRoot(rwInside, RootMode.RW, "嵌套可写"),
        )
        assertNull("RO 根里更深的 RW 根说了算", WorkspaceRootRules.writeDenial(roots, File(rwInside, "a.md")))
        assertTrue(
            "RW 根之外的 RO 地界照拒",
            WorkspaceRootRules.writeDenial(roots, File(ro, "别处/a.md")) != null,
        )
    }

    // ── 附加根配置的合法性 ───────────────────────────────────────────────────

    @Test
    fun `validExtra_绝对路径_不带点杠_系统目录拒`() {
        assertNull(WorkspaceRoots.validExtra("/storage/emulated/0/资料", RootMode.RO))
        assertEquals("path 为空", WorkspaceRoots.validExtra("", RootMode.RO))
        assertTrue(WorkspaceRoots.validExtra("storage/x", RootMode.RO)!!.contains("绝对路径"))
        assertTrue(WorkspaceRoots.validExtra("/a/../b", RootMode.RO)!!.contains(".."))
        assertTrue(WorkspaceRoots.validExtra("/storage/x", null)!!.contains("mode"))
        assertTrue("系统目录进不来", WorkspaceRoots.validExtra("/system/etc", RootMode.RW)!!.contains("系统"))
    }

    @Test
    fun `parse_坏条目跳过_label缺省取末段_mode缺省RW`() {
        val text = """
            [
              {"path": "/storage/emulated/0/资料", "mode": "RO"},
              {"path": "不是绝对路径"},
              {"path": "/system/etc", "mode": "RW"},
              "乱写的一条",
              {"path": "/storage/emulated/0/备份", "mode": "RW", "label": "我的备份"}
            ]
        """.trimIndent()
        val roots = WorkspaceRoots.parse("test", text)
        assertEquals(2, roots.size)
        assertEquals("资料", roots[0].label)
        assertEquals(RootMode.RO, roots[0].mode)
        assertEquals("我的备份", roots[1].label)
        assertEquals(RootMode.RW, roots[1].mode)
    }

    @Test
    fun `parse_整份不是JSON数组_空表不抛`() {
        assertTrue(WorkspaceRoots.parse("test", "不是 JSON").isEmpty())
        assertTrue(WorkspaceRoots.parse("test", "").isEmpty())
    }

    @Test
    fun `RootMode_读不懂的写法不猜`() {
        assertNull(RootMode.fromTag("rw"))
        assertEquals(RootMode.RW, RootMode.fromTag("RW"))
        assertEquals(RootMode.RO, RootMode.fromTag("RO"))
        assertFalse(RootMode.entries.isEmpty())
    }
}
