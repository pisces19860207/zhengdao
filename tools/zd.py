#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""zd.py —— 证道（zhengdao）仓库的**统一工具入口**。

为什么要有它
    这个仓库同时有三个 AI agent 在干活（WorkBuddy / ZCode / DeepSeek Harness），
    各家的壳不一样（PowerShell / bash / 直接调 git），同一件事被写了三遍、还各自跑偏。
    统一成这**一个** Python 入口以后：谁都能跑、跑出来的东西一样、修也只修一处。

用法（在任意 worktree 里跑，命令都一样）
    python tools/zd.py preflight [-k 关键词]... [-p 路径]... [--max-behind N] [--no-fetch]
    python tools/zd.py doctor
    python tools/zd.py install-hooks
    python tools/zd.py hooks-status

退出码
    preflight     0 = 未发现阻塞，可以开工 ／ 1 = 有阻塞项 ／ 2 = 不在仓库或找不到基准
    doctor        0 = 环境健康 ／ 1 = 有 ✗ 项（✗ 之外的 ！ 只是提醒）
    install-hooks 0 = 装好了 ／ 2 = 不在仓库
    hooks-status  0 = 与库里一致 ／ 1 = 没装或与库里不一致 ／ 2 = 不在仓库

规矩写在 docs/协作规约.md，开工须知见 AGENTS.md。只依赖标准库。
"""

from __future__ import annotations

import os
import re
import subprocess
import sys
from pathlib import Path

GIT = ["git", "-c", "core.quotePath=false"]
BASE_REF = "origin/main"

# ── 输出小工具 ────────────────────────────────────────────────────────────────
# Windows 控制台默认 cp936，中文 + ✓/✗ 容易炸；统一按 utf-8 写，写不出去就替换。
try:  # pragma: no cover - 取决于运行环境
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")  # type: ignore[attr-defined]
except Exception:
    pass

_USE_COLOR = sys.stdout.isatty() and os.environ.get("NO_COLOR") is None
_C = {"red": "\033[31m", "green": "\033[32m", "yellow": "\033[33m", "cyan": "\033[36m", "gray": "\033[90m"}


def say(msg: str = "", color: str | None = None) -> None:
    if color and _USE_COLOR:
        print(f"{_C.get(color, '')}{msg}\033[0m")
    else:
        print(msg)


def sec(title: str) -> None:
    say()
    say(f"==== {title} ====", "cyan")


# ── git 小工具 ────────────────────────────────────────────────────────────────
def git(*args: str, cwd: str | Path | None = None, check: bool = False) -> tuple[int, str]:
    """跑一条 git 命令，返回 (退出码, stdout)。永不抛异常（除了编码都归一到 str）。"""
    try:
        p = subprocess.run(
            GIT + list(args),
            cwd=str(cwd) if cwd else None,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
    except FileNotFoundError:
        return 127, ""
    if check and p.returncode != 0:
        raise RuntimeError(f"git {' '.join(args)} 失败（exit {p.returncode}）")
    return p.returncode, (p.stdout or "")


def git_lines(*args: str, cwd: str | Path | None = None) -> list[str]:
    rc, out = git(*args, cwd=cwd)
    if rc != 0 or not out:
        return []
    return [ln.rstrip("\r") for ln in out.splitlines() if ln.strip()]


def repo_root() -> str | None:
    rc, out = git("rev-parse", "--show-toplevel")
    if rc != 0 or not out.strip():
        return None
    return out.strip()


def common_git_dir(root: str) -> str | None:
    rc, out = git("rev-parse", "--path-format=absolute", "--git-common-dir", cwd=root)
    if rc != 0 or not out.strip():
        return None
    return out.strip().replace("\\", "/")


# ── preflight ────────────────────────────────────────────────────────────────
def cmd_preflight(argv: list[str]) -> int:
    keywords: list[str] = []
    paths: list[str] = []
    max_behind = 0
    no_fetch = False

    i = 0
    while i < len(argv):
        a = argv[i]
        if a in ("-k", "--keyword", "--keywords"):
            i += 1
            while i < len(argv) and not argv[i].startswith("-"):
                keywords.extend(x for x in re.split(r"[,，]", argv[i]) if x.strip())
                i += 1
            continue
        if a in ("-p", "--path", "--paths"):
            i += 1
            while i < len(argv) and not argv[i].startswith("-"):
                paths.extend(x for x in re.split(r"[,，]", argv[i]) if x.strip())
                i += 1
            continue
        if a == "--max-behind":
            i += 1
            max_behind = int(argv[i]) if i < len(argv) else 0
            i += 1
            continue
        if a == "--no-fetch":
            no_fetch = True
            i += 1
            continue
        if a in ("-h", "--help"):
            say(__doc__)
            return 0
        say(f"！不认识的参数：{a}（用法见 python tools/zd.py preflight -h）", "yellow")
        return 2

    root = repo_root()
    if not root:
        say("！不在 git 仓库里，无法核对", "red")
        return 2
    os.chdir(root)

    blockers: list[str] = []

    sec("0. 同步远端")
    if not no_fetch:
        rc, _ = git("fetch", "--prune", "--quiet", "origin")
        if rc != 0:
            say("  ！git fetch 失败（离线？）——下面的判定基于本地上次的 origin/main，可能已过时", "yellow")
    rc, base_sha = git("rev-parse", "--short", BASE_REF)
    if rc != 0:
        say(f"！找不到 {BASE_REF}，无法比对", "red")
        return 2
    say(f"  基准 {BASE_REF} = {base_sha.strip()}")

    sec("1. 这个仓库有几个工作树、各自停在哪")
    wts = _parse_worktrees()
    for w in wts:
        behind = ahead = "?"
        rc, lr = git("rev-list", "--left-right", "--count", f"{BASE_REF}...HEAD", cwd=w["path"])
        if rc == 0 and lr.strip():
            parts = lr.split()
            if len(parts) >= 2:
                behind, ahead = parts[0], parts[1]
        rc, st = git("status", "--porcelain", cwd=w["path"])
        dirty = len([ln for ln in st.splitlines() if ln.strip()])
        mark = ""
        if behind not in ("?", "") and int(behind) > 0:
            mark += f" 落后{behind}"
        if dirty:
            mark += f" 未提交{dirty}"
        if not mark:
            mark = " 干净且同步"
        cur = " <-当前" if os.path.normcase(w["path"]) == os.path.normcase(root) else ""
        say(f"  {w['path']:<46} {w['branch']:<34} {w['head'][:7]}{mark}{cur}")

    sec("2. 我在哪、落后多少")
    rc, br = git("symbolic-ref", "--short", "-q", "HEAD")
    branch = br.strip() if rc == 0 and br.strip() else "(detached HEAD)"
    _, cur_head = git("rev-parse", "--short", "HEAD")
    cur_head = cur_head.strip()
    say(f"  分支 = {branch}     HEAD = {cur_head}")
    rc, lr = git("rev-list", "--left-right", "--count", f"{BASE_REF}...HEAD")
    parts = lr.split() if rc == 0 else ["?", "?"]
    behind_i = int(parts[0]) if parts and parts[0].isdigit() else 0
    ahead_i = int(parts[1]) if len(parts) > 1 and parts[1].isdigit() else 0
    say(f"  相对 {BASE_REF}：落后 {behind_i} 个提交，领先 {ahead_i} 个提交")
    if behind_i > max_behind:
        blockers.append(f"HEAD 落后 {BASE_REF} {behind_i} 个提交（先 git fetch + rebase/merge 再动手）")
        say("  ✗ 你落后了：你看到的代码不是大家看到的代码，在这里动手就是在重做别人已经做完的事", "red")
        say("    先看一眼别人改了什么：", "yellow")
        for ln in git_lines("log", "--oneline", "--date=short", "--pretty=format:%h %ad %s", f"{cur_head}..{BASE_REF}")[:25]:
            say(f"      {ln}", "gray")
    else:
        say("  ✓ 没有落后", "green")
    rc, st = git("status", "--porcelain")
    dirty_cur = len([ln for ln in st.splitlines() if ln.strip()])
    if dirty_cur:
        say(f"  ！本工作树有 {dirty_cur} 个未提交改动——它们随时可能被 checkout/rebase/清理冲掉", "yellow")

    sec("3. 这是不是已经做过了（关键词反查）")
    if not keywords:
        say("  （没给 -k，跳过。开工前建议带上功能关键词再跑一次）", "gray")
    else:
        for kw in keywords:
            code_hits = git_lines("log", "--all", "--date=short", "--pretty=format:%h %ad %s", f"-S{kw}")
            msg_hits = git_lines("log", "--all", "--date=short", "--pretty=format:%h %ad %s", f"--grep={kw}")
            hits = list(dict.fromkeys(code_hits + msg_hits))
            if not hits:
                say(f"  [{kw}] 无命中", "green")
            else:
                blockers.append(f"关键词 [{kw}] 在历史里有 {len(hits)} 条命中，先确认不是重复劳动")
                say(f"  [{kw}] 命中 {len(hits)} 条 —— 先读这些再决定要不要重写：", "red")
                for ln in hits[:12]:
                    say(f"      {ln}", "gray")

    sec("4. 复活检测（你要新增的文件，历史上是不是被删过）")
    if not paths:
        say("  （没给 -p，跳过。要新建文件时建议带上它的路径再跑一次）", "gray")
    else:
        for p in paths:
            dels = git_lines("log", "--all", "--diff-filter=D", "--date=short", "--pretty=format:%h %ad %s", "--", p)
            if not dels:
                say(f"  {p} —— 没被删过", "green")
            else:
                blockers.append(f"{p} 在历史上被删除过，复活它必须先问用户")
                say(f"  ✗ {p} 被删除过：", "red")
                for ln in dels[:6]:
                    say(f"      {ln}", "gray")

    sec("5. 结论")
    if not blockers:
        say("  ✓ 未发现阻塞项。开工前再去 docs/FEATURE-LEDGER.md 的 §2 扫一眼这个功能在不在。", "green")
        return 0
    say(f"  ✗ 发现 {len(blockers)} 个需要先处理的问题：", "red")
    for b in blockers:
        say(f"      - {b}", "red")
    say("  处理顺序：先同步，再查重，最后才写代码。", "yellow")
    return 1


def _parse_worktrees() -> list[dict]:
    """解析 `git worktree list --porcelain`。"""
    wts: list[dict] = []
    cur: dict | None = None
    for ln in git_lines("worktree", "list", "--porcelain"):
        if ln.startswith("worktree "):
            if cur:
                wts.append(cur)
            cur = {"path": ln[len("worktree "):], "head": "", "branch": "(detached)"}
        elif cur is None:
            continue
        elif ln.startswith("HEAD "):
            cur["head"] = ln[len("HEAD "):]
        elif ln.startswith("branch "):
            cur["branch"] = ln[len("branch "):].replace("refs/heads/", "")
        elif ln.startswith("detached"):
            cur["branch"] = "(detached)"
    if cur:
        wts.append(cur)
    return wts


# ── doctor ───────────────────────────────────────────────────────────────────
class Checks:
    def __init__(self) -> None:
        self.rows: list[tuple[str, str, str]] = []  # (标记, 标题, 细节)
        self.bad = 0

    def ok(self, title: str, detail: str = "") -> None:
        self.rows.append(("✓", title, detail))

    def warn(self, title: str, detail: str = "") -> None:
        self.rows.append(("！", title, detail))

    def fail(self, title: str, detail: str = "") -> None:
        self.rows.append(("✗", title, detail))
        self.bad += 1


def _which(*names: str) -> str | None:
    from shutil import which

    for n in names:
        p = which(n)
        if p:
            return p
    return None


def cmd_doctor(_argv: list[str]) -> int:
    c = Checks()
    root = repo_root()
    if not root:
        say("！不在 git 仓库里", "red")
        return 2
    os.chdir(root)

    # 1. 基础环境
    c.ok("python", f"{sys.version.split()[0]} ({sys.executable})")
    gv = git("--version")[1].strip()
    c.ok("git", gv or "?")
    c.ok("仓库根", root)

    # 2. 我在哪 / 与 origin/main 的关系
    rc, br = git("symbolic-ref", "--short", "-q", "HEAD")
    branch = br.strip() if rc == 0 and br.strip() else "(detached HEAD)"
    _, head = git("rev-parse", "--short", "HEAD")
    _, lr = git("rev-list", "--left-right", "--count", f"{BASE_REF}...HEAD")
    parts = lr.split() if lr.strip() else ["?", "?"]
    behind = parts[0] if parts else "?"
    ahead = parts[1] if len(parts) > 1 else "?"
    _, st = git("status", "--porcelain")
    dirty = len([ln for ln in st.splitlines() if ln.strip()])
    detail = f"{branch} @{head.strip()} 落后{behind}/领先{ahead} 未提交{dirty}"
    (c.warn if behind not in ("0", "?") or dirty else c.ok)("工作树", detail)
    say(f"  工作树数：{len(_parse_worktrees())}")

    # 3. 钩子
    cdir = common_git_dir(root)
    src_hook = Path(root) / "tools" / "hooks" / "pre-commit"
    if cdir:
        dst_hook = Path(cdir) / "hooks" / "pre-commit"
        rc, hp = git("config", "--get", "core.hooksPath")
        if hp.strip():
            c.warn("core.hooksPath 被设成了别的目录", f"{hp.strip()}（tools/zd.py install-hooks 装的可能没生效）")
        if not dst_hook.exists():
            c.fail("pre-commit 钩子未安装", "跑 python tools/zd.py install-hooks")
        elif src_hook.exists():
            a = src_hook.read_bytes().replace(b"\r\n", b"\n")
            b = dst_hook.read_bytes().replace(b"\r\n", b"\n")
            if a == b:
                c.ok("pre-commit 钩子", f"已装且与库里一致（{len(b)} 字节，{cdir}/hooks）")
            else:
                c.fail("pre-commit 钩子与库里不一致", "跑 python tools/zd.py install-hooks 覆盖")
        else:
            c.warn("库里没有 tools/hooks/pre-commit", "钩子源码缺失")
    else:
        c.fail("找不到共享 .git 目录", "")

    # 4. JDK / Gradle
    java_home = os.environ.get("JAVA_HOME", "")
    if java_home and (Path(java_home) / "bin").is_dir():
        c.ok("JAVA_HOME", java_home)
    elif java_home:
        c.fail("JAVA_HOME 指向的目录不对", java_home)
    else:
        c.warn("JAVA_HOME 未设置", "gradlew 可能起不来；本机常用 D:\\Program Files\\Android\\Android Studio\\jbr")
    if (Path(root) / "gradlew.bat").exists() or (Path(root) / "gradlew").exists():
        c.ok("gradlew", "存在")
    else:
        c.fail("gradlew 缺失", "")

    # 5. Android SDK / 工具
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or str(
        Path(os.environ.get("LOCALAPPDATA", "")) / "Android" / "Sdk"
    )
    if Path(sdk).is_dir():
        c.ok("Android SDK", sdk)
        adb = Path(sdk) / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")
        (c.ok if adb.exists() else c.warn)("adb", str(adb) if adb.exists() else "没找到（真机验收要用）")
        bt = sorted((Path(sdk) / "build-tools").glob("*/apksigner*")) if (Path(sdk) / "build-tools").is_dir() else []
        (c.ok if bt else c.warn)("apksigner", str(bt[-1]) if bt else "没找到（验产物签名要用）")
        ndks = sorted(p.name for p in (Path(sdk) / "ndk").glob("*")) if (Path(sdk) / "ndk").is_dir() else []
        (c.ok if ndks else c.warn)("NDK", ", ".join(ndks) if ndks else "没找到")
    else:
        c.warn("找不到 Android SDK", sdk)

    # 6. 签名钥匙 + 与 build.yml 期望值比对
    ks = Path(os.path.expanduser("~")) / ".android" / "debug.keystore"
    expected = ""
    wf = Path(root) / ".github" / "workflows" / "build.yml"
    if wf.exists():
        m = re.search(r"EXPECTED_SHA256:\s*([0-9A-Fa-f]{64})", wf.read_text(encoding="utf-8", errors="replace"))
        expected = m.group(1).upper() if m else ""
    if not ks.exists():
        c.fail("找不到 ~/.android/debug.keystore", str(ks))
    else:
        kt = _which("keytool") or (str(Path(java_home) / "bin" / "keytool.exe") if java_home else None)
        if kt and Path(kt).exists():
            try:
                out = subprocess.run(
                    [kt, "-list", "-v", "-keystore", str(ks), "-storepass", "android", "-alias", "androiddebugkey"],
                    stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, encoding="utf-8", errors="replace",
                ).stdout
                m = re.search(r"SHA256:\s*([0-9A-Fa-f:]{60,})", out)
                fp = m.group(1).replace(":", "").upper() if m else ""
                if not fp:
                    c.warn("读不出 keystore 指纹", "keytool 输出里没有 SHA256")
                elif expected and fp != expected:
                    c.fail("本机 debug keystore ≠ build.yml 的 EXPECTED_SHA256", f"本机 {fp[:16]}… / 期望 {expected[:16]}…")
                else:
                    c.ok("本机 debug keystore", f"{fp[:16]}…（与 build.yml 期望一致）" if expected else f"{fp[:16]}…")
            except Exception as e:  # pragma: no cover
                c.warn("keytool 调用失败", str(e))
        else:
            c.warn("找不到 keytool", "无法核对 keystore 指纹")
    if expected:
        c.ok("build.yml 里的 EXPECTED_SHA256", expected[:16] + "…")
    else:
        c.warn("build.yml 里读不到 EXPECTED_SHA256", str(wf))

    # 7. workflow YAML 可解析 + 文档齐不齐
    try:
        import yaml  # type: ignore

        if wf.exists():
            d = yaml.safe_load(wf.read_text(encoding="utf-8"))
            jobs = d.get("jobs", {})
            c.ok("build.yml 可解析", f"{len(jobs)} 个 job：" + "、".join(f"{k}({len(v.get('steps', []))} 步)" for k, v in jobs.items()))
        else:
            c.warn("没有 .github/workflows/build.yml", "")
    except ImportError:
        c.warn("没装 PyYAML", "python -m pip install pyyaml（仅影响本项检查）")
    except Exception as e:
        c.fail("build.yml 解析失败", str(e))

    for rel in ("AGENTS.md", "docs/协作规约.md", "docs/FEATURE-LEDGER.md", "docs/ERRATA.md", "tools/zd.py"):
        p = Path(root) / rel
        (c.ok if p.exists() else c.warn)(rel, f"{p.stat().st_size} 字节" if p.exists() else "缺少")

    # 8. 汇总
    sec("体检结果")
    for mark, title, detail in c.rows:
        color = {"✓": "green", "！": "yellow", "✗": "red"}[mark]
        say(f"  {mark} {title}" + (f" —— {detail}" if detail else ""), color)
    say()
    if c.bad:
        say(f"  ✗ 有 {c.bad} 项需要处理", "red")
        return 1
    say("  ✓ 没有 ✗ 项（！是提醒，不影响）", "green")
    return 0


# ── install-hooks / hooks-status ─────────────────────────────────────────────
def _hook_pairs(root: str) -> tuple[Path, Path] | None:
    cdir = common_git_dir(root)
    if not cdir:
        return None
    return Path(root) / "tools" / "hooks", Path(cdir) / "hooks"


def cmd_install_hooks(_argv: list[str]) -> int:
    root = repo_root()
    if not root:
        say("！不在 git 仓库里", "red")
        return 2
    pair = _hook_pairs(root)
    if not pair:
        say("！找不到共享 .git 目录", "red")
        return 2
    src_dir, dst_dir = pair
    if not src_dir.is_dir() or not any(src_dir.iterdir()):
        say(f"！{src_dir} 里没有钩子源码", "red")
        return 2
    dst_dir.mkdir(parents=True, exist_ok=True)
    say(f"共享 .git 目录：{dst_dir}（所有 worktree 共用同一套钩子）", "gray")
    for s in sorted(src_dir.iterdir()):
        if not s.is_file():
            continue
        # 钩子必须 LF、无 BOM —— git for Windows 用 sh 跑，CRLF 会让 shebang 失效
        data = s.read_bytes().replace(b"\r\n", b"\n")
        if data.startswith(b"\xef\xbb\xbf"):
            data = data[3:]
        dst = dst_dir / s.name
        dst.write_bytes(data)
        try:
            os.chmod(dst, 0o755)
        except Exception:
            pass
        say(f"  ✓ 已安装 {s.name} → {dst}（{len(data)} 字节，LF）", "green")
    say("  绕过闸门（确有正当理由时）：环境变量 ZHENGDAO_HOOK_BYPASS=1", "yellow")
    return 0


def cmd_hooks_status(_argv: list[str]) -> int:
    root = repo_root()
    if not root:
        say("！不在 git 仓库里", "red")
        return 2
    pair = _hook_pairs(root)
    if not pair:
        say("！找不到共享 .git 目录", "red")
        return 2
    src_dir, dst_dir = pair
    rc, hp = git("config", "--get", "core.hooksPath")
    if hp.strip():
        say(f"！core.hooksPath = {hp.strip()}（钩子未必从 .git/hooks 走）", "yellow")
    bad = 0
    for s in sorted(src_dir.iterdir()) if src_dir.is_dir() else []:
        if not s.is_file():
            continue
        dst = dst_dir / s.name
        a = s.read_bytes().replace(b"\r\n", b"\n")
        if not dst.exists():
            say(f"  ✗ {s.name}：未安装（跑 python tools/zd.py install-hooks）", "red")
            bad += 1
            continue
        b = dst.read_bytes().replace(b"\r\n", b"\n")
        if a == b:
            say(f"  ✓ {s.name}：已装且与库里一致（{len(b)} 字节）", "green")
        else:
            say(f"  ✗ {s.name}：与库里不一致（库里 {len(a)} B / 装的 {len(b)} B）", "red")
            bad += 1
    return 1 if bad else 0


# ── 入口 ─────────────────────────────────────────────────────────────────────
COMMANDS = {
    "preflight": cmd_preflight,
    "doctor": cmd_doctor,
    "install-hooks": cmd_install_hooks,
    "hooks-status": cmd_hooks_status,
}


def main(argv: list[str]) -> int:
    if not argv or argv[0] in ("-h", "--help", "help"):
        say(__doc__)
        return 0
    cmd, rest = argv[0], argv[1:]
    fn = COMMANDS.get(cmd)
    if not fn:
        say(f"！不认识的命令：{cmd}", "red")
        say("可用命令：" + "、".join(COMMANDS))
        return 2
    return fn(rest)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
