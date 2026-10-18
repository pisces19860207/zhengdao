# KERNEL · 抱朴内核（七条边界）

> 立：2026-10-18 ｜ 第二十六批（26-A 抽常量、26-B 搬红线＋留痕）
> 代码：`app/src/main/java/com/example/zhengdao/baopu/kernel/`
> 门禁：`app/src/test/java/com/example/zhengdao/baopu/kernel/KernelGuardTest.kt`

---

## 0. 先把话说透（这句话不是修辞）

**安全性来自「模型没有那条通路」，不是来自模块划分。**

模型今天**根本改不了代码**：它没有「写 APK 源码 → 重编译 → 生效」工具链。
能通到内核的只有两条路：

1. **提案机制** —— 写人设／技能文件（`PersonaFiles.applyProposal`，落盘要人点确认）；
2. **配置面** —— 工作区／阈值／通道（目前**只有用户本人**能在设置页改）。

所以「把边界收在一处」**不是**安全措施，它的真实价值只有四条：

| # | 价值 | 怎么体现 |
| --- | --- | --- |
| ① | **单一真相源** | 一处改，所有引用跟着变，不会再出现两套口径 |
| ② | **改动可见** | 谁动内核，`git diff` 一眼看到（从「翻五个文件」降到「看一个文件」） |
| ③ | **门禁可扫** | `KernelGuardTest` 钉住「唯一来源、不许重复字面量、不许被改成 var」 |
| ④ | **有地方挂规矩** | 以后写「这属于内核，模型不可提案」有落点，不必靠记性 |

> 任何人（包括以后的 agent）往这个文件里加一条「因为收在一处所以更安全」的说法，
> 都是**假话**，先删了再谈别的。

---

## 1. 七条边界

| # | 边界 | 代码位置 | 装的是什么 | 谁有权改 |
| --- | --- | --- | --- | --- |
| ① | **数据边界** | `BaopuKernel.Data` | 资料库原件目录（只增不减）、备份后缀；工作区判定体在 `terminal/Workspace`＋`rootfs/PathGuard` | 人（留痕） |
| ② | **执行边界** | `BaopuKernel.Exec` | 不跨 rootfs、不起子进程、只在工作区活动 | 人（留痕） |
| ③ | **工具边界** | `BaopuKernel.Tools` | 工具名规则、结果回灌上限、spill 落盘阈值与摘要长度；工具全表在 `agent/BaopuToolCatalog` | 人（留痕） |
| ④ | **授权边界** | `BaopuKernel.Auth` | 确认制（`ToolPermission`）、确认超时、轮询间隔、确认卡参数上限 | 人（留痕） |
| ⑤ | **停止与预算边界** | `BaopuKernel.Budget` | 步数 / 失败数 / 自修次数 / 熔断 / 换通道次数 / 压缩四项 / **压缩红线 `NEVER_COMPRESS`** | 人（留痕） |
| ⑥ | **外发边界** | `BaopuKernel.Egress` | 浏览器 scheme 白名单、桥只绑回环 | 人（留痕）；`WebViewEngine` 那份等浏览器线稳定后并入（见 §4） |
| ⑦ | **准入门** | `BaopuKernel.Gate` | 评测用例数下限；用例与阈值在 `eval/baseline.json` | 人（留痕），模型**可加**用例、不可删不可改阈值 |

⑤ 之所以单独点名，是因为它是用户 2026-10-18 补的第 4 条边界 —— **停止权**：
这一族是「模型绝不能把自己调成 ∞」的那几个数。`KernelGuardTest.K6` 逐个点数。

---

## 2. 权威表（谁能改）

| 主体 | 能不能改内核 | 边界 |
| --- | --- | --- |
| **人**（用户／开发者） | ✅ 能改 | 必须留痕：改内核项时写 `[kernel] …` 到 RunLog（见 §3）；改代码走正常提交与门禁 |
| **模型**（运行时） | 🛑 **不改、也不提案改** | 没有写源码／改常量的工具通路；发现「这条阈值不好」只能在对话里**说**，不动手 |
| 其他 agent（Trea／qoder／workbuddy） | ⚠️ 只在派工单明确授权时改 | 单子里没点到 `baopu/kernel/**` 就是不许碰；19:00 后该类路径归蓝总管 |

**为什么模型连「提案」都不给**：内核项没有「待确认提案」这一层——
`PersonaFiles.applyProposal` 那套是给人设／技能文件用的，不是给边界用的。
边界要改就是人改代码或人点设置页，没有第三条路。

---

## 3. 留痕：`[kernel] …`

用户改**内核项**（当前已接线：工作区；预留：压缩阈值、通道）时，写一行到 RunLog：

```
[kernel] 工作区改为 /storage/emulated/0/Download/男性（原 /storage/emulated/0/Download/证道）
```

- 产出文本的是 `KernelAudit`（纯函数，无 I/O，可在 JVM 单测里直接断言）；
- 落盘的是调用方 `rootfs/RunLog.log(...)`；
- **只在真的变了时写** —— 反复点同一个 chip 不该把日志刷满；
- `[kernel]` 是可 grep 的锚：`grep '\[kernel\]'` 就是「历史上谁动过底线」；
- 值里带换行会被压成空格：一条留痕只能占一行。

设置页在工作区那一块下面有一行小字「改这里会记一条日志（[kernel] 留痕）」，
文案键 `baopu_ws_log_note`（中英对称）。

---

## 4. 已知未并入（诚实记账）

| 项 | 现状 | 什么时候并 |
| --- | --- | --- |
| `browser/WebViewEngine` 的 `allowedSchemes` | 浏览器线正在改这个文件，本批不动 | 刀 4 稳定后并入 `Egress.ALLOWED_SCHEMES` |
| `rootfs/RootfsDelta` | 里面**没有**数值型边界判据（只有补丁文件名与 `..` 上跳校验，判定逻辑原地不动） | 无（不需要搬） |
| 压缩阈值／通道 的用户入口 | `KernelAudit` 的两个函数已备好，设置页还没上这两个控件 | 上控件那批一起接 |

**「不许留第二份」是硬要求**：并进来之后，被并的那一处只留委托（`const val X = BaopuKernel.G.X`），
不许两处各写一份字面量。`KernelGuardTest.K3`／`K8` 就是干这个的。

---

## 5. 门禁怎么跑

```powershell
cd C:\Users\guoli\AndroidStudioProjects\wt-docs5
.\gradlew.bat :app:testDebugUnitTest --tests "*KernelGuardTest*" --console=plain
```

九组判据（`K1`–`K9`）：

| 判据 | 钉的是什么 |
| --- | --- |
| K1 | 内核包**每个文件**都不带 `android.`／`androidx.`／`java.io.`／`java.net.` |
| K2 | 七组都在、且 Budget／Auth 有真常量（不许空壳） |
| K3 | 被搬走的常量，主源码里不许再出现字面量定义 |
| K4 | `RunRules` 与 `ContextAssembler` 是**委托**（≥11 处），不是各写一份 |
| K5 | 值没变（16／3／1／3／15min／0.85／120k／60／200） |
| K6 | 停止权那一族六个数齐全 |
| K7 | 压缩红线＝那五段，且 `setOf` 全仓只有一份 |
| K8 | spill 阈值（4_000／2_000）搬进内核且是委托 |
| K9 | `[kernel]` 留痕的格式 ＋ 设置页三处入口都接了 |

---

## 6. 改这个包之前

1. 先读本文件 §0（别再说「收在一处就更安全」）；
2. 只加**常量与纯函数**，不加 I/O、不加 Compose、不 import Android —— K1 会红；
3. 加一个新数值判据 ⇒ 同时在 `KernelGuardTest.K5`（或新增一组）里钉住它的值；
4. 改完跑 K1–K9 ＋ 全量单测（纯重构**用例数只该因为新测试增加**）。
