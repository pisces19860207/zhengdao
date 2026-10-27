// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao

import java.io.File

/**
 * 门禁测试的**源码根解析**（第二百四十一批新增）。
 *
 * 背景：本仓的门禁测试（"某文件里必须还有某行"这类）都是**读源码文本**断言，路径写死成
 * `src/main/java/com/example/zhengdao/...`（Gradle 单测的工作目录是模块目录 `app/`）。
 * 第二百四十一批把终端内核（`com.termux` 包、`terminal` 包、`rootfs` 包、`rust` 包）搬进了
 * `:terminal-core`，那些文件就不在 `app/src/main/java` 下了 —— 43 条门禁当场"找不到文件"。
 *
 * 处置原则：**门禁的判据一条不改，只改"去哪儿找文件"**。判据本身（某行在不在、某模式有没有）
 * 原样保留，所以搬家前后它们仍然是同一批断言，只是多了一个候选根。
 *
 * 找不到时抛的错里会列出全部试过的路径 —— 下次再搬模块，报错会直接告诉你去哪儿加根。
 */
object SrcRoots {

    /** Java 源码根候选（按优先级）。cwd 可能是 `app/`，也可能是仓根。 */
    fun javaRoots(): List<File> = listOf(
        File("src/main/java"),                       // cwd = app/
        File("../core/src/main/java"),               // 共享层（第二百四十批）
        File("app/src/main/java"),                   // cwd = 仓根
        File("core/src/main/java"),
    )

    /**
     * 把测试里原来写的路径（`src/main/java/...`、`app/src/main/java/...`、`app/...`）
     * 展开成一组候选 File，顺序即优先级。
     *
     * 非 Java 源码路径（`src/main/AndroidManifest.xml`、`build.gradle.kts`）**不套 java 根**，
     * 按模块根展开 —— 它们只可能属于 :app 或 :core 的模块根。
     */
    fun candidates(path: String): List<File> {
        val p = path.replace('\\', '/')
        if (p.startsWith("..") || File(p).isAbsolute) return listOf(File(path))
        val rel = when {
            p.startsWith("app/src/main/java/") -> p.removePrefix("app/src/main/java/")
            p.startsWith("src/main/java/") -> p.removePrefix("src/main/java/")
            p.startsWith("app/") -> return listOf(File(path), File("../core/${p.removePrefix("app/")}"))
            else -> null
        }
        if (rel == null) {
            // 模块根相对路径（清单 / 构建脚本 / 资源）
            return listOf(File(path), File("../core/$p"))
        }
        return javaRoots().map { File(it, rel) }
    }

    /** 取第一个存在的候选；都不存在时抛错（列出全部候选，便于定位）。 */
    fun find(path: String): File {
        val cs = candidates(path)
        return cs.firstOrNull { it.isFile } ?: cs.firstOrNull { it.exists() }
            ?: error("找不到源码 $path（试过：${cs.joinToString { it.path }}）")
    }
}
