// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百四十七批 · 暗卫自动档那**一个配置域**的 [ConfigAccess] 生产适配器（447 §三的执行侧）。
//
// 为什么在 `repair/` 而不在 `inspect/`：写 SharedPreferences 是写盘，而 `InspectReadOnlyGateTest`
// 把勘验层整层钉成"只查不改"（唯一允许落盘的是那几本 JSONL 的追加）。这一层因此只做适配器，
// 而且**不自己认键**：
// - 读走属主既有读口 [NocturnalAuto.switchOn]（不另开一条读法——同一个配置两套读法就是漂移的出生地）；
// - 文件名与键名一律引用 [NocturnalAuto.SWITCH_FILE]／[NocturnalAuto.SWITCH_KEY]，这里没有字面量；
// - 缺省值也不在这里：[NocturnalAuto.switchOn] 自己带（拍板 A＝开），本类不写"默认应该是 true"。
//
// 三条纪律：
// 1. **只认自己那一个键**：[read]/[write] 收到别的键就回 null/false——判据表要是长出了
//    不属于这个域的键，那是接线接错了，宁可落成"未取样"，不许拿别的域的读法凑上去；
// 2. **写失败就是没改成**：[write] 回 false，由调用方落成 `outcome=failed` 的账行；
//    不打日志、不抛（`RepairLedger` 同款理由：单测会走到失败路径，`android.util.Log` 是桩）；
// 3. **类型跟着读口走**：判据表的值一律是字符串，这里落盘前转成读口能读的类型（布尔）。
//    转不动（表里写了 `"on"` 这种）⇒ 回 false——**不许**把它当 `true` 蒙过去。
package com.example.zhengdao.baopu.repair

import android.content.Context
import androidx.core.content.edit
import com.example.zhengdao.baopu.inspect.DataRepair.ConfigItem
import com.example.zhengdao.baopu.inspect.NocturnalAuto

/**
 * 暗卫自动档（SP `NocturnalAuto.SWITCH_FILE` / key `NocturnalAuto.SWITCH_KEY`）的读与写。
 *
 * 生效值 vs 盘上原样：本类判的是**生效值**——`switchOn` 在 SP 坏了／没建过时回缺省，
 * 而调度器吃的也正是那个缺省值，所以"生效值对位"就是"这趟 app 真按判据表在跑"。
 * 盘上有没有那一行、原样是什么，只进 [observed] 的表外名单，不参与判漂移。
 */
internal class NightWatchConfig(private val ctx: Context) : ConfigAccess {

    private fun prefs() = ctx.applicationContext
        .getSharedPreferences(NocturnalAuto.SWITCH_FILE, Context.MODE_PRIVATE)

    override fun read(key: String): String? =
        if (key != NocturnalAuto.SWITCH_KEY) {
            null
        } else {
            runCatching { NocturnalAuto.switchOn(ctx).toString() }.getOrNull()
        }

    /**
     * 这个配置域里看得见的全部项（表外的也要给，判据层才有东西可"只报不修"）。
     *
     * 读不动 ⇒ 交一个**说不清的项**进去（不是空表）：空表会被读成"这域里没有别的键"，
     * 而真相是我们没看见——按派工单"绝不猜"的口径，没看见也要成为一条需人处理。
     */
    override fun observed(): List<ConfigItem> = runCatching {
        prefs().all.mapNotNull { (k, v) -> v?.let { ConfigItem(k, it.toString()) } }
    }.getOrElse { listOf(ConfigItem(UNREADABLE, it.javaClass.simpleName)) }

    override fun write(key: String, value: String): Boolean {
        if (key != NocturnalAuto.SWITCH_KEY) return false
        val on = value.toBooleanStrictOrNull() ?: return false
        return runCatching {
            prefs().edit { putBoolean(NocturnalAuto.SWITCH_KEY, on) }
            true
        }.getOrDefault(false)
    }

    private companion object {
        const val UNREADABLE = "<配置域读不动>"
    }
}
