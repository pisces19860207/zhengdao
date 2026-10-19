# 质量契约（QUALITY-CONTRACT）

- 生效：2026-10-19（P2-3；来源：`证道-质量工程评估与改进方案-2026-10-19.md`）
- 适用：`main` 及所有支线；**新接手的 agent 先读这一页**，再动代码。
- 原则：**门禁的数字必须「同输入同输出」**（E-164）；依赖网络/时间的指标只进报告，不进闸。

## 1. 门禁清单（会红）

| 门禁 | 在哪跑 | 红了怎么办 |
|---|---|---|
| 全量单测 `:app:testDebugUnitTest` | CI `verify` | 修测试或修代码，**不许 `@Ignore` 蒙混** |
| R8 冒烟 `:app:assembleRelease` | CI（PR/手动） | 修混淆/资源收缩，别关 |
| so 页对齐／JNI 符号／同源（`tools/check-native-so.py`、`check-so-provenance.py`） | CI | 换回正确产物，别改断言 |
| 许可证明文逐字节（`tools/check-licenses.py`） | CI | 补/换 LICENSE 原文 |
| 账本一致性（`tools/check-doc-ledger.py --strict`） | CI | 补 ERRATA／改引用；D1–D5 全绿才算完 |
| R8 mapping 断言（`tools/check-r8-mapping.py`） | CI（PR/手动） | 关键类被改名＝真问题，修 keep 规则 |
| 重编比对（评测基线） | CI | 按流程更新基线并写理由 |
| **Lint 预算闸**（`tools/check-lint-budget.py`） | CI | 新增告警先**修**；确有理由才调基线（见 §4） |

## 2. 只出报告、不设门槛

| 项 | 命令 | 产物 |
|---|---|---|
| 覆盖率（JaCoCo） | `./gradlew :app:jacocoUnitTestReport` | `app/build/reports/jacoco/jacocoUnitTestReport/html/index.html` |
| Lint 明细 | `./gradlew :app:lintDebug` | `app/build/reports/lint-results-debug.html`（CI artifact `lint-report`） |
| Detekt（两条复杂度规则） | 见 `config/detekt/README.md`（需 JDK 21） | `app/build/reports/detekt/detekt.html` |
| 真机冒烟 | `powershell -ExecutionPolicy Bypass -File scripts/smoke/device-smoke.ps1` | 5 项 PASS/FAIL ＋ 截图 |

## 3. 基线数字（2026-10-19，改基线必须同时改这一节）

| 指标 | 值 | 说明 |
|---|---|---|
| 全量单测 | **257 suites / 2501 tests / 0 fail / 0 err** | 改动后必须仍为 0 fail |
| 跳过 | **13 条** | 全部经 `TestAssume`，带中文原因（软链环境 12 条 ＋ `ZHENGDAO_EVAL_KEY` 1 条）；连跑 3 轮恒 13 |
| 覆盖率 | line **33.3%**（9911/29807）／branch 36.4%／method 38.2% | 只报告；首跑值，后续只许升 |
| Lint 预算 | total **700**（errors **178** / warnings 484 / infos 38） | 11 条 `NewerVersionAvailable` 类**不计预算**（网络/版本相关） |
| Detekt | **175 条**（LongMethod 78 / Cyclomatic 97，90 文件） | 已冻结进 `config/detekt/baseline.xml`；不接 CI |

## 4. 改基线的规则

1. **能修就修**，尤其是 `error` 级——Error 不是债务。
2. 确实要上调（如接受新功能带来的 warning）：**同一笔提交**里改基线文件 ＋ 在 `docs/FEATURE-LEDGER.md` 当轮行里写明「为什么接受、接受几条」。
3. 下调永远欢迎：顺手把基线压到实测值。
4. 禁止：为了过 CI 而 `--write-baseline` 却不看数字；禁止 `lint --baseline` 式压制（那是闭眼，不是还债）。

## 5. 已知例外与理由

- `lint { disable += "ExpiredTargetSdkVersion" }`：本项目 targetSdk=36、只走 GitHub Releases、不上架 Play；谁把 targetSdk 调回 <33 该检查会立刻重新生效（保留不删正是为此）。
- **Lint 仍有 178 条 error 存量**（主要是 `LocalContextGetResourceValueCall` 历史用法）：这是**冻结的债**，不是标准；新代码一律用 `LocalResources`／`stringResource`，不许新增。
- 覆盖率暂无门槛：先有数字，再谈阈值（等 line 覆盖稳定上升后再评估）。

## 6. 快速自检（提交前）

```powershell
.\gradlew.bat :app:testDebugUnitTest --console=plain
python tools/check-doc-ledger.py --strict
.\gradlew.bat :app:lintDebug --console=plain ; python tools/check-lint-budget.py
```

全绿即可提；有任何一项红，先修或按 §4 走流程。
