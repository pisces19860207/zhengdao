// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。

package com.example.zhengdao.baopu

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 第六百三十八批：教学态的**进程级唯一持有者**（解 623 判据③方向二）。
 *
 * 为什么要有它：`teachRecording / teachStepCount / teachDraft` 原先是 `BrowserScreen` 内的
 * `remember`；`when (tab)` 切到抱朴页时玄览组合被销毁，三态跟着归零。于是「教学中切去抱朴
 * 发一条 → 回玄览胶囊仍在、能看到那条」在同一教学期内根本做不出来（623B 偏差 11）。
 *
 * 上提后的语义（派工单 §1②）：
 * · 切页 / 切标签**不结束**教学 —— 状态在 `object` 里，组合进出不碰它；
 * · 只有「显式停止」或「保存 / 放弃草案」才结束 —— 走本类的方法，页面不许直接写；
 * · 进程被杀即结束 —— 纯内存 Compose state，不落盘、不读 SP、不开 DB、不写 jsonl。
 *
 * 单一真相源：三态**只在这里定义**；`TeachSessionUiModel` 里只剩录制器自身的守卫
 * （`recording = recorder != null`），不再另存步数 / 草稿，避免第二个真相源。
 * UI 侧那份 `TeachSessionUiModel` 由 `BaopuBrowserWiring.uiTeachModel` 在进程级持有
 * （与既有 `TeachSessionUiModel` lazy 单例同房型），它的协程域是进程级的：原先
 * `rememberCoroutineScope` 会随切页取消，录制器会被一起收掉，教学就"看着还在、其实死了"。
 */
object TeachSessionState {

    /** 三态唯一定义处（全仓源码断言：`teachRecording` 定义处恰好一处）。 */
    var teachRecording: Boolean by mutableStateOf(false)
        private set

    /** 录制步数（玄览在屏时 1s 轮询从 `TeachSessionUiModel.recordedSoFar` 读回）。 */
    var teachStepCount: Int by mutableIntStateOf(0)
        private set

    /** 待确认的技能草案（非 null ⇒ 教学未结束，胶囊转「待确认」红点；623 判据⑪）。 */
    var teachDraft: TeachSkillDraft? by mutableStateOf(null)
        private set

    /** 教学模式的唯一合成口径：录制中或待确认。 */
    fun teachingNow(): Boolean = teachRecording || teachDraft != null

    /** 开始录制：显式起点，清掉上一轮残留。 */
    fun beginRecording() {
        teachRecording = true
        teachStepCount = 0
        teachDraft = null
    }

    /** 轮询读数。切走玄览时这条轮询停，但录制器跑在进程级协程域里照收事件。 */
    fun noteSteps(count: Int) {
        teachStepCount = count
    }

    /**
     * 显式停止。没出草案 ⇒ 三态归零、教学结束；出了草案 ⇒ 只停录制，草案留着等
     * 保存 / 放弃（623 判据⑪：胶囊转红点，还不算结束）。
     */
    fun finishRecording(draft: TeachSkillDraft?) {
        teachRecording = false
        teachStepCount = 0
        teachDraft = draft
    }

    /** 参数化面板带回来的草案（只换内容，不改「教学仍在进行」这件事）。 */
    fun replaceDraft(draft: TeachSkillDraft) {
        teachDraft = draft
    }

    /** 保存 / 放弃草案 ⇒ 教学真正结束，三态归零。 */
    fun endTeaching() {
        teachRecording = false
        teachStepCount = 0
        teachDraft = null
    }

    /**
     * 只给夹具用：模拟**进程被杀后重启**（新进程里 `object` 重新初始化 ⇒ 三态全零，
     * `BaopuBrowserWiring` 里的模型实例也随新进程重建）。生产代码不许调用；
     * 真机语义由「纯内存 + 无任何持久化 API」保证。
     */
    internal fun simulateNewProcess() {
        endTeaching()
        BaopuBrowserWiring.forgetUiTeachModel()
    }
}
