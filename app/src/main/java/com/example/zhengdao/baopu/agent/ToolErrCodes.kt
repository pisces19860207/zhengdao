// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百二十批收口（2026-11-13，用户裁定）：工具失败**码**的唯一一份。
//
// 为什么码要单独成表：631 往 `run_log.err` 里写的是「码 + 固定短句」，
// 620 的归因要从同一列读回来判「谁的错」。两边各写一份表 ⇒ 迟早漂
// （实测过：631 的 11 条码**一条都不命中** 620 的中文词表 ⇒ 全落未归因、
// 该弹的行为类约束卡一张不弹）。所以：
//   · 码、短句、归属、特征词，**只在本文件定义**；
//   · 631（写侧）从这里取「码 → 短句」；
//   · 620（读侧）从这里取「码 → 归属」；
//   · [ALL] 是完整性断言的唯一依据（新增码没写归属 ⇒ 测试红）。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.FailureOwner

/**
 * 一个工具失败码。
 *
 * @param code 码文本（写进 `err` 的前缀，形如 `timeout`）
 * @param line 写进 `err` 的固定短句（**不含正文**：正文一律不带）
 * @param owner 归属（决定要不要提约束卡）；`protocol` 归一为行为类，见下注
 * @param patterns 从失败原文认这个码的特征词（**宁窄不宽**，认错比认不出更糟）
 * @param note 归属口径的一句话备注（`protocol` 那条必须写清「对端/协议侧问题时归环境类」）
 */
internal data class ToolErrCode(
    val code: String,
    val line: String,
    val owner: FailureOwner,
    val patterns: List<Regex>,
    val note: String = "",
)

internal object ToolErrCodes {

    /** `err` 里「码：短句」的分隔符（写侧与读侧共用，改一处即两处一起变）。 */
    const val SEPARATOR = ": "

    /**
     * 全部码（**唯一一份**）。顺序有意义：写侧按此顺序认码，先命中先用。
     *
     * 归属口径（用户 2026-11-13 裁定）：
     * · `dns/port/tls/timeout/auth/config/network/version/endpoint/refused` ⇒ **环境类**
     *   （只记账、不弹卡：这些都是「外面不好」或「人被闸挡了」，约束模型既冤枉也修不好）；
     * · `protocol` ⇒ **行为类**（模型把参数发错形状），但若证据显示是对端/协议侧问题，
     *   应改归环境类 —— 这条备注写在下面对应行里。
     */
    val ALL: List<ToolErrCode> = listOf(
        ToolErrCode("dns", "dns: 域名解析不了", FailureOwner.ENVIRONMENT, listOf(Regex("域名解析|\\bDNS\\b"))),
        ToolErrCode("port", "port: 端口拒绝连接", FailureOwner.ENVIRONMENT, listOf(Regex("拒绝连接"))),
        ToolErrCode("tls", "tls: 证书信不过", FailureOwner.ENVIRONMENT, listOf(Regex("证书|\\bTLS\\b"))),
        ToolErrCode("timeout", "timeout: 等太久没回音", FailureOwner.ENVIRONMENT, listOf(Regex("超时"))),
        ToolErrCode(
            "version", "version: 协议版本对不上", FailureOwner.ENVIRONMENT,
            listOf(Regex("版本对不上|版本不匹配|不在支持区间|protocolVersion")),
        ),
        ToolErrCode(
            "endpoint", "endpoint: 那儿没有这条接口", FailureOwner.ENVIRONMENT,
            listOf(Regex("没有这条接口|HTTP 404")),
        ),
        ToolErrCode(
            "auth", "auth: 要先验明身份", FailureOwner.ENVIRONMENT,
            listOf(Regex("需要授权|凭据无效|授权已失效")),
        ),
        ToolErrCode(
            "config", "config: 本机配置不过检", FailureOwner.ENVIRONMENT,
            listOf(Regex("配置不过检|配置不可用|非 ASCII")),
        ),
        ToolErrCode(
            "protocol", "protocol: 说的不是一路话", FailureOwner.BEHAVIOR,
            listOf(Regex("说的不是一路话|协议不符|不是合法 JSON")),
            note = "模型把参数发错形状 ⇒ 行为类；若证据显示是对端/协议侧问题，改归环境类",
        ),
        ToolErrCode(
            "refused", "refused: 外发没批准，没有发起", FailureOwner.ENVIRONMENT,
            listOf(Regex("外发没有批准|未批准外发")),
        ),
        ToolErrCode(
            "network", "network: 连接半路断了", FailureOwner.ENVIRONMENT,
            listOf(Regex("尚未连接|连接已断|连接半路断了")),
        ),
    )

    /** 按码文本查（读侧用）。 */
    fun byCode(code: String): ToolErrCode? = ALL.firstOrNull { it.code == code }

    /**
     * 从 `err` 文本里认码（读侧用）：形如 `timeout: …` 的开头。
     *
     * 只认**开头**：正文里偶然出现「timeout:」不算——那是文本，不是码。
     */
    fun parse(err: String): ToolErrCode? {
        val text = err.trim()
        val idx = text.indexOf(SEPARATOR)
        if (idx <= 0) return null
        return byCode(text.substring(0, idx).trim().lowercase())
    }
}
