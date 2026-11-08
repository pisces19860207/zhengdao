// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百二十二批（2026-11-08）· 治理中心面板的**判据层**（纯函数，零 Android、零 IO）。
//
// 一句话：本文件只做一件事——把 527 `governance_state` 那一把 JSON **原样抄成行**。
//
// 为什么要有这一层，而不是在 `@Composable` 里 `optString` 一把：
//   · 判据②要的是「读数与 `governance_state` 逐项对拍相等」。屏幕能拿 Compose 骗过去，
//     纯函数骗不过——测试给同一段 JSON，逐格比对"显示的字"与"出口的字"是否**逐字节相等**；
//   · 本层**不算任何数**：没有除法、没有百分比化、没有四舍五入、没有"0 补成 0%"。
//     527 说 `no_sample` 就显示 `no_sample`；527 说读不到，就显示读不到＋那句原因。
//     「不另算」在这里是结构保证：**本文件里没有任何算术**（`GovernanceCenterGateTest` 钉）。
//
// 与决策中心的零重叠（判据⑤）也在这层落地：本层没有账本、没有队列、没有待批条目——
// 它只认识 527 出口那六段 JSON 里的三段（ratios / cards / gray）＋ identity 两格。
package com.example.zhengdao.ui.baopu

import com.example.zhengdao.baopu.consequence.IdentityRow
import com.example.zhengdao.baopu.consequence.IdentityState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.json.JSONObject

/**
 * 一行读数（屏幕上「标签 — 值」的那一格）。
 *
 * @param key 稳定机器键（`violation`／`pass`／`capability.wire` …）——标签文案查资源用它，
 *   所以**纯层不碰 R.string**（JVM 单测里 R 是 0，查不到文案）
 * @param value 显示的字，**与出口逐字节相等**（判据②）
 * @param detail 小字副注（如 `n/d` 或读不到的原因）；空串＝不显示
 */
internal data class GovReading(
    val key: String,
    val value: String,
    val detail: String = "",
)

/**
 * 执行体三格。
 *
 * @param id8 执行体 id 前 8 位（空 id ⇒ 空串，界面显示「未生成」）
 * @param state 527 给的线值（`active`／`revoked`／`rotated_out`）
 * @param credentialCount 凭据绑定表条数（读侧 `CredentialStore.load`）
 * @param lastRevokeClock 最近一次撤销的本地时刻（没有 ⇒ 空串）
 */
internal data class GovExecutor(
    val id8: String,
    val state: String,
    val credentialCount: Int,
    val lastRevokeClock: String,
)

internal object GovernanceCenterRules {

    /** 四率的固定顺序（屏幕顺序＝这个顺序；缺哪格就少哪格，不补 0）。 */
    val RATIO_KEYS: List<String> = listOf("violation", "misjudge", "intervention", "skip")

    /** 弹卡三数的固定顺序。 */
    val CARD_KEYS: List<String> = listOf("pass", "card", "compress")

    /** 执行体 id 显示前几位。 */
    const val ID_PREFIX = 8

    /** 撤销时刻的显示形状（本地时区；与时间词口径同族——显示的是"那一刻"，不是"多久以前"）。 */
    private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.US)

    /**
     * 解析一把出口 JSON（**失败也只回空表**：屏幕画「读不到」，不抛、不编）。
     *
     * @return null ＝这根本不是一个 JSON 对象（出口被毁）
     */
    fun parse(json: String): JSONObject? = runCatching { JSONObject(json) }.getOrNull()

    /** 四率（`ratios` 段逐格抄；`unknown` 段原样带原因）。 */
    fun ratios(root: JSONObject?): List<GovReading> {
        val sec = root?.optJSONObject("ratios") ?: return emptyReadings(RATIO_KEYS)
        return RATIO_KEYS.map { key -> fromRate(key, sec.optJSONObject(key)) }
    }

    /** 弹卡三数（`cards` 段；数就是出口给的数，不做任何换算）。 */
    fun cards(root: JSONObject?): List<GovReading> {
        val sec = root?.optJSONObject("cards") ?: return emptyReadings(CARD_KEYS)
        // 527 的缺省降级：整段 unknown（账本根没装配）⇒ 三格一起说读不到
        sec.optString("unknown").takeIf { it.isNotEmpty() }?.let { why ->
            return CARD_KEYS.map { GovReading(it, UNKNOWN, why) }
        }
        return CARD_KEYS.map { key ->
            // 键缺失与"值为 0"必须分开：`opt` 会给 0，但那是编出来的读数
            when {
                !sec.has(key) -> GovReading(key, UNKNOWN, "出口没给这一格")
                else -> GovReading(key, sec.optInt(key).toString(), "")
            }
        }
    }

    /** 灰度（`gray` 段逐能力一格；值用出口给的 `cn`，不是本层翻译）。 */
    fun grays(root: JSONObject?): List<GovReading> {
        val sec = root?.optJSONObject("gray")
            ?: return listOf(GovReading("gray", UNKNOWN, "灰度段没出来"))
        sec.optString("unknown").takeIf { it.isNotEmpty() }?.let { why ->
            return listOf(GovReading("gray", UNKNOWN, why))
        }
        return sec.keys().asSequence().sorted().map { wire ->
            val cell = sec.optJSONObject(wire)
            val cn = cell?.optString("cn").orEmpty()
            val band = cell?.optString("band").orEmpty()
            GovReading(wire, cn.ifEmpty { band.ifEmpty { UNKNOWN } }, band)
        }.toList()
    }

    /** 出口自报的当日（`day` 段；缺 ⇒ 空串，界面不写"今天"来冒充）。 */
    fun dayOf(root: JSONObject?): String = root?.optString("day").orEmpty()

    /**
     * 执行体三格。
     *
     * @param revokedAtMs 最近一次撤销的时刻（0 ＝没撤销过）
     * @param zone 显示用到的时区（测试传固定时区，生产传系统时区）
     */
    fun executor(
        root: JSONObject?,
        credentialCount: Int,
        revokedAtMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): GovExecutor {
        val id = root?.optJSONObject("identity")?.optString("id").orEmpty()
        // identity 段读不到时 527 给的是 {"unknown": ...}，此时 id 为空 → 界面显示「未生成」而不是假装有主
        val state = root?.optJSONObject("identity")?.optString("state").orEmpty()
        return GovExecutor(
            id8 = if (id.length >= ID_PREFIX) id.take(ID_PREFIX) else id,
            state = state,
            credentialCount = credentialCount,
            lastRevokeClock = if (revokedAtMs > 0L) clock(revokedAtMs, zone) else "",
        )
    }

    /** 撤销时刻的显示（纯：给毫秒与时区，回一串字）。 */
    fun clock(atMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        CLOCK.format(Instant.ofEpochMilli(atMs).atZone(zone))

    /**
     * 最近一次撤销的时刻（0 ＝没撤销过）。
     *
     * 为什么在判据层而不在屏幕里：屏幕层"自己扫表"是本仓反复收口过的那条缝
     * （本仓源级门禁一直把 `filter {` 列在屏幕层的禁词里）。这里只挑「已撤销且有撤销时刻」的行，
     * 已轮换（rotated_out）不算撤销——那只是被新 id 顶下来，不是"谁被拿掉了"。
     */
    fun lastRevokeAtMs(rows: List<IdentityRow>): Long =
        rows.filter { it.state == IdentityState.REVOKED && it.revokedAtMs > 0L }
            .maxOfOrNull { it.revokedAtMs } ?: 0L

    // ── 共用小件 ─────────────────────────────────────────────────────────────

    /** 读不到的那句（**不写 0**：0 是一个读数，读不到不是）。 */
    const val UNKNOWN: String = "读不到"

    private fun emptyReadings(keys: List<String>): List<GovReading> =
        keys.map { GovReading(it, UNKNOWN, "读数段没出来") }

    /** 一格率：`rate` 原样显示（`no_sample` 也是原样），`n/d` 进小字。 */
    private fun fromRate(key: String, cell: JSONObject?): GovReading {
        if (cell == null) return GovReading(key, UNKNOWN, "出口没给这一格")
        cell.optString("unknown").takeIf { it.isNotEmpty() }?.let { why ->
            return GovReading(key, UNKNOWN, why)
        }
        val rate = cell.optString("rate")
        if (rate.isEmpty()) return GovReading(key, UNKNOWN, "这一格没有 rate")
        return GovReading(key, rate, "${cell.optInt("n")}/${cell.optInt("d")}")
    }
}
