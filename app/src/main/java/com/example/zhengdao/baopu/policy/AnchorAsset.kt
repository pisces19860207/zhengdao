// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百二十七批 · 锚定集**随包出厂**（assets/policy/anchor-set.jsonl）：新用户装完 App，
// 判据准入的「标准答案」就是现成的——50 条整批签字的样本随包带走，不再依赖
// 私有侧文件在不在本机。
//
// 分工照 122 批的 PolicyAssets/PolicyTable 房型：本文件**纯 JVM**（零 Android、零 IO，
// PolicySourceGateTest 逐文件机检）；Context 读资产在唯一装配点 BaopuRuntime。
// 四层 fail closed，任何一层不过 ⇒ [Snapshot] 拒收、准入集恒空，**不是跳过继续跑**：
//   ① 出厂禁词：`source: history` 一行都不许有（真账绝不出厂，历史回灌的自动账混不进来）；
//   ② 完整性：sha256 必须与 [EXPECTED_SHA256] 相符（改一个字节就不是那份锚，拒）；
//   ③ 形态：逐行合法 JSON、八个字段全非空（含 signedBy/signedAt——没签字的不出厂）、
//      id 唯一、人工结论只认「通过／不通过」打头（无法判定不当通过，311 批同口径）；
//   ④ 规模：恰好 [EXPECTED_COUNT] 条。
package com.example.zhengdao.baopu.policy

import java.security.MessageDigest
import org.json.JSONObject

internal object AnchorAsset {

    /** APK 内路径（assets 根以下）。全仓只许本文件出现这串字面量（AnchorAssetGateTest 机检）。 */
    const val PATH = "policy/anchor-set.jsonl"

    /** 审计行里的来源名。 */
    const val SOURCE = "assets/$PATH"

    /** 出厂规模：50 条。 */
    const val EXPECTED_COUNT = 50

    /** 出厂指纹：assets 文件**按提交进去的字节**算的 sha256（.gitattributes 已 -text 禁 EOL 转换）。 */
    const val EXPECTED_SHA256 = "165e4ae7730144d7d600d4fdd443d530d19bdfe082d9c5ee2a8b5a39132b110d"

    /** 出厂禁词：锚定集必须是从真账人工挑选的样本，历史回灌的自动账一行不许混进来。 */
    const val FORBIDDEN_MARKER = "source: history"

    /**
     * 禁词的机检形态：资产是 JSONL，字段会写成 `"source": "history"`——
     * 引号与空格不许成为逃逸口（与门禁的裸字面量扫描互补）。
     */
    private val FORBIDDEN_PATTERN = Regex("\"?source\"?\\s*:\\s*\"?history")

    /**
     * 锚定集快照：判据准入读锚定集的**唯一形态**。
     *
     * 为什么拒收不抛异常：装配在启动路径上，抛异常等于把整个 App 打死；
     * 而「拒收＋准入集恒空」已经足够 fail closed——[forAdmission] 空 ⇒
     * [AnchorSet.status] 为 EMPTY ⇒ [CriteriaEvolution.admit] 一律 REFUSED。
     */
    class Snapshot private constructor(
        val ok: Boolean,
        val refusal: String?,
        parsed: List<AnchorCase>,
    ) {
        /** 不可变快照：构造期转死，此后没有改它的路。 */
        private val cases: List<AnchorCase> = ArrayList(parsed)

        /** 快照条数（拒收态恒 0）。 */
        val count: Int get() = cases.size

        /**
         * 喂给 [CriteriaEvolution.admit] 的那一份：校验失败**恒空** ⇒
         * 准入一律 REFUSED（不许拿残集继续准入）。
         */
        fun forAdmission(): List<AnchorCase> = if (ok) cases else emptyList()

        /** 一行审计读数：fail closed 的结果给人看。 */
        fun summary(): String =
            if (ok) "$SOURCE 载入 $count 条全部生效"
            else "$SOURCE 拒收：$refusal（判据准入一律拒绝）"

        companion object {
            fun force(parsed: List<AnchorCase>): Snapshot = Snapshot(true, null, parsed)

            fun refuse(reason: String): Snapshot = Snapshot(false, reason, emptyList())
        }
    }

    /**
     * 资产字节 → 快照。四层校验按「先禁词、再指纹、再形态、后规模」排序：
     * 禁词是政策问题（哪怕有人顺手转了常量也拦），指纹是完整性问题，
     * 形态/规模防的是「有人换内容也换常量」的第二层失守。
     */
    fun fromAsset(raw: ByteArray): Snapshot {
        if (raw.isEmpty()) return Snapshot.refuse("$SOURCE 为空")
        val text = raw.toString(Charsets.UTF_8)
        if (FORBIDDEN_PATTERN.containsMatchIn(text)) {
            return Snapshot.refuse("$SOURCE 含出厂禁词「$FORBIDDEN_MARKER」（真账绝不出厂）")
        }
        val actual = sha256Hex(raw)
        if (actual != EXPECTED_SHA256) {
            return Snapshot.refuse("$SOURCE sha256 与常量不符：常量 $EXPECTED_SHA256，实际 $actual")
        }
        return validate(text)
    }

    /**
     * 形态＋规模两层：逐行解析、恰好 [EXPECTED_COUNT] 条。
     * 独立成口是为了可测——指纹层挡住的负例到不了这里，但「有人连常量一起换」
     * 时这两层是第二道闸，必须自己被钉住。
     */
    internal fun validate(text: String): Snapshot {
        val parsed = try {
            parse(text)
        } catch (e: IllegalArgumentException) {
            return Snapshot.refuse(e.message ?: "$SOURCE 解析失败")
        }
        if (parsed.size != EXPECTED_COUNT) {
            return Snapshot.refuse("$SOURCE 应为 ${EXPECTED_COUNT} 条，实际 ${parsed.size} 条")
        }
        return Snapshot.force(parsed)
    }

    /** sha256 小写十六进制；输入是**原始字节**（行尾转换在 .gitattributes 已禁）。 */
    internal fun sha256Hex(raw: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) }

    /** JSONL → 样本：任何一行不合格抛 [IllegalArgumentException]（带行号），调用方转拒收。 */
    internal fun parse(text: String): List<AnchorCase> {
        val seen = HashSet<String>()
        val parsed = ArrayList<AnchorCase>()
        text.lineSequence().forEachIndexed { idx, rawLine ->
            val line = rawLine.trim()
            if (line.isNotEmpty()) {
                parsed.add(parseLine(line, idx + 1, seen))
            }
        }
        return parsed
    }

    private fun parseLine(line: String, lineNo: Int, seen: MutableSet<String>): AnchorCase {
        val o = try {
            JSONObject(line)
        } catch (e: RuntimeException) {
            throw IllegalArgumentException("第 $lineNo 行不是合法 JSON：${e.message}")
        }
        val case = AnchorCase(
            id = required(o, "id", lineNo),
            scene = required(o, "scene", lineNo),
            oldVerdict = required(o, "oldVerdict", lineNo),
            humanVerdict = required(o, "humanVerdict", lineNo),
            evidence = required(o, "evidence", lineNo),
            screenedBy = required(o, "screenedBy", lineNo),
            signedBy = required(o, "signedBy", lineNo),
            signedAt = required(o, "signedAt", lineNo),
        )
        if (!seen.add(case.id)) {
            throw IllegalArgumentException("第 $lineNo 行 id 重复：${case.id}")
        }
        if (case.side() == VERDICT_UNKNOWN) {
            throw IllegalArgumentException(
                "第 $lineNo 行人工结论不以「$VERDICT_PASS／$VERDICT_FAIL」打头：${case.humanVerdict}",
            )
        }
        return case
    }

    /** 字段缺失或空值即拒（signedBy/signedAt 空＝没签字，不出厂）。 */
    private fun required(o: JSONObject, key: String, lineNo: Int): String {
        val v = o.optString(key).trim()
        if (v.isEmpty()) {
            throw IllegalArgumentException("第 $lineNo 行缺字段或空值：$key")
        }
        return v
    }
}
