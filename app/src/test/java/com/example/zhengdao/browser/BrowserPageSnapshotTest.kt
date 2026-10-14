// 独立开发声明：本文件为本项目从零编写。
package com.example.zhengdao.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 第⑲刀「给 agent 的眼睛」第一版（只读）验收门禁。
 *
 * 派工单要的门禁名就是本类：**摘要器做成纯函数（HTML 字符串 → 摘要）**，覆盖
 * input / select / textarea / aria-label / 隐藏节点剔除 / 超长截断 / 空页面；
 * 外加**导出内容不含密钥类字段**的断言（负例自证见交付报告：把 [PageSnapshot.scrubSecrets]
 * 改成原样返回 ⇒ 本类"明文禁项"那一组立刻变红，跑完已还原）。
 *
 * 分两段，理由与既有门禁同：
 * - **前半段是真判据**：直接调纯函数 [PageSnapshot.summarize] / [scrubSecrets]，喂真 HTML、断言真结果；
 * - **后半段是文本门禁**（写法照 `ui/SettingsDestructiveConfirmGateTest` / `BrowserNavCapsuleGuardTest`）：
 *   钉"接线还在不在"——按钮有没有、导出是不是**只有用户点才会跑**、落点是不是共享目录。
 *   文本门禁的已知局限照旧：它证明"没人把结构改回去"，不证明"点下去一定对"，后者归真机。
 *
 * ⚠️ 一处**刻意的分工**：纯摘要器不做 PII 打码（手机号/卡号），那是落盘管线里
 * [SensitiveRedactor.redactDom] 的活（先摘凭据 → 再打 PII → 然后才摘要，见
 * [com.example.zhengdao.ui.browser.BrowserPageExporter] 的 KDoc）。所以下面测"当前值"
 * 的那个用例里，`value="13800000000"` 在**摘要器这一层**会原样取出来——这是有意的：
 * 摘要器只负责"把字段读出来"，脱敏是它上游的事。真正落盘的内容由"明文禁项"那一组把关。
 */
class BrowserPageSnapshotTest {

    private fun source(rel: String): String {
        val f = File(rel).let { if (it.exists()) it else File("app/$rel") }
        assertTrue("找不到 $rel：${f.path}", f.isFile)
        return f.readText()
    }

    /** 去注释（文本门禁用）：注释里写着同名字面量不该算数。 */
    private fun stripComments(s: String): String = s
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)//.*$"), "")

    // ═════════════════════════════════════════════════════════════════════
    // 一、摘要器（纯函数）
    // ═════════════════════════════════════════════════════════════════════

    @Test
    fun `摘要_input_取名称_当前值_与状态`() {
        val html = """
            <html><head><title>登录页</title></head><body>
            <div><label>账号</label>
            <input type="text" name="user" placeholder="请输入手机号" value="13800000000" required></div>
            <input type="submit" value="登录">
            </body></html>
        """.trimIndent()
        val s = PageSnapshot.summarize(html)

        assertEquals("标题要能从 <title> 取到", "登录页", s.title)
        assertEquals("应识别出两个可交互节点", 2, s.nodesTotal)

        val text = s.nodes[0]
        assertEquals("input[type=text] 的角色是 textbox", "textbox", text.role)
        assertEquals("名称优先取 placeholder", "请输入手机号", text.name)
        assertEquals("当前值取 value 属性", "13800000000", text.value)
        assertEquals("状态取 required", "required", text.states)

        val submit = s.nodes[1]
        assertEquals("input[type=submit] 是 button", "button", submit.role)
        assertEquals("提交按钮的名称取它自己的 value", "登录", submit.name)
        assertEquals("提交按钮没有'当前值'", "", submit.value)
    }

    @Test
    fun `摘要_禁用与勾选_都进状态列`() {
        val html = """
            <input type="checkbox" name="agree" checked disabled>
            <input type="radio" name="plan" aria-checked="true" aria-required="true">
        """.trimIndent()
        val s = PageSnapshot.summarize(html)
        assertEquals(2, s.nodesTotal)
        assertEquals("checkbox", s.nodes[0].role)
        assertEquals("状态按 disabled → checked → required 固定顺序", "disabled checked", s.nodes[0].states)
        assertEquals("radio", s.nodes[1].role)
        assertEquals("radio 的 aria 状态同样要认", "checked required", s.nodes[1].states)
    }

    @Test
    fun `摘要_select_取选中项文本为当前值`() {
        val html = """
            <select name="city" aria-label="城市">
            <option value="bj">北京</option><option value="sh" selected>上海</option>
            </select>
        """.trimIndent()
        val s = PageSnapshot.summarize(html)
        assertEquals(1, s.nodesTotal)
        val node = s.nodes[0]
        assertEquals("combobox", node.role)
        assertEquals("aria-label 优先于一切", "城市", node.name)
        assertEquals("当前值 = 选中项的可见文本", "上海", node.value)
        assertEquals("select 不列子项（选项太多会淹掉摘要）", 1, s.nodesTotal)
    }

    @Test
    fun `摘要_textarea_文本内容即当前值`() {
        val html = """<textarea name="note" placeholder="写点什么" rows="3">你好，世界</textarea>"""
        val s = PageSnapshot.summarize(html)
        assertEquals(1, s.nodesTotal)
        val node = s.nodes[0]
        assertEquals("textbox", node.role)
        assertEquals("写点什么", node.name)
        assertEquals("textarea 的值是它的文本内容", "你好，世界", node.value)
        assertFalse("textarea 的内容不该再重复进正文", s.text.contains("你好，世界"))
    }

    @Test
    fun `摘要_aria-label_优先于可见文字`() {
        val html = """
            <button aria-label="提交订单">提交</button>
            <a href="/detail" aria-label="查看详情">详情</a>
            <a>没有链接的锚点</a>
        """.trimIndent()
        val s = PageSnapshot.summarize(html)
        assertEquals("无 href 的 <a> 不算可交互节点", 2, s.nodesTotal)
        assertEquals("button", s.nodes[0].role)
        assertEquals("提交订单", s.nodes[0].name)
        assertEquals("link", s.nodes[1].role)
        assertEquals("查看详情", s.nodes[1].name)
        assertTrue("无 href 的锚点文字仍应落进正文", s.text.contains("没有链接的锚点"))
    }

    @Test
    fun `摘要_隐藏节点整体剔除_节点与正文都不收`() {
        val html = """
            <div style="display:none"><input name="gone1" value="x"><p>看不见的文字</p></div>
            <div hidden><input name="gone2"></div>
            <div aria-hidden="true"><span>也看不见</span></div>
            <input type="hidden" name="csrf" value="y">
            <p>看得见的文字</p>
        """.trimIndent()
        val s = PageSnapshot.summarize(html)
        assertEquals("隐藏子树里的输入框一个都不许进节点表", 0, s.nodesTotal)
        assertEquals("正文只留可见的那一句", "看得见的文字", s.text)
    }

    @Test
    fun `摘要_去脚本与样式`() {
        val html = """
            <html><head><style>body{color:red}</style></head>
            <body><script>alert('秘密脚本')</script><p>正文一句话</p></body></html>
        """.trimIndent()
        val s = PageSnapshot.summarize(html)
        assertTrue("正文要在", s.text.contains("正文一句话"))
        assertFalse("脚本正文不许进摘要", s.text.contains("秘密脚本"))
        assertFalse("样式正文不许进摘要", s.text.contains("color:red"))
    }

    @Test
    fun `摘要_超长正文截断并标注`() {
        val long = "字".repeat(PageSnapshot.MAX_TEXT_CHARS + 3000)
        val s = PageSnapshot.summarize("<p>$long</p>")
        assertTrue("超长正文必须标位截断", s.textTruncated)
        assertEquals(
            "截断到 MAX_TEXT_CHARS 为止",
            PageSnapshot.MAX_TEXT_CHARS,
            s.text.length,
        )
        // 渲染成 tab.txt 时也要有可读的标注（agent 要知道这里被截了）
        val txt = PageSnapshot.renderTxt(s, "https://example.com", "2026-10-14 23:30:00", 1, 1)
        assertTrue("tab.txt 要写明正文被截断", txt.contains("已截断"))
    }

    @Test
    fun `摘要_节点数超上限_只列前 N 个并标位`() {
        val n = PageSnapshot.MAX_NODES + 5
        val html = (1..n).joinToString("") { "<input name='f$it' placeholder='字段$it'>" }
        val s = PageSnapshot.summarize(html)
        assertEquals("节点总数要如实报出", n, s.nodesTotal)
        assertEquals("列出的受上限约束", PageSnapshot.MAX_NODES, s.nodes.size)
        assertTrue(s.nodesTruncated)
        val txt = PageSnapshot.renderTxt(s, "u", "t", 2, 3)
        assertTrue("tab.txt 要写明只列了前 N 个", txt.contains("只列前"))
        assertTrue("标签序号/总数要写进 tab.txt", txt.contains("标签: 2/3"))
    }

    @Test
    fun `摘要_空页面与畸形输入都不许抛`() {
        val empty = PageSnapshot.summarize("")
        assertEquals("", empty.title)
        assertEquals(0, empty.nodesTotal)
        assertTrue(empty.nodes.isEmpty())
        assertEquals("", empty.text)
        assertFalse(empty.textTruncated)

        // 畸形输入：未闭合标签、裸闭合标签、残破属性 —— 一个都不许抛异常
        val broken = listOf("</div></p>", "<div><p>abc", "<a href=>x</a>", "<input value=\"开", "<<<>>", "<p>a & b &amp; c</p>")
        broken.forEach { bad ->
            val s = runCatching { PageSnapshot.summarize(bad) }.getOrElse { t ->
                throw AssertionError("畸形输入不许抛异常：${bad.take(20)} → ${t::class.simpleName}")
            }
            assertTrue("畸形输入也要能产出摘要对象", s.nodesTotal >= 0)
        }
        assertTrue("实体要解码", PageSnapshot.summarize("<p>a &amp; b &lt;c&gt;</p>").text.contains("a & b <c>"))
    }

    @Test
    fun `摘要_正文压成逐行文本并去重空白`() {
        val html = """
            <div>
              <h1>  标题  一  </h1>
              <p>第一段</p><p></p><p>第二段</p>
            </div>
        """.trimIndent()
        val s = PageSnapshot.summarize(html)
        assertEquals("块级标签之间保留换行、空白折叠、空行丢弃", "标题 一\n第一段\n第二段", s.text)
    }

    // ═════════════════════════════════════════════════════════════════════
    // 二、明文禁项（导出内容不许出现 Cookie / Authorization / localStorage / sessionStorage 值）
    // ═════════════════════════════════════════════════════════════════════

    /** 一页"脏"HTML：把四种点名凭据 + 常见同类凭据全塞进去。 */
    private val dirtyPage = """
        <html><head>
        <title>账号设置</title>
        <meta name="csrf-token" content="META_LIVE_7c1d">
        <style>.x{color:red}</style>
        </head><body>
        <input type="hidden" name="csrf_token" value="CSRF_LIVE_9f3a2b">
        <input type="password" name="password" value="PASSWORD_LIVE_3k4l">
        <div data-token="DATA_LIVE_5e8f">凭据容器</div>
        <p>Authorization: Bearer BEARER_LIVE_4a2b</p>
        <p>cookie=COOKIE_LIVE_1z9y</p>
        <p>localStorage: LOCALSTORAGE_LIVE_8q7w</p>
        <p>sessionStorage: SESSIONSTORAGE_LIVE_2m3n</p>
        <script>var t=localStorage.getItem('token');document.cookie='sid=SID_LIVE_0p1q';</script>
        <p>正常内容：欢迎来到证道</p>
        </body></html>
    """.trimIndent()

    /** 上面页面里所有**真值**（一个都不许出现在导出里）。 */
    private val plantedSecrets = listOf(
        "CSRF_LIVE_9f3a2b", "META_LIVE_7c1d", "DATA_LIVE_5e8f", "PASSWORD_LIVE_3k4l",
        "BEARER_LIVE_4a2b", "COOKIE_LIVE_1z9y", "LOCALSTORAGE_LIVE_8q7w",
        "SESSIONSTORAGE_LIVE_2m3n", "SID_LIVE_0p1q",
    )

    @Test
    fun `明文禁项_凭据类值一律打码`() {
        val clean = PageSnapshot.scrubSecrets(dirtyPage)
        plantedSecrets.forEach { secret ->
            assertFalse("凭据值泄漏到导出文本里了：$secret", clean.contains(secret))
        }
        assertTrue("普通内容不该被误伤", clean.contains("欢迎来到证道"))
        assertTrue("凭据字段的**键名**要留着（agent 才知道这里有凭据）", clean.contains("csrf_token"))
    }

    @Test
    fun `明文禁项_tab_txt_与摘要里也不许有`() {
        // 与真实管线同序：先摘凭据 → 再摘要 → 再渲染（见 BrowserPageExporter.writeAll）
        val clean = PageSnapshot.scrubSecrets(dirtyPage)
        val summary = PageSnapshot.summarize(clean)
        val txt = PageSnapshot.renderTxt(summary, "https://example.com/account", "2026-10-14 23:30:00", 1, 1)
        plantedSecrets.forEach { secret ->
            assertFalse("tab.txt 里出现了凭据值：$secret", txt.contains(secret))
            assertFalse("摘要正文里出现了凭据值：$secret", summary.text.contains(secret))
        }
        // 密码框仍在，但值已打码
        assertTrue(
            "密码字段仍应作为字段被看到；实际 nodes=" +
                summary.nodes.joinToString { "[${it.role}|${it.name}|${it.value}]" },
            summary.nodes.any { it.value == PageSnapshot.MASK },
        )
    }

    @Test
    fun `明文禁项_不打码无关字段`() {
        val html = """<input type="text" name="q" placeholder="搜索" value="证道"><p>欢迎来到证道</p>"""
        val clean = PageSnapshot.scrubSecrets(html)
        assertTrue("普通输入框的值不许被误打码", clean.contains("value=\"证道\""))
        assertTrue("普通正文不许被误打码", clean.contains("欢迎来到证道"))
    }

    @Test
    fun `明文禁项_script_与_style_正文被掏空`() {
        val clean = PageSnapshot.scrubSecrets(dirtyPage)
        assertFalse("脚本正文里最可能藏凭据读取，必须掏空", clean.contains("localStorage.getItem"))
        assertFalse("样式正文没有导出价值", clean.contains("color:red"))
        assertTrue("标签本身留着（DOM 结构还在）", clean.contains("<script"))
    }

    @Test
    fun `明文禁项_识别器认得四个点名键`() {
        listOf("cookie", "authorization", "localstorage", "sessionstorage").forEach {
            assertTrue("派工单点名的键必须被识别：$it", PageSnapshot.containsSensitiveWord(it))
            assertTrue("大小写无关", PageSnapshot.containsSensitiveWord(it.uppercase()))
        }
        assertFalse("普通词不该被当成凭据", PageSnapshot.containsSensitiveWord("username"))
    }

    @Test
    fun `tab_html_超 512KB 截断且不劈开多字节字符`() {
        val big = "汉".repeat(PageSnapshot.MAX_HTML_BYTES) // 每字 3 字节 ⇒ 必然超限
        val (out, cut) = PageSnapshot.clampHtmlBytes(big)
        assertTrue(cut)
        val bytes = out.toByteArray(Charsets.UTF_8)
        assertTrue("字节数必须卡在上限内", bytes.size <= PageSnapshot.MAX_HTML_BYTES)
        assertEquals("必须整字截断（回退到字符边界）", 0, bytes.size % 3)
        assertTrue("截出来的仍是完整汉字", out.all { it == '汉' })

        val (small, noCut) = PageSnapshot.clampHtmlBytes("<p>小</p>")
        assertFalse(noCut)
        assertEquals("<p>小</p>", small)
    }

    @Test
    fun `meta_json_字段齐全且能转义`() {
        val json = PageSnapshot.renderMetaJson(
            url = "https://example.com/a?b=1&c=\"2\"",
            title = "带\"引号\"的标题",
            exportedAt = "2026-10-14 23:30:00",
            tabIndex = 2,
            tabCount = 5,
            htmlTruncated = true,
            htmlBytes = 1234,
            textTruncated = false,
            nodes = 7,
        )
        listOf("\"url\"", "\"title\"", "\"exportedAt\"", "\"tabIndex\"", "\"tabCount\"", "\"truncated\"")
            .forEach { assertTrue("meta.json 缺字段 $it", json.contains(it)) }
        assertTrue("双引号必须转义", json.contains("\\\""))
        assertFalse("裸引号不许出现（会破坏 JSON）", json.contains("带\"引号\"的标题"))
        assertTrue("缩进两空格 + 结尾换行", json.startsWith("{\n") && json.endsWith("}\n"))
    }

    // ═════════════════════════════════════════════════════════════════════
    // 三、接线门禁（文本门禁：钉"没人把结构改回去"）
    // ═════════════════════════════════════════════════════════════════════

    @Test
    fun `接线_标签条有导出按钮`() {
        val strip = source("src/main/java/com/example/zhengdao/ui/browser/BrowserTabStrip.kt")
        assertTrue("BrowserTabStrip 必须收 onExport 回调", strip.contains("onExport: () -> Unit"))
        assertTrue("按钮必须有自己的无障碍名称", strip.contains("R.string.browser_export"))
        assertTrue("按钮必须把点击交回去（组件不自己做 I/O）", strip.contains("onClick = onExport"))
        assertFalse(
            "标签条不许碰落盘/导出器（组件只发事件）",
            stripComments(strip).contains("BrowserPageExporter"),
        )
    }

    @Test
    fun `接线_导出只由用户点击触发_不许后台自动导`() {
        val screen = source("src/main/java/com/example/zhengdao/ui/browser/BrowserScreen.kt")
        assertTrue("BrowserScreen 必须把 onExport 接上", screen.contains("onExport = exportPage"))
        val calls = Regex("BrowserPageExporter\\.export\\(").findAll(screen).count()
        assertEquals("一次一导：导出调用只允许出现一处", 1, calls)

        val callIdx = screen.indexOf("BrowserPageExporter.export(")
        val lambdaIdx = screen.indexOf("val exportPage")
        assertTrue("导出必须写在 exportPage 回调里（按钮点了才跑）", lambdaIdx in 0 until callIdx)
        val launchedIdx = screen.lastIndexOf("LaunchedEffect", callIdx)
        assertTrue(
            "导出不许挂在 LaunchedEffect / 任何自动副作用上（派工单：不做后台自动持续导出）",
            launchedIdx < lambdaIdx,
        )
    }

    @Test
    fun `接线_落点就是共享目录_三个文件名齐全`() {
        val ex = source("src/main/java/com/example/zhengdao/ui/browser/BrowserPageExporter.kt")
        val code = stripComments(ex)
        assertTrue("落点必须是共享目录 Store.root（guest 里就是 /opt/zhengdao）", ex.contains("Store.root(context)"))
        assertTrue("guest 侧路径要能对上", ex.contains("Store.GUEST_ROOT"))
        listOf("tab.txt", "tab.html", "meta.json").forEach { name ->
            assertTrue("缺落盘文件 $name", ex.contains("\"$name\""))
        }
        assertTrue("落盘必须原子（临时名 + rename，防 agent 读到半截）", ex.contains(".part") && ex.contains("renameTo(target)"))
    }

    @Test
    fun `接线_导出管线必须先摘凭据再打PII`() {
        val code = stripComments(source("src/main/java/com/example/zhengdao/ui/browser/BrowserPageExporter.kt"))
        assertTrue("落盘前必须过明文禁项过滤", code.contains("PageSnapshot.scrubSecrets("))
        assertTrue("落盘前必须过 PII 打码（用户 2026-10-13 定调）", code.contains("SensitiveRedactor().redactDom("))
        assertTrue(
            "顺序不可换：scrubSecrets 必须是 redactDom 的**内层**（先摘凭据、后打 PII）",
            code.contains("redactDom(PageSnapshot.scrubSecrets("),
        )
        assertTrue("摘要必须建立在已过滤的文本上", code.contains("PageSnapshot.summarize(html)"))
    }

    @Test
    fun `接线_导出过程一行都不碰_cookie_与本地存储`() {
        val code = stripComments(source("src/main/java/com/example/zhengdao/ui/browser/BrowserPageExporter.kt"))
        listOf("document.cookie", "localStorage", "sessionStorage", "CookieManager", "getCookie").forEach { needle ->
            assertFalse("明文禁项：导出代码里不许出现 $needle", code.contains(needle))
        }
    }

    @Test
    fun `接线_中英文案键对称`() {
        val zh = source("src/main/res/values/strings.xml")
        val en = source("src/main/res/values-en/strings.xml")
        val keys = listOf(
            "browser_export", "browser_export_running",
            "browser_export_done", "browser_export_failed", "browser_export_no_tab",
        )
        val keyRe = Regex("name=\"([^\"]+)\"")
        val zhKeys = keyRe.findAll(zh).map { it.groupValues[1] }.toList()
        val enKeys = keyRe.findAll(en).map { it.groupValues[1] }.toList()
        assertEquals("zh/en 文案条数必须相等（红线③）", zhKeys.size, enKeys.size)
        assertTrue("只有中文有的键：${zhKeys.toSet() - enKeys.toSet()}", (zhKeys.toSet() - enKeys.toSet()).isEmpty())
        assertTrue("只有英文有的键：${enKeys.toSet() - zhKeys.toSet()}", (enKeys.toSet() - zhKeys.toSet()).isEmpty())
        keys.forEach { k ->
            assertTrue("$k 必须双侧都在（红线③）", k in zhKeys && k in enKeys)
            assertTrue("$k 中文不许是空串", !zh.contains("name=\"$k\"></string>"))
            assertTrue("$k 英文不许是空串", !en.contains("name=\"$k\"></string>"))
        }
    }

    @Test
    fun `接线_底栏几何一个像素没动`() {
        val screen = source("src/main/java/com/example/zhengdao/ui/browser/BrowserScreen.kt")
        assertTrue("地址栏带仍是 50dp（红线①）", screen.contains(".height(50.dp)"))
        assertTrue("地址栏上下 padding 仍是 6dp（红线①）", screen.contains("top = 6.dp, bottom = 6.dp"))
        val capsule = source("src/main/java/com/example/zhengdao/ui/browser/BrowserNavCapsule.kt")
        assertTrue("浮动胶囊仍是 56dp（红线①）", capsule.contains(".height(56.dp)"))
        assertTrue("键命中区仍是 48dp（红线①）", capsule.contains(".size(48.dp)"))
        // 导出按钮不许出现在底栏两个文件里（它住在标签条）
        assertFalse("导出按钮不许塞进底栏地址带", screen.contains("stringResource(R.string.browser_export)"))
        assertFalse("导出按钮不许塞进浮动胶囊", capsule.contains("browser_export"))
    }
}
