// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百四十一批（打开产物）· 判据层。
// 依据的公开接口：Android 官方文档 FileProvider（external-path 对应
// Environment.getExternalStorageDirectory、exported=false、grantUriPermissions=true、
// FLAG_GRANT_READ_URI_PERMISSION、API 16-22 需 ClipData），Microsoft Learn 的
// OOXML contentType 表（docx/xlsx）与 image/png；核对日期 2026-11-14。
//
// 为什么把 Intent 拆成一份「规格」：本仓单测是纯 JVM（没有 Robolectric），
// android.content.Intent 造不出来；而判据要钉的正是那几个能决定成败的字段
// （action / scheme / MIME / 临时读授权 / ClipData）。规格由这里出，
// ArtifactOpenActions.kt 逐字段落地成真 Intent，源码断言钉住落地。
//
// 三类之外的文件一律 Unsupported，文件不在盘上一律 Gone：两种情况都必须
// 说人话，不许静默、不许退化成 application/octet-stream 去碰运气。
package com.example.zhengdao.ui.baopu

import java.util.Locale

internal object ArtifactOpen {

    /** 与 AndroidManifest.xml 里第二个 provider 的 authorities 后缀逐字一致。 */
    const val AUTHORITY_SUFFIX = ".artifact.open"

    const val ACTION_VIEW = "android.intent.action.VIEW"
    const val SCHEME_CONTENT = "content"

    /** 定位父目录时试探用的目录 MIME；没有应用接就退化成路径＋复制。 */
    const val FOLDER_MIME = "vnd.android.document/directory"

    const val MIME_DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    const val MIME_XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    const val MIME_PNG = "image/png"

    /** 只认三类：docx / xlsx / png。大小写不敏感，但绝不猜未知后缀的 MIME。 */
    fun mimeOf(fileName: String): String? = when (
        fileName.substringAfterLast('/').substringAfterLast('.', "").lowercase(Locale.ROOT)
    ) {
        "docx" -> MIME_DOCX
        "xlsx" -> MIME_XLSX
        "png" -> MIME_PNG
        else -> null
    }

    /** 一次 ACTION_VIEW 的全部可判字段。grantRead 与 withClipData 恒为 true。 */
    fun intentSpecOf(mime: String): ArtifactOpenIntent = ArtifactOpenIntent(
        action = ACTION_VIEW,
        scheme = SCHEME_CONTENT,
        authoritySuffix = AUTHORITY_SUFFIX,
        mime = mime,
        grantRead = true,
        withClipData = true,
    )

    /** 点「用其他应用打开」之前的那道闸：先看盘上有没有，再认类型。 */
    fun planOf(fileName: String, onDisk: Boolean): ArtifactOpenPlan {
        if (!onDisk) return ArtifactOpenPlan.Gone
        val mime = mimeOf(fileName) ?: return ArtifactOpenPlan.Unsupported
        return ArtifactOpenPlan.Ready(intentSpecOf(mime))
    }

    /** 点「在文件管理器中定位」的那道闸：没有能开目录的应用就退化。 */
    fun locatePlanOf(onDisk: Boolean, folderViewer: Boolean): ArtifactLocatePlan = when {
        !onDisk -> ArtifactLocatePlan.Gone
        folderViewer -> ArtifactLocatePlan.OpenFolder
        else -> ArtifactLocatePlan.ShowPath
    }
}

/** Intent 规格（纯数据，JVM 可断言）。 */
internal data class ArtifactOpenIntent(
    val action: String,
    val scheme: String,
    val authoritySuffix: String,
    val mime: String,
    val grantRead: Boolean,
    val withClipData: Boolean,
)

internal sealed interface ArtifactOpenPlan {
    data class Ready(val spec: ArtifactOpenIntent) : ArtifactOpenPlan
    object Gone : ArtifactOpenPlan
    object Unsupported : ArtifactOpenPlan
}

internal enum class ArtifactLocatePlan { Gone, OpenFolder, ShowPath }
