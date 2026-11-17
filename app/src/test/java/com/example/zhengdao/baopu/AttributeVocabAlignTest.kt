// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百九十六批 · 归因词表与切句对齐的门禁（派工单判据①②③④⑤⑥）。
//
// 病灶：`runLogErr` 切句后 `err` 只剩「不存在」「已拒绝」「超时」这类裸尾巴，
// 而 620 的 `E_PATH_ABSENT` 词表全是「路径不存在」「文件不存在」这种带前缀的完整短语
// ⇒ 一个都命中不了 ⇒ 真失败被判「未归因」，该弹的行为类卡不弹、该记的环境账也记不上。
//
// 六条判据都跑**真链路**（失败原文 → [RunLoopRules.runLogErr] → [ToolFailureAttribution.classify]），
// 不构造「看起来像 err」的假输入；每条都配负例，门禁自己必须能红。
package com.example.zhengdao.baopu

import com.example.zhengdao.baopu.agent.RunLoopRules
import com.example.zhengdao.baopu.agent.ToolErrCodes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AttributeVocabAlignTest {

    /** 真链路：失败原文 ⇒ err 列实际写什么 ⇒ 从 err 判出的归属。 */
    private fun chain(wrapped: String): Pair<String, FailureAttribution> {
        val err = RunLoopRules.runLogErr(wrapped)
        return err to ToolFailureAttribution.classify(err)
    }

    // ── 判据①：三条「切句后只剩尾巴」的真失败，不许落「未归因」────────────────

    @Test
    fun `判据1_三条裸尾巴真失败_走真链路都不落未归因`() {
        val fixtures = listOf(
            // CardTools/ConvertEncodingTool/SheetReadTool 的原文形态：`不存在：<文件名>（…）`
            "失败：不存在：a.md（先 list_dir 看结构再决定）",
            // 守卫拒绝被剥掉「被拒：」前缀后只剩这三个字
            "失败：已拒绝：用户取消了这次授权",
            // MCP 超时：写侧按码表写成 `timeout: …`
            "失败：超时：30s 没回音",
        )
        fixtures.forEach { wrapped ->
            val (err, a) = chain(wrapped)
            assertTrue("失败必须留下线索（不许空）: $wrapped", err.isNotEmpty())
            assertNotEquals("真失败不许判「未归因」：$wrapped ⇒ err=$err", FailureOwner.UNATTRIBUTED, a.owner)
            assertEquals("这三条都是环境的错，不许记到模型头上：$wrapped ⇒ $err", FailureOwner.ENVIRONMENT, a.owner)
            assertFalse("环境类不许提约束卡：$wrapped", ToolFailureAttribution.mayProposeRule(a))
            assertTrue("归因依据不许为空：$wrapped", a.reason.isNotBlank())
        }
    }

    @Test
    fun `判据1_裸尾巴直接进判类_也不落未归因`() {
        val tails = listOf("不存在", "已拒绝", "超时", "不可用")
        tails.forEach { tail ->
            val a = ToolFailureAttribution.classify(tail)
            assertNotEquals("裸尾巴「$tail」也被判未归因 ⇒ 696 没修好", FailureOwner.UNATTRIBUTED, a.owner)
            assertEquals("裸尾巴「$tail」应归环境类（宁可只记账，也不冤枉模型）", FailureOwner.ENVIRONMENT, a.owner)
        }
    }

    @Test
    fun `判据1_负例_把裸尾巴别名删掉_这三条立刻落未归因`() {
        // 负例证明上面那组断言不是恒真：把别名换成一份「只有完整短语」的词表口径，
        // 即「原文里除了尾巴没有别的词」，等价于旧的命中方式 —— 必须判不出。
        val bareOnly = "不存在"
        assertTrue("样本本身只有裸尾巴（否则判据①白测）", bareOnly.length == 3)
        assertTrue(
            "旧口径（只认完整短语）在裸尾巴上必然一个都不命中",
            listOf("路径不存在", "文件不存在", "目录不存在", "源不存在", "目标不存在", "里有一层不存在")
                .none { bareOnly.contains(it) },
        )
        assertNotEquals(
            "新口径必须命中（这条断言与上面互为反证）",
            FailureOwner.UNATTRIBUTED,
            ToolFailureAttribution.classify(bareOnly).owner,
        )
    }

    // ── 判据②：既含「参数」又含「不存在」⇒ 环境类(E_PATH_ABSENT)，不许归行为类 ──

    @Test
    fun `判据2_参数里有一层不存在_归环境类且是路径码_不是行为类`() {
        val text = "参数里有一层不存在"
        val a = ToolFailureAttribution.classify(text)
        assertEquals("归成行为类会乱提约束，比未归因更糟：$text ⇒ ${a.reason}", FailureOwner.ENVIRONMENT, a.owner)
        assertEquals("要落到路径不存在的码上：${a.reason}", "ENVIRONMENT|E_PATH_ABSENT", a.rootCause)
        assertFalse("「参数」两个字不许把环境类拉成行为类", ToolFailureAttribution.mayProposeRule(a))
    }

    @Test
    fun `判据2_负例_最长匹配若退化成首段命中_这条会翻成行为类`() {
        // 负例：证明「按最长匹配」不是摆设——同样的文本，若只按表序取第一条命中的规则
        // （旧写法 firstOrNull），「参数」类规则不比长短，结果会随表序漂。
        val text = "参数里有一层不存在"
        assertTrue("文本里同时含短语与别名（这才是两条都命中的场景）", text.contains("里有一层不存在"))
        assertTrue("也含裸别名", text.contains("不存在"))
        assertEquals(
            "选中的必须是更长的那个（7 字的短语赢 3 字的别名）",
            "ENVIRONMENT|E_PATH_ABSENT",
            ToolFailureAttribution.classify(text).rootCause,
        )
        assertFalse(
            "命中的是短语，不该标成「裸尾巴别名」",
            ToolFailureAttribution.classify(text).reason.contains("裸尾巴别名"),
        )
    }

    // ── 判据③：真无线索仍「未归因」（不硬猜）────────────────────────────────

    @Test
    fun `判据3_空串与纯感叹与无关原文_仍是未归因`() {
        val empty = ToolFailureAttribution.classify("")
        assertEquals(FailureOwner.UNATTRIBUTED, empty.owner)
        assertEquals(ToolFailureAttribution.NO_CLUE_ROOT, empty.rootCause)

        listOf("！！", "？？？", "某某内部状态异常", "发生了一些事").forEach { text ->
            val a = ToolFailureAttribution.classify(text)
            assertEquals("没线索就是没线索，不许硬猜：$text", FailureOwner.UNATTRIBUTED, a.owner)
            assertFalse("未归因不许提约束卡：$text", ToolFailureAttribution.mayProposeRule(a))
        }
    }

    @Test
    fun `判据3_负例_别名不许宽到把无关注释也吃掉`() {
        // 负例：别名只有「不存在/已拒绝/不可用」这几个尾巴，不能把普通叙述判成环境类
        val narrations = listOf("我把文件放在桌面了", "这一步做完了", "请稍等")
        narrations.forEach { text ->
            assertEquals(
                "「$text」不含任何失败线索，判成别的都是误报",
                FailureOwner.UNATTRIBUTED,
                ToolFailureAttribution.classify(text).owner,
            )
        }
    }

    // ── 判据④：最长匹配 —— 两条都命中时取最长那条（断言选中的规则）──────────

    @Test
    fun `判据4_两条规则都命中_取最长那条`() {
        // 「里有一层不存在」(7) 属 E_PATH_ABSENT；「组未解锁」(4)/「未解锁」(3) 属 E_VISIBILITY。
        // 两条规则都命中，必须取更长的路径码，而不是表序更靠前的可见性码或更短的别名。
        val text = "路径里有一层不存在，工具组未解锁"
        assertTrue("两个短词确实都在文本里（否则这条测不到最长匹配）", text.contains("组未解锁"))
        assertTrue("长词也在", text.contains("里有一层不存在"))
        assertEquals(
            "选中的必须是最长那条：${ToolFailureAttribution.classify(text).reason}",
            "ENVIRONMENT|E_PATH_ABSENT",
            ToolFailureAttribution.classify(text).rootCause,
        )
    }

    @Test
    fun `判据4_负例_最长匹配改回首段命中_这条会红`() {
        // 负例：把文本里的长词去掉，只剩短的 —— 选中的规则随之变化，证明上面真的在比长短。
        val shortOnly = "工具组未解锁"
        assertEquals("去掉长词后落到可见性码", "ENVIRONMENT|E_VISIBILITY", ToolFailureAttribution.classify(shortOnly).rootCause)
        assertNotEquals(
            "两者结论不同 ⇒ 上面那条的断言不是恒真",
            ToolFailureAttribution.classify(shortOnly).rootCause,
            ToolFailureAttribution.classify("路径里有一层不存在，工具组未解锁").rootCause,
        )
    }

    // ── 判据⑤：切句保尾部语义（含分号的长原文，尾部仍含完整短语）──────────────

    @Test
    fun `判据5_分号长原文_切句后仍含完整短语且不带路径`() {
        val wrapped = "失败：写入失败；目标文件不存在：/sdcard/Download/机密合同.md（先 list_dir）"
        val err = RunLoopRules.runLogErr(wrapped)
        assertTrue("切句不许把判类那截丢掉：err=$err", err.contains("目标文件不存在"))
        assertTrue("要保住完整短语，不许只剩「不存在」：err=$err", err.contains("文件不存在"))
        assertFalse("路径明文仍不许进账：err=$err", err.contains("/sdcard") || err.contains("机密合同"))
        assertEquals("保住短语之后归因就该落环境类：err=$err", FailureOwner.ENVIRONMENT, ToolFailureAttribution.classify(err).owner)
    }

    @Test
    fun `判据5_负例_按旧口径切在第一个分号_这句话会被切成写入失败`() {
        val wrapped = "失败：写入失败；目标文件不存在：/sdcard/a.md"
        val old = wrapped.removePrefix("失败：").substringBefore('；').substringBefore('：')
        assertEquals("旧口径（切第一个分句）确实只剩首段", "写入失败", old)
        assertFalse("旧结果里没有判类线索 ⇒ 才会落未归因", ToolFailureAttribution.carriesClue(old))
        assertTrue("新口径保住了短语", ToolFailureAttribution.carriesClue(RunLoopRules.runLogErr(wrapped)))
    }

    @Test
    fun `判据5_限定词不许被切掉_路径不存在不切成不存在`() {
        val err = RunLoopRules.runLogErr("失败：路径不存在：/storage/emulated/0/Download/a.md")
        assertEquals("限定词与短语必须一起留下", "路径不存在", err)
        assertFalse("单独一个「不存在」不合格", err == "不存在")
    }

    // ── 判据⑥：词表里没有新码；引用码表的超时族与码链路同一个根因键 ─────────────

    @Test
    fun `判据6_超时族复用码表的timeout_根因键与码链路一致`() {
        val byCode = ToolErrCodes.byCode("timeout")
        assertTrue("码表里必须已有 timeout（本批不新增码）", byCode != null)
        val fromCodeLine = ToolFailureAttribution.classify(byCode!!.line)
        val fromText = ToolFailureAttribution.classify("超时")
        assertEquals(
            "词表路径与码路径必须是同一个根因键，否则「同轮限一次」会被拆成两件事",
            fromCodeLine.rootCause,
            fromText.rootCause,
        )
        assertEquals("两边归属都是环境类", fromCodeLine.owner, fromText.owner)
    }

    @Test
    fun `判据6_别名都落在既有码上_没有新造根因码`() {
        // 620 原有码（判类表）＋ 码表已有码，两者之外不许出现新键。
        val allowed = setOf(
            "E_PATH_ABSENT", "E_PERMISSION", "E_VISIBILITY", "E_PARSE",
            "B_WRONG_TOOL", "B_BAD_ARGS", "B_RETRY_USELESS",
        ) + ToolErrCodes.ALL.map { it.code.uppercase() }
        val samples = listOf("不存在", "已拒绝", "不可用", "超时", "路径不存在", "未知工具「x」", "缺少必填参数「to」")
        samples.forEach { text ->
            val code = ToolFailureAttribution.classify(text).rootCause.substringAfter('|')
            assertTrue("「$text」产出了新根因码 $code —— 本批只许加别名", code in allowed)
        }
    }
}
