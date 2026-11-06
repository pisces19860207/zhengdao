// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百三十六批 B · 预读回执闸：**接线门面**（闸的对外唯一入口，带 fail-open）。
//
// ## 为什么闸只认这一个口
// GuardPipeline 里不许出现"预读怎么判"的逻辑——判据在 [PreReadRules]、态在 [PreReadBoard]、
// 账在 [PreReadLedger]。管线只问一句"这笔走不走"。三处分开的代价是多一层，
// 换来的是判据能单测直接算、账能单测直接读、管线那几行能被源级测试钉住位置。
//
// ## fail-open 是本批的红线，不是偷懒（判据⑥）
// 「超时/异常 ⇒ 放行并记账（不许拦死主流程）」——这一句决定了这里**必须**兜住一切异常：
// 板子内是个 LinkedHashMap/HashSet 操作，账是文件追加（可能碰上磁盘满、目录被清、
// 落点没权限）。那些都不该让用户的任务停摆。所以：
//   · 判据算不出来 ⇒ 放行；
//   · 放行之前先落一行 [PreReadEventKind.FAIL_OPEN]，让"闸自己坏了"在账上看得见；
//   · 连这行都落不上 ⇒ 只 RunLog，仍然放行（留痕失败也不许反过来卡住主流程）。
// 唯一不放的是态③**正常算出来**的那个"未交回执"——那是裁定要拦的，不是异常。
//
// ## taskKey 从哪来（生产只有一条真路）
// 450 批回填的 `taskId`（＝已批准计划契约的规范化摘要），没回填时回落本轮 `runId`；
// 两个都空 ⇒ 本批**不参与**（不拦、不落、不编一个键去猜是哪个任务）。
package com.example.zhengdao.baopu.preread

import com.example.zhengdao.baopu.agent.DeviationWiring
import com.example.zhengdao.core.RunLog

/** 预读回执闸的对外门面（管线、派单侧、承接侧、收工侧都从这里进）。 */
internal object PreReadWiring {

    /** 日志前缀（grep 用；拦/放/坏三侧都带它，logcat 里一条链看得出）。 */
    const val LOG_HEAD = "抱朴预读"

    /** 当前任务键：450 的 taskId 优先，回落 runId；都没有 ⇒ 空串（本批不参与）。 */
    fun taskKey(): String =
        DeviationWiring.currentTaskId().ifBlank { DeviationWiring.currentRunId() }

    /**
     * 管线执行前问这一句（判据②「未交回执 ⇒ 不许调用任何写工具」的唯一生产入口）。
     *
     * @return null＝放行（含只读工具、态①②、已交回执、以及一切异常 fail-open）；
     *         非 null＝拦住，字符串就是回给模型的那句可操作提示
     */
    fun beforeToolCall(tool: String, isWrite: Boolean): String? {
        val key = taskKey()
        if (key.isBlank()) return null
        return runCatching {
            val r = PreReadBoard.gateFor(key, tool, isWrite)
            if (r.message.isNotBlank()) RunLog.log("$LOG_HEAD｜${r.mode.cn}｜$tool｜${r.message}")
            if (r.blocked) r.message else null
        }.getOrElse {
            failOpen(key, tool, it)
            null
        }
    }

    /**
     * 开工那一句提示（态②；态①③返回 null）。由接线批次在起跑处念一次。
     * 提示**不拦**——这是裁定里"仍可写"的那一档。
     */
    fun startHint(): String? {
        val key = taskKey()
        if (key.isBlank()) return null
        return runCatching { PreReadBoard.hintAtStart(key) }
            .getOrElse {
                failOpen(key, "start_hint", it)
                null
            }
    }

    /** 派单侧：附一张必读清单（判据①）。返回不合规项，空＝收下。 */
    fun attachOrder(order: ReadFirstOrder): List<OrderIssue> {
        val issues = runCatching { PreReadBoard.attach(order) }.getOrElse {
            failOpen(order.taskKey, "attach", it)
            return emptyList()
        }
        if (issues.isNotEmpty()) RunLog.log("$LOG_HEAD｜任务单没收下：${issues.joinToString("、") { it.cn }}")
        return issues
    }

    /**
     * 承接侧：交回执（判据②）。返回不齐的项，空＝收下并解锁本任务的写工具。
     *
     * 这里刻意**不**做 fail-open 的"缺行也放行"：三行不齐是裁定写明"视为未交"，
     * 不是异常。门面坏了才放行，内容不齐不放行——两者不能混，混了闸就是纸糊的。
     */
    fun submitReceipt(receipt: PreReadReceipt): List<ReceiptGap> {
        val gaps = runCatching { PreReadBoard.submit(receipt) }.getOrElse {
            failOpen(receipt.taskKey, "submit", it)
            return emptyList()
        }
        if (gaps.isNotEmpty()) RunLog.log("$LOG_HEAD｜回执没收下：${gaps.joinToString("、") { it.cn }}")
        return gaps
    }

    /** 承接侧：声明"本任务不要求回执"（拦住那句提示给的第二出口）。true＝这次生效。 */
    fun declareNotRequired(why: String = ""): Boolean {
        val key = taskKey()
        if (key.isBlank()) return false
        return runCatching { PreReadBoard.declareNotRequired(key, why) }
            .getOrElse {
                failOpen(key, "declare", it)
                false
            }
    }

    /** 自动武装（长程/跨会话 ⇒ 要求回执）。判定复用 274 的 isLongTask，本批不另造。 */
    fun armByLongTask(itemCount: Int, crossSession: Boolean): Boolean {
        val key = taskKey()
        if (key.isBlank()) return false
        return runCatching { PreReadBoard.armByLongTask(key, itemCount, crossSession) }
            .getOrElse {
                failOpen(key, "arm", it)
                false
            }
    }

    /**
     * 收工对照（判据③）：**只落账与提出，不阻断**。返回 null＝没单没据或兜住了异常。
     *
     * @param touched 本轮真写过的文件（生产侧传引擎收尾那份清单，与 242 契约同一口径）
     */
    fun closeout(touched: List<String>): ScopeDiff? {
        val key = taskKey()
        if (key.isBlank()) return null
        return runCatching {
            val diff = PreReadBoard.closeout(key, touched)
            if (diff != null && diff.hasFindings) {
                RunLog.log(
                    "$LOG_HEAD｜收工对照：对上 ${diff.matched.size}｜说改未改 ${diff.notTouched.size}" +
                        "｜清单外 ${diff.outOfScope.size}（记账不阻断）",
                )
            }
            diff
        }.getOrElse {
            failOpen(key, "closeout", it)
            null
        }
    }

    /** 回看（判据④「它当时怎么理解的」）：任务单 → 回执 → 对照，一屏。 */
    fun review(touched: List<String> = emptyList()): List<String> {
        val key = taskKey()
        if (key.isBlank()) return listOf("$LOG_HEAD｜没有任务键，回看不了（账在盘上仍可按任务查）")
        return runCatching { PreReadBoard.review(key, touched) }
            .getOrElse {
                failOpen(key, "review", it)
                listOf("$LOG_HEAD｜回看没算出来（已记账放行）")
            }
    }

    /** 当前态（探针/体检/卡面读数）。 */
    fun modeNow(): PreReadMode {
        val key = taskKey()
        if (key.isBlank()) return PreReadMode.OFF
        return runCatching { PreReadBoard.modeFor(key) }.getOrDefault(PreReadMode.OFF)
    }

    /** 一行结论（体检/审计用；只出计数）。 */
    fun conclusion(): String = runCatching { PreReadLedger.conclusion() }.getOrDefault("$LOG_HEAD｜账读不出")

    // ── 内部 ────────────────────────────────────────────────────────────────

    /** 闸自己坏了：先落一行 FAIL_OPEN，再放行（判据⑥）。落不上也只 log，不抛。 */
    private fun failOpen(taskKey: String, where: String, err: Throwable) {
        RunLog.log("$LOG_HEAD｜$where 出异常 ⇒ 放行并记账——${err.javaClass.simpleName}: ${err.message}")
        if (taskKey.isBlank()) return
        runCatching {
            PreReadLedger.append(
                PreReadRow(
                    id = "pr:fail:$where:${System.currentTimeMillis()}",
                    atMs = System.currentTimeMillis(),
                    taskKey = taskKey,
                    kind = PreReadEventKind.FAIL_OPEN,
                    text = "$LOG_HEAD：$where 异常 ⇒ 放行（不许拦死主流程）",
                    detail = listOf("${err.javaClass.simpleName}: ${err.message}"),
                ),
            )
        }
    }
}
