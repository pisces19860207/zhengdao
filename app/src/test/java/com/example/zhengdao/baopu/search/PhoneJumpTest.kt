// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百九十八批（派工单 §1⑤ ＋ §2）：**回跳三条路径的判据层直测**。
// 派工单原话「`artifact.session_id` → `.归属.jsonl` → 文件本身；外部只读范围只给『打开/预览/复制进证道』」。
//
// ## 本文件钉住的两条最容易做错的事
// 1. **不凭空给 SESSION**。台账是按 `workspace` 隔离的：外部根（PHONE 档）的文件在 `artifact`
//    里根本没有行。真出现「外部根也能回会话」只有两种可能——判据写错了，或者有人把读侧账本
//    当成了写侧批准。所以 [表驱动_台账命中但根不是工作区_不给回会话] 是本批最重要的一条负例。
// 2. **动作表里没有「用别的 App 打开」**。派工单那句「打开」实测做不了而不该硬做
//    （唯一的 FileProvider 只放相机缓存目录，加宽到外置存储根是**安全面**的改动，理由写在
//    `PhoneJump.kt` 文件头）。所以「外部只读只给预览／复制进证道」这条判据的红线是
//    **不多给动作**：[结构_动作表里没有打开与写] 把枚举本身钉死。
//
// ## `.归属.jsonl` 为什么做成「传进来才有」而不是「自己去读」
// 那张清单是第一百九十九批（S4）的产物（`docs/FEATURE-LEDGER.md`）。本批把接口做成类型化的挂点
// （[PhoneJump.plan] 的 `ownershipOwner`），199 落地时它自己就有地方接，不用回来改判据的形状。
package com.example.zhengdao.baopu.search

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneJumpTest {

    private fun target(rootId: Long = 1L, absRoot: String = "/storage/emulated/0/Download", rel: String = "a.md", isWorkspace: Boolean = false) =
        JumpTarget(rootId = rootId, absRoot = absRoot, relPath = rel, rootIsWorkspace = isWorkspace)

    // ── 1. 台账读数：null／非正数都当「没有这一行」 ──────────────────────────────

    @Test
    fun `表驱动_台账列只有正数id才算命中`() {
        assertNull(PhoneJump.artifactSessionIdOf(null))
        assertNull("0 不是合法的 session id（`session` 表从 1 起）", PhoneJump.artifactSessionIdOf(0L))
        assertNull(PhoneJump.artifactSessionIdOf(-7L))
        assertEquals(1L, PhoneJump.artifactSessionIdOf(1L))
        assertEquals(42L, PhoneJump.artifactSessionIdOf(42L))
    }

    @Test
    fun `表驱动_工作区根的写法对不上就当没命中`() {
        // `artifact.workspace` 存 `spillRoot().absolutePath`，`scope.abs_root` 存归一串：
        // 两边写法不同 ⇒ 宁可退回路径③（少一个跳转按钮），不许跳到别的目录的同名文件去。
        val host = "/storage/emulated/0/Download/证道"
        assertTrue(PhoneJump.workspaceMatches(host, host))
        assertTrue("末尾斜杠是同一个目录的两种写法", PhoneJump.workspaceMatches("$host/", host))
        assertTrue(PhoneJump.workspaceMatches(host, "$host/"))
        assertFalse("前缀像但不是同一个目录（组件级判据不许退化成字符串前缀）",
            PhoneJump.workspaceMatches("$host-evil", host))
        assertFalse(PhoneJump.workspaceMatches("/storage/emulated/0/Download", host))
        assertFalse("空串不许匹配空串：那等于允许一个没填的字段冒充命中", PhoneJump.workspaceMatches("", ""))
    }

    @Test
    fun `表驱动_是不是当前工作区根`() {
        val key = "/storage/emulated/0/Documents/证道"
        assertTrue(PhoneJump.isWorkspaceRoot(key, key))
        assertTrue(PhoneJump.isWorkspaceRoot("$key/", key))
        assertFalse(PhoneJump.isWorkspaceRoot("/storage/emulated/0", key))
        assertFalse("账上没这个根／工作区还没定 ⇒ 一律判不是（不猜）", PhoneJump.isWorkspaceRoot(key, ""))
        assertFalse(PhoneJump.isWorkspaceRoot("", key))
    }

    // ── 2. 三条路径的优先级（§1⑤ 的「有账 ⇒ 有归属 ⇒ 只有文件」） ─────────────────

    @Test
    fun `路径①_台账命中给回会话`() {
        val p = PhoneJump.plan(target(absRoot = "/w/证道", isWorkspace = true), artifactSessionId = 7L)
        assertEquals(listOf(JumpAction.SESSION, JumpAction.PREVIEW, JumpAction.COPY_INTO_VAULT), p.actions)
        assertEquals(7L, p.sessionId)
        assertTrue(p.hasSession)
        assertEquals("", p.ownershipOwner)
    }

    @Test
    fun `表驱动_台账命中但根不是工作区_不给回会话`() {
        // 外部根（PHONE 档）不可能有台账行；真出现了说明 `workspaceMatches` 判错了。
        // 这条是「宁少不错」在回跳侧的落点：少一个按钮不影响任何人，错一个按钮会把用户带进别的会话。
        val p = PhoneJump.plan(target(absRoot = "/storage/emulated/0/Download", isWorkspace = false), artifactSessionId = 7L)
        assertFalse("外部根不许给回会话", JumpAction.SESSION in p.actions)
        assertFalse(p.hasSession)
        assertEquals(listOf(JumpAction.PREVIEW, JumpAction.COPY_INTO_VAULT), p.actions)
    }

    @Test
    fun `路径②_没有台账但有归属才给回归属`() {
        val p = PhoneJump.plan(target(), artifactSessionId = null, ownershipOwner = "证道中心")
        assertEquals(listOf(JumpAction.OWNERSHIP, JumpAction.PREVIEW, JumpAction.COPY_INTO_VAULT), p.actions)
        assertEquals("证道中心", p.ownershipOwner)
        // 归属与台账同时在场 ⇒ 台账优先（路径①成立就不给②：两条都是「回到谁产生的」，给一条就够）
        val both = PhoneJump.plan(target(isWorkspace = true), artifactSessionId = 3L, ownershipOwner = "别人")
        assertEquals(listOf(JumpAction.SESSION, JumpAction.PREVIEW, JumpAction.COPY_INTO_VAULT), both.actions)
        assertFalse(JumpAction.OWNERSHIP in both.actions)
    }

    @Test
    fun `表驱动_空白归属不算归属`() {
        listOf<String?>(null, "", "   ").forEach { owner ->
            val p = PhoneJump.plan(target(), artifactSessionId = null, ownershipOwner = owner)
            assertFalse("$owner 不许凭空给回归属动作", JumpAction.OWNERSHIP in p.actions)
            assertEquals("$owner 要归一成空串（界面据此显示「下一批的活」）", "", p.ownershipOwner)
        }
    }

    @Test
    fun `路径③_前两条都不成立也永远给预览与复制`() {
        // 用户搜到一篇文件之后至少永远能做两件事：就地看、复制进证道。这是本批的兜底。
        val p = PhoneJump.plan(target(), artifactSessionId = null)
        assertEquals(listOf(JumpAction.PREVIEW, JumpAction.COPY_INTO_VAULT), p.actions)
        assertEquals(0L, p.sessionId)
        // 顺序＝界面渲染顺序 ⇒ 同一份命中两次渲染必须一模一样（不靠 Map/Set 的偶然顺序）
        val again = PhoneJump.plan(target(rel = "b.md"), artifactSessionId = null)
        assertEquals(p.actions, again.actions)
    }

    // ── 3. 动作表的形状（§1⑤「只给打开/预览/复制进证道」） ──────────────────────

    @Test
    fun `结构_动作表里没有打开与写`() {
        val names = JumpAction.values().map { it.name }.toSet()
        assertEquals(setOf("SESSION", "OWNERSHIP", "PREVIEW", "COPY_INTO_VAULT"), names)
        // 「用别的 App 打开」「删除」「重命名」「编辑」都不许出现在这张表里（理由见文件头第 2 条）
        listOf("OPEN", "OPEN_WITH", "EXTERNAL", "SHARE", "DELETE", "RENAME", "EDIT", "MOVE", "WRITE").forEach {
            assertFalse("动作表里出现了「$it」", it in names)
        }
        // 唯一的落盘动作只有 COPY_INTO_VAULT，而它写的是证道自己的目录（UI 侧，用户点一下才做）
        val jump = code(sourceOf("PhoneJump.kt"))
        listOf(
            "FileWriter", "writeText(", "writeBytes(", ".delete(", "deleteRecursively", "ContentResolver",
            "Intent", "startActivity", "FileProvider", "fileAccessCheck", "FilePolicy",
        ).forEach { f -> assertFalse("PhoneJump 里出现「$f」：判据层不执行动作，更不写盘", jump.contains(f)) }
        assertTrue("COPY_INTO_VAULT 的名字要说清是「复制进证道」", jump.contains("COPY_INTO_VAULT"))
    }

    @Test
    fun `结构_台账语句只读且不掺查询词`() {
        val sql = PhoneJump.SELECT_ARTIFACT_SESSION
        assertTrue("只许 SELECT（读侧不写主库）", sql.startsWith("SELECT ") && !sql.contains("INSERT") && !sql.contains("UPDATE") && !sql.contains("DELETE"))
        assertEquals("两个绑参都是位置（工作区＋库里的 rel_path），查询词一个字不进语句", 2, sql.count { it == '?' })
        assertFalse(sql.contains("token"))
        assertTrue(sql.contains("FROM artifact"))
        assertTrue("必须有 LIMIT：台账一行了结，不捞多行", sql.contains("LIMIT 1"))
    }

    @Test
    fun `表驱动_命中行到动作表之间的数据不带路径猜测`() {
        // JumpTarget 只带「哪个根的哪条相对路径」，不猜绝对路径：拼路径是 UI 侧唯一的一次拼接，
        // 而拼接的判据（越界）只有一把（SearchAdmission／PathGuard）。本条钉住判据层不吃绝对路径。
        val t = target(absRoot = "/storage/emulated/0", rel = "Docs/笔记.md")
        val p = PhoneJump.plan(t, null)
        assertEquals(1L, t.rootId)
        assertEquals("Docs/笔记.md", t.relPath)
        assertEquals(2, p.actions.size)
    }

    // ── helper（房型同 `SearchAdmissionTest`：源码文本比对要剥注释） ──────────────

    private fun sourceOf(name: String): String = repoFile("src/main/java/com/example/zhengdao/baopu/search/$name").readText()

    private fun code(text: String): String =
        text.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    private fun repoFile(rel: String): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            listOf(File(dir, "app/$rel"), File(dir, rel)).firstOrNull { it.exists() }?.let { return it }
            dir = dir.parentFile
        }
        throw AssertionError("找不到 $rel（工作目录 ${File(".").absolutePath}）")
    }
}
