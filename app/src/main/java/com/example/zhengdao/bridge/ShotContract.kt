// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。

package com.example.zhengdao.bridge

/**
 * 截图与证据快照的**回执契约**（622 从 BrowserCommands 搬出：bridge 包里两类调用方——
 * 执行端口 BrowserPort 与执行层 BrowserCommands——共用这两个形状；搬出来之后
 * 「bridge 之外只许引用端口」的源码断言才不破：调用方判回执类型不再需要碰
 * BrowserCommands 这个名字）。
 *
 * 字段语义一字未动，搬家的判据与历史见各类型 KDoc（原 BrowserCommands KDoc 的
 * 脱敏边界表仍管着这些字段——新增字段先进表再落码）。
 */

/** 一次截图的落盘结果（第一百七十七批：**JSON 与 ref 两个出口共用这一份**）。 */
data class ShotRef(
    val file: String,
    val hostPath: String,
    val guestPath: String,
    val bytes: Int,
    val mode: String,
    val width: Int,
    val height: Int,
    val scale: Float,
    val note: String?,
)

/** 截图结局：要么拿到落盘引用，要么一句可读错误（不抛、不回 1×1 假成功）。 */
sealed interface ShotCapture {
    data class Ok(val ref: ShotRef) : ShotCapture
    data class Bad(val error: String) : ShotCapture
}

/** `evidence` 的回包：Ok 带最终文本（已脱敏＋钳制＋render），Bad 带原因。 */
sealed class EvidenceCapture {
    data class Ok(val text: String) : EvidenceCapture()
    data class Bad(val error: String) : EvidenceCapture()
}
