// 独立开发声明：本文件为本项目从零编写。
package com.example.zhengdao.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * §B 顺序 2 · **P2-5（删除无二次确认）** 的验收门禁。
 *
 * 三处破坏性操作原本在按钮的 `onClick` 里**直接执行**：
 * 1. `SettingsHelpActivity.kt` —— `RunLog.pruneArchivesKeep(ctx, keep = 3)`（永久删旧日志归档）
 * 2. `SettingsStorageActivity.kt` —— `RootfsCache.cleanupNonCurrent(ctx)`（永久删旧版本安装包缓存）
 * 3. `KeepaliveLogActivity.kt` —— `KeepaliveArchive.clear(ctx)`（永久清空保活留档）
 *
 * 改法照抄项目现成的确认框口径（`SettingsStorageActivity.kt` 里那段 `optConfirm`）：
 * 按钮只把 `…Confirm` 置 true，破坏性动作原封不动搬进 `AlertDialog` 的 `confirmButton`。
 *
 * 为什么用文本门禁而不是 Compose UI 测试：项目当前 Compose UI 测试为 0（P4-5），
 * 给第一个 UI 测试加依赖、定规则不该塞进本刀（与 `StateLifetimeGateTest` 同一判断）。
 * 门禁证明的是"没人把二次确认退回去"，**不是**"弹窗点下去一定跑对"——后者归真机复验。
 *
 * 文字写法同目录 `StateLifetimeGateTest.kt`：读源码文本做断言。
 */
class SettingsDestructiveConfirmGateTest {

    /** 与 `StateLifetimeGateTest.src` 同一套路径定位：Gradle 的测试工作目录有时是仓库根，有时是 `app/`。 */
    private fun src(rel: String): String {
        val f = File(rel).let { if (it.exists()) it else File("app/$rel") }
        assertTrue("找不到 $rel：${f.path}", f.isFile)
        return f.readText()
    }

    private fun keyOf(name: String) = "name=\"$name\">"

    /**
     * 门禁 1：三处破坏性调用必须**落在确认框内**，不能还留在按钮 onClick 里。
     *
     * 判定方式（可稳定判定，不靠模糊印象）：从调用点往**回**数到最近的 `AlertDialog(`，
     * 再从调用点往**后**数到最近的 `dismissButton`；两段文本里必须各含该文件**唯一**的一个
     * `…Confirm = false`。也就是说：调用点被夹在「这个弹窗的开启标记」与「取消按钮」之间 ——
     * 只可能坐在 `confirmButton` 的 `onClick` 里（`dismissButton` 只负责关框，不执行动作）。
     *
     * 断言前先自检「`…Confirm = false` 在该文件里恰好出现 3 次」—— 本项目确认框口径固定为三处：
     * `onDismissRequest` 一处、`confirmButton` 的 `onClick` 开头一处、`dismissButton` 一处。
     * 数量一变（有人又挂了第二个对话框、或把某处关框语句删了），"最近的那个 AlertDialog("这条
     * 定位前提就失效，门禁会变假阳性 —— 所以当场炸出来。
     */
    private fun assertInsideDestructiveConfirmDialog(
        file: String,
        destructiveCall: String,
        confirmOffLiteral: String,
    ) {
        val s = src(file)

        val occurrences = Regex(Regex.escape(confirmOffLiteral)).findAll(s).count()
        assertEquals(
            "$file：`$confirmOffLiteral` 应恰好出现 3 次（onDismissRequest、confirmButton 的 onClick 开头、" +
                "dismissButton 各一处）；实际 $occurrences 次 —— 数量变了说明确认框结构被改写，本门禁的定位前提失效",
            3,
            occurrences,
        )

        val callIdx = s.indexOf(destructiveCall)
        assertTrue("$file 里找不到破坏性调用 `$destructiveCall`", callIdx >= 0)

        // 反向找：调用点之前最近的 AlertDialog( —— 只能是包裹它的那个确认框；
        // 再反向找最近的 confirmButton —— 调用必须落在它的分支里（而不是 dismissButton 分支）。
        val dialogIdx = s.lastIndexOf("AlertDialog(", callIdx)
        assertTrue("$file：`$destructiveCall` 之前必须有一个 AlertDialog(（现在的写法是确认后才执行）", dialogIdx >= 0)
        val confirmBranchIdx = s.lastIndexOf("confirmButton", callIdx)
        assertTrue("$file：`$destructiveCall` 之前必须有 confirmButton（否则说明它还在别的分支里）", confirmBranchIdx > dialogIdx)

        // 正向找：调用点之后最近的 dismissButton（取消按钮所在行）。
        val dismissIdx = s.indexOf("dismissButton", callIdx)
        assertTrue("$file：`$destructiveCall` 之后必须有 dismissButton（取消按钮）", dismissIdx > callIdx)

        val openingWindow = s.substring(dialogIdx, callIdx)
        assertTrue(
            "$file：`$destructiveCall` 之前的最近一个 AlertDialog( 与调用点之间应能看到 $confirmOffLiteral —— " +
                "说明调用落在该确认框体内（onDismissRequest 紧随 AlertDialog( 出现）",
            openingWindow.contains(confirmOffLiteral),
        )
        assertTrue(
            "$file：`$destructiveCall` 之前的最近一个 confirmButton 应早于它，之后的最近一个 dismissButton 应晚于它 —— " +
                "即调用被夹在 confirmButton 与 dismissButton 之间，只可能坐在 confirmButton 的 onClick 里",
            confirmBranchIdx < callIdx && callIdx < dismissIdx,
        )
    }

    @Test
    fun `P2-5 帮助页_剪旧日志归档必须二次确认`() {
        assertInsideDestructiveConfirmDialog(
            file = "src/main/java/com/example/zhengdao/ui/SettingsHelpActivity.kt",
            destructiveCall = "pruneArchivesKeep(",
            confirmOffLiteral = "pruneConfirm = false",
        )
    }

    @Test
    fun `P2-5 存储页_清理旧版本安装包必须二次确认`() {
        assertInsideDestructiveConfirmDialog(
            file = "src/main/java/com/example/zhengdao/ui/SettingsStorageActivity.kt",
            destructiveCall = "cleanupNonCurrent(",
            confirmOffLiteral = "installerCleanConfirm = false",
        )
    }

    @Test
    fun `P2-5 保活页_清空留档必须二次确认`() {
        assertInsideDestructiveConfirmDialog(
            file = "src/main/java/com/example/zhengdao/ui/KeepaliveLogActivity.kt",
            destructiveCall = "KeepaliveArchive.clear(",
            confirmOffLiteral = "clearConfirm = false",
        )
    }

    /**
     * 门禁 2：每个文件里 `…Confirm = true`（弹框）与破坏性调用**不在同一个 onClick 字面块里**。
     *
     * 判定方式：取出从 `…Confirm = true` 到最近的 `}) {` 这一小段 —— 那就是「触发按钮的
     * onClick lambda 一直到它的收尾」；破坏性调用必须**不在**这一段里。换句话说，
     * 点按钮那一下只允许把确认框立起来，动删除的代码必须留在后面的 confirmButton 里。
     */
    private fun assertTriggerButtonOnlyOpensDialog(
        file: String,
        confirmOnLiteral: String,
        destructiveCall: String,
    ) {
        val s = src(file)
        val triggerIdx = s.indexOf(confirmOnLiteral)
        assertTrue("$file 里找不到 `$confirmOnLiteral`", triggerIdx >= 0)

        // 触发按钮的 lambda 收尾：本项目按钮一律 `\n            }) {`/`}) { Text(` 写法。
        val lambdaEndIdx = s.indexOf("\n", s.indexOf("}) {", triggerIdx))
        assertTrue("$file：`$confirmOnLiteral` 之后应能定位到按钮 onClick 的收尾 `}) {`", lambdaEndIdx > triggerIdx)

        val triggerLambda = s.substring(triggerIdx, lambdaEndIdx)
        assertFalse(
            "$file：按钮 onClick 只该把确认框立起来，不该直接执行 `$destructiveCall`；" +
                "实际它落在 `$confirmOnLiteral` 之后那个 onClick 块里 —— P2-5 被退回",
            triggerLambda.contains(destructiveCall),
        )
        assertFalse(
            "$file：`$confirmOnLiteral` 之后到按钮收尾之间不该出现 AlertDialog（弹框应另起一块，不能塞进 onClick）",
            triggerLambda.contains("AlertDialog("),
        )
    }

    @Test
    fun `P2-5 三个触发按钮都只负责弹框`() {
        assertTriggerButtonOnlyOpensDialog(
            file = "src/main/java/com/example/zhengdao/ui/SettingsHelpActivity.kt",
            confirmOnLiteral = "pruneConfirm = true",
            destructiveCall = "pruneArchivesKeep(",
        )
        assertTriggerButtonOnlyOpensDialog(
            file = "src/main/java/com/example/zhengdao/ui/SettingsStorageActivity.kt",
            confirmOnLiteral = "installerCleanConfirm = true",
            destructiveCall = "cleanupNonCurrent(",
        )
        assertTriggerButtonOnlyOpensDialog(
            file = "src/main/java/com/example/zhengdao/ui/KeepaliveLogActivity.kt",
            confirmOnLiteral = "clearConfirm = true",
            destructiveCall = "KeepaliveArchive.clear(",
        )
    }

    /**
     * 门禁 3：zh/en 两个 strings 文件的 key 数量相等、且互为子集（顺带等于全等）。
     *
     * 用 `java.io.File` 直接读 `app/src/main/res/...`（同 `StateLifetimeGateTest` 的定位写法），
     * 不依赖 Android 资源编译产物 —— 门禁要在纯 JVM 单测里成立。
     */
    @Test
    fun `P2-5 中英文文案键必须一一对称`() {
        val zh = File("app/src/main/res/values/strings.xml").let { if (it.isFile) it else File("src/main/res/values/strings.xml") }
        val en = File("app/src/main/res/values-en/strings.xml").let { if (it.isFile) it else File("src/main/res/values-en/strings.xml") }
        assertTrue("找不到中文文案：${zh.path}", zh.isFile)
        assertTrue("找不到英文文案：${en.path}", en.isFile)

        val re = Regex("name=\"([^\"]+)\"")
        val zhKeys = re.findAll(zh.readText()).map { it.groupValues[1] }.toList()
        val enKeys = re.findAll(en.readText()).map { it.groupValues[1] }.toList()

        assertEquals(
            "中英文文案条数必须相等（新增文案要两个文件同时加）；zh=${zhKeys.size} en=${enKeys.size}",
            zhKeys.size,
            enKeys.size,
        )

        val zhMissingInEn = zhKeys.toSet() - enKeys.toSet()
        val enMissingInZh = enKeys.toSet() - zhKeys.toSet()
        assertTrue("这些 key 只有中文有：$zhMissingInEn", zhMissingInEn.isEmpty())
        assertTrue("这些 key 只有英文有：$enMissingInZh", enMissingInZh.isEmpty())
    }

    /**
     * 门禁 4：本刀（P2-5）新增的九条文案 key —— 三处确认框各一条 title、一条 desc、一条 warn ——
     * 在 zh/en 两边都存在，且都不是空串。
     */
    @Test
    fun `P2-5 新增确认框文案在两边都存在`() {
        val zh = File("app/src/main/res/values/strings.xml").let { if (it.isFile) it else File("src/main/res/values/strings.xml") }
        val en = File("app/src/main/res/values-en/strings.xml").let { if (it.isFile) it else File("src/main/res/values-en/strings.xml") }
        val zhText = zh.readText()
        val enText = en.readText()

        val keys = listOf(
            "help_log_prune_title", "help_log_prune_desc", "help_log_prune_warn",
            "storage_installer_clean_title", "storage_installer_clean_desc", "storage_installer_clean_warn",
            "keepalive_log_clear_title", "keepalive_log_clear_desc", "keepalive_log_clear_warn",
        )
        for (k in keys) {
            val marker = keyOf(k)
            assertTrue("中文文案缺 `$k`（确认框文案必须两个文件同时加）", zhText.contains(marker))
            assertTrue("英文文案缺 `$k`（确认框文案必须两个文件同时加）", enText.contains(marker))
            assertTrue("中文 `$k` 是空串", !zhText.contains(marker + "</string>"))
            assertTrue("英文 `$k` 是空串", !enText.contains(marker + "</string>"))
        }
    }
}
