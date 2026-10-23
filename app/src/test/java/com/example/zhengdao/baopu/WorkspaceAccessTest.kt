// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第一百五十六批判据测试：工作区外访问的四象限纯函数（派工单 §3「纯函数：内/外/开关/
// 已授权四象限」）＋ 会话级授权账本 ＋ 工具层统一出口的三分支映射。
// 157 补充（用户 2026-10-23）：账本粒度从「一次运行」改成「一次会话」——原来那条
// 「跨运行必须重新问」改成「换会话才重新问」，另加撤销/清单/无会话 fail-closed 三条。
// 全部 JVM 直测，不碰 Android API（库版账本只钉 SQL 文本，见 WorkspaceGrantTest）。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class WorkspaceAccessTest {

    private fun tmp(name: String): File =
        Files.createTempDirectory("ws-access-$name").toFile()

    /** WorkspaceAccessPolicy 是进程级读数点 ⇒ 每条用例自带恢复（finally 还原，不污染别的测试）。 */
    private fun withPolicy(
        settingOn: () -> Boolean,
        granted: (File) -> Boolean,
        block: () -> Unit,
    ) {
        val oldOn = WorkspaceAccessPolicy.settingOn
        val oldGranted = WorkspaceAccessPolicy.granted
        WorkspaceAccessPolicy.settingOn = settingOn
        WorkspaceAccessPolicy.granted = granted
        try {
            block()
        } finally {
            WorkspaceAccessPolicy.settingOn = oldOn
            WorkspaceAccessPolicy.granted = oldGranted
        }
    }

    // ── 四象限（派工单 156 §3 第一条）────────────────────────────────────

    @Test
    fun `象限1_工作区内_无论开关与授权_都INSIDE`() {
        val root = tmp("inside")
        val target = File(root, "a.md")
        assertEquals(AccessVerdict.INSIDE, workspaceAccessVerdict(root, target, userGranted = false, settingOn = false))
        assertEquals(AccessVerdict.INSIDE, workspaceAccessVerdict(root, target, userGranted = false, settingOn = true))
        assertEquals(AccessVerdict.INSIDE, workspaceAccessVerdict(root, target, userGranted = true, settingOn = false))
        assertEquals(AccessVerdict.INSIDE, workspaceAccessVerdict(root, target, userGranted = true, settingOn = true))
    }

    @Test
    fun `象限2_工作区外_开关关_DENIED`() {
        val root = tmp("off")
        val outside = File(root.parentFile, "outside-off").apply { mkdirs() }
        assertEquals(
            AccessVerdict.DENIED,
            workspaceAccessVerdict(root, outside, userGranted = false, settingOn = false),
        )
        // 已授权也不能翻案：开关关了就是关了（用户明确关掉的能力，授权账本不许越过去）
        assertEquals(
            AccessVerdict.DENIED,
            workspaceAccessVerdict(root, outside, userGranted = true, settingOn = false),
        )
    }

    @Test
    fun `象限3_工作区外_开关开_未授权_ASK_USER`() {
        val root = tmp("ask")
        val outside = File(root.parentFile, "outside-ask").apply { mkdirs() }
        assertEquals(
            AccessVerdict.ASK_USER,
            workspaceAccessVerdict(root, outside, userGranted = false, settingOn = true),
        )
    }

    @Test
    fun `象限4_工作区外_开关开_已授权_放行`() {
        val root = tmp("granted")
        val outside = File(root.parentFile, "outside-granted").apply { mkdirs() }
        assertEquals(
            AccessVerdict.INSIDE,
            workspaceAccessVerdict(root, outside, userGranted = true, settingOn = true),
        )
    }

    @Test
    fun `同前缀兄弟目录_仍算工作区外_组件级比较不许退化`() {
        // 156 批放开的只是「问一次」，不是把 PathGuard 的组件级判据换成字符串前缀
        val parent = tmp("sibling-parent")
        val root = File(parent, "ws").apply { mkdirs() }
        val evil = File(parent, "ws-evil").apply { mkdirs() }
        assertEquals(AccessVerdict.ASK_USER, workspaceAccessVerdict(root, evil, userGranted = false, settingOn = true))
    }

    // ── WorkspaceGrants：一次会话一次（157 补充，用户 2026-10-23）──────────

    /** 每条用例自带干净账本（内存版）＋一个会话号；用例结束还原（不污染别的测试）。 */
    private fun withLedger(sessionId: Long, block: () -> Unit) {
        WorkspaceGrants.resetForTest()
        WorkspaceGrants.enterSession(sessionId)
        try {
            block()
        } finally {
            WorkspaceGrants.resetForTest()
        }
    }

    @Test
    fun `账本_记账后同根放行_换会话才重新问`() {
        val root = tmp("grants")
        withLedger(SESSION_A) {
            assertFalse(WorkspaceGrants.granted(root))
            WorkspaceGrants.grant(root)
            assertTrue("同会话立刻放行（下一次工具调用直接过）", WorkspaceGrants.granted(root))
            WorkspaceGrants.enterSession(SESSION_B)
            assertFalse("新开会话必须重新问", WorkspaceGrants.granted(root))
        }
    }

    @Test
    fun `账本_同会话跨运行仍放行_不再按运行清账`() {
        val root = tmp("cross-run")
        withLedger(SESSION_A) {
            WorkspaceGrants.grant(root)
            // 156 批的 beginRun 清账已按用户裁定撤掉：重入同一会话（＝同会话的下一轮运行）
            // 必须仍是放行，否则「一次会话问一次」就又变回「一次运行问一次」。
            WorkspaceGrants.enterSession(SESSION_A)
            assertTrue("同会话跨运行仍算批过", WorkspaceGrants.granted(root))
        }
    }

    @Test
    fun `账本_缓存被清后从账本读回_重启后不再问的那条判据`() {
        val root = tmp("rehydrate")
        val store = InMemoryWorkspaceGrantStore()
        WorkspaceGrants.resetForTest()
        WorkspaceGrants.install(store)
        WorkspaceGrants.enterSession(SESSION_A)
        try {
            WorkspaceGrants.grant(root)
            WorkspaceGrants.install(store) // 模拟「进程内缓存没了、账本还在」（库里那行才是真相）
            assertTrue("缓存丢了也要从账本读回放行", WorkspaceGrants.granted(root))
        } finally {
            WorkspaceGrants.resetForTest()
        }
    }

    @Test
    fun `账本_撤销后重新问_且只影响那一个会话`() {
        val root = tmp("revoke")
        withLedger(SESSION_A) {
            WorkspaceGrants.grant(root)
            WorkspaceGrants.enterSession(SESSION_B)
            WorkspaceGrants.grant(root)
            assertEquals("撤掉 A 的一行", 1, WorkspaceGrants.revoke(SESSION_A))
            WorkspaceGrants.enterSession(SESSION_A)
            assertFalse("A 被撤销 ⇒ 下次越界重新问", WorkspaceGrants.granted(root))
            WorkspaceGrants.enterSession(SESSION_B)
            assertTrue("B 不受影响", WorkspaceGrants.granted(root))
        }
    }

    @Test
    fun `账本_清单只列放行过的会话_撤销后消失`() {
        val root = tmp("list")
        withLedger(SESSION_A) {
            assertTrue("没批过 ⇒ 空清单", WorkspaceGrants.list().isEmpty())
            WorkspaceGrants.grant(root)
            val rows = WorkspaceGrants.list()
            assertEquals(1, rows.size)
            assertEquals(SESSION_A, rows[0].sessionId)
            assertEquals(WorkspaceGrantRules.rootKey(root), rows[0].rootPath)
            WorkspaceGrants.revoke(SESSION_A)
            assertTrue("撤销后清单里不该再有它", WorkspaceGrants.list().isEmpty())
        }
    }

    @Test
    fun `账本_按规范化路径认根_同一目录不同写法算同一个根`() {
        val root = tmp("canon")
        withLedger(SESSION_A) {
            WorkspaceGrants.grant(File(root, "."))
            assertTrue("canonicalPath 归一后认的是同一个根", WorkspaceGrants.granted(root))
            val other = File(root.parentFile, "elsewhere-canon").apply { mkdirs() }
            assertFalse("别的目录不放行", WorkspaceGrants.granted(other))
        }
    }

    @Test
    fun `账本_没有会话身份时一律要问_不许静默放行`() {
        val root = tmp("no-session")
        withLedger(WorkspaceGrantRules.NO_SESSION) {
            WorkspaceGrants.grant(root) // 无会话 ⇒ 记不上账
            assertFalse("记不上账就意味着永远要问（fail closed）", WorkspaceGrants.granted(root))
            assertTrue(WorkspaceGrants.list().isEmpty())
        }
    }

    // ── 工具层统一出口（三分支映射只此一处）────────────────────────────

    @Test
    fun `工具层出口_INSIDE放行_ASK与DENIED各给各的文案`() {
        val root = tmp("boundary")
        val inside = File(root, "a.md").apply { writeText("x") }
        val outside = File(root.parentFile, "outside-boundary").apply { mkdirs() }
        val original = "路径越界被拒：原始文案一个字不动"
        withPolicy(settingOn = { true }, granted = { false }) {
            assertNull("在内放行", workspaceBoundaryCheck(root, inside, "a.md", original))
            assertTrue(
                "越界未授权 ⇒ 需用户批准（不许静默放行）",
                workspaceBoundaryCheck(root, outside, "outside", original)!!.contains("需用户批准"),
            )
        }
        withPolicy(settingOn = { false }, granted = { false }) {
            assertEquals(
                "开关关 ⇒ 原文拒绝（156 批之前的文案逐字保留）",
                original,
                workspaceBoundaryCheck(root, outside, "outside", original),
            )
        }
        withPolicy(settingOn = { true }, granted = { true }) {
            assertNull("已授权放行", workspaceBoundaryCheck(root, outside, "outside", original))
        }
    }

    // ── 负例（派工单 156 §3 负例条）────────────────────────────────────

    @Test
    fun `负例_开关关加越界_判据仍是DENIED_不许被授权翻案`() {
        val root = tmp("neg-off")
        val outside = File(root.parentFile, "outside-neg").apply { mkdirs() }
        withLedger(SESSION_A) {
            WorkspaceGrants.grant(root)
            assertEquals(
                "授权账本在、开关关 ⇒ 仍 DENIED",
                AccessVerdict.DENIED,
                workspaceAccessVerdict(root, outside, userGranted = WorkspaceGrants.granted(root), settingOn = false),
            )
        }
    }

    private companion object {
        const val SESSION_A = 41L
        const val SESSION_B = 42L
    }
}
