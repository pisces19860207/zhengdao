// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百八十批 · 「画像黑箱」的源码级门禁（判据②「画像不进上下文」——行为用例钉不住
// "谁将来会引用它"，只能对着文件钉，房型照 AutonomyWiringGateTest）：
//   1. 全仓扫描：画像符号只许出现在 profile 包自身与装配点 BaopuRuntime——
//      上下文拼装（ContextAssembler 等）与一切 UI 文件引用即红；
//   2. 黑箱对外面积钉死：UserProfileBlackbox 上不许出现返回明细的读口；
//   3. profile 包零 Android 依赖（纯数据层，JVM 直测）；
//   4. 装配落点钉在 filesDir/baopu_internal（不在抱朴记忆/工作区等可读目录）。
package com.example.zhengdao.baopu.profile

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileInvisibilityTest {

    private fun file(rel: String): File {
        val direct = File(rel)
        return if (direct.isFile) direct else File("app/$rel")
    }

    private fun dir(rel: String): File = File(rel).takeIf { it.isDirectory } ?: File("app/$rel")

    /** 去掉整行注释（纪律写在注释里，门禁不能拿注释当证据）。 */
    private fun code(rel: String): String = file(rel).readText(Charsets.UTF_8).lines()
        .filterNot { l ->
            val t = l.trimStart()
            t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
        }.joinToString("\n")

    private val mainRoot = dir("src/main/java/com/example/zhengdao")

    /** 画像符号——出现在白名单之外的任何文件都算泄漏。 */
    private val profileSymbols = listOf(
        "UserProfileBlackbox", "TwoLayerProfile", "ColdStartThrottle",
        "FeedVerdict", "FeedDeny", "VerdictExperienceStore", "baopu.profile",
    )

    // ── 判据②：画像不进上下文（结构上就不可能） ────────────────────────────

    @Test
    fun `画像符号只在profile包与装配点出现`() {
        val offenders = mainRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { f ->
                val path = f.absolutePath.replace('\\', '/')
                val whitelisted = path.contains("/baopu/profile/") || f.name == "BaopuRuntime.kt"
                !whitelisted && profileSymbols.any { f.readText(Charsets.UTF_8).contains(it) }
            }
            .map { it.absolutePath }
            .toList()
        assertTrue("画像符号泄漏到：$offenders", offenders.isEmpty())
    }

    @Test
    fun `上下文拼装与界面点名不引用画像`() {
        // 注意别误伤别家的画像：TasteProfile（202 口味档案）／PatienceProfile（238 耐心画像）
        // 是别的批次的既有件，这里只禁**本批**画像包的符号与"画像"二字入代码。
        listOf(
            "src/main/java/com/example/zhengdao/baopu/ContextAssembler.kt",
            "src/main/java/com/example/zhengdao/baopu/MemoryInjectionBudget.kt",
            "src/main/java/com/example/zhengdao/baopu/MemoryLayout.kt",
            "src/main/java/com/example/zhengdao/ui/baopu/BaopuChatController.kt",
            "src/main/java/com/example/zhengdao/ui/baopu/BaopuSettingsScreen.kt",
        ).forEach { rel ->
            val body = code(rel)
            listOf("UserProfileBlackbox", "TwoLayerProfile", "ColdStartThrottle", "baopu.profile")
                .forEach { s -> assertFalse("$rel 引用了画像符号 $s", body.contains(s)) }
            assertFalse("$rel 写了画像二字", body.contains("画像"))
        }
    }

    // ── 黑箱对外面积 ────────────────────────────────────────────────────────

    @Test
    fun `黑箱无读明细的口_返回类型钉死`() {
        val bannedTypes = listOf("ShortTermEntry", "StableBucket", "TwoLayerProfile", "ColdStartThrottle")
        UserProfileBlackbox::class.java.declaredMethods.forEach { m ->
            val sig = m.toString()
            bannedTypes.forEach { b ->
                assertFalse("黑箱读口泄漏了明细类型 $b：$sig", sig.contains(b))
            }
        }
    }

    @Test
    fun `黑箱函数面闭集_加读口必须改这里`() {
        val allowed = setOf(
            "install", "installed", "feed", "recompute", "clearShortTerm",
            "distribution", "isStableKind", "conclusion", "retuneTtl", "resetForTest",
            "save", "getInstance", // save 是私有落盘；getInstance 是 object 的编译产物
        )
        val names = UserProfileBlackbox::class.java.declaredMethods
            .map { it.name }
            .filter { !it.contains("$") } // access$…／feed$default／$jacocoInit 都是编译产物
            .toSet()
        assertEquals(
            "UserProfileBlackbox 出现了白名单之外的方法：${names - allowed}",
            emptySet<String>(),
            names - allowed,
        )
    }

    // ── 纯数据层：零 Android 依赖 ───────────────────────────────────────────

    @Test
    fun `profile包零Android依赖`() {
        val profileDir = dir("src/main/java/com/example/zhengdao/baopu/profile")
        profileDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
            f.readLines(Charsets.UTF_8)
                .filter { l -> val t = l.trimStart(); !t.startsWith("//") && !t.startsWith("*") }
                .forEachIndexed { i, l ->
                    assertFalse("${f.name}:${i + 1} 引了 Android", l.contains("import android"))
                }
        }
    }

    // ── 装配落点：画像在私有目录，判决经验在工作区 ──────────────────────────

    @Test
    fun `装配落点_画像私有_判决经验进工作区`() {
        val runtime = code("src/main/java/com/example/zhengdao/baopu/BaopuRuntime.kt")
        assertTrue(
            "画像装配必须落在 filesDir/baopu_internal（私有不可读目录）",
            runtime.contains("UserProfileBlackbox.install(File(appCtx.filesDir, UserProfileBlackbox.INTERNAL_DIR))"),
        )
        runtime.lines().filter { it.contains("UserProfileBlackbox.install") }.forEach { line ->
            assertFalse("画像不许装进抱朴记忆", line.contains("DIR_GLOBAL_MEMORY"))
            assertFalse("画像不许装进工作区", line.contains("workspaceRoot"))
        }
        assertTrue(
            "判决经验必须落在工作区（Download/证道 的地盘）",
            runtime.contains("VerdictExperienceStore.install(workspaceRoot(appCtx)"),
        )
    }
}
