// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百四十九批：本批的三条**源级**纪律（结构上做不到，而不是"记得别做"）。
package com.example.zhengdao.baopu.authz

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskGrantSourceGateTest {

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.exists()) it else File("app/$rel") }

    private fun authzFiles(): List<File> {
        val dir = repoFile("src/main/java/com/example/zhengdao/baopu/authz")
        require(dir.isDirectory) { "authz 目录没找到：${dir.absolutePath}" }
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".kt") }!!.sortedBy { it.name }
    }

    @Test
    fun 授权层不带IO() {
        // 快照/运行时/门禁/勘验/授权段：一个字节的读写都没有（只有 Ledger 那个文件该有 File）。
        val noIo = listOf(
            "TaskGrant.kt",
            "TaskGrantRuntime.kt",
            "TaskGrantGate.kt",
            "TaskGrantInspect.kt",
            "TaskGrantSection.kt",
        )
        val byName = authzFiles().associateBy { it.name }
        noIo.forEach { name ->
            val f = requireNotNull(byName[name]) { "缺文件 $name" }
            val src = f.readText()
            listOf("java.io.File", "SharedPreferences", "readText", "writeText", "appendText", "java.nio.file")
                .forEach { bad ->
                    assertFalse("$name 里出现了 $bad：授权判定层不许自己做 IO", src.contains(bad))
                }
        }
    }

    @Test
    fun 运行时不住任何持久化面() {
        val src = requireNotNull(authzFiles().firstOrNull { it.name == "TaskGrantRuntime.kt" }).readText()
        listOf("File(", "Context", "SharedPreferences", "DataStore", "Room", "sqlite").forEach { bad ->
            assertFalse("TaskGrantRuntime.kt 里出现了 $bad：运行状态只许在内存（§4）", src.contains(bad))
        }
    }

    @Test
    fun 勘验不数卡() {
        val src = requireNotNull(authzFiles().firstOrNull { it.name == "TaskGrantInspect.kt" }).readText()
        listOf("cardCount", "卡片数", "弹卡次数", "免卡次数", "askCount", "confirmCount").forEach { bad ->
            assertFalse("勘验里出现了 $bad：判据必须是授权证据，不是数卡", src.contains(bad, ignoreCase = true))
        }
    }

    @Test
    fun 六步的稳定键就是这六个且顺序写死() {
        assertEquals(
            listOf("objects", "actions", "limits", "boundary", "shadow", "record"),
            GrantStep.entries.map { it.wire },
        )
        GrantStep.entries.forEach { assertEquals(it, GrantStep.ofWire(it.wire)) }
    }

    @Test
    fun 结局码是稳定键且互不重复() {
        val codes = listOf(
            TaskGrantGate.CODE_ALLOW, TaskGrantGate.CODE_NO_GRANT,
            TaskGrantGate.CODE_PROCESS_CHANGED, TaskGrantGate.CODE_FOREIGN_TASK,
            TaskGrantGate.CODE_EXPIRED, TaskGrantGate.CODE_WHOLE_WORKSPACE,
            TaskGrantGate.CODE_OBJECT_OUT, TaskGrantGate.CODE_SHARED_FILE,
            TaskGrantGate.CODE_STATE_CHANGED, TaskGrantGate.CODE_ACTION_BANNED,
            TaskGrantGate.CODE_ACTION_OUT, TaskGrantGate.CODE_ACTION_LIMIT,
            TaskGrantGate.CODE_FILE_TOO_BIG, TaskGrantGate.CODE_TOTAL_TOO_BIG,
            TaskGrantGate.CODE_HARD_DENY, TaskGrantGate.CODE_PROTECTED,
            TaskGrantGate.CODE_RISK_TOO_HIGH, TaskGrantGate.CODE_NO_DATA,
            TaskGrantGate.CODE_SHADOW_MISMATCH, TaskGrantGate.CODE_SHADOW_MISWIRED,
            TaskGrantGate.CODE_NO_OP_ID, TaskGrantGate.CODE_DUPLICATE_OP,
        )
        assertEquals("结局码不许重复", codes.size, codes.toSet().size)
    }

    @Test
    fun 字符串独立成文件且中英同键() {
        val zh = repoFile("src/main/res/values/strings_baopu_authz.xml")
        val en = repoFile("src/main/res/values-en/strings_baopu_authz.xml")
        assertTrue("缺中文串文件：${zh.absolutePath}", zh.isFile)
        assertTrue("缺英文串文件：${en.absolutePath}", en.isFile)
        val zhKeys = keysOf(zh)
        val enKeys = keysOf(en)
        assertTrue("本批至少要有十来个键", zhKeys.size >= 10)
        assertEquals("中英必须同键（漏一条就会显示成 key）", zhKeys, enKeys)
        assertTrue(
            "本批的键一律 baopu_authz_ 前缀",
            zhKeys.all { it.startsWith("baopu_authz_") },
        )
    }

    @Test
    fun 主字符串文件不许出现本批的键() {
        // §0.4：`strings.xml` 正被 251 改着，本批对它的改动必须是 0 字节。
        val main = repoFile("src/main/res/values/strings.xml").readText()
        val mainEn = repoFile("src/main/res/values-en/strings.xml").readText()
        keysOf(repoFile("src/main/res/values/strings_baopu_authz.xml")).forEach { key ->
            assertFalse("strings.xml 里出现了本批的键 $key（应当只在独立文件里）", main.contains("name=\"$key\""))
            assertFalse("values-en/strings.xml 里出现了本批的键 $key", mainEn.contains("name=\"$key\""))
        }
    }

    @Test
    fun 预览入口只在开发包() {
        // 用户 2026-10-28：只把这张卡接到真机上看一眼（最小可看版本），B 段接线另立一批。
        val debugManifest = repoFile("src/debug/AndroidManifest.xml")
        assertTrue("缺开发包清单：${debugManifest.absolutePath}", debugManifest.isFile)
        assertTrue(
            "开发包清单里要有预览入口（否则手机上点不到这张卡）",
            debugManifest.readText().contains("TaskGrantCardPreviewActivity"),
        )
        val mainSrc = repoFile("src/main/AndroidManifest.xml").readText()
        assertFalse(
            "main 清单里出现了预览 Activity：正式包不该带这扇门",
            mainSrc.contains("TaskGrantCardPreviewActivity"),
        )
        // 预览页只回显：不许装授权、不许落账——"看一眼"不等于接线。
        val activity = repoFile(
            "src/debug/java/com/example/zhengdao/baopu/authz/TaskGrantCardPreviewActivity.kt",
        )
        assertTrue("缺预览页：${activity.absolutePath}", activity.isFile)
        val src = activity.readText()
        listOf("TaskGrantRuntime.install", "DecisionLedger", "TaskGrantLedger", "TaskGrantGate").forEach { bad ->
            assertFalse("预览页里出现了 $bad：这是「看一眼」，不是接线", src.contains(bad))
        }
    }

    @Test
    fun 授权不自己另起一张卡() {
        // 用户 2026-10-28：「**不要新建第二张卡**。授权并入现有计划卡……一张卡上半『做什么/
        // 碰哪些对象』，下半『授权：对象清单☑ / 动作类型☑ / 上限』，按钮叫『开工』」。
        // 所以：授权层里一个卡容器都不许有（外形/边框/标题条归计划卡），
        // 而计划卡必须真的把这一段挂上去——否则「一张卡」只剩上半张。
        val files = authzFiles()
        assertTrue(
            "authz 目录里还有 *Card.kt：那是第二张卡",
            files.none { it.name.endsWith("Card.kt") },
        )
        files.forEach { f ->
            val src = f.readText()
            listOf("BaopuActionContainer", "Surface(").forEach { bad ->
                assertFalse("${f.name} 里出现了 $bad：授权段不许自带卡壳（第二张卡）", src.contains(bad))
            }
        }
        val planCard = repoFile("src/main/java/com/example/zhengdao/ui/baopu/BaopuPlanCard.kt")
        assertTrue("缺计划卡：${planCard.absolutePath}", planCard.isFile)
        assertTrue(
            "计划卡没把授权段挂上去：一张卡就没落地",
            planCard.readText().contains("BaopuPlanGrantSection("),
        )
        val preview = repoFile(
            "src/debug/java/com/example/zhengdao/baopu/authz/TaskGrantCardPreviewActivity.kt",
        )
        assertTrue(
            "预览页画的应当就是计划卡本身（画别的＝又把两张卡分开了）",
            preview.readText().contains("BaopuPlanCard("),
        )
    }

    private fun keysOf(f: File): Set<String> =
        Regex("""<string\s+name="([^"]+)"""")
            .findAll(f.readText())
            .map { it.groupValues[1] }
            .toSet()
}
