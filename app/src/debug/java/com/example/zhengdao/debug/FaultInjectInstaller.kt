// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百八十四批 · 故障注入的**进程入口**（只在 debug 清单里注册）。
//
// 为什么用 ContentProvider 而不是 Application：`ZhengdaoApp` 在 main 源集，它一旦
// 引用 `com.example.zhengdao.debug.*`，release 就编不过（那正是我们要的"结构上不可能"）；
// 而 debug 清单里的 provider 只在开发包存在——正式包没有这条注册、也没有这个类。
// provider 的 onCreate 在进程起来时跑一次（早于 Application.onCreate），装的就是
// [FaultInjection.install] 这一行：把带热重载的注入器挂到 main 的那根针上。
//
// 不解析任何外部 intent/uri、exported=false：它不是对外入口，只是"进程启动时跑一行"。
package com.example.zhengdao.debug

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/** debug 专用安装器（正式包不存在）。 */
class FaultInjectInstaller : ContentProvider() {

    override fun onCreate(): Boolean {
        context?.let { FaultInjection.install(it) }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
