// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：第二百六十九批派工单 §3——三种结局要分开（与 ToolOutcome 的三分法同源）：
// · [Ok] 正常完成，带回读数；
// · [Rejected] **判据拒绝**（zip-slip／超 2GB／同名／不支持的格式）——用户可读的原因，
//   整包不落盘或落盘前已回滚，重试同一个包没有意义；
// · [Failed] 包坏了/IO 失败——可换包重试。
// 混在一起会让模型「再试一次」撞同一堵墙（程枢版 §8 的原话）。
package com.example.zhengdao.baopu.archive

internal sealed interface ExtractOutcome {
    /** @param entries 落盘条目数；@param bytes 实际写出字节数（不是包头声明值）。 */
    data class Ok(val entries: Int, val bytes: Long) : ExtractOutcome
    data class Rejected(val reason: String) : ExtractOutcome
    data class Failed(val reason: String) : ExtractOutcome
}
