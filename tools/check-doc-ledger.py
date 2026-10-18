#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""check-doc-ledger.py —— 文档账本一致性门禁（第三十三批）。

为什么需要它
    本仓库的账是**文档账**：docs/FEATURE-LEDGER.md（台账）＋ docs/ERRATA.md
    （错误台账 E 号）＋ docs/派工单-*.md。漂移已真实发生过两次，都是蓝总管
    对账时人工扫出来的（同一行 🔴 与「已修」自相矛盾；编号/交叉引用/commit
    短号全靠手写，没有任何机器校验）。人工扫一次半小时以上，扫完下次还漂。
    本脚本把机器能判的五条做成会红的门禁，**不重写任何账、不改任何文件**。

五条检查（每条都指名 文件:行号 与人话原因）
    D1  ERRATA 的 E 号唯一：`## E-1xx` / `### E-1xx` 标题（含续记标题）重复即报；
    D2  ERRATA 的 E 号无空洞：最小号到最大号之间必须连续；
    D3  引用可查：全仓 docs/**/*.md 里出现的 `E-\d+` 引用都要能在 ERRATA
        找到对应标题（E 号按整数比对，E-16 与 E-016 视为同一号）；
    D4  commit 短号可达：docs/FEATURE-LEDGER.md 与 docs/派工单-*.md 里
        反引号包裹的 7–40 位十六进制短号，`git cat-file --batch-check` 必须
        认得过（只查本地对象库，不联网）；
    D5  例外要留痕：已知不查的引用/短号一律写进 tools/doc-ledger-allowlist.txt
        （一行一条 `判据-值 # 理由`）；**allowlist 条目若已不再触发也要报**——
        否则烂账永远清不掉（修好了没人知道该删）。

只读（硬约束）
    脚本没有任何写文件/删文件调用（没有 --fix，不做自动修）。它只读账本、
    只读 git 对象库、只打印。发现问题的合法出路只有两条：把账改对（归蓝总管），
    或确有存量且蓝总管认可 ⇒ 写进 allowlist 并说明理由。

用法
    python3 tools/check-doc-ledger.py [仓库根]      # 默认模式：只报告，退出码恒 0
    python3 tools/check-doc-ledger.py --strict [仓库根]  # 有发现即退出码 1（CI 用）

退出码
    0 = 无发现，或默认模式（CI 之外的人扫账不该失败）；
    1 = --strict 且有发现（含 allowlist 失效）；
    2 = 用法/环境错误（不像仓库根、git 不可用等）。

输出
    每行发现固定前缀（方便 CI 摘）：`[doc-ledger] <判据> <文件>:<行> <原因>`；
    结尾一行汇总：`[doc-ledger] 检查 D1–D5：N 条发现`。
    路径一律正斜杠（Windows 反斜杠会污染输出与正则）。
    默认仓库根 = 脚本所在目录的上一级。
"""

import re
import subprocess
import sys
from pathlib import Path

try:  # Windows 控制台默认 GBK：个别字符编不出来时降级为替换，而不是让门禁自己崩
    sys.stdout.reconfigure(errors="replace")
except Exception:  # noqa: BLE001 - reconfigure 是尽力而为
    pass

ERRATA_REL = "docs/ERRATA.md"
LEDGER_REL = "docs/FEATURE-LEDGER.md"
WORKORDER_GLOB = "docs/派工单-*.md"
ALLOWLIST_REL = "tools/doc-ledger-allowlist.txt"

# ERRATA 的 E 标题：`## E-001 …` / `### E-005 修订（…）…`（仓内无 #### 级 E 标题，
# 放到 4 级是为防将来有人升标题级后悄悄漏扫）
E_TITLE_RE = re.compile(r"^#{2,4}\s+E-(\d+)", re.MULTILINE)
# 正文里的 E 号引用；左边不许再跟标识符字符（防 SUITE-12 之类误抓）
E_REF_RE = re.compile(r"(?<![A-Za-z0-9_-])E-(\d+)")
# 反引号包裹的 7–40 位十六进制短号（markdown 里代码/短号的标准形态；
# 不限定反引号会把日期数字 20261018 之类当短号误报）
SHORT_RE = re.compile(r"`([0-9a-f]{7,40})`")
# allowlist 行：`判据-值 # 理由`；D2 的值可以是号段 200-205
ALLOW_RE = re.compile(r"^\s*(D[1-5])\s*:\s*([0-9A-Za-z]+(?:-[0-9]+)?)\s*#\s*(.+?)\s*$")


def posix(rel: Path) -> str:
    """相对路径一律正斜杠。"""
    return rel.as_posix()


def snippet(line: str, limit: int = 60) -> str:
    """一行上下文（压空白、截断）——让人一眼看出这条发现是什么。"""
    s = " ".join(line.split())
    return s if len(s) <= limit else s[: limit - 1] + "…"


class Finding:
    """一条发现。key 用于 allowlist 匹配（D1/D2/D3 用整数号，D4 用小写短号）。"""

    __slots__ = ("criterion", "path", "line", "key", "reason")

    def __init__(self, criterion: str, path: str, line: int, key, reason: str) -> None:
        self.criterion = criterion
        self.path = path
        self.line = line
        self.key = key
        self.reason = reason

    def render(self) -> str:
        return f"[doc-ledger] {self.criterion} {self.path}:{self.line} {self.reason}"

    def match_key(self) -> tuple:
        return (self.criterion, self.key)


def parse_allowlist(path: Path) -> tuple[dict, list[str]]:
    """读 allowlist。返回 ((判据,值)->理由, 解析失败的行号列表)。

    行格式：`判据-值 # 理由`。空行与整行注释忽略。值按判据归一：
    D1/D2/D3 用整数（E-16 与 E-016 同一号）；D4 用小写十六进制原样。
    """
    entries: dict[tuple, str] = {}
    bad: list[str] = []
    if not path.is_file():
        return entries, bad
    for lineno, raw in enumerate(path.read_text(encoding="utf-8", errors="replace").splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        m = ALLOW_RE.match(line)
        if not m:
            bad.append(f"{posix(path)}:{lineno}")
            continue
        crit, value, reason = m.group(1), m.group(2), m.group(3)
        if crit in ("D1", "D2", "D3"):
            if "-" in value:  # 号段只允许 D2
                if crit != "D2" or not value.replace("-", "").isdigit():
                    bad.append(f"{posix(path)}:{lineno}")
                    continue
                lo, hi = (int(x) for x in value.split("-", 1))
                if lo > hi:
                    bad.append(f"{posix(path)}:{lineno}")
                    continue
                for n in range(lo, hi + 1):
                    entries[(crit, n)] = reason
            else:
                if not value.isdigit():
                    bad.append(f"{posix(path)}:{lineno}")
                    continue
                entries[(crit, int(value))] = reason
        else:  # D4：十六进制短号，小写归一
            entries[(crit, value.lower())] = reason
    return entries, bad


def check_d1_d2(repo: Path) -> tuple[list[Finding], set[int]]:
    """D1 标题唯一 + D2 号无空洞。返回 (发现列表, ERRATA 现有号集合)。"""
    findings: list[Finding] = []
    errata_rel = posix(Path(ERRATA_REL))
    text = (repo / ERRATA_REL).read_text(encoding="utf-8", errors="replace")
    # 逐行扫（保留行号）；标题行形如 `## E-001 · …`
    seen: dict[int, int] = {}  # 号 -> 首次行号
    count: dict[int, int] = {}  # 号 -> 已出现次数
    numbers: set[int] = set()
    for lineno, line in enumerate(text.splitlines(), 1):
        m = re.match(r"^#{2,4}\s+E-(\d+)", line)
        if not m:
            continue
        n = int(m.group(1))
        numbers.add(n)
        count[n] = count.get(n, 0) + 1
        if n in seen:
            findings.append(
                Finding(
                    "D1",
                    errata_rel,
                    lineno,
                    n,
                    f"E-{n:03d} 标题重复（该号第 {count[n]} 次出现；"
                    f"首次在 {errata_rel}:{seen[n]}）——账本号必须全仓唯一",
                )
            )
        else:
            seen[n] = lineno
    if numbers:
        lo, hi = min(numbers), max(numbers)
        for n in range(lo, hi + 1):
            if n not in numbers:
                findings.append(
                    Finding(
                        "D2",
                        errata_rel,
                        1,
                        n,
                        f"E-{n:03d} 缺号（现有号从 E-{lo:03d} 到 E-{hi:03d} 连续，"
                        f"缺 E-{n:03d}；删行或跳号都要有人解释）",
                    )
                )
    return findings, numbers


def check_d3(repo: Path, numbers: set[int]) -> list[Finding]:
    """D3 引用可查：docs/**/*.md 里所有 E 号引用都要能在 ERRATA 找到标题。"""
    findings: list[Finding] = []
    for p in sorted(repo.glob("docs/**/*.md")):
        rel = posix(p.relative_to(repo))
        try:
            text = p.read_text(encoding="utf-8", errors="replace")
        except OSError as e:
            findings.append(Finding("D3", rel, 1, -1, f"读不了（{e}）"))
            continue
        for lineno, line in enumerate(text.splitlines(), 1):
            for m in E_REF_RE.finditer(line):
                n = int(m.group(1))
                if n not in numbers:
                    findings.append(
                        Finding(
                            "D3",
                            rel,
                            lineno,
                            n,
                            f"引用了 E-{n:03d}，ERRATA 里没有这个号的标题"
                            f"（上下文「{snippet(line)}」）",
                        )
                    )
    return findings


def check_d4(repo: Path) -> list[Finding]:
    """D4 commit 短号可达：台账＋派工单里反引号包裹的 7–40 位十六进制，
    git cat-file 必须认得过。一次 --batch-check 批量校验（不逐个起进程）。"""
    targets = [repo / LEDGER_REL] + sorted(repo.glob(WORKORDER_GLOB))
    hits: list[tuple[str, int, str, str]] = []  # (rel, lineno, sha, 上下文)
    shas: list[str] = []
    for p in targets:
        if not p.is_file():
            continue
        rel = posix(p.relative_to(repo))
        for lineno, line in enumerate(
            p.read_text(encoding="utf-8", errors="replace").splitlines(), 1
        ):
            for m in SHORT_RE.finditer(line):
                sha = m.group(1)
                shas.append(sha)
                hits.append((rel, lineno, sha, snippet(line)))
    findings: list[Finding] = []
    if not hits:
        return findings
    proc = subprocess.run(
        ["git", "cat-file", "--batch-check=%(objectname) %(objecttype)"],
        input="\n".join(shas) + "\n",
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        cwd=str(repo),
    )
    if proc.returncode != 0:
        print(f"[环境错误] git cat-file 失败（repo={repo}）：{proc.stderr.strip()[:200]}")
        raise SystemExit(2)
    # --batch-check 的输出与输入逐行对应；missing 行形如 `<输入> missing`
    out_lines = proc.stdout.splitlines()
    for (rel, lineno, sha, ctx), out in zip(hits, out_lines):
        if out.endswith(" missing"):
            findings.append(
                Finding(
                    "D4",
                    rel,
                    lineno,
                    sha.lower(),
                    f"短号 {sha} 在 git 对象库里不存在（cat-file missing；"
                    f"上下文「{ctx}」）",
                )
            )
    return findings


def main() -> int:
    args = sys.argv[1:]
    strict = "--strict" in args
    rest = [a for a in args if not a.startswith("--")]
    if any(a.startswith("--") and a != "--strict" for a in args):
        print(__doc__)
        return 2
    repo = Path(rest[0]).resolve() if rest else Path(__file__).resolve().parent.parent
    if not (repo / ERRATA_REL).is_file():
        print(f"[环境错误] 不像仓库根（找不到 {ERRATA_REL}）：{repo}")
        return 2

    allow_path = repo / ALLOWLIST_REL
    allow, allow_bad = parse_allowlist(allow_path)
    for bad in allow_bad:
        print(f"[doc-ledger] D5 {bad} allowlist 行格式不对（应为「判据-值 # 理由」）")

    findings: list[Finding] = []
    d12, numbers = check_d1_d2(repo)
    findings += d12
    findings += check_d3(repo, numbers)
    findings += check_d4(repo)

    # allowlist 过滤：命中的抑制（不计发现）；没命中任何发现的 ⇒ D5 失效报警
    triggered = {f.match_key() for f in findings}
    kept = [f for f in findings if f.match_key() not in allow]
    suppressed = len(findings) - len(kept)
    for (crit, key), reason in sorted(allow.items(), key=lambda kv: (kv[0][0], str(kv[0][1]))):
        if (crit, key) not in triggered:
            lineno = 0
            if allow_path.is_file():
                for i, raw in enumerate(
                    allow_path.read_text(encoding="utf-8", errors="replace").splitlines(), 1
                ):
                    m = ALLOW_RE.match(raw.strip())
                    if not m:
                        continue
                    v = m.group(2)
                    if crit in ("D1", "D2", "D3") and "-" in v:
                        lo, hi = (int(x) for x in v.split("-", 1))
                        if crit == key[0] and lo <= int(key) <= hi:
                            lineno = i
                            break
                    elif (
                        m.group(1) == crit
                        and (
                            v.lower() == key
                            if crit == "D4"
                            else v.isdigit() and int(v) == key
                        )
                    ):
                        lineno = i
                        break
            shown = f"{key:03d}" if isinstance(key, int) else key
            kept.append(
                Finding(
                    "D5",
                    posix(Path(ALLOWLIST_REL)),
                    lineno,
                    (crit, key),
                    f"allowlist 条目 {crit}:{shown} 已不再触发（原登记理由：{reason}）"
                    f"——毛病已修，可删除该条",
                )
            )

    for f in kept:
        print(f.render())
    total = len(kept) + len(allow_bad)
    print(f"[doc-ledger] 检查 D1–D5：{total} 条发现")
    if suppressed:
        print(f"（另有 {suppressed} 条按 allowlist 豁免，未计入）")
    if strict and total > 0:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
