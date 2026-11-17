// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百八十四批 · 工具故障注入的**缝**（release 里就只剩这一条缝）。
//
// 病灶：工具失败路径（`run_log.err` → 归因层 → 约束卡）只有真出事才走得到——
// 环境好的时候没人能验「失败三次到底会不会弹一张卡」，只能靠等。要验它就得能让
// **指定工具按规则失败**，而且必须是**真失败**：真写 `run_log.err`、真进归因层，
// 不是往日志里抄三行假话。
//
// 本文件只放**一根针**：一个接口 ＋ 一个可空引用。它**没有**规则、没有文件名、
// 没有开关字面量，也没有任何入口——那些全在 `app/src/debug/java` 的
// `com.example.zhengdao.debug.FaultInjection` 里，release 编译时根本不参与，
// 所以正式包里连"注入"这两个字都找不到（判据③；源码断言见
// `FaultInjectionExclusionTest`，产物断言见 `tools/check-fault-inject-absent.py`）。
//
// 为什么缝必须留在 main：工具只有 [GuardPipeline] 一条执行路径（§5 红线），
// 注入要落在**执行那一段**（④ 之前、①②③ 之后）才谈得上"真失败路径"。
// 缝的行为只有一个：`hook` 为 null ⇒ 原样执行。release 恒 null（没人赋值），
// 所以「注入关闭 ⇒ 行为与现在完全一致」在正式包里是**结构上**成立的。
package com.example.zhengdao.baopu.agent

import org.json.JSONObject

/**
 * 故障注入实现（**只有 debug 源集有实现类**）。
 *
 * 命中规则 ⇒ 返回一个要冒充的 [ToolOutcome]（失败）；不命中 ⇒ null（照常执行）。
 * 实现方**不许**碰盘、不许改参数、不许在命中时把真工具也跑一遍——它的全部职责
 * 就是「这次执行的结果长什么样」，后面的 ⑤（spill/围栏）、引擎记账、归因全是原路。
 */
internal fun interface ToolFaultHook {
    fun intercept(tool: String, args: JSONObject): ToolOutcome?
}

/** 缝本体：一个可空引用 ＋ 一次判空（release 里永远为空）。 */
internal object ToolFaultSeam {

    /** debug 的安装器在进程起来时赋值；release 没有任何写入点。 */
    @Volatile
    var hook: ToolFaultHook? = null

    /** ④ 之前问一句：这次要不要冒充失败。null ⇒ 调用方照原样执行。 */
    fun intercept(tool: String, args: JSONObject): ToolOutcome? = hook?.intercept(tool, args)
}
