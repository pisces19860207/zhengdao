// 独立开发声明：本文件为本项目从零编写。
package com.example.zhengdao.terminal

/**
 * 安装包下载进度的**分档**规则（纯逻辑，可单测）。
 *
 * ## 为什么分 20% 一档
 * 下载回调按网络缓冲触发，一秒可能来几十次；进度文案每变一次就要刷一次横幅
 * （`InstallFlow` 的通知栏/终端页横幅跟着走）。不分档就是刷屏 + 每帧重组。
 * 20% 一档 ⇒ 一次 192MB 首装最多刷 6 次，用户看得到推进、又不会被高频更新打扰。
 *
 * ## 判据（与搬移前逐字一致）
 * `((done * 100 / total).coerceIn(0, 100) / 20) * 20`：
 * 先算整数百分比（向下取整，避免"还没走完就显示 100%"），夹到 0–100
 * （镜像返回错误的总长度时也不出 `-20%` 或 `120%`），再向下取整到 20 的倍数。
 *
 * @param done 已下载字节数（≥0）。
 * @param total 总字节数；**前置条件 `total > 0`**（调用点 `startInstall` 的下载回调
 *   本来就包在 `if (total > 0)` 里；返回头没有 Content-Length 时下载器走等价口径，
 *   见 `RootfsDownloader.downloadOne`）。
 * @return 0/20/40/60/80/100 之一；**类型是 Long**——调用方拿它跟 `lastPercent: Long`
 *   比较去重、再 `.toInt()` 交给 `installStatus`（与搬移前的类型逐字一致）。
 */
internal fun downloadPercentBucket(done: Long, total: Long): Long {
    return ((done * 100 / total).coerceIn(0, 100) / 20) * 20L
}
