// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 依据的公开标准：JSON（RFC 8259）——读取器只实现对象/数组/字符串/数字/字面量五类值。
package com.example.zhengdao.rootfs

import android.util.Log
import com.example.zhengdao.ui.Ed25519Verify
import java.util.Base64

/**
 * 索引里的增量补丁引用（协议 §4 的 `patch` 对象）。
 * @param from 本补丁适用的基线 env id（必须等于本地已装 env，否则只能全量）
 * @param size 补丁包字节数（UI 用来估增量下载体积）
 */
data class PatchRef(val from: String, val url: String, val sha256: String, val size: Long)

/**
 * 环境索引（协议 §4，滚动 Release `latest` 里的 `rootfs-index.json`）。
 *
 * @param schema 索引格式版本（缺失记 0；当前为 1）
 * @param distro 如 `debian-13.7`
 * @param version 人读版本号，如 `13.7`（**检查更新按 [env] 比对，不再拿它比**）
 * @param env 内容指纹（16 位十六进制）；缺失为 null
 * @param url 全量包地址
 * @param sha256 全量包 SHA256（老格式可能为空串 ⇒ 调用方按"无校验值"处理）
 * @param size 全量包字节数（缺失/非法记 0 ⇒ UI 显示"大小未知"）
 * @param manifestUrl 清单地址（可选）
 * @param patch 增量补丁（可为 null/缺省 ⇒ 只能全量）
 */
data class RootfsIndex(
    val schema: Int,
    val distro: String,
    val version: String,
    val env: String?,
    val url: String,
    val sha256: String,
    val size: Long,
    val manifestUrl: String?,
    val patch: PatchRef?,
)

/**
 * 索引解析（**纯函数**，可在 JVM 单测里直接跑）。
 *
 * 为什么不用 `org.json`：它在本项目的非 Robolectric 单测里会抛 `not mocked`，
 * 而"索引怎么解析"恰恰是最该被单测锁死的逻辑（字段缺失、老格式、半截下载）。
 *
 * 容错策略（**永不抛异常给 UI**，拿不准就返回 null 让调用方走全量/降级）：
 * - 整段必须是**一个括号配平的 JSON 对象**（尾部除空白外不能有别的字符）——
 *   半截下载的索引不可用，直接判 null；
 * - `url` 为空/缺失 ⇒ null（没有可安装目标的索引没有意义）；
 * - 其余字段缺失/类型不对 ⇒ 取默认值（schema=0、distro=""/version=""、sha256=""、size=0、env=null）；
 * - `patch` 必须 `from`/`url`/`sha256` 三者齐全才构造，否则 null（没有校验值的补丁绝不装）。
 */
object RootfsIndexParser {

    fun parse(json: String): RootfsIndex? {
        val root = runCatching { JsonReader(json).readRootObject() }.getOrNull() ?: return null
        val url = root.str("url") ?: return null

        val patchObj = root["patch"] as? Map<*, *>
        val patch = patchObj?.let { p ->
            val from = p.str("from")?.lowercase()
            val purl = p.str("url")
            val psha = p.str("sha256")
            if (from != null && purl != null && psha != null) {
                PatchRef(from = from, url = purl, sha256 = psha, size = p.long("size"))
            } else {
                null
            }
        }

        return RootfsIndex(
            schema = root.int("schema"),
            distro = root.str("distro") ?: "",
            version = root.str("version") ?: "",
            env = root.str("env")?.lowercase(),
            url = url,
            sha256 = root.str("sha256") ?: "",
            size = root.long("size"),
            manifestUrl = root.str("manifestUrl"),
            patch = patch,
        )
    }

    // ── Map 取值助手（缺失/类型不符一律走默认值） ──

    private fun Map<*, *>.str(key: String): String? =
        (this[key] as? String)?.trim()?.takeIf { it.isNotEmpty() }

    private fun Map<*, *>.long(key: String): Long = when (val v = this[key]) {
        is Number -> v.toLong()
        is String -> v.trim().toLongOrNull() ?: 0L
        else -> 0L
    }

    private fun Map<*, *>.int(key: String): Int = when (val v = this[key]) {
        is Number -> v.toInt()
        is String -> v.trim().toIntOrNull() ?: 0
        else -> 0
    }
}

/**
 * 索引抓取。先试 GitHub 原始地址，再试 gh-proxy 镜像（[RootfsDownloader.withMirrorFallback]）；
 * 全部失败或解析不出来就返回 null，**由调用方决定降级**（设置页保留老的 manifest/Releases 提示路径）。
 */
object RootfsIndexFetcher {

    /** 滚动 Release `latest` 里的固定资产名（协议 §4）。 */
    const val URL =
        "https://github.com/pisces19860207/zhengdao/releases/download/latest/rootfs-index.json"

    /**
     * 索引签名公钥（32 字节 raw 的 base64），固化在 APK 里。
     *
     * 为什么单独一把、而不是复用 `agents.json` 那把：发布主密钥只在本机签名器里、
     * **绝不进 CI**；而索引是**构建产物**，必须由 CI 在构建时签 ⇒ 给它一把
     * **只用于签索引**的密钥：公钥进 APK，私钥只以 GitHub Secret 形式存在。
     * 代价是**换密钥要发一次新 App**（公钥在里面）——这是这条链的固有成本，写在这里免得将来踩。
     *
     * 生成与签发：`python tools/sign-rootfs-index.py --genkey`；签发默认作用于 `rootfs-out/rootfs-index.json`。
     */
    const val INDEX_SIGNING_PUBKEY_B64 = "o504TEF3eRPtLicRzp7qBF5AJyTam4voaO89tGYgxxk="

    /** 签名边车地址：索引同目录下的同名 `.sig`（与 `agents.json.sig` 同一约定）。 */
    fun signatureUrl(indexUrl: String): String = "$indexUrl.sig"

    /**
     * 验签（纯函数，JVM 可测）。
     *
     * ⚠️ 必须对**原始字节**验：签名覆盖文件全部字节，先 trim 再验恒败
     * （2026-10-04 在 manifest 上实测踩过，恰好差末尾一个换行）。
     *
     * 计算走 `ui/Ed25519Verify`（Rust 核心优先 + 平台对拍，见 ERRATA E-051）——
     * 与 agents 清单**同一条入口**：此前索引这条直连平台实现，等于同一信任链上
     * 有两种验证强度，而 Rust 侧的真实签名输入只覆盖了清单。
     *
     * @param publicKeyBase64 仅测试会传入别的公钥（错钥方向要能覆盖）；生产走默认值。
     */
    fun verifySignature(
        indexBytes: ByteArray,
        signatureBase64: String,
        publicKeyBase64: String = INDEX_SIGNING_PUBKEY_B64,
    ): Boolean = try {
        val sig = Base64.getDecoder().decode(signatureBase64.trim())
        val pub = Base64.getDecoder().decode(publicKeyBase64)
        check(pub.size == 32) { "索引公钥配置非法（长度 ${pub.size} ≠ 32）" }
        Ed25519Verify.verify(pub, sig, indexBytes, "索引")
    } catch (t: Throwable) {
        Log.w("RootfsIndexFetcher", "索引验签异常（视为失败）: ${t.message}")
        false
    }

    /**
     * 抓取索引：**先验签、后解析**（顺序不可颠倒）。
     *
     * 为什么索引需要签名：索引里的 sha256 与被校验的包在**同一个 Release** 里，
     * 所以"能改包的人也能顺手改哈希"——sha256 只能防传输损坏，防不住发布端被篡改。
     * 只有"发布方私钥签名 + App 内置公钥验签"才能让这种情况变成**装不上**而不是照单全收。
     * 这与 `agents.json` 的处理一致（那边是"验签不过 = 整份拒绝"）。
     *
     * ⚠️ 三种失败必须**看得见区分**，不能都表现成"没查到更新"：
     * ① 拿不到（网络/源不可达）→ 沿用既有降级；
     * ② 拿到但**没有签名** → 拒绝使用（未验证的 sha256 不能当校验值）；
     * ③ 有签名但**验不过** → 拒绝使用 + 明确留痕（这是安全事件）。
     */
    fun fetch(): RootfsIndex? {
        var sawUnverified = false
        for (u in RootfsDownloader.withMirrorFallback(URL)) {
            val bytes = runCatching { RootfsDownloader.fetchBytes(u) }.getOrNull() ?: continue
            val sig = runCatching { RootfsDownloader.fetchBytes(signatureUrl(u)) }
                .getOrNull()?.let { String(it, Charsets.UTF_8) }
            if (sig.isNullOrBlank()) {
                sawUnverified = true
                continue
            }
            if (!verifySignature(bytes, sig)) {
                Log.w("RootfsIndexFetcher", "索引验签失败（已试全部源），不使用该索引")
                RunLog.log(
                    "环境索引验签失败：发布源被篡改，或签名密钥已轮换。已拒绝使用该索引" +
                        "（从本地文件安装环境不受影响）",
                )
                return null
            }
            val parsed = runCatching {
                RootfsIndexParser.parse(String(bytes, Charsets.UTF_8))
            }.getOrNull()
            if (parsed != null) return parsed
        }
        Log.w(
            "RootfsIndexFetcher",
            if (sawUnverified) "索引不可信（源上只有未签名版本），调用方按老路径降级"
            else "索引抓取失败（已试全部源），调用方按老路径降级",
        )
        return null
    }
}

/**
 * 极简 JSON 读取器：只认 RFC 8259 的五类值，**坏输入抛异常**（由 [RootfsIndexParser] 统一兜成 null）。
 * 对象落成 `Map<String, Any?>`、数组落成 `List<Any?>`，数字按有无小数点/指数落成 Long 或 Double。
 */
private class JsonReader(private val s: String) {

    private var i = 0
    private var depth = 0

    /** 读**整段**：必须是一个对象，且尾部只有空白（半截/多余内容都算坏输入）。 */
    fun readRootObject(): Map<*, *> {
        val v = readValue()
        skipWs()
        if (i < s.length) throw bad("JSON 尾部有多余内容")
        if (v !is Map<*, *>) throw bad("JSON 顶层不是对象")
        return v
    }

    private fun readValue(): Any? {
        skipWs()
        if (i >= s.length) throw bad("JSON 意外结束")
        return when (val c = s[i]) {
            '{' -> readObject()
            '[' -> readArray()
            '"' -> readString()
            't' -> { expect("true"); true }
            'f' -> { expect("false"); false }
            'n' -> { expect("null"); null }
            else -> if (c == '-' || c in '0'..'9') readNumber() else throw bad("非法字符 '$c'")
        }
    }

    private fun readObject(): Map<String, Any?> {
        enter()
        i++ // '{'
        val map = LinkedHashMap<String, Any?>()
        skipWs()
        if (peek() == '}') { i++; leave(); return map }
        while (true) {
            skipWs()
            if (peek() != '"') throw bad("对象的键必须是字符串")
            val key = readString()
            skipWs()
            if (peek() != ':') throw bad("对象键后缺少 ':'")
            i++
            map[key] = readValue()
            skipWs()
            when (peek()) {
                ',' -> i++
                '}' -> { i++; leave(); return map }
                else -> throw bad("对象里缺少 ',' 或 '}'")
            }
        }
    }

    private fun readArray(): List<Any?> {
        enter()
        i++ // '['
        val list = ArrayList<Any?>()
        skipWs()
        if (peek() == ']') { i++; leave(); return list }
        while (true) {
            list.add(readValue())
            skipWs()
            when (peek()) {
                ',' -> i++
                ']' -> { i++; leave(); return list }
                else -> throw bad("数组里缺少 ',' 或 ']'")
            }
        }
    }

    private fun readString(): String {
        i++ // '"'
        val sb = StringBuilder()
        while (true) {
            if (i >= s.length) throw bad("字符串未闭合")
            when (val c = s[i++]) {
                '"' -> return sb.toString()
                '\\' -> {
                    if (i >= s.length) throw bad("转义未完成")
                    when (val e = s[i++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (i + 4 > s.length) throw bad("\\u 转义不完整")
                            val hex = s.substring(i, i + 4)
                            i += 4
                            sb.append((hex.toIntOrNull(16) ?: throw bad("\\u 转义非法: $hex")).toChar())
                        }
                        else -> throw bad("未知转义 '\\$e'")
                    }
                }
                else -> sb.append(c)
            }
        }
    }

    private fun readNumber(): Any {
        val start = i
        if (peek() == '-') i++
        while (i < s.length && s[i] in '0'..'9') i++
        var floating = false
        if (i < s.length && s[i] == '.') {
            floating = true
            i++
            while (i < s.length && s[i] in '0'..'9') i++
        }
        if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
            floating = true
            i++
            if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
            while (i < s.length && s[i] in '0'..'9') i++
        }
        val text = s.substring(start, i)
        return if (floating) {
            text.toDoubleOrNull() ?: throw bad("非法数字: $text")
        } else {
            text.toLongOrNull() ?: text.toDoubleOrNull() ?: throw bad("非法数字: $text")
        }
    }

    private fun expect(literal: String) {
        if (!s.startsWith(literal, i)) throw bad("期望字面量 $literal")
        i += literal.length
    }

    private fun peek(): Char {
        if (i >= s.length) throw bad("JSON 意外结束")
        return s[i]
    }

    private fun skipWs() {
        while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
    }

    /** 嵌套深度上限：网络来的脏数据不该把递归栈打爆。 */
    private fun enter() {
        if (++depth > 32) throw bad("JSON 嵌套过深")
    }

    private fun leave() {
        depth--
    }

    private fun bad(msg: String) = IllegalArgumentException("$msg（位置 $i）")
}
