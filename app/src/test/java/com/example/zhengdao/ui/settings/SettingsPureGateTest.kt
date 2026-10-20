// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单《第七十七批 · 结构拆分第六阶段：Settings 存储/数据页纯逻辑抽取》§2
// 「源码门禁：页面里对应逻辑只剩调用，不得留两份实现」。
//
// 这一套钉的是**结构**，不是行为：行为口径在 StorageRulesTest / DataRulesTest 里。
package com.example.zhengdao.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 第七十七批源码门禁。
 *
 * 三件事各一组：
 * 1. 两页里九条判据**只剩调用**，内联实现（映射表 / 过滤 / 求和 / 前缀匹配）一个字不剩；
 * 2. helper 侧**每条判据恰好一份**（页面不得再定义同名判定）；
 * 3. helper **纯度**：剥掉注释与字符串后，代码骨架里不许出现 Android / IO / 线程。
 *
 * 门禁是「抽取不完整」与「有人加回第二份实现」的唯一拦路石——派工单 §2 要求。
 */
class SettingsPureGateTest {

    /** Gradle 单测工作目录是模块目录 `app/`（与 ProotLaunchRulesTest 同一约定）。 */
    private fun appFile(rel: String): File =
        File(rel).let { if (it.exists()) it else File("app/$rel") }

    private fun src(rel: String): String =
        appFile(rel)
            .also { assertTrue("找不到 $rel：${it.path}", it.isFile) }
            .readText()

    private fun storagePage() = src("src/main/java/com/example/zhengdao/ui/SettingsStorageActivity.kt")
    private fun dataPage() = src("src/main/java/com/example/zhengdao/ui/SettingsDataActivity.kt")
    private fun storageRules() = src("src/main/java/com/example/zhengdao/ui/settings/StorageRules.kt")
    private fun dataRules() = src("src/main/java/com/example/zhengdao/ui/settings/DataRules.kt")

    /** 出现次数（不重叠计数）。 */
    private fun count(hay: String, needle: String): Int = hay.split(needle).size - 1

    /**
     * 剥掉 Kotlin 行注释 / 块注释 / 字符串内容，只留代码骨架。
     * 纯度门禁要用它——helper 的 KDoc 里光明正大写着「不碰 Context」，直接扫全文会误判。
     */
    private fun codeOnly(src: String): String {
        val sb = StringBuilder()
        var i = 0
        var inStr = false
        while (i < src.length) {
            val c = src[i]
            val n = if (i + 1 < src.length) src[i + 1] else ' '
            if (inStr) {
                if (c == '\\') { i += 2; continue }
                if (c == '"') inStr = false
                i++
                continue
            }
            if (c == '"') { inStr = true; i++; continue }
            if (c == '/' && n == '/') {
                while (i < src.length && src[i] != '\n') i++
                continue
            }
            if (c == '/' && n == '*') {
                i += 2
                while (i + 1 < src.length && !(src[i] == '*' && src[i + 1] == '/')) i++
                i += 2
                continue
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    // ── 一、存储页：四条判据只剩调用 ─────────────────────────────────────

    @Test
    fun `存储页_四条判据只剩调用`() {
        val page = storagePage()
        for (call in listOf(
            "StorageRules.groupLabel(",
            "StorageRules.optionalToolsMb(",
            "StorageRules.hasScriptCache(",
            "StorageRules.agentCacheMb(",
        )) {
            assertEquals("存储页对 $call 的调用应恰好一处", 1, count(page, call))
        }
    }

    @Test
    fun `存储页_内联实现一个字不剩`() {
        val page = storagePage()
        for (lit in listOf(
            // ① 分组名 → 白话映射表（原 when 三路）
            "装 hermes 工具时下载的文件",
            "hermes 自检记录",
            "安装时下载的脚本",
            "when (item.group)",
            // ②③ 按组名过滤（数据契约只许留在 helper 的常量里）
            "item.group ==",
            // ④ 逐目录求和
            ".sumOf { it.second }",
        )) {
            assertFalse("存储页还留着「$lit」——抽取不完整或有人加回了第二份实现", page.contains(lit))
        }
    }

    // ── 二、数据页：五条判据只剩调用 ─────────────────────────────────────

    @Test
    fun `数据页_五条判据只剩调用`() {
        val page = dataPage()
        for (call in listOf(
            "DataRules.shouldConfirmWorkspaceChange(",
            "DataRules.isSharedStoragePath(",
            "DataRules.shouldShowKbPath(",
            "DataRules.shouldOfferOtherKbCopy(",
            "DataRules.capReached(",
        )) {
            assertEquals("数据页对 $call 的调用应恰好一处", 1, count(page, call))
        }
    }

    @Test
    fun `数据页_内联实现一个字不剩`() {
        val page = dataPage()
        for (lit in listOf(
            // ⑤ 迁移前置条件
            "(kbStatus?.files ?: 0) > 0",
            // ⑨ 工作区路径前缀匹配
            "startsWith(\"/storage/emulated/0\")",
            // ⑧ 扫描上限
            "MAX_SCAN",
            // ⑦ 路径行判据（状态文案分支里的 `State.DISABLED ->` 是视觉，不算）
            "!= com.example.zhengdao.terminal.KnowledgeBase.State.DISABLED",
            // ⑥ 别处资料库提示
            "otherKb.isNotEmpty()",
        )) {
            assertFalse("数据页还留着「$lit」——抽取不完整或有人加回了第二份实现", page.contains(lit))
        }
    }

    // ── 三、helper 侧：每条判据恰好一份，且不反向依赖页面 ────────────────

    @Test
    fun `helper_每条判据恰好一份_页面不得再定义同名判定`() {
        val storage = storageRules()
        val data = dataRules()
        for (fn in listOf("groupLabel", "optionalToolsMb", "hasScriptCache", "agentCacheMb")) {
            assertEquals("StorageRules.$fn 应恰好定义一次", 1, count(storage, "fun $fn("))
            assertFalse("存储页不得再定义 $fn", storagePage().contains("fun $fn("))
        }
        for (fn in listOf(
            "shouldConfirmWorkspaceChange",
            "isSharedStoragePath",
            "shouldShowKbPath",
            "shouldOfferOtherKbCopy",
            "capReached",
        )) {
            assertEquals("DataRules.$fn 应恰好定义一次", 1, count(data, "fun $fn("))
            assertFalse("数据页不得再定义 $fn", dataPage().contains("fun $fn("))
        }
        // 方向单一：helper 不许反过来引用页面 / Activity。
        // 只在**代码骨架**上判——helper 的出处注释里本来就写着「原 SettingsStorageActivity.kt」，
        // 那是来历说明，不是依赖。
        val storageCode = codeOnly(storage)
        val dataCode = codeOnly(data)
        for (bad in listOf("SettingsStorageActivity", "SettingsDataActivity", "Activity", "Composable")) {
            assertFalse("StorageRules 反向依赖了 $bad", storageCode.contains(bad))
            assertFalse("DataRules 反向依赖了 $bad", dataCode.contains(bad))
        }
    }

    @Test
    fun `helper_纯度_代码骨架里零Android零IO零线程`() {
        for ((name, body) in listOf(
            "StorageRules" to codeOnly(storageRules()),
            "DataRules" to codeOnly(dataRules()),
        )) {
            for (bad in listOf(
                "android.", "androidx.", "Context", "java.io", "File(", "File ",
                "Toast", "Thread", "Dispatchers", "suspend", "@Composable", "stringResource",
            )) {
                assertFalse("$name 的代码里出现了「$bad」——纯判据层不许碰 Android/IO/线程", body.contains(bad))
            }
        }
    }
}

// 负例口径（交付报告里逐条实跑）：
// ① 把 `StorageRules.groupLabel(item.group)` 换回内联 `when` ⇒ 「存储页_内联实现一个字不剩」红；
// ② 把 `DataRules.isSharedStoragePath(path)` 换回 `path.startsWith("/storage/emulated/0")`
//    ⇒ 「数据页_内联实现一个字不剩」红；
// ③ 在 StorageRules 里 import android.content.Context ⇒ 「helper_纯度」红。
