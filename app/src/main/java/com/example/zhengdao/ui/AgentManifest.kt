// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 依据的公开标准：Ed25519 签名（RFC 8032，java.security.Signature "Ed25519"，
// Android API 33+ Conscrypt 内置；本项目 minSdk 35）。
package com.example.zhengdao.ui

import android.content.Context
import android.util.Log
import com.example.zhengdao.rootfs.RootfsDownloader
import com.example.zhengdao.rust.CoreNative
import java.math.BigInteger
import java.security.MessageDigest

/**
 * Agent 清单 manifest（M3，骨架 §1 简化版）：
 * - 双通道拉取（GitHub raw + jsDelivr CDN），按序尝试；通道本身不可信，签名才是信任根；
 * - 安全闸：先验签后解析，验签不过 = 整份拒绝（防篡改 RCE）；
 * - 双通道都失败 / 验签不过 → 沿用最近一次验签通过的缓存；从无缓存 → 回退出厂版
 *   （AppState 内置清单，与 APK 同源发布）。
 * - 卡片数据合并规则见 [entriesOf]：manifest 按 id 覆盖内置，未知 id 追加。
 */
object AgentManifest {

    private const val TAG = "AgentManifest"
    private const val PREFS = "zhengdao-agents-manifest"
    private const val KEY_BODY = "verified_body"
    private const val KEY_TIME = "verified_at"
    private const val TTL_MS = 6 * 60 * 60 * 1000L

    /**
     * 固化在 APK 里的 Ed25519 公钥（32 字节 raw，base64）。
     * 私钥在发布者本地（仓库外 ~/.zhengdao-keys/），签发流程见 tools/sign-agents-manifest.py。
     * 断言：非占位符、长度必须 32 字节（骨架红线：构建期可断言，这里运行时兜底断言）。
     */
    private val PUBLIC_KEY_B64 = "LW7JtVXGiZGrFBFl8x1wlyPBtBez7tNNWzz4AhSI54Q="

    /** manifest 条目。npmPackage 非空时：已装版本从 rootfs 的 node_modules package.json
     *  探测，最新版从 npm registry 查询 → 卡片可显示「可更新」。 */
    data class Entry(
        val id: String,
        val name: String,
        val desc: String,
        val launchCmd: String,
        val installCmd: String,
        val npmPackage: String? = null,
        val uninstallCmd: String? = null, // P3：卸载命令（缺省 = 无卸载能力）
    )

    /**
     * Ed25519 验签（纯函数，先验签后解析的"验签"半边；JVM 可测）。
     *
     * R2 起计算搬进 Rust 核心（`CoreNative.verifyEd25519`，见 ERRATA E-051），平台实现保留做
     * **对拍与回退**；这段逻辑已抽到 `Ed25519Verify`，**与环境包索引共用同一条入口**
     * （2026-10-08 收口：此前只有清单走 Rust，索引那条仍直连平台实现）。
     */
    fun verify(body: ByteArray, sigBase64: String): Boolean = try {
        val sig = java.util.Base64.getDecoder().decode(sigBase64)
        val pub = java.util.Base64.getDecoder().decode(PUBLIC_KEY_B64)
        check(pub.size == 32) { "manifest 公钥配置非法（长度 ${pub.size} ≠ 32）" }
        Ed25519Verify.verify(pub, sig, body, "清单")
    } catch (t: Throwable) {
        Log.w(TAG, "验签异常（视为失败）: ${t.message}")
        false
    }

    /** 解析 manifest 文本（纯函数；只在验签通过后调用）。 */
    fun parse(text: String): List<Entry> {
        fun str(id: String, key: String): String {
            // 逐 agent 块解析：以 "id" 为锚找块内字段
            // ⚠ 花括号必须转义：部分设备 regex 引擎（如 MagicOS/ICU）拒绝字符类内的裸 `}`，
            // 桌面 JVM 容忍——单测发现不了，真机上 parse 会整体抛 PatternSyntaxException。
            val block = Regex("\"id\"\\s*:\\s*\"$id\"[^\\}]*\\}").find(text)?.value ?: return ""
            return Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(block)?.groupValues?.get(1) ?: ""
        }
        val ids = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").findAll(text).map { it.groupValues[1] }.toList()
        return ids.mapNotNull { id ->
            val name = str(id, "name")
            val install = str(id, "installCmd")
            if (name.isBlank() || install.isBlank()) null
            else Entry(
                id = id,
                name = name,
                desc = str(id, "desc"),
                launchCmd = str(id, "launchCmd").ifBlank { id },
                installCmd = install,
                npmPackage = str(id, "npmPackage").ifBlank { null },
                uninstallCmd = str(id, "uninstall").ifBlank { null },
            )
        }
    }

    /** 已安装 Agent 的版本探测：读 rootfs 内 npm 全局包的 package.json（host 侧直接可见）。 */
    fun installedVersion(ctx: Context, npmPackage: String): String? = try {
        val pkgJson = java.io.File(ctx.filesDir, "rootfs/usr/lib/node_modules/$npmPackage/package.json")
        if (pkgJson.isFile) {
            Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(pkgJson.readText())?.groupValues?.get(1)
        } else null
    } catch (_: Throwable) {
        null
    }

    /** 最近一次验签通过的条目（无缓存返回 null = 调用方用出厂版）。 */
    fun cached(ctx: Context): List<Entry>? = try {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.getString(KEY_BODY, null)?.let { parse(it) }
    } catch (t: Throwable) {
        // 不再静默：正则兼容性这类问题静默 null 会让 manifest 全链路失效且无迹可查
        Log.w(TAG, "清单缓存读取/解析失败，回退出厂版: $t")
        null
    }

    fun cachedVersionText(ctx: Context): String = try {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.getString(KEY_BODY, null)
            ?.let { Regex("\"version\"\\s*:\\s*([0-9]+)").find(it)?.groupValues?.get(1) }
            ?: "出厂版"
    } catch (_: Throwable) {
        "出厂版"
    }

    /**
     * 刷新（后台线程调用；结果回调任意线程）。
     * @param force true = 忽略 TTL 立即拉取（设置页手动按钮）；false = 6 小时内跳过
     */
    fun refresh(ctx: Context, force: Boolean = false, onDone: ((applied: Boolean) -> Unit)? = null) {
        Log.i(TAG, "refresh 被调用 force=$force")
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!force &&
            System.currentTimeMillis() - prefs.getLong(KEY_TIME, 0) < TTL_MS &&
            prefs.contains(KEY_BODY)
        ) {
            onDone?.invoke(false)
            return
        }
        Thread {
            var applied = false
            var idx = 0
            for (channel in channels(ctx)) {
                idx++
                try {
                    Log.i(TAG, "刷新：尝试通道 $idx")
                    val (body, sig) = channel()
                    Log.i(TAG, "刷新：通道 $idx 拉取成功，开始验签")
                    Log.i(TAG, "通道 $idx 内容指纹 body=${sha256Hex(body.toByteArray())} sig=${sha256Hex(sig.toByteArray())}")
                    if (!verify(body.toByteArray(Charsets.UTF_8), sig)) {
                        Log.w(TAG, "验签不过，丢弃该通道")
                        continue
                    }
                    // 验签通过才落地缓存（先验签后解析、先验签后持久化）
                    prefs.edit().putString(KEY_BODY, body)
                        .putLong(KEY_TIME, System.currentTimeMillis()).commit()
                    Log.i(TAG, "Agent 清单已更新（${cachedVersionText(ctx)}）")
                    RunLogCompat.log("Agent 清单已更新（${cachedVersionText(ctx)}）")
                    applied = true
                    break
                } catch (t: Throwable) {
                    Log.w(TAG, "通道 $idx 失败: ${t.message}")
                }
            }
            if (!applied) RunLogCompat.log("Agent 清单刷新失败，沿用现有清单")
            onDone?.invoke(applied)
        }.start()
    }

    /**
     * 通道列表（惰性，每通道返回 <manifest 正文, 签名 base64>）。
     * 实测排序依据（2026-10-04，本机）：raw.githubusercontent 与 jsDelivr 在国内网络
     * 不稳定，而 api.github.com 稳定可达（环境更新按钮同域验证）——Contents API 排第一。
     */
    private fun channels(ctx: Context): List<() -> Pair<String, String>> {
        val repoPath = "pisces19860207/zhengdao/main"
        return listOf(
            // ① GitHub Contents API（base64 包裹，走 api.github.com）
            {
                val body = fetchContentsApi("$repoPath/rootfs/agents.json")
                val sig = fetchContentsApi("$repoPath/rootfs/agents.json.sig")
                require(body != null && sig != null) { "Contents API 不可达" }
                body to sig
            },
            // ② GitHub raw（trimEnds=false：签名覆盖完整字节，末尾换行不能裁）
            {
                val u = "https://raw.githubusercontent.com/$repoPath/rootfs/agents.json"
                val body = RootfsDownloader.fetchText(u, trimEnds = false)
                val sig = RootfsDownloader.fetchText("$u.sig")
                require(body != null && sig != null) { "raw 不可达" }
                body to sig
            },
            // ③ jsDelivr CDN（同上）
            {
                val u = "https://cdn.jsdelivr.net/gh/pisces19860207/zhengdao@main/rootfs/agents.json"
                val body = RootfsDownloader.fetchText(u, trimEnds = false)
                val sig = RootfsDownloader.fetchText("$u.sig")
                require(body != null && sig != null) { "jsDelivr 不可达" }
                body to sig
            },
        )
    }
    private fun sha256Hex(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    /** 经 GitHub Contents API 抓文件内容（base64 解包；任何失败返回 null）。 */    private fun fetchContentsApi(path: String): String? {
        val json = RootfsDownloader.fetchText("https://api.github.com/repos/$path") ?: return null
        // 必须用正规 JSON 解析：GitHub 会把 \n / \/ / = (\u003d) 等全部转义，
        // 手写替换无法覆盖 \uXXXX 形态（实测 2026-10-04 内容被静默破坏致验签恒败）
        val content = org.json.JSONObject(json).getString("content")
        return String(
            java.util.Base64.getMimeDecoder().decode(content),
            Charsets.UTF_8,
        )
    }
}

/** RunLog 的轻引用（避免 ui 包反向依赖 rootfs 包名冲突的阅读成本）。 */
private object RunLogCompat {
    fun log(text: String) = com.example.zhengdao.rootfs.RunLog.log(text)
}

/**
 * Ed25519 验签的**统一入口**：Rust 核心优先 + 平台实现对拍（见 ERRATA E-051）。
 *
 * 为什么要有这个对象：项目里有**两条** Ed25519 签名链——agents 清单（`AgentManifest`）
 * 与环境包索引（`rootfs/RootfsIndex.kt`）。E-051 把计算搬进 Rust 时只改了清单那条，
 * 索引那条仍直连平台实现 ⇒ 同一份信任链上出现了两种验证强度，且 Rust 侧的真实输入
 * 只覆盖了清单。两条链共用这里，以后新加的签名链不会再漏（缺一条就少一次对拍）。
 */
internal object Ed25519Verify {
    private const val TAG = "Ed25519Verify"

    /**
     * @param what 人话标签（"清单"/"索引"）——只进日志，真机取证时用来分辨是哪条链。
     */
    fun verify(pub: ByteArray, sig: ByteArray, msg: ByteArray, what: String): Boolean = try {
        val platform = Ed25519.verify(pub, sig, msg)
        val rust = CoreNative.verifyEd25519(pub, sig, msg)
        when {
            rust == null -> {
                Log.i(TAG, "$what：验签走平台回退（Rust 核心不可用）：$platform")
                platform
            }
            rust != platform -> {
                Log.w(TAG, "$what：验签结论不一致（Rust=$rust 平台=$platform）——按拒绝处理，见 ERRATA E-051")
                false
            }
            else -> {
                Log.i(TAG, "$what：验签走 Rust 核心（与平台对拍一致）：$rust")
                rust
            }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "$what：验签异常（视为失败）: ${t.message}")
        false
    }
}

/**
 * Ed25519 验签（RFC 8032 §5.1，从零实现，BigInteger 扭曲爱德华兹曲线运算）。
 *
 * 为什么不用 java.security：Android 的 KeyFactory("Ed25519") 默认路由到 AndroidKeystore，
 * 拒收外部公钥字节（2026-10-04 真机实测，连 BCWorkaround 伪装 provider 都拦截），
 * provider 选择在设备间不可控。算法本体很小（约百行），按 RFC 从零实现，
 * 正确性由单元测试对照真实签名的 manifest 强制锁定（篡改/错钥/正品三向覆盖）。
 *
 * `internal` 而非 `private`：**同一模块内复用**——环境包索引（`rootfs/RootfsIndex.kt`）
 * 也用同一份实现验它自己的签名，避免出现第二份曲线代码（两份实现迟早会分叉）。
 */
internal object Ed25519 {

    private val P = BigInteger.TWO.pow(255).subtract(BigInteger.valueOf(19))
    private val D = BigInteger.valueOf(-121665).multiply(
        BigInteger.valueOf(121666).modInverse(P)
    ).mod(P)
    private val D2 = D.shiftLeft(1).mod(P)
    private val L = BigInteger.TWO.pow(252).add(
        BigInteger("27742317777372353535851937790883648493")
    )
    private val SQRT_M1 = BigInteger.TWO.modPow(
        P.subtract(BigInteger.ONE).shiftRight(2), P
    )

    // 基点 B（RFC 8032 §5.1 常量）
    private val BASE = Point(
        BigInteger("15112221349535400772501151409588531511454012693041857206046113283949847762202"),
        BigInteger("46316835694926478169428394003475163141307993866256225615783033603165251855960"),
        BigInteger.ONE, null,
    )

    /** 扩展坐标点 (X:Y:Z:T)，T = XY/Z；恒等元 = (0,1,1,0)。 */
    private data class Point(val x: BigInteger, val y: BigInteger, val z: BigInteger, val t: BigInteger?)

    private fun pt(x: BigInteger, y: BigInteger, z: BigInteger, t: BigInteger) = Point(
        x.mod(P), y.mod(P), z.mod(P), t.mod(P)
    )

    /** 统一加法（RFC 8032 §5.1.4，含倍点——公式对 P=Q 完备）。 */
    private fun add(a: Point, b: Point): Point {
        val t1 = a.t ?: a.x.multiply(a.y).mod(P)
        val t2 = b.t ?: b.x.multiply(b.y).mod(P)
        val aa = a.y.subtract(a.x).multiply(b.y.subtract(b.x)).mod(P)
        val bb = a.y.add(a.x).multiply(b.y.add(b.x)).mod(P)
        val cc = t1.multiply(D2).multiply(t2).mod(P)
        val dd = a.z.multiply(b.z).shiftLeft(1).mod(P)
        val ee = bb.subtract(aa).mod(P)
        val ff = dd.subtract(cc).mod(P)
        val gg = dd.add(cc).mod(P)
        val hh = bb.add(aa).mod(P)
        return pt(ee.multiply(ff), gg.multiply(hh), ff.multiply(gg), ee.multiply(hh))
    }

    /** 标量乘：LSB 双加（加法公式完备，无需显式倍点分支）。 */
    private fun mul(scalar: BigInteger, p: Point): Point {
        var r = Point(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)
        var t = p
        var s = scalar.mod(L)
        for (i in 0 until s.bitLength()) {
            if (s.testBit(i)) r = add(r, t)
            t = add(t, t)
        }
        return r
    }

    private fun equal(a: Point, b: Point): Boolean =
        a.x.multiply(b.z).mod(P) == b.x.multiply(a.z).mod(P) &&
            a.y.multiply(b.z).mod(P) == b.y.multiply(a.z).mod(P)

    /** 解码 32 字节压缩点（RFC 8032 §5.1.3）；非法返回 null。 */
    private fun decode(bytes: ByteArray): Point? {
        if (bytes.size != 32) return null
        val copy = bytes.copyOf()
        val xOdd = (copy[31].toInt() and 0x80) != 0
        copy[31] = (copy[31].toInt() and 0x7F).toByte()
        val y = BigInteger(1, copy.reversedArray())
        if (y >= P) return null
        val u = y.multiply(y).subtract(BigInteger.ONE).mod(P)
        val v = D.multiply(y).multiply(y).add(BigInteger.ONE).mod(P)
        // x = u·v³·(u·v⁷)^((p-5)/8)
        var x = u.multiply(v.modPow(BigInteger.valueOf(3), P)).mod(P)
        val uv7 = u.multiply(v.modPow(BigInteger.valueOf(7), P)).mod(P)
        x = x.multiply(uv7.modPow(P.subtract(BigInteger.valueOf(5)).shiftRight(3), P)).mod(P)
        val vx2 = v.multiply(x).multiply(x).mod(P)
        if (vx2 == u) {
            if (x.signum() == 0 && xOdd) return null
        } else if (vx2 == P.subtract(u).mod(P)) {
            x = x.multiply(SQRT_M1).mod(P)
        } else {
            return null
        }
        if (x.signum() == 0 && xOdd) return null
        if ((x.testBit(0)) != xOdd) x = P.subtract(x)
        return Point(x, y, BigInteger.ONE, x.multiply(y).mod(P))
    }

    private fun leInt(bytes: ByteArray): BigInteger = BigInteger(1, bytes.reversedArray())

    /** 验签主流程：S·B == R + h·A（h = SHA512(R‖A‖M) mod L）。 */
    fun verify(pub: ByteArray, sig: ByteArray, msg: ByteArray): Boolean {
        if (pub.size != 32 || sig.size != 64) return false
        val a = decode(pub) ?: return false
        val r = decode(sig.copyOfRange(0, 32)) ?: return false
        val s = leInt(sig.copyOfRange(32, 64))
        if (s >= L) return false
        val md = MessageDigest.getInstance("SHA-512")
        md.update(sig, 0, 32)
        md.update(pub)
        md.update(msg)
        val h = leInt(md.digest()).mod(L)
        val left = mul(s, BASE)
        val right = add(r, mul(h, a))
        return equal(left, right)
    }
}
