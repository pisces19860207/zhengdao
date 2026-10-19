// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第四十八批 P0-4（审计 §一）：`library_organize` 的 `path` 被管线改写后必败。
//
// 病灶：`path` 是「相对 投递/ 或 整理/」的口径，而 `GuardPipeline.guardPath`
// 对所有含 `path` 的工具一律改写成**绝对路径**；`LibraryTools.normalizeRel` 见
// `/` 开头直接返回 null ⇒ promote/rename 每次都回「需要 path」。既有单测直调
// `organize(...)`，绕过了管线，所以一直全绿。
//
// 本文件**经 GuardPipeline 全链路**（派工单 §4），钉四组：
//   L1 promote（a.txt / 武侠/a.txt）成功，且 path **仍是相对值**（没被改写）；
//   L2 rename（同目录改名）成功；
//   L3 负例：`../x`、绝对路径必须被拒且原因能看懂；
//   L4 index 动作不受影响（没有 path）。
package com.example.zhengdao.baopu.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors

class LibraryOrganizePipelineTest {

    private class Fixture(name: String) {
        val root: File = Files.createTempDirectory("baopu-organize-$name").toFile()
        val deliver: File = File(root, "资料库/投递").apply { mkdirs() }
        val done: File = File(root, "资料库/整理").apply { mkdirs() }
        val pipeline: GuardPipeline

        init {
            val reg = ToolRegistry().apply {
                register(LibraryTools.organizeSpec { root })
                // 第五十九批 §1.2：library_organize 属默认不可见的 library 组 ⇒ 不解锁就只剩
                // 「未解锁」桩，本文件六条判据（promote/rename/index/越界）全会被桩打成同一种
                // Rejected，越界那两条还会把"原因写清"的判据带偏。这里给一个已解锁的会话当舞台。
                currentSessionId = 1L
                unlockStore().unlock(1L, ToolGroup.values().toList())
            }
            pipeline = GuardPipeline(
                reg,
                { root },
                { File(root, "资料库/原始") }, // 原件区：organize 不该碰它
                ToolRunner(Executors.newSingleThreadExecutor()),
                {},
            )
        }

        fun deliverFile(rel: String, content: String = "正文"): File =
            File(deliver, rel).apply {
                parentFile?.mkdirs()
                writeText(content)
            }

        fun doneFile(rel: String, content: String = "正文"): File =
            File(done, rel).apply {
                parentFile?.mkdirs()
                writeText(content)
            }
    }

    private fun call(args: JSONObject): ToolCall = ToolCall("c-organize", LibraryTools.NAME, args)

    private fun approved(f: Fixture, args: JSONObject): GuardPipeline.Result =
        f.pipeline.executeApproved(call(args), { _, _ -> null })

    // ── L1 promote ───────────────────────────────────────────────────────

    @Test
    fun `L1_promote 平铺文件_经管线成功且 path 未被改写成绝对路径`() {
        val f = Fixture("promote-flat")
        f.deliverFile("a.txt", "第一章")
        val r = approved(f, JSONObject().put("action", "promote").put("path", "a.txt"))
        assertTrue("promote 必须成功（旧病：改写后回「需要 path」）：$r", r is GuardPipeline.Result.Done)
        val done = r as GuardPipeline.Result.Done
        assertEquals("管线不能把 library_organize 的 path 改成绝对路径", "a.txt", done.call.arguments.getString("path"))
        assertEquals("第一章", File(f.done, "a.txt").readText())
        assertTrue("投递里那份应当不在了", !File(f.deliver, "a.txt").exists())
    }

    @Test
    fun `L1b_promote 带子目录的文件成功_武侠分册`() {
        val f = Fixture("promote-sub")
        f.deliverFile("武侠/a.txt", "剑法")
        val r = approved(f, JSONObject().put("action", "promote").put("path", "武侠/a.txt"))
        assertTrue("子目录 promote 必须成功：$r", r is GuardPipeline.Result.Done)
        assertEquals("剑法", File(f.done, "武侠/a.txt").readText())
    }

    // ── L2 rename ────────────────────────────────────────────────────────

    @Test
    fun `L2_rename 同目录改名成功`() {
        val f = Fixture("rename")
        f.doneFile("旧名.txt", "内容")
        val r = approved(
            f,
            JSONObject().put("action", "rename").put("path", "旧名.txt").put("new_name", "新名.txt"),
        )
        assertTrue("rename 必须成功：$r", r is GuardPipeline.Result.Done)
        assertEquals("内容", File(f.done, "新名.txt").readText())
        assertTrue("旧名应当不在了", !File(f.done, "旧名.txt").exists())
    }

    // ── L3 负例 ──────────────────────────────────────────────────────────

    @Test
    fun `L3a_点点点逃逸_必须被拒`() {
        val f = Fixture("dotdot")
        val r = approved(f, JSONObject().put("action", "promote").put("path", "../x.txt"))
        assertTrue("`..` 形态必须被拒：$r", r is GuardPipeline.Result.Rejected)
        val reason = (r as GuardPipeline.Result.Rejected).reason
        assertTrue("原因要写清越界：$reason", reason.contains("越界"))
    }

    @Test
    fun `L3b_绝对路径_被拒且文案说清只能是相对路径`() {
        val f = Fixture("absolute")
        val abs = File(f.deliver, "a.txt").absolutePath
        val r = approved(f, JSONObject().put("action", "promote").put("path", abs))
        assertTrue("绝对路径必须被拒（Failed）：$r", r is GuardPipeline.Result.Failed)
        val msg = (r as GuardPipeline.Result.Failed).error
        assertTrue("文案不能再说「需要 path」（那会让人以为没传）：$msg", !msg.contains("需要 path"))
        assertTrue("文案要说清只能相对路径：$msg", msg.contains("相对"))
    }

    // ── L4 index 不受影响 ────────────────────────────────────────────────

    @Test
    fun `L4_index 动作没有 path_照常重建索引`() {
        val f = Fixture("index")
        f.doneFile("x.txt", "正文")
        val r = approved(f, JSONObject().put("action", "index"))
        assertTrue("index 应成功：$r", r is GuardPipeline.Result.Done)
        assertTrue("索引文件要落盘", File(f.root, "资料库/索引.md").isFile)
    }
}
