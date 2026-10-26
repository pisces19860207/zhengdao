// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百二十八批 C 片：回放的**站点级信任**（派工单 C 段：默认关、白名单显式加、
// 可撤销、决策中心留痕）。
//
// 一句话：示教与回放这类「把页面自动化」的动作，只许发生在用户点名信任过的站点上；
// 名单默认是**空的**——没有「默认信任」这回事。
//
// 硬边界（逐条可单测）：
//   ① 零 Android/零 IO 依赖（只认调用方给的 File 与字符串）⇒ JVM 直测；
//   ② 主机名校验从严：拒 localhost／无点／IP／内网伪后缀／通配符——信任名单里
//      出现这些等于没有边界（`localhost` 谁都能起、IP 段无法表达意图）；
//   ③ 匹配复用 [TeachSafety.domainAllowed] 的口径（host 全等或 `.*` 后缀，
//      点前缀防 `a.com.evil.io` 冒充）——不另立第二套域匹配；
//   ④ 留痕不在本文件：加/撤的决策记录由调用方（接线层）写——这里只管名单文件本身，
//      决策中心「回滚」复用的也正是这两个纯文件操作（回滚行由账本自己记）。
package com.example.zhengdao.baopu

import java.io.File
import org.json.JSONArray

internal object TeachSiteTrust {

    /** 名单文件（放在全局记忆目录，与决策记录/技能同棵；用户可看可备份）。 */
    const val FILE_NAME = "回放信任站点.json"

    fun fileIn(globalRoot: File): File = File(globalRoot, FILE_NAME)

    /** 现读名单（小写、去重、保序；文件不存在/坏 JSON ⇒ 空名单＝全拒，fail-closed）。 */
    fun load(globalRoot: File): List<String> {
        val f = fileIn(globalRoot)
        if (!f.isFile) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText(Charsets.UTF_8))
            (0 until arr.length()).mapNotNull { i ->
                (arr.opt(i) as? String)?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            }.distinct()
        }.getOrElse { emptyList() }
    }

    /**
     * 加一个站点（幂等：已在名单里就不再写）。成功（含本来就有）⇒ true；
     * 盘写失败 ⇒ false（调用方给一句人话，不静默）。
     */
    fun add(globalRoot: File, host: String): Boolean {
        val h = host.lowercase()
        val cur = load(globalRoot)
        if (h in cur) return true
        val next = JSONArray(cur + h)
        return runCatching {
            fileIn(globalRoot).writeText(next.toString(2), Charsets.UTF_8)
            true
        }.getOrElse { false }
    }

    /** 撤一个站点（名单里没有 ⇒ false——「撤了个寂寞」要照实说，不当成功）。 */
    fun remove(globalRoot: File, host: String): Boolean {
        val h = host.lowercase()
        val cur = load(globalRoot)
        if (h !in cur) return false
        val next = JSONArray(cur.filter { it != h })
        return runCatching {
            fileIn(globalRoot).writeText(next.toString(2), Charsets.UTF_8)
            true
        }.getOrElse { false }
    }

    /**
     * 校验并归一用户给的主机名；不合规 ⇒ null（调用方回一句人话，不猜意图）。
     *
     * 宽容一处：粘成 `https://a.com/x` 也收（取 host），其余从严——
     * 通配符、端口、IP、localhost、内网伪后缀一律不进名单。
     */
    fun normalize(raw: String): String? {
        var s = raw.trim().lowercase()
        if (s.isEmpty()) return null
        if ("://" in s) s = TeachSafety.hostOf(s) ?: return null
        if (s.isEmpty()) return null
        if (s.any { it.isWhitespace() }) return null
        if (s.any { it in "/@:*%?\\" }) return null
        if (s.length > 253) return null
        if (s.startsWith(".") || s.endsWith(".")) return null
        if (s == "localhost" || s.endsWith(".localhost")) return null
        if ('.' !in s) return null
        val labels = s.split('.')
        // 全数字标签＝IPv4：信任名单只认域名（IP 无法表达「这个站」的意图）
        if (labels.all { it.toShortOrNull() != null }) return null
        val badSuffix = setOf("local", "internal", "lan", "home", "corp", "invalid", "test", "example")
        if (labels.last() in badSuffix) return null
        return if (labels.all { it.isNotEmpty() && it.length <= 63 && it.all { c -> c.isLetterOrDigit() || c == '-' } && !it.startsWith("-") && !it.endsWith("-") }) s else null
    }

    /** host 是否被名单覆盖（全等或子域；与 [TeachSafety.domainAllowed] 同口径）。 */
    fun covers(host: String, trusted: List<String>): Boolean {
        val h = host.lowercase()
        return trusted.any { t -> h == t || h.endsWith(".$t") }
    }

    /** url 的站点是否已信任（取不出 host ⇒ 不信任）。 */
    fun isTrusted(url: String, trusted: List<String>): Boolean =
        TeachSafety.hostOf(url)?.let { covers(it, trusted) } == true

    /**
     * 信任闸的判语：null＝放行；非 null＝拒（点名哪个动作、当前站点是谁、怎么加白）。
     * 默认名单为空 ⇒ 这句就是新会话的第一屏。
     */
    fun gateError(url: String, trusted: List<String>, what: String): String? {
        if (isTrusted(url, trusted)) return null
        val host = TeachSafety.hostOf(url) ?: "（取不到站点名）"
        return "$what 只在信任过的站点上自动执行；$host 不在信任名单" +
            "（先 teach_site_trust add $host 显式加白，或人手动操作这一步）"
    }

    /** 回放前置闸：技能的授权域里只要有没信任的，整场不跑（点名差哪几个）。 */
    fun domainsError(allowedDomains: List<String>, trusted: List<String>): String? {
        val missing = allowedDomains.filter { !covers(it, trusted) }
        if (missing.isEmpty()) return null
        return "回放技能的授权域里有未信任站点：${missing.joinToString("、")}" +
            "（先 teach_site_trust add <域名> 逐个加白；名单在「回放信任站点.json」，可撤销）"
    }
}
