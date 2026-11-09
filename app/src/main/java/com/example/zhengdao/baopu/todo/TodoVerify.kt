// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百四十一批 · 待办中心的**右相接入**（谁判「做完」）。
//
// 用户口径（2026-11-09）：「待做 →（模型标）**已做** →（**右相三态 PASS**）已完成」＋
// 「权责：模型增改状态 · **右相判完成** · 用户只点最后一下」。
//
// 所以这里**不自己判**：本文件只做一件事——把"一条待办"装成右相吃得下的**托单**
// （[ChancellorSubmission]），三态结论由 [ChancellorGate] 出（302 定稿的判据一个字不改）。
// 谁判、判什么、判不了怎么办，全在那一份闸里；本文件一旦开始"补判一下"，
// 就等于在右相旁边又立了一个右相（302 §四「克制」当场破）。
//
// 托单怎么装（三样输入＋一个锚点，派工单 302 §一的形状）：
//   ① 交付物声称：`kind = FILE`，摘要＝这一条待办的那句话，路径＝**证据路径**（见下）；
//   ② 意见：空集——四器 Opinion 的取数源（298/306）还没接通，与 `ChancellorWiring`
//      现有两处调用点同一口径（接通后本文件不用改，[ChancellorGate] 的 C5 自动生效）；
//   ③ 账行：调用方给的快照（`FileOpLog` 最近 N 笔 ＋ 决策账全量），判据不回查库；
//   锚点 `root`：工作区根（C1 判"在不在"要有个相对的锚）。
//
// **证据路径**的口径（这是本批唯一的"判据设计"决定，写清楚免得后人当成魔法）：
//   「这条待办**立起来之后**，本仓文件账上最近一笔**落盘**动作（写/移/拷）指到的那个文件」。
//   为什么认账本而不认模型自述：C2 的口径本来就是「声称做了 ⇒ 账上必须有对应记录；
//   没有账行 = 未完成」（302 §二 C2）。模型嘴上说"做完了"不构成交付。
//   找不到任何落盘账行 ⇒ 路径给空串 ⇒ 右相自己回「缺交付路径（C1 判不了：只有摘要没有路径）」
//   ⇒ **UNVERIFIABLE**，条目停在「已做」。这条降级是闸里现成的分支，不是本文件的发明。
//
// 已知局限（如实写进交付报告 §未覆盖项）：账本行与"哪一条待办"的对应是**时间窗**近似的
// （一条待办立起来之后的第一笔/最近一笔落盘）。同一窗口里做三件事、却只落了一个文件时，
// 右相会给这个文件判 PASS——它能判的是"交付物真存在且账对得上"，判不了"这件事就是那条待办"。
// 要更准只能给 `todo_mark_done` 加一个证据参数，而 541 的派工单把签名钉死成 `(id)`，
// 所以本批不擅自加参（多一个参数就是换一个工具契约，得另立单）。
package com.example.zhengdao.baopu.todo

import com.example.zhengdao.baopu.FileOpEntry
import com.example.zhengdao.baopu.FileOpKind
import com.example.zhengdao.baopu.DecisionRecord
import com.example.zhengdao.baopu.verify.ChancellorClaimKind
import com.example.zhengdao.baopu.verify.ChancellorDeliverable
import com.example.zhengdao.baopu.verify.ChancellorGate
import com.example.zhengdao.baopu.verify.ChancellorLedger
import com.example.zhengdao.baopu.verify.ChancellorReport
import com.example.zhengdao.baopu.verify.ChancellorSubmission
import com.example.zhengdao.baopu.verify.ChancellorVerdict
import java.io.File

/** 待办 → 右相托单（只读账、只出结论；不改文件、不动账本）。 */
internal object TodoVerify {

    /** 取最近多少笔文件账行当快照（与 `ChancellorWiring.FILE_OPS_WINDOW` 同一把尺）。 */
    const val FILE_OPS_WINDOW: Int = 200

    /** 什么算"一次落盘交付"（读/列/搜/删 都不算交付：读不产生交付物，删是销毁不是交付）。 */
    private val DELIVERY_KINDS = setOf(FileOpKind.WRITE, FileOpKind.MOVE, FileOpKind.COPY)

    /**
     * 这条待办的证据（立起来之后最近一次落盘；没有 ⇒ null）。
     *
     * 只认 `at >= createdAtMs`：待办立起来**之前**的落盘，不能拿来给这条待办充数
     * （否则任何一条新待办都能靠旧账直接 PASS）。
     */
    fun evidenceOf(item: TodoItem, fileOps: List<FileOpEntry>): FileOpEntry? = fileOps
        .filter { it.at >= item.createdAtMs && it.kind in DELIVERY_KINDS }
        .maxByOrNull { it.at }

    /** 装托单（纯函数：同样的入参永远得到同一份托单，单测直接对拍）。 */
    fun submissionOf(
        wsRoot: File,
        item: TodoItem,
        fileOps: List<FileOpEntry>,
        decisions: List<DecisionRecord>,
    ): ChancellorSubmission = ChancellorSubmission(
        deliverables = listOf(
            ChancellorDeliverable(
                kind = ChancellorClaimKind.FILE,
                summary = item.text,
                path = evidenceOf(item, fileOps)?.path.orEmpty(),
            ),
        ),
        opinions = emptyList(),
        ledger = ChancellorLedger(fileOps = fileOps, decisions = decisions),
        root = wsRoot,
    )

    /**
     * 判一条（右相三态）。
     *
     * 闸自己抛异常（理论不该发生：它是纯判定）⇒ 回一份 UNVERIFIABLE，理由照实写
     * "右相没跑起来"——**fail-closed**：判不了就不许进「已完成」，也不许假装判过。
     */
    fun judge(
        wsRoot: File,
        item: TodoItem,
        fileOps: List<FileOpEntry>,
        decisions: List<DecisionRecord>,
    ): ChancellorReport {
        val sub = submissionOf(wsRoot, item, fileOps, decisions)
        return runCatching { ChancellorGate.verify(sub) }.getOrElse {
            ChancellorReport(
                verdict = ChancellorVerdict.UNVERIFIABLE,
                findings = emptyList(),
                notes = emptyList(),
                missing = listOf("右相没跑起来（${it.javaClass.simpleName}）：判不了，不当通过"),
                directPass = false,
                elapsedMs = 0L,
            )
        }
    }

    /** 结论压成一行（进账本 `verify` 行与工具回执；整份报告太长，进 `reason` 会被截）。 */
    fun brief(report: ChancellorReport): String = when (report.verdict) {
        ChancellorVerdict.PASS ->
            "右相 PASS：" + (report.notes.firstOrNull() ?: "判据全过")
        ChancellorVerdict.FAIL -> report.findings.firstOrNull()?.let {
            "右相 FAIL〔${it.criterion}〕${it.evidence}：${it.note}"
        } ?: "右相 FAIL（没带发现行）"
        ChancellorVerdict.UNVERIFIABLE ->
            "右相 UNVERIFIABLE：" + (report.missing.firstOrNull() ?: "判不了（缺证据），不当通过")
    }
}
