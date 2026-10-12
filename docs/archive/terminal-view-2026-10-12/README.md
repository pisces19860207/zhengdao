# 终端 View 现代化原型 —— 封档（2026-10-12）

**状态**：⏸️ 暂停 / 已封档，**不要接着改**。
**停因（用户逐字）**：「算了，暂时不弄 terminal view 了，把 GPT-luna 弄的封档吧。」
**来源**：外部模型 GPT-luna 在**主工作树**里直接写的原型（未经仓库任何 agent 验收、也从未提交）。
**为什么存这里**：这批文件从未进过版本库；若不归档，工作树一清就只剩报告里的转述，原件无法复原。

---

## 一、原件是什么

**新增 4 个文件**（`files/` 下按原相对路径保存，sha256 与原件逐一比对过）：

| 文件 | 体积 | 说明 |
|---|---|---|
| `ZhengdaoTerminalView.java` | 6,854 B | 继承 vendored `TerminalView` 的子类：圆角卡片外观（`clipPath` 真裁 + 描边）+ 把输入/手势转发给两个控制器 |
| `ZhengdaoTerminalRenderer.java` | 16,921 B | 独立字符网格渲染器：逐格 `canvas.drawText`、宽字符/组合字符、反显、选区、三种光标；`TerminalRenderRunGrouper` 是它的 run 分组纯函数 |
| `ZhengdaoTerminalGestureController.java` | 13,282 B | 自写手势识别器（上游同包本来就有 `TerminalGestureController` + `GestureAndScaleRecognizer`；去空白/注释后的行级重合度实测仅 **12.6%**） |
| `TerminalRenderRunGrouper.java` | 1,442 B | 相邻「同样式/同选区/同光标状态且等宽」单元聚 run 的纯函数 |
| `TerminalRenderRunGrouperTest.java` | 2,611 B | 上面那个纯函数的单测（原路径 `app/src/test/java/com/termux/view/`） |

**被跟踪文件改动 5 个（+122 −33）** —— 完整补丁见同目录 `diff-tracked.patch`：

| 文件 | 改动 | 内容 |
|---|---|---|
| `app/src/androidTest/java/com/example/zhengdao/ui/ImeRegressionSelfCheckTest.kt` | +70 | 新增「四场景走 Zhengdao 缓冲」用例，与旧用例共用 `verifyInputScenarios(useModernView)` |
| `app/src/main/java/com/example/zhengdao/ui/ImeRegressionActivity.kt` | +48 | 接线新 View（开关 `modern_terminal_view`）+ 场景加一档 |
| `app/src/main/java/com/termux/view/TerminalView.java` | +23/−8 | 去 `final`、抽出 `getSelectionSelectors()` / `renderSelectionHandles()`、加包内访问器 |
| `app/src/main/java/com/example/zhengdao/ui/ImeProbe.kt` | +11 | 探针记录项 |
| `app/src/main/java/com/example/zhengdao/ui/SettingsDataActivity.kt` | +3 | 设置页文案「三个场景」→「四个场景」 |

**没封进这里的东西**：仓库根的 `证道设计报告\`（10 份外部报告，用户自己在看，未跟踪、保持原样）；`ZhengdaoTerminalInputController.java`（原型后期已被作者自己删掉，改为复用上游 `TerminalImeBridge`，无原件可封）。

---

## 二、做到哪一步 / 没做到

- **做到**：能编译（本机 `.\gradlew.bat --offline :app:assembleDebug` 曾通过）；圆角用 `clipPath` 真裁不靠遮；渲染稳态无新增分配；输入层已从「逐段拷贝 `TerminalImeBridge`」改为**复用它**。
- **没做到 / 未验**：
  1. **生产未接线** —— 全仓只有 `ImeRegressionActivity.kt` 引用它，生产 `TerminalActivity` 一行没动；
  2. **真机未验收** —— 四个 IME 场景（候选词上屏 / 退格删组合字符 / 连续快速输入 …）在真机上从未跑过一次；
  3. 渲染仍是逐格 `drawText`，批量化（`drawTextRun`）只完成了「分组纯函数」这一半；
  4. 手势那 323 行**没必要** —— 上游同包现成可用，自写等于多一份要维护的实现。

---

## 三、怎么恢复（真要接着做时）

```powershell
cd C:\Users\guoli\AndroidStudioProjects\zhengdao
python tools\zd.py preflight -k 终端,渲染,IME          # 先过闸：退出码 0 才动手
git apply docs\archive\terminal-view-2026-10-12\diff-tracked.patch
# 再把 files\ 下的 4 个 .java 按原相对路径复制回 app\src\main\java\com\termux\view\
# 以及 app\src\test\java\com\termux\view\
```

恢复前**先问用户**（台账 `docs/FEATURE-LEDGER.md` §D 已登记「已封档，要复活必须先问用户」）。
真机四场景的期望输出表、以及当初为什么这么拆，见 GPT-luna 的报告：
`证道设计报告\证道App审计报告事实核验与终端显示交互现代化方案-2026-10-12.md`。

---

## 四、封档时的验收动作（可复查）

| 动作 | 结果 |
|---|---|
| 4 个新文件 + 1 个测试复制进归档 | sha256 原件 vs 副本 **5/5 OK** |
| 被跟踪文件改动存成补丁 | `diff-tracked.patch` 16,286 B，含 5 个文件 |
| 工作树被跟踪文件还原 | `git diff` 为空 |
| 新增文件从工作树移除 | `app/src/main/java/com/termux/view/Zhengdao*`、`TerminalRenderRunGrouper.java`、`app/src/test/java/com/termux/` 均已不存在 |
| 单测树完好 | `app/src/test/java/com/example/zhengdao/**` 仍是 **68 个 .kt**（本次未触及） |
