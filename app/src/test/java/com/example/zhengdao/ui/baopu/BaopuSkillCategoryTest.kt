// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第三十六批 §2 B 段（技能分类**旁挂**）＋ §4 判据 T4/T5/T6。
//
// 两层打法与 A 段那份同构（本批只动界面侧）：
//   1. **行为层**：真临时目录里造出 `技能/<名>/SKILL.md`，跑 setCategory／readCategories／
//      categorize，断言**盘上到底变成什么样**——T5 那句「SKILL.md 一个字节都不动」只能靠
//      前后 sha256 相减来证，源码字符串代替不了；
//   2. **源码层**：读剥掉注释后的分类文件与设置页那一段，钉「写出口只有一处且写的是 `_分类.json`」
//      「正文头不许出现 `kind=`」「索引行那套读写函数一个字没碰」。每条「不许出现 X」都配判别器自证。
//
// 负例 N3（派工单 §5 第 3 条）：把分类改成写进 `SKILL.md` 头（`kind=…`）⇒ 本文件
// `T5_写完分类之后_SKILL_md的sha一字不变` 与 `T5_正文头里不许出现kind这个字段` 一起红。
// 这是本批刻意避开的那条路，所以钉子放最前。
//
// 为什么全在 JVM 里真跑：这一步的输入输出是「一个目录＋一个 json」，不碰 Android API
// （`Dialog`/`Toast` 只在界面层，判据不依赖它们）。自己写编解码也不引库（零新增依赖；
// 本地单测的 android.jar 是桩，`org.json` 一调就抛 "not mocked"，钉不住 T4）。
package com.example.zhengdao.ui.baopu

import com.example.zhengdao.baopu.SkillRules
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * 第三十六批 B 段 · 技能分类（旁挂 `_分类.json`，只影响显示）。
 */
class BaopuSkillCategoryTest {

    // ── 源码读取（工作目录 = app/；判据一律读剥掉注释后的文本）──────────────

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.isFile) it else File("app/$rel") }

    private fun strip(path: String): String = repoFile(path).readText(Charsets.UTF_8)
        .replace("\r\n", "\n")
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    private fun cat(): String = strip("src/main/java/com/example/zhengdao/ui/baopu/BaopuSkillCategory.kt")

    private fun settings(): String =
        strip("src/main/java/com/example/zhengdao/ui/baopu/BaopuSettingsScreen.kt")

    private fun zh(): String = repoFile("src/main/res/values/strings.xml").readText(Charsets.UTF_8)

    private fun en(): String = repoFile("src/main/res/values-en/strings.xml").readText(Charsets.UTF_8)

    private fun value(fileText: String, key: String): String =
        Regex("name=\"$key\">([^<]*)<").find(fileText)?.groupValues?.get(1)
            ?: throw AssertionError("找不到键 $key（被删了？）")

    private fun placeholders(text: String): List<String> =
        Regex("%\\d\\$[a-zA-Z]").findAll(text).map { it.value }.toList()

    // ── 盘上现场 ──────────────────────────────────────────────────────────

    private lateinit var root: File
    private lateinit var skillDir: File

    @Before
    fun setUp() {
        root = File.createTempFile("zd36-cat", "").let { it.delete(); it.mkdirs(); it }
        skillDir = File(root, SkillRules.SKILL_DIR)
        assertTrue("技能目录建不出来", skillDir.mkdirs() || skillDir.isDirectory)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    /** 造一个「盘上真存在的技能」：`技能/<名>/SKILL.md`，正文就是 body。 */
    private fun makeSkill(name: String, body: String): File {
        val dir = File(skillDir, name)
        assertTrue("技能目录没建出来：$name", dir.mkdirs() || dir.isDirectory)
        return File(dir, SkillRules.SKILL_FILE).apply { writeText(body, Charsets.UTF_8) }
    }

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256")
            .digest(file.readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun tableOf(vararg pairs: Pair<String, SkillCategory>): Map<String, SkillCategory> =
        LinkedHashMap<String, SkillCategory>().apply { pairs.forEach { put(it.first, it.second) } }

    // ── T4：幂等 ＋ 坏 JSON 当空表不抛 ─────────────────────────────────────

    @Test
    fun `T4_同一状态写两次字节一致_与操作顺序无关`() {
        makeSkill("做卡流程", "正文A")
        makeSkill("查图", "正文B")
        val file = categoryFileOf(skillDir)

        // 第一遍：先写 A 再写 B
        assertTrue(setCategory(skillDir, "做卡流程", SkillCategory.MAKE_CARD) is CategoryOutcome.Saved)
        assertTrue(setCategory(skillDir, "查图", SkillCategory.LOOKUP) is CategoryOutcome.Saved)
        val first = file.readBytes().copyOf()
        val firstSha = sha256(file)

        // 第二遍：同一个状态，反过来写（先 B 后 A），再把 A 重写一遍
        assertTrue(setCategory(skillDir, "查图", SkillCategory.LOOKUP) is CategoryOutcome.Saved)
        assertTrue(setCategory(skillDir, "做卡流程", SkillCategory.MAKE_CARD) is CategoryOutcome.Saved)
        val second = file.readBytes().copyOf()

        assertContentEquals(
            "同一状态两个顺序 ⇒ 盘上字节必须一字不差（编码靠排序，不靠 HashMap 迭代顺序）",
            first,
            second,
        )
        assertEquals("同一状态 ⇒ 同一个指纹", firstSha, sha256(file))

        // 连写三遍同一个状态也不许长出一行来
        assertTrue(setCategory(skillDir, "做卡流程", SkillCategory.MAKE_CARD) is CategoryOutcome.Saved)
        assertContentEquals("重复写同一档不追加、不重排", first, file.readBytes())
        assertEquals("表读回来就是那两条", setOf("做卡流程", "查图"), readCategories(skillDir).byName.keys)
    }

    @Test
    fun `T4_编码纯函数按技能名排序_键顺序换了输出不变`() {
        val ab = encodeCategories(tableOf("甲" to SkillCategory.OTHER, "乙" to SkillCategory.LOOKUP))
        val ba = encodeCategories(tableOf("乙" to SkillCategory.LOOKUP, "甲" to SkillCategory.OTHER))
        assertEquals("两空格缩进、每行一键、带尾换行", true, ab.startsWith("{\n  \"") && ab.endsWith("}\n"))
        assertEquals("排序之后两个输入同一个输出", ab, ba)
        assertEquals("空表也要是合法 JSON", "{\n}\n", encodeCategories(emptyMap()))
        val round = decodeCategories(ab)
        assertFalse("往返之后不算坏", round.corrupt)
        assertEquals("甲", SkillCategory.OTHER, round.of("甲"))
        assertEquals("乙", SkillCategory.LOOKUP, round.of("乙"))
    }

    @Test
    fun `T4_坏JSON一律当空表_且不许抛`() {
        val bad = listOf(
            "[]" to "数组不是这一种形状",
            "{\"a\":{\"b\":\"c\"}}" to "嵌套对象",
            "{\"a\":1}" to "值是数字",
            "{\"a\":\"做卡\",}" to "尾随逗号（不装作读懂了）",
            "{a:\"做卡\"}" to "键没引号",
            "{\"a\"" to "半截文件",
            "这不是 JSON" to "裸词",
            "{\"a\":\"未\"知\"}" to "不认识的转义/乱引号",
            "{\"a\":\"做卡\"}{\"b\":\"其他\"}" to "两个对象拼一起",
            "" to "空串",
        )
        bad.forEach { (text, why) ->
            val wantCorrupt = text.trim().isNotEmpty()      // 空内容＝「没写过」，不算坏文件
            val t = runCatching { decodeCategories(text) }
            assertTrue("$why ⇒ 不许抛（读到的异常=${t.exceptionOrNull()}）", t.isSuccess)
            val table = t.getOrThrow()
            assertTrue("$why ⇒ 只能当空表用", table.byName.isEmpty())
            assertEquals("$why ⇒ corrupt 位读错", wantCorrupt, table.corrupt)
        }
        // 判别器自证：上面那条「不许抛」在实现真抛时能抓到
        val thrown = runCatching { throw IllegalStateException("模拟实现抛") }.isFailure
        assertTrue("判别器抓得住抛异常", thrown)
    }

    @Test
    fun `T4_没这个文件或空对象是空表_corrupt是false`() {
        val nothing = readCategories(File(root, "根本没有这个目录"))
        assertTrue("目录不存在 ⇒ 空表", nothing.byName.isEmpty())
        assertFalse("目录不存在不算坏文件", nothing.corrupt)

        categoryFileOf(skillDir).writeText("   \n ", Charsets.UTF_8)
        assertFalse("空白文件不算坏", readCategories(skillDir).corrupt)

        categoryFileOf(skillDir).writeText("{\n}\n", Charsets.UTF_8)
        val emptyObj = readCategories(skillDir)
        assertTrue("空对象 ⇒ 空表", emptyObj.byName.isEmpty())
        assertFalse("空对象是合法 JSON，不该报「读不通」", emptyObj.corrupt)

        categoryFileOf(skillDir).writeText("{\"a\": 做卡}", Charsets.UTF_8)
        assertTrue("坏 JSON ⇒ corrupt=true（界面要补那一句红字）", readCategories(skillDir).corrupt)
    }

    @Test
    fun `T4_认不出的值只当未分类_不牵连整表`() {
        categoryFileOf(skillDir).writeText(
            "{\"会用的\":\"做卡\",\"没这一档的\":\"做饭\",\"空的\":\"\"}",
            Charsets.UTF_8,
        )
        val t = readCategories(skillDir)
        assertFalse("值认不出不等于文件坏", t.corrupt)
        assertEquals("认得的照用", SkillCategory.MAKE_CARD, t.of("会用的"))
        assertEquals("认不出的 ⇒ 未分类", SkillCategory.UNSET, t.of("没这一档的"))
        assertEquals("空值 ⇒ 未分类", SkillCategory.UNSET, t.of("空的"))
        assertEquals("表里没有的名字 ⇒ 也是未分类（缺省那档）", SkillCategory.UNSET, t.of("压根没写过"))
    }

    // ── T5：只影响显示，正文一个字不动 ────────────────────────────────────

    @Test
    fun `T5_写完分类之后_SKILL_md的sha一字不变`() {
        val body = "<!-- status=draft uses=1 -->\n\n# 做卡流程\n\n先读原文再动刀。\n"
        val md = makeSkill("做卡流程", body)
        val mdShaBefore = sha256(md)
        val otherMd = makeSkill("另一技能", "另一份正文")
        val otherShaBefore = sha256(otherMd)
        val persona = File(root, "SOUL.md").apply { writeText("人设：索引行是【技能】做卡流程：…", Charsets.UTF_8) }
        val personaShaBefore = sha256(persona)
        val listingBefore = (skillDir.list()?.toList() ?: emptyList()).sorted()

        val saved = setCategory(skillDir, "做卡流程", SkillCategory.MAKE_CARD)

        assertTrue("这一步要成：$saved", saved is CategoryOutcome.Saved)
        assertEquals("SKILL.md 一个字节都不动（改前 $mdShaBefore）", mdShaBefore, sha256(md))
        assertEquals("正文内容原样", body, md.readText(Charsets.UTF_8))
        assertEquals("别的技能正文也不许被顺手改", otherShaBefore, sha256(otherMd))
        assertEquals("人设那一份更不许碰（索引行在那里面）", personaShaBefore, sha256(persona))
        assertEquals("正文头里不许多出 kind 这种字段", false, md.readText(Charsets.UTF_8).contains("kind="))

        val newOnDisk = ((skillDir.list()?.toList() ?: emptyList()).sorted() - listingBefore.toSet())
        assertEquals("技能目录里唯一新增的就是旁挂那一份", listOf("_分类.json"), newOnDisk)
        assertEquals(
            "旁挂文件内容就是一张名→档的表",
            SkillCategory.MAKE_CARD,
            readCategories(skillDir).of("做卡流程"),
        )
    }

    @Test
    fun `T5_正文头里不许出现kind这个字段`() {
        val src = cat() + settings()
        assertFalse("分类不进正文头（这条就是负例 N3 的那条路）", src.contains("kind="))
        assertFalse("也不许写成 status/kind 那种头字段", src.contains("kind"))
        assertFalse("正文头注释那个格式本批不读不写", src.contains("<!--"))
        // 判别器自证：N3 那种写法确实被上面第一条抓到
        assertTrue("判别器抓得住 kind= 写法", Regex("kind=").containsMatchIn("sb.append(\"<!-- kind=X -->\")"))
    }

    @Test
    fun `T5_写出口只有一处且写的是_分类json`() {
        val src = cat()
        val writes = Regex("\\.writeText\\(").findAll(src).toList()
        assertEquals("整个分类文件里只允许一次写盘", 1, writes.size)
        assertTrue("那一次写的就是编码结果", src.contains("file.writeText(text, Charsets.UTF_8)"))
        assertTrue("写的目标是 categoryFileOf（不是 SKILL.md、不是人设）", src.contains("val file = categoryFileOf(skillDir)"))
        assertTrue("内容来自排序编码", src.contains("val text = encodeCategories(next)"))
        assertFalse("不许直接写正文文件名", src.contains("SKILL_FILE).writeText"))
        assertFalse("不许引数据层那个写口", src.contains("SkillStore.apply("))
        assertFalse("不许碰人设", src.contains("PersonaRules"))
        assertFalse("不许加索引行", src.contains("addIndexLine("))
        assertFalse("不许删索引行", src.contains("removeIndexLine("))
        assertFalse("不许删文件（本批只写 json）", src.contains(".delete()") || src.contains("deleteRecursively"))
        // 判别器自证：把目标换成 SKILL.md 的坏写法必须被上面那条抓到
        val badShape = "File(File(skillDir, name), SKILL_FILE).writeText(text, Charsets.UTF_8)"
        assertTrue("判别器抓得住写正文", Regex("SKILL_FILE\\)\\.writeText").containsMatchIn(badShape))
    }

    @Test
    fun `T5_设置页那一段只读分类_不新增任何写盘`() {
        val src = settings()
        assertEquals("设置页一个字写盘语句都不许有（写全在数据层与分类函数里）", 0, Regex("\\.writeText\\(").findAll(src).count())
        assertTrue("两层各读一份表", src.contains("readCategories(SkillRules.globalSkillDir(globalRoot))"))
        assertTrue("项目那份读项目目录", src.contains("readCategories(SkillRules.projectSkillDir(projectRoot))"))
        assertTrue("索引区按分类重排（全局）", src.contains("sortByCategory(globalLines, { it.name }, globalCats)"))
        assertTrue("索引区按分类重排（项目）", src.contains("sortByCategory(projectLines, { it.name }, projectCats)"))
        assertTrue("行右侧显示当前分类", src.contains("text = stringResource(lineCat.labelRes)"))
        assertTrue("写分类只有 setCategory 一条口", src.contains("setCategory(pickDir, pickName, cat)"))
        assertTrue("坏表要补那一句红字", src.contains("stringResource(R.string.skill_cat_corrupt)"))
        assertFalse("分组不许动索引行文本", src.contains("INDEX_MARK"))
        assertFalse("也不许在这里拼索引行", src.contains("【技能】"))
    }

    // ── 不存在的技能名不写进去 ────────────────────────────────────────────

    @Test
    fun `不存在的技能名一律不写进去_盘上不留下分类文件`() {
        val ghost = setCategory(skillDir, "根本没这个技能", SkillCategory.OTHER)

        assertTrue("要拒：$ghost", ghost is CategoryOutcome.Rejected)
        assertEquals("拒的是那个名字", "根本没这个技能", (ghost as CategoryOutcome.Rejected).name)
        assertTrue("要给出理由这句话", ghost.why.isNotBlank())
        assertFalse("一个字节都没写：旁挂文件不该被凭空建出来", categoryFileOf(skillDir).exists())

        // 名字里带路径分隔／点号：不许借此写到目录外面去
        listOf("", " ", ".", "..", "a/b", "a\\b").forEach { bad ->
            val o = setCategory(skillDir, bad, SkillCategory.OTHER)
            assertTrue("「$bad」要拒：$o", o is CategoryOutcome.Rejected)
            assertFalse("「$bad」也没落盘", categoryFileOf(skillDir).exists())
        }
        assertFalse("工作区根外面没被造出文件", File(root, "_分类.json").exists())

        // 已有表时再拒一次：旧内容不许被改写
        makeSkill("在的", "正文")
        assertTrue(setCategory(skillDir, "在的", SkillCategory.READ_IMAGE) is CategoryOutcome.Saved)
        val before = categoryFileOf(skillDir).readBytes().copyOf()
        assertTrue(setCategory(skillDir, "不在的", SkillCategory.EDIT_IMAGE) is CategoryOutcome.Rejected)
        assertContentEquals("拒绝那一次不许动旧表", before, categoryFileOf(skillDir).readBytes())
    }

    @Test
    fun `两层各一份表_全局与项目互不串`() {
        val globalRoot = File(root, "全局根").apply { mkdirs() }
        val projectRoot = File(root, "项目根").apply { mkdirs() }
        listOf(false, true).forEach { global ->
            val dir = skillDirOf(globalRoot, projectRoot, global)
            File(dir, "共名技能").mkdirs()
            File(dir, "共名技能/SKILL.md").writeText("正文", Charsets.UTF_8)
        }
        val gDir = skillDirOf(globalRoot, projectRoot, true)
        val pDir = skillDirOf(globalRoot, projectRoot, false)
        assertEquals("全局那份走 SkillRules 同一个口径", SkillRules.globalSkillDir(globalRoot), gDir)
        assertEquals("项目那份走 SkillRules 同一个口径", SkillRules.projectSkillDir(projectRoot), pDir)

        assertTrue(setCategory(gDir, "共名技能", SkillCategory.MAKE_CARD) is CategoryOutcome.Saved)

        assertEquals("全局那份读得到", SkillCategory.MAKE_CARD, readCategories(gDir).of("共名技能"))
        assertEquals("项目那份仍是缺省档（互不串）", SkillCategory.UNSET, readCategories(pDir).of("共名技能"))
        assertFalse("项目目录里没被多写一份", categoryFileOf(pDir).exists())
    }

    @Test
    fun `转义往返_名字与值里的引号反斜杠换行都回得来`() {
        val tricky = tableOf(
            "带\"引号\"的名字" to SkillCategory.OTHER,
            "带\\反斜杠" to SkillCategory.LOOKUP,
            "带\n换行" to SkillCategory.MAKE_CARD,
            "带\t制表" to SkillCategory.READ_IMAGE,
        )
        val back = decodeCategories(encodeCategories(tricky))
        assertFalse("自己编码出来的一定读得通", back.corrupt)
        tricky.forEach { (name, category) ->
            assertEquals("「${name.replace("\n", "\\n").replace("\t", "\\t")}」往返同档", category, back.of(name))
        }
        assertEquals("一条不多一条不少", tricky.size, back.byName.size)
    }

    // ── 分组的显示口径 ────────────────────────────────────────────────────

    @Test
    fun `六档顺序固定_空组不显示_组内保持原顺序`() {
        assertEquals(
            "枚举顺序＝派工单给的六档（显示与选单都按这个）",
            listOf("做卡", "查资料", "看图", "改图", "其他", "未分类"),
            SkillCategory.values().map { it.jsonValue },
        )
        val rows = listOf("甲", "乙", "丙", "丁")                       // 索引行原顺序
        val table = SkillCategoryTable(
            byName = mapOf("乙" to SkillCategory.EDIT_IMAGE, "甲" to SkillCategory.EDIT_IMAGE),
            corrupt = false,
        )

        val groups = categorize(rows, { it }, table)

        assertEquals("没人的那几档不占一行标题", listOf(SkillCategory.EDIT_IMAGE, SkillCategory.UNSET), groups.map { it.category })
        assertEquals("同组内还是原顺序（稳定排序）", listOf("甲", "乙"), groups[0].rows)
        assertEquals("未分类那组按原顺序收尾", listOf("丙", "丁"), groups[1].rows)

        val flat = sortByCategory(rows, { it }, table)
        assertEquals("重排后仍是这四行，一行不丢不加", 4, flat.size)
        assertEquals("内容集合没变", rows.sorted(), flat.sorted())
        assertTrue("引用的是原来那些行（分组不许改写行内容）", flat.contains("甲"))
    }

    @Test
    fun `六个标签值与存盘六档对得上_中文侧一字不差`() {
        val keys = listOf(
            "skill_cat_make_card", "skill_cat_lookup", "skill_cat_read_image",
            "skill_cat_edit_image", "skill_cat_other", "skill_cat_unset",
        )
        assertEquals("六档六个键，顺序对齐", SkillCategory.values().size, keys.size)
        keys.forEachIndexed { i, key ->
            assertEquals(
                "$key 的中文值就是存盘那六个字之一",
                SkillCategory.values()[i].jsonValue,
                value(zh(), key),
            )
        }
        val distinct = SkillCategory.values().map { it.labelRes }.toSet()
        assertEquals("六档六个不同资源键（不许有两档共用一个标签）", 6, distinct.size)
    }

    // ── T6：中英对称 ──────────────────────────────────────────────────────

    @Test
    fun `T6_分类这批新键中英都在且占位符一致`() {
        val keys = listOf(
            "skill_cat_make_card", "skill_cat_lookup", "skill_cat_read_image", "skill_cat_edit_image",
            "skill_cat_other", "skill_cat_unset", "skill_cat_pick_title", "skill_cat_saved",
            "skill_cat_rejected", "skill_cat_io_failed", "skill_cat_corrupt", "skill_cat_close",
        )
        keys.forEach { key ->
            assertTrue("$key 中文不许空", value(zh(), key).isNotBlank())
            assertTrue("$key 英文不许空", value(en(), key).isNotBlank())
            assertEquals(
                "$key 的占位符两边必须一致（否则切英文直接崩）",
                placeholders(value(zh(), key)),
                placeholders(value(en(), key)),
            )
        }
        assertEquals("B 段共 12 个键", 12, keys.size)
        assertEquals(
            "写成中英各一份的键数必须相等",
            Regex("name=\"skill_cat_").findAll(zh()).count(),
            Regex("name=\"skill_cat_").findAll(en()).count(),
        )
    }

    @Test
    fun `T6_界面文案全从strings取_档名只许出现在枚举声明里`() {
        val src = settings()
        listOf("做卡", "查资料", "看图", "改图", "其他", "未分类").forEach { label ->
            assertFalse("设置页不许把档名写死在代码里：$label", src.contains("\"$label\""))
        }
        // 旁挂文件里那六个字是**存盘数据**（jsonValue），不是文案：一人一次，只在枚举声明那一行
        val catSrc = cat()
        SkillCategory.values().forEach { c ->
            val needle = "\"${c.jsonValue}\""
            val hits = catSrc.split(needle).size - 1
            assertEquals("${c.jsonValue} 只许出现在枚举声明那一处", 1, hits)
            assertTrue(
                "那一处就是枚举项本身：${c.name}",
                catSrc.lineSequence().any { it.trimStart().startsWith("${c.name}(\"${c.jsonValue}\"") },
            )
        }
        assertTrue("选单每一档走资源", catSrc.contains("stringResource(cat.labelRes)"))
        assertTrue("行右侧与组标题都走资源", src.contains("text = stringResource(lineCat.labelRes)"))
        assertEquals(
            "两层各一处组标题＋一处行右侧标签＝4 处，全从资源取",
            4,
            Regex("stringResource\\(lineCat\\.labelRes\\)").findAll(src).count(),
        )
        assertTrue("回执走资源", src.contains("R.string.skill_cat_saved"))
        // 判别器自证：写死标签的那种形状确实被上面第一条抓到
        assertTrue("判别器抓得住硬编码档名", Regex("\"做卡\"").containsMatchIn("Text(\"做卡\")"))
    }

    private fun assertContentEquals(message: String, expected: ByteArray, actual: ByteArray) {
        assertTrue(
            "$message（前 ${expected.size} 字节 / 后 ${actual.size} 字节，" +
                "sha ${expected.sha256Hex()} vs ${actual.sha256Hex()}）",
            expected.contentEquals(actual),
        )
    }

    private fun ByteArray.sha256Hex(): String =
        MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
