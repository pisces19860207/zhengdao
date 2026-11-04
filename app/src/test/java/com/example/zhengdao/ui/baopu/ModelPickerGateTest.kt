// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单《第一百三十一批 · 模型选择 P0》⑤「负例：内置 key、key 进日志、免费 403 死路、
// 只拉自定义不拉预设、UI 显示模型 ID ⇒ 各红」＋ 11:51 补充节 §四两条硬纪律与 §三设计规格。
//
// 为什么用**源码扫描**：本仓无 Robolectric、也不跑真机（本单红线），Compose 界面没法在 JVM 里渲染。
// 但"这一屏不许出现什么"在源码层面钉得死——同族先例见 BaopuShellP1GateTest /
// VisionConsentWiringTest / ChannelSwitchTruthGateTest。
// 两套读法：**正向**判据读原文（注释也算，依据写在注释里正好），**负向**判据只读代码
// （注释里出现"不铺 errorContainer 大色块"是合规说明，不能当成违规）。
package com.example.zhengdao.ui.baopu

import com.example.zhengdao.baopu.KeyPool
import com.example.zhengdao.baopu.ModelEntry
import com.example.zhengdao.baopu.ModelPickerRules
import com.example.zhengdao.baopu.ProviderPrefs
import com.example.zhengdao.baopu.Providers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ModelPickerGateTest {

    private val root = "src/main/java/com/example/zhengdao/"
    private val alt = "app/src/main/java/com/example/zhengdao/"

    /** 相对路径读源文件（Gradle 的 test working dir 有两种历史形态，都兜住）。 */
    private fun src(rel: String): String {
        val f = File(root + rel).let { if (it.exists()) it else File(alt + rel) }
        assertTrue("读不到 $rel（工作目录形态变了？$f）", f.exists())
        return f.readText()
    }

    private fun res(rel: String): String {
        val f = File("src/main/res/" + rel).let { if (it.exists()) it else File("app/src/main/res/" + rel) }
        assertTrue("读不到 $rel", f.exists())
        return f.readText()
    }

    /** 去掉注释（行注释＋KDoc），只留代码——负向判据用它。 */
    private fun codeOnly(text: String): String {
        val sb = StringBuilder()
        var inBlock = false
        text.replace("\r\n", "\n").lines().forEach { raw ->
            val line = raw.trim()
            if (inBlock) {
                if (line.contains("*/")) inBlock = false
                return@forEach
            }
            if (line.startsWith("/*")) {
                if (!line.contains("*/")) inBlock = true
                return@forEach
            }
            if (line.startsWith("//") || line.startsWith("*")) return@forEach
            sb.append(line.substringBefore("//")).append('\n')
        }
        return sb.toString()
    }

    /** 取两个锚点之间的那一段（顶栏公司名那颗、拉清单那个 LaunchedEffect 都用它）。 */
    private fun between(text: String, startMark: String, endMark: String): String {
        val s = text.indexOf(startMark)
        assertTrue("首锚点没了：$startMark", s >= 0)
        val e = text.indexOf(endMark, s + startMark.length)
        assertTrue("尾锚点没了：$endMark", e > s)
        return text.substring(s, e)
    }

    private val sheetRaw = src("ui/baopu/BaopuChannelSheet.kt")
    private val sheet = codeOnly(sheetRaw)
    private val draftRaw = src("ui/baopu/ModelSheetDraft.kt")
    private val screenRaw = src("ui/baopu/BaopuScreen.kt")
    private val modelAreaRaw = src("ui/baopu/BaopuModelArea.kt")
    private val topBarRulesRaw = src("ui/baopu/BaopuTopBarRules.kt")
    private val provider = src("baopu/Provider.kt")
    private val prefsRaw = src("baopu/ProviderPrefs.kt")
    private val keyPoolRaw = src("baopu/KeyPool.kt")
    private val rulesRaw = src("baopu/ModelPickerRules.kt")
    private val catalogRaw = src("baopu/ModelCatalog.kt")
    private val cacheRaw = src("baopu/ModelCatalogCache.kt")
    private val runtime = src("baopu/BaopuRuntime.kt")

    /** 本单新增/重写的文件（硬纪律只管这些，别拿旧代码的历史账算在本单头上）。 */
    private val newRaw = listOf(sheetRaw, draftRaw, rulesRaw, keyPoolRaw, catalogRaw, cacheRaw, prefsRaw)
    private val newCode = newRaw.map { codeOnly(it) } + codeOnly(runtime) + codeOnly(screenRaw)

    // ── 派工单 ⑤ 五条红负例 ─────────────────────────────────────────────────

    @Test
    fun 负例一_仓库里一把Key都没有() {
        // 红线「不内置任何 API key」＋①「用户自己注册、自己填；App 不兜底、不内置」
        Providers.all().forEach { p ->
            assertEquals("${p.id} 的 apiKey 必须是空串", "", p.apiKey)
        }
        newCode.forEach { code ->
            assertFalse("源码里出现了 \"sk-…\" 形态的 Key 字面量",
                Regex("\"sk-[A-Za-z0-9_-]{6,}").containsMatchIn(code))
            assertFalse("也没有别家形态的 Key 字面量",
                Regex("\"AIza[0-9A-Za-z_-]{8,}\"|\"sk-ant-[A-Za-z0-9_-]{8,}\"").containsMatchIn(code))
        }
        // 免费车道不再带"占位 Key＝null"那种假凭据（旧 zen 通道的病根）
        assertFalse("FREE_PLACEHOLDER_KEY 这个概念随旧通道一起删掉了",
            provider.contains("FREE_PLACEHOLDER_KEY"))
    }

    @Test
    fun 负例二_Key不进日志也不进崩溃上报() {
        // ②「本地保存，不明文进日志」＋红线（Key 不进 RunLog/库/上报）。
        // 判据落在"这一层根本没有日志设施可写"：本单新增的七个文件里一个 Log/println 都不许有。
        newRaw.map { codeOnly(it) }.forEach { code ->
            listOf("Log.", "println(", "print(", "android.util").forEach { needle ->
                assertFalse("新增代码里出现了 $needle", code.contains(needle))
            }
        }
        assertFalse("KeyPool 不 import 任何 Android 设施（它连 Context 都不吃）",
            keyPoolRaw.contains("import android"))
        assertFalse("ModelPickerRules 同上", rulesRaw.contains("import android"))
        // 界面能看到的 Key 形态只有尾 4 位（行为判据在 KeyPoolTest，这条钉调用点）
        assertTrue("Key 行只经 KeyPool.mask", sheet.contains("title = KeyPool.mask("))
        assertFalse("不许把原文 Key 摆成行标题", Regex("title = e\\.key").containsMatchIn(sheet))
        assertFalse("也不许把原文 Key 拼进任何 Text(...)", Regex("Text\\(\\s*e\\.key").containsMatchIn(sheet))
        assertFalse("不许回填已存的 Key 到输入框", Regex("addKeyText = ProviderPrefs").containsMatchIn(sheet))
    }

    @Test
    fun 负例三_没有403死路也没有旧zen通道() {
        // ①「去掉整条 403 的 opencode zen 免费通道」＋补充节「拉不到/没 key 时提示去注册填 Key」
        assertFalse("代码里不许再提 zen 网关", codeOnly(provider).contains("opencode.ai/zen"))
        assertFalse("zen 网关地址那一格整个没了", provider.contains("FREE_BASE_URL"))
        assertFalse("BUILTIN_FREE 这个位随旧通道一起没了", provider.contains("BUILTIN_FREE"))
        assertFalse("面向用户的中文文案里不许出现 403", res("values/strings.xml").contains("403"))
        assertFalse("英文文案同理", res("values-en/strings.xml").contains("403"))
        assertFalse("抽屉代码里没有 403 字样", sheet.contains("403"))
        assertTrue("没 Key 时说的一定是下一步：去 OpenRouter 注册填 Key",
            ModelPickerRules.NEED_FREE_KEY_HINT.contains("OpenRouter"))
    }

    @Test
    fun 负例四_预设也拉清单不只自定义() {
        // ③「预设 provider 也拉 {base}/models」——判据是拉取那一段不吃 isCustom
        // 第一百三十六批：Anthropic 自定义通道不拉 `/v1/models`，拉取段多了一个
        // `manualModelOnly` 开关（只挡这一种组合；预设仍照旧全拉——本用例其余判据不变）。
        val block = between(
            sheetRaw,
            "LaunchedEffect(selected, effectiveBase, keyForFetch, refreshTick, manualModelOnly)",
            "val filtered = if (aggregate) ModelPickerRules.filter(query, entries) else entries",
        )
        assertTrue("拉的是当前这一家的地址", block.contains("effectiveBase"))
        assertFalse("拉取段里不许出现 isCustom（那样就退回只拉自定义）", block.contains("isCustom"))
        assertTrue("三种形状共用一个解析入口", block.contains("ModelCatalog.fetchEntries("))
        assertTrue("缓存先行", block.contains("ModelCatalogCache.cached(") && block.contains("ModelCatalogCache.store("))
        assertTrue("停手 0.6s 再发", block.contains("delay(ModelPickerRules.DEBOUNCE_MS)"))
        assertTrue("免费档走『不缓存、每次重拉』", block.contains("val neverCache = isFreeLane"))
    }

    @Test
    fun 负例五_清单行不摆模型ID() {
        // ③「UI 只显示名」＋补充节「模型 ID 不出现在 UI」。
        // 唯一例外是**手填框**：那里的文本就是要发给端点的 model 参数（见 ModelPickerRules 的显示名注释）。
        val rows = between(sheetRaw, "// ── 二、模型（搜索框", "// ── 三、Key")
        assertTrue("行标题走显示名四级规则", rows.contains("title = ModelPickerRules.displayName(e)"))
        assertTrue("副行走「公司 · 上下文」", rows.contains("sub = ModelPickerRules.modelSubLine(e, company)"))
        assertFalse("标题不许是 ID", rows.contains("title = e.id"))
        assertFalse("副行不许拼 ID", Regex("sub = .*\\.id").containsMatchIn(rows))
        // 行为侧：四级规则认得出就不许退回 ID（这条是 2026-11-04 换上的现役 ID）
        assertEquals("DeepSeek-V4.1-Flash", ModelPickerRules.displayName("deepseek-flash"))
        assertFalse(
            "副行里没有 ID",
            ModelPickerRules.modelSubLine(
                ModelEntry("nvidia/nemotron-3.5-super-vl:free", null, 1_000_000, null),
                "OpenRouter",
            ).contains("nemotron"),
        )
    }

    // ── 补充节 §四 两条硬纪律 ───────────────────────────────────────────────

    @Test
    fun 硬纪律一_新增代码零Color0x() {
        // 「本单新增代码 grep \"Color(0x\" 必须为 0，所有颜色走 MaterialTheme.colorScheme.*」
        newRaw.forEach { text ->
            assertFalse("颜色不许写死十六进制（本单新增文件）", codeOnly(text).contains("Color(0x"))
        }
        // 抽屉用到的 token 只有这四枚（墨/次墨/黛青/浅青），没有第五种颜色来源
        val used = Regex("colorScheme\\.([a-zA-Z]+)").findAll(sheetRaw).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("onSurface", "onSurfaceVariant", "primary", "primaryContainer"), used)
        // 危险字色仍走既有函数（深浅各一套），不是本单新造的色
        assertTrue(sheet.contains("zhengdaoErrorText()"))
    }

    @Test
    fun 硬纪律二_不许出现12sp字号只有三档() {
        listOf(sheetRaw, draftRaw).forEach { text ->
            assertFalse("字号一律走 MaterialTheme.typography.*", text.contains(".sp"))
            assertFalse("尤其不许 12.sp", text.contains("12.sp"))
        }
        val roles = Regex("typography\\.([a-zA-Z]+)").findAll(sheetRaw).map { it.groupValues[1] }.toSet()
        assertEquals(
            "抱朴只许三档：正文 15／脚注 13／角标 10（titleSmall 与 bodyMedium 同档，labelSmall 用在顶栏角标那颗）",
            setOf("titleSmall", "bodyMedium", "bodySmall"),
            roles,
        )
        // 第一百三十一批那一颗「公司名＋上下文角标」由第四百三十四批换成 `BaopuModelArea`
        // （两颗命中区＋两张弹层），所以这条纪律跟着挪到新文件上测：**一个 .sp 字面都不许有**，
        // 用到的槽位必须全在《纸墨黛青》梯子上（bodySmall 13 / bodyMedium 15 / labelSmall 10 /
        // labelLarge 15——徽标那颗字母用 labelLarge，不在梯外）。
        assertFalse("顶栏模型区也不许出现 .sp 字面", modelAreaRaw.contains(".sp"))
        assertEquals(
            "模型区用的四个槽位全在梯子上（15/13/10），不许新增一档",
            setOf("bodySmall", "bodyMedium", "labelSmall", "labelLarge"),
            Regex("typography\\.([a-zA-Z]+)").findAll(modelAreaRaw).map { it.groupValues[1] }.toSet(),
        )
    }

    // ── 补充节 §三 设计规格 ──────────────────────────────────────────────────

    @Test
    fun 选中样式只有那一种() {
        // 浅青底 primaryContainer ＋左侧 2dp 黛青竖条；不许新造第三种
        val row = between(sheetRaw, "private fun PickerRow(", "/**")
        assertTrue(row.contains("MaterialTheme.colorScheme.primaryContainer"))
        assertTrue(row.contains(".width(2.dp)"))
        assertTrue(row.contains("MaterialTheme.colorScheme.primary"))
        assertEquals("全抽屉只有一处选中样式（PickerRow 自己）",
            1, Regex("colorScheme\\.primaryContainer").findAll(sheetRaw).count())
        assertTrue("条目行与输入框都 ≥48dp 命中",
            Regex("heightIn\\(min = BAO_ROW_MIN_HEIGHT_DP\\.dp\\)").findAll(sheet).count() >= 3)
        assertEquals("浮层圆角 24dp", true, sheetRaw.contains("shape = RoundedCornerShape(24.dp)"))
    }

    @Test
    fun 报警与限额不铺大色块() {
        // 全部不可用＝13sp 朱砂字色；免费限额＝13sp 次墨。都不铺容器色块、不用琥珀。
        assertFalse(sheet.contains("errorContainer"))
        assertFalse(sheet.contains("tertiary"))
        assertFalse(sheet.contains("琥珀"))
        assertTrue("免费限额那句照数据层原文摆出来",
            sheetRaw.contains("FREE_QUOTA_HINT") && sheet.contains("Providers.FREE_QUOTA_HINT"))
    }

    @Test
    fun 接口地址用中间省略() {
        assertTrue(sheetRaw.contains("ModelPickerRules.middleEllipsis(p.baseUrl, MIDDLE_ELLIPSIS_CHARS)"))
        assertTrue(sheetRaw.contains("ModelPickerRules.middleEllipsis(effectiveBase, MIDDLE_ELLIPSIS_CHARS)"))
        // 行为侧：长地址被折时头尾都保（保留 host 与路径尾巴）
        val cut = ModelPickerRules.middleEllipsis("https://generativelanguage.googleapis.com/v1beta", 34)
        assertEquals(34, cut.length)
        assertTrue(cut.startsWith("https://"))
        assertTrue(cut.endsWith("/v1beta"))
    }

    /**
     * 顶栏第二件（第四百三十四批：`[徽标] 模型名` 两颗命中区）逐条对上派工单。
     *
     * 第一百三十一批原案＝一颗「公司名＋上下文角标」，配色点名「不用黛青」。第四百三十四批
     * 把它换成模型区，并逐字要求「模型名用青系（浅青 #E3EDE7 ＋ 黛青 #2C5F54），与跑马灯朱系不混」
     * ⇒ **「不用黛青」这一条在本件上作废**（下面显式反钉，防下一个人照抄旧案把青系改回去）。
     * 其余老纪律原样保留：13sp 墨、≥48dp 热区高、不占 48dp 图标位、无下划线／无箭头、
     * 判定全在 rules 文件里（界面不自己算）。
     */
    @Test
    fun 顶栏模型区两颗逐条对上434() {
        val area = codeOnly(modelAreaRaw)
        // ── 版式：13sp 墨 ＋ 自绘字母徽标（青系实心块）──
        assertTrue("正文 13sp 墨", area.contains("typography.bodySmall") && area.contains("colorScheme.onSurface"))
        assertTrue("徽标那颗走青系实心＋白字（434 逐字：浅青/黛青，不用官方 logo）",
            area.contains("colorScheme.primary, shape)"))
        assertTrue("徽标字母取 onPrimary", area.contains("colorScheme.onPrimary"))
        // 434 把 131 的「不用黛青」反过来了：这颗必须是青系，不许蹭朱系的 error 槽。
        assertFalse("模型区不许用朱系槽（那是跑马灯的）", area.contains("colorScheme.error"))
        assertFalse("不加下划线", area.contains("TextDecoration"))
        assertFalse("不加箭头字形（也不引图标库）", Regex("▾|→|↓|[aA]rrow").containsMatchIn(area))
        // ── 判据④：两块命中区各自 ≥44dp，中间 ≥8dp ──
        assertTrue("热区高 ≥48dp（老 138 门禁）＋容器视觉 44dp（434）两档分离",
            area.contains("heightIn(min = BAO_ROW_MIN_HEIGHT_DP.dp)"))
        // 判据⑦「跑马灯与模型名区同高（44dp）、同圆角（10dp）」：两颗都从同一个常量/令牌取，不许各写一个数。
        assertTrue("模型区容器高走 44dp 常量（与跑马灯同一把）",
            area.contains(".height(BAO_TOPBAR_PILL_HEIGHT_DP.dp)"))
        assertTrue("模型区圆角走 rPill 令牌（与跑马灯同一个 10dp）",
            area.contains("RoundedCornerShape(rPill)"))
        assertFalse("模型区不许自己写死圆角数字（10 只能来自 rPill）",
            Regex("RoundedCornerShape\\(\\s*[\\d.]+\\s*\\.dp").containsMatchIn(area))
        assertEquals("两颗命中区各自独立（两颗都挂 zoneHit）", 2, Regex("modifier = Modifier\\.zoneHit\\(").findAll(area).count())
        assertTrue("命中区宽 ≥44dp 走常量", area.contains("widthIn(min = BAO_TOPBAR_PILL_HEIGHT_DP.dp)"))
        assertTrue("两段之间间距走常量", area.contains("Spacer(Modifier.width(BAO_TOPBAR_ZONE_GAP_DP.dp))"))
        assertFalse("不许占 48dp 图标位（顶栏图标只有 ☰／🛡 两颗）", area.contains("size(BAO_ICON_SLOT_DP.dp)"))
        // ── 判据①：两张弹层互斥，同一时刻只许开一张 ──
        assertEquals("弹层只两张", 2, Regex("DropdownMenu\\(").findAll(area).count())
        assertTrue("供应商那张按 open 判", area.contains("open == BaopuTopBarSheet.PROVIDER"))
        assertTrue("模型那张按 open 判", area.contains("open == BaopuTopBarSheet.MODEL"))
        // 点空白关闭：两张都把 DropdownMenu 自己的 onDismissRequest 交回同一个 setter（一个值 ⇒ 只会关一张）。
        assertEquals("两张弹层都接 onDismissRequest（不接就关不掉）",
            2, Regex("DropdownMenu\\(expanded = true, onDismissRequest = onDismiss\\)").findAll(area).count())
        assertTrue("页面那头的 setter 只写 topSheet = NONE",
            codeOnly(screenRaw).contains("onDismiss = { topSheet = BaopuTopBarSheet.NONE }"))
        // ── 判据②：整块不跳「换模型/设置」页，界面不自己判 ──
        assertFalse("模型区文件里不许有跳设置的口子", area.contains("showSettings"))
        assertTrue("公司名算在 ModelPickerRules（界面不自己算）",
            screenRaw.contains("ModelPickerRules.companyOf(companyProvider)"))
        assertTrue("显示名算在 BaopuTopBarRules（表里没有显 ID）",
            screenRaw.contains("BaopuTopBarRules.topBarModelName("))
        // ── 无障碍名走 strings（138 批口径：不硬编码在 .kt 里）──
        listOf("baopu_topbar_badge_desc", "baopu_topbar_model_desc", "baopu_topbar_unconfigured",
            "baopu_topbar_unconfigured_desc", "baopu_topbar_only_one",
        ).forEach { key ->
            assertTrue("中文缺 $key", res("values/strings.xml").contains("name=\"$key\""))
            assertTrue("英文缺 $key", res("values-en/strings.xml").contains("name=\"$key\""))
        }
        // 131 的「选模型」那颗键还在（设置页/抽屉入口名），术语不许回退成「换通道」。
        assertTrue("strings 里这颗键的中文值＝选模型（④ 术语：不说『换通道』）",
            res("values/strings.xml").contains("<string name=\"baopu_channel_button\">选模型</string>"))
    }

    /**
     * 第四百三十四批判据③：档位必须有、内部构建号不上屏、表里没有 ⇒ 显 ID（绝不猜绝不编）。
     *
     * 红＝有人往映射表里塞了一条没出处的名字，或让界面自己拼公司名（那就会显成
     * 「DeepSeek V4.1 flash」——派工单点名的错例），或把 `…-1017` 这种构建号原样带上屏。
     *
     * 2026-11-04 用户裁定（「选模型不应该是 V4 Pro 或者 V4.1 flash 这种吗？怎么会是 chat 之类的」
     * ＋「死别名直接删掉，按照最新的来」）追加下半段：**表里的 DeepSeek 两条必须钉在机上真存在的
     * ID 上**，且三张表（顶栏名表／131 人工表／预设短名单）都不许再留官方 2026-07-24 停用的一对别名。
     * 出处＝本仓实测探针 `D:\zd-tools\ds-probe.txt`（2026-10-17 `GET /models` 只有这两条）。
     */
    @Test
    fun 显示名三条_档位有_构建号不上屏_表外显ID() {
        // 派工单逐字要的显示字，钉在**实测现役 ID**上（原先钉的是假 ID `deepseek-v4.1-flash-1017`）
        assertEquals("V4.1 flash", BaopuTopBarRules.topBarModelName("deepseek-flash", "DeepSeek"))
        assertEquals("V4 Pro", BaopuTopBarRules.topBarModelName("deepseek-v4-pro", "DeepSeek"))
        assertEquals("GPT 6.1 Sol", BaopuTopBarRules.topBarModelName("gpt-6.1-sol", "OpenAI"))
        // 表里没有 ⇒ 原样回吐 ID，绝不猜、绝不编（去掉构建号那截＝编）。
        assertEquals("mystery-model-9901", BaopuTopBarRules.topBarModelName("mystery-model-9901", "DeepSeek"))
        // 停用别名不再有人包装：机器上真剩这条存储时，顶栏显的就是这个死串（看得见才知道要重选）。
        assertEquals("deepseek-chat", BaopuTopBarRules.topBarModelName("deepseek-chat", "DeepSeek"))
        // 公司名不重复：徽标那颗代表公司，正文再写一遍就是「两处说同一件事」（A 案）。
        assertEquals("2.5 Pro", BaopuTopBarRules.topBarModelName("gemini-2.5-pro", "Google Gemini"))
        assertFalse(
            "表里每一条都不许以公司词开头（摘词算式真在跑）",
            BaopuTopBarRules.TOP_BAR_NAMES.values.any { name ->
                val head = name.split(" ").first().trim('.', ',', '-', '_').lowercase()
                setOf("deepseek", "openai", "gemini", "google", "anthropic", "claude").contains(head)
            },
        )
        // ── 三张表都不许留那对死别名（2026-11-04 用户裁定；红＝有人又把它塞回去）──
        val deadAliases = setOf("deepseek-chat", "deepseek-reasoner")
        assertTrue("顶栏名表不许留停用别名：${BaopuTopBarRules.TOP_BAR_NAMES.keys.filter { it in deadAliases }}",
            BaopuTopBarRules.TOP_BAR_NAMES.keys.none { it in deadAliases })
        assertTrue("131 人工表不许留停用别名：${ModelPickerRules.CURATED_NAMES.keys.filter { it in deadAliases }}",
            ModelPickerRules.CURATED_NAMES.keys.none { it in deadAliases })
        val deepseek = Providers.all().first { it.id == "deepseek" }
        assertEquals("DeepSeek 预设短名单＝实测现役那两条（顺序照探针）",
            listOf("deepseek-flash", "deepseek-v4-pro"), ModelPickerRules.presetModels(deepseek))
        // 顶栏名单的来源仍是那张 preset 短名单（一处真相）——它现在给的就是实测现役那两条
        assertTrue("顶栏名单仍从 presetModels 取（不另起一张表）",
            screenRaw.contains("ModelPickerRules.presetModels(provider)"))
        // 徽标＝自绘字母，官方 logo 属商标，不走那条路。
        assertEquals(
            "D/O/G/Z/Q 五颗照派工单逐字",
            mapOf("DeepSeek" to "D", "OpenAI" to "O", "Gemini" to "G", "Zhipu" to "Z", "Qwen" to "Q"),
            listOf("DeepSeek", "OpenAI", "Gemini", "Zhipu", "Qwen").associateWith { BaopuTopBarRules.badgeLetterOf(it) },
        )
        assertEquals("表里没有的公司 ⇒ 取首字母（是首字母，不是猜公司）", "自", BaopuTopBarRules.badgeLetterOf("自定义"))
        // 弹层两张互斥：算式只回**一个值**（双布尔才是上一版两个同时开着的根因）。
        val open = setOf(
            BaopuTopBarRules.toggleSheet(BaopuTopBarSheet.NONE, BaopuTopBarSheet.PROVIDER),
            BaopuTopBarRules.toggleSheet(BaopuTopBarSheet.PROVIDER, BaopuTopBarSheet.PROVIDER),
            BaopuTopBarRules.toggleSheet(BaopuTopBarSheet.PROVIDER, BaopuTopBarSheet.MODEL),
            BaopuTopBarRules.toggleSheet(BaopuTopBarSheet.MODEL, BaopuTopBarSheet.PROVIDER),
        )
        assertEquals("四步算式的值域＝NONE/PROVIDER/MODEL 三选一", setOf(
            BaopuTopBarSheet.NONE, BaopuTopBarSheet.PROVIDER, BaopuTopBarSheet.MODEL,
        ), open)
        assertEquals("点正开着的那颗＝收起", BaopuTopBarSheet.NONE,
            BaopuTopBarRules.toggleSheet(BaopuTopBarSheet.MODEL, BaopuTopBarSheet.MODEL))
        // 未配置 ⇒ 两张都不开（点它只许引导去设置，不弹空列表）。
        assertEquals(BaopuTopBarSheet.NONE,
            BaopuTopBarRules.sheetForClick(false, BaopuTopBarSheet.NONE, BaopuTopBarSheet.PROVIDER))
        assertEquals(BaopuTopBarSheet.NONE,
            BaopuTopBarRules.sheetForClick(false, BaopuTopBarSheet.MODEL, BaopuTopBarSheet.PROVIDER))
        // 只配了一家 ⇒ 「仅此一家」这句话，而不是弹一张只有一行的空列表。
        assertTrue("只有一家时给话术", BaopuTopBarRules.onlyOneProviderHint(
            listOf(BaopuTopBarRules.ProviderRow("deepseek", "DeepSeek", "D", true, true)),
        ))
        assertFalse("两家以上不许再喊仅此一家", BaopuTopBarRules.onlyOneProviderHint(
            listOf(
                BaopuTopBarRules.ProviderRow("deepseek", "DeepSeek", "D", true, true),
                BaopuTopBarRules.ProviderRow("openai", "OpenAI", "O", false, true),
            ),
        ))
    }

    @Test
    fun 搜索框是输入即过滤() {
        // 补充节 §一：不需要点搜索；空结果那句一字不改
        assertTrue(sheetRaw.contains("Text(\"搜索模型（输入 free 只看免费）\")"))
        assertTrue(sheet.contains("ModelPickerRules.filter(query, entries)"))
        assertTrue("空结果说『没有匹配的模型，可手填模型名』",
            sheetRaw.contains("Footnote(ModelPickerRules.SEARCH_EMPTY_NOTICE)"))
        assertFalse("不许有『搜索』按钮（那是点击才过滤的形态）", sheet.contains("SmallAction(\"搜索\")"))
        assertTrue("搜索位活过跳玄览（草稿里有它）", draftRaw.contains("var query: String"))
        // 行为侧：输入 free 就筛出免费模型
        assertTrue(ModelPickerRules.matches("free", ModelEntry("nvidia/nemotron-3.5-super-vl:free")))
        assertFalse(ModelPickerRules.matches("free", ModelEntry("deepseek/deepseek-v4.1-flash")))
    }

    @Test
    fun 跳转只走玄览B路径() {
        // ①「用现有玄览 B 路径：BrowserHolder.pendingUrl ＋ requestBrowserTab」
        assertTrue(sheetRaw.contains("openInXuanlan(ModelPickerRules.OPENROUTER_SIGNUP_URL)"))
        newRaw.map { codeOnly(it) }.forEach { code ->
            listOf("Intent", "startActivity", "CustomTabs", "Uri.parse").forEach { n ->
                assertFalse("本单新增代码不许走系统外部浏览器：$n", code.contains(n))
            }
        }
        val helper = src("ui/baopu/BaopuPreview.kt")
        val body = between(helper, "internal fun openInXuanlan(url: String) {", "/** 卡片标题截断长度")
        assertTrue(body.contains("BrowserHolder.pendingUrl = url"))
        assertTrue("先写 url 再切 Tab（反过来会有一瞬 load 旧页）",
            body.indexOf("pendingUrl") < body.indexOf("requestBrowserTab()"))
        assertFalse("不新开 WebView", codeOnly(body).contains("WebView("))
    }

    @Test
    fun 跳玄览返回不重置回默认通道() {
        // 补充节 §三末句。判据分两处：抱朴页据 showing 重弹抽屉；抽屉起稿时先读草稿而不是现值。
        val screen = codeOnly(screenRaw)
        assertTrue("抱朴页重新组合时按草稿重弹抽屉",
            screen.contains("var showChannel by remember { mutableStateOf(ModelSheetDraft.showing) }"))
        val restored = between(sheetRaw, "val restored = remember {", "/** 某一家此刻该显示的模型名")
        assertTrue(restored.contains("ModelSheetDraft.showing"))
        // 草稿位**从源码里数出来**（不是抄一份名单）：以后新增一位，下面三条判据自动扩到它——
        // 漏了"读回"＝返回后丢字，漏了"回写"＝白填，漏了"作废"＝Key 原文过夜。
        val draftFields = Regex("var (\\w+):").findAll(draftRaw).map { it.groupValues[1] }.toList()
        assertTrue("草稿位不止七个（$draftFields）", draftFields.size >= 8)
        val clearBody = draftRaw.substringAfter("fun clear() {").substringBefore("}")
        draftFields.filterNot { it == "showing" }.forEach { field ->
            assertTrue("输入位 `$field` 走『先草稿后现值』",
                Regex("if \\(restored\\) ModelSheetDraft\\.$field").containsMatchIn(sheetRaw))
            assertTrue("sheet 要把 `$field` 回写进草稿（否则下一帧就丢了）",
                sheetRaw.contains("ModelSheetDraft.$field ="))
        }
        draftFields.forEach { field ->
            assertTrue("`$field` 得被 clear 抹掉", clearBody.contains("$field ="))
        }
        assertTrue("只有用户关掉抽屉才作废（离开页面不丢）",
            sheetRaw.contains("onDismissRequest = { ModelSheetDraft.clear(); onDismiss() }"))
        assertTrue("启用之后也作废（不留过夜）",
            screen.contains("ModelSheetDraft.clear()"))
    }

    @Test
    fun 用户可见文本里没有旧术语() {
        // ④ 术语：对用户说"模型/提供商/接口地址"；技术注释（KDoc）里仍说"通道"，那是协议语义不改。
        val literals = (Regex("Text\\(\"([^\"]*)\"").findAll(sheetRaw).map { it.groupValues[1] } +
            Regex("label = \\{ Text\\(\"([^\"]*)\"").findAll(sheetRaw).map { it.groupValues[1] } +
            Regex("Footnote\\(\"([^\"]*)\"").findAll(sheetRaw).map { it.groupValues[1] } +
            Regex("GroupLabel\\(\"([^\"]*)\"").findAll(sheetRaw).map { it.groupValues[1] }).toList()
        val offenders = literals.filter {
            it.contains("通道") || it.contains("缓存命中") || it.contains("待命中") || it.contains("baseUrl")
        }
        assertTrue("用户可见文本里不许有旧术语：$offenders", offenders.isEmpty())
        assertTrue("摆出了『提供商』", literals.any { it.contains("提供商") })
        assertTrue("摆出了『接口地址』", literals.any { it.contains("接口地址") })
        assertTrue("对用户说『自定义接口』而不是『自定义通道』", sheetRaw.contains("title = \"自定义接口\""))
    }

    @Test
    fun 多Key上限与轮询参数写在数据层() {
        // ②「冷却/顺序/禁用单把的策略写清」——UI 不许自己数上限、引擎不许自己解禁
        assertEquals(8, KeyPool.MAX_KEYS)
        assertTrue("上限那句是从常量算的，不是抄的字面量",
            sheetRaw.contains("Key（最多 \${KeyPool.MAX_KEYS} 把，轮询使用）"))
        assertTrue("全挂时的人话出自数据层", sheet.contains("KeyPool.allUnavailableNotice("))
        assertTrue("禁用/解禁只在抽屉里发生", sheet.contains("ProviderPrefs.setKeyDisabled("))
        assertFalse("引擎不自己解禁（401 那把只能用户点）", runtime.contains("setKeyDisabled"))
        assertEquals("custom", ProviderPrefs.CHANNEL_CUSTOM)
    }

    /**
     * 派工单硬规矩逐字：「顶栏的快速切换**不许**顺带改 key、不许把设置页搬上来」。
     *
     * 为什么这条要单独钉：`BaopuRuntime.switchChannel` 的第 3 个位置参数就是 `apiKey`，
     * 它一路走到 `ProviderPrefs.save()`——那里第 276 行 `if (key.isEmpty()) pool else KeyPool.add(pool, key)`
     * 是「空串＝这把不加、也不清池」的唯一依据。顶栏传空串就一个 Key 都不写；哪天有人把这里
     * 改成 `ProviderPrefs.storedKey(...)` 或直接调 `save(`，顶栏一点就动密钥池了（那是设置页的活）。
     * 同一条也算式还必须把**自定义 base/model 原样带回**：`save()` 对那两格是无条件覆盖
     * （第 280-281 行），不带回＝点一下顶栏就把设置页存好的值抹了。
     */
    @Test
    fun 顶栏快速切换一个Key都不写也不跳设置页() {
        val screen = codeOnly(screenRaw)
        val providerFast = between(screen, "val switchProviderFast: (String) -> Unit = { id ->", "val switchModelFast")
        val modelFast = between(screen, "val switchModelFast: (String) -> Unit = { id ->", "var showBackup")
        assertTrue("两段都在（找不到锚点＝顶栏那两把算式被人搬走或改名）",
            providerFast.isNotBlank() && modelFast.isNotBlank())
        listOf(
            "供应商那颗" to providerFast,
            "模型名那颗" to modelFast,
        ).forEach { (where, body) ->
            // 第 3 个位置参数＝apiKey；这里必须是空串字面量。
            assertTrue("$where 换通道必须传空串密钥（空串＝这把不加也不清池，见 ProviderPrefs.save 第 276 行）",
                Regex("context, [\\w.]+, \"\"").containsMatchIn(body))
            assertFalse("$where 不许直接写密钥池", body.contains("ProviderPrefs.save("))
            assertFalse("$where 不许读已存的 Key 再写回去", body.contains("storedKey("))
            assertFalse("$where 不许跳设置页（那是另一个入口）", body.contains("showSettings"))
        }
        assertTrue("供应商那颗要把自定义 base/model 原样带回（save() 对那两格是无条件覆盖）",
            providerFast.contains("ProviderPrefs.storedCustom(context)") &&
                providerFast.contains("customBase, customModel"))
        assertTrue("模型名那颗同样带回自定义 base", modelFast.contains("ProviderPrefs.storedCustom(context)"))
        assertTrue("预设那颗换模型只落名字＋清单里拿到的元数据（查不到就 null，不许留上一个模型的窗口数）",
            modelFast.contains("ProviderPrefs.saveModelOverride(") && modelFast.contains("hit?.contextWindow"))
    }
}
