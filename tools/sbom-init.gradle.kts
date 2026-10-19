// sbom-init.gradle.kts —— SBOM 数据源：把选定 configuration 的**解析闭包**导成 JSON。
//
// 为什么用 init script 而不是改 build.gradle.kts
// ----------------------------------------------
// 第四十三批（供应链可见）红线：只许改 tools/、.github/workflows/、docs/，
// **不许改业务代码与构建脚本、不许升级 AGP/Kotlin、不许引第三方 Gradle 插件**。
// init script 用 `-I` 注入，构建脚本零改动；任务只在显式调用 `:sbomDump` 时存在，
// 对 assembleDebug / test 等既有任务的配置与执行**零影响**（不注册、不改配置）。
//
// 它做什么
// --------
// 对每个 (project, configuration) 解析一次 `incoming.resolutionResult`，
// 把闭包里每个构件（含传递依赖）的 group/name/version 与依赖边导出成 JSON：
//   {
//     "generator": "tools/sbom-init.gradle.kts",
//     "gradleVersion": "9.6.0",
//     "sections": {
//       "release": {"project": ":app", "configuration": "releaseRuntimeClasspath",
//                   "components": [{"group": "...", "name": "...", "version": "..."}],
//                   "edges": [["root", "g:n:v"], ["g:n:v", "g:n:v"], ...],
//                   "componentCount": N},
//       ... debug / test / androidTest / benchmark / macrobenchmark
//     }
//   }
// 主 SBOM 只取 release 节（真正打进 release APK 的运行时闭包）；
// debug/test/androidTest/macrobenchmark 单列，避免构建工具链与测试依赖混进主清单。
//
// 兼容性说明
// -----------
// * configuration cache：调用方统一加 `--no-configuration-cache`（见 tools/sbom.py），
//   本脚本不依赖任何缓存行为；
// * `DependencyResult.getRequested()` 实测（Gradle 9.6）返回
//   `DefaultModuleComponentSelector`：**没有 getName()**，group 走 getGroup()、
//   名字走 getModuleIdentifier().getName()、版本走 getVersion()（BOM 托管时为 null，
//   按 group:name 匹配闭包内构件即可 —— 同一次解析里同一 group:name 只会有一个版本）。
//   另保留两个历史命名候选，换 Gradle 版本时不至于静默丢边。
//
// 用法：`./gradlew -I tools/sbom-init.gradle.kts :sbomDump -PsbomOut=build/sbom/resolved.json`
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult

/** 构件的稳定标识：Maven 构件 → group:name:version；工程依赖 → null（不进 SBOM）。 */
fun gavOf(component: ResolvedComponentResult): String? {
    val id = component.id
    return if (id is ModuleComponentIdentifier) "${id.group}:${id.module}:${id.version}" else null
}

/** 从请求边取值：ModuleComponentSelector 走 getGroup()/getModuleIdentifier().getName()/getVersion()。 */
fun requestedGav(requested: Any): String? {
    val candidates = listOf<(Any) -> String?>({
        // Gradle 9（实测 org.gradle.internal.component.external.model.DefaultModuleComponentSelector）：
        // 没有 getName()，名字要从 ModuleIdentifier 取；version 可能为 null（BOM 托管）。
        val group = it.javaClass.getMethod("getGroup").invoke(it) as String?
            ?: return@listOf null
        val moduleId = it.javaClass.getMethod("getModuleIdentifier").invoke(it)
            ?: return@listOf null
        val name = moduleId.javaClass.getMethod("getName").invoke(moduleId) as String?
            ?: return@listOf null
        val version = it.javaClass.getMethod("getVersion").invoke(it) as String?
        if (version != null) "$group:$name:$version" else "$group:$name"
    }, {
        // 旧命名的 Gradle：ResolvedDependency（module* 前缀）
        var g: String? = null
        var n: String? = null
        for (name in listOf("getModuleGroup", "getModuleName", "getModuleVersion")) {
            val value = it.javaClass.getMethod(name).invoke(it) as String?
            if (name.endsWith("Group")) g = value else n = value
        }
        if (g != null && n != null) "$g:$n" else null
    }, {
        // 另一种历史形态：ModuleVersionSelector（group/name/version）
        val g = it.javaClass.getMethod("getGroup").invoke(it) as String?
        val n = it.javaClass.getMethod("getName").invoke(it) as String?
        val v = it.javaClass.getMethod("getVersion").invoke(it) as String?
        if (g != null && n != null) "$g:$n" + (if (v != null) ":$v" else "") else null
    })
    for (candidate in candidates) {
        try {
            candidate(requested)?.let { return it }
        } catch (_: Exception) {
            // 换下一个候选
        }
    }
    return null
}

val sections = linkedMapOf(
    "release" to listOf(":app" to "releaseRuntimeClasspath"),
    "debug" to listOf(":app" to "debugRuntimeClasspath"),
    "test" to listOf(":app" to "debugUnitTestRuntimeClasspath"),
    "androidTest" to listOf(":app" to "debugAndroidTestRuntimeClasspath"),
    "benchmark" to listOf(":app" to "benchmarkRuntimeClasspath"),
    "macrobenchmark" to listOf(":macrobenchmark" to "benchmarkRuntimeClasspath"),
)

gradle.projectsEvaluated {
    val outRel = providers.gradleProperty("sbomOut").orElse("build/sbom/resolved.json").get()
    val sectionsOut = LinkedHashMap<String, Any>()

    rootProject.tasks.register("sbomDump") {
        group = "supply chain"
        description = "导出选定 configuration 的解析闭包为 JSON（SBOM 数据源，只读）"
        doLast {
            for ((section, targets) in sections) {
                val (projectPath, configName) = targets.first()
                val project = rootProject.findProject(projectPath)
                if (project == null) {
                    sectionsOut[section] = mapOf("error" to "找不到工程 $projectPath")
                    continue
                }
                val configuration = project.configurations.findByName(configName)
                if (configuration == null) {
                    sectionsOut[section] = mapOf(
                        "project" to projectPath,
                        "configuration" to configName,
                        "error" to "该工程没有名为 $configName 的 configuration",
                    )
                    continue
                }
                val result = configuration.incoming.resolutionResult
                val components = linkedMapOf<String, Map<String, String>>()
                for (component in result.allComponents) {
                    val gav = gavOf(component) ?: continue
                    val parts = gav.split(":")
                    components[gav] = mapOf(
                        "group" to parts[0],
                        "name" to parts[1],
                        "version" to parts.getOrElse(2) { "" },
                    )
                }
                // group:name → gav（一次解析内唯一）
                val byGaN = components.keys.associateBy { it.substringBeforeLast(":") }
                val edgeSet = mutableSetOf<Pair<String, String>>()
                for (component in result.allComponents) {
                    val fromGav = gavOf(component) ?: "root" // 根构件（本工程自身）
                    for (dependency in component.dependencies) {
                        val requestedGav = requestedGav(dependency.requested) ?: continue
                        val toGav = byGaN[requestedGav.substringBeforeLast(":")] ?: requestedGav
                        edgeSet += fromGav to toGav
                    }
                }
                val edgeList = edgeSet.sortedWith(compareBy({ it.first }, { it.second }))
                    .map { listOf(it.first, it.second) }
                val directFromRoot = edgeList.filter { it[0] == "root" }.map { it[1] }.sorted()
                sectionsOut[section] = mapOf(
                    "project" to projectPath,
                    "configuration" to configName,
                    "componentCount" to components.size,
                    "components" to components.keys.sorted().map { components.getValue(it) },
                    "edges" to edgeList,
                    "directFromRoot" to directFromRoot,
                )
            }
            val payload = mapOf(
                "generator" to "tools/sbom-init.gradle.kts",
                "gradleVersion" to gradle.gradleVersion,
                "sections" to sectionsOut,
            )
            val outFile = rootProject.file(outRel)
            outFile.parentFile.mkdirs()
            outFile.writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(payload)))
            val releaseCount = (sectionsOut["release"] as? Map<*, *>)?.get("componentCount")
            logger.lifecycle("[sbomDump] 写入 $outRel（release 节 $releaseCount 个构件）")
        }
    }
}
