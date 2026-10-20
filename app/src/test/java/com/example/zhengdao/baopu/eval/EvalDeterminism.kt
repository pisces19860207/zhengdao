// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第九十四批 ②「确定性 eval 模式（只影响 eval）」——
// eval/自检时显式 temperature=0、seed（provider 支持才传），把 provider/model/参数
// 记进 eval 结果；provider 不支持 seed ⇒ 明确标 NON_DETERMINISTIC，不假装可复现。
//
// 这个文件只做**纯判定**：不发请求、不读环境以外的东西、不碰任何线上对话参数。
package com.example.zhengdao.baopu.eval

import org.json.JSONObject

/**
 * 一次 eval 的确定性口径（第九十四批 ②）。
 *
 * 为什么要把「参数」与「可复现」分成两个字段：`temperature=0` 只是把采样噪声压掉，
 * **不等于**服务端会给出逐字节相同的回答（并发、批处理、模型版本都会动）。所以
 * [deterministic] 报的是「参数层面已经尽力固定」，[label] 里那句括号必须把话说全，
 * 不许写成「可复现」三个字就完了。
 *
 * @param temperature eval 请求体里显式发出的温度（默认 [EVAL_TEMPERATURE] = 0.0）
 * @param seed 显式发出的采样种子；**null = 没发**（provider 未必支持，见 [seedFrom]）
 */
internal data class EvalDeterminism(
    val temperature: Double = EVAL_TEMPERATURE,
    val seed: Long? = null,
) {

    /** 参数层面是否已固定：温度 0 且种子真发出去了。 */
    val deterministic: Boolean get() = temperature == 0.0 && seed != null

    /**
     * 结果里那一行标签。
     *
     * 支持 seed ⇒ `DETERMINISTIC(temperature=0, seed=N)`；
     * 不支持 ⇒ `NON_DETERMINISTIC(...)`——**明写「不保证可复现」比含糊更有用**：
     * 一条偶发失败只有在标了 NON_DETERMINISTIC 的时候才不会被当成真回归。
     */
    fun label(): String = if (deterministic) {
        "DETERMINISTIC(temperature=0, seed=$seed)"
    } else {
        "NON_DETERMINISTIC(temperature=$temperature, seed=${seed ?: "未发送"})"
    }

    /** 人话原因（报告与流水里都贴这句，免得只看标签看不出为什么）。 */
    fun reason(): String = when {
        temperature != 0.0 -> "temperature=$temperature 不是 0，采样本身就是随机的"
        seed == null -> "provider 未必支持 seed ⇒ 不传（本仓库没有核实过任何 provider 支持，" +
            "按不支持处理；要开：ZHENGDAO_EVAL_SEED=<整数>）"
        else -> "temperature=0 且已发 seed=$seed；仍可能不同（服务端只尽力而为，不是契约）"
    }

    /** 记进 eval 结果/trace 的那一块（键名与请求体同名，便于对账）。 */
    fun toJson(): JSONObject = JSONObject()
        .put("temperature", temperature)
        .put("seed", seed ?: JSONObject.NULL)
        .put("deterministic", deterministic)
        .put("label", label())
        .put("reason", reason())

    companion object {

        /** 环境变量：显式开关 seed（不设 = 不发）。 */
        const val ENV_SEED = "ZHENGDAO_EVAL_SEED"

        /** eval 显式温度（派工单 ② 的原文要求）。 */
        const val EVAL_TEMPERATURE = 0.0

        /** 这些写法的意思是「关掉 seed」。 */
        private val OFF: Set<String> = setOf("", "off", "none", "disabled", "no", "false", "null")

        /**
         * 解析 seed 取值。
         *
         * **默认 null（不发）**——这是刻意的：`DeepSeek / OpenAI 兼容 / Anthropic`
         * 各自的 `seed` 支持情况本仓库一处都没核实过（C5：不臆造 API 能力）。
         * 发一个对方不认的种子只会让结果里多一个「看起来固定了」的假象，
         * 所以默认按「不支持」处理并在结果里标 NON_DETERMINISTIC；
         * 确认某通道支持后，用 `ZHENGDAO_EVAL_SEED=1234` 打开。
         *
         * @param raw 环境变量原值（null = 没设）
         * @return 整数种子；null = 不发；非数字/关掉写法一律 null
         */
        fun seedFrom(raw: String?): Long? {
            val s = raw?.trim()?.lowercase().orEmpty()
            if (s in OFF) return null
            return s.toLongOrNull()
        }

        /** 从环境读（测试可注入另一个取值函数）。 */
        fun seedFromEnv(env: (String) -> String? = { System.getenv(it) }): Long? = seedFrom(env(ENV_SEED))
    }
}
