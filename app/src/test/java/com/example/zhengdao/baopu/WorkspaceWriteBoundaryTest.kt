// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百二十九批（派工单 §3 读写边界表）：**读放大到全盘 ≠ 写放大**。
//
// 用户 2026-10-26 口径：「工作区和默认工作区可写」——写/改/删/移动只允许两处：
// ① 当前工作区 ② 默认工作区 `Download/证道`；其余路径**一律拒绝（不是弹卡）**；
// 原件区/备份区仍由各工具既有的 PathGuard 硬拒（不在本文件判）。
//
// 派工单点名的单测：「写 /sdcard/Download/其它/ ⇒ 拒绝」——`/sdcard` 在真机上是
// `/storage/emulated/0` 的软链，判定按 canonical/词法归一后组件比较，两种写法都必须拒。
//
// 与 156 批那套的关系：`workspaceAccessVerdict`/`workspaceBoundaryCheck`（读/写共用、
// 带 ASK_USER 三档）一个字没动，由 `WorkspaceAccessTest` 与
// `SearchReadNeverGrantsWriteTest` 逐字钉着；本文件钉的是**写类工具新走的**那对
// `workspaceWriteVerdict`/`workspaceWriteBoundaryCheck`（两档：INSIDE / DENIED）。
package com.example.zhengdao.baopu

import com.example.zhengdao.baopu.BaopuWorkspace.defaultDir
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class WorkspaceWriteBoundaryTest {

    private val home = File("/storage/emulated/0/Download/证道")
    private val phone = "/storage/emulated/0"

    // ── 允许的两处 ──────────────────────────────────────────────────────────

    @Test
    fun `当前工作区内可写`() {
        assertEquals(AccessVerdict.INSIDE, workspaceWriteVerdict(home, File(home, "笔记/x.md")))
    }

    @Test
    fun `默认工作区可写_即使当前工作区在别处`() {
        // 当前工作区切到 custom（这里用手机盘上另一个真实目录代表），默认工作区 Download/证道
        // 仍是允许的第二处——用户口径原话「工作区和默认工作区可写」。
        val customRoot = File("$phone/Documents/我的工作区")
        assertEquals(AccessVerdict.INSIDE, workspaceWriteVerdict(customRoot, File(home, "x.md")))
        assertEquals(AccessVerdict.INSIDE, workspaceWriteVerdict(customRoot, File(customRoot, "x.md")))
        // 允许根集合去重：root 就是 defaultDir 时也只是一处，不该多出一份
        assertEquals(AccessVerdict.INSIDE, workspaceWriteVerdict(home, File(defaultDir(), "y.md")))
    }

    // ── 派工单点名的单测 ────────────────────────────────────────────────────

    @Test
    fun `写sdcard下其它目录_拒绝`() {
        // `/sdcard` 是真机软链；`/storage/emulated/0` 是全称。两种写法都得拒，
        // 而且拒得干脆：DENIED，不是 ASK_USER（不是弹卡，是拒绝）。
        listOf(
            File("/sdcard/Download/其它/x.md"),
            File("$phone/Download/其它/x.md"),
        ).forEach { target ->
            assertEquals(
                "写 $target 必须 DENIED（读写边界表：写只允许当前工作区＋Download/证道）",
                AccessVerdict.DENIED,
                workspaceWriteVerdict(home, target),
            )
        }
    }

    @Test
    fun `负例_全盘不许扩成可写_读的根不等于写的根`() {
        // 搜索范围放大到全盘（PHONE 根 = /storage/emulated/0）之后，
        // 「全盘」这个根本身**不能**顺势变成可写——读写边界表的整行都在钉这个。
        assertEquals(
            "手机全盘根自身的写必须被拒（读全盘 ≠ 写全盘）",
            AccessVerdict.DENIED,
            workspaceWriteVerdict(home, File("$phone/Notes.md")),
        )
        // 也不许因为「读侧账本里有 PHONE 行」就翻案：写判据的入参里根本没有读侧位置
        // （SearchReadNeverGrantsWriteTest 的结构门禁管着），这里再钉一次行为面。
        assertEquals(
            "Android/data 这类别家私有目录同样 DENIED",
            AccessVerdict.DENIED,
            workspaceWriteVerdict(home, File("$phone/Android/data/com.x/files/z.md")),
        )
    }

    // ── 出口与档位形状 ──────────────────────────────────────────────────────

    @Test
    fun `工具层出口_内放行_外原文拒绝_永不弹卡`() {
        val denied = "路径越界被拒：原文一个字不动"
        assertNull("两处之内放行", workspaceWriteBoundaryCheck(home, File(home, "a.md"), "a.md", denied))
        assertEquals(
            "两处之外：调用方传的原文逐字返回（拒绝，不是确认卡）",
            denied,
            workspaceWriteBoundaryCheck(home, File("/sdcard/Download/其它/a.md"), "a.md", denied),
        )
    }

    @Test
    fun `负例_写判据永不产出ASK_USER`() {
        // 156 批的 ASK_USER 那条路在写侧不存在：判据全部输入组合下只有 INSIDE / DENIED 两档。
        val roots = listOf(home, File("$phone/Documents/别处"), defaultDir())
        val targets = listOf(
            File(home, "x.md"),
            File(defaultDir(), "x.md"),
            File("/sdcard/Download/其它/x.md"),
            File("$phone/Android/data/com.x/z"),
            File("$phone/x"),
        )
        roots.forEach { r ->
            targets.forEach { t ->
                val v = workspaceWriteVerdict(r, t)
                assertEquals(
                    "写判据对 root=$r target=$t 给出了 ASK_USER——写侧没有卡，这是越权翻案",
                    false,
                    v == AccessVerdict.ASK_USER,
                )
            }
        }
    }
}
