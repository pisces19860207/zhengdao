// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百九十二批 · 闸①：**宰相边界源级断言**（2026-10-31 蓝总管落地）
//
// 为什么要有这道闸（原话来自 292 派工单）：
//   宰相（左相）应该是「只读意见、只吐决定」的纯判定器。一旦它开始持有
//   网络客户端、数据库句柄、完整对话历史，它就有了"自己去看、自己去查、自己去说"的能力——
//   那时候"宰相不越权"就只能靠自觉，而不是靠结构。
//
// 这道闸扫的是**源码文本**（不跑生产逻辑），命中即红：
//   ① 网络/模型客户端：okhttp / openai / java.net / HttpURLConnection / WebView
//   ② 存储与上下文：android.content.Context / android.database / BaopuDb / Room
//   ③ 完整对话历史：messages / history 一类的全量入口（**只禁"全量"，不禁 Opinion 这种结构化输入**）
//
// 与 293 的关系：293 会把宰相抽成 `:policy` 模块，用 **Gradle 依赖白名单**实现更强的隔离；
// 本闸是**过渡期的廉价护栏**（半天成本、立刻生效），293 落地后仍然保留（双保险）。
//
// 加白名单的门槛（给后来的人）：只有"新功能确实需要、且不破坏纯判定"才可加，
// 每加一条必须写清批号与理由——**不许为了让红变绿就把名字塞进 ALLOWED**。
package com.example.zhengdao.baopu

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class MediatorPurityGateTest {

    /** 宰相本体（纯判定）：这些文件里**禁止**出现 IO/上下文/网络 */
    private val mediatorFiles = listOf(
        "src/main/java/com/example/zhengdao/baopu/HarmonyMediator.kt",
        "src/main/java/com/example/zhengdao/baopu/MediatorWiring.kt",
        "src/main/java/com/example/zhengdao/baopu/agent/LearnWiring.kt",
    )

    /** 允许的例外（文件 → 允许的 import 前缀）：每条必须带批号与理由 */
    private val allowed = mapOf(
        // 2026-10-31 · 292：装配层需要日志（RunLog 是纯 IO-free 的落盘口，不算"看上下文"）
        "src/main/java/com/example/zhengdao/baopu/MediatorWiring.kt" to listOf(
            "com.example.zhengdao.core.RunLog",
        ),
        // 2026-10-31 · 283：学习器接线需要决策账本与裁决数据（它负责"记"，不负责"看"）
        "src/main/java/com/example/zhengdao/baopu/agent/LearnWiring.kt" to listOf(
            "com.example.zhengdao.baopu.Decision",
            "com.example.zhengdao.baopu.inspect.LawRef",
            "com.example.zhengdao.baopu.inspect.Opinion",
            "com.example.zhengdao.baopu.inspect.Severity",
            "com.example.zhengdao.baopu.policy.VerdictExperienceRuntime",
            "com.example.zhengdao.core.RunLog",
        ),
    )

    /** 禁止出现的符号（子串匹配） */
    private val forbidden = listOf(
        "okhttp3",
        "com.openai",
        "java.net.",
        "HttpURLConnection",
        "android.webkit.WebView",
        "android.content.Context",
        "android.database",
        "com.example.zhengdao.baopu.BaopuDb",
        "androidx.room",
        "getSystemService",
        // 完整对话历史（只禁"全量喂给宰相"，不禁 Opinion 这种结构化意见）
        "allMessages",
        "fullHistory",
        "conversationHistory",
    )

    @Test
    fun `宰相本体不得持有网络_存储_上下文_完整历史`() {
        val violations = mutableListOf<String>()
        for (path in mediatorFiles) {
            val file = File(path)
            assertTrue("找不到宰相源文件：$path（路径变了就更新本闸，别让它静默跳过）", file.isFile)
            val text = file.readText()
            val allow = allowed[path].orEmpty()

            // 查 import 行：命中禁止符号且不在允许名单里 ⇒ 违约
            text.lineSequence()
                .filter { it.trimStart().startsWith("import ") }
                .forEach { line ->
                    val sym = line.trim().removePrefix("import ").trim()
                    val ok = allow.any { sym.startsWith(it) }
                    if (!ok && forbidden.any { sym.contains(it) }) {
                        violations += "$path 引入了禁止依赖：$sym"
                    }
                }

            // 再查正文里的 API 调用痕迹（防止用全限定名绕过 import 检查）
            forbidden.filterNot { it.startsWith("com.example") }.forEach { token ->
                if (text.contains(token)) {
                    val okAnywhere = allow.any { text.contains("import " + it) && token.isEmpty() }
                    if (!okAnywhere) violations += "$path 正文出现禁止符号：$token"
                }
            }
        }
        assertTrue(
            "宰相边界被打破（应只读意见、只吐决定）：\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    @Test
    fun `闸自己不能静默跳过`() {
        // 三个文件必须都在：删文件也算"绕闸"，必须红
        mediatorFiles.forEach {
            assertTrue("宰相源文件缺失：$it", File(it).isFile)
        }
    }
}
