// 独立开发声明：本文件为本项目原创，不含第三方源码的拷贝。
//
// 第七百四十七批 · 源码断言臂：判据⑥（不锚性别／性取向）、判据⑦（不许自造 660 字段）、
// 判据②（没有自动推进的口子）、判据④（三条链互不写入）＋「规格只读、不复制进仓」这条边界。
//
// 这一批的判据有两类：一类数得出来（行数、字段名），一类只在源码层面才站得住
// ——「不许出现性取向标签」用运行期断言扫不出注释外的口子，「660 未合时不许自造字段」
// 光看本模块的类根本看不出是不是抄来的。所以这四道闸一律读源码文本，剥掉注释再扫：
// 注释是给人讲口径的，代码才是判据本身。每道闸都配注入自检，防闸没牙（照 728 批的做法）。
package com.example.zhengdao.baopu.state.anchor

import com.example.zhengdao.SrcRoots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AnchorSourceAssertTest {

    private val dir = "app/src/main/java/com/example/zhengdao/baopu/state/anchor/"
    private val files = listOf(
        "AnchorFields.kt", "AnchorSpecTable.kt", "AnchorProfile.kt", "AnchorBodyBridge.kt",
        "AnchorTrack.kt", "AnchorLines.kt", "CrossLedger.kt",
    )
    private val form = "app/src/main/java/com/example/zhengdao/baopu/state/form/HumanBodySpec.kt"

    /** 判据⑥ 的第一层：取向标签在任何注释外的代码里都不许出现（含英文口径）。 */
    private val orientation = listOf(
        "同性恋", "异性恋", "双性恋", "直男", "直女", "性取向",
        "orientation", "heterosexual", "homosexual", "bisexual", "lesbian", "gay",
    )

    /** 判据⑥ 的第二层：身份标签不许成为七维的「列」——只禁在 id／label／group 三列。 */
    private val identity = listOf("性别", "取向", "gender", "sex", "orientation", "男", "女")

    /** 判据④：670 第一机（沦陷链／升温链）的落点符号，本包一个都不许碰。 */
    private val otherChains = listOf(
        "dStage", "aStage", "STAGE_NAMES", "ATTACKER_NAMES", "TrackStateView", "CharacterTrack", "fallStage",
    )

    /** 判据②：任何「自己往前走」的入口都不存在——名字扫不出来就别想自动推进。 */
    private val autoDrive = listOf(
        "fun advance", "fun autoAdvance", "fun autoStep", "fun tick", "fun next(",
        "Timer", "ScheduledExecutor", "while (true)", "delay(",
    )

    private fun source(rel: String): String = SrcRoots.find(rel).readText()

    private fun stripped(text: String): String =
        text.replace(Regex("/\\*[\\s\\S]*?\\*/"), "").replace(Regex("//[^\\n]*"), "")

    private fun codeOf(rel: String): String = stripped(source(rel))

    private val code: String = files.joinToString("\n") { codeOf(dir + it) }

    /** 谁在某一列里放了身份标签——列外不算（规格的文献出处写在 note／specRow 里是允许的）。 */
    private fun identityOffender(f: FieldSpec): String? = when {
        identity.any { it in f.id } -> "id=${f.id}"
        identity.any { it in f.label } -> "label=${f.label}"
        identity.any { it in f.group } -> "group=${f.group}"
        f.words.any { w -> identity.any { it in w } } -> "words=${f.words}"
        else -> null
    }

    /** 单字「直／弯」是取值，不是标签；但每一处都要写明它是哪一维的物理档。 */
    private fun bareBendOffender(f: FieldSpec): String? {
        val bare = f.words.filter { it == "直" || it == "弯" }
        if (bare.isEmpty()) return null
        val word = bare.first()
        return if ("「$word」是" in f.note) null else "${f.id} 的「$word」没写档口径"
    }

    /** 名册里抽出来的 660 字段名（「660 无此口径」的行不抽）。 */
    private fun sixtyNames(f: BridgeField): List<String> =
        if (f.sixSixtyPath.startsWith("660 无")) emptyList()
        else f.sixSixtyPath.split("＋").map { it.trim().substringAfterLast(".") }

    @Test
    fun `防闸空转_七个生产文件与660源码都真被读到`() {
        assertEquals(7, files.size)
        for (rel in files) {
            val c = codeOf(dir + rel)
            assertTrue("$rel 剥注释后不得为空", c.length > 200)
            assertTrue("$rel 须真是 anchor 包的源码", "package com.example.zhengdao.baopu.state.anchor" in c)
        }
        assertTrue("660 源码须真读到", codeOf(form).contains("data class HumanBodySpec"))
    }

    @Test
    fun `判据六_取向词在注释外的代码里零命中`() {
        for (word in orientation) assertFalse("生产代码含取向词「$word」", word in code)
    }

    @Test
    fun `判据六_注入取向词必被咬住_闸有牙自检`() {
        val injected = "$code\nval probe = \"性取向\"\nval other = \"异性恋\"\n"
        assertTrue("注入后闸必须咬中", orientation.any { it in injected })
        assertFalse("干净的源码本身不许已经被咬中", orientation.any { it in code })
    }

    @Test
    fun `判据六_七维的列里没有性别与取向标签`() {
        val offenders = AnchorFields.ALL.mapNotNull { identityOffender(it) }
        assertTrue("这些行把身份写进了列：$offenders", offenders.isEmpty())
        assertEquals(130, AnchorFields.ALL.size)
        AnchorFields.L1_TAGS.forEach { tag ->
            assertTrue("L1 标签「$tag」是身份标签", identity.none { it in tag })
        }
    }

    @Test
    fun `判据六_注入身份列必被闸判为违规`() {
        val fake = FieldSpec(id = "face.orientation", dim = DimKind.FACE, label = "性取向",
            words = listOf("直男"), specRow = "§二·编的")
        assertEquals("id=face.orientation", identityOffender(fake)?.substringBefore(" "))
        assertTrue(identityOffender(fake) != null)
        assertTrue("照抄的一行不许被误判", identityOffender(AnchorFields.spec("face.brows")) == null)
    }

    @Test
    fun `判据六_单字直弯必须写明是哪一维的物理档`() {
        val rows = AnchorFields.ALL.filter { it.words.any { w -> w == "直" || w == "弯" } }
        assertTrue("这几行该有档口径注记：" + rows.mapNotNull { bareBendOffender(it) },
            rows.none { bareBendOffender(it) != null })
        assertEquals(5, rows.size)
        assertEquals(listOf("face.brows", "body.legs", "posture.standing", "g.pubicHairForm", "t.gaze"),
            rows.map { it.id })
    }

    @Test
    fun `判据六_注入无注记的直档必被咬住`() {
        val bare = FieldSpec(id = "test.x", dim = DimKind.BODY, label = "试", words = listOf("直"),
            specRow = "§二·注入")
        assertTrue(bareBendOffender(bare) != null)
        val noted = bare.copy(note = "「直」是腿型档")
        assertEquals(null, bareBendOffender(noted))
    }

    @Test
    fun `判据七_名册字段名逐条回查660源码`() {
        val sixty = codeOf(form)
        val missing = AnchorBodyBridge.ROSTER.flatMap { sixtyNames(it) }.filterNot { "val $it" in sixty }
        assertTrue("名册写了 660 源码里没有的字段（＝自造字段）：$missing", missing.isEmpty())
        assertEquals(13, AnchorBodyBridge.ROSTER.size)
        assertTrue("真字段名要扫到够多才算闸开着", AnchorBodyBridge.ROSTER.flatMap { sixtyNames(it) }.size >= 12)
    }

    @Test
    fun `判据七_自造字段名必被名册闸咬住`() {
        val fake = BridgeField("body.thigh", "HumanBodySpec.thighGirth")
        val missing = sixtyNames(fake).filter { "val $it" !in codeOf(form) }
        assertTrue("660 没有 thighGirth，注入它闸必须咬中", missing.isNotEmpty())
        val none = BridgeField("body.frame", "660 无骨架口径")
        assertEquals(emptyList<String>(), sixtyNames(none))
    }

    @Test
    fun `判据七_无口径的四行必须标在名册上`() {
        val noPath = AnchorBodyBridge.ROSTER.filter { it.sixSixtyPath.startsWith("660 无") }
        assertEquals(listOf("body.frame", "body.legs", "body.chest", "body.hands"), noPath.map { it.fieldId })
        assertFalse("桥不许写回 660（copy 即改写）", ".copy(" in codeOf(dir + "AnchorBodyBridge.kt"))
    }

    @Test
    fun `判据四_本包不写沦陷链与升温链的落点`() {
        val hits = otherChains.filter { it in code }
        assertTrue("anchor 包碰了别的链的符号：$hits", hits.isEmpty())
        assertTrue("注入链符号后闸必须咬中", otherChains.any { it in "$code\nval dStage = 3\n" })
    }

    @Test
    fun `判据二_没有任何自动推进的入口`() {
        val hits = autoDrive.filter { it in code }
        assertTrue("出现了自己往前走的口子：$hits", hits.isEmpty())
        assertTrue("注入定时推进后闸必须咬中", autoDrive.any { it in "$code\nfun advanceGrid() = Unit\n" })
        val track = codeOf(dir + "AnchorTrack.kt")
        assertTrue("唯一的写入口是带证据的 step", "fun step(to: RelationState, evidence: List<String>" in track)
        assertTrue("闸必须在引擎之前", track.indexOf("gate(to, evidence)") < track.indexOf("relation.step("))
    }

    @Test
    fun `边界_规格只读_一个字都没抄进仓`() {
        val root = listOf(File("src"), File("app/src"), File("../app/src")).firstOrNull { it.isDirectory }
            ?: error("找不到模块源码根，这道闸没在地上扫（cwd=${File(".").absolutePath}）")
        val seen = root.walkTopDown().count { it.isFile }
        assertTrue("$root 只扫到 $seen 个文件，闸等于没扫", seen > 100)
        val names = listOf("人物锚定与越界轨迹.md", "人物锚定图.dot", "_预览.html")
        val copied = names.filter { name -> root.walkTopDown().filter { it.isFile }.any { it.name == name } }
        assertTrue("仓外真源被复制进来了：$copied（扫的＝$root）", copied.isEmpty())
        val pinned = source(dir + "AnchorFields.kt")
        assertTrue("真源路径须钉在源码注释里", "OneDrive\\Desktop\\男性\\人物锚定\\人物锚定与越界轨迹.md" in pinned)
        assertTrue("规格指纹须钉在源码注释里", "4ab930c29f3d9c02" in pinned)
        val fieldCode = codeOf(dir + "AnchorFields.kt")
        assertTrue("路径还得活在代码字符串里（注释会被剥掉，出处钉不能只写在头上）", "OneDrive\\Desktop\\男性\\人物锚定" in fieldCode)
        assertTrue("指纹同理：AnchorSpecSource 里必须有 sha 常量", "4ab930c29f3d9c02" in fieldCode)
        assertTrue("出处须带 asOf（AGENTS 铁律：证据带 source＋asOf）", "VERIFIED_ON" in fieldCode)
    }

    @Test
    fun `铁律_本批文件零Suppress`() {
        val users = files.filter { "@Suppress" in source(dir + it) }
        assertTrue("这些文件用了 @Suppress：$users", users.isEmpty())
    }
}
