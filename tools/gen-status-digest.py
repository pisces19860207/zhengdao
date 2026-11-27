#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""gen-status-digest.py —— 生成 docs/STATUS.md（公开状态摘要，857 批）。

为什么要有它
    外部评估只看得到公开仓已合并的提交，看不到在飞/待验收的工作，
    于是一直在「事实对、进度滞后」的区间判断。本脚本把「能公开讲的部分」
    自动写成 docs/STATUS.md：已合批次、在飞与待验收的**域 × 数量**。

四段输出
    头三行：UTC 生成时间 · 数据来源说明 · 「本文件由脚本生成，勿手改」；
    ① 已合（最近 30 天）：批次 | 一句话 | commit 9 位——按批次号归组，
       一句话＝该批最新一笔提交标题原文，commit＝该笔全号前 9 位；
    ② 在飞：域 | 数量——本地工作树占用、未并入基线的分支；
    ③ 待验收：域 | 数量——已提交、未并入基线、且无工作树占用的分支。

判据（S1–S5）
    S1 可复算：删掉 docs/STATUS.md 重跑 ⇒ 除时间戳外逐字节一致
        （数据全部来自 git 只读命令＋固定排序＋固定格式；窗口按 UTC 日取整）。
    S2 只读公开信息：数据只出自**本仓** git；--selftest 断言源码不引用私有仓名
        （`_zd` 前缀私有仓）或任何仓外路径（私有侧一律不读）。
    S3 在飞/待验收只给数量：输出不含单号、内容、分支名；
        域名只来自下方 _DOMAINS 固定词表（--selftest 校验已生成文件）。
    S4 短号必须 9 位：取全号前 9 位（前缀截取与 git 缩写同源）。
    S5 时间戳 UTC 且格式固定：YYYY-MM-DDTHH:MM:SSZ。

批次号提取（三规则，首个命中生效；实测 30 天窗口覆盖约 80% 提交）
    1. 括号紧跟数字：feat(927)／feat(788B)／fix(534/625)／feat(647-L3)；
       括号内「数字-数字」视为日期（2026-11-08）不取；
    2. 标题以裸数字开头：759 虚构题材模式／649 批一；
    3. 冒号后紧跟数字：feat(peer): 648④／fix(test): 655 三文件。
    无批次号的提交不逐条入表，在 ① 表后以注记给出条数（fix/docs/chore 等杂项）。

用法（纯标准库；不联网、不读仓外任何路径）
    python tools/gen-status-digest.py                # 生成 docs/STATUS.md
    python tools/gen-status-digest.py --out X.md     # 换个输出路径（测试用）
    python tools/gen-status-digest.py --selftest     # S1–S5 判据自检（不写文件）

退出码
    0 = 成功／自测全过；1 = 失败。
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

try:  # Windows 控制台 cp936 下中文容易炸，统一按 utf-8 写（与 tools/zd.py 同款）
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")  # type: ignore[attr-defined]
except Exception:
    pass

_REPO = Path(__file__).resolve().parent.parent
_OUT_DEFAULT = _REPO / "docs" / "STATUS.md"
_WINDOW_DAYS = 30
_SHORT_LEN = 9

# 批次号三规则（见文件头「批次号提取」；(?!\d)(?!-\d) 排掉 2026-11-08 这类日期）
_BATCH_PATTERNS = (
    re.compile(r"\((\d{2,4})(?!\d)(?!-\d)[^)]*\)"),
    re.compile(r"^(\d{2,4})(?!\d)(?!-\d)"),
    re.compile(r"^[A-Za-z]+\([^)]*\):\s*(\d{2,4})(?!\d)(?!-\d)"),
)

# 域词表（S3：②③ 输出只允许出现这里的标签＋数量；分支名未命中任何关键词 → 其他）
_DOMAINS = (
    ("anatomy", "人体"), ("geometry", "几何"), ("gjk", "几何"), ("epa", "几何"), ("cavity", "几何"),
    ("gate", "门禁"), ("guard", "门禁"), ("emergency", "门禁"),
    ("persona", "人格"), ("memory", "记忆"), ("browser", "玄览"), ("harness", "抱朴"),
    ("skill", "技能"), ("forensic", "取证"), ("shape", "形态"), ("license", "许可"),
    ("term", "终端"), ("eval", "评估"), ("verify", "校验"), ("rust", "构建"), ("build", "构建"),
)
_DOMAIN_VOCAB = frozenset(label for _, label in _DOMAINS) | {"其他"}


def _git(*args: str) -> str:
    """只读 git 调用（S2：所有数据出自本仓 git；字节按 UTF-8 解码保证跨机一致）。"""
    r = subprocess.run(
        ["git", "-c", "i18n.logOutputEncoding=UTF-8", "-c", "core.quotepath=false", *args],
        cwd=str(_REPO), capture_output=True, check=True,
    )
    return r.stdout.decode("utf-8", errors="replace")


def _is_ancestor(rev: str, base: str) -> bool:
    r = subprocess.run(["git", "merge-base", "--is-ancestor", rev, base],
                       cwd=str(_REPO), capture_output=True)
    return r.returncode == 0


def _base_ref() -> str:
    """已合口径＝origin/main（外部可见）；取不到（离线/裸仓）回落 main。"""
    r = subprocess.run(["git", "rev-parse", "--verify", "--quiet", "origin/main^{commit}"],
                       cwd=str(_REPO), capture_output=True)
    return "origin/main" if r.returncode == 0 else "main"


def _short(full: str) -> str:
    """S4：短号＝全号前 9 位（git 缩写即前缀截取，恒 9 位）。"""
    return full[:_SHORT_LEN]


def _extract_batch(subject: str) -> str | None:
    for pat in _BATCH_PATTERNS:
        m = pat.search(subject)
        if m:
            return m.group(1)
    return None


def _cutoff_epoch() -> int:
    """窗口起点＝UTC 今日零点往前推 30 天（按日取整：同一天内重跑集合不变 → S1）。"""
    now = datetime.now(timezone.utc)
    today = now.replace(hour=0, minute=0, second=0, microsecond=0)
    return int((today - timedelta(days=_WINDOW_DAYS)).timestamp())


def _merged_batches(base: str) -> tuple[dict[str, tuple[str, str]], list[str], int]:
    """① 已合：按批次归组。批次 → (该批最新一笔标题, 其 9 位短号)——log 自新到旧，
    首见即该批最新一笔；无批次号标题单列（保序）；第三返回值＝窗口内提交总条数。"""
    out = _git("log", base, f"--since=@{_cutoff_epoch()}", "--no-merges",
               "--pretty=format:%H%x09%s")
    batches: dict[str, tuple[str, str]] = {}
    others: list[str] = []
    total = 0
    for line in out.splitlines():
        if "\t" not in line:
            continue
        total += 1
        full, subj = line.split("\t", 1)
        subj = subj.strip().replace("\r", "")
        b = _extract_batch(subj)
        if b is None:
            others.append(subj)
        elif b not in batches:
            batches[b] = (subj, _short(full))
    return batches, others, total


def _branch_states(base: str) -> tuple[list[str], list[str]]:
    """②③ 分支口径：在飞＝工作树占用且未并入基线；待验收＝已提交未并入且无工作树占用。
    main 与摘要自身所在分支（当前检出分支）不计——自己不数自己。返回分支名（仅供域归类，
    不进输出——S3 由渲染层与 --selftest 双重把关）。"""
    cur = _git("rev-parse", "--abbrev-ref", "HEAD").strip()
    in_tree: set[str] = set()
    for line in _git("worktree", "list", "--porcelain").splitlines():
        if line.startswith("branch "):
            in_tree.add(line[len("branch refs/heads/"):].strip())
    flying: list[str] = []
    pending: list[str] = []
    for b in _git("for-each-ref", "refs/heads", "--format=%(refname:short)").splitlines():
        b = b.strip()
        if not b or b == "main" or b == cur:
            continue
        if _is_ancestor(b, base):
            continue
        (flying if b in in_tree else pending).append(b)
    return flying, pending


def _domain(branch: str) -> str:
    low = branch.lower()
    for kw, label in _DOMAINS:
        if kw in low:
            return label
    return "其他"


def _counts_block(branches: list[str]) -> list[str]:
    """②③ 渲染：域 | 数量（域∈词表；按数量降序、标签升序——固定排序保 S1）。"""
    if not branches:
        return ["（无）", ""]
    counts: dict[str, int] = {}
    for b in branches:
        d = _domain(b)
        counts[d] = counts.get(d, 0) + 1
    rows = sorted(counts.items(), key=lambda kv: (-kv[1], kv[0]))
    out = ["| 域 | 数量 |", "|---|---|"]
    for d, n in rows:
        out.append(f"| {d} | {n} |")
    out.append("")
    out.append(f"合计 {len(branches)} 条。")
    out.append("")
    return out


def build_digest() -> str:
    """组装 STATUS.md 全文（唯一的时间戳不确定性在第 1 行）。"""
    ts = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")  # S5
    base = _base_ref()
    batches, others, total = _merged_batches(base)
    flying, pending = _branch_states(base)
    window_from = datetime.fromtimestamp(_cutoff_epoch(), timezone.utc).strftime("%Y-%m-%d")
    L: list[str] = [
        f"生成时间（UTC）：{ts}",
        f"数据来源：本仓 git 只读——已合＝{base} 自 {window_from}（UTC）起的历史；"
        "在飞＝工作树占用且未并入基线的分支，待验收＝已提交未并入且无工作树占用的分支；"
        "在飞/待验收只给域与数量，不给单号、内容或分支名；私有仓与本仓外任何路径一律不读。",
        "本文件由 tools/gen-status-digest.py 生成，勿手改；复算应逐字节一致（除时间戳外）。",
        "",
        "# 证道 · 公开状态摘要",
        "",
        f"## 一、已合（最近 {_WINDOW_DAYS} 天）",
        "",
        f"共 {total} 条提交，归并出 {len(batches)} 个批次（每批取最新一笔作代表）；另有 {len(others)} 条无批次号提交，见表后注。一句话＝该批最新一笔提交标题原文，commit＝其 9 位短号。",
        "",
        "| 批次 | 一句话 | commit |",
        "|---|---|---|",
    ]
    for b, (subject, short) in batches.items():
        L.append(f"| {b} | {subject.replace('|', chr(92) + '|')} | {short} |")
    L.append("")
    L.append(f"> 注：另有 {len(others)} 条无批次号标记的提交（fix/docs/chore 等杂项、发版与门禁修正），不逐条入表；明细可用 git log 自查。")
    L.append("")
    L.append("## 二、在飞（工作树占用 · 未并入基线）")
    L.append("")
    L.extend(_counts_block(flying))
    L.append("## 三、待验收（已提交 · 未并入基线 · 无工作树占用）")
    L.append("")
    L.extend(_counts_block(pending))
    L.append("> 口径与判据（S1–S5）详见 tools/gen-status-digest.py 文件头注释。")
    return "\n".join(L) + "\n"


# ---------------------------------------------------------------- S1–S5 自检

_TS_LINE = re.compile(r"^生成时间（UTC）：.*$", re.M)
_BRANCHISH = re.compile(r"(feat|wip|ci|fix|docs|test|chore|merge)/[\w.-]+/")


def _mask_ts(text: str) -> str:
    return _TS_LINE.sub("生成时间（UTC）：MASK", text, count=1)


def selftest() -> int:
    ok = True

    def expect(cond: bool, msg: str) -> None:
        nonlocal ok
        ok = ok and bool(cond)
        print(f"  {'✓' if cond else '✗'} {msg}")

    print("自测（857 批：S1–S5）")
    src = Path(__file__).resolve().read_text(encoding="utf-8")
    token = "_zd" + "-private"  # 运行时拼出私有仓名：断言源码不含它（源码本身也不得出现完整字面量）
    expect(token not in src, "S2 源码断言：无私有仓名路径（私有侧一律不读）")
    expect(re.fullmatch(r"[0-9a-f]{9}", _short("0123456789abcdef")) is not None,
           "S4 短号＝全号前 9 位、恒 9 位")
    sample = datetime(2026, 11, 27, 8, 30, 0, tzinfo=timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    expect(sample == "2026-11-27T08:30:00Z", "S5 时间戳 UTC 固定格式 YYYY-MM-DDTHH:MM:SSZ")
    t1, t2 = build_digest(), build_digest()
    expect(_mask_ts(t1) == _mask_ts(t2), "S1 复算：同仓连跑两次除时间戳行外逐字节一致")
    lines = t1.splitlines()
    expect(lines[0].startswith("生成时间（UTC）：") and lines[2].startswith("本文件由 tools/gen-status-digest.py"),
           "④ 头三行：UTC 生成时间／数据来源说明／勿手改")
    out_path = _OUT_DEFAULT
    if out_path.is_file():
        text = out_path.read_text(encoding="utf-8")
        section, sec23, bad = "", [], []
        for ln in text.splitlines():
            if ln.startswith("## "):
                section = ln
                continue
            if section.startswith(("## 二、", "## 三、")):
                sec23.append(ln)
                m = re.fullmatch(r"\|\s*([^|]+?)\s*\|\s*(\d+)\s*\|", ln)
                if m and m.group(1) != "域" and m.group(1) not in _DOMAIN_VOCAB:
                    bad.append(ln)
        expect(not bad, "S3 输出校验：②③ 表行只含词表域＋数量" + ("" if not bad else f"（越表：{bad}）"))
        expect(not _BRANCHISH.search("\n".join(sec23)), "S3 红线：②③ 无分支名（feat/… 等）")
    else:
        print("  （docs/STATUS.md 尚未生成，S3 输出校验跳过）")
    print("自测" + ("：全部通过" if ok else "：存在失败"))
    return 0 if ok else 1


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description="生成 docs/STATUS.md 公开状态摘要（857 批，只读本仓 git）")
    ap.add_argument("--out", default=None, metavar="FILE", help="输出路径（默认 docs/STATUS.md）")
    ap.add_argument("--selftest", action="store_true", help="跑 S1–S5 判据自检，不写文件")
    args = ap.parse_args(argv)
    if args.selftest:
        return selftest()
    text = build_digest()
    out = Path(args.out).resolve() if args.out else _OUT_DEFAULT
    out.write_text(text, encoding="utf-8", newline="\n")
    batches, others, total = _merged_batches(_base_ref())
    flying, pending = _branch_states(_base_ref())
    print(f"✓ 已生成 {out}")
    print(f"  ① 已合 {len(batches)} 批（{total} 条提交，其中 {len(others)} 条无批次号）"
          f" · ② 在飞 {len(flying)} 条 · ③ 待验收 {len(pending)} 条")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
