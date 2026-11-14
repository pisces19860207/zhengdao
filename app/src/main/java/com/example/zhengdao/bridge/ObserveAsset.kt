// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。

package com.example.zhengdao.bridge

import android.content.Context
import java.security.MessageDigest

/**
 * 645：OBSERVE 动作的观察项枚举——调用方唯一能传的东西。
 *
 * 为什么是枚举而不是字符串/任意参数：OBSERVE 的执行体是 assets 里**那一份**固定脚本
 * （[ObserveAsset]），端口只把枚举的 wire 字面量作为唯一的变量包进调用式。调用方
 * 传不进任何脚本，也传不进任何别的参数——想加观察面，只能显式加枚举成员＋改脚本
 * ＋改 hash，三件事在同一次 review 里（PortAction 的既有纪律：没有字符串动作名，
 * 没有"其它"兜底分支）。
 */
enum class ObserveItem(val wire: String) {
    /** 字段与标签文本（表单字段的身份面＋可及名——不含输入值）。 */
    FIELDS_TEXT("fields_text"),

    /** 语义节点表（可交互元素的定位面：tag/role/可及名/xp/cls——无正文无值）。 */
    NODE_TABLE("node_table"),

    /** 值形状（已填输入框的值只以 empty/short/digits/date/long 五形出页）。 */
    VALUE_SHAPE("value_shape"),
}

/**
 * 645：OBSERVE 固定脚本的资产壳——路径、写死的 hash、读资产、核对 hash、包装调用式。
 *
 * ## 为什么脚本放 assets 并写死 hash（判据：改脚本必须改 hash ＝ 强制 review）
 *
 * OBSERVE 是端口上第一个"脚本完全不在调用方手里"的动作：脚本内容属于安全面
 * （隐私三闸在页内源头执行，见脚本文件头），所以它随包发布（assets，改它必须发版）
 * ＋**SHA-256 写死在本文件**。改脚本却不改 hash ⇒ 运行时 [ObserveAsset.verify]
 * fail-closed 拒执行并记账、判据测试红——"顺手改一下脚本"这件事在闸上不存在。
 *
 * 行尾归一：hash 对 `\r\n`/`\r` → `\n` 归一后取——Windows checkout 换行不算
 * "改了脚本"；`.gitattributes` 同时把该资产钉成 LF，双保险。
 *
 * 本文件零 BrowserCommands 引用（判据①的例外只有 BrowserPort 内部实现一处），
 * 也不执行任何东西——读资产、核 hash、拼字符串，都是纯壳。
 */
object ObserveAsset {

    /** APK 内路径（assets 根以下；`assets/bridge/observe_v1.js`）。 */
    const val PATH: String = "bridge/observe_v1.js"

    /**
     * 固定脚本 v1 的 SHA-256（**写死**：改 assets 里的脚本必须同步改这里——
     * 判据测试按本值核对资产文件，运行时按本值 fail-closed）。
     */
    const val SHA256: String = "ded167b97848f21dfa366a6d3aca57f0d49dd6e291f7d22b2fbb6e4b0272761d"

    /** 读资产（读不到/读坏 ⇒ null，调用方 fail-closed 拒执行，不猜）。 */
    fun load(context: Context): String? = runCatching {
        context.assets.open(PATH).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }.getOrNull()

    /** 行尾归一（CRLF/CR → LF）：hash 只认内容，不认 checkout 的换行形态。 */
    fun normalizeEol(text: String): String = text.replace("\r\n", "\n").replace("\r", "\n")

    /** 归一化后取 SHA-256 hex（小写）。 */
    fun sha256Hex(text: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(normalizeEol(text).toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }
    }

    /** hash 闸本体：资产文本与写死值一致才放行。 */
    fun verify(text: String): Boolean = sha256Hex(text) == SHA256

    /**
     * 包装成一次 evaluateJavascript 的调用式：`(<脚本>)("<观察项wire>")`。
     * 唯一的变量是枚举字面量（[jsStringLiteral] 带引号转义嵌不进去）——脚本本体
     * 原样内嵌不转义，这是执行层的既有口径（execJs 的 code 同款）。
     */
    fun wrap(script: String, item: ObserveItem): String =
        script.trim() + "(" + jsStringLiteral(item.wire) + ")"
}
