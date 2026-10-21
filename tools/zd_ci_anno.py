# -*- coding: utf-8 -*-
"""取 GitHub check-runs / annotations（匿名，仓库公开）并把 CI 基线落地。

为什么能匿名：仓库是公开的，`commits/<sha>/check-runs` 与 `check-runs/<id>/annotations`
都无需 token（同 ERRATA E-028 的处置思路）。Actions 的**日志**未登录读不到，annotations 可以。

为什么要分块：GitHub 对单条 annotation 的 message 会**截断**（2026-10-15 实测约 2.8 KB 处断开，
取数方 `json.loads` 报 `Unterminated string`）。整份基线 base64 有 6 KB+ ⇒
`tools/rebuild-and-compare.py --emit-baseline` 改成每 1500 字符一块、标题带 `part k/n`；
本脚本按 k 顺序拼接，并用 summary 里公布的**字节数 + sha256** 双重校验后落盘。

用法：
    python tools/zd_ci_anno.py <sha>                        # 列 check-runs 与 annotations（摘要）
    python tools/zd_ci_anno.py <sha> --wait 900             # 等所有 check-run 离开 queued/in_progress
    python tools/zd_ci_anno.py <sha> --save-baseline tools/so-repro-baseline-ci.json
"""
import argparse, base64, hashlib, io, json, os, re, sys, time, urllib.request


try:  # Windows 控制台默认 GBK：个别字符编不出来时降级为替换，而不是让门禁自己崩
    sys.stdout.reconfigure(errors="replace")
except Exception:  # noqa: BLE001 - reconfigure 是尽力而为
    pass

REPO = "pisces19860207/zhengdao"
API = "https://api.github.com/repos/%s" % REPO


def get(url):
    req = urllib.request.Request(url, headers={
        "User-Agent": "zd-ci-anno/2.0",
        "Accept": "application/vnd.github+json",
    })
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)


def check_runs(sha):
    return get("%s/commits/%s/check-runs" % (API, sha)).get("check_runs", [])


def annotations(run_id):
    return get("%s/check-runs/%d/annotations" % (API, run_id))


def short(s, n=200):
    s = " ".join(str(s).split())
    return s if len(s) <= n else s[:n] + "…"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("sha")
    ap.add_argument("--wait", type=int, default=0, help="等所有 run 离开 queued/in_progress（秒）")
    ap.add_argument("--save-baseline", default=None, help="把本主机基线写到这个路径")
    args = ap.parse_args()

    deadline = time.time() + args.wait
    while True:
        runs = check_runs(args.sha)
        pending = [r for r in runs if r.get("status") in ("queued", "in_progress")]
        print("[run] %d 个 check-run；未结束 %d 个 (%s)"
              % (len(runs), len(pending),
                 ", ".join("%s:%s" % (r["name"], r["status"]) for r in pending)))
        if not pending or time.time() >= deadline:
            break
        time.sleep(20)

    summary = None          # (host, size, sha256, json_bytes, n_parts)
    chunks = {}             # k -> 文本
    legacy = None           # 兜底：未分块的 base64(n)=…
    for r in runs:
        print("  · %-10s %-12s %s  id=%s" % (r["name"], r["status"], r.get("conclusion"), r["id"]))
        try:
            anns = annotations(r["id"])
        except Exception as exc:
            print("      (annotations 读取失败：%r)" % (exc,))
            continue
        for a in anns:
            title = a.get("title") or ""
            msg = " ".join(str(a.get("message") or "").split())
            print("      [%s] %s :: %s" % (a.get("annotation_level"), title, short(msg, 200)))
            m = re.search(r"host=([\w.\-]+).*?normalized=(\d+) B/([0-9a-f]{64})"
                          r".*?json=(\d+) B sha256 ([0-9a-f]{64}).*?分 (\d+) 块", msg)
            if m:
                summary = (m.group(1), int(m.group(2)), m.group(3), int(m.group(4)), m.group(5), int(m.group(6)))
                continue
            m = re.search(r"^part (\d+)/(\d+) ([A-Za-z0-9+/=]+)$", msg)
            if m:
                chunks[int(m.group(1))] = m.group(3)
                continue
            m = re.search(r"base64\((\d+)\)=([A-Za-z0-9+/=]+)", msg)
            if m:
                legacy = (int(m.group(1)), m.group(2))

    if args.save_baseline:
        if summary:
            host, n_size, n_sha, n_bytes, d_sha, n_parts = summary
            assert "linux" in host, "这份基线不是 Linux 主机的：host=%r" % host
            missing = [k for k in range(1, n_parts + 1) if k not in chunks]
            assert not missing, "缺块 %r（共 %d 块）" % (missing, n_parts)
            b64 = "".join(chunks[k] for k in range(1, n_parts + 1))
            print("[save] summary: host=%s normalized=%d B/%s json=%d B sha256 %s 块数=%d"
                  % (host, n_size, n_sha, n_bytes, d_sha, n_parts))
            blob = base64.b64decode(b64)
            actual = hashlib.sha256(blob).hexdigest()
            assert len(blob) == n_bytes, "字节数不符：%d ≠ %d" % (len(blob), n_bytes)
            assert actual == d_sha, "sha256 不符：%s ≠ %s" % (actual, d_sha)
        else:
            assert legacy, "既没有分块 summary，也没有整块 base64= —— annotation 里找不到基线"
            blob = base64.b64decode(legacy[1])
        doc = json.loads(blob.decode("utf-8"))
        host = doc.get("generated_on_host") or ""
        assert "linux" in host, "这份基线不是 Linux 主机的：host=%r" % host
        norm = doc["normalized"]
        print("[save] host=%s；normalized=%d B/%s；sections=%d；fingerprint=%s"
              % (host, norm["size"], norm["sha256"],
                 len(norm.get("sections") or []), norm.get("fingerprint")))
        with io.open(args.save_baseline, "w", encoding="utf-8", newline="\n") as f:
            json.dump(doc, f, ensure_ascii=False, indent=2, sort_keys=False)
            f.write("\n")
        print("[save] 已写出 %s（%d 字节）" % (args.save_baseline, os.path.getsize(args.save_baseline)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
