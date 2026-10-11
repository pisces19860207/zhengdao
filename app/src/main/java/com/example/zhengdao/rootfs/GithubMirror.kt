// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.rootfs

/**
 * GitHub 加速镜像策略（2026-10-11 新增）——**全项目唯一的镜像表**。
 *
 * 为什么要有这个对象：镜像地址此前散在三处（[RootfsDownloader.withMirrorFallback] 里的
 * gh-proxy.com、`oc/OcManager` 两处硬编码的 gh-proxy.com、`terminal/ProotLauncher` 写进
 * `/root/.gitconfig` 的 gh-proxy.com），换一次源要改四个地方，且**从没人做过跨源实测**。
 *
 * 实测（2026-10-11，本机 Windows，`curl --ssl-no-revoke`，同一时刻同一网络，8 MB 真资产 =
 * 本项目 v2.0.11 release apk）：
 *
 * | 源 | 8 MB 下载 | git 智能 HTTP（`info/refs?service=git-upload-pack`） |
 * |---|---|---|
 * | 直连 github | 65 KB/s（25s 未完） | 200 / 262 KB |
 * | **ghfast.top** | **625 KB/s（13.1s 下完）** | **403 text/html（只代理下载，不转 git 协议）** |
 * | gh-proxy.com | 138 KB/s（25s 未完） | 200 / 1.3 MB |
 * | ghproxy.net | 42 KB/s（25s 未完） | 200 / 325 KB |
 * | hk.gh-proxy.com | 39 KB/s | 302 |
 *
 * ⇒ 两条通道**必须分开配**：下载最快的 ghfast.top 恰好不转 git 协议，把它塞进 clone 链只会
 *   直接 403 失败（这是"不实测就会踩"的典型坑）。历史留档可对照
 *   `docs/acceptance/terminal-font-2026-10-10.md:51-59`（当时 ghfast.top 300 KB/s~2 MB/s 被采用）。
 *
 * 两条链都**保留直连在首位**：镜像转发的是同一份字节（下载侧另有 SHA256 校验、git 侧有对象
 * 哈希），"先信主源"的姿态不变 —— 直连慢/不通时才由镜像兜底（慢速兜底见
 * [RootfsDownloader.SpeedGuard]）。
 */
object GithubMirror {

    /**
     * 下载通道（release 资产 / rootfs / opencode 包 / 更新包 ……）：实测首选比现用的
     * gh-proxy.com 快约 4.5×。
     */
    val DOWNLOAD_CHAIN = listOf("https://ghfast.top/", "https://gh-proxy.com/")

    /** git 智能 HTTP 通道（clone / fetch）：只放**实测支持 git 协议**的源。 */
    val GIT_CHAIN = listOf("https://gh-proxy.com/", "https://ghproxy.net/")

    private const val GITHUB_PREFIX = "https://github.com/"

    /**
     * 给一个 github.com 直链补镜像候选（**主源在首位**）。纯函数、无副作用。
     *
     * 已经是某个镜像地址的**不二次套娃**（`ghfast.top/ghfast.top/…` 必然 404，
     * 既有用例见 `UrlTransformTest`）。
     */
    fun candidates(url: String, chain: List<String> = DOWNLOAD_CHAIN): List<String> {
        if (!url.startsWith(GITHUB_PREFIX)) return listOf(url)
        val mirrored = chain.filterNot { url.startsWith(it) }.map { "$it$url" }
        return listOf(url) + mirrored
    }
}
