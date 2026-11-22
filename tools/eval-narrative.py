#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""808 统一入口 —— `tools/eval-narrative.py`：判分前先验「这是不是真跑出来的」。

为什么需要它
------------
792 的判分器在 `docs/evals/narrative/grader/grade.py`，但 786 README §十一 自己写着：
「基线 e6c54a0d1 上 `tools/eval-narrative.py` 这层统一入口**尚未入库**；800 只写跑法、不新建脚本」。
README 里那条 `python tools/eval-narrative.py --pred <file> --out <dir>` 一直是空头支票。
808 补的正是这一层 —— 它同时是「不伪造」的落点：`--dry-run` 产出的东西必须在这里被拦下。

它做什么（逐条编号）
--------------------
1. **DRYRUN 闸**（判据②）：扫 `--pred` 每一行，只要见到 `DRYRUN` 标记 ——
   · `verdict` 字面是 DRYRUN，或
   · 该行有真值的 `dryrun` 字段，或
   · 整行文本含 `DRYRUN` ——
   **立刻退出 2，不产任何报告。** 桩数据永远变不成指标。
2. **委托判分**：闸过了才把参数原样转给 `grader/grade.py`，退出码照抄。
   口径、算法、报告格式一个字不在这份脚本里，改口径仍然是 792 的事。
3. **参数对齐 README**：`--out` 就是 grade.py 的 `--out-dir`（§十一 明说两者指同一个目录）。

它不干什么（红线）
------------------
· **不判分**：一个指标都不算，一行报告都不写。判分只有 `grade.py` 一处。
· **不改 786 数据、不改 792 口径**：只读、只转发。
· **不联网**：纯标准库，不 import urllib／socket／http.client／requests。

用法
----
    python tools/eval-narrative.py --pred pred.jsonl --out out
    python tools/eval-narrative.py --pred pred.jsonl --out out --items docs/evals/narrative/items.jsonl
    python tools/eval-narrative.py --selftest

退出码
------
    0/1/2 = 判分器的退出码原样透传（含义见 `grader/README.md`）；
    本层额外保证：**pred 带 DRYRUN ⇒ 恒为 2 且不产报告**（判分器自己会以 1 拒绝，这里抢在它前面）。
"""

import argparse
import contextlib
import io
import json
import re
import subprocess
import sys
import tempfile
from pathlib import Path

try:  # Windows 控制台默认 GBK：个别字符编不出来时降级替换，别让入口自己崩
    sys.stdout.reconfigure(errors="replace")
    sys.stderr.reconfigure(errors="replace")
except Exception:  # noqa: BLE001 - reconfigure 是尽力而为
    pass

REPO_ROOT = Path(__file__).resolve().parent.parent
GRADER_REL = "docs/evals/narrative/grader/grade.py"
DRYRUN_MARK = "DRYRUN"
_MARK_RE = re.compile(r"DRYRUN", re.IGNORECASE)

# DRYRUN 的三种可能藏法，逐条写死 —— 判据②要的「见到就报错」不能靠运气
KINDS = {
    "verdict": "verdict 字面就是 DRYRUN（桩数据没被翻译成真判定）",
    "field": "该行带 dryrun 标记字段",
    "text": "该行文本里出现 DRYRUN",
}


class GateError(Exception):
    """pred 不可信或读不出 —— 一律拦，不放进判分器。"""


def scan_pred(path):
    """逐行扫 pred，返回命中的 DRYRUN 行 [{line,kind,id}]。读不出直接抛。"""
    p = Path(path)
    if not p.is_file():
        raise GateError("pred 不存在：%s" % path)
    hits = []
    with open(p, "r", encoding="utf-8-sig") as fh:
        for lineno, raw in enumerate(fh, 1):
            text = raw.strip()
            if not text:
                continue
            obj = None
            try:
                obj = json.loads(text)
            except ValueError:
                pass
            if isinstance(obj, dict):
                if isinstance(obj.get("verdict"), str) and \
                        obj["verdict"].strip().upper() == DRYRUN_MARK:
                    hits.append({"line": lineno, "kind": "verdict", "id": obj.get("id")})
                    continue
                if obj.get("dryrun") is True:
                    hits.append({"line": lineno, "kind": "field", "id": obj.get("id")})
                    continue
            if _MARK_RE.search(text):
                hits.append({"line": lineno, "kind": "text",
                             "id": obj.get("id") if isinstance(obj, dict) else None})
    return hits


def gate(path):
    """判据②：见 DRYRUN ⇒ 打印可定位的行号并拒绝；返回命中数（0 = 放行）。"""
    hits = scan_pred(path)
    if not hits:
        return 0
    print("[eval-narrative] ✗ 拒绝判分：%s 里有 %d 行带 DRYRUN 标记" % (path, len(hits)),
          file=sys.stderr)
    for h in hits[:10]:
        print("    行 %d  id=%s  %s" % (h["line"], h["id"], KINDS[h["kind"]]), file=sys.stderr)
    if len(hits) > 10:
        print("    …另有 %d 行同类命中" % (len(hits) - 10), file=sys.stderr)
    print("[eval-narrative] DRYRUN 是 --dry-run 的桩数据，不是被测系统的输出；"
          "不产报告、不出处数。判据②「不伪造」在此生效。", file=sys.stderr)
    return len(hits)


def run_grader(argv):
    grader = REPO_ROOT / GRADER_REL
    if not grader.is_file():
        raise GateError("判分器读不出：%s" % grader)
    return subprocess.call([sys.executable, str(grader)] + argv)


def selftest():
    checks, failures = [], []

    def ok(name, cond):
        checks.append(name)
        if not cond:
            failures.append(name)

    tmp = Path(tempfile.mkdtemp(prefix="ev808n-"))
    quiet = contextlib.redirect_stderr(io.StringIO())   # 自检里 gate() 的告警是预期噪声
    try:
        good = tmp / "good.jsonl"
        good.write_text('{"id": "NAR-CH-01", "verdict": "支持", "raw": "支持——"}\n'
                        '{"id": "NAR-CH-02", "verdict": "矛盾"}\n', encoding="utf-8")
        with quiet:
            ok("N01 干净 pred 放行", gate(str(good)) == 0)
        ok("N02 干净 pred 零命中", scan_pred(str(good)) == [])

        v = tmp / "by-verdict.jsonl"
        v.write_text('{"id": "NAR-CH-01", "verdict": "DRYRUN", "dryrun": true}\n', encoding="utf-8")
        with quiet:
            ok("N03 verdict=DRYRUN 拦下", gate(str(v)) == 1)
        ok("N04 命中记行号", scan_pred(str(v))[0]["line"] == 1
           and scan_pred(str(v))[0]["kind"] == "verdict")

        fv = tmp / "by-field.jsonl"
        fv.write_text('{"id": "NAR-CH-01", "verdict": "支持", "dryrun": true}\n', encoding="utf-8")
        ok("N05 dryrun 字段拦下", scan_pred(str(fv))[0]["kind"] == "field")

        tv = tmp / "by-text.jsonl"
        tv.write_text('{"id": "NAR-CH-01", "verdict": "支持", "note": "DRYRUN stub"}\n',
                      encoding="utf-8")
        ok("N06 行文本含 DRYRUN 也拦下（多余字段不豁免）", scan_pred(str(tv))[0]["kind"] == "text")

        mixed = tmp / "mixed.jsonl"
        mixed.write_text('{"id": "NAR-CH-01", "verdict": "支持"}\n'
                         '{"id": "NAR-CH-02", "verdict": "dryrun"}\n'
                         'not json at all\n', encoding="utf-8")
        ok("N07 小写 dryrun 也算（大小写无关）", len(scan_pred(str(mixed))) == 1
           and scan_pred(str(mixed))[0]["line"] == 2)
        try:
            scan_pred(str(tmp / "missing.jsonl"))
            ok("N08 pred 缺失 ⇒ 抛错", False)
        except GateError:
            ok("N08 pred 缺失 ⇒ 抛错", True)
        ok("N09 判分器路径存在", (REPO_ROOT / GRADER_REL).is_file())
    finally:
        import shutil
        shutil.rmtree(tmp, ignore_errors=True)
    return checks, failures


def main(argv=None):
    ap = argparse.ArgumentParser(
        prog="eval-narrative.py",
        description="808：判分前的 DRYRUN 闸 ＋ 792 判分器统一入口（口径仍在 grade.py）")
    ap.add_argument("--pred", metavar="PATH", help="被测系统（808 adapter 产出）的 {id, verdict} JSONL")
    ap.add_argument("--out", metavar="DIR", help="报告目录 = grade.py 的 --out-dir")
    ap.add_argument("--items", metavar="PATH", help="真值集，默认用 grade.py 自带的相对路径")
    ap.add_argument("--allow-count-drift", action="store_true", help="透传给 grade.py")
    ap.add_argument("--selftest", action="store_true", help="跑内置自检，不读外部输入")
    args = ap.parse_args(argv)

    if args.selftest:
        checks, failures = selftest()
        print("[eval-narrative] selftest %d/%d 通过" % (len(checks) - len(failures), len(checks)))
        for name in failures:
            print("  ✗ %s" % name)
        return 1 if failures else 0

    if not args.pred:
        print("[eval-narrative] 用法错误：--pred 必填", file=sys.stderr)
        return 2
    try:
        if gate(args.pred):
            return 2                               # 判据②：直接报错退出，不进判分器
    except GateError as exc:
        print("[eval-narrative] 环境错误：%s" % exc, file=sys.stderr)
        return 2

    forwarded = []
    if args.items:
        forwarded += ["--items", args.items]
    if args.out:
        forwarded += ["--out-dir", args.out]
    if args.allow_count_drift:
        forwarded.append("--allow-count-drift")
    forwarded += ["--pred", args.pred]
    return run_grader(forwarded)


if __name__ == "__main__":
    sys.exit(main())
