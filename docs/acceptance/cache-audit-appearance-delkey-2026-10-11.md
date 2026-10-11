# 缓存口径 / 终端外观卡 / DEL 键 —— 真机验收（2026-10-11）

**分支**：`fix/cache-audit-termcard`（基线 `c0fe11c`）
**真机**：`AD3J023824001723`（Android 16 / arm64-v8a），装的是 debug 包 `zhengdao-2.0.11-debug.apk`（44,752,814 B）

---

## 1. 起因：用户报的三个现象

1. **ffmpeg 谜团** —— 环境里明明有 ffmpeg，为什么「缓存清理」把它列成可清除？清完 hermes 又报 ffmpeg 没了？而且「如果环境里有，为什么 hermes 不认？」
2. 「hermes 的安装包要可以清除，免得重新安装的时候又拿这个显示出问题导致安装失败。」
3. 设置页「终端外观」卡要**只留昨天改的那些不可选**，保留舒适/紧凑的选择。
4. 终端里的 **`⌫` 键要改成 Delete**（退格键手机键盘上本来就有）。

## 2. 查清了什么（ffmpeg 那条）

**三个事实叠在一起，才变成用户看到的样子。**

| # | 事实 | 证据 |
|---|---|---|
| 1 | 系统层（rootfs）**确实自带** ffmpeg | `files/rootfs/usr/bin/{ffmpeg,ffplay,ffprobe}` + `files/rootfs/usr/share/ffmpeg` |
| 2 | 但 hermes 不用系统那份，只认自己工具库里的 | `hermes_cli/pm/install.py:951 activate()` 把 store 工具目录**插到 PATH 最前**（docstring：「never a user copy that sorts earlier」）；启动自检 `pm/install.py:901 check()` → `drift()`（`:863`）遍历 lockfile 判「包在不在」 |
| 3 | 而被清掉的正是它自己要的那份 | 「可选工具清理」把 `chromium / ffmpeg / cua-driver / agent-browser` 四个目录删了（`files/home/.hermes/tools/` 只剩 python/node/uv/npm/ripgrep），facts.json 里却仍注册着它们 |

**关键一行**（`hermes_cli/pm/install.py` 的 `drift()`）：

```python
if package.optional or package.internal:
    continue          # ← optional 包跳过，不报
```

ffmpeg 报了 ⇒ **它在 hermes 里是必需包**（非 optional）；chromium / cua-driver / agent-browser 是 optional（所以只报 ffmpeg 一个）。
报错原文（真机截图 `05-...webp`）：

```
⚠ install out of sync (ffmpeg: not installed or outdated) — run `hermes pm install`
```

**两个错误都在 App 这边**：
- 把 hermes 的**必需包**（ffmpeg）当成可清的可选工具；
- 说明文案写「删了 hermes 用到时会自己重新下载」——**实际不会**：`facts.json` 只是注册表，删目录不会触发重下，得跑 `hermes pm install`。

## 3. 改了什么

| 文件 | 改动 |
|---|---|
| `app/src/main/java/com/example/zhengdao/terminal/StorageAudit.kt` | 新增 `REQUIRED_TOOL_NAMES = listOf("ffmpeg")`（从可选名单移出）；`ToolKind` 加 `REQUIRED`；新增 `missingRegisteredPackages()`（读 `facts.json`，报「注册了但目录不在」的包）；新增 `cleanAgentScriptCache()`（只清公共区安装脚本 + 宿主指纹 + guest 私有副本，不动 `installed.json`）；`items()` 新增「hermes 自检」组，「公共区」拆成「安装脚本（可清）」与「工作区（用户数据）」 |
| `app/src/main/java/com/example/zhengdao/ui/SettingsScreen.kt` | 存储占用卡：可选工具对话框去掉 ffmpeg 并说明原因；新增「清理安装脚本缓存（下次安装重新下载）」入口。终端外观卡：**字号 / 画布留白 / 配色 改成只读默认值**，只留「行距」两个芯片可选 |
| `app/src/main/res/layout/term_keys.xml` + `layout-w600dp/term_keys.xml` | `key_backspace` 文字 `⌫` → **`DEL`**（id 与 17 键顺序契约不变） |
| `app/src/main/java/com/example/zhengdao/TerminalActivity.kt` | `key_backspace` 点击从「写 `0x7F`（退格）」改为 **`sendKey("\u001b[3~")`（Delete）**；不再调 `KnowledgeBaseHints.onUserBackspace()`（前向删除语义不同） |
| `app/src/test/java/com/example/zhengdao/terminal/StorageAuditTest.kt` | ffmpeg 断言改 `REQUIRED`；清理测试断言「只删可选工具，ffmpeg 与核心运行时原地不动」；新增自检缺口用例 |

一处判断需要用户确认：用户原话是「把终端外观卡，只留昨天改的那些作为不可选择项，保留舒适和紧凑的选择就行」——按这句话，**字号也做成了只读默认**（`12`）；若本意只是配色/留白只读、字号仍要能选，回退点就是 `SettingsScreen.kt` 里那一行 `InfoRow` 换回 `FilterChip2`。

## 4. 真机验收

| # | 项目 | 结果 |
|---|---|---|
| 1 | 存储占用卡出现「hermes 自检」组，如实列出四个缺口 | ✅ `agent-browser-0.26.0-linux-arm64` / `chromium-1208` / `cua-driver-0.21.0-linux-arm64` / `ffmpeg-9.0.1-linux-arm64` 各一行「目录不在 · 跑 hermes pm install 补」，行尾 `0 MB · 保留`（图 `01`） |
| 2 | ffmpeg 不再出现在可清组；hermes 工具组全部「保留」 | ✅ python / node / uv / npm / ripgrep 全 `保留`（图 `01`） |
| 3 | 终端外观卡只读默认 + 行距可选 | ✅ 语义树逐字：字号 `12（默认）`、画布留白 `上下 8 dp`、配色 `Catppuccin Mocha`、行距 `舒适`/`紧凑`（图 `02`） |
| 4 | 键条第 17 键标签为 `DEL` | ✅ `uiautomator`：`key_backspace text='DEL' bounds=[1181,2652][1266,2820]`；键条序列 `ESC TAB CTRL SHIFT ^C PGUP PGDN HOME END DEL`（图 `03`） |
| 5 | **DEL 键真的发 Delete 序列** | ✅ 新 tmux 窗口跑 `cat -v` → 点 DEL → 屏幕出现 **`^[[3~`**（= `ESC [ 3 ~`），状态栏 `1:cat*`（图 `04`） |
| 6 | 测试过程没动用户会话 | ✅ 全程在新 tmux 窗口操作，收尾 `exit` 关掉它，回落到 `0:[tmux]` 的 Hermes 窗口（图 `05`） |

构建与单测：`gradlew.bat :app:testDebugUnitTest :app:assembleDebug` **BUILD SUCCESSFUL in 38s**（单测全绿）。

## 5. 没验到 / 已知限制

- 「清理安装脚本缓存」按钮**没点**（会删掉 52 KB 的 `hermes-install.sh`，下次装 hermes 要重新下载）；只验了它出现在设置页、文案与实际删除范围一致（代码审读）。
- 「hermes 自检」只读 `facts.json`：如果某个包既没注册、目录也不在，这里不会显示（不是事实源）。
- 系统层那份 `/usr/bin/ffmpeg` 仍然存在且可用（终端里直接敲 `ffmpeg` 走的是它）——hermes 不认它是**设计如此**，不是缺陷。

## 6. 复现命令

```bash
# 看 facts.json 里注册了什么、目录在不在
adb shell run-as com.example.zhengdao sh -c 'cat files/home/.hermes/tools/facts.json'
adb shell run-as com.example.zhengdao sh -c 'ls files/home/.hermes/tools/'

# 系统层那份 ffmpeg
adb shell run-as com.example.zhengdao sh -c 'ls files/rootfs/usr/bin/ffmpeg'

# hermes 的自检口径（optional 包会被 continue 跳过）
adb shell run-as com.example.zhengdao sh -c 'sed -n "855,880p" files/home/.hermes/hermes-agent/hermes_cli/pm/install.py'

# 键条节点（含 DEL 标签与 bounds）
adb shell uiautomator dump /sdcard/k.xml && adb shell cat /sdcard/k.xml | tr '>' '\n' | grep key_backspace

# DEL 行为：新 tmux 窗口里 cat -v，点 DEL，应出现 ^[[3~
#   （粘滞 CTRL + b，再按 c = 新窗口）
```

## 7. 图片资产

| 文件 | 内容 |
|---|---|
| `01-storage-audit-hermes-check.webp` | 存储占用卡：新增「hermes 自检」组列出四个缺口；hermes 工具组全部「保留」 |
| `02-terminal-appearance-readonly.webp` | 终端外观卡：字号/画布留白/配色 只读默认值 + 行距两个芯片 |
| `03-keybar-del-label.webp` | 键条第 17 键已是 `DEL`（这张还顺带拍到新 tmux 窗口建好了） |
| `04-del-key-sends-csi3tilde.webp` | `cat -v` 下点 DEL → 上屏 `^[[3~` |
| `05-hermes-out-of-sync-ffmpeg.webp` | hermes 的 `install out of sync (ffmpeg: …)` 原文 + 收尾回到用户的 Hermes 窗口 |
