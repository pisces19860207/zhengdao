// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百四十一批（打开产物）· 交付判据。
// 四条硬判据：① Intent 解析断言三类；② 无应用提示；③ MIME 三条；
// ④ 不新增存储权限（源码断言）。另加「文件不在了」「定位退化」「既有预览与分享未动」回归。
//
// 为什么规格与源码各钉一半：本仓单测是纯 JVM，android.content.Intent 造不出来，
// 所以「Intent 长什么样」由 ArtifactOpenIntent 这份纯数据断言；「这份规格真的
// 落成了 Intent」由 ArtifactOpenActions.kt 的源码文本断言。两半合起来才是这一击。
package com.example.zhengdao.ui.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ArtifactOpenGateTest {

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.isFile) it else File("app/$rel") }

    private fun src(rel: String): String = repoFile(rel).readText(Charsets.UTF_8).replace("\r\n", "\n")

    /** 剥掉注释再断言：注释里写了什么不算代码。 */
    private fun code(rel: String): String = src(rel)
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    private fun plan() = code("src/main/java/com/example/zhengdao/ui/baopu/ArtifactOpenPlan.kt")
    private fun actions() = code("src/main/java/com/example/zhengdao/ui/baopu/ArtifactOpenActions.kt")
    private fun shelf() = code("src/main/java/com/example/zhengdao/ui/baopu/BaopuArtifactShelf.kt")
    private fun locateDialog() = code("src/main/java/com/example/zhengdao/ui/baopu/ArtifactLocateDialog.kt")

    private fun manifest() = src("src/main/AndroidManifest.xml")
    private fun cameraPaths() = src("src/main/res/xml/baopu_file_paths.xml")
    private fun openPaths() = src("src/main/res/xml/artifact_open_paths.xml")
    private fun zh() = src("src/main/res/values/strings.xml")
    private fun en() = src("src/main/res/values-en/strings.xml")

    private fun resolved(name: String): ArtifactOpenIntent {
        val p = ArtifactOpen.planOf(name, onDisk = true)
        assertTrue("$name 必须能打开：$p", p is ArtifactOpenPlan.Ready)
        return (p as ArtifactOpenPlan.Ready).spec
    }

    // ── 判据①：Intent 解析断言三类 ────────────────────────────────────────

    private fun assertViewSpec(name: String, expectedMime: String) {
        val s = resolved(name)
        assertEquals("action 必须是 ACTION_VIEW", "android.intent.action.VIEW", s.action)
        assertEquals("必须是 content://（file:// 在安卓 7+ 直接崩）", "content", s.scheme)
        assertEquals("authority 后缀要与清单一致", ".artifact.open", s.authoritySuffix)
        assertEquals("$name 的 MIME", expectedMime, s.mime)
        assertTrue("必须给临时读授权", s.grantRead)
        assertTrue("必须带 ClipData（API 16-22 的授权兼容）", s.withClipData)
    }

    @Test
    fun `判据1a_docx_的Intent解析`() {
        assertViewSpec("产物/报告-20261114.docx", ArtifactOpen.MIME_DOCX)
        // 规格必须真的落成 Intent，而不是只停在纯数据里
        val a = actions()
        assertTrue("缺 setDataAndType", a.contains("setDataAndType(uri, spec.mime)"))
        assertTrue("缺临时读授权 flag", a.contains("Intent.FLAG_GRANT_READ_URI_PERMISSION"))
        assertTrue("缺 ClipData", a.contains("ClipData.newRawUri"))
        assertTrue("缺 FileProvider 的 content Uri", a.contains("FileProvider.getUriForFile"))
        assertTrue("缺 PathGuard 越界闸", a.contains("PathGuard.isInside(root, file)"))
    }

    @Test
    fun `判据1b_xlsx_的Intent解析`() {
        assertViewSpec("产物/统计表.xlsx", ArtifactOpen.MIME_XLSX)
    }

    @Test
    fun `判据1c_png_的Intent解析`() {
        assertViewSpec("产物/图/立绘.PNG", ArtifactOpen.MIME_PNG)
    }

    @Test
    fun `判据1d_三类之外不发Intent_大小写与子目录不误判`() {
        assertTrue("md 不该发", ArtifactOpen.planOf("笔记.md", true) is ArtifactOpenPlan.Unsupported)
        assertTrue(".doc 不该当成 docx", ArtifactOpen.planOf("旧版.doc", true) is ArtifactOpenPlan.Unsupported)
        assertEquals("大写后缀照样认", ArtifactOpen.MIME_PNG, ArtifactOpen.mimeOf("A/B/C.PNG"))
        assertNotEquals("三类 MIME 不许撞车", ArtifactOpen.MIME_DOCX, ArtifactOpen.MIME_XLSX)
        assertNotEquals("三类 MIME 不许撞车", ArtifactOpen.MIME_XLSX, ArtifactOpen.MIME_PNG)
    }

    // ── 判据②：无可用应用 ⇒ 明确提示 ─────────────────────────────────────

    @Test
    fun `判据2_无应用有两条路都不许静默_并且界面说人话`() {
        val a = actions()
        assertTrue("先查有没有能接的应用", a.contains("resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)"))
        assertTrue("resolveActivity 为空要回 NO_VIEWER", a.contains("if (resolved == null) return ArtifactOpenOutcome.NO_VIEWER"))
        assertTrue("startActivity 抛异常也要兜住", a.contains("ActivityNotFoundException"))
        assertEquals(
            "NO_VIEWER 必须同时来自判空与异常两条路",
            2,
            Regex("ArtifactOpenOutcome.NO_VIEWER").findAll(a).count(),
        )
        val s = shelf()
        assertTrue("界面必须查「没有应用」那句资源", s.contains("R.string.baopu_artifact_open_no_app"))
        assertTrue("中文有这句", zh().contains("name=\"baopu_artifact_open_no_app\""))
        assertTrue("英文有这句", en().contains("name=\"baopu_artifact_open_no_app\""))
        // 判别器：回 NO_VIEWER 这个形状确实能被上面的正则扫到（门禁不是空转）
        assertTrue(
            "判别器抓得住 NO_VIEWER 分支",
            Regex("return ArtifactOpenOutcome.NO_VIEWER")
                .containsMatchIn("if (resolved == null) return ArtifactOpenOutcome.NO_VIEWER"),
        )
    }

    // ── 判据③：MIME 三条逐字正确 ─────────────────────────────────────────

    @Test
    fun `判据3_三条MIME逐字对_不靠兜底`() {
        assertEquals(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            ArtifactOpen.MIME_DOCX,
        )
        assertEquals(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            ArtifactOpen.MIME_XLSX,
        )
        assertEquals("image/png", ArtifactOpen.MIME_PNG)
        val p = plan()
        assertTrue("docx 常量在源码里逐字存在", p.contains(ArtifactOpen.MIME_DOCX))
        assertTrue("xlsx 常量在源码里逐字存在", p.contains(ArtifactOpen.MIME_XLSX))
        assertTrue("png 常量在源码里逐字存在", p.contains(ArtifactOpen.MIME_PNG))
        assertFalse("不许拿 MimeTypeMap 猜（docx 常返回 null）", p.contains("MimeTypeMap"))
        assertFalse("不许拿 octet-stream 兜底", p.contains("application/octet-stream"))
        assertEquals("docx", ArtifactOpen.MIME_DOCX, resolved("a.docx").mime)
        assertEquals("xlsx", ArtifactOpen.MIME_XLSX, resolved("a.xlsx").mime)
        assertEquals("png", ArtifactOpen.MIME_PNG, resolved("a.png").mime)
    }

    // ── 判据④：不新增存储权限（源码断言）────────────────────────────────

    private val permissionRe = Regex("<uses-permission[^>]*android:name=\"(android\\.permission\\.[A-Z_]+)\"")

    @Test
    fun `判据4_主清单权限集合与基线一字不差_代码侧不申请权限`() {
        val declared = permissionRe.findAll(manifest()).map { it.groupValues[1] }.toSet()
        val baseline = setOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE",
            "android.permission.MANAGE_EXTERNAL_STORAGE",
            "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
            "android.permission.WAKE_LOCK",
            "android.permission.CAMERA",
            "android.permission.RECORD_AUDIO",
            "android.permission.SCHEDULE_EXACT_ALARM",
            "android.permission.READ_CALENDAR",
        )
        assertEquals("基线 14 条", 14, baseline.size)
        assertEquals(
            "清单权限集合变了（新增/删除都算）：${declared - baseline} / ${baseline - declared}",
            baseline,
            declared,
        )
        assertEquals(
            "存储类权限仍只有既有的三条",
            setOf(
                "android.permission.READ_EXTERNAL_STORAGE",
                "android.permission.WRITE_EXTERNAL_STORAGE",
                "android.permission.MANAGE_EXTERNAL_STORAGE",
            ),
            declared.filter { it.contains("STORAGE") }.toSet(),
        )
        val batch = plan() + actions() + shelf() + locateDialog()
        listOf(
            "checkSelfPermission", "requestPermissions", "ShouldShowRequestPermissionRationale",
            "Manifest.permission.", "ACTION_MANAGE_ALL_STORAGE", "isExternalStorageManager",
        ).forEach { tok ->
            assertFalse("本单代码里不许出现 $tok", batch.contains(tok))
        }
        assertTrue(
            "判别器：权限申请写法确实能被扫到",
            "ctx.requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)".contains("requestPermissions"),
        )
    }

    @Test
    fun `判据4b_新provider口子写死_既有相机paths一字未动`() {
        val m = manifest()
        assertTrue("缺专用 provider 的 authority", m.contains("\${applicationId}.artifact.open"))
        assertTrue("缺 exported=false", m.contains("android:exported=\"false\""))
        assertTrue("缺 grantUriPermissions", m.contains("android:grantUriPermissions=\"true\""))
        assertTrue("缺专用 paths", m.contains("@xml/artifact_open_paths"))
        val op = openPaths()
        assertTrue("共享存储根要能发 content Uri", op.contains("<external-path"))
        assertTrue("私有模式也要覆盖", op.contains("<external-files-path"))
        listOf("<root-path", "<files-path", "<cache-path").forEach { bad ->
            assertFalse("专用 paths 不许开 $bad 口子", op.contains(bad))
        }
        val cam = cameraPaths()
        assertTrue("相机 paths 仍是 cache-path", cam.contains("<cache-path"))
        assertFalse("相机 paths 不许被加宽", cam.contains("<external-path") || cam.contains("<root-path"))
    }

    // ── 回归：文件不在了 / 定位退化 / 既有预览与分享未动 ──────────────────

    @Test
    fun `回归_删后再点开报文件不在了_现查盘不信旧徽标`() {
        assertTrue("盘上没有 ⇒ 不发 Intent", ArtifactOpen.planOf("产物/报告.docx", false) is ArtifactOpenPlan.Gone)
        assertEquals("定位也一样", ArtifactLocatePlan.Gone, ArtifactOpen.locatePlanOf(false, true))
        val a = actions()
        assertTrue("打开前现查 isFile", a.contains("ArtifactOpen.planOf(file.name, file.isFile)"))
        assertTrue("定位前现查 isFile", a.contains("!file.isFile"))
        assertTrue("界面有「文件不在了」那句", shelf().contains("R.string.baopu_artifact_open_gone"))
        assertTrue("中文文案就是用户要的那句", zh().contains("文件不在了：%1\$s"))
    }

    @Test
    fun `回归_定位没有应用接就退化成路径加复制_复制只有一份`() {
        assertEquals("无处理者 ⇒ 退化", ArtifactLocatePlan.ShowPath, ArtifactOpen.locatePlanOf(true, false))
        assertEquals("有处理者 ⇒ 试着开目录", ArtifactLocatePlan.OpenFolder, ArtifactOpen.locatePlanOf(true, true))
        assertTrue("退化层真的写剪贴板", locateDialog().contains("setPrimaryClip"))
        assertTrue("退化层显示完整路径", locateDialog().contains("R.string.baopu_artifact_locate_path, absPath"))
        assertTrue("界面调用了退化层", shelf().contains("ArtifactLocateDialog("))
        assertTrue("界面明说没有文件管理器", shelf().contains("R.string.baopu_artifact_locate_no_app"))
        assertFalse("产物架里不许有第二份复制实现", shelf().contains("setPrimaryClip"))
    }

    @Test
    fun `回归_既有预览与分享没被改`() {
        val v = code("src/main/java/com/example/zhengdao/ui/baopu/BaopuArtifactViewer.kt")
        assertTrue("复制全文还是查看器那一份", v.contains("copyArtifactToClipboard(context, root, relPath)"))
        assertTrue("只读查看仍在", v.contains("artifactCopyOf(root, relPath)"))
        assertFalse("查看器不许改造成外部打开", v.contains("ACTION_VIEW") || v.contains("FileProvider"))
        val r = code("src/main/java/com/example/zhengdao/IncomingIntentRouter.kt")
        assertTrue("系统分享入口仍在", r.contains("ACTION_SEND -> routeSend(request)"))
    }
}
