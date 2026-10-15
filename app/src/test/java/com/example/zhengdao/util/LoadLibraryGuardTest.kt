package com.example.zhengdao.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 第⑳刀（2026-10-15）：loadLibrary 白名单守卫（GPL 合规红线的技术化）。
 *
 * 背景：proot 四件套（Termux 官方 GPL-2.0+ 二进制）自第⑳刀起随 APK jniLibs 内置
 * （nativeLibraryDir）。合规口径（PROVENANCE.md :22 / :43 / :60-65）是**聚合分发、
 * 只 exec、绝不 dlopen/loadLibrary**——它们是被 execve 的独立 ELF 程序，一旦被
 * 某处顺手 `System.loadLibrary("proot")` 当库加载，"聚合分发不传染"的定性就被
 * 自家代码亲手推翻（本项目 2026-10-11 起为专有许可，后果比 GPL 时代严重）。
 * 口头红线靠不住（交接单 §七 / PROVENANCE :43 都写过），所以立成自动化门禁。
 *
 * 白名单（全仓合法的 loadLibrary 目标，新增必须同步本测试与 PROVENANCE.md）：
 * - `zhengdao_core` —— 自研 Rust 核心（独立开发，与 GPL 无关）
 * - `termux` —— vendored `com/termux/terminal/JNI.java:9` 加载的 terminal-emulator
 *   native 半边（构建期由 cpp/CMakeLists.txt 从 termux.c 编译，Apache-2.0，
 *   §4(b) 修改通知与 PROVENANCE 登记已是项目纪律）
 *
 * 扫描口径：
 * - 范围＝`app/src/main/java` 全量（kt + java，含 vendored com/termux——红线对
 *   vendored 代码同样生效：上游本来就 loadLibrary 自己的 termux，属白名单内）；
 * - 只认**调用形态**（`loadLibrary("` / `System.load(`），注释/文案里提到
 *   "loadLibrary" 不算（OpenSourceLicensesActivity 的红线文案、EnvHealth 的 KDoc
 *   都只提名字不带括号，实测不误报）；
 * - `dlopen` 是 proot/loader 域的禁词，但纯文本扫描区分不了 FFI 调用与注释，
 *   维持"扫描调用形态"的同款口径：`Runtime.getRuntime().load(` 形态才算。
 */
class LoadLibraryGuardTest {

    /** 全仓允许的 loadLibrary 目标（必须与 PROVENANCE.md 的合规口径一致）。 */
    private val whitelist = setOf("zhengdao_core", "termux")

    private val loadLibraryCall = Regex("""loadLibrary\s*\(\s*"([^"]+)"""")
    private val systemLoadCall = Regex("""System\.load\s*\(""")
    private val runtimeLoadCall = Regex("""Runtime\.getRuntime\(\)\.load\s*\(""")

    private data class ScanResult(
        /** 白名单外的违规调用（键=文件相对路径）。空 = 绿灯（全仓只有白名单内调用）。 */
        val offenders: Map<String, List<String>>,
        /** 扫到的 loadLibrary 调用总数（**含白名单内**）——防呆自检用。 */
        val totalCalls: Int,
    )

    private fun scanSources(): ScanResult {
        val root = File("src/main/java")
        val out = mutableMapOf<String, List<String>>()
        var total = 0
        root.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .forEach { f ->
                val text = f.readText()
                total += loadLibraryCall.findAll(text).count()
                val hits = buildList {
                    addAll(loadLibraryCall.findAll(text).mapNotNull { m ->
                        m.groupValues[1].takeIf { it !in whitelist }
                    })
                    if (systemLoadCall.containsMatchIn(text) || runtimeLoadCall.containsMatchIn(text)) {
                        add("<System.load 直路径加载>")
                    }
                }
                if (hits.isNotEmpty()) out[f.path] = hits
            }
        return ScanResult(out, total)
    }

    @Test
    fun `门禁_loadLibrary 只允许白名单目标`() {
        val scan = scanSources()
        // 防呆自检：正常态下全仓至少有两处**白名单内**调用（CoreNative.kt 的
        // zhengdao_core、vendored JNI.java 的 termux）——连它们都没扫到说明守卫失效
        assertTrue(
            "扫描范围内连白名单内的 loadLibrary 都没扫到——守卫自己失效了（工作目录/路径变了？）",
            scan.totalCalls >= 2,
        )
        assertTrue(
            "发现白名单外的 loadLibrary（GPL 红线：proot 四件套只能 exec，" +
                "绝不 dlopen/loadLibrary——新增白名单项必须同步 PROVENANCE.md）：\n" +
                scan.offenders.entries.joinToString("\n") { (path, hits) ->
                    "  $path: ${hits.joinToString("、")}"
                },
            scan.offenders.isEmpty(),
        )
    }

    @Test
    fun `门禁_白名单口径自证`() {
        // 防呆：白名单若被人清空/改名，本测试自己要红，而不是悄悄放行一切
        assertEquals(setOf("zhengdao_core", "termux"), whitelist)
    }
}
