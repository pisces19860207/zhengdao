// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第七百一十九批 · 649 事实片内容批——把 fact 片的 words 喂进 DangerWords。
//
// 设计要点（派工单 719 + 675 裁定）：
//   · fact 片真源＝docs/facts/fact-slices.json（git 可审、source 带来源文件行号）；
//     生产随包分发时与 index.json 同放包根，文件名 [FACTS_NAME]。
//   · 复用 649 的装载规矩：开关取 [PackTools.gateOn]（默认关）、包根取 [PackTools.rootProvider]。
//     **关态第一行就退、零磁盘读**——与 PackTools.search 同一条纪律，不改开关默认值。
//   · 打开后读片、取 words、喂 [DangerWords.fromFacts]；该函数 719 起是并集语义：
//     默认 24 词保留 + fact 新词追加 ⇒ 词数 >24 且含默认（判据②；负例"替换⇒红"）。
//   · 本对象是纯 JVM（不碰 Context）；校验与对照都是纯函数，单测直打。
package com.example.zhengdao.baopu.pack

import com.example.zhengdao.baopu.bio.DangerWords
import java.io.File
import org.json.JSONObject

/** 一片 fact 的解析结果（对应真源 JSON 的一条 slice）。 */
internal data class FactSlice(
    val id: String,
    val title: String,
    val body: String,
    val source: String,
    val asOf: String,
    val words: List<String>,
)

/** 装载结果：装好的词表 + 读数（供注入账与断言）。 */
internal data class FactWordLoad(
    val words: DangerWords,
    val sliceCount: Int,
    val wordCount: Int,
    val readBytes: Int,
)

/**
 * 649 事实片 → DangerWords 的装载出口。
 *
 * 与 [PackTools] 共用两个注入位（同一份开关、同一个包根），所以：
 * 关态零读、开态可检索这两条 649 老规矩在这里原样成立，且**不改默认值**。
 */
internal object FactPackWords {

    /** fact 片文件名（与 index.json 同目录，包根下）。 */
    const val FACTS_NAME = "fact-slices.json"

    /** 事实片校验上限（派工单：body ≤200 字、最小集 24 片）。 */
    const val BODY_MAX = 200
    const val MIN_SLICES = 24

    /**
     * 装载出口：默认关 ⇒ 原样回 base，**一个字节都不读**。
     * 打开 ⇒ 读包根的 [FACTS_NAME]，取词喂 [DangerWords.fromFacts]（并集）。
     * 文件缺失/损坏/无有效片 ⇒ 回 base（fail closed，不退化成空表）。
     */
    fun load(base: DangerWords = DangerWords.DEFAULT): FactWordLoad {
        // ① 开关：关着 ⇒ 第一行就退，和 PackTools.search 同款（判据③关态零读）
        if (!runCatching { PackTools.gateOn() }.getOrDefault(false)) {
            return FactWordLoad(base, 0, 0, 0)
        }
        val root = runCatching { PackTools.rootProvider?.invoke() }.getOrNull()
            ?: return FactWordLoad(base, 0, 0, 0)
        val f = File(root, FACTS_NAME)
        if (!f.isFile) return FactWordLoad(base, 0, 0, 0)
        val text = runCatching { f.readText(Charsets.UTF_8) }.getOrNull()
            ?: return FactWordLoad(base, 0, 0, 0)
        val slices = runCatching { parse(text) }.getOrDefault(emptyList())
        if (slices.isEmpty()) return FactWordLoad(base, 0, 0, text.toByteArray(Charsets.UTF_8).size)
        val lines = slices.flatMap { it.words }
        return FactWordLoad(
            words = DangerWords.fromFacts(lines, base),
            sliceCount = slices.size,
            wordCount = lines.toSet().size,
            readBytes = text.toByteArray(Charsets.UTF_8).size,
        )
    }

    /**
     * 解析真源 JSON。逐片校验 kind/source/asOf/body/words：
     * kind≠fact、缺 source 或 asOf、body 超长、无词 ⇒ 该片跳过（不半吊子入库）。
     */
    fun parse(json: String): List<FactSlice> {
        val arr = JSONObject(json).optJSONArray("slices") ?: return emptyList()
        val out = ArrayList<FactSlice>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val slice = parseOne(o) ?: continue
            out.add(slice)
        }
        return out
    }

    /** 单片解析；任一项不合格 ⇒ null（跳过该片，不半吊子入库）。拆出以降低 parse 圈复杂度。 */
    private fun parseOne(o: JSONObject): FactSlice? {
        val id = o.optString("id").trim()
        val source = o.optString("source").trim()
        val asOf = o.optString("asOf").trim()
        val body = o.optString("body")
        if (id.isEmpty() || source.isEmpty() || asOf.isEmpty()) return null
        if (o.optString("kind") != "fact") return null
        if (body.isEmpty() || body.length > BODY_MAX) return null
        val words = ArrayList<String>()
        val wa = o.optJSONArray("words")
        if (wa != null) {
            for (j in 0 until wa.length()) {
                val w = wa.optString(j).trim()
                if (w.isNotEmpty()) words.add(w)
            }
        }
        if (words.isEmpty()) return null
        return FactSlice(id, o.optString("title"), body, source, asOf, words)
    }

    /**
     * 判据④⑤的纯校验：返回问题描述列表（空＝全过）。
     * 查：最小片数、id 唯一、source 带文件+行号、asOf 齐、body 无数值点值、无病名药名。
     *
     * 这里只做"结构性违规"（点值/病名药名按字面表查），不替作者裁决内容真假——
     * 真假由 source 行号指回事实底盘人工核。
     */
    fun validate(slices: List<FactSlice>): List<String> {
        val issues = ArrayList<String>()
        if (slices.size < MIN_SLICES) issues += "片数不足：${slices.size} < $MIN_SLICES"
        if (slices.map { it.id }.toSet().size != slices.size) issues += "id 有重复"
        for (s in slices) {
            if (!s.source.contains(".md") || !Regex("L\\d+").containsMatchIn(s.source)) {
                issues += "${s.id} source 缺来源文件或行号：${s.source}"
            }
            if (s.asOf.isBlank()) issues += "${s.id} 缺 asOf"
            if (s.body.length > BODY_MAX) issues += "${s.id} body 超长：${s.body.length}"
            if (POINT_VALUE.containsMatchIn(s.body)) issues += "${s.id} body 含数值点值"
            val hit = DISEASE_DRUG.firstOrNull { s.body.contains(it) }
            if (hit != null) issues += "${s.id} body 含病名/药名：$hit"
        }
        return issues
    }

    /** 供判据⑤抽查：按 id 取回 source 行号串（5 条能指回来源文件与行号）。 */
    fun sourceRefs(slices: List<FactSlice>, ids: List<String>): Map<String, String> =
        slices.filter { it.id in ids }.associate { it.id to it.source }

    // 数值点值：阿拉伯数字紧接单位/百分号/比值（区间写法"x–y"里也带数字，故只钉可疑单点量）。
    private val POINT_VALUE = Regex(
        "\\d+(?:\\.\\d+)?\\s*(?:cm|mm|℃|分钟|小时|%|厘米|毫米)",
    )
    // 病名/药名黑名单（事实底盘 §四禁收项，按作者口径不进事实片）。
    private val DISEASE_DRUG = listOf(
        "佩罗尼", "静脉曲张", "糖尿病", "心血管病", "前列腺炎", "多精症",
        "ED", "SSRI", "阳痿", "早泄", "尿道下裂",
    )
}
