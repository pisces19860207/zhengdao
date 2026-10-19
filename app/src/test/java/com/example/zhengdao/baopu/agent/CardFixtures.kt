// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：docs/格式层规格-三套卡与酒馆PNG-2026-10-19.md §6（合成夹具设计）
//   §6.0 红线：**不许**出现真实卡的任何正文/人名/关键词；全部手工最小化的占位词
//   §6.1 J1–J7／§6.2 N1–N7／§6.3 P1–P5
//
// 全部夹具在**运行时生成**，不落盘、不进 resources —— 这样就不存在
// 「谁把真实卡复制进测试资源」的风险。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.card.CardJson
import com.example.zhengdao.baopu.card.CardPng
import com.example.zhengdao.baopu.card.PngScan
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import javax.imageio.ImageIO

internal object CardFixtures {

    // ── J1 · 网络平台常规（WEB_BOOK / PLAIN）──────────────────────────────
    const val J1 = """
{"world_book":[{"key":"_or_甲@wb@乙","value":"示例正文","group":"","key_region":3,"value_region":1,"sort":1,"depth":2,"probability":100,"match_type":2,"enable":true}]}
"""

    // ── J2 · 网络平台模板（WEB_BOOK / TEMPLATE）───────────────────────────
    const val J2 = """
{"_comment":["示例说明一","示例说明二"],"world_book":[{"key":"_or_甲","value":"示例正文","group":"","key_region":3,"value_region":1,"sort":1,"depth":2,"probability":100,"match_type":2,"enable":true}]}
"""

    /** 一条 Tavo 世界书条目（占位词；字段齐 26 个）。 */
    private const val TAVO_ENTRY = """"identifier":"%s","name":"示例条目","content":"示例正文","strategy":"%s","injectionPosition":"lorebookBefore","injectionDepth":4,"injectionRole":"system","keywords":%s,"secondaryKeywords":[],"secondaryKeywordStrategy":"none","scanDepth":2,"caseSensitive":false,"matchWholeWord":true,"probability":100,"sticky":0,"cooldown":0,"delay":0,"excludeRecursion":true,"preventRecursion":true,"delayUntilRecursion":0,"groupName":"","groupOverride":false,"groupWeight":100,"useGroupScoring":false,"enabled":true,"useRegex":false"""

    // ── J3 · Tavo 世界书 ─────────────────────────────────────────────────
    val J3: String = tavoBook(tavoEntry("1", "keyword", """["甲"]"""))

    // ── J4 · 酒馆原生世界书 ──────────────────────────────────────────────
    const val J4 = """
{"entries":{"0":{"uid":0,"key":["甲"],"keysecondary":[],"comment":"示例备注","content":"示例正文","constant":false,"vectorized":false,"selective":true,"addMemo":true,"order":100,"position":0,"disable":false,"excludeRecursion":false,"preventRecursion":false,"delayUntilRecursion":false,"probability":100,"useProbability":true,"depth":4,"group":"","groupOverride":false,"groupWeight":100,"scanDepth":null,"caseSensitive":null,"matchWholeWords":null,"role":0,"sticky":0,"cooldown":0,"delay":0,"displayIndex":0}}}
"""

    // ── J5 · Tavo 角色卡 ─────────────────────────────────────────────────
    const val J5 = """
{"kind":"character","id":"demo-2","name":"示例角色","updatedAt":0,"data":{"name":"示例角色","description":"","first_mes":"","avatar":"","personality":"","scenario":"","mes_example":"","creator_notes":"","system_prompt":"","post_history_instructions":"","alternate_greetings":[],"character_book":null,"tags":[],"creator":"","character_version":"","nickname":"","creator_notes_multilingual":{},"source":[],"group_only_greetings":[],"creation_date":0,"modification_date":0,"extensions":{}}}
"""

    // ── J6 · Tavo 正则 ───────────────────────────────────────────────────
    const val J6 = """
{"kind":"regex","id":"demo-3","name":"示例正则","updatedAt":0,"entries":[{"identifier":"1","name":"示例","findRegex":"甲","replaceString":"乙","trimStrings":[],"placements":[],"timing":"","substitution":"","minDepth":0,"maxDepth":0,"enabled":true}]}
"""

    // ── J7 · Tavo 预设 ───────────────────────────────────────────────────
    const val J7 = """
{"kind":"preset","id":"demo-4","name":"示例预设","updatedAt":0,"active":true,"basicPrompts":[],"official":false,"entries":[{"identifier":"1","name":"示例","content":"示例正文","role":"system","type":"","injectionPosition":"lorebookBefore","injectionDepth":4,"forbidOverrides":false,"enabled":true,"active":true}]}
"""

    // ── N2 · 判不出 ──────────────────────────────────────────────────────
    const val N2 = """{"foo":1,"bar":[1,2],"baz":{"x":true}}"""

    // ── N4 · `_or_` 单字 ────────────────────────────────────────────────
    const val N4 = """
{"world_book":[
 {"key":"_or_水@wb@火","value":"v","group":"","key_region":3,"value_region":1,"sort":1,"depth":2,"probability":100,"match_type":2,"enable":true},
 {"key":"_and_水@wb@火","value":"v","group":"","key_region":3,"value_region":1,"sort":2,"depth":2,"probability":100,"match_type":2,"enable":true},
 {"key":"_or_水滴@wb@火焰","value":"v","group":"","key_region":3,"value_region":1,"sort":3,"depth":2,"probability":100,"match_type":2,"enable":true}
]}
"""

    // ── N5 · A/B/C 五条 ─────────────────────────────────────────────────
    val N5: String = webBook(
        webEntry("_or_甲", keyRegion = 3, sort = 1),
        webEntry("_or_乙", keyRegion = 3, sort = 2),
        webEntry("_or_丙", keyRegion = 2, sort = 3),
        webEntry("_or_丁", keyRegion = 4, sort = 4),
        webEntry("_or_戊", keyRegion = 3, sort = 5),
    )

    // ── 构造器 ───────────────────────────────────────────────────────────

    /** 一条网络平台条目（字段齐、group 空、常规模式）。 */
    fun webEntry(
        key: String,
        keyRegion: Int = 3,
        matchType: Int = 2,
        sort: Int = 1,
        valueRegion: Int = 1,
        depth: Int = 2,
        probability: Int = 100,
        value: String = "v",
        group: String = "",
        enable: Boolean = true,
    ): String = """{"key":"$key","value":"$value","group":"$group","key_region":$keyRegion,"value_region":$valueRegion,"sort":$sort,"depth":$depth,"probability":$probability,"match_type":$matchType,"enable":$enable}"""

    fun webBook(vararg entries: String): String = """{"world_book":[${entries.joinToString(",")}]}"""

    fun tavoEntry(identifier: String, strategy: String, keywordsJson: String): String =
        "{" + TAVO_ENTRY.format(identifier, strategy, keywordsJson) + "}"

    fun tavoBook(vararg entries: String): String =
        """{"kind":"lorebook","id":"demo-1","name":"示例书","updatedAt":0,"entries":[${entries.joinToString(",")}]}"""

    /** 一条酒馆条目（六个 match* 默认全 false；`disable` 反义）。 */
    fun stEntry(
        uid: Int,
        key: String = """["乙"]""",
        disable: Boolean = false,
        constant: Boolean = false,
        matchDescription: Boolean = false,
    ): String = """{"uid":$uid,"key":$key,"keysecondary":[],"comment":"示例备注","content":"示例正文","constant":$constant,"vectorized":false,"selective":false,"addMemo":true,"order":$uid,"position":0,"disable":$disable,"excludeRecursion":false,"preventRecursion":false,"delayUntilRecursion":false,"probability":100,"useProbability":true,"depth":4,"group":"","groupOverride":false,"groupWeight":100,"scanDepth":null,"caseSensitive":null,"matchWholeWords":null,"role":0,"sticky":0,"cooldown":0,"delay":0,"displayIndex":0,"matchPersonaDescription":false,"matchCharacterDescription":$matchDescription,"matchCharacterPersonality":false,"matchCharacterDepthPrompt":false,"matchScenario":false,"matchCreatorNotes":false}"""

    fun stWorld(vararg entries: String): String =
        """{"entries":{${entries.mapIndexed { i, e -> "\"$i\":$e" }.joinToString(",")}}}"""

    // ── PNG 夹具（每次现画，不落盘）──────────────────────────────────────

    /** 造 1×1 白图（§6.3）。**夹具允许 ImageIO**（产品代码不许）。 */
    fun basePng(): ByteArray {
        val img = BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB)
        img.setRGB(0, 0, 0xFFFFFF)
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "png", out)
        return out.toByteArray()
    }

    /** 在 IEND 之前插一个 tEXt 块。 */
    fun withText(base: ByteArray, keyword: String, text: String): ByteArray {
        val chunks = (CardPng.scan(base) as PngScan.Ok).chunks
        val iend = chunks.first { it.type == "IEND" }
        return base.copyOfRange(0, iend.offset) +
            CardPng.textChunk(keyword, text) +
            base.copyOfRange(iend.offset, base.size)
    }

    fun withTexts(base: ByteArray, vararg pairs: Pair<String, String>): ByteArray =
        pairs.fold(base) { acc, (k, v) -> withText(acc, k, v) }

    fun b64(s: String): String = Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))

    /** P5：chara=V2、ccv3=V3（验「取 ccv3」）。 */
    fun pngTwoVersions(): ByteArray = withTexts(
        basePng(),
        "chara" to b64("""{"spec":"chara_card_v2","data":{"name":"旧"}}"""),
        "ccv3" to b64("""{"spec":"chara_card_v3","data":{"name":"新"}}"""),
    )

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** 顶层键序（测试独立于产品代码再算一次；只用产品里的文本扫描器，避免两套口径）。 */
    fun topKeys(json: String): List<String> = CardJson.topLevelKeys(json, 100)
}
