// 独立开发声明：本文件为本项目原创。
package com.example.zhengdao.terminal

/**
 * 安装包下载进度的**分档**规则（纯逻辑，可单测）。
 *
 * ## 为什么改 1% 一档（2026-10-22 用户口径）
 * 原实现 20% 一档：192MB 只跳 6 次，慢网（20–170 KB/s）下用户盯着「0%」十几分钟，
 * 分不清是「在走」还是「卡死」。用户原话：「百分比应该是个位数跳动的话，会心里更有数」。
 * ⇒ 改为 **1% 一档**：最多 100 次更新；配合调用点的去重（同档不刷），
 * 横幅/通知不会刷屏，但每推进约 2MB 就能看到一个数字变化。
 *
 * ## 判据
 * `(done * 100 / total).coerceIn(0, 100)`：
 * 整数百分比向下取整（没走完不显示 100%），夹到 0–100
 * （镜像返回错误总长时也不出负数或 120%）。
 *
 * @param done 已下载字节数（≥0，含断点续传已落盘的那部分）。
 * @param total 总字节数；**前置条件 `total > 0`**（调用点 `startInstall` 的下载回调
 *   本来就包在 `if (total > 0)` 里；返回头没有 Content-Length 时下载器走等价口径，
 *   见 `RootfsDownloader.downloadOne`）。
 * @return 0–100 的整数百分比；**类型是 Long**——调用方拿它跟 `lastPercent: Long`
 *   比较去重、再 `.toInt()` 交给 `installStatus`（与原签名的类型一致）。
 */
internal fun downloadPercentBucket(done: Long, total: Long): Long {
    return (done * 100 / total).coerceIn(0, 100)
}
