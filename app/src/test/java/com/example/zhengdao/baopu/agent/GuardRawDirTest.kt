// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第四十八批 P0-2（审计 §一，数据红线）：`资料库/原始/` 只读，一个字节都不许碰。
//
// 病灶：`GuardPipeline.guardPath` 把 `path` 改写成绝对路径后交工具；
// `DeletePathTool` 拿**模型原始字符串**比对「资料库/原始」⇒ 改写后以 `/` 开头，
// 守卫必然跳过；`WriteFileTool`/`EditFileTool`/`MovePathTool`/`ConvertEncodingTool`
// 只有 `PathGuard.isInside(工作区)`——默认 `资料库/原始` 就在工作区内，`isInside` 放行。
//
// 本文件全部**经 GuardPipeline 全链路**（派工单 §2：只直调工具内部不算），钉四组：
//   R1 形态矩阵 × 改盘工具 ⇒ 一律 Rejected，且原件字节/存在性未变；
//   R2 只读工具不误伤（read_file 仍可读原件区——读不改字节）；
//   R3 普通路径照常执行（批准后真的写/搬/删成功）；
//   R4 同前缀兄弟目录（`资料库/原始2/`）不误伤。
//
// 服务端工具用临时 root ＋ 临时 rawDir（派工单红线：绝不碰用户真实资料库）。
package com.example.zhengdao.baopu.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors

class GuardRawDirTest {

    /** 临时工作区 ＋ 「资料库/原始」原件区（与生产同构：raw 在 root 之内）。 */
    private class Fixture(name: String) {
        val root: File = Files.createTempDirectory("baopu-raw-$name").toFile()
        val raw: File = File(root, "资料库/原始").apply { mkdirs() }
        val pipeline: GuardPipeline

        init {
            val reg = ToolRegistry().apply {
                register(ReadFileTool.spec())
                register(WriteFileTool.spec { root })
                register(EditFileTool.spec { root })
                register(MovePathTool.spec { root })
                register(DeletePathTool.spec { root })
                register(ConvertEncodingTool.spec { root })
                register(ImageOpsTool.spec { root })
            }
            pipeline = GuardPipeline(
                reg,
                { root },
                { raw }, // 权威路径注入：生产走 KnowledgeBase.rawDir(appCtx)
                ToolRunner(Executors.newSingleThreadExecutor()),
                {},
            )
        }

        fun rawFile(rel: String = "a.txt", content: String = "原件内容"): File =
            File(raw, rel).apply {
                parentFile?.mkdirs()
                writeText(content)
            }

        fun inside(rel: String): File = File(root, rel).apply { parentFile?.mkdirs() }
    }

    private fun call(name: String, args: JSONObject): ToolCall =
        ToolCall("c-$name", name, args)

    private fun rejected(r: GuardPipeline.Result, label: String) {
        assertTrue("$label 必须 Rejected，实得：$r", r is GuardPipeline.Result.Rejected)
        val reason = (r as GuardPipeline.Result.Rejected).reason
        assertTrue("$label 的原因要写清原件区：$reason", reason.contains("原件区"))
        assertTrue("$label 的原因要写清红线：$reason", reason.contains("一个字节"))
    }

    // ── R1 形态 × 工具 ────────────────────────────────────────────────────

    @Test
    fun `R1_各种路径形态_改盘工具一律拒_且原件字节未变`() {
        val f = Fixture("matrix")
        val orig = f.rawFile("a.txt", "原件内容")
        val before = orig.readBytes()
        val forms = listOf(
            "资料库/原始/a.txt",
            "资料库/整理/../原始/a.txt", // 词法折叠后仍落在原件区
            "资料库//原始/a.txt", // 多余分隔符
            "./资料库/原始/a.txt",
            File(f.raw, "a.txt").absolutePath, // 绝对路径（旧实现正是在这里被骗过）
        )
        for (form in forms) {
            rejected(
                f.pipeline.execute(
                    call("write_file", JSONObject().put("path", form).put("content", "x")),
                    { _, _ -> null },
                ),
                "write_file($form)",
            )
            rejected(
                f.pipeline.execute(
                    call(
                        "edit_file",
                        JSONObject().put("path", form).put("op", "append").put("content", "x"),
                    ),
                    { _, _ -> null },
                ),
                "edit_file($form)",
            )
            rejected(
                f.pipeline.execute(call("delete_path", JSONObject().put("path", form)), { _, _ -> null }),
                "delete_path($form)",
            )
            rejected(
                f.pipeline.execute(call("convert_encoding", JSONObject().put("path", form)), { _, _ -> null }),
                "convert_encoding($form)",
            )
            rejected(
                f.pipeline.execute(
                    call("move_path", JSONObject().put("from", form).put("to", "资料库/整理/m.txt")),
                    { _, _ -> null },
                ),
                "move_path(from=$form)",
            )
            rejected(
                f.pipeline.execute(
                    call("move_path", JSONObject().put("from", "资料库/整理/m.txt").put("to", form)),
                    { _, _ -> null },
                ),
                "move_path(to=$form)",
            )
            // image_ops：缺省 out 落在源图同目录 ⇒ path 在原件区就必须拒（判在解码之前）
            rejected(
                f.pipeline.execute(
                    call(
                        "image_ops",
                        JSONObject().put("action", "resize").put("path", form).put("max_edge", 100),
                    ),
                    { _, _ -> null },
                ),
                "image_ops(path=$form)",
            )
        }
        assertTrue("原件必须还在：${orig.absolutePath}", orig.isFile)
        assertTrue("原件字节必须一个不差", before.contentEquals(orig.readBytes()))
        assertTrue("不能凭空在整理/里造出文件", !File(f.root, "资料库/整理/m.txt").exists())
    }

    @Test
    fun `R1b_显式 out 落原件区也拒_输出目标同样受红线管`() {
        val f = Fixture("out")
        val orig = f.rawFile("a.txt")
        val before = orig.readBytes()
        rejected(
            f.pipeline.execute(
                call(
                    "image_ops",
                    JSONObject()
                        .put("action", "resize")
                        .put("path", "附件/图.png")
                        .put("out", "资料库/原始/缩略图.png")
                        .put("max_edge", 100),
                ),
                { _, _ -> null },
            ),
            "image_ops(out 指原件区)",
        )
        assertTrue("原件区不能多出文件", !File(f.raw, "缩略图.png").exists())
        assertTrue("原件字节未变", before.contentEquals(orig.readBytes()))
    }

    @Test
    fun `R1c_原件区目录本身也拒`() {
        val f = Fixture("dir-itself")
        rejected(
            f.pipeline.execute(
                call("write_file", JSONObject().put("path", "资料库/原始").put("content", "x")),
                { _, _ -> null },
            ),
            "write_file(原件区根)",
        )
    }

    // ── R2 只读工具不误伤 ────────────────────────────────────────────────

    @Test
    fun `R2_read_file 仍能读原件区_读不改字节`() {
        val f = Fixture("readonly-ok")
        f.rawFile("a.txt", "可以读，但不许改")
        val r = f.pipeline.execute(
            ToolCall("c-read", "read_file", JSONObject().put("path", "资料库/原始/a.txt")),
            { _, _ -> null },
        )
        assertTrue("只读工具不该被红线拦：$r", r is GuardPipeline.Result.Done)
        assertTrue((r as GuardPipeline.Result.Done).wrapped.contains("可以读"))
    }

    // ── R3 普通路径照常执行（批准后真动手） ──────────────────────────────

    @Test
    fun `R3_普通路径_批准后写搬改删都正常`() {
        val f = Fixture("normal-ok")

        // write_file：附件/新文件.txt
        val write = f.pipeline.executeApproved(
            call("write_file", JSONObject().put("path", "附件/新文件.txt").put("content", "正文")),
            { _, _ -> null },
        )
        assertTrue("普通写入应当成功：$write", write is GuardPipeline.Result.Done)
        assertEquals("正文", File(f.root, "附件/新文件.txt").readText())

        // edit_file（append）：资料库/整理/草稿.txt
        val draft = f.inside("资料库/整理/草稿.txt").apply { writeText("第一行\n") }
        val edit = f.pipeline.executeApproved(
            call(
                "edit_file",
                JSONObject().put("path", "资料库/整理/草稿.txt").put("op", "append").put("content", "第二行"),
            ),
            { _, _ -> null },
        )
        assertTrue("普通编辑应当成功：$edit", edit is GuardPipeline.Result.Done)
        assertTrue("追加要真的落盘", draft.readText().contains("第二行"))

        // move_path：附件/新文件.txt → 资料库/整理/新文件.txt
        val move = f.pipeline.executeApproved(
            call(
                "move_path",
                JSONObject().put("from", "附件/新文件.txt").put("to", "资料库/整理/新文件.txt"),
            ),
            { _, _ -> null },
        )
        assertTrue("普通搬动应当成功：$move", move is GuardPipeline.Result.Done)
        assertTrue(File(f.root, "资料库/整理/新文件.txt").isFile)
        assertTrue("源文件应当不在了", !File(f.root, "附件/新文件.txt").exists())

        // delete_path：资料库/整理/新文件.txt（默认进回收站）
        val del = f.pipeline.executeApproved(
            call("delete_path", JSONObject().put("path", "资料库/整理/新文件.txt")),
            { _, _ -> null },
        )
        assertTrue("普通删除应当成功：$del", del is GuardPipeline.Result.Done)
        assertTrue("应当被移进回收站", File(f.root, "回收站").isDirectory)
    }

    // ── R4 同前缀兄弟目录不误伤 ──────────────────────────────────────────

    @Test
    fun `R4_原始2 这种同前缀兄弟目录_不算原件区`() {
        val f = Fixture("sibling")
        f.rawFile("a.txt")
        val r = f.pipeline.executeApproved(
            call("write_file", JSONObject().put("path", "资料库/原始2/a.txt").put("content", "另一处")),
            { _, _ -> null },
        )
        assertTrue("按组件比较，资料库/原始2 不是资料库/原始：$r", r is GuardPipeline.Result.Done)
        assertEquals("另一处", File(f.root, "资料库/原始2/a.txt").readText())
    }
}
