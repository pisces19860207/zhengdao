// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第九十四批 ①③ 与 §2「测试」——
// - 四个核心文件工具（write_file / edit_file / read_file / list_dir）的 Schema 契约锁定；
// - Golden cases：合法参数「执行且输出逐值一致」、缺字段／类型错／枚举外／未知参数
//   「**不执行** ＋ 错误原文回灌」；
// - 一次修正：同一轮只给一次重试机会（[RunRules.shouldRetryBadArgs]）；
// - **负例**：把 schema 的约束摘掉（required / additionalProperties）⇒ 非法参数被放行
//   ⇒ 上面那批「不执行」的断言会红 —— 这证明本文件的断言不是空转。
//
// 为什么这组用例不联网：契约验的是「参数合不合规、不合法会不会被执行」，
// 这件事与模型无关（模型只需要「被回灌后能改对」，那归 EvalGateTest 量）。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.RawToolCall
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ToolSchemaContractTest {

    private val roots = mutableListOf<File>()

    @After
    fun cleanup() {
        roots.forEach { it.deleteRecursively() }
    }

    // ── ① Schema 契约（单一来源） ────────────────────────────────────────

    @Test
    fun `核心工具_schema_契约齐全`() {
        val root = newRoot()
        val reg = registryOf(root)
        for (name in CORE_TOOLS) {
            val spec = reg.all().firstOrNull { it.name == name }
            assertNotNull("核心工具没注册：$name", spec)
            val schema = spec!!.inputSchema
            assertEquals("$name 的顶层 type 必须是 object", "object", schema.optString("type"))
            assertFalse(
                "$name 的 additionalProperties 必须是 false（否则幻觉出的参数会被静默接受）",
                schema.optBoolean("additionalProperties", true),
            )
            val required = schema.optJSONArray("required")
            assertNotNull("$name 没有 required 段", required)
            assertTrue("$name 的 required 不能为空", required!!.length() >= 1)
            val requiredKeys = (0 until required.length()).map { required.optString(it) }
            assertTrue("$name 的 required 必须含 path", requiredKeys.contains("path"))
            val props = schema.optJSONObject("properties")
            assertNotNull("$name 没有 properties", props)
            for (key in props!!.keys()) {
                val p = props.optJSONObject(key)
                assertNotNull("$name.$key 的定义不是对象", p)
                assertTrue("$name.$key 缺 type", p!!.optString("type").isNotBlank())
                p.optJSONArray("enum")?.let {
                    assertTrue("$name.$key 的 enum 不能是空数组", it.length() > 0)
                }
            }
            assertTrue("$name 的 description 为空（模型看不见用途就不会调它）", spec.description.isNotBlank())
            assertTrue(
                "$name 的 description 超过上限 ${ToolSpec.MAX_DESC}",
                spec.description.length <= ToolSpec.MAX_DESC,
            )
        }
    }

    @Test
    fun `发给模型的tools数组_schema与注册表同一份`() {
        val root = newRoot()
        val reg = registryOf(root)
        val sent = reg.toolsJsonOpenAi(SESSION)
        val byName = HashMap<String, JSONObject>()
        for (i in 0 until sent.length()) {
            val fn = sent.getJSONObject(i).getJSONObject("function")
            byName[fn.getString("name")] = fn.getJSONObject("parameters")
        }
        for (name in CORE_TOOLS) {
            val spec = reg.all().first { it.name == name }
            val onWire = byName[name]
            assertNotNull("$name 没发进 tools 数组（模型根本不知道有这个工具）", onWire)
            assertEquals(
                "$name 发出去的 schema 与注册表校验用的不是同一份（第二套定义 ⇒ 模型按 A 写、校验按 B 判）",
                spec.inputSchema.toString(),
                onWire!!.toString(),
            )
        }
    }

    // ── ③ Golden cases：合法参数 ────────────────────────────────────────

    @Test
    fun `合法参数_照常执行_且输出与纯函数逐值一致`() {
        val root = newRoot()
        seed(root, "a.md", "第一行\n第二行\n")
        seed(root, "资料库/整理/书.txt", "内容")
        seed(root, "b.md", "原第一行\n")

        // ① read_file：回灌文本就是 ReadFileTool.read 的原文（被围栏包住，内容逐字一致）
        val readCall = call("read_file", JSONObject().put("path", "a.md"))
        val readResult = pipeline(registryOf(root), root).execute(readCall) { _, _ -> null }
        val readOk = readResult as GuardPipeline.Result.Done
        val expectRead = ReadFileTool.read(File(root, "a.md"), 0, ReadFileTool.DEFAULT_LIMIT) as ToolOutcome.Ok
        assertTrue(
            "read_file 的返回原文变了：期望含「${expectRead.text}」，实到「${readOk.wrapped}」",
            readOk.wrapped.contains(expectRead.text),
        )

        // ② list_dir：条目名必须出现
        val listCall = call(
            "list_dir",
            JSONObject().put("path", "资料库").put(ListDirTool.ARG_DEPTH, 2),
        )
        val listResult = pipeline(registryOf(root), root).execute(listCall) { _, _ -> null }
        val listOk = listResult as GuardPipeline.Result.Done
        assertTrue("list_dir 没列出子项：${listOk.wrapped}", listOk.wrapped.contains("书.txt"))

        // ③ write_file：回灌文本与 WriteFileTool.write 逐值一致（新建态，不含时间戳）
        // 纯函数基线先在**另一块空地**上算：同一块地上刚被管线写过，再调一次会撞
        // 「目标已存在」，比的就不是同一件事了。
        val pureRoot = newRoot()
        val expectWrite = WriteFileTool.write(pureRoot, "out.md", "甲乙\n丙", overwrite = false) as ToolOutcome.Ok
        val writeCall = call(
            "write_file",
            JSONObject().put("path", "out.md").put("content", "甲乙\n丙"),
        )
        val writeResult = pipeline(registryOf(root), root).execute(writeCall) { _, _ -> null }
        val writeOk = writeResult as GuardPipeline.Result.Done
        assertTrue(
            "write_file 的返回文案变了：期望「${expectWrite.text}」，实到「${writeOk.wrapped}」",
            writeOk.wrapped.contains(expectWrite.text),
        )
        assertEquals("文件内容要原样落盘", "甲乙\n丙", File(root, "out.md").readText(Charsets.UTF_8))

        // ④ edit_file：append 后文件里要有新行，且旧行一个字没动
        val editCall = call(
            "edit_file",
            JSONObject().put("path", "b.md").put("op", "append").put("content", "新行"),
        )
        val editResult = pipeline(registryOf(root), root).execute(editCall) { _, _ -> null }
        assertTrue("edit_file 合法参数必须执行成功，实到：$editResult", editResult is GuardPipeline.Result.Done)
        val edited = File(root, "b.md").readText(Charsets.UTF_8)
        assertTrue("append 没写进去：$edited", edited.contains("新行"))
        assertTrue("append 把旧内容弄丢了：$edited", edited.contains("原第一行"))
    }

    // ── ③ Golden cases：非法参数（不执行 + 回灌原文） ────────────────────

    @Test
    fun `缺字段_不执行_错误原文点名缺哪个`() {
        val root = newRoot()
        seed(root, "a.md", "内容\n")
        val taps = mutableListOf<String>()
        val reg = registryOf(root, taps)

        // read_file 缺 path
        val missingPath = call("read_file", JSONObject())
        val r1 = pipeline(reg, root).execute(missingPath) { _, _ -> null }
        val f1 = r1 as GuardPipeline.Result.Failed
        assertTrue("错误原文要点名缺哪个参数：${f1.error}", f1.error.contains("path"))
        assertTrue("缺字段绝不许执行", taps.isEmpty())

        // write_file 缺 content
        val missingContent = call("write_file", JSONObject().put("path", "ghost.md"))
        val f2 = pipeline(reg, root).execute(missingContent) { _, _ -> null } as GuardPipeline.Result.Failed
        assertTrue("错误原文要点名 content：${f2.error}", f2.error.contains("content"))
        assertFalse("被拒的写请求不许真的建文件", File(root, "ghost.md").exists())
        assertTrue("缺字段绝不许执行", taps.isEmpty())
    }

    @Test
    fun `类型错与枚举外与未知参数_一律不执行_原文回灌`() {
        val root = newRoot()
        seed(root, "b.md", "原第一行\n")
        val taps = mutableListOf<String>()
        val reg = registryOf(root, taps)
        val pipe = pipeline(reg, root)

        // 类型错：overwrite 传字符串（模型最常见的错法）
        val badType = call(
            "write_file",
            JSONObject().put("path", "t.md").put("content", "x").put("overwrite", "true"),
        )
        val e1 = (pipe.execute(badType) { _, _ -> null } as GuardPipeline.Result.Failed).error
        assertTrue("类型错要点名参数与期望类型：$e1", e1.contains("overwrite") && e1.contains("布尔"))

        // 枚举外：edit_file 的 op 只能是 replace/insert_after/append
        val badEnum = call("edit_file", JSONObject().put("path", "b.md").put("op", "delete").put("content", "x"))
        val e2 = (pipe.execute(badEnum) { _, _ -> null } as GuardPipeline.Result.Failed).error
        assertTrue("枚举外要点名参数：$e2", e2.contains("op"))
        assertTrue("枚举外要把允许范围写进原文：$e2", e2.contains("append"))

        // 未知参数：模型幻觉出的 encoding
        val unknown = call("read_file", JSONObject().put("path", "b.md").put("encoding", "gbk"))
        val e3 = (pipe.execute(unknown) { _, _ -> null } as GuardPipeline.Result.Failed).error
        assertTrue("未知参数要点名：$e3", e3.contains("encoding"))

        assertTrue("三条非法参数都必须一次都没执行，实到：$taps", taps.isEmpty())
        assertFalse("被拒的写请求不许真的建文件", File(root, "t.md").exists())
        assertEquals("被拒的 edit 不许动文件", "原第一行\n", File(root, "b.md").readText(Charsets.UTF_8))
    }

    @Test
    fun `Codec解码即校验_坏参数在解码层就拦下且原文不二次包装`() {
        val root = newRoot()
        val reg = registryOf(root)
        val direct = reg.validateCall("read_file", JSONObject())
        assertNotNull(direct)

        val decoded = ToolCallCodec.decode(listOf(RawToolCall(id = "c1", name = "read_file", argumentsJson = "{}")), reg)
        val bad = decoded as ToolCallCodec.DecodeResult.Bad
        assertEquals("Codec 回灌的必须是校验函数的原文，不许再包一层自己的话", direct, bad.error)

        val ok = ToolCallCodec.decode(
            listOf(RawToolCall(id = "c2", name = "read_file", argumentsJson = """{"path":"a.md"}""")),
            reg,
        )
        assertTrue("合法参数在解码层必须放行：$ok", ok is ToolCallCodec.DecodeResult.Ok)
    }

    @Test
    fun `一次修正_同一轮只给一次重试`() {
        assertTrue("第一次坏参数要给模型改的机会", RunRules.shouldRetryBadArgs(0))
        assertFalse("第二次就停（继续喂只会烧 token）", RunRules.shouldRetryBadArgs(1))
    }

    // ── 负例：把 schema 的约束摘掉 ⇒ 上面那批断言必须红 ────────────────────

    @Test
    fun `负例_摘掉required_非法参数就会被放行_证明断言不是空转`() {
        val root = newRoot()
        seed(root, "a.md", "内容\n")
        val taps = mutableListOf<String>()
        val reg = registryOf(root, taps, stripRequired = true)
        pipeline(reg, root).execute(call("read_file", JSONObject())) { _, _ -> null }
        assertTrue(
            "摘掉 required 之后缺 path 仍然一次都没执行 ⇒ 拦住它的不是 Schema 契约本身，" +
                "本文件「缺字段_不执行」那条断言是假的",
            taps.isNotEmpty(),
        )
    }

    @Test
    fun `负例_放开additionalProperties_未知参数就会被放行`() {
        val root = newRoot()
        seed(root, "a.md", "内容\n")
        val taps = mutableListOf<String>()
        val reg = registryOf(root, taps, allowAdditional = true)
        pipeline(reg, root).execute(
            call("read_file", JSONObject().put("path", "a.md").put("encoding", "gbk")),
        ) { _, _ -> null }
        assertTrue(
            "放开 additionalProperties 之后未知参数仍被拦 ⇒ 两条断言的靶心不是同一个开关",
            taps.isNotEmpty(),
        )
    }

    // ── 夹具 ─────────────────────────────────────────────────────────────

    private fun newRoot(): File =
        kotlin.io.path.createTempDirectory("zd-contract-").toFile().also { roots += it }

    private fun seed(root: File, path: String, content: String) {
        val f = File(root, path)
        f.parentFile?.mkdirs()
        f.writeText(content, Charsets.UTF_8)
    }

    private fun call(name: String, args: JSONObject) = ToolCall(id = "t-$name", name = name, arguments = args)

    private fun pipeline(reg: ToolRegistry, root: File) = GuardPipeline(reg, { root }, { null })

    /**
     * 真注册表（不是手搓的假件）：[BaopuToolCatalog] 原样加载，只做两处**测试专用**改动：
     * 1. 解锁全部组 + 指定会话号（否则 `find` 回的是「组未解锁」的拒绝桩）；
     * 2. CONFIRM 降级为 FREE（确认卡是线上交互，JVM 里没人点），执行器包一层计数。
     *
     * @param taps 执行器每被调用一次记一个工具名——「不执行」这条判据全靠它
     * @param stripRequired 负例用：把 `required` 段摘掉
     * @param allowAdditional 负例用：把 `additionalProperties` 放开
     */
    private fun registryOf(
        root: File,
        taps: MutableList<String>? = null,
        stripRequired: Boolean = false,
        allowAdditional: Boolean = false,
    ): ToolRegistry {
        val reg = ToolRegistry(InMemoryToolPrefs())
        BaopuToolCatalog.loadInto(reg) { root }
        reg.currentSessionId = SESSION
        reg.unlockStore().unlock(SESSION, ToolGroup.values().toList())
        for (name in reg.names().toList()) {
            val spec = reg.find(name) ?: continue
            val schema = if (stripRequired || allowAdditional) {
                mutate(spec.inputSchema, stripRequired, allowAdditional)
            } else {
                spec.inputSchema
            }
            reg.register(
                ToolSpec(
                    name = spec.name,
                    description = spec.description,
                    inputSchema = schema,
                    permission = ToolPermission.FREE,
                    timeoutMs = spec.timeoutMs,
                ) { args ->
                    taps?.add(spec.name)
                    spec.executor(args)
                },
            )
        }
        return reg
    }

    private fun mutate(schema: JSONObject, stripRequired: Boolean, allowAdditional: Boolean): JSONObject {
        val out = JSONObject()
        for (key in schema.keys()) {
            if (stripRequired && key == "required") continue
            if (allowAdditional && key == "additionalProperties") continue
            out.put(key, schema.get(key))
        }
        if (allowAdditional) out.put("additionalProperties", true)
        return out
    }

    private companion object {
        /** 评测会话号（与真会话分格，不碰线上状态）。 */
        const val SESSION = 9_494L

        /** 本批锁定的四个核心文件工具（派工单 ① 原文点名的那四个）。 */
        val CORE_TOOLS = listOf("write_file", "edit_file", "read_file", "list_dir")
    }
}
