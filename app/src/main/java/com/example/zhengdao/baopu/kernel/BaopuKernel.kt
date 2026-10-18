// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：第二十六批（内核抽取）。裁决与口径：`docs/派工单-2026-10-18-第二十六批-内核抽取.md`
// ＋ `docs/口径-2026-10-18-L2.5定义与升级闸门.md`。
//
// ⚠️ 先说清楚一句（也印在 docs/KERNEL.md 顶部）：
//    「把边界收在一处」**不能让模型碰不到代码**——模型根本没有写 APK 源码并重编译的通路。
//    这层的真实价值是四条：
//      ① 单一真相源（一处改，所有引用跟着变）
//      ② 改动可见（谁动内核，git diff 一眼看到）
//      ③ 门禁可扫（KernelGuardTest 钉住「唯一来源、不许重复字面量、不许被改成 var」）
//      ④ 有地方挂规矩（以后写「这属于内核，模型不可提案」有落点）
//    安全性仍然来自「模型没有那条通路」，不来自模块划分。
//
// 权威表（谁能改）：
//    · 人（用户/开发者）：可以改，**改完要有留痕**（26-B 落地：写 `[kernel] …` 到 RunLog）
//    · 模型（运行时）：不改、也不可提案改 —— 见 `docs/KERNEL.md`
package com.example.zhengdao.baopu.kernel

import com.example.zhengdao.baopu.SegmentKind

/**
 * 抱朴内核：**七条边界**的唯一真相源。
 *
 * 只放常量与纯判据：不许 import Android、不许 I/O、不许 Compose（`KernelGuardTest` 会扫）。
 * 每个子 object 对应派工单里的一条边界，KDoc 写清「这条在防什么」。
 */
object BaopuKernel {

    /** ① 数据边界：能读写的只有工作区（默认 `Download/证道`，可被用户改），资料库原件只增不减。 */
    object Data {
        /** 资料库原始件的相对目录（相对工作区）；写入一律走归档闸门，**不在这里删**。 */
        const val LIBRARY_RAW_DIR = "资料库/原始"

        /** 抱朴自己的备份后缀（`write_file`/`edit_file` 覆盖前留一份）。 */
        const val BACKUP_SUFFIX = ".baopu-bak"
    }

    /** ② 执行边界：A 内核三条 —— 不跨 rootfs、不起子进程、只在工作区活动。 */
    object Exec {
        const val CROSS_ROOTFS_ALLOWED = false
        const val SUBPROCESS_ALLOWED = false
        const val WORKSPACE_ONLY = true
    }

    /** ③ 工具边界：工具集与 schema 只能人改（模型不许增删工具、不许改描述里的权限语义）。 */
    object Tools {
        /** 工具名一律 `snake_case`；新工具必须同时进设置页文案表（`baopuToolLabel/Desc`）。 */
        const val TOOL_NAME_PATTERN = "^[a-z][a-z0-9_]*$"

        /** 工具结果回灌给模型时的单次上限（字符）。 */
        const val TOOL_RESULT_MAX_CHARS = 8 * 1024

        /**
         * 工具结果超过这个字符数就**落盘改写**（模型只看到摘要 ＋ 落盘提示）。
         * 26-B 从 `baopu/agent/GuardPipeline.kt` 搬进来，那边保留同名委托。
         */
        const val SPILL_THRESHOLD_CHARS = 4_000

        /** 落盘后回灌给模型的那截摘要有多长。 */
        const val SPILL_SNIPPET_CHARS = 2_000
    }

    /** ④ 授权边界：确认制与超时。模型可以把动作**提案**给人，不能把 CONFIRM 改成 FREE。 */
    object Auth {
        /** 确认卡超时（只算前台时间）。 */
        const val CONFIRM_TIMEOUT_MS = 15 * 60 * 1000L

        /** 确认轮询间隔。 */
        const val CONFIRM_POLL_MS = 100L

        /** 写进确认卡的参数上限（防把整本书塞进确认卡）。 */
        const val CONFIRM_ARGS_MAX_CHARS = 8 * 1024
    }

    /**
     * ⑤ 停止与预算边界（用户 2026-10-18 补的第 4 条边界：**停止权**）。
     * 这一族是「模型绝不能把自己调成 ∞」的那几个数：步数、失败数、自修次数、熔断、压缩阈值。
     */
    object Budget {
        /** 一次 run 最多多少步（死循环的第一道刹车）。 */
        const val MAX_STEPS = 16

        /** 连续多少次工具失败就停手问人。 */
        const val MAX_TOOL_FAILURES = 3

        /** 收尾闸允许自修几次。 */
        const val MAX_VERIFY_REPAIRS = 1

        /** 通道连续失败几次就熔断。 */
        const val CHANNEL_BREAK_THRESHOLD = 3

        /** 一轮里最多换几次通道。 */
        const val MAX_CHANNEL_SWITCHES = 2

        /** 压缩触发线＝窗口 × 该比例（与绝对上限取小）。 */
        const val COMPRESS_THRESHOLD = 0.85

        /** 压缩触发线的绝对上限（窗口再大也不许超过它）。 */
        const val COMPRESS_ABS_MAX_TOKENS = 120_000

        /** 可压旧消息条数下限（少于这个数不压）。 */
        const val COMPRESS_OLD_MESSAGE_MIN = 60

        /** 单次压缩最多处理多少条。 */
        const val COMPRESS_MAX_MESSAGES_PER_RUN = 200

        /**
         * 压缩**永不触碰**的段（压缩红线）。26-B 从 `baopu/ContextAssembler.kt` 整块搬进来，
         * 那边改成引用这里 —— **不许**在两处各写一份（抄错一个段名就是红线被悄悄改掉）。
         *
         * 为什么这个集合在内核里、而 [SegmentKind] 枚举不搬：枚举序本身就是拼装层的
         * 「缓存前缀顺序」判据，动它要改整条组装链；内核只持有「哪些段不许压」这一条判据。
         * 依赖方向：内核 → 纯枚举（无 Android、无 I/O），K1 仍然成立。
         */
        val NEVER_COMPRESS: Set<SegmentKind> = setOf(
            SegmentKind.PERSONA_A, SegmentKind.PERSONA_B, SegmentKind.PERSONA_C,
            SegmentKind.HARD_FACTS, SegmentKind.MEMORY,
        )
    }

    /**
     * ⑥ 外发边界（用户 2026-10-18 补的第 5 条边界）：**数据送给谁**。
     * provider 白名单、浏览器 scheme 白名单、桥的监听地址 —— 三条都是「出去的口子」。
     */
    object Egress {
        /** 允许 WebView 打开的 scheme（别的 scheme 一律拒）。 */
        val ALLOWED_SCHEMES: Set<String> = setOf("http", "https")

        /**
         * 桥只绑本机回环，且要 token。
         * TODO(刀4后并入)：`browser/WebViewEngine.kt` 里那份 `allowedSchemes` 等浏览器线稳定后并进来，
         * **不许**再留第二份常量。
         */
        const val BRIDGE_BIND_LOOPBACK_ONLY = true
    }

    /** ⑦ 准入门：评测集与基线只能人改；模型可以加用例，不能删用例、不能改阈值。 */
    object Gate {
        /** 进化闸门的用例数下限（`eval/` 里那 10 例）。 */
        const val EVAL_MIN_CASES = 10
    }
}
