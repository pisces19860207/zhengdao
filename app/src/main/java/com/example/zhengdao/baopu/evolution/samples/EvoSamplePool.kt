// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百八十九批（2026-11-11）· 样本池的**落盘口**（全仓唯一碰 `<工作区>/.zd-evo/` 的地方）。
//
// 派工单 ①：「样本池：`<工作区>/.zd-evo/`，每域一个文件，**append-only**」。
// 三条与 544 项目账本同款的纪律（同一个房型，不为这批另造一个形状）：
//   1. **append-only**：只追加，不改历史行、不删行。去重是**读侧**的纯折叠（`EvoSampleRules.fold`）
//      ——所以同一行重放两遍，读数一个字不变（判据④「样本池可重放」的结构保证）。
//   2. **半行不致命**：追加是单次 write，最坏末尾坏一行 JSON；读侧跳过坏行不崩（[read]），
//      一本账不该因为一行坏字节变成"读不出来"。
//   3. **不认识 Android**：只吃 `java.io.File` + `org.json`，JVM 单测直接打。
//
// 为什么样本池是**另一个文件**而不是写进 208 决策账：208 那一行记的是"发生了什么"，
// 记不下"这一条算不算独立事件"（去重、防刷是判据，不是事实）。事实进 208，判据进这里，
// 两边各自能重放——这正是判据④「删池不丢历史账」的落法：删掉 `.zd-evo/` 只丢**派生读数**，
// 208 那本账一行不少（`EvoSamplePoolTest·判据④…` 用例逐行核对条数）。
//
// 边界：不建表、不联网、不写 App 私有目录；域不在 473 登记表里 ⇒ 连文件都不建（fail-closed）。
package com.example.zhengdao.baopu.evolution.samples

import com.example.zhengdao.baopu.consequence.CanaryCapability
import com.example.zhengdao.core.RunLog
import java.io.File
import org.json.JSONObject

/** 样本池（每域一个 `.jsonl`；判据在 `EvoSampleRules`，装配在 `EvoSampleWiring`）。 */
internal object EvoSamplePool {

    /** 文件后缀（`<域线值>.jsonl`）。 */
    const val FILE_SUFFIX: String = ".jsonl"

    /** 池目录：`<工作区>/.zd-evo/`。 */
    fun dirOf(wsRoot: File): File = File(wsRoot, EvoSampleRules.DIR_NAME)

    /** 某域的文件：`<工作区>/.zd-evo/<域线值>.jsonl`。 */
    fun fileOf(wsRoot: File, domain: String): File = File(dirOf(wsRoot), domain + FILE_SUFFIX)

    /**
     * 读一个域的事件（文件不在 ⇒ 空表：一个还没跑过的域是合法状态，不是错误）。
     *
     * 三类行**跳过不算数**（都只 log 一句，不抛）：坏 JSON、认不出的域、
     * 域与文件名对不上的行——最后那类防的是"把 A 域的行粘进 B 域文件"，
     * 粘错了读数会虚高，而虚高的样本正是这批要治的病。
     */
    fun read(wsRoot: File, domain: String): List<EvoSampleEvent> {
        val f = fileOf(wsRoot, domain)
        if (!f.isFile) return emptyList()
        val lines = runCatching { f.readLines(Charsets.UTF_8) }.getOrElse {
            RunLog.log("样本池读不出（$domain）：${it.javaClass.simpleName}")
            return emptyList()
        }
        return lines.mapNotNull { parse(it, domain) }
    }

    /**
     * 追加一行（唯一写口）。
     *
     * @return false ＝没写成（盘上原有内容一个字节没动），调用方必须如实说"没记上"
     */
    fun append(wsRoot: File, event: EvoSampleEvent): Boolean = runCatching {
        if (CanaryCapability.ofWire(event.domain) == null) return@runCatching false
        val f = fileOf(wsRoot, event.domain)
        val dir = f.parentFile ?: return@runCatching false
        if (!dir.isDirectory && !dir.mkdirs()) return@runCatching false
        // 那个位置上要是躺着一件不是普通文件的东西（同名目录之类），一律不动它、如实失败
        if (f.exists() && !f.isFile) return@runCatching false
        f.appendText(toLine(event) + "\n", Charsets.UTF_8)
        true
    }.getOrElse {
        RunLog.log("样本池写失败（${event.domain}）：${it.javaClass.simpleName}")
        false
    }

    /**
     * 重放一批**原始行**（判据④：池可重放）。
     *
     * 用途只有一个：池被删/被换机搬走之后，把备份下来的行喂回来。因为去重是读侧的纯折叠，
     * 重放已存在的行不会把读数变大（幂等）；返回真正写进去的条数（读侧去重前）。
     */
    fun replay(wsRoot: File, domain: String, lines: List<String>): Int {
        var written = 0
        lines.forEach { raw ->
            val event = parse(raw, domain) ?: return@forEach
            if (append(wsRoot, event)) written++
        }
        return written
    }

    /** 一行 → 事件（坏行/域不符 ⇒ null；见 [read] 的口径）。 */
    internal fun parse(raw: String, domain: String): EvoSampleEvent? {
        val line = raw.trim()
        if (line.isEmpty()) return null
        val o = runCatching { JSONObject(line) }.getOrNull() ?: return null
        if (o.optString("domain") != domain) return null
        val eventId = o.optString("event")
        val kind = o.optString("kind")
        if (eventId.isEmpty() || kind.isEmpty()) return null
        return runCatching {
            EvoSampleEvent(
                domain = domain,
                sessionId = o.optLong("session", -1L),
                eventId = eventId,
                kind = kind,
                atMs = o.optLong("at", -1L),
            )
        }.getOrNull()
    }

    /** 事件 → 一行（键名固定；少写一个键就是历史读不回来）。 */
    internal fun toLine(event: EvoSampleEvent): String = JSONObject()
        .put("v", EvoSampleRules.SCHEMA)
        .put("domain", event.domain)
        .put("session", event.sessionId)
        .put("event", event.eventId)
        .put("kind", event.kind)
        .put("at", event.atMs)
        .toString()
}
