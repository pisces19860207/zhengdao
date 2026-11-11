// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百四十九批 · 准入的 **A2A 出题通道**（546 留的那道缝的第一半生产实现；MCP 侧归后续批）。
//
// 546 的口径原样复用：L1 题面（ProbeTasks／ExamTasks）与 L2 判卷（IntakeProbes／ConductExam）
// 一个字不动，本通道只负责"把卷送到对端、把答卷收回来"。三点如实：
//   · **开卷**：A2A 对端没有本机盘，读不到考场工作区——底稿随卷内嵌（`——附件 <路径>` 段），
//     读准/数对两题才有的考。write_exact 这类"落盘"题对纯文本对端**必不过**：考场量的就是
//     本地产物字节，对端写不进来（这是结构结果，不是放宽或加严）；
//   · **答卷只收线上可见的**：A2A 线上看不到对端的工具调用（toolCalls 恒空）——判卷只按
//     留痕（终答＋考场差集），品行考的外发题对 A2A 只考"卷面诚实"，不是线外监控（残余如实记）；
//   · **中断即残卷**：超时/要人/对端报失败 ⇒ 抛异常 ⇒ IntakeExam.askOne 记 null ⇒ 残卷
//     UNVERIFIABLE，不喂半份证据给门（546 判据②口径）。
package com.example.zhengdao.baopu.peer

import com.example.zhengdao.baopu.intake.ExamAnswer
import com.example.zhengdao.baopu.intake.ExamChannel
import com.example.zhengdao.baopu.intake.ExamQuestion
import java.io.File

internal class A2aExamChannel(
    private val client: A2aClient,
    private val card: A2aCard,
    /** 每题的轮询节奏与上限（卷是同步收的：九道题共用 [com.example.zhengdao.baopu.intake.IntakeExam.TIME_LIMIT_MS] 总上限）。 */
    private val pollEveryMs: Long = EXAM_POLL_EVERY_MS,
    private val maxWaitMs: Long = EXAM_QUESTION_MAX_WAIT_MS,
    private val sleeper: (Long) -> Boolean = { ms -> runCatching { Thread.sleep(ms) }.isSuccess },
) : ExamChannel {

    override fun answer(question: ExamQuestion, workspace: File): ExamAnswer {
        val run = client.sendAndPoll(
            card,
            paperOf(question),
            pollEveryMs = pollEveryMs,
            maxWaitMs = maxWaitMs,
            sleeper = sleeper,
        )
        when {
            run.timedOut -> throw A2aException(
                PeerFailureCode.TIMEOUT,
                "「${card.name}」这道题等不到收卷（超时）——按残卷处理，不算考过",
            )
            run.needsHuman -> throw A2aException(
                PeerFailureCode.PROTOCOL,
                "「${card.name}」考到一半停下来要人（${run.task.state.wire}）——这道题没收上答卷，按残卷处理",
            )
            !run.task.state.terminal -> throw A2aException(
                PeerFailureCode.PROTOCOL,
                "「${card.name}」这道题停在非终态（${run.task.state.wire}）——按残卷处理",
            )
        }
        return ExamAnswer(toolCalls = emptyList(), finalText = answerTextOf(run.task))
    }

    /** 题面正文＋底稿内嵌（开卷：对端读不到考场，卷里必须自带材料）。 */
    private fun paperOf(question: ExamQuestion): String = buildString {
        append(question.text)
        for ((path, text) in question.seeds) {
            append("\n——附件 ").append(path).append("（内容如下，就在本卷里）：\n").append(text)
        }
    }

    /** 终答＝状态说明＋产物文本（线上能看到的全部；产物字节由考场量，不信自报）。 */
    private fun answerTextOf(task: A2aTask): String {
        val pieces = buildList {
            if (task.statusMessage.isNotBlank()) add(task.statusMessage)
            for (artifact in task.artifacts) addAll(artifact.texts)
        }
        return pieces.joinToString("\n").trim()
    }

    companion object {
        /** 每题轮询间隔（考试在准入闸后面同步跑；间隔取小、上限取紧，别把闸吊死）。 */
        const val EXAM_POLL_EVERY_MS = 200L

        /** 单题收卷上限（总上限仍是 IntakeExam.TIME_LIMIT_MS＝60 秒，先到先断）。 */
        const val EXAM_QUESTION_MAX_WAIT_MS = 20_000L
    }
}
