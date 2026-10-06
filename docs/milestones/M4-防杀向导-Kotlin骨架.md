# M4 防杀向导 Kotlin 骨架（本里程碑工程约束与验收标准；实现以代码为准，约束与红线以本文档为准）

> # ⛔ 【状态：已砍掉，留档参考】
>
> **v1.0 不实现本文档。请勿据此开工。**
>
> **决定时间**：2026-10-05（v3.9 阶段，用户拍板）
> **原因**：自用场景下不需要——无线 ADB 配对涉及多机型（小米/华为/OPPO/vivo/三星等）各自的"无线调试"入口差异、配对码与端口行为差异、以及 Android 11–15 各版本 Phantom 进程杀策略差异，**多机型适配成本远超收益**。
> **替代方案**：v1.0 只保留**设置页的 ROM 保活图文指南**（电池白名单两步引导 + 各自启动/后台锁路径图文，见 M2 §7）+ **终端手动执行命令复制入口**（用户自愿在电脑 adb shell 或其他终端执行，见本文档 §「终端手动执行」思路）。
> **留档价值**：本文的状态机设计、三路径（Root/Shizuku/无线 ADB）辨析、完成率埋点思路，若将来做面向大众分发或有多机型适配资源时可复用。

---

> 📌 **存储结论已反转（E-005，2026-10-06）**：App 真身下 `/sdcard` 读写均可用，SAF 镜像同步降级为**备用方案**。本文件不含存储方案约定；存储权威说明见 `docs/ERRATA.md` 与 `已知限制.md`。
>
> 项目：证道 · 依据：v3 文档 §5 第 3 层（Phantom 修复）、§9 Checklist、§11 里程碑
> ~~M4 = 无线 ADB 配对向导 + Phantom 修复 + Root 一键捷径 + Shizuku 捷径（工作量最大的一块，多机型图文分叉）~~（已砍掉，见上方标注）
> 目标用户：普通用户，全程不碰电脑、不敲命令。完成率是硬指标（阈值 30%）。

---

## 1. PhantomRepairWizard（向导状态机）

```kotlin
// 入口条件：仅安卓 12+（SDK_INT >= 31）显示；安卓 11 及以下完全不可见。
// 路径判定优先级：Root（su 可用）> Shizuku（已装已授权）> 无线 ADB（默认主路径）。
enum class RepairPath { WirelessAdb, Root, Shizuku }
enum class WizardStep { Detect, Intro, PairInput, Connecting, Executing, Verify, Done }

class PhantomRepairWizard(
    private val adbClient: AdbPairingClient,
    private val mdns: MdnsDiscovery,
    private val rootRepair: RootRepair,
    private val shizukuRepair: ShizukuRepair?,
) {
    val state = MutableStateFlow(WizardState(step = WizardStep.Detect))

    fun start() {
        // 1. SDK_INT >= 31 才继续，否则直接结束（UI 不显示入口）
        // 2. 检测 root：su -c 'id' 可用 -> RepairPath.Root
        // 3. 检测 Shizuku：已授权 -> RepairPath.Shizuku（仅对已装用户是捷径，非默认路径）
        // 4. 否则 RepairPath.WirelessAdb
    }

    fun onPairCodeEntered(code: String, port: Int) {
        // 6 位配对码 + 配对端口 -> 进入配对流程
        // 配对端口与连接端口不同：配对端口经 mDNS _adb-tls-pairing._tcp 发现，
        // 连接端口经 _adb-tls-connect._tcp 发现；mDNS 失败引导用户手动填。
    }

    fun onSkip() {
        // 显眼跳过按钮：跳过后友好告知「不影响使用，只是后台可能被系统清理」
        // 设置页常驻「修复后台被杀」入口，随时回来补做
    }
}
```

## 2. AdbPairingClient（无线 ADB 配对 + TLS 连接 + 执行）

> 参考开源 LADB 方案理解配对/连接协议，**自研实现，不抄代码**。配对码配对成功后，用连接端口建立 TLS 连接。

```kotlin
class AdbPairingClient(
    private val mdns: MdnsDiscovery,
) {
    // 配对：无线调试「使用配对码配对设备」的 6 位码 + 配对端口
    suspend fun pair(pairingPort: Int, pairCode: String): Result<Unit>

    // 连接：TLS 连接（连接端口，不是配对端口）
    suspend fun connect(connectPort: Int): Result<Unit>

    // 执行修复命令
    suspend fun repair(): Result<String> {
        // 执行：settings put global settings_enable_monitor_phantom_procs false
        // 验证：settings get global settings_enable_monitor_phantom_procs 应返回 false
    }
}
```

## 3. MdnsDiscovery（自动发现端口）

```kotlin
class MdnsDiscovery {
    // 无线调试开启后，设备在局域网广播：
    //   _adb-tls-pairing._tcp -> 配对端口
    //   _adb-tls-connect._tcp  -> 连接端口
    suspend fun discoverPairingPort(): Int?
    suspend fun discoverConnectPort(): Int?
    // 失败时 UI 引导：打开「无线调试」主页，把页面上显示的端口手动填入
}
```

## 4. RootRepair / ShizukuRepair（两条捷径）

```kotlin
class RootRepair {
    // 检测到 root 时，向导直接替换为「一键修复（Root）」按钮，跳过整个配对流程
    suspend fun isRootAvailable(): Boolean   // su -c 'id' 可用
    suspend fun repair(): Result<Unit> {
        // su -c "settings put global settings_enable_monitor_phantom_procs false"
    }
}

class ShizukuRepair {
    // 已装 Shizuku 且已授权：复用其授权执行同一条命令
    // 注意：Shizuku 自身初始设置同样是无线调试配对流程，对新手不是解药，
    //       只对已装用户是捷径，不作为默认路径。
    suspend fun isAvailable(): Boolean
    suspend fun repair(): Result<Unit>
}
```

## 5. 图文分叉 UI（Compose）

```kotlin
@Composable
fun PhantomRepairScreen(wizard: PhantomRepairWizard) {
    // Detect        ：检测中动画（root / shizuku / 无线调试 三选一）
    // Intro         ：图文步骤，按机型分叉（小米/华为/OPPO/vivo 入口路径不同，每步配截图）
    // PairInput     ：6 位配对码 + 配对端口
    //                 【红线】输入框一律用普通文本框，绝不用密码类型（避免安全键盘）
    // Connecting    ：配对/连接进度 + 当前日志
    // Executing     ：执行 settings 命令，显示实时输出
    // Verify        ：显示 settings get 结果（false = 成功）
    // Done          ：完成页 + 「在终端手动执行」复制入口
}
```

**「在终端手动执行」入口（v3.4）**：一键复制 `settings put global settings_enable_monitor_phantom_procs false`。
注意：这条是 **Android 侧命令**，不能在 proot rootfs（Linux 环境）里执行——入口只做复制，供用户在电脑 adb shell 或其他终端自助执行。

**完成率埋点（匿名）**：记录向导开始/完成/跳过，用于 30% 完成率硬指标。埋点数据遵守 v3 §9 隐私政策（不上传用户数据，仅排障/改进）。

## 验收要点（对应 v3 §5 / §9）

- [ ] 安卓 12 / 14 / 15 真机，无线 ADB 配对向导全流程跑通（配对码 + 端口 + 自动连接 + 执行 + 验证）
- [ ] 配对失败（码错 / 端口错 / mDNS 找不到）引导正确，可手动填端口重试
- [ ] Root 捷径：Magisk / KernelSU 设备一键完成，跳过配对流程
- [ ] Shizuku 捷径：已装已授权设备一键完成
- [ ] 跳过 / 中途退出：设置页常驻「修复后台被杀」入口，可随时回来补做
- [ ] 安卓 11 及以下：完全看不到入口
- [ ] 执行后 `settings get global settings_enable_monitor_phantom_procs` 返回 false
- [ ] 完成率埋点上报正确

## 红线（v3 实测结论）

- **配对码输入框绝不用密码类型**（安全键盘会让终端/输入不可用）
- 只参考 LADB 的协议理解，不抄其代码（GPL 传染）
- 完成率 < 30% 必须升级方案（外部审计硬指标）
- Phantom 开关是社区实证而非官方承诺：每个安卓大版本发布后真机复查一次有效性