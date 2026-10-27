// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 依据的公开标准：Ed25519 签名（RFC 8032）。
//
// 第二百四十一批（终端解耦第二刀）：本文件的两个对象**整段搬自**
// `app/src/main/java/com/example/zhengdao/ui/AgentManifest.kt` 尾部，代码逐字未改；
// 只有三处机械变化：package 行、`internal` → `public`（跨模块要给 :app 用）、
// 以及新增 CoreNative 的 import（原文件里在同包外，靠 import 拿）。
// 为什么搬：环境包索引（rootfs/RootfsIndex.kt，随本刀进 :terminal-core）验签要用同一份
// 曲线实现与同一条「Rust 优先 + 平台对拍」入口，两份实现不许分叉（ERRATA E-051）。
package com.example.zhengdao.terminal.crypto

import android.util.Log
import com.example.zhengdao.rust.CoreNative
import java.math.BigInteger
import java.security.MessageDigest

/**
 * Ed25519 验签的**统一入口**：Rust 核心优先 + 平台实现对拍（见 ERRATA E-051）。
 *
 * 为什么要有这个对象：项目里有**两条** Ed25519 签名链——agents 清单（`AgentManifest`）
 * 与环境包索引（`rootfs/RootfsIndex.kt`）。E-051 把计算搬进 Rust 时只改了清单那条，
 * 索引那条仍直连平台实现 ⇒ 同一份信任链上出现了两种验证强度，且 Rust 侧的真实输入
 * 只覆盖了清单。两条链共用这里，以后新加的签名链不会再漏（缺一条就少一次对拍）。
 */
object Ed25519Verify {
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
object Ed25519 {

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
