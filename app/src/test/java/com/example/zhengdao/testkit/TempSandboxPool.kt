// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。

package com.example.zhengdao.testkit

import java.io.File

/**
 * 573：存量测试迁 [TempSandbox] 时的「跨作用域注册表」。
 *
 * ## 为什么需要它
 *
 * 一批存量测试把临时目录建在**嵌套 class / object / 字段初始化**里（例：
 * `GuardRawDirTest` 的 `private class Fixture`、`ConfirmFlowTest` 的 `private class Rig`、
 * `EvalFeedChainTest` 的 `private val temp`）。外层测试类的实例成员从嵌套类里访问不到，
 * 而 JUnit 的 `@After` 只能挂在外层测试类上 —— 若把「注册 + 清理」都做成外层实例成员，
 * 这些文件会直接编译不过。
 *
 * 本对象把「谁建了目录」记成一份与类层级无关的清单：任何作用域都只换一行创建调用，
 * 外层类的 `@After` 统一调 [clean] 收口，**测试断言一行都不用动**。
 *
 * ## 纪律
 *
 * - 出口仍是 [TempSandbox]：建走 [TempSandbox.create]、删走 [TempSandbox.deleteTree]；
 *   删不干净照样抛（不静默通过），本对象不另造一套删除逻辑。
 * - 单 JVM 串行跑单测（Gradle 默认 `maxParallelForks = 1`，JUnit4 默认不并行）⇒
 *   每个测试方法结束后清单即被清空，不跨用例攒目录。
 * - 只用于 `app/src/test`；Android 仪器测试看不到它，那边走设备侧目录。
 */
object TempSandboxPool {

    private val roots = mutableListOf<File>()

    /** 建一个沙盒并登记；等价于 `TempSandbox.create(prefix)`，只是多了退出时的清理凭据。 */
    @Synchronized
    fun create(prefix: String): File =
        TempSandbox.create(prefix).also { roots += it }

    /** 由外层测试类的 `@After` 调用：删干净并清空清单。 */
    @Synchronized
    fun clean() {
        roots.forEach { dir ->
            check(TempSandbox.deleteTree(dir) == 0) { "573 沙盒未删干净：$dir" }
        }
        roots.clear()
    }
}
