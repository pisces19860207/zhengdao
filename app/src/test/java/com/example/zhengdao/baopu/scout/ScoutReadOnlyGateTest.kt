// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百九十批 · 斥候的**源码门禁**（文本层钉边界，不靠自觉）。
//
// 每条硬约束都有**两个**断言：真源码零违规 ＋ 把违规写法喂进同一把尺必须认出来。
// 只有前者的话，"绿"可能只是因为尺子量不出脏东西。
//
// 负例编号照派工单「负例（各红）」的顺序：
//   1 探测打扰前台 / 2 清单超 10 行 / 3 把"不可用"说成"可以做" / 4 重复问同一条权限 / 5 探测读用户内容。
// 这里管 1、4、5（文本层量得出的那三条）；2、3 是行为层的，落在 `ScoutSheetTest`、`ScoutRulesTest`。
package com.example.zhengdao.baopu.scout

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoutReadOnlyGateTest {

    private val dir = File("src/main/java/com/example/zhengdao/baopu/scout")

    /** 纯判据：不碰 Android、不碰 IO、不读时钟（JVM 直测的前提）。 */
    private val pure = setOf("ScoutVocab.kt", "ScoutRules.kt", "ScoutSheet.kt", "ScoutOpinions.kt", "Unfinished.kt")

    /** 唯一允许碰文件的：两本账的壳＋扇出＋路径解析＋Android 读数层（后者只用 File 查存在性）。 */
    private val ioOrAndroid = setOf(
        "ScoutLedger.kt", "UnfinishedLedger.kt", "LedgerFanout.kt", "ScoutDirs.kt", "AndroidScoutProbes.kt",
    )

    /** 用户内容：能力探测一律不许碰（`primaryClip` 单独列一条＝Kotlin 属性写法也要认出来）。 */
    private val contentBanned = listOf(
        "getPrimaryClip", "primaryClip", "ClipboardManager", "MediaStore", "ContactsContract",
        "TelephonyManager", "openInputStream", "ContentResolver", "listFiles(",
    )

    /** 界面／打扰前台。 */
    private val uiBanned = listOf(
        "AlertDialog", "Toast.makeText", "setContent", "startActivity", "NotificationCompat.Builder",
    )

    /** 自己申请权限（首启必须静默探）。 */
    private val permissionBanned = listOf(
        "requestPermissions", "ActivityResultContracts", "startActivityForResult",
    )

    private fun sources(): Map<String, String> {
        val files = dir.listFiles { f -> f.isFile && f.extension == "kt" }.orEmpty()
        assertTrue("scout sources missing at ${dir.absolutePath}", files.size >= 11)
        return files.associate { it.name to code(it.readText()) }
    }

    private fun code(text: String): String = text
        .replace(Regex("""/\*[\s\S]*?\*/"""), " ")
        .replace(Regex("(?m)//.*$"), " ")

    private fun violations(text: String, banned: List<String>): List<String> = banned.filter { text.contains(it) }

    private fun everything(): String = sources().values.joinToString("\n")

    @Test fun `scout never reads user content`() {
        assertEquals(emptyList<String>(), violations(everything(), contentBanned))
    }

    @Test fun `negative five the content guard bites on offending code`() {
        // 负例⑤「探测读用户内容（越界）」：三行典型越界写法喂进**同一把尺**，必须全认出来；
        // 而合法的"只问有没有摄像头"不许被误伤（尺子松紧都要验）。
        val offending = "val cm = ctx.getSystemService(CLIPBOARD_SERVICE) as ClipboardManager\n" +
            "val clip = cm.primaryClip\n" +
            "val rows = MediaStore.Images.Media.EXTERNAL_CONTENT_URI"
        assertEquals(3, violations(offending, contentBanned).size)
        assertTrue(violations("hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)", contentBanned).isEmpty())
    }

    @Test fun `scout never shows a dialog or starts an activity`() {
        assertEquals(emptyList<String>(), violations(everything(), uiBanned))
    }

    @Test fun `negative one the foreground guard bites on a real card`() {
        // 负例①「探测打扰前台」：确认卡与 Toast 任意一种都必须被认出来。
        val offending = "AlertDialog.Builder(ctx).show()\nToast.makeText(ctx, msg, Toast.LENGTH_LONG).show()"
        assertEquals(2, violations(offending, uiBanned).size)
    }

    @Test fun `scout never asks for permissions by itself`() {
        assertEquals(emptyList<String>(), violations(everything(), permissionBanned))
    }

    @Test fun `negative four the repeat-ask guard is a ledger fact not a session counter`() {
        // 负例④「重复问同一条权限」的根因：次数记在内存 ⇒ 每次开 App 从 0 数，永远"才第 1 次"。
        // 所以次数必须经账本落盘，装配层里不许放可变计数器。
        val scout = sources().getValue("Scout.kt")
        assertFalse("驳回次数不许只记在内存", Regex("""var denials""").containsMatchIn(scout))
        assertTrue("驳回次数必须写进账本", scout.contains("ledger.append(denied)"))
        assertTrue("未办成单也要落盘", scout.contains("UnfinishedLedger("))
    }

    @Test fun `the judgment layer stays free of android io coroutines and clocks`() {
        val text = sources()
        pure.forEach { name ->
            val body = text.getValue(name)
            listOf(
                "import android.", "import java.io.", "import kotlinx.", "import androidx.",
                "System.currentTimeMillis", "SystemClock", "Thread(",
            ).forEach { token ->
                assertFalse("$name 碰了 $token（判据层要能 JVM 直测）", body.contains(token))
            }
        }
    }

    @Test fun `only the ledger shells and the probe layer touch files`() {
        val text = sources()
        text.filterKeys { it !in ioOrAndroid }.forEach { (name, body) ->
            listOf("java.io.File", "readText(", "writeText(", "appendText(", "bufferedReader(", "FileOutputStream").forEach {
                assertFalse("$name 绕过账本壳自己读写文件：$it", body.contains(it))
            }
        }
        // 反过来：账本壳必须真的落盘（否则"失败结论落账"只是句口号）。
        val fanout = text.getValue("LedgerFanout.kt")
        assertTrue("扇出层没有真的追加写", fanout.contains("appendText("))
        assertTrue("扇出层没有真的覆盖写", fanout.contains("writeText("))
        assertTrue(text.getValue("ScoutLedger.kt").contains("appendLine("))
        assertTrue(text.getValue("UnfinishedLedger.kt").contains("appendLine("))
        // 路径解析层只拼路径，不许自己读写。
        val dirs = text.getValue("ScoutDirs.kt")
        listOf("appendText(", "writeText(", "readText(", "bufferedReader(").forEach {
            assertFalse("ScoutDirs 越界做了 IO：$it", dirs.contains(it))
        }
    }

    @Test fun `the ledger keeps two copies so a permission flip cannot reset the counts`() {
        // 权威（App 私有）＋镜像（工作区）：只写工作区的话，一关存储权限就换成另一本账、
        // 次数归零 ⇒ 斥候把"已经拒过两次"的权限当"还没问过"再问一遍（负例④的另一半）。
        val dirs = sources().getValue("ScoutDirs.kt")
        assertTrue("没有 App 私有权威本", dirs.contains("ctx.filesDir"))
        assertTrue("没有工作区镜像本", dirs.contains("BaopuWorkspace.hostDir"))
        assertTrue(
            "权威本必须排在镜像本之前（读侧冲突时权威胜）",
            dirs.indexOf("privateScout(ctx)") < dirs.indexOf("MemoryLayout.memoryDir"),
        )
    }

    @Test fun `the probe layer only asks whether things exist never what is inside`() {
        val body = sources().getValue("AndroidScoutProbes.kt")
        listOf("exists()", "canRead()").forEach { assertTrue("探针该只查元数据：$it", body.contains(it)) }
        listOf("readText(", "readBytes(", "inputStream(", "listFiles(").forEach {
            assertFalse("探针打开了文件内容：$it", body.contains(it))
        }
    }

    @Test fun `the scout is installed once from the application entry point`() {
        val app = code(File("src/main/java/com/example/zhengdao/ZhengdaoApp.kt").readText())
        assertTrue("ZhengdaoApp 没有装斥候（新能力不许做了没接线）", app.contains("scout.Scout.install(this)"))
        assertTrue("斥候必须在 AppForeground 之后装（要挂回前台监听）", app.indexOf("AppForeground.install") < app.indexOf("Scout.install"))
    }

    @Test fun `both outputs of the scout have a real caller`() {
        val scout = sources().getValue("Scout.kt")
        assertTrue("意见没有交给四器：ScoutOpinions.forVessels 不在探测流水线里", scout.contains("ScoutOpinions.forVessels"))
        assertTrue("一页清单没有落盘", scout.contains("saveSheet("))
        assertTrue("读数层没被调用", scout.contains("AndroidScoutProbes.probeAll("))
        assertTrue("判据层没被调用", scout.contains("ScoutRules.resolve("))
        assertTrue("触发原因没被记录", scout.contains("ScoutFreshness.autoTriggers("))
    }

    @Test fun `the page limit lives in one place and is ten`() {
        assertEquals(10, ScoutLimits.MAX_SHEET_LINES)
        assertEquals(2_000L, ScoutLimits.MAX_PROBE_MS)
        val sheet = sources().getValue("ScoutSheet.kt")
        assertTrue("行数上限只许取常量", sheet.contains("ScoutLimits.MAX_SHEET_LINES"))
        assertFalse("渲染层不许自己再写一个行数", Regex("""take\(\s*9\s*\)""").containsMatchIn(sheet))
    }
}
