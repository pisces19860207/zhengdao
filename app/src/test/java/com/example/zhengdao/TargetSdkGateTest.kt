// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 第㉕刀（抬 targetSdk 28 → 36）的源码形态门禁（2026-10-15）。
 *
 * ## 为什么这刀需要门禁
 * targetSdk 是**一个字符就能改、改错全无声**的旋钮：把它写回 28，编译照过、单测照绿，
 * 只有真机行为会变；把 FGS 类型删掉，targetSdk 34+ 的机型上一起服务就崩
 * （MissingForegroundServiceTypeException）；存储判定少查一条通道，36 上共享存储直接全灭。
 * 这些都没有编译器或既有测试看着 ⇒ 按项目惯例立源码形态门禁，跟 `:app:testDebugUnitTest`
 * 每次提交都跑。
 *
 * ## 判据口径（E-133 教训：钉实质不钉变量名）
 * 全部断言走「剥注释后的源码」—— 判据不许被 KDoc 散文命中（本刀之前就有门禁判据命中
 * 自己写的 `overridePendingTransition` 注释的旧例），也不许被注释里的历史陈述糊弄。
 */
class TargetSdkGateTest {

    /** 从模块目录或仓库根出发找文件（单测 CWD 在不同 Gradle 版本/IDE 下不一致）。 */
    private fun src(relative: String): File {
        val candidates = listOf(File(relative), File("app/$relative"))
        val found = candidates.firstOrNull { it.isFile }
            ?: error("找不到 $relative（试过：${candidates.joinToString { it.path }}）")
        return found
    }

    private fun read(relative: String): String = src(relative).readText()

    /** 剥掉 Kotlin 行注释与块注释：判据只对**真代码**生效。 */
    private fun kotlinCode(text: String): String =
        text.replace(Regex("/\\*[\\s\\S]*?\\*/"), "").replace(Regex("//[^\n]*"), "")

    /** 剥掉 XML 注释：免得注释里提到的属性被当成真声明（与 StoragePermissionGuardTest 同款）。 */
    private fun xmlOnly(text: String): String =
        text.replace(Regex("<!--[\\s\\S]*?-->"), "")

    private val gradleCode: String get() = kotlinCode(read("build.gradle.kts"))
    private val manifestXml: String get() = xmlOnly(read("src/main/AndroidManifest.xml"))

    // ── ① targetSdk 本体 ────────────────────────────────────────────────

    @Test
    fun `targetSdk 就是 36`() {
        assertTrue(
            "targetSdk 不是 36 —— 第㉕刀（抬 28 → 36）被回退了？依据 ERRATA E-134：" +
                "执行链迁 jniLibs 后 W^X 数据目录限制不再作用到 guest 二进制",
            Regex("targetSdk\\s*=\\s*36\\b").containsMatchIn(gradleCode),
        )
        assertFalse(
            "代码里还写着 targetSdk = 28 —— 抬回去意味着抱朴/终端至少一条链会按旧口径行事",
            Regex("targetSdk\\s*=\\s*28\\b").containsMatchIn(gradleCode),
        )
    }

    @Test
    fun `targetSdk 注释口径_引用 E-134 且不再宣称生死线`() {
        // 注释**不剥**——这一条钉的正是注释本身：口径必须从「架构生死线、不可上调」
        // 换成「现行打包方式的前提」，并且给出依据编号（E-134）。
        val raw = read("build.gradle.kts")
        assertTrue(
            "targetSdk 上方的注释没引 E-134 —— 口径更改必须给证据编号，" +
                "后来人才查得到当初凭什么抬",
            raw.contains("E-134"),
        )
        assertFalse(
            "旧禁令行「🚫 未经架构前提重新评估，任何人不得上调此值」还在 —— " +
                "这句话在 36 之下是错的（E-134 已证伪其必然性）",
            raw.contains("🚫 未经架构前提重新评估"),
        )
        assertFalse(
            "注释里还有「架构生死线」四个字 —— 同旧禁令，一并清掉",
            raw.contains("架构生死线"),
        )
    }

    // ── ② 版本号三处一致（发版口径，自动跟随） ──────────────
    //
    // 旧口径（第、刀：把 versionName / versionCode 钉死在 2.1.1 / 36）在 v2.1.2 发版时必然变红：
    // 它把「本刀不许动版本号」这条**一次性**纪律写成了**永久**断言。
    // 现在改成发版口径的自动跟随 —— 版本号可以在发版时前进，
    // 但必须**三处同时跟上**（gradle ／ release note ／ README 中英两份），少一处就红。
    // 这样既抓得住「偷偷改版本号」，也不会在下次发版时变成假警报。

    /** 从当前工作目录向上找到仓库根（含 .github 的那一级），再拼相对路径。 */
    private fun repo(relative: String): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.exists()) return candidate
            if (File(dir, ".github").isDirectory) return candidate
            dir = dir.parentFile
        }
        error("找不到仓库根（从 ${File(".").absolutePath} 向上）")
    }

    @Test
    fun `版本号红线未动`() {
        val version = Regex("appVersionName\\s*=\\s*\"(\\d+\\.\\d+\\.\\d+)\"")
            .find(gradleCode)?.groupValues?.get(1)
            ?: error("app/build.gradle.kts 里找不到 appVersionName")
        val code = Regex("versionCode\\s*=\\s*(\\d+)").find(gradleCode)?.groupValues?.get(1)
            ?: error("app/build.gradle.kts 里找不到 versionCode")

        assertTrue(
            "release note `.github/release-notes/v$version.md` 不在 —— 版本号前进了，发布正文没跟上",
            repo(".github/release-notes/v$version.md").isFile,
        )
        val cn = repo("README.md").readText()
        assertTrue(
            "README.md 没写「当前版本：v$version」—— 三处一致里中文那份没跟上",
            cn.contains("当前版本：v$version"),
        )
        assertTrue(
            "README.md 没写「versionCode $code」—— 版本号与版本码必须一起前进",
            cn.contains("versionCode $code"),
        )
        val en = repo("README.en.md").readText()
        assertTrue(
            "README.en.md 没写「Current release: v$version」—— 英文那份没跟上",
            en.contains("Current release: v$version"),
        )
        assertTrue(
            "README.en.md 没写「versionCode $code」—— 英文那份的版本码没跟上",
            en.contains("versionCode $code"),
        )
    }

    // ── ③ FGS 类型（targetSdk 34+ 硬要求） ─────────────────────────────

    @Test
    fun `SessionService 声明 specialUse 类型_且专属权限在清单里`() {
        val xml = manifestXml
        assertTrue(
            "SessionService 没有 foregroundServiceType —— targetSdk 34+ 起不声明就抛 " +
                "MissingForegroundServiceTypeException，一起服务就崩",
            Regex("<service[^>]*foregroundServiceType=\"specialUse\"[^>]*>").containsMatchIn(xml),
        )
        assertTrue(
            "specialUse 的 subtype 属性没了 —— API 34+ 对 specialUse 的强制项",
            xml.contains("android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"),
        )
        assertTrue(
            "清单里缺 FOREGROUND_SERVICE_SPECIAL_USE 权限 —— 与 specialUse 类型配对的硬要求",
            Regex("<uses-permission[^>]*FOREGROUND_SERVICE_SPECIAL_USE").containsMatchIn(xml),
        )
        assertFalse(
            "不许回退成 dataSync —— Android 15+ 起该类型每天累计 6 小时被系统停掉，" +
                "长终端会话必死（选型理由写在 manifest 注释里）",
            xml.contains("foregroundServiceType=\"dataSync\""),
        )
    }

    // ── ④ 存储判定双通道（抬 36 的直接连带项） ─────────────────────────

    @Test
    fun `存储判定双通道_36 走所有文件访问_28 走 WRITE 权限`() {
        val launcher = kotlinCode(read("src/main/java/com/example/zhengdao/terminal/ProotLauncher.kt"))
        assertTrue(
            "storageGranted 不查 isExternalStorageManager —— 36 上 WRITE 权限已 inert，" +
                "少这条通道共享存储全灭",
            launcher.contains("isExternalStorageManager"),
        )
        assertTrue(
            "storageGranted 不认 WRITE_EXTERNAL_STORAGE —— target 28 设备的 legacy 视图通道被删了",
            launcher.contains("WRITE_EXTERNAL_STORAGE"),
        )
        val main = kotlinCode(read("src/main/java/com/example/zhengdao/MainActivity.kt"))
        // 134 批（lint ObsoleteSdkInt）：minSdk=36 ⇒ 原 `SDK_INT < 30` 的存储补授权分支是死代码，
        // 已整段删除。判据改成「死分支不许回来」：那两个 inert 权限常量不许再出现在请求代码里。
        assertFalse(
            "MainActivity 又请求 READ/WRITE_EXTERNAL_STORAGE 了 —— minSdk=36 上这是死分支" +
                "（API 30+ 请求只会被静默拒绝）",
            main.contains("READ_EXTERNAL_STORAGE") || main.contains("WRITE_EXTERNAL_STORAGE"),
        )
    }

    // ── ⑤ 边到边（Android 15+ 对 35+ 强制，36 无 opt-out） ──────────────

    @Test
    fun `边到边两页显式处理_不再靠框架自动留位`() {
        // 其余页（Compose）早在 v1.1 第三阶段就 setDecorFitsSystemWindows(false) + insets；
        // 本刀补的是全仓唯一经典 View 页（TerminalActivity）与漏网的 ImeRegressionActivity。
        val terminal = kotlinCode(read("src/main/java/com/example/zhengdao/TerminalActivity.kt"))
        assertTrue(
            "TerminalActivity 没关 decor 自动适配 —— 35+ 强制边到边后顶栏会被状态栏压住",
            terminal.contains("setDecorFitsSystemWindows"),
        )
        assertTrue(
            "TerminalActivity 没挂 insets 监听 —— 只关不适，内容会画到两根栏底下",
            terminal.contains("setOnApplyWindowInsetsListener"),
        )
        val ime = kotlinCode(read("src/main/java/com/example/zhengdao/ui/ImeRegressionActivity.kt"))
        assertTrue(
            "ImeRegressionActivity 没关 decor 自动适配",
            ime.contains("setDecorFitsSystemWindows"),
        )
        assertTrue(
            "ImeRegressionActivity 根 Column 没吃 insets（imePadding/statusBarsPadding）",
            ime.contains("imePadding") && ime.contains("statusBarsPadding"),
        )
    }

    // ── ⑥ 已知代价必须写在码边（oc 的 W^X 回归） ──────────────────────

    @Test
    fun `targetSdk 注释写明 oc 直启的真机实测结论`() {
        // 真机实测（第㉕刀）：抬 36 后首启曾 error=13，但重启后两次复验均成功——
        // 「36 必被 W^X 拦」未复现。这条钉的是「后人改这个文件时必须看见这段实测 +
        // 单设备边界」，不是钉 oc 代码本身（红线不碰）。
        // 负例教训：本文件 changelog 头本来就有多处 "opencode"，只断言 contains("opencode")
        // 在基线上也绿（漏红）——必须同时钉本刀注释特有的「error=13」+「未复现」措辞。
        val raw = read("build.gradle.kts")
        assertTrue(
            "targetSdk 注释没写 oc 直启的真机实测结论（error=13 偶发 / 重启后成功 / " +
                "W^X 必拦未复现）—— 后来人要么以为 36 下抱朴必死，要么忘了换机要重验",
            raw.contains("opencode") && raw.contains("error=13") && raw.contains("未复现"),
        )
    }
}
