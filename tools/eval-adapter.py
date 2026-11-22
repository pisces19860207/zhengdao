#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""808 评测 adapter —— 把「一次真实写作轮产物」翻成 792 判分器吃的 {id, verdict}。

为什么需要它
------------
786 有 100 条三档真值，792 有判分器，但判分器要的 `{id, verdict}` JSONL **没有任何东西产出**
（786 README §十一 原话：「CI 里没有被测系统」；全仓 `git ls-files` 里一个 pred 都没有）。
没有这一层，「世界模型有没有让写作更对」只能靠人把答案手敲进判分器 —— 等于自评。

它做什么（逐条编号）
--------------------
1. 读 786 的 `docs/evals/narrative/items.jsonl`（**只读**，一条不改）。
2. 读「一次真实写作轮产物」`--round`（可给多次）：
   · 774 那套落盘的请求体 `*request-*.json`（真机发出、本机回环抓下来的）；
   · 正文 `*.md`（App 写到 `产物/` 的东西）；
   · 738 的注入账 `注入账.jsonl`（`{entries,categories,atMs}` 一行一轮）。
   从里面抽出 **722 读回三行**（`[身体]`／`[状态]`／`[词档]`）与 **738 注入行**（`[校准·…]`）。
3. `--emit-asks` 吐出 100 条待答问题（带该轮上下文），形状就是既有模型调用能直接吃的
   `{id, question, probe, context}` —— 谁来答由**既有路径**决定，本脚本不替它答。
4. `--answers` 收被测 App 的原始回答 `{id, answer}`，把自由文本映射成三档。
5. `--out` 写 `pred.jsonl`：`{id, verdict, raw}`（多余字段判分器忽略，`raw` 留原文便于人查）。
6. `--dry-run` 不碰任何真答案，用桩数据把整条管道跑通，输出**每行都带 `DRYRUN`**。

它不干什么（红线）
------------------
· **不新造模型调用路径**：全程零网络。不 import urllib／socket／http.client／requests，
  不起服务、不连设备、不读密钥。发请求的是 App 自己的 `LlmClient`（774 那套回环抓包），
  本脚本只读它落下来的东西。`tools/check-license-policy.py` 会拦 import，这条不靠自觉。
· **不改判分口径**：映射只负责把答案归到三档本名，怎么算分是 792 `grader/grade.py` 的事。
· **不评判「写得好不好」**：只认 支持／矛盾／未知 三档断言。
· **不伪造**：桩数据一律标 DRYRUN；`tools/eval-narrative.py` 见到就直接退出 2、不出报告。
· **不丢条**：三档之外的回答（「无法判断」之类）按写死规则记进 `未知` 并在 `--mapping-report`
  里单列计数；压根没答的**不代答** —— 不出行，交给判分器的「表外／覆盖行」去体现。

用法
----
    # 桩数据跑通管道（输出带 DRYRUN，判分器必拒）
    python tools/eval-adapter.py --dry-run --out /tmp/pred.jsonl

    # 真轮：先出待答清单，喂给既有模型调用；再把原始回答收回来翻成 pred
    python tools/eval-adapter.py --round 证道设计报告/774-真机取证/cap --emit-asks asks.jsonl
    python tools/eval-adapter.py --round 证道设计报告/774-真机取证/cap \\
            --answers answers.jsonl --out pred.jsonl --mapping-report map.json

    # 自检（不读外部输入，夹具只写在系统临时目录）
    python tools/eval-adapter.py --selftest

退出码
------
    0 = 成功；1 = 输入有错（缺文件／坏 JSON／答案里的 id 不在真值集）；2 = 用法错误。
"""

import argparse
import hashlib
import json
import re
import sys
import tempfile
from pathlib import Path

try:  # Windows 控制台默认 GBK：个别字符编不出来时降级替换，别让脚本自己崩
    sys.stdout.reconfigure(errors="replace")
    sys.stderr.reconfigure(errors="replace")
except Exception:  # noqa: BLE001 - reconfigure 是尽力而为
    pass

REPO_ROOT = Path(__file__).resolve().parent.parent
ITEMS_REL = "docs/evals/narrative/items.jsonl"

VERDICTS = ("支持", "矛盾", "未知")
DRYRUN_MARK = "DRYRUN"

# 722 读回三行 / 738 注入行的锚 —— 与 774 score-rounds.py 的 TAGS 同一套，不另立定义
TAG_READBACK = ("[身体]", "[状态]", "[词档]")
TAG_INJECT_PREFIX = "[校准·"
# 判「是不是写作轮」用同一把尺（774 score-rounds.py:28-33：blob ≥ 2000 字才算，探活不算）
MIN_ROUND_CHARS = 2000

# ---------------------------------------------------------------- 三档映射表
# 写死，不外接、不按 item 变。顺序无关：匹配按「最靠先出现」＋「同起点取更长标记」。
# 三档之外的任何回答 ⇒ 未知（unknown），计入 mapping-report，绝不丢弃。
TIER_EXACT = {
    "支持": {"支持", "一致", "相符", "相符的", "成立", "正确", "对", "是", "是的",
             "support", "supported", "consistent", "entailment", "entails", "yes", "true"},
    "矛盾": {"矛盾", "冲突", "不一致", "不符", "不相符", "不成立", "错误", "错的", "否",
             "不是", "contradict", "contradiction", "contradictory", "no", "false"},
    "未知": {"未知", "无法判断", "无法确定", "不确定", "不清楚", "证据不足", "信息不足",
             "不足以判断", "无法佐证", "存疑", "待核", "待核实", "未提及", "原文未提及",
             "unknown", "unclear", "ambiguous", "undetermined", "insufficient"},
}
# 长文本里要扫的标记（含上面全部本名，外加常见前缀式写法）
TIER_MARKERS = {
    "支持": ("支持", "一致", "相符", "成立", "正确"),
    "矛盾": ("矛盾", "冲突", "不一致", "不符", "不成立", "错误"),
    "未知": ("无法判断", "无法确定", "不能判断", "不确定", "不清楚", "证据不足",
             "信息不足", "不足以判断", "未提及", "存疑", "未知"),
}
_PUNCT_RE = re.compile(r"[\s\u3000。，、；：！？…—－\-·\(\)（）\[\]【】\"'“”‘’,.;:?!/|]+")


class AdapterError(Exception):
    """输入有问题：带行号/来源，绝不静默跳过。"""


def norm(text):
    """归一：去空白标点、ASCII 转小写，只留判据用的字面。"""
    if not isinstance(text, str):
        return ""
    return _PUNCT_RE.sub("", text).lower()


def map_answer(answer):
    """自由文本 → (canonical 三档, 命中标记, 归类依据)。永不返回 None：三档之外归 未知。"""
    raw = answer if isinstance(answer, str) else ""
    key = norm(raw)
    if not key:
        return "未知", "", "empty"
    for tier in VERDICTS:                      # 1) 整条正好是本名/别名
        if key in {norm(x) for x in TIER_EXACT[tier]}:
            return tier, raw.strip(), "exact"
    best = None                                # 2) 最靠先出现；同起点取更长标记
    for tier in VERDICTS:
        for mark in TIER_MARKERS[tier]:
            idx = key.find(norm(mark))
            if idx < 0:
                continue
            cand = (idx, -len(mark), tier, mark)
            if best is None or cand < best:
                best = cand
    if best is not None:
        return best[2], best[3], "marker"
    return "未知", raw.strip(), "off-tier"      # 3) 三档之外，计入 unknown 不丢弃


# ---------------------------------------------------------------- 写作轮读取

def _content_text(msg):
    c = msg.get("content")
    if isinstance(c, str):
        return c
    if c is None:
        return ""
    return json.dumps(c, ensure_ascii=False, sort_keys=True)


def _tag_lines(blob):
    lines = [l for l in blob.split("\n") if l.strip()]
    readback = [l for l in lines if l.startswith(TAG_READBACK)]
    inject = [l for l in lines if l.startswith(TAG_INJECT_PREFIX)]
    return readback, inject


def parse_round(path):
    """把一个写作轮产物读成 {kind, source, blob, readback, inject, body, ledger}。"""
    p = Path(path)
    if not p.exists():
        raise AdapterError("写作轮产物不存在：%s" % path)
    art = {"kind": None, "source": str(p), "blob": "", "readback": [],
           "inject": [], "body": [], "ledger": []}
    if p.is_dir():
        for f in sorted(p.rglob("*")):
            if f.is_file():
                _absorb_file(art, f)
        art["kind"] = "dir"
        return art
    _absorb_file(art, p)
    return art


def _absorb_file(art, f):
    name = f.name
    try:
        if name.endswith(".json"):
            _absorb_request(art, f)
        elif name.endswith(".jsonl"):
            _absorb_jsonl(art, f, name)
        elif name.endswith(".md") or name.endswith(".txt"):
            art["kind"] = art["kind"] or "body"
            art["body"].append({"file": str(f), "text": _read_text(f)})
    except (OSError, ValueError) as exc:
        raise AdapterError("%s 读不出：%s" % (name, exc))


def _read_text(f):
    with open(f, "r", encoding="utf-8-sig") as fh:
        return fh.read()


def _absorb_request(art, f):
    with open(f, "r", encoding="utf-8-sig") as fh:
        doc = json.load(fh)
    if not isinstance(doc, dict) or "messages" not in doc:
        return                                   # 不是请求体：正文类 json 忽略
    blob = "\n".join(_content_text(m) for m in doc.get("messages", [])
                     if isinstance(m, dict))
    art["kind"] = "request"
    art["blob"] = blob
    rb, inj = _tag_lines(blob)
    art["readback"].extend(rb)
    art["inject"].extend(inj)
    if len(blob) < MIN_ROUND_CHARS:              # 探活/侧调用：不当写作轮（同 774 尺子）
        art["kind"] = "probe-only"


def _absorb_jsonl(art, f, name):
    art["kind"] = art["kind"] or "ledger"
    for lineno, line in enumerate(_read_text(f).split("\n"), 1):
        line = line.strip()
        if not line:
            continue
        try:
            obj = json.loads(line)
        except ValueError:
            raise AdapterError("%s:%d 不是合法 JSON" % (name, lineno))
        if isinstance(obj, dict):
            art["ledger"].append(obj)
            text = json.dumps(obj, ensure_ascii=False, sort_keys=True)
            if text.startswith(TAG_INJECT_PREFIX) or TAG_INJECT_PREFIX in text:
                art["inject"].append(text[:200])


def round_context(rounds):
    """把多份产物汇成一段上下文（供 asks 用）。排序写死 ⇒ 同输入同输出。"""
    rb, inj, body = [], [], []
    for r in rounds:
        rb.extend(r["readback"])
        inj.extend(r["inject"])
        for b in r["body"]:
            body.append("〔%s〕\n%s" % (Path(b["file"]).name, b["text"].strip()))
    seen, uniq_rb, uniq_inj = set(), [], []
    for l in rb:
        if l not in seen:
            seen.add(l)
            uniq_rb.append(l)
    seen2 = set()
    for l in inj:
        if l not in seen2:
            seen2.add(l)
            uniq_inj.append(l)
    parts = []
    if body:
        parts.append("# 本轮正文\n" + "\n\n".join(body))
    if uniq_rb:
        parts.append("# 722 读回（App 自己吐的三行，原样）\n" + "\n".join(uniq_rb))
    if uniq_inj:
        parts.append("# 738 注入（App 自己带上的校准行，原样）\n" + "\n".join(uniq_inj))
    return "\n\n".join(parts), {"readback": len(uniq_rb), "inject": len(uniq_inj),
                                "body_files": len(body)}


# ---------------------------------------------------------------- 题目与回答

def load_items(path):
    items = []
    try:
        text = _read_text(path)
    except OSError as exc:
        raise AdapterError("真值集读不出：%s" % exc)
    for lineno, line in enumerate(text.split("\n"), 1):
        line = line.strip()
        if not line:
            continue
        try:
            obj = json.loads(line)
        except ValueError:
            raise AdapterError("items.jsonl:%d 不是合法 JSON：%s" % (lineno, line[:60]))
        if not isinstance(obj, dict) or not obj.get("id"):
            raise AdapterError("items.jsonl:%d 缺 id" % lineno)
        items.append(obj)
    return items


def build_asks(items, context):
    asks = []
    for it in items:
        asks.append({"id": it["id"], "question": it.get("question", ""),
                     "probe": it.get("probe", ""), "context": context})
    return asks


def load_answers(path):
    """收被测 App 的原始回答：{id, answer}／{id, verdict}／{id, text}，或纯 {id: text}。"""
    out = {}
    order = []
    try:
        text = _read_text(path)
    except OSError as exc:
        raise AdapterError("回答文件读不出：%s" % exc)
    try:
        doc = json.loads(text)
    except ValueError:
        doc = None
    if isinstance(doc, dict) and all(not isinstance(v, (dict, list)) for v in doc.values()):
        for k, v in doc.items():
            order.append(k)
            out[k] = str(v)
        return out, order
    for lineno, line in enumerate(text.split("\n"), 1):
        line = line.strip()
        if not line:
            continue
        try:
            obj = json.loads(line)
        except ValueError:
            raise AdapterError("%s:%d 不是合法 JSON" % (Path(path).name, lineno))
        if not isinstance(obj, dict):
            raise AdapterError("%s:%d 该行不是对象" % (Path(path).name, lineno))
        aid = obj.get("id")
        if not isinstance(aid, str) or not aid.strip():
            raise AdapterError("%s:%d 缺 id" % (Path(path).name, lineno))
        aid = aid.strip()
        ans = next((obj[k] for k in ("answer", "verdict", "text", "raw")
                    if isinstance(obj.get(k), str)), None)
        if ans is None:
            raise AdapterError("%s:%d id=%s 没有 answer/verdict/text 字段"
                               % (Path(path).name, lineno, aid))
        if aid in out:
            raise AdapterError("%s:%d id=%s 重复出现" % (Path(path).name, lineno, aid))
        out[aid] = ans
        order.append(aid)
    return out, order


# ---------------------------------------------------------------- 产出 pred

def stub_answer(item, idx):
    """桩答案：只用来跑通管道，永远带 DRYRUN 标记（见 build_pred 的 dryrun 分支）。"""
    pool = ["支持——与本轮原文对齐。", "矛盾：器物归属错置。", "无法判断，原文未提及。",
            "也许吧", "不一致", "证据不足以定"]
    h = int(hashlib.sha256(("%s|%s" % (item["id"], idx)).encode("utf-8")).hexdigest()[:8], 16)
    return pool[h % len(pool)]


def build_pred(items, answers, dryrun):
    """items 全量走一遍：有答案就归三档，没答案不出行（不代答）。"""
    rows, stats = [], {"exact": 0, "marker": 0, "off_tier": 0, "empty": 0,
                       "no_answer": 0, "unknown_answer_id": 0}
    tiers = {v: 0 for v in VERDICTS}
    if answers:
        unknown = sorted(set(answers) - {it["id"] for it in items})
        stats["unknown_answer_id"] = len(unknown)
        if unknown:
            raise AdapterError("回答里有 %d 个 id 不在真值集，例如 %s"
                               % (len(unknown), "／".join(unknown[:5])))
    for it in items:
        if it["id"] not in answers:
            stats["no_answer"] += 1
            continue
        raw = answers[it["id"]]
        tier, mark, why = map_answer(raw)
        if dryrun:
            tier, mark, why = DRYRUN_MARK, DRYRUN_MARK, "dryrun-stub"
        stats[{"exact": "exact", "marker": "marker", "off-tier": "off_tier",
               "empty": "empty", "dryrun-stub": "off_tier"}.get(why, "exact")] += 1
        if not dryrun:
            tiers[tier] += 1
        row = {"id": it["id"], "verdict": tier, "raw": raw}
        if not dryrun:
            row["mapping"] = {"via": why, "marker": mark}
        else:
            row["dryrun"] = True
        rows.append(row)
    return rows, stats, tiers


def write_jsonl(path, rows):
    if path in (None, "-"):
        for r in rows:
            sys.stdout.write(json.dumps(r, ensure_ascii=False, sort_keys=True) + "\n")
        return
    p = Path(path)
    parent = p.parent if str(p.parent) else Path(".")
    if not parent.is_dir():       # 父目录不存在 ⇒ 报错，绝不静默 mkdir（路径打错会建出一棵树）
        raise AdapterError("输出目录不存在：%s" % parent)
    with open(p, "w", encoding="utf-8", newline="\n") as fh:
        for r in rows:
            fh.write(json.dumps(r, ensure_ascii=False, sort_keys=True) + "\n")


# ---------------------------------------------------------------- 自检

def selftest():
    checks, failures = [], []

    def ok(name, cond):
        checks.append(name)
        if not cond:
            failures.append(name)

    ok("T01 整条本名 支持", map_answer("支持") == ("支持", "支持", "exact"))
    ok("T02 整条别名 一致", map_answer("一致") == ("支持", "一致", "exact"))
    ok("T03 不一致≠支持（长标记优先）", map_answer("不一致")[0] == "矛盾")
    ok("T04 前缀式 支持——理由", map_answer("支持——器物与原文对齐")[0] == "支持")
    ok("T05 无法判断 → 未知", map_answer("无法判断")[0] == "未知")
    ok("T06 三档之外 也许吧 → 未知 off-tier", map_answer("也许吧") == ("未知", "也许吧", "off-tier"))
    ok("T07 空白 → 未知 empty", map_answer("   ")[2] == "empty")
    ok("T08 UNKNOWN → 未知", map_answer("UNKNOWN")[0] == "未知")
    ok("T09 英文 contradict → 矛盾", map_answer("contradiction")[0] == "矛盾")
    ok("T10 先出现者优先（无法判断…不支持…）",
       map_answer("无法判断：原文既不支持也无冲突")[0] == "未知")
    ok("T11 归一化吃标点", map_answer("支持。")[0] == "支持")
    ok("T12 非字符串不炸", map_answer(None)[0] == "未知")

    tmp = Path(tempfile.mkdtemp(prefix="ev808-"))
    try:
        items = [{"id": "NAR-CH-01", "question": "q1", "probe": "p1"},
                 {"id": "NAR-CH-02", "question": "q2", "probe": "p2"},
                 {"id": "NAR-CH-03", "question": "q3", "probe": "p3"},
                 {"id": "NAR-CH-04", "question": "q4", "probe": "p4"}]
        ip = tmp / "items.jsonl"
        write_jsonl(str(ip), items)
        ok("T13 items 往返读回 4 条", len(load_items(str(ip))) == 4)

        ans = {"NAR-CH-01": "支持", "NAR-CH-02": "矛盾——器物错置",
               "NAR-CH-03": "无法判断"}
        rows, stats, tiers = build_pred(items, ans, False)
        ok("T14 三答出行", len(rows) == 3)
        ok("T15 缺答不出行不代答", stats["no_answer"] == 1)
        ok("T16 三档之外计 unknown 不丢", tiers["未知"] == 1 and stats["off_tier"] + stats["exact"] >= 1)
        ok("T17 raw 留原文", rows[1]["raw"].startswith("矛盾——"))
        ok("T18 非 dryrun 不带 DRYRUN", all(r["verdict"] != DRYRUN_MARK for r in rows))

        drows, dstats, _ = build_pred(items, {i["id"]: stub_answer(i, 0) for i in items}, True)
        ok("T19 dryrun 每行 verdict=DRYRUN", all(r["verdict"] == DRYRUN_MARK for r in drows))
        ok("T20 dryrun 每行 dryrun=true", all(r.get("dryrun") is True for r in drows))
        ok("T21 dryrun 覆盖全 4 条", len(drows) == 4)

        ap = tmp / "answers.jsonl"
        write_jsonl(str(ap), [{"id": k, "answer": v} for k, v in sorted(ans.items())])
        got, _order = load_answers(str(ap))
        ok("T22 answers JSONL 读回", got == ans)
        dp = tmp / "answers.json"
        with open(dp, "w", encoding="utf-8") as fh:
            json.dump(ans, fh, ensure_ascii=False)
        ok("T23 answers 纯对象也能读", load_answers(str(dp))[0] == ans)

        r1 = tmp / "注入账.jsonl"
        with open(r1, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(json.dumps({"entries": 8, "categories": "下体区间,包皮",
                                 "atMs": 1795279764744}, ensure_ascii=False) + "\n")
        ok("T24 注入账可读", len(parse_round(str(r1))["ledger"]) == 1)

        body = "x" * MIN_ROUND_CHARS
        req = {"model": "m", "messages": [
            {"role": "system", "content": "前言\n" + "\n".join(
                ["[身体] 176cm · 体脂25%", "[状态] turgor:未接入", "[词档] 根部/柱身",
                 "[校准·SENS-RANGE] 勃起长度区间 131.0–170.0mm｜asOf:2026-11-16"]) + "\n" + body},
            {"role": "user", "content": "详细描述根部的状态"}]}
        rp = tmp / "request-01.json"
        with open(rp, "w", encoding="utf-8") as fh:
            json.dump(req, fh, ensure_ascii=False)
        art = parse_round(str(rp))
        ok("T25 请求体识别为 request", art["kind"] == "request")
        ok("T26 抽出 722 三行", len(art["readback"]) == 3)
        ok("T27 抽出 738 注入行", len(art["inject"]) == 1)
        ctx, meta = round_context([art])
        ok("T28 上下文含读回三行", "[身体]" in ctx and "[词档]" in ctx)
        ok("T29 上下文计数正确", meta == {"readback": 3, "inject": 1, "body_files": 0})

        short = {"model": "m", "messages": [{"role": "user", "content": "ping"}]}
        sp = tmp / "request-02.json"
        with open(sp, "w", encoding="utf-8") as fh:
            json.dump(short, fh, ensure_ascii=False)
        ok("T30 探活不算写作轮", parse_round(str(sp))["kind"] == "probe-only")

        asks = build_asks(items, ctx)
        ok("T31 asks 与题数等长", len(asks) == 4 and all(a["context"] == ctx for a in asks))

        p1 = tmp / "pred-a.jsonl"
        p2 = tmp / "pred-b.jsonl"
        write_jsonl(str(p1), rows)
        write_jsonl(str(p2), rows)
        ok("T32 幂等：同输入两次逐字节一致",
           p1.read_bytes() == p2.read_bytes())
        ok("T33 pred 无时间戳字段",
           not any(k in r for r in rows for k in ("ts", "atMs", "time", "generated_at")))

        try:
            build_pred(items, {"NAR-XX-99": "支持"}, False)
            ok("T34 野 id 报错", False)
        except AdapterError:
            ok("T34 野 id 报错", True)
        try:
            load_answers(str(tmp / "nope.jsonl"))
            ok("T35 缺文件报错", False)
        except AdapterError:
            ok("T35 缺文件报错", True)
        bad = tmp / "bad.jsonl"
        bad.write_text("{not json\n", encoding="utf-8")
        try:
            load_answers(str(bad))
            ok("T36 坏 JSON 报错", False)
        except AdapterError:
            ok("T36 坏 JSON 报错", True)
        try:
            write_jsonl(str(tmp / "no-such-dir" / "pred.jsonl"), rows)
            ok("T37 输出目录不存在 ⇒ 报错且不静默建目录", False)
        except AdapterError:
            ok("T37 输出目录不存在 ⇒ 报错且不静默建目录",
               not (tmp / "no-such-dir").exists())
    finally:
        import shutil
        shutil.rmtree(tmp, ignore_errors=True)

    return checks, failures


# ---------------------------------------------------------------- main

def main(argv=None):
    ap = argparse.ArgumentParser(
        prog="eval-adapter.py", description="808 评测 adapter：写作轮产物 → {id, verdict}")
    ap.add_argument("--items", default=str(REPO_ROOT / ITEMS_REL),
                    help="786 真值集（只读），默认 %(default)s")
    ap.add_argument("--round", action="append", default=[], metavar="PATH",
                    help="一次真实写作轮产物（请求体 json／正文 md／注入账 jsonl，可给多次）")
    ap.add_argument("--emit-asks", metavar="PATH",
                    help="把 100 条待答问题（带本轮上下文）写出去，喂既有模型调用")
    ap.add_argument("--answers", metavar="PATH",
                    help="被测 App 的原始回答 JSONL（{id, answer}）")
    ap.add_argument("--out", metavar="PATH", help="pred.jsonl 输出路径（- = stdout）")
    ap.add_argument("--mapping-report", metavar="PATH",
                    help="映射计数（exact/marker/off_tier/no_answer）写这份")
    ap.add_argument("--dry-run", action="store_true",
                    help="用桩数据跑通管道；输出每行带 DRYRUN，判分器必拒")
    ap.add_argument("--selftest", action="store_true", help="跑内置自检，不读外部输入")
    args = ap.parse_args(argv)

    if args.selftest:
        checks, failures = selftest()
        print("[eval-adapter] selftest %d/%d 通过" % (len(checks) - len(failures), len(checks)))
        for name in failures:
            print("  ✗ %s" % name)
        return 1 if failures else 0

    try:
        items = load_items(args.items)
        rounds = [parse_round(r) for r in args.round]
        context, meta = round_context(rounds)

        if args.emit_asks:
            write_jsonl(args.emit_asks, build_asks(items, context))
            print("[eval-adapter] 待答 %d 条 → %s（上下文 %d 字｜读回 %d／注入 %d／正文 %d）"
                  % (len(items), args.emit_asks, len(context),
                     meta["readback"], meta["inject"], meta["body_files"]))

        if args.dry_run:
            answers = {it["id"]: stub_answer(it, 0) for it in items}
            print("[eval-adapter] DRYRUN：桩数据，没碰任何真答案")
        elif args.answers:
            answers, _order = load_answers(args.answers)
            print("[eval-adapter] 收到原始回答 %d 条" % len(answers))
        else:
            if not args.emit_asks:
                print("[eval-adapter] 用法错误：要么 --dry-run，要么 --answers，要么 --emit-asks",
                      file=sys.stderr)
                return 2
            print("[eval-adapter] 只出待答清单，不产 pred —— 没有 --answers 就不替被测系统编答案")
            return 0

        rows, stats, tiers = build_pred(items, answers, args.dry_run)
        if args.out:
            write_jsonl(args.out, rows)
        else:
            write_jsonl("-", rows)

        if args.mapping_report:
            rep = {"dryrun": bool(args.dry_run), "items": len(items),
                   "pred_rows": len(rows), "tiers": tiers, "mapping": stats,
                   "round": {"artifacts": len(rounds), "readback": meta["readback"],
                             "inject": meta["inject"], "body_files": meta["body_files"]}}
            with open(args.mapping_report, "w", encoding="utf-8", newline="\n") as fh:
                json.dump(rep, fh, ensure_ascii=False, indent=2, sort_keys=True)
                fh.write("\n")

        tag = "DRYRUN " if args.dry_run else ""
        print("%s[eval-adapter] %d 行 → %s｜支持 %d 矛盾 %d 未知 %d｜缺答不代答 %d｜三档之外计未知 %d"
              % (tag, len(rows), args.out or "stdout",
                 tiers["支持"], tiers["矛盾"], tiers["未知"],
                 stats["no_answer"], stats["off_tier"]))
        if args.dry_run:
            print("[eval-adapter] 提醒：这份带 DRYRUN，判分器（tools/eval-narrative.py）会直接退出 2")
        return 0
    except AdapterError as exc:
        print("[eval-adapter] 输入有错：%s" % exc, file=sys.stderr)
        return 1
    except OSError as exc:
        print("[eval-adapter] 环境错误：%s" % exc, file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
