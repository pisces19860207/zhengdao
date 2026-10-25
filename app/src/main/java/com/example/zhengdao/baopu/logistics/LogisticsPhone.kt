// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百九十五批 · 「这车谁的、司机电话多少」那一问的号码判据（2026-10-25 她补的三条口径之一）。
//
// ## 为什么号码的形状判据要单独成一个文件
// 面板要「点一下就拨号盘带号」，工具与日志一侧要求「姓名与手机号一个字都不许留痕」，
// 台账与答话要求「原样显示、不许打码」。三个要求都指向同一个问题：**这一格到底是不是个号码**。
// 判据写在壳里就会变成两套（面板一套、台账一套），而答错的代价是「点了个空号」或
// 「把单号当电话拨出去」。所以这里给唯一一条：[parse]。壳只做「把它接到点击上」那一行。
//
// ## 只给「打开拨号盘」这一档；直接外呼那一档连名字都不许出现
// 派工单 §5 新增负例：不经拨号盘直接外呼的那个 action 与它的拨号权限被门禁钉成禁词——
// 禁词按原文扫，整条链上**连注释**都不许出现那两个词，所以这里不点名。道理不是风格：
// 外呼会真的打出去，误点一次就是一通电话；本批只做「把号码递到系统拨号盘」。
// 所以这一层连 `android.content` 的东西都不引（门禁按 import 扫），只交出一个数字串
// （[DialNumber.dial]），拨号盘由系统去开。
//
// ## 为什么下限是 7 位而不是「有数字就行」
// T9 的配货单号是 5 位以上的纯数字（见 `LogisticsQuery.WAYBILL_RE`）。如果号码判据只要求「纯数字」，
// 一格写成单号、或她把单号那一列错认成电话，就会长出一个能点的链接——点了之后拨号盘里躺着一个
// 拨不通的号。车牌带汉字本来就被「必须纯数字」拦掉，但那是巧合，所以位数这条单独钉住：
// 7 位以下一律不算号码（本地座机最短也是 7 位）。
package com.example.zhengdao.baopu.logistics

/**
 * 一个可以拨的号码。
 *
 * @param shown 表里怎么写就怎么显示（§1.8 冻结：司机手机号原样呈现，不许打码、不许改写）
 * @param dial  交给拨号盘的数字串：分隔符剥干净，国际前缀 `+` 保留
 */
data class DialNumber(val shown: String, val dial: String)

/** 手机号／电话那一列的形状判据（纯函数，无 Android 依赖）。 */
object LogisticsPhone {

    /** 最短 7 位：5 位的单号与 6 位的内线不配长成一条拨号链接（理由见文件头）。 */
    const val MIN_DIGITS = 7

    /** 最长 15 位：E.164 的上限，超过它多半是两格粘连或一串货号。 */
    const val MAX_DIGITS = 15

    /** 一格里写两个号的切分符（调度常写 `138…/139…`）。 */
    private val SPLITS = charArrayOf('、', '，', ',', ';', '；', '/', '／', '\n', '\r', '\u2028', '\u2029')

    /** 组号用的分隔符：剥掉它们才能得到可拨的数字串（`138 0013 8000`、`（010）1234-5678`）。 */
    private const val DROPPED = " \t-‐‑‒–—―_·.．()（）[]【】{}｛｝\u00A0\u3000\u2007\u202F\u2060\uFEFF"

    /**
     * 这一格能拨吗：能就给出 1..n 个号码，**不能就是空表**（空表＝界面写「未填」且不可点）。
     *
     * 一条链子上的每一环都是判据：切开 → 剥分隔符 → 必须纯数字（最多一个前导 `+`）→
     * 位数落在 [MIN_DIGITS]..[MAX_DIGITS]。任何一环不过 ⇒ 这一格被丢掉，不猜、不补位。
     */
    fun parse(raw: String): List<DialNumber> {
        if (raw.isBlank()) return emptyList()
        return raw.split(*SPLITS)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { token -> shaped(token)?.let { DialNumber(shown = token, dial = it) } }
    }

    /** 只有一个号时用它（面板一行只显示一个，多号走 [parse] 的全量）。 */
    fun firstOrNull(raw: String): DialNumber? = parse(raw).firstOrNull()

    /** 这一行的可拨号码（**只读 [StoredRow.phone] 那一格**——单号与车牌永不是号码来源）。 */
    fun forRow(row: StoredRow): List<DialNumber> = parse(row.phone)

    /**
     * 一批命中行里出现过的号码，按行序去重（答话下面那排「拨打」用的就是它）。
     *
     * 去重按拨号串而不是按原文：`138 0013 8000` 与 `1380013 8000` 是同一个号，
     * 摆两个链接出来她会以为有两个司机。
     */
    fun forRows(rows: List<StoredRow>): List<DialNumber> {
        val seen = HashSet<String>()
        val out = ArrayList<DialNumber>()
        rows.forEach { row ->
            forRow(row).forEach { n ->
                if (seen.add(n.dial)) out += n
            }
        }
        return out
    }

    /** 剥分隔符并验形；不是号码就回 null（不许在这里顺手补 1 位或去掉一位）。 */
    internal fun shaped(token: String): String? {
        val compact = StringBuilder(token.length)
        var plus = false
        token.forEach { c ->
            when {
                c in DROPPED -> Unit
                c == '+' -> {
                    // 只认开头的一个 +：中间的 + 说明这格是两串东西粘在一起的，不是号码
                    if (compact.isNotEmpty() || plus) plus = false else plus = true
                    if (!plus) return null
                }
                c.isDigit() -> compact.append(c)
                else -> return null
            }
        }
        if (compact.length < MIN_DIGITS || compact.length > MAX_DIGITS) return null
        return if (plus) "+$compact" else compact.toString()
    }
}
