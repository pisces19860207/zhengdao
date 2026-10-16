// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 台账 §B 顺序 54／§B-补-51（改法②「同一原因合并计数」）的**接线门禁**（2026-10-16，第㉞刀）。
 *
 * ## 为什么单有一份门禁
 *
 * 改法②的成败不在 `IssueCenter` 内部（那半边由 `IssueCenterTest` 用行为锁死），
 * 而在**三处接线**上，任何一处没接上都不会让行为测试变红：
 *
 * 1. 保活留档要把 `exits.log` 里数出来的**跨进程**累计次数交进问题卡 —— 不接，次数永远显示 1；
 * 2. 主页要**真的把次数画出来**（角标）—— 不接，数据算了也没人看见（＝改动白做）；
 * 3. `strings.xml` 要有那个格式串 —— 缺了就是编译期就挂，但没有断言指着它时容易被顺手删掉。
 *
 * 判据钉**接线事实**（谁调用谁、哪个资源被引用），不钉局部变量名与实现形态（见 ERRATA E-133）。
 */
class IssueCountGateTest {

    /** 读仓库里的源码文件（相对 app/ 或仓库根，两处都试）。 */
    private fun source(rel: String): String {
        val roots = listOf(
            File("app/$rel"),
            File(rel),
            File("../app/$rel"),
        )
        val f = roots.firstOrNull { it.isFile }
            ?: error("找不到源码：$rel（cwd=${File("").absolutePath}）")
        return f.readText()
    }

    /** ① 保活留档：累计次数必须从持久留档里数出来，并交给问题卡。 */
    @Test
    fun `保活留档把跨进程累计次数交给问题卡`() {
        val archive = source("src/main/java/com/example/zhengdao/keepalive/KeepaliveArchive.kt")
        assertTrue(
            "累计次数必须从 exits.log 数出来（uncleanCount 是那条纯函数）",
            archive.contains("uncleanCount("),
        )
        // 这一条是**接线契约**（名字绑得紧是故意的）：数出来的那个数必须原样交进问题卡。
        // 只断言"report 里有个 count 参数"是不够的 —— 把它写成常量 1 也一样能通过，
        // 而那正是这个改动最容易塌的地方（数据算了没接线，卡片永远显示 1 次）。
        // 数得对不对由 KeepaliveArchiveTest 的行为用例负责，两边合起来才完整。
        assertTrue(
            "数出来的 uncleanTotal 必须是交给 IssueCenter 的那个次数（count = uncleanTotal）",
            Regex("IssueCenter\\.report\\([\\s\\S]{0,600}?count\\s*=\\s*uncleanTotal").containsMatchIn(archive),
        )
    }

    /** ② 主页：「最近问题」卡必须把次数画出来（角标），否则次数算了也没人看见。 */
    @Test
    fun `主页最近问题卡把次数画出来`() {
        val home = source("src/main/java/com/example/zhengdao/ui/HomeScreen.kt")
        assertTrue(
            "次数角标要引用 home_issue_count 这个格式串",
            home.contains("R.string.home_issue_count"),
        )
        assertTrue(
            "角标读的是那一条问题自己的次数",
            home.contains("issue.count"),
        )
    }

    /** ③ 文案资源：格式串存在，且是「×N」这种一眼看出"同样的事发生了 N 次"的形态。 */
    @Test
    fun `次数角标文案存在且是乘号形态`() {
        val strings = source("src/main/res/values/strings.xml")
        val m = Regex("<string name=\"home_issue_count\">([^<]*)</string>").find(strings)
        assertTrue("strings.xml 缺少 home_issue_count", m != null)
        val body = m!!.groupValues[1]
        assertTrue("角标要带 ×（乘号），不能只写裸数字：$body", body.contains("×"))
        assertTrue("角标要带一个 %1\$d 占位符：$body", body.contains("%1\$d"))
    }
}
