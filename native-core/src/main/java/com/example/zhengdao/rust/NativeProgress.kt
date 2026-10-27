// 第二百四十一批修正（2026-10-27）：`:native-core` ↔ `:terminal-core` 的解耦点
//
// 背景：`CoreNative.onProgress` 原先直接写 `com.example.zhengdao.rootfs.InstallPulse.fromRust(...)`，
// 那条调用把 Rust 核（本模块，主 APP 也要用）反向绑到了 rootfs（终端模块）。
// 修法：**反转回调** —— 本模块只暴露一个可注入的 sink；谁关心进度谁自己挂上来
// （现在是 `InstallPulse` 在初始化时挂；将来别的东西要用也照挂，不需要本模块认识它们）。
//
// 纪律：sink 为空 ⇒ 什么都不做（静默，不抛、不日志刷屏）；本模块**不许** import
// `com.example.zhengdao.rootfs.**` / `com.example.zhengdao.terminal.**` / `com.termux.**`。
package com.example.zhengdao.rust

/**
 * 解包进度回调的挂载点。
 *
 * - 由**消费方**（当前是 `:terminal-core` 的 `InstallPulse`）在初始化时挂上；
 * - 回调在 Rust 解包线程上执行，实现方自己负责线程安全与节流；
 * - 任何异常都被吞掉——进度不能反过来影响解包本身。
 */
object NativeProgress {
    /** `(累计条目数文本, 当前条目名)`；Rust 侧按约 200 条目一次回调。 */
    @Volatile
    var sink: ((entries: String, name: String) -> Unit)? = null

    /** 供 native 侧回调使用：空 sink 静默跳过。 */
    fun emit(entries: String, name: String) {
        val s = sink ?: return
        runCatching { s(entries, name) }
    }
}
