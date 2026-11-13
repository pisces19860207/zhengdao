// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百三十一批 · 把工具失败线索写进 run_log.err 的门禁（派工单判据①②③④）。
//
// 这批只做一件事：`persistToolExchange` 那一行的 `err = ""` 换成
// `RunLoopRules.runLogErr(wrapped)`。所以四条判据全部围绕**那一个值**：
//   ① 真失败 ⇒ 非空且 ≤200 字符（含超长正文样本）；
//   ② 成功 ⇒ 空串（负例反过来验：把成功样本当失败写，判据②立刻红）；
//   ③ 去正文：路径明文 / 用户输入样本 / 文件正文一个都不许出现在 err 里；
//   ④ 反思器回归：它按 `ok=0` 的 err 首行做分类——换成错误码格式后分类仍取得出，
//      且**归因不被格式带偏**（传输/环境类不许被记成行为类）。
//
// 为什么判据②③要用「负例」写：这条链路上写脏了不会崩、也不会有人看见，
// 只会把私密内容静静抄进运维账；门禁自己不能红就起不到保护作用。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.FailureOwner
import com.example.zhengdao.baopu.ToolFailureAttribution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RunLogErrTest {

    // ── 判据①：工具真失败 ⇒ 该行 err 非空且 ≤200 字符 ─────────────────────

    @Test
    fun `判据1_工具真失败_err非空且不超两百字`() {
        val fixtures = listOf(
            "失败：源不存在：/storage/emulated/0/Download/合同.md",
            "失败：缺少必填参数「to」",
            "被拒：越界（原件区只读）",
            "失败：MCP 服务器「local」tools/call 超时（30s）",
            "失败：" + "内容".repeat(400), // 超长正文（无标点、无路径）
            "失败",
        )
        fixtures.forEach { wrapped ->
            val err = RunLoopRules.runLogErr(wrapped)
            assertFalse("样本本身必须是失败行：$wrapped", RunLoopRules.toolExchangeOk(wrapped))
            assertTrue("失败必须留线索，不许空着：$wrapped", err.isNotEmpty())
            assertTrue("err 不许超 ${RunLoopRules.ERR_MAX} 字符（实长 ${err.length}）：$wrapped", err.length <= RunLoopRules.ERR_MAX)
        }
    }

    @Test
    fun `判据1_负例_上限真的是上限_两百零一字必须被截`() {
        val long = "失败：" + "甲".repeat(201)
        val err = RunLoopRules.runLogErr(long)
        assertTrue("201 字的线索必须被截到上限内（实长 ${err.length}）", err.length <= RunLoopRules.ERR_MAX)
        assertTrue("截断要留痕（省略号）", err.endsWith("…"))
    }

    // ── 判据②：工具成功 ⇒ err 为空 ─────────────────────────────────────────

    @Test
    fun `判据2_工具成功_err必须为空`() {
        val okCases = listOf(
            "",
            "已写入 12 行",
            "写入成功：/storage/emulated/0/Download/a.md",
            "找不到匹配项（0 条）",
        )
        okCases.forEach { wrapped ->
            assertTrue("样本本身必须是成功行：$wrapped", RunLoopRules.toolExchangeOk(wrapped))
            assertEquals("成功不许写 err：$wrapped", "", RunLoopRules.runLogErr(wrapped))
        }
    }

    @Test
    fun `判据2_负例_成功也写err就红`() {
        // 负例：同一个样本文本，只在前面加「失败：」——判据②必须能红（不是恒真的 assertTrue）
        val okText = "已写入 12 行"
        assertEquals("成功样本必须为空", "", RunLoopRules.runLogErr(okText))
        assertTrue(
            "负例：把成功样本当失败写，err 非空 ⇒ 判据②本条立红",
            RunLoopRules.runLogErr("失败：$okText").isNotEmpty(),
        )
    }

    // ── 判据③：去正文（路径明文 / 用户输入原文 / 文件正文）─────────────────

    @Test
    fun `判据3_去正文_err不带路径不带用户原文不带文件正文`() {
        val userSample = "我的银行卡密码是 8801"
        val bodyText = "甲方机密条款：乙方不得泄露本协议任何内容"
        val fixtures = listOf(
            // 真机形状：写入类工具的失败里带着绝对路径与文件正文
            "失败：写入失败：文件 /storage/emulated/0/Download/机密合同.md 的正文包含「$bodyText」",
            // Windows 盘符路径 + 异常类名（分句边界在异常名后面）
            "失败：写入失败（IllegalStateException）：C:\\Users\\guoli\\Desktop\\私密.xlsx 打不开",
            // 没有冒号、只有路径
            "失败：看不见 /sdcard/Download/机密合同.md",
            // 用户原话被工具回显
            "失败：改名失败：$userSample",
            // 解析类：用户原话进 JSON 报错
            "失败：参数解析失败：用户原话「$userSample」不是合法 JSON",
        )
        val secrets = listOf(
            userSample,
            bodyText,
            "机密合同",
            "私密.xlsx",
            "8801",
            "/storage/emulated",
            "/sdcard",
            "C:\\Users",
            "guoli",
        )
        fixtures.forEach { wrapped ->
            val err = RunLoopRules.runLogErr(wrapped)
            assertTrue("失败行必须留线索：$wrapped", err.isNotEmpty())
            secrets.forEach { secret ->
                assertFalse("err 里不许出现「$secret」（fail-closed）：err=$err", err.contains(secret))
            }
            assertFalse("err 里不许有斜杠路径片段：err=$err", err.contains("/"))
        }
    }

    @Test
    fun `判据3_负例_直写原文就会把这些词带进账`() {
        val wrapped = "失败：写入失败：文件 /sdcard/Download/机密合同.md 打不开"
        // 负例：证明上面那组断言不是恒真——同一份原文里确实有这些字，只有过闸才消失
        assertTrue("原文本身含路径与正文（否则判据③白测）", wrapped.contains("/sdcard") && wrapped.contains("机密合同"))
        val err = RunLoopRules.runLogErr(wrapped)
        assertFalse("过闸之后不许再有", err.contains("/sdcard") || err.contains("机密合同"))
    }

    // ── 判据④：反思器回归（读 ok=0 的 err 做分类）──────────────────────────

    /** 与 [com.example.zhengdao.baopu.ReflectionRunner] 兜底分支**同一个表达式**（口径不许各写一份）。 */
    private fun reflectionKind(err: String): String =
        err.lineSequence().firstOrNull()?.trim()?.take(48).orEmpty()

    @Test
    fun `判据4_反思器回归_错误码格式下分类仍取得出且归因不跑偏`() {
        // (err 来源样本, 归因结论)——第三列是这批最容易搞坏的一条：传输/环境类失败
        // 一旦被写成行为类，620 就会去「约束模型」，冤枉也修不好。
        val cases = listOf(
            Triple("失败：缺少必填参数「to」", FailureOwner.BEHAVIOR, "缺少必填参数"),
            Triple("失败：源不存在：/sdcard/x/a.md", FailureOwner.ENVIRONMENT, "源不存在"),
            // 620 收口（2026-11-13 用户裁定）：码是主判据 ⇒ `timeout` 认成**环境类**（只记账不弹卡）。
            // 原先这里钉 UNATTRIBUTED 是 631 的时点口径（那时 620 还不认码），现按裁定改。
            Triple("失败：MCP 服务器「x」tools/call 超时（30s）", FailureOwner.ENVIRONMENT, "timeout"),
        )
        cases.forEach { (wrapped, owner, head) ->
            val err = RunLoopRules.runLogErr(wrapped)
            val kind = reflectionKind(err)
            assertTrue("失败分类不许为空：$wrapped", kind.isNotEmpty())
            assertTrue("分类要认得出线索头「$head」：kind=$kind", kind.startsWith(head))
            assertEquals(
                "归因被 err 格式带偏了（$wrapped ⇒ $err）",
                owner,
                ToolFailureAttribution.classify(err).owner,
            )
            assertTrue("归因依据不许为空", ToolFailureAttribution.classify(err).reason.isNotBlank())
        }
    }

    @Test
    fun `判据4_反思器仍按err首行取分类_负例改掉会红`() {
        val src = raw("src/main/java/com/example/zhengdao/baopu/ReflectionRunner.kt")
        val block = slice(src, "SELECT err, created_at, id, step FROM run_log", "}.getOrElse {")
        assertTrue("反思器仍读 ok=0 的行", block.contains("WHERE ok=0"))
        assertTrue("仍按 err 首行取分类", block.contains("lineSequence().firstOrNull()?.trim()?.take(48)"))
        assertFalse(
            "负例：把首行截断改成整段接进去，门禁必须看得见",
            block.replace(".lineSequence().firstOrNull()?.trim()?.take(48)", ".trim()")
                .contains("lineSequence().firstOrNull()?.trim()?.take(48)"),
        )
    }

    // ── 工具 ────────────────────────────────────────────────────────────

    private fun raw(rel: String): String =
        File(rel).let { if (it.isFile) it else File("app/$rel") }
            .readText(Charsets.UTF_8).replace("\r\n", "\n")

    private fun slice(src: String, start: String, end: String): String {
        val i = src.indexOf(start)
        assertTrue("源码里找不到起点：$start", i >= 0)
        val j = src.indexOf(end, startIndex = i + start.length)
        assertTrue("源码里找不到终点：$end", j > i)
        return src.substring(i, j)
    }
}
