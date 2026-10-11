// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 本文件只按 POSIX 标准接口（execve / wait4 / pipe / lstat 等）与 Android 官方文档实现，未参考任何第三方同类应用（含 termux-app）的源码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.oc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 太极网络层的**进程级持有者**（2026-10-11 用户报「每次重进太极都会重新连接」）。
 *
 * > 名字说明：本想叫 `OcSession`，但 `oc/OcDto.kt:26` 已有 `data class OcSession`（服务端会话
 * > DTO），重名会把它顶掉（实测编译报 `Unresolved reference 'id' / 'title'`）。这里的
 * > "Runtime" 指的是"进程内的运行期外壳"，不是会话。
 *
 * ## 原先为什么会重连
 *
 * 三层叠在一起，缺一条都还能忍，三条凑齐就成了"每进一次连一次"：
 *
 * | 层 | 原先 | 后果 |
 * |---|---|---|
 * | repository 的寿命 | `TaijiScreen` 内 `remember { }` | 切 Tab（`when (tab)` 让页面退出 Composition）即销毁 |
 * | 轮询 / SSE 任务的寿命 | 跑在 `rememberCoroutineScope()` 上 | 跟着页面一起被取消 |
 * | 重进页面的动作 | `LaunchedEffect` 每次调 `open()` | 重新握手 + 重新全量拉取 |
 *
 * ## 现在
 *
 * repository 与它的作用域都挂在**进程**上：切 Tab、转屏、退到后台都不再断连；
 * 只有三种情况会结束连接 —— 用户主动「关闭太极并退出」（`repo.close()` + [shutdown]）、
 * 进程被杀、或真的断线（由 `SseClient` 的退避重连接管）。
 *
 * ## 边界（刻意的）
 *
 * - **不自动起服务**：`opencode serve` 仍由 `TaijiScreen` 首次进入时
 *   `OcManager.startServe()` 拉起。[scope] 只保证**一旦连上就不再断**，
 *   不会让"从不用太极的人"在开机时多一个常驻进程。
 * - **单例只持有一个 repository**：与「单会话模型」一致（见 `OcRepository` 设计 §3.4）。
 * - 纯 REST 的旁路任务（如 `KnowledgeBaseSummarizerRunner`）**仍然自己 new 一套**，
 *   不共用这里——它那条注释写着"绝不干扰用户正在看的太极会话"。
 */
object OcRuntime {

    /**
     * 应用级作用域：跟进程同寿命，**不**随 Activity / Composition 销毁。
     *
     * `SupervisorJob` 是必须的：单个子任务失败（例如一次 401）不应该把整条连接连带取消。
     */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var repo: OcRepository? = null

    /** 取（或首次创建）全局唯一的 repository。 */
    fun repository(): OcRepository {
        repo?.let { return it }
        return synchronized(this) {
            repo ?: run {
                val client = OcClient(passwordProvider = { OcManager.servePassword })
                OcRepository(client, SseClient(client.sseClient)).also { repo = it }
            }
        }
    }

    /**
     * 结束并丢弃单例 —— 用户点「关闭太极并退出」时调用（与 `repo.close()` 成对）。
     *
     * 之所以要丢引用：`OcRepository.close()` 只取消两个 Job，实例本身仍可用；若留在单例里，
     * 下次进太极拿到的就是"一个关过但还活着的壳"，`open()` 的判据会以为连接仍在。
     */
    fun shutdown() {
        synchronized(this) {
            repo?.close()
            repo = null
        }
    }
}
