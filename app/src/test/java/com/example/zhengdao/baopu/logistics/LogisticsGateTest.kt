// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百九十五批 · 源码门禁（派工单 §5 负例⑦ ＋ §7 隐私 ＋ §2 独立库 ＋ 排他红线）。
//
// 这一档不看行为，只看**代码里有没有长出那条路**：
// - 负例⑦「试图联网/登录 T9」⇒ 查询这条链上任何文件都不许出现该域名、也不许有网络客户端；
// - 隐私「查询词不落库、不进日志」⇒ 表里没有能装问句的列，日志语句里不许插用户的原话与单号；
// - §1.8（2026-10-25 冻结）的另一半 ⇒ 司机姓名与手机号**能进库、能进面板与台账，但不进日志**：
//   留痕那几行只许报条数与异常，所以禁词表跟着原话一起加了 `$driver`／`$phone`。
//   这条不看行为看形状：日志行里出现任何一个「按人名/手机号插值」的样子就是红。
// - 独立库 ⇒ 这条链不许引用 `BaopuDb`（版本号是它的红线，升一次就得迁移一次全机数据）；
// - 排他红线 ⇒ 不许 import 197/198/199/200/192/162 那几批在飞的文件，`BaopuChatController` 里
//   也不许出现查询的字（B 档短路直答是她合并时自己接的）。
//
// 房型照仓库既有的源码门禁（122 批 `PolicySourceGateTest`）：读本机源文件、按 token 扫，
// 不需要 Robolectric。
package com.example.zhengdao.baopu.logistics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import com.example.zhengdao.core.RunLog

class LogisticsGateTest {

    private fun mainRoot(): File = File("src/main").takeIf { it.isDirectory } ?: File("app/src/main")

    private data class Src(val path: String, val text: String)

    private fun allMain(): List<Src> = mainRoot().walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .map { Src(it.relativeTo(mainRoot()).path.replace('\\', '/'), it.readText(Charsets.UTF_8)) }
        .sortedBy { it.path }
        .toList()

    /** 查询这条链的全部文件：内核包 + 工具壳 + 面板壳。 */
    private fun chain(): List<Src> {
        val prefix = "java/com/example/zhengdao/baopu/logistics/"
        val shells = listOf(
            "java/com/example/zhengdao/baopu/agent/tools/LogisticsQueryTool.kt",
            "java/com/example/zhengdao/ui/baopu/LogisticsSheet.kt",
        )
        val files = allMain().filter { it.path.startsWith(prefix) || it.path in shells }
        assertEquals("内核 18 个文件＋两个壳，数一下就说明有文件游离在门禁外", 20, files.size)
        return files
    }

    private fun hit(token: String): List<String> =
        chain().filter { it.text.contains(token) }.map { it.path }

    // ── 负例⑦：不联网、不登 T9 ───────────────────────────────────────────────

    @Test
    fun `查询这条链里没有任何网络客户端`() {
        listOf("okhttp", "HttpURL", "URLConnection", "java.net.Socket", "Retrofit", "Volley", "MultipartBody").forEach {
            assertEquals("不许出现「$it」：${hit(it)}", emptyList<String>(), hit(it))
        }
        // `java.net` 整族都不要（`java.nio` 走的是另一条，不在这条里）
        assertEquals("不许 import java.net：${hit("import java.net")}", emptyList<String>(), hit("import java.net"))
        // 上一行只管 `import`，写全限定名 `java.net.URL(...)` 就绕过去了——195 批负例⑦取证实测到的洞。
        // 所以这一条按「家族前缀＋裸 URL」封：干净树上这两个 token 出现 0 次，封得住也不误伤。
        listOf("java.net.", "URL").forEach {
            assertEquals("不许出现「$it」：${hit(it)}", emptyList<String>(), hit(it))
        }
    }

    @Test
    fun `代码里不许出现 T9 的域名，也不许出现任何 URL`() {
        // 站点是蓝桥广达 T9（`https://www.561861.cn`）。派工单 §5 负例⑦：出现该域名的网络调用 ⇒ 直接红。
        // 门禁比负例更严：这一条链上连 `http` 这四个字母都不该出现——xlsx 的命名空间 URI 不参与读法
        // （读的是标签名与属性名），所以不需要它。
        listOf("561861", "蓝桥", "http").forEach { token ->
            assertEquals("「$token」不该在查询这条链里：${hit(token)}", emptyList<String>(), hit(token))
        }
    }

    @Test
    fun `不登 T9 也不收账号密码`() {
        listOf("login", "Login", "password", "Cookie", "Authorization", "token=").forEach {
            assertEquals("不许出现「$it」：${hit(it)}", emptyList<String>(), hit(it))
        }
    }

    @Test
    fun `不调模型，也不跑 JS`() {
        listOf("RunEngine", "ModelRouter", "evaluateJavascript", "WebView", "loadUrl").forEach {
            assertEquals("面板与工具都是本地判据：不许出现「$it」：${hit(it)}", emptyList<String>(), hit(it))
        }
    }

    // ── 不引 POI（xlsx 自己读 zip＋XML）──────────────────────────────────────

    @Test
    fun `xlsx 自己读，不引 POI`() {
        listOf("org.apache.poi", "XSSFWorkbook", "WorkbookFactory").forEach {
            assertEquals("「$it」不该出现：${hit(it)}", emptyList<String>(), hit(it))
        }
        // 只读第一张 sheet 这件事靠 zip＋rels，不靠库
        assertTrue(chain().any { it.text.contains("ZipInputStream") })
    }

    // ── 隐私：查询词不落库、不进日志 ─────────────────────────────────────────

    @Test
    fun `表里没有能装问句的列`() {
        val offenders = LogisticsSql.columnNames.filter { c ->
            listOf("query", "text", "ask", "question", "term").any { c.contains(it) }
        }
        assertEquals("库里只存单子，不存她问了什么：$offenders", emptyList<String>(), offenders)
    }

    @Test
    fun `日志语句里不许插用户的原话、单号、车牌或司机姓名手机号`() {
        // 查询的日志只许写条数与「口径可疑」这种不含单据内容的句子。
        // §1.8 把「原样显示」给了面板与台账，日志这一面**不在**那四列里：姓名与手机号一旦进了留痕，
        // 这份文件迟早会被拿去反馈或贴进群，而台账是按天留在工作区里的。
        // 逐条看每一个 RunLog.log 调用点：留痕点集合钉死，多一个文件就要在这里说明理由。
        val sites = chain().flatMap { src ->
            src.text.lines().filter { it.contains("RunLog.log(") }.map { src.path to it.trim() }
        }
        sites.forEach { (path, line) ->
            listOf("\$text", "\$term", "unmatchedTerm", "spec.", "\$waybill", "\$plate", "\$driver", "\$phone", "rowsFor(").forEach {
                assertFalse("$path 的日志「$line」里不许带「$it」", line.contains(it))
            }
        }
        assertEquals(
            "留痕只许这五处：查询可疑、建库补列、读取失败、台账落盘、库读写失败（都只报条数与异常）",
            listOf(
                "java/com/example/zhengdao/baopu/logistics/LogisticsAsk.kt",
                "java/com/example/zhengdao/baopu/logistics/LogisticsDb.kt",
                "java/com/example/zhengdao/baopu/logistics/LogisticsImport.kt",
                "java/com/example/zhengdao/baopu/logistics/LogisticsLedger.kt",
                "java/com/example/zhengdao/baopu/logistics/LogisticsStore.kt",
            ),
            sites.map { it.first }.distinct(),
        )
    }

    @Test
    fun `测试与仓库里没有真实导出件`() {
        // 判据：只用脱敏假数据。真的 .csv/.xlsx 一旦被 commit，里面就是她的客户名单与车牌。
        val root = File("src/test").takeIf { it.isDirectory } ?: File("app/src/test")
        val data = root.walkTopDown().filter { it.isFile && (LogisticsPaths.isReadable(it.name) || it.name.endsWith(".db")) }.toList()
        assertEquals("测试目录里不许放真的表：$data", emptyList<Any>(), data)
        val res = File("src/test/resources").takeIf { it.isDirectory } ?: File("app/src/test/resources")
        assertFalse("也不许开一个 resources 目录来放它们", res.isDirectory)
    }

    // ── 独立库：不动 BaopuDb ─────────────────────────────────────────────────

    @Test
    fun `查询这条链不引用主库`() {
        // 注释里出现「BaopuDb」是**声明红线**（派工单 §2 要求写明），不是引用；
        // 所以门禁扫的是引用形状：import、成员访问、构造。`onUpgrade` 是我们自己那张库的
        // SQLiteOpenHelper 回调（LogisticsDb 里必须重写），与主库无关，不列禁。
        listOf("import com.example.zhengdao.baopu.BaopuDb", "BaopuDb.", "BaopuDb(", "DatabaseHelper").forEach {
            assertEquals("独立库不许碰主库的「$it」：${hit(it)}", emptyList<String>(), hit(it))
        }
        assertEquals(1, LogisticsSql.VERSION)
    }

    // ── 排他红线（别的批在飞的文件夹不许 import）──────────────────────────────

    @Test
    fun `没碰其它批在飞的文件`() {
        val banned = listOf(
            "com.example.zhengdao.baopu.search" to "198 读侧授权阶梯",
            "com.example.zhengdao.baopu.bridge.BrowserCommands" to "199 浏览器指令",
            "SkillWritePolicy" to "192 技能自写",
            "SkillStore" to "192 技能存储",
            "TeachSheet" to "200 教学入口",
        )
        banned.forEach { (token, why) ->
            assertEquals("查询不依赖「$token」（$why）：${hit(token)}", emptyList<String>(), hit(token))
        }
        // 她明令不许改 BaopuChatController：那里不该出现查询的字
        val controller = allMain().first { it.path.endsWith("ui/baopu/BaopuChatController.kt") }
        assertFalse("B 档短路直答由蓝总管合并时自己接", controller.text.contains("Logistic"))
    }

    // ── 接线确实接上了（面板、抽屉、提示、装配）──────────────────────────────

    // ── 负例⑧-⑪（2026-10-25 新增）：号码可点，但只开拨号盘 ─────────────────────

    @Test
    fun `只开拨号盘，不外呼也不要拨号权限`() {
        // 直接外呼的那个 action（不经拨号盘、误点一下真拨出去）与它的拨号权限是禁词；
        // 禁词按原文扫，**注释里也不许出现**（要提它就用「外呼」两个字），所以那条路长不出来。
        listOf("ACTION_CALL", "CALL_PHONE", "android.permission.CALL").forEach {
            assertEquals("「$it」不该在查询这条链里：${hit(it)}", emptyList<String>(), hit(it))
        }
        // 清单里也不许有拨号权限：打开拨号盘是零权限的，加了权限反而说明路走歪了
        val manifest = mainRoot().resolve("AndroidManifest.xml").readText(Charsets.UTF_8)
        assertFalse("清单里不许申请拨号权限", manifest.contains("CALL_PHONE"))
        // 接线必须存在（负例「号码不可点」的另一半：能判形的号码真的接上了点击）。
        // 断言按**构造形态**而不是词：注释里提到 ACTION_DIAL 满足不了 `Intent(Intent.ACTION_DIAL`，
        // 这一钉是 isoC3 取证喂出来的——把构造改成 ACTION_VIEW 时，含词断言被注释满足，红不出来。
        val sheet = chain().first { it.path.endsWith("LogisticsSheet.kt") }
        assertTrue(
            "面板要把号码接到 ACTION_DIAL 的构造上（不是只在注释里提它）",
            Regex("""Intent\(Intent\.ACTION_DIAL""").containsMatchIn(sheet.text),
        )
        assertTrue(
            "点击走 detectTapGestures 调用（点＝拨号盘、长按＝复制）",
            Regex("""detectTapGestures\s*\(""").containsMatchIn(sheet.text),
        )
        // 形状判据保持纯函数：那一层不许长出 android 依赖（否则 JVM 测不了，迟早分叉成两套判据）
        val phone = chain().first { it.path.endsWith("LogisticsPhone.kt") }
        listOf("import android", "Intent(", "startActivity", "ACTION_DIAL").forEach {
            assertFalse("号码判据是纯函数，不许出现「$it」", phone.text.contains(it))
        }
    }

    @Test
    fun `四处接线各有一处，不多一处`() {
        val all = allMain()
        fun sitesOf(token: String): List<String> = all.filter { it.text.contains(token) }.map { it.path }
        // 出话只有一条路径：答案层之外不许有人直接拼三行
        assertEquals(listOf("java/com/example/zhengdao/baopu/logistics/LogisticsAsk.kt"), sitesOf("LogisticsAnswer.of("))
        // 面板与工具都从 answerOf 出话（面板走 reply：同一条出话，外加把命中行的号码捎给手指）
        assertTrue(sitesOf("LogisticsAsk.answerOf(").any { it.endsWith("LogisticsQueryTool.kt") })
        assertTrue(sitesOf("LogisticsAsk.reply(").any { it.endsWith("LogisticsSheet.kt") })
        // 抽屉里能开面板，主屏挂播报，装配层给工具注入库
        assertTrue(sitesOf("LogisticsSheet(").any { it.endsWith("BaopuDrawer.kt") })
        assertTrue(sitesOf("LogisticsNotice.forOpen(").any { it.endsWith("BaopuScreen.kt") })
        assertTrue(sitesOf("LogisticsQueryTool.store =").any { it.endsWith("BaopuRuntime.kt") })
        // 台账措辞只有一个出处（面板与导入两侧都调 report）
        assertEquals(listOf("java/com/example/zhengdao/baopu/logistics/LogisticsLedger.kt"), sitesOf("fun report(w: LedgerWrite)"))
        assertTrue(sitesOf("LogisticsLedger.report(").size >= 2)
    }

    @Test
    fun `提示是渲染之后才记一笔`() {
        // 组合期间写偏好 = 把「也许没显示」当成「已经说过」，那样今天她就再也没机会看到这条
        val notice = chain().first { it.path.endsWith("LogisticsNotice.kt") }.text
        val forOpen = notice.substringAfter("fun forOpen(")
        assertFalse("forOpen 里不许顺手 markShown", forOpen.contains("markShown"))
        val screen = allMain().first { it.path.endsWith("ui/baopu/BaopuScreen.kt") }.text
        assertTrue("markShown 要在 LaunchedEffect 里", screen.contains("LaunchedEffect(logisticsNotice)"))
        assertTrue(screen.contains("LogisticsNotice.markShown("))
    }

    @Test
    fun `列名只有一个出处，面板不许自己拼`() {
        // 「面板写计费重量、台账写重量」这种两套话，靠的是列名只有一处：[LogisticsField.label]。
        // 别名表里当然会出现「计费重量」这类写法（那是**认列**用的，不是给用户看的话），
        // 所以这条门禁钉的是两个壳：它们不许出现任何一个列名字面量，缺列名单走 label。
        val labels = LogisticsField.entries.map { it.label }
        assertEquals("十二个字段十二个名", LogisticsField.entries.size, labels.distinct().size)
        listOf(
            "java/com/example/zhengdao/ui/baopu/LogisticsSheet.kt",
            "java/com/example/zhengdao/baopu/agent/tools/LogisticsQueryTool.kt",
        ).forEach { path ->
            val src = chain().first { it.path == path }
            labels.forEach { label ->
                assertFalse("$path 不许写字面量「$label」（要写 LogisticsField.…label）", src.text.contains("\"$label\""))
            }
        }
    }

    @Test
    fun `三行纪律的禁词表由答话层独家持有`() {
        val owners = chain().filter { it.text.contains("FORBIDDEN") }.map { it.path }
        assertEquals(listOf("java/com/example/zhengdao/baopu/logistics/LogisticsAnswer.kt"), owners)
    }
}
