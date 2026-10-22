// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第一百二十二批 · 政策表 v1：**审计行**——用了哪一版、从哪来、是不是被拒了。
//
// 只产出文本，不写盘（落盘由装配层 `RunLog.log` 做）：政策包因此保持零 Android、
// JVM 直测；与 [com.example.zhengdao.baopu.agent.ToolGroupAudit] 同一个做法。
package com.example.zhengdao.baopu.policy

import java.util.Locale

/**
 * `[policy]` 前缀是可 grep 的锚：谁想知道「这台机器按哪版政策跑的」，
 * `grep '\[policy\]'` 就能捞到安装那一刻的账。
 */
internal object PolicyAudit {

    /** 日志锚。 */
    const val TAG = "[policy]"

    /** 表通过校验并生效。 */
    fun loaded(policy: Policy, source: String): String = line(
        "v${policy.version} 已加载 来源=$source" +
            " 工具组=${policy.toolGroups.defaultVisible.sortedBy { it.ordinal }.joinToString(",") { it.key }}" +
            " 组闸=${policy.approval.groupReaskThreshold}" +
            " eval=${"%.2f".format(Locale.ROOT, policy.eval.floor)}/${policy.eval.minEffectSamples}",
    )

    /** 表不在：按内置默认执行，明确说「没有放宽」。 */
    fun missing(source: String): String =
        line("未找到 来源=$source；按内置默认执行（未放宽）")

    /** 表在但不合格：整张拒绝，明确说「按上一份/默认执行」。 */
    fun invalid(reason: String, source: String): String =
        line("拒绝 来源=$source；原因=${flat(reason)}；按当前政策执行（未放宽）")

    private fun line(body: String): String = "$TAG ${flat(body)}"

    /** 日志按行读：一个换行就能把一条账劈成两条，这里压平。 */
    private fun flat(v: String): String =
        v.replace('\n', ' ').replace('\r', ' ').trim()
}
