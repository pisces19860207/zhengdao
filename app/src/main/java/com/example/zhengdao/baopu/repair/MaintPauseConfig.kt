// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百七十九批 · 后台维护**配置域**的 [ConfigAccess] 生产适配器（447 §三 的执行侧）。
//
// 属主在 479 换了：511 那个「后台自动维护」总开关删掉了，本域从"一个开关"变成"四把单件锁"
// ——每个器可以单独停一段时间（`maintenance/MaintPause`）。本适配器因此只认这四把锁：
//   · 键＝`pause_until_<wire>`（拼法只住 [MaintPause]，这里一个字面量都不抄）；
//   · 值＝停多少小时（正整数 ⇒ 续这么多小时；`0` ⇒ 立刻恢复）；
//   · 别的键一律回 null／false——判据表要是长出不属于这个域的键，那是接线接错了，
//     宁可落成"未取样"，不许拿别的域的读法凑上去（447 纪律①）。
//
// 为什么还在 `repair/`：写 SharedPreferences 是写盘，而 `InspectReadOnlyGateTest` 把勘验层
// 整层钉成"只查不改"。这一层只做适配器，写只经 [MaintPause] 的口。
package com.example.zhengdao.baopu.repair

import android.content.Context
import com.example.zhengdao.baopu.inspect.DataRepair.ConfigItem
import com.example.zhengdao.baopu.maintenance.MaintPause

/**
 * 后台维护配置域（四把单件锁）的读与写。
 *
 * 生效值 vs 盘上原样：读回的是**生效值**——"停到几点"（epoch ms）。没停过 ⇒ `0`。
 */
internal class MaintPauseConfig(private val ctx: Context) : ConfigAccess {

    override fun read(key: String): String? {
        val device = MaintPause.deviceOfKey(key) ?: return null
        return runCatching { MaintPause.untilMs(ctx, device).toString() }.getOrNull()
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
        val device = MaintPause.deviceOfKey(key) ?: return false
        val hours = value.trim().toLongOrNull() ?: return false
        return if (hours <= 0L) {
            MaintPause.setUntil(ctx, device, 0L)
        } else {
            MaintPause.pauseFor(ctx, device, hours) > 0L
        }
    }

    private fun prefs() = ctx.applicationContext
        .getSharedPreferences(MaintPause.SP, Context.MODE_PRIVATE)

    private companion object {
        const val UNREADABLE = "<配置域读不动>"
    }
}
