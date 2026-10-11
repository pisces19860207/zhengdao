// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.terminal

import com.example.zhengdao.rootfs.GithubMirror
import java.io.File

/**
 * 终端 git 加速 + 加固（2026-10-11，用户：「能不能给终端里的 git 加下速和加固一下」）。
 *
 * ## 为什么要重做（旧写法的问题）
 * `terminal/ProotLauncher.kt:355-367`（旧）只在 `/root/.gitconfig` **不存在**时写一段
 * ```
 * [url "https://gh-proxy.com/https://github.com/"]
 *     insteadOf = https://github.com/
 * ```
 * 两个毛病：
 * 1. **只判存在**：镜像表以后再改，老环境永远停在旧的 gh-proxy.com 上（与 E-085 修过的
 *    zz-npm-registry / zz-cursor-bar 是同一个坑）；
 * 2. **全局 `insteadOf` 连 `push` 一起改道**：写操作会被送到第三方镜像 —— 凭据/令牌外泄的风险，
 *    且镜像普遍不收 push，用户只会看到一个莫名其妙的失败。**这是本次"加固"要修的主项。**
 *
 * ## 现在的做法
 * - `.gitconfig` 只放**加固键**（HTTP/1.1、低速断开、协议 v2、并行取数、对象校验），
 *   **绝不写 `insteadOf`**；写入按内容比对、幂等，并会清掉历史遗留的镜像段。
 * - 镜像改由 `/usr/local/bin/git` **包装器**在每次调用内临时注入
 *   （`GIT_CONFIG_COUNT`/`GIT_CONFIG_KEY_0`/`GIT_CONFIG_VALUE_0`，git ≥ 2.31）：
 *   只对 `clone|fetch|pull|ls-remote|submodule|archive` 生效，**`push` 等写操作一律 exec 真实 git**，
 *   进程退出即失效、不落盘、不影响用户自己的 git 配置。
 * - 源顺序 = 直连 → [GithubMirror.GIT_CHAIN]（实测支持 git 智能 HTTP 的源）× 重试次数；
 *   任一源成功即结束。`ZZ_GIT_DIRECT=1` 只直连、`ZZ_NO_GIT_WRAPPER=1` 完全绕过包装器。
 * - 每次尝试都写 `/root/.zz-git-accel.log`（只留最近 200 行），失败时打印出来给用户看。
 */
object GitAccel {

    const val BEGIN = "# >>> zhengdao git accel >>>"
    const val END = "# <<< zhengdao git accel <<<"

    /** 包装器自识别标记：文件里没有它，就**不覆盖**（可能是用户自己装的 git）。 */
    const val WRAPPER_MARK = "# zhengdao git accel wrapper"

    const val WRAPPER_REL_PATH = "usr/local/bin/git"
    const val LOG_PATH = "/root/.zz-git-accel.log"

    private const val MIRROR_RETRIES_DEFAULT = 2

    /** 历史/在用的镜像前缀：合并配置时要清掉它们留下的 `insteadOf` 段（用户自己的镜像不动）。 */
    private val KNOWN_MIRROR_PREFIXES =
        (GithubMirror.DOWNLOAD_CHAIN + GithubMirror.GIT_CHAIN + listOf(
            "https://hk.gh-proxy.com/",
            "https://hub.gitmirror.com/",
            "https://gh.llkk.cc/",
        )).distinct()

    /**
     * 托管段正文（**只有加固键，没有 insteadOf**）。
     *
     * 每个键都是"要么防卡死、要么防损坏"，且都不改变克隆结果：
     * - `http.version = HTTP/1.1`：走代理时 HTTP/2 复用容易被中间设备掐断（旧版 curl/git 的经典
     *   症状是 `RPC failed; curl 92 HTTP/2 stream … not closed cleanly`）；
     * - `http.lowSpeedLimit/Time`：低于 1 KB/s 持续 30s 就断开 —— 让"僵死的连接"尽早变成失败，
     *   好让包装器换源（否则 hang 到 TCP 超时，Hermes 安装的 clone 就卡在这儿）；
     * - `http.postBuffer`：大 push 时避免 `HTTP 411`（400MB）；
     * - `protocol.version = 2` + `fetch.parallel` + `submodule.fetchJobs`：v2 协议 + 并行取数，
     *   实测能显著减少握手往返；
     * - `fetch/transfer.fsckObjects = true` +（注）**对象完整性校验**：拉下来的对象逐个校验 SHA，
     *   镜像被投毒/链路损坏会在这里炸，而不是几个月后在使用中炸。代价是 clone 时多一遍哈希计算。
     *   嫌慢可以删掉这一段——不影响功能。
     */
    private fun managedSection(): String = """
        |$BEGIN
        |# 本段由证道 App 生成（2026-10-11），请勿手改：每次开终端会按内容比对重写。
        |# 要恢复原状：删掉本段（含 BEGIN/END 两行）与 /usr/local/bin/git 即可。
        |# 这里**故意没有** url.insteadOf —— 镜像由 /usr/local/bin/git 包装器只对
        |# clone/fetch/pull/ls-remote/submodule/archive 临时注入，push 永远直连 github.com。
        |[http]
        |	version = HTTP/1.1
        |	lowSpeedLimit = 1000
        |	lowSpeedTime = 30
        |	postBuffer = 524288000
        |[protocol]
        |	version = 2
        |[fetch]
        |	parallel = 4
        |	fsckObjects = true
        |[transfer]
        |	fsckObjects = true
        |[submodule]
        |	fetchJobs = 4
        |$END
    """.trimMargin()

    /**
     * 把托管段合进既有 `.gitconfig`：**返回 null 表示"无需写盘"**（内容已一致 ⇒ 幂等）。
     *
     * 会顺手清掉两类东西：
     * 1. 本对象上次写的托管段（BEGIN…END）；
     * 2. **历史遗留的镜像段**：`[url "https://<已知镜像>/…"]` 且段内 `insteadOf = https://github.com/`
     *    —— 就是旧 ProotLauncher 写的那种。只删"确实指向 github.com 的已知镜像"，用户自己配的
     *    镜像段（别的 URL、别的 insteadOf）原样保留。
     */
    fun mergeConfig(existing: String?): String? {
        val original = (existing ?: "").replace("\r\n", "\n")
        val kept = dropLegacyMirrorSections(dropManagedSection(original))
        val head = if (kept.isBlank()) "" else kept.trimEnd() + "\n"
        val merged = "$head${managedSection()}\n"
        return if (merged == original) null else merged
    }

    private fun dropManagedSection(text: String): String {
        if (!text.contains(BEGIN)) return text
        val out = StringBuilder()
        var skipping = false
        for (line in text.lines()) {
            val t = line.trim()
            when {
                t == BEGIN -> skipping = true
                t == END -> skipping = false
                !skipping -> out.append(line).append('\n')
            }
        }
        return out.toString()
    }

    private fun dropLegacyMirrorSections(text: String): String {
        val lines = text.lines()
        val out = StringBuilder()
        var i = 0
        while (i < lines.size) {
            val isMirrorHeader = mirrorPrefixOf(lines[i].trim()) != null
            if (isMirrorHeader) {
                var j = i + 1
                var hasGithubInsteadOf = false
                while (j < lines.size && !lines[j].trim().startsWith("[")) {
                    if (lines[j].replace(" ", "").trim().equals("insteadOf=https://github.com/", true)) {
                        hasGithubInsteadOf = true
                    }
                    j++
                }
                if (hasGithubInsteadOf) {
                    i = j
                    continue
                }
            }
            out.append(lines[i]).append('\n')
            i++
        }
        return out.toString()
    }

    /** `[url "https://gh-proxy.com/https://github.com/"]` → `https://gh-proxy.com/`；否则 null。 */
    private fun mirrorPrefixOf(header: String): String? {
        if (!header.startsWith("[url ", ignoreCase = true) || !header.endsWith("]")) return null
        val start = header.indexOf('"')
        val end = header.lastIndexOf('"')
        if (start < 0 || end <= start) return null
        val url = header.substring(start + 1, end)
        return KNOWN_MIRROR_PREFIXES.firstOrNull { url.startsWith(it) }
    }

    /**
     * `/usr/local/bin/git` 包装器正文。
     *
     * ⚠️ 正文里的 `§` 是 shell 美元号的占位符（Kotlin 会拿 `$` 当字符串模板），落盘前替换成 `$`。
     */
    fun wrapperScript(): String {
        val mirrors = GithubMirror.GIT_CHAIN.joinToString(" ")
        return """
        |#!/bin/sh
        |$WRAPPER_MARK
        |# 由证道 App 生成（2026-10-11）。删掉本文件即恢复系统 git；正文每次开终端按内容比对重写。
        |# 只给「取数」类子命令临时注入国内镜像；push 等写操作**永远**直连真实 git。
        |# 开关：ZZ_NO_GIT_WRAPPER=1 完全绕过；ZZ_GIT_DIRECT=1 只直连；ZZ_GIT_MIRROR_RETRIES=N 改重试次数。
        |
        |REAL=""
        |for cand in /usr/bin/git /bin/git /usr/lib/git-core/git /usr/local/libexec/git-core/git; do
        |  if [ -x "§cand" ]; then REAL="§cand"; break; fi
        |done
        |if [ -z "§REAL" ]; then
        |  echo "zz-git: 找不到系统 git（/usr/bin/git）—— 本包装器只应存在于证道的 proot 客户机内" >&2
        |  exit 127
        |fi
        |
        |LOG="$LOG_PATH"
        |zz_log() {
        |  # 记不上账不算错误（home 没挂上时 shell 会往 stderr 喷脏话，所以先判可写）
        |  [ -w /root ] || return 0
        |  printf '%s %s\n' "§(date '+%F %T' 2>/dev/null || echo '-')" "§1" >>"§LOG" 2>/dev/null || true
        |}
        |
        |if [ -n "§{ZZ_NO_GIT_WRAPPER:-}" ]; then exec "§REAL" "§@"; fi
        |
        |# 找子命令：跳过全局选项，以及会吃掉下一个参数的选项（-C <path> / -c <k=v> / --git-dir <p>）。
        |# ⚠️ 子命令是**第一个非选项参数**——全局选项按 git 的规矩都排在子命令之前，所以
        |#    `git clone --branch main URL` 里的 `main` 不会把子命令认错（那时 CMD 已是 clone）。
        |CMD=""
        |EXTRA=""
        |SKIP=0
        |for arg in "§@"; do
        |  if [ "§SKIP" = 1 ]; then SKIP=0; continue; fi
        |  case "§arg" in
        |    -C|-c|--git-dir|--work-tree|--namespace|--exec-path|--config) SKIP=1; continue ;;
        |    --*=*|-C?*) continue ;;
        |    -*) continue ;;
        |  esac
        |  if [ -z "§CMD" ]; then CMD="§arg"; else EXTRA="§EXTRA §arg"; fi
        |done
        |
        |case "§CMD" in
        |  clone|fetch|pull|ls-remote|submodule|archive) : ;;
        |  *) exec "§REAL" "§@" ;;
        |esac
        |
        |if [ -n "§{ZZ_GIT_DIRECT:-}" ]; then
        |  zz_log "direct(forced) §CMD"
        |  exec "§REAL" "§@"
        |fi
        |
        |# clone 的目标目录（`git clone <repo> [<dir>]`）：只用于"失败后还要不要继续换源"的判断。
        |if [ "§CMD" = clone ]; then
        |  if [ -z "§EXTRA" ]; then
        |    DIR="§{CMD##*/}"
        |    EXTRA="§{DIR%.git}"
        |  fi
        |else
        |  EXTRA=""
        |fi
        |
        |MIRRORS="$mirrors"
        |RETRIES="§{ZZ_GIT_MIRROR_RETRIES:-$MIRROR_RETRIES_DEFAULT}"
        |LIST="-"
        |for m in §MIRRORS; do
        |  n=0
        |  while [ "§n" -lt "§RETRIES" ]; do
        |    LIST="§LIST §m"
        |    n=§((n+1))
        |  done
        |done
        |
        |CODE=1
        |for src in §LIST; do
        |  if [ "§src" = "-" ]; then
        |    unset GIT_CONFIG_COUNT GIT_CONFIG_KEY_0 GIT_CONFIG_VALUE_0
        |    zz_log "direct: §CMD"
        |  else
        |    GIT_CONFIG_COUNT=1
        |    GIT_CONFIG_KEY_0="url.§{src}https://github.com/.insteadOf"
        |    GIT_CONFIG_VALUE_0="https://github.com/"
        |    export GIT_CONFIG_COUNT GIT_CONFIG_KEY_0 GIT_CONFIG_VALUE_0
        |    zz_log "mirror §{src}: §CMD"
        |  fi
        |  "§REAL" "§@"
        |  CODE=§?
        |  if [ "§CODE" = 0 ]; then exit 0; fi
        |  zz_log "fail(§CODE) §{src}"
        |  if [ "§CMD" = clone ] && [ -n "§EXTRA" ]; then
        |    for d in §EXTRA; do
        |      if [ -d "§d" ] && [ -n "§(ls -A "§d" 2>/dev/null)" ]; then
        |        echo "zz-git: clone 失败，且 §d 已存在且非空 —— 不再换源（避免动到已有内容）。" >&2
        |        echo "        清掉它或换目标目录后重试；ZZ_GIT_DIRECT=1 可只走直连。" >&2
        |        exit "§CODE"
        |      fi
        |    done
        |  fi
        |done
        |
        |echo "zz-git: 直连与镜像都失败了（最后退出码 §CODE）。" >&2
        |echo "        看尝试记录：tail -n 20 §LOG" >&2
        |echo "        只走直连：ZZ_GIT_DIRECT=1 git §CMD …" >&2
        |exit "§CODE"
    """.trimMargin().replace('§', '$')
    }

    /**
     * 落盘：`<homeDir>/.gitconfig`（按内容比对）＋ `<rootfsDir>/usr/local/bin/git`（包装器）。
     * 返回给人看的动作列表（供调用方日志/单测断言）；幂等时返回空列表。
     */
    fun apply(rootfsDir: File, homeDir: File): List<String> {
        val actions = mutableListOf<String>()

        val gitconfig = File(homeDir, ".gitconfig")
        val existing = if (gitconfig.isFile) runCatching { gitconfig.readText() }.getOrNull() else null
        val merged = mergeConfig(existing)
        if (merged != null) {
            gitconfig.parentFile?.mkdirs()
            gitconfig.writeText(merged)
            actions += if (existing == null) {
                "git 加固配置已写入（${gitconfig.path}）"
            } else {
                "git 配置已更新（清掉旧镜像段 + 加固键，${gitconfig.path}）"
            }
        }

        val wrapper = File(rootfsDir, WRAPPER_REL_PATH)
        val want = wrapperScript()
        val current = if (wrapper.isFile) runCatching { wrapper.readText() }.getOrNull() else null
        when {
            current == null -> {
                if (wrapper.parentFile?.let { it.isDirectory || it.mkdirs() } == true) {
                    wrapper.writeText(want)
                    wrapper.setExecutable(true, false)
                    actions += "git 加速包装器已就绪（/${WRAPPER_REL_PATH}）"
                }
            }
            !current.contains(WRAPPER_MARK) ->
                actions += "警告：/${WRAPPER_REL_PATH} 已存在且不是证道的包装器，拒绝覆盖"
            current != want || !wrapper.canExecute() -> {
                wrapper.writeText(want)
                wrapper.setExecutable(true, false)
                actions += "git 加速包装器已更新（/${WRAPPER_REL_PATH}）"
            }
        }
        return actions
    }
}
