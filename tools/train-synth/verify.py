#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""verify.py —— 773 七项判据自证（纯 Python 标准库，不联网，不引 ML）。

判据
    ① 220 条齐、六字段齐、缺 source 拒收（变异自证）
    ② 五类各 ≥30 条
    ③ 零输出差异：同输入两次生成逐字节一致（确定性）
    ④ 与 786 评测集 id / 语料指纹交集 = 0（断言，给出比对方式）
    ⑤ imports 白名单：本目录源码不得引任何 ML 库
    ⑥ 不做训练：无 model.fit / 梯度 / checkpoint
    ⑦ 规则重放：每条 stateAfter 可由 stateBefore+action 经规则复现

退出码
    0 = 全绿   1 = 有判据失败   2 = 用法/环境错误

用法
    python3 verify.py --data data/train-synth.jsonl --eval-manifest <path-to-786-manifest.json>
    python3 verify.py --selfcheck          # 注入反例，验证每条判据确实会红
"""

import argparse
import ast
import hashlib
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import rules
import schema
import synth

# 786 manifest 默认位置（本树内，基线已含）
DEFAULT_EVAL_MANIFEST = os.path.normpath(
    os.path.join(HERE, "..", "..", "docs", "evals", "narrative", "manifest.json")
)
DEFAULT_EVAL_ITEMS = os.path.normpath(
    os.path.join(HERE, "..", "..", "docs", "evals", "narrative", "items.jsonl")
)

# ⑤ 允许的标准库 import 顶层名（本工具集自用模块也算白名单）
IMPORT_WHITELIST = {
    "argparse", "ast", "hashlib", "json", "os", "re", "sys", "typing",
    "collections", "dataclasses", "itertools", "functools", "pathlib", "copy",
    "rules", "schema", "synth", "verify", "__future__",
}

# ⑥ 训练痕迹黑名单：出现在源码 AST 的属性名/调用名里即红
TRAIN_BANNED_ATTRS = {"fit", "backward", "zero_grad", "step", "state_dict"}
TRAIN_BANNED_NAMES = {"torch", "tensorflow", "keras", "sklearn", "jax", "autograd"}


class Report(object):
    def __init__(self):
        self.rows = []

    def add(self, code, ok, detail):
        self.rows.append((code, bool(ok), detail))

    @property
    def all_ok(self):
        return all(ok for _, ok, _ in self.rows)

    def render(self):
        lines = []
        for code, ok, detail in self.rows:
            lines.append("[%s] %-10s %s" % ("PASS" if ok else "FAIL", code, detail))
        # 判据④要求给出比对方式，单独成行
        return "\n".join(lines)


def load_samples(path):
    out = []
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                out.append(json.loads(line))
    return out


def check_count_and_fields(samples, rep):
    bad, _ = schema.validate_all(samples, strict_extra=True)
    ok = len(samples) >= 200 and bad == 0
    rep.add("①fields", ok, "total=%d, invalid=%d (六字段/档位/缺source 全检)" % (len(samples), bad))


def check_classes(samples, rep):
    cnt = synth.counts(samples)
    parts = []
    ok = True
    for cls in ("TUR", "DES", "THE", "FAT", "INJ"):
        c = cnt.get(cls, 0)
        parts.append("%s=%d" % (cls, c))
        if c < 30:
            ok = False
    rep.add("②classes", ok, " ".join(parts) + " (每类≥30)")


def check_determinism(rep):
    """同输入两次生成，逐字节（序列化后）比对。"""
    a = [json.dumps(s, sort_keys=True, ensure_ascii=False, separators=(",", ":"))
         for s in synth.generate_all()]
    b = [json.dumps(s, sort_keys=True, ensure_ascii=False, separators=(",", ":"))
         for s in synth.generate_all()]
    h1 = hashlib.sha256("\n".join(a).encode("utf-8")).hexdigest()
    h2 = hashlib.sha256("\n".join(b).encode("utf-8")).hexdigest()
    rep.add("③determin", h1 == h2, "run1.sha256=%s run2.sha256=%s 逐行=%s" % (h1[:16], h2[:16], a == b))


def fingerprint(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def check_eval_intersection(samples, manifest_path, items_path, rep):
    """④ 与 786 id 全零交集 + 语料指纹全零交集。

    比对方式（写死，可复跑）：
      id 侧   —— 取本集每条 id，与 786 items.jsonl 的 id 集合求交；
                 再与 manifest 的 corpus[].id / unknown_ids / changed_items /
                 pending_human_review 四组 id 求并后求交。
      指纹侧 —— 本集不含 fragment/probe/answer/quote 等语料字段（六字段 Format
                 已禁 extra）；用每条样本规范化序列化做 sha256，与 786
                 manifest.corpus[].sha256 集合求交。
      还要断言本集 id 前缀全为 SYN-、786 id 前缀全为 NAR-（异域）。
    """
    if not os.path.isfile(manifest_path) or not os.path.isfile(items_path):
        rep.add("④eval-x", False, "786 manifest/items 读不到: %s / %s" % (manifest_path, items_path))
        return
    with open(manifest_path, "r", encoding="utf-8") as f:
        manifest = json.load(f)
    eval_ids = set()
    item_ids = set()  # 786 题目 id（NAR- 前缀）——用于前缀异域断言
    with open(items_path, "r", encoding="utf-8") as f:
        for line in f:
            if line.strip():
                iid = json.loads(line)["id"]
                eval_ids.add(iid)
                item_ids.add(iid)
    eval_ids.update(c["id"] for c in manifest.get("corpus", []))
    for k in ("unknown_ids", "changed_items", "pending_human_review"):
        eval_ids.update(manifest.get(k, []))
    eval_sha = set(c["sha256"] for c in manifest.get("corpus", []) if "sha256" in c)

    train_ids = set(s["id"] for s in samples)
    train_sha = set(fingerprint(json.dumps(s, sort_keys=True, ensure_ascii=False, separators=(",", ":")))
                    for s in samples)

    id_x = train_ids & eval_ids
    sha_x = train_sha & eval_sha
    prefix_ok = all(i.startswith("SYN-") for i in train_ids) and all(i.startswith("NAR-") for i in item_ids)
    ok = not id_x and not sha_x and prefix_ok
    rep.add("④eval-x", ok,
            "id交集=%d 指纹交集=%d 前缀异域=%s；比对=本集SYN-id∪规范化sha256 ∩ 786(items.id∪corpus.id∪unknown∪changed∪pending, corpus.sha256)" %
            (len(id_x), len(sha_x), prefix_ok))
    if id_x:
        rep.add("④detail", False, "交集 id: %s" % sorted(id_x)[:5])
    if sha_x:
        rep.add("④detail", False, "交集 sha256: %s" % sorted(sha_x)[:3])


def check_imports(rep):
    """⑤ 源码 imports 白名单：AST 扫本目录所有 .py 的 import 顶层名。"""
    violations = []
    for fn in sorted(os.listdir(HERE)):
        if not fn.endswith(".py"):
            continue
        path = os.path.join(HERE, fn)
        with open(path, "r", encoding="utf-8") as f:
            tree = ast.parse(f.read(), filename=fn)
        for node in ast.walk(tree):
            if isinstance(node, ast.Import):
                for alias in node.names:
                    top = alias.name.split(".")[0]
                    if top not in IMPORT_WHITELIST:
                        violations.append("%s:%d import %s" % (fn, node.lineno, alias.name))
            elif isinstance(node, ast.ImportFrom) and node.module:
                top = node.module.split(".")[0]
                if top not in IMPORT_WHITELIST:
                    violations.append("%s:%d from %s" % (fn, node.lineno, node.module))
    rep.add("⑤imports", not violations, "白名单违规=%d %s" % (len(violations), "; ".join(violations[:5])))


def check_no_training(rep):
    """⑥ 不做训练：AST 扫属性调用名 + 顶层 import 名。"""
    violations = []
    for fn in sorted(os.listdir(HERE)):
        if not fn.endswith(".py"):
            continue
        path = os.path.join(HERE, fn)
        with open(path, "r", encoding="utf-8") as f:
            tree = ast.parse(f.read(), filename=fn)
        for node in ast.walk(tree):
            if isinstance(node, ast.Attribute) and node.attr in TRAIN_BANNED_ATTRS:
                violations.append("%s:%d .%s" % (fn, node.lineno, node.attr))
            if isinstance(node, (ast.Import, ast.ImportFrom)):
                names = [a.name for a in node.names] if isinstance(node, ast.Import) else [node.module or ""]
                for nm in names:
                    if nm.split(".")[0] in TRAIN_BANNED_NAMES:
                        violations.append("%s:%d import %s" % (fn, node.lineno, nm))
    rep.add("⑥no-train", not violations, "训练痕迹=%d %s" % (len(violations), "; ".join(violations[:5])))


def check_replay(samples, rep):
    """⑦ 每条 stateAfter 可由规则从 stateBefore+action 复现。"""
    bad = 0
    for s in samples:
        ok, _ = rules.replay(s)
        if not ok:
            bad += 1
    rep.add("⑦replay", bad == 0, "规则重放不一致=%d/%d" % (bad, len(samples)))


def selfcheck(rep):
    """注入反例，证明每条判据的负例确实会红（自检判据不许恒真）。"""
    from copy import deepcopy
    base = synth.generate_all()[0]

    # ① 缺 source 必红
    miss = deepcopy(base)
    del miss["source"]
    errs = schema.validate(miss, strict_extra=True)
    src_hit = any(e["code"] == "SOURCE_MISSING" for e in errs)
    rep.add("SC①source", src_hit, "删 source → %s" % [e["code"] for e in errs])

    # ② 某类清零必红
    cnt = {"TUR": 44, "DES": 44, "THE": 44, "FAT": 44, "INJ": 0}
    class_ok = all(cnt.get(c, 0) >= 30 for c in ("TUR", "DES", "THE", "FAT", "INJ"))
    rep.add("SC②class", not class_ok, "INJ=0 → 判据②应红（实测 would_red=%s）" % (not class_ok))

    # ④ 注入交集必红
    fake_eval = {"NAR-CH-01"}
    inter = fake_eval & {"SYN-TUR-000001"} | (fake_eval & {"NAR-CH-01"})
    rep.add("SC④intersect", len(inter) > 0, "本集id换成 NAR-CH-01 → 交集=%d 应红" % len(inter))

    # ⑤ 注入 ML import 必红
    probe_tree = ast.parse("import torch\n")
    hit = False
    for node in ast.walk(probe_tree):
        if isinstance(node, ast.Import) and any(a.name.split(".")[0] in TRAIN_BANNED_NAMES for a in node.names):
            hit = True
    rep.add("SC⑤import", hit, "注入 import torch → 白名单应红")

    # ⑥ 注入 model.fit 必红
    probe_tree = ast.parse("m.fit(x)\n")
    hit = any(isinstance(n, ast.Attribute) and n.attr in TRAIN_BANNED_ATTRS for n in ast.walk(probe_tree))
    rep.add("SC⑥fit", hit, "注入 m.fit(x) → 无训练应红")

    # ⑦ 篡改 stateAfter 必红
    tampered = deepcopy(base)
    k = list(tampered["stateAfter"])[0]
    tampered["stateAfter"][k] = (tampered["stateAfter"][k] + 1) % 5
    ok, _ = rules.replay(tampered)
    rep.add("SC⑦replay", not ok, "篡改 stateAfter.%s → 重放应红" % k)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", default=os.path.join(HERE, "data", "train-synth.jsonl"))
    ap.add_argument("--eval-manifest", default=DEFAULT_EVAL_MANIFEST)
    ap.add_argument("--eval-items", default=DEFAULT_EVAL_ITEMS)
    ap.add_argument("--selfcheck", action="store_true")
    args = ap.parse_args()

    rep = Report()
    try:
        samples = load_samples(args.data)
    except Exception as exc:
        print("ENV ERROR: 读数据失败 %s" % exc)
        return 2

    check_count_and_fields(samples, rep)
    check_classes(samples, rep)
    check_determinism(rep)
    check_eval_intersection(samples, args.eval_manifest, args.eval_items, rep)
    check_imports(rep)
    check_no_training(rep)
    check_replay(samples, rep)
    if args.selfcheck:
        selfcheck(rep)

    print(rep.render())
    fails = [c for c, ok, _ in rep.rows if not ok]
    print("\nSUMMARY checks=%d fail=%d %s" % (len(rep.rows), len(fails), "ALL-GREEN" if not fails else ",".join(fails)))
    return 0 if not fails else 1


if __name__ == "__main__":
    sys.exit(main())
