#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
760 混合召回 · 离线对照实测（①选型实测 + ③20条查询对照）
- 语料：drill-corpus-715 真实设定语料（人物设定 30 条 + 实体词表），逐条作为"世界书条目"
- embedding：本机 llama.cpp / Qwen3-Embedding-0.6B，127.0.0.1，无外网
- 对照：BM25-only vs 混合(向量top-k ∥ BM25 top-k → RRF合并去重 → 精筛[否定走规则] → 裁剪)
- 20 条查询按派工单：5 换说法 + 5 否定条件 + 5 精确数值 + 5 专名
- 真值(gold)人工标注，写死在脚本里，避免自证漂移
- 输出：召回率@5/@10、误召回率(MRR@10作参考)、耗时、每组明细 JSON
不写生产代码、不碰 App 门禁。
判据版本：v2（2026-11-20 派单人裁定，事前定义）——见 JUDGMENT 块。
v2 修订是「判据口径变更」，非结果修饰：检索算法、gold 标注、语料一字未动，仅在末尾新增确定性判定逻辑并重跑。
"""
import json, time, urllib.request, sys, os
from collections import Counter

EMB_URL = "http://127.0.0.1:9931/v1/embeddings"
CORPUS = r"C:/Users/guoli/AndroidStudioProjects/drill-corpus-715"
OUT = r"C:/Users/guoli/AndroidStudioProjects/wt-760/tools/760-bench-results.json"

# ---------- 1. 语料：把人物设定 30 条 + 实体词表关键条目做成"世界书条目" ----------
def load_entries():
    entries = []
    import re
    p = os.path.join(CORPUS, "人物设定.md")
    for line in open(p, encoding="utf-8"):
        line = line.strip()
        m = re.match(r"^- ([SLH]-\d{2})\s+(.+)$", line)
        if m:
            entries.append({"id": m.group(1), "text": m.group(2), "kind": "setting"})
    p = os.path.join(CORPUS, "实体词表.md")
    section = ""
    for line in open(p, encoding="utf-8"):
        line = line.strip()
        if line.startswith("## "):
            section = line[3:]
        if line.startswith("- "):
            text = line[2:]
            entries.append({"id": "ENT-%02d" % len(entries), "text": "[" + section + "] " + text, "kind": "entity"})
    return entries

# ---------- 2. embedding ----------
def embed(texts):
    body = json.dumps({"model": "qwen3-emb", "input": texts}).encode("utf-8")
    req = urllib.request.Request(EMB_URL, data=body, headers={"Content-Type": "application/json"})
    t0 = time.perf_counter()
    with urllib.request.urlopen(req, timeout=120) as f:
        d = json.load(f)
    dt = (time.perf_counter() - t0) * 1000
    return [x["embedding"] for x in d["data"]], dt

def cos(a, b):
    dot = sum(x * y for x, y in zip(a, b))
    na = sum(x * x for x in a) ** 0.5
    nb = sum(y * y for y in b) ** 0.5
    return dot / (na * nb) if na and nb else 0.0

# ---------- 3. BM25（分词用简单 CJK bigram + ASCII 词，正交于仓库的分词机，仅做离线对照） ----------
def toks(s):
    import re
    s = s.lower()
    out = re.findall(r"[a-z0-9]+", s)
    cjk = "".join(re.findall(r"[\u4e00-\u9fff]", s))
    out += [cjk] if len(cjk) == 1 else [cjk[i:i+2] for i in range(len(cjk)-1)]
    return out

class BM25:
    def __init__(self, docs):
        self.docs = docs
        self.tf = [Counter(toks(d["text"])) for d in docs]
        self.dl = [sum(t.values()) for t in self.tf]
        self.avg = sum(self.dl) / len(self.dl) if self.dl else 1.0
        self.N = len(docs)
        self.df = Counter()
        for t in self.tf:
            self.df.update(t.keys())
    def score(self, q, k1=1.2, b=0.75):
        qt = Counter(toks(q))
        out = []
        for i, t in enumerate(self.tf):
            s = 0.0
            for term, qtf in qt.items():
                f = t.get(term, 0)
                if not f: continue
                idf = 1 + (self.N - self.df[term] + 0.5) / (self.df[term] + 0.5)
                import math
                s += math.log(idf) * f * (k1 + 1) / (f + k1 * (1 - b + b * self.dl[i] / self.avg))
            out.append((i, s))
        out.sort(key=lambda x: -x[1])
        return out

# ---------- 4. 20 条查询（gold 人工标注） ----------
# cat: paraphrase(换说法) / negation(否定条件) / number(精确数值) / entity(专名)
QUERIES = [
    # 换说法 5 条：gold 是语义对应条目，字面词尽量不同
    ("P1", "paraphrase", "观测员每天清晨量井口粗细的那个固定数字", ["S-02","S-04"]),
    ("P2", "paraphrase", "女技术员量井口前擦卡尺刻度的动作", ["S-02","S-03"]),
    ("P3", "paraphrase", "老站长用布擦的独眼铜蛤蟆", ["H-05","H-06","H-08"]),
    ("P4", "paraphrase", "测绘员那个少了页的本子", ["L-03","L-07"]),
    ("P5", "paraphrase", "退休站长因为怕担责任瞒了三十年的旧事故", ["H-09","H-10"]),
    # 否定条件 5 条：gold 要求"不得出现 X"类被规则拦下，语义腿不得因 X 检出
    ("N1", "negation", "不要铜蟾蜍，老站长随身带的东西", ["H-01","H-03","H-04"]),
    ("N2", "negation", "不涉及八三年事故的设定", ["S-01","S-02","S-03","L-01","L-02"]),
    ("N3", "negation", "排除缺页相关的测绘员条目", ["L-01","L-02","L-03","L-04","L-05","L-06","L-08","L-09","L-10"]),
    ("N4", "negation", "不要搪瓷缸相关的老站长习惯", ["H-02","H-05","H-06","H-08","H-09"]),
    ("N5", "negation", "不提盐壳样本的观测员工作", ["S-01","S-02","S-03","S-04","S-06"]),
    # 精确数值 5 条：gold 必须是数值精确对应的条目，不得语义近似返回别的数
    ("M1", "number", "十二点七", ["S-04","H-04"]),
    ("M2", "number", "第四十一天", ["S-01"]),
    ("M3", "number", "三十年是铜钉贴身携带的时间", ["H-08","H-10"]),
    ("M4", "number", "样本编号 S-023 和 S-024", ["S-05"]),
    ("M5", "number", "第十七页缺失", ["L-07"]),
    # 专名 5 条
    ("E1", "entity", "沈砚秋", ["S-01","S-02","S-03","S-04","S-05","S-06","S-07","S-08","S-09","S-10"]),
    ("E2", "entity", "陆行舟", ["L-01","L-02","L-03","L-04","L-05","L-06","L-07","L-08","L-09","L-10"]),
    ("E3", "entity", "贺长庚 老站长", ["H-01","H-02","H-03","H-04","H-05","H-06","H-07","H-08","H-09","H-10"]),
    ("E4", "entity", "白碱滩 七号观测井", ["S-02","S-04","H-04","H-07"]),
    ("E5", "entity", "黄铜游标卡尺", ["S-03"]),
]

# ---------- 5. 指标 ----------
def recall_at(retrieved, gold, k):
    if not gold: return None
    return len(set(retrieved[:k]) & set(gold)) / len(set(gold))

def false_recall(retrieved, gold, k):
    # 误召回率：前 k 里非 gold 的占比
    top = retrieved[:k]
    if not top: return 0.0
    return sum(1 for x in top if x not in set(gold)) / len(top)

def rrf_merge(vec_rank, bm_rank, rrf_k=60, topn=10):
    scores = Counter()
    for lst in (vec_rank, bm_rank):
        for rank, idx in enumerate(lst[:topn]):
            scores[idx] += 1.0 / (rrf_k + rank + 1)
    return [idx for idx, _ in scores.most_common()]

# ---------- 6. 判据 v2（事前定义，2026-11-20 派单人裁定）----------
# 版本历史：
#   v1（废止）：整体 20 条 @10 混合比 BM25 >= +15pp —— 因铁律把 15/20 条挡在向量外，上限被锁死，废止。
#   v2（本版，事前定义）：分组判定
#     R1  换说法组：hybrid@10 - bm25@10 >= +15pp
#     R2  数值/否定/专名三组：每组 hybrid@10 - bm25@10 >= -5pp（即不降超 5pp）
#     R3  整体召回率不作为通过条件，仅作参考读数（overall_is_gate=False）
#     总判定：R1 与 R2 三组全部 PASS => verdict=PASS；任一 FAIL => verdict=FAIL
# 纪律：本判定基于上面的检索结果做确定性比较，不改动检索、gold、语料、阈值；重跑即复现。
JUDGMENT_VERSION = "v2"
JUDGMENT_RULES = {
    "R1_paraphrase_min_delta_pp": 15.0,
    "R2_other_max_drop_pp": 5.0,
    "R3_overall_is_gate": False,
}
JUDGMENT_DEFINED = "2026-11-20 派单人裁定（事前定义；整体 +15pp 判据 v1 废止）"

def judge(summary):
    checks = []
    d = summary["paraphrase"]["delta_pp"]
    checks.append({"rule": "R1_paraphrase", "metric": "@10 delta >= +15pp",
                   "value_pp": d, "threshold_pp": 15.0, "pass": d >= 15.0})
    for cat in ("number", "negation", "entity"):
        d = summary[cat]["delta_pp"]
        checks.append({"rule": "R2_" + cat, "metric": "@10 delta >= -5pp (不降超5pp)",
                       "value_pp": d, "threshold_pp": -5.0, "pass": d >= -5.0})
    verdict = "PASS" if all(c["pass"] for c in checks) else "FAIL"
    return {"version": JUDGMENT_VERSION, "defined": JUDGMENT_DEFINED,
            "rules": JUDGMENT_RULES, "checks": checks, "verdict": verdict,
            "overall_is_gate": False,
            "note": "整体召回率仅参考读数；本次离线脚本未实现精筛层否定规则，negation/entity 组为纯召回上限数据"}

def main():
    entries = load_entries()
    print("entries:", len(entries))
    doc_vecs, emb_doc_ms = embed([e["text"] for e in entries])
    print("embedded docs in %.0f ms, dim=%d" % (emb_doc_ms, len(doc_vecs[0])))
    bm = BM25(entries)
    queries = [q[2] for q in QUERIES]
    q_vecs, emb_q_ms = embed(queries)
    print("embedded %d queries in %.0f ms (avg %.1f ms)" % (len(queries), emb_q_ms, emb_q_ms/len(queries)))

    results = []
    for (qid, cat, qtext, gold), qv in zip(QUERIES, q_vecs):
        bm_rank = [i for i, _ in bm.score(qtext)]
        vec_rank = [i for _, i in sorted(((cos(qv, v), i) for i, v in enumerate(doc_vecs)), key=lambda x: -x[0])]
        bm_ids = [entries[i]["id"] for i in bm_rank[:10]]
        merged_ids = [entries[i]["id"] for i in rrf_merge(vec_rank, bm_rank)]
        r = {
            "qid": qid, "cat": cat, "query": qtext, "gold": gold,
            "bm25": {"@5": recall_at(bm_ids, gold, 5), "@10": recall_at(bm_ids, gold, 10),
                     "false@10": false_recall(bm_ids, gold, 10), "top10": bm_ids},
            "hybrid": {"@5": recall_at(merged_ids, gold, 5), "@10": recall_at(merged_ids, gold, 10),
                       "false@10": false_recall(merged_ids, gold, 10), "top10": merged_ids},
        }
        r["hybrid"]["d@10_pp"] = round((r["hybrid"]["@10"] - r["bm25"]["@10"]) * 100, 1)
        results.append(r)

    # 汇总
    def avg(xs): return sum(xs)/len(xs) if xs else 0.0
    summary = {}
    for cat in ["paraphrase","negation","number","entity"]:
        rs = [r for r in results if r["cat"] == cat]
        summary[cat] = {
            "n": len(rs),
            "bm25@10": round(avg([r["bm25"]["@10"] for r in rs]), 4),
            "hybrid@10": round(avg([r["hybrid"]["@10"] for r in rs]), 4),
            "delta_pp": round((avg([r["hybrid"]["@10"] for r in rs]) - avg([r["bm25"]["@10"] for r in rs])) * 100, 1),
            "bm25_false@10": round(avg([r["bm25"]["false@10"] for r in rs]), 4),
            "hybrid_false@10": round(avg([r["hybrid"]["false@10"] for r in rs]), 4),
        }
    allr = results
    summary["overall"] = {
        "bm25@10": round(avg([r["bm25"]["@10"] for r in allr]), 4),
        "hybrid@10": round(avg([r["hybrid"]["@10"] for r in allr]), 4),
        "delta_pp": round((avg([r["hybrid"]["@10"] for r in allr]) - avg([r["bm25"]["@10"] for r in allr])) * 100, 1),
        "bm25_false@10": round(avg([r["bm25"]["false@10"] for r in allr]), 4),
        "hybrid_false@10": round(avg([r["hybrid"]["false@10"] for r in allr]), 4),
    }
    summary["paraphrase_only"] = summary["paraphrase"]
    out = {"meta": {"entries": len(entries), "model": "Qwen3-Embedding-0.6B Q5_K_M (local llama.cpp)",
                    "dim": len(doc_vecs[0]), "emb_doc_ms": round(emb_doc_ms), "emb_query_total_ms": round(emb_q_ms),
                    "emb_query_avg_ms": round(emb_q_ms/len(queries),1), "embedding_network": "127.0.0.1 only, no outbound",
                    "note": "RRF 融合；否定/数值/专名按铁律不进语义腿的判定在精筛层做，本离线脚本仅先测纯召回上限"},
           "judgment": judge(summary),
           "summary": summary, "details": results}
    json.dump(out, open(OUT, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
    print("\n=== SUMMARY ===")
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    print("\n=== JUDGMENT (判据 %s) ===" % JUDGMENT_VERSION)
    jd = out["judgment"]
    for c in jd["checks"]:
        print("  %-18s %-28s value=%+5.1fpp  threshold=%+5.1fpp  %s" %
              (c["rule"], c["metric"], c["value_pp"], c["threshold_pp"], "PASS" if c["pass"] else "FAIL"))
    print("  overall is gate: %s (仅参考: %+.1fpp)" % (jd["overall_is_gate"], summary["overall"]["delta_pp"]))
    print("  >>> VERDICT: %s (判据 %s)" % (jd["verdict"], jd["version"]))
    print("\nwrote", OUT)

if __name__ == "__main__":
    main()
