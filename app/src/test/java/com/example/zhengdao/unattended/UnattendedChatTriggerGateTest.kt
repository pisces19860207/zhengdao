// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百四十一单 · 一句话值守的**源码门禁**（照 UnattendedWiringGateTest 的口径：
// 读源码、剥注释、按文本判）——判据⑤⑥是"接线不许做歪"的负例，行为测试拦不住
// "多做了一条道"，只能钉在源码上：
//   ⑤ 点通知／点问卡按钮不算回话 ⇒ 触发层只许有一条喂入口（send），别处出现即红；
//   ⑥ 网页/工具文本不触发 ⇒ 触发层无第二条喂入口（结构保证，源码钉）；
//   ⑧ 不加新界面、不做语音 ⇒ 触发层不许有 Composable/Activity/语音识别的口子。
package com.example.zhengdao.unattended

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UnattendedChatTriggerGateTest {

    /** 工作目录可能是 app/ 也可能是仓根（照 388 门禁的同款口径＋"必须读到"防静默空转）。 */
    private fun repoFile(rel: String): File {
        val f = listOf(File(rel), File("app/$rel"), File("../$rel"))
            .firstOrNull { it.isFile || it.isDirectory }
        assertTrue("源码文件读不到（路径解析坏了整套账会空转）：$rel", f != null)
        return f!!
    }

    private fun code(rel: String): String = repoFile(rel).readText()
        .replace("\r\n", "\n")
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)//.*$"), "")

    /** 某成员函数的函数体（从 `fun 名(` 起到下一个同缩进 `fun ` 止；找不到即红）。 */
    private fun funBody(src: String, name: String): String {
        val start = src.indexOf("fun $name(")
        assertTrue("找不到函数：$name", start >= 0)
        val next = src.indexOf("\n    fun ", start).let { if (it < 0) src.length else it }
        return src.substring(start, next)
    }

    private val controller: String
        get() = code("src/main/java/com/example/zhengdao/ui/baopu/BaopuChatController.kt")

    // ── 判据①②③④的接线：唯一喂入口在 send() 里 ────────────────────────

    @Test
    fun `触发层唯一接线点_在send里_且放在调解器之后`() {
        val body = funBody(controller, "send")
        assertTrue(
            "send() 里必须过一道值守触发（判据①②③④的进线）",
            body.contains("UnattendedChatTrigger.onUserSend("),
        )
        val sendStart = controller.indexOf("fun send(")
        val mediator = controller.indexOf("MediatorWiring.onUserTurn(")
        val trigger = controller.indexOf("UnattendedChatTrigger.onUserSend(")
        assertTrue("触发判定必须在调解器之后（同一道，别造第二道）", trigger > mediator && mediator > 0)
        assertTrue("onUserSend 必须出现在 send 函数体内", trigger > sendStart)
    }

    @Test
    fun `负例5_resume与retry与editUser不许触发_点按钮回话不算`() {
        // 这三处是"点出来的重发"，不是打字——258 批同款纪律（别在 resume/retry/editUser 补一道）。
        listOf("resume", "retryLastTurn", "editUser").forEach { name ->
            val body = funBody(controller, name)
            assertTrue("「$name」不许碰值守触发（判据⑤：点按钮不算用户回话）", !body.contains("UnattendedChatTrigger"))
        }
    }

    @Test
    fun `负例5+6_全仓只有控制器一处调触发层_通知路由与问卡按钮无一条道`() {
        // 全仓 main 源码里引用 UnattendedChatTrigger 的文件必须只有两个：
        // 触发层本体 + BaopuChatController。多出任何一个文件，就是多了一条喂入口——
        // 通知点按（IncomingIntentRouter/UnattendedActivity）与问卡按钮（answerChoice/answerGroup）
        // 想触发也够不着（判据⑤⑥的结构保证）。
        val dir = repoFile("src/main/java")
        val hits = dir.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }
            .filter {
                // 剥注释再判：KDoc 里提到触发层名字不算引用（判的是代码面，不是文档面）。
                it.readText().replace("\r\n", "\n")
                    .replace(Regex("(?s)/\\*.*?\\*/"), "")
                    .replace(Regex("(?m)//.*$"), "")
                    .contains("UnattendedChatTrigger")
            }
            .map { it.name }.sorted().toList()
        assertTrue(
            "引用面必须恰好是触发层本体＋控制器，实际：$hits",
            hits == listOf("BaopuChatController.kt", "UnattendedChatTrigger.kt"),
        )
    }

    @Test
    fun `上屏走引擎同一格notice_不许另造第二块显示面`() {
        val body = funBody(controller, "send")
        assertTrue(body.contains("engine.showNotice("))
        val e = code("src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt")
        assertTrue("引擎要有 showNotice 上屏口（441 加法）", e.contains("fun showNotice(text: String)"))
    }

    // ── 进场回执与自动到期 ──────────────────────────────────────────────

    @Test
    fun `进场回执是全仓唯一豁免前台抑制的通知_force只许一个调用方`() {
        val n = code("src/main/java/com/example/zhengdao/unattended/UnattendedNotifier.kt")
        assertTrue("armedNow 要带 force = true（进场回执必须响到）", n.contains("force = true"))
        assertTrue(
            "force 豁免只许 armedNow 一处（不许扩散）",
            n.split("force = true").size - 1 == 1,
        )
        assertTrue("进场回执走 SESSION 普通档（回执不是警报）", n.contains("NOTIF_ARMED, REQ_ARMED, AppNotifications.SESSION,"))
        assertTrue("豁免口要挂在 post 上", n.contains("force: Boolean = false"))
    }

    @Test
    fun `服务节拍要有到期自动收_收值守要清到期值`() {
        val s = code("src/main/java/com/example/zhengdao/unattended/UnattendedDutyService.kt")
        assertTrue("服务每拍要查 autoDisarmAtMs（判据②的到点自动收）", s.contains("autoDisarmPass("))
        assertTrue("到期收的也要走 disarmAndReport 带理由", s.contains("closeReasonCn = reason"))
        val st = code("src/main/java/com/example/zhengdao/unattended/UnattendedStatus.kt")
        assertTrue("disarm 必须清 auto_disarm_at（留着就是下次误收的雷）", st.contains("putLong(KEY_AUTO_DISARM_AT, 0L)"))
        assertTrue("arm 要能带 autoDisarmAtMs（判据②）", st.contains("autoDisarmAtMs: Long? = null"))
    }

    // ── 判据⑧：不做语音、不加新界面 ─────────────────────────────────────

    @Test
    fun `判据8_不做语音识别_触发层与值守包不许摸语音口`() {
        val dir = repoFile("src/main/java/com/example/zhengdao/unattended")
        val all = dir.listFiles { f: File -> f.isFile && f.name.endsWith(".kt") }.orEmpty()
            .joinToString("\n") { code("src/main/java/com/example/zhengdao/unattended/${it.name}") }
        assertTrue("不许出现语音识别口（判据⑧：只做文字）", !all.contains("SpeechRecognizer"))
        assertTrue("不许摸RecognizerIntent", !all.contains("RecognizerIntent"))
        assertTrue("不许摸AudioRecord", !all.contains("AudioRecord"))
    }

    @Test
    fun `判据8_不加新界面_触发层不许有Composable与Activity`() {
        val t = code("src/main/java/com/example/zhengdao/unattended/UnattendedChatTrigger.kt")
        assertTrue("触发层不许长界面", !t.contains("@Composable") && !t.contains("Activity"))
        val p = code("src/main/java/com/example/zhengdao/unattended/UnattendedPhrase.kt")
        assertTrue("判定件是纯函数，不许长界面", !p.contains("@Composable") && !p.contains("Activity"))
    }
}
