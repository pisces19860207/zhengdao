// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百二十一批 · 岛叶的**源码门禁**（判据③四条禁止的文字形态不变量）。
//
// 钉什么：
//   ① 岛叶包里**没有**改授权档/用户设置/判据阈值/工具白名单的任何路径——引用即红。
//   ② 读数只复用既有采集：AutoMaintenance（电量/充电/发烫）、AskDailyBudget（预算）、
//     PatrolScheduler（日历尺）、StatFs（存储，WishIOWiring 既定尺）、Insula.snapshot（器官消费）；
//     不许出现第二把电池/系统服务采集（BatteryManager/getSystemService/registerReceiver）。
//   ③ 参数面形状即白名单：参数文件只写四个键（TunableParam 闭集由行为测试钉）。
// 每条判据带**变异负例**（判据本身能被绕过它就是假的）。
package com.example.zhengdao.baopu.insula

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

internal class InsulaSourceGateTest {

    private companion object {
        /** 岛叶包的主源码目录（新增文件自动进扫描；门禁扫的是代码、注释剥掉）。 */
        val PACKAGE_DIR = "app/src/main/java/com/example/zhengdao/baopu/insula"

        /** 判据③：越权写口禁词——授权档／用户设置／判据阈值／工具白名单，引用即红。 */
        val FORBIDDEN_OVERREACH = listOf(
            "AskGovernance", "AskTier", "setTier(", "currentTier",
            "ProviderPrefs", "putString(", "putInt(", "putBoolean(", "putFloat(",
            "ToolPrefs", "HabitLimits", "EchoLimits", "REPEAT_THRESHOLD",
            "MIN_BATTERY_PCT", "PROMOTE_USES", "edit {", "SharedPreferences",
        )

        /** 判据②：不许新造采集——这些是"第二把电池/系统服务"的禁词。 */
        val FORBIDDEN_NEW_COLLECTORS = listOf(
            "BatteryManager", "getSystemService", "registerReceiver", "Debug.getMemoryInfo",
        )

        /** 判据②的正面钉子：读数必须从这几处既有采集来。 */
        val REQUIRED_EXISTING_SOURCES = listOf(
            "AutoMaintenance.", "AskDailyBudget.", "PatrolScheduler.dayBucket",
            "StatFs", "Insula.snapshot",
        )
    }

    /** 从测试工作目录回仓库根找文件/目录（与 SubconsciousGateTest.repoFile 同房型）。 */
    private fun repoFile(rel: String): File {
        for (f in listOf(File(rel), File("app/$rel"), File("../$rel"))) {
            if (f.isFile || f.isDirectory) return f
        }
        error("找不到源文件：$rel（模块搬家了？门禁跟着改）")
    }

    /** 读源码并剥掉注释（注释里提到禁词不算数，只看代码）。 */
    private fun code(rel: String): String = repoFile(rel).readText(Charsets.UTF_8)
        .replace("\r\n", "\n")
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^[ \t]*//.*$"), "")

    /** 门禁本体（给负例复用）：禁词一个都不许出现。 */
    private fun eatsNo(forbidden: List<String>, src: String): Boolean =
        forbidden.none { src.contains(it) }

    private fun packageSources(): List<Pair<String, String>> =
        repoFile(PACKAGE_DIR).listFiles().orEmpty()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.name to code("$PACKAGE_DIR/${it.name}") }
            .sortedBy { it.first }

    // ── 判据③：试图改授权档的代码路径不存在 ────────────────────────────────

    /** 岛叶包里没有授权档/用户设置/判据阈值/工具白名单的任何引用（新增文件自动进扫描）。 */
    @Test
    fun 源级_岛叶包没有越权写路径() {
        packageSources().forEach { (name, src) ->
            assertTrue(
                "$name 出现越权引用：${FORBIDDEN_OVERREACH.filter { src.contains(it) }}",
                eatsNo(FORBIDDEN_OVERREACH, src),
            )
        }
    }

    /** 变异负例：往岛叶包塞一行授权档引用 ⇒ 门禁必须红（判据咬得住）。 */
    @Test
    fun 负例_塞进授权档引用门禁必须红() {
        val sample = packageSources().first().second
        val poisoned = sample + "\nprivate fun sneak() = AskGovernance.setTier(\"DELEGATED\")\n"
        assertFalse("变异负例必须红（否则不越权的判据是假的）", eatsNo(FORBIDDEN_OVERREACH, poisoned))
    }

    /** 变异负例：塞一行 SharedPreferences 写 ⇒ 门禁必须红（用户设置的写口不许长进来）。 */
    @Test
    fun 负例_塞进用户设置写口门禁必须红() {
        val sample = packageSources().first().second
        val poisoned = sample + "\nprivate fun sneak2(p: android.content.SharedPreferences) = p.edit { putString(\"k\", \"v\") }\n"
        assertFalse(eatsNo(FORBIDDEN_OVERREACH, poisoned))
    }

    // ── 判据②：读数只复用既有采集 ─────────────────────────────────────────

    /** 正面钉子：五路读数全部引既有采集；不许出现第二把电池/系统服务采集。 */
    @Test
    fun 源级_读数只复用既有采集() {
        val all = packageSources().joinToString("\n") { it.second }
        REQUIRED_EXISTING_SOURCES.forEach { needle ->
            assertTrue("岛叶包缺既有采集的引用：$needle", all.contains(needle))
        }
        packageSources().forEach { (name, src) ->
            assertTrue(
                "$name 出现新造采集：${FORBIDDEN_NEW_COLLECTORS.filter { src.contains(it) }}",
                eatsNo(FORBIDDEN_NEW_COLLECTORS, src),
            )
        }
    }

    /** 变异负例：塞一行 BatteryManager ⇒ 门禁必须红（不许偷偷摸系统服务）。 */
    @Test
    fun 负例_塞进新采集门禁必须红() {
        val sample = packageSources().first().second
        val poisoned = sample + "\nprivate fun sneak3() = BatteryManager.BATTERY_PROPERTY_CAPACITY\n"
        assertFalse(eatsNo(FORBIDDEN_NEW_COLLECTORS, poisoned))
    }
}
