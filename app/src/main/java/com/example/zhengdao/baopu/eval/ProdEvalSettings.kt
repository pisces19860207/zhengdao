// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百六十八批（派工单 §2）：`eval_prod_on` 的**写入口**——设置页「生产评测」开关。
//
// 为什么单独一个文件、不并进 [ProdEvalGate]：闸门只读、写口在 UI 侧，两件事分开，
// `ProdEvalTest` 的源码门禁才门得住（[ProdEval] 里出现写口 ⇒ 红，见那条 forbidden 名单）。
// 这里也只有「显式拨动」一条路：没有 devMode / adb / 环境变量旁路（派工单 §2 原文）。
//
// 与 84 批 [SelfCheckGate] 的差别再次对照：自检那把闸有 devMode 并联（调试构建开箱即跑），
// 因为自检烧的是本地算力、测的是用户自己的提案；生产跑批烧的是**用户的额度和网络**，
// 所以这里连并联都没有——只有用户在设置里拨过才算数。
package com.example.zhengdao.baopu.eval

import android.content.Context
import androidx.core.content.edit

/** `eval_prod_on` 的写口（设置页开关调用它）；读仍走 [ProdEvalGate.prefOn]，不养第二份读法。 */
internal object ProdEvalSettings {

    /** 拨动开关（设置页唯一写口）。写失败按没写处理——下次读到的还是旧值，屏上状态回正。 */
    fun set(ctx: Context, on: Boolean) {
        runCatching {
            ctx.getSharedPreferences(ProdEvalGate.PREFS_NAME, Context.MODE_PRIVATE)
                .edit { putBoolean(ProdEvalGate.PREF_KEY, on) }
        }
    }

    /** 现读（与闸门同一出口；UI 初值也走它，保证「屏上显示的」与「闸门看到的」一致）。 */
    fun get(ctx: Context): Boolean = ProdEvalGate.prefOn(ctx)
}
