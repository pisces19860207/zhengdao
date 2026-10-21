#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""osv-scan.py —— 离线 OSV 漏洞扫描（第四十三批 · 任务 B；report-only，不阻塞 CI）。

为什么
------
供应链可见的下一个问题：这些构件里有已知漏洞吗？本脚本用**官方 osv-scanner 二进制**
（固定版本 + sha256 校验，manifest：tools/osv-scanner.json）扫 CycloneDX SBOM，
漏洞数据只来自**入库的离线快照** `tools/osv/osv-scanner/Maven/all.zip`
（manifest：tools/osv/SNAPSHOT.json），默认不联网。

三个硬口径（派工单 §2）
----------------------
1. **数据截止日打在报告首行**：取快照自身的 Last-Modified（不是运行当天）——
   离线库有多旧，读者第一眼就要知道；
2. **确定性输出**：报告不写运行时间，全部按 (severity, id, package) 排序；
   同一快照、同一输入连跑两次，输出逐字节一致（`--selftest` 会实际验这条）；
3. **report-only**：发现问题不判红（退出码恒 0，findings 走 ::warning annotation）。
   退出码 2 只用于环境错误（二进制/快照缺失、scanner 自身崩溃）。

SBOM 输入怎么进 scanner
-----------------------
osv-scanner v2.7.0 **不直接读 CycloneDX**（无 sbom 子命令），但支持官方自定义
lockfile 中间格式 `--lockfile osv-scanner:<file>`（osv-scanner 文档
「Custom Lockfiles」一节）。本脚本把 SBOM 主清单（scope=required，即 release
运行时闭包——真正打进 APK 的部分）翻译成该格式再扫。分区（debug/test/…）不进
扫描输入：它们不随 release 分发，要扫可临时把对应分区构件另存 SBOM 再跑。

扫描 N 怎么数
-------------
N = 主清单去重后的构件数；一条漏洞可能同时命中多个 id（OSV 与 GHSA 双源），
report 按 (构件, 漏洞 id) 逐条列，H/M/L 按唯一 (构件, id) 计数。

用法
----
    python3 tools/osv-scan.py [--sbom build/sbom/sbom-*.cdx.json] [--out DIR]
    python3 tools/osv-scan.py --selftest      # 夹具验收：高危必中、干净版不中、两次运行 diff 为空
    python3 tools/osv-scan.py --update-db     # 手动刷新离线快照（默认关闭；刷新即改写 SNAPSHOT.json）

产物（--out 下，CI 进 artifact）：
    vuln-report-<commit>-<date>.md     人读报告（首行数据截止日）
    vuln-report-<commit>-<date>.json   机读数据（含 scanner 原始输出）
"""

from __future__ import annotations

import argparse
import glob
import hashlib
import json
import os
import platform
import re
import subprocess
import sys
import tempfile
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path


try:  # Windows 控制台默认 GBK：个别字符编不出来时降级为替换，而不是让门禁自己崩
    sys.stdout.reconfigure(errors="replace")
except Exception:  # noqa: BLE001 - reconfigure 是尽力而为
    pass

ROOT = Path(__file__).resolve().parent.parent
MANIFEST = ROOT / "tools" / "osv-scanner.json"
SNAPSHOT = ROOT / "tools" / "osv" / "SNAPSHOT.json"
DB_DIR = ROOT / "tools" / "osv"
SBOM_GLOB = "build/sbom/sbom-*-*.cdx.json"
DEFAULT_OUT = ROOT / "build" / "supply-chain"
DB_URL = "https://osv-vulnerabilities.storage.googleapis.com/Maven/all.zip"

# 严重级映射（GitHub advisory 的 database_specific.severity → 本报告三档）
DB_SPECIFIC_SEVERITY = {
    "CRITICAL": "高危",
    "HIGH": "高危",
    "MODERATE": "中危",
    "MEDIUM": "中危",
    "LOW": "低危",
}
SEVERITY_ORDER = {"高危": 0, "中危": 1, "低危": 2, "未分级": 3}

# CVSS 3.1 基础分权重（规范 7.2 表）
CVSS_WEIGHTS = {
    "AV": {"N": 0.85, "L": 0.62, "A": 0.55, "P": 0.20},
    "AC": {"L": 0.77, "H": 0.44},
    "PR_U": {"N": 0.85, "L": 0.62, "H": 0.27},
    "PR_C": {"N": 0.85, "L": 0.68, "H": 0.50},
    "UI": {"N": 0.85, "R": 0.62},
    "CIA": {"H": 0.56, "L": 0.22, "N": 0.0},
}


def cvss3_base_score(vector: str) -> float | None:
    """CVSS 3.x 向量 → 基础分（规范公式；解析不了返回 None）。"""
    metrics = {}
    for part in vector.split("/"):
        if ":" in part:
            k, _, v = part.partition(":")
            metrics[k] = v
    if "AV" not in metrics or "AC" not in metrics:
        return None
    try:
        scope = metrics.get("S", "U")
        av = CVSS_WEIGHTS["AV"][metrics["AV"]]
        ac = CVSS_WEIGHTS["AC"][metrics["AC"]]
        pr = CVSS_WEIGHTS["PR_C" if scope == "C" else "PR_U"][metrics["PR"]]
        ui = CVSS_WEIGHTS["UI"][metrics["UI"]]
        c = CVSS_WEIGHTS["CIA"][metrics["C"]]
        i = CVSS_WEIGHTS["CIA"][metrics["I"]]
        a = CVSS_WEIGHTS["CIA"][metrics["A"]]
    except KeyError:
        return None
    iss = 1 - (1 - c) * (1 - i) * (1 - a)
    if scope == "U":
        impact = 6.42 * iss
    else:
        impact = 7.52 * (iss - 0.029) - 3.25 * (iss - 0.02) ** 15
    if impact <= 0:
        return 0.0
    exploitability = 8.22 * av * ac * pr * ui
    raw = min(impact + exploitability, 10.0)
    # CVSS 规范 roundup（一位小数）：向上取到 0.1
    import math
    return math.ceil(raw * 10) / 10.0


def severity_band(vuln: dict) -> str:
    """漏洞 → 高危/中危/低危/未分级。优先 advisory 自带的定性标签，兜底 CVSS 3 向量计算。"""
    label = (vuln.get("database_specific") or {}).get("severity", "")
    if isinstance(label, str) and label.upper() in DB_SPECIFIC_SEVERITY:
        return DB_SPECIFIC_SEVERITY[label.upper()]
    best = None
    for entry in vuln.get("severity", []) or []:
        stype = (entry.get("type") or "").upper()
        score = entry.get("score") or ""
        if stype.startswith("CVSS_V3") and score.startswith("CVSS:"):
            value = cvss3_base_score(score)
            if value is not None:
                best = value if best is None else max(best, value)
    if best is None:
        return "未分级"
    if best >= 7.0:
        return "高危"
    if best >= 4.0:
        return "中危"
    return "低危"


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def platform_key() -> str:
    machine = platform.machine().lower()
    system = platform.system().lower()
    if system == "windows":
        arch = "arm64" if "arm" in machine else "amd64"
        return "windows_%s" % arch
    if system == "darwin":
        arch = "arm64" if "arm" in machine else "amd64"
        return "darwin_%s" % arch
    arch = "arm64" if "arm" in machine or "aarch" in machine else "amd64"
    return "linux_%s" % arch


def cache_dir() -> Path:
    env = os.environ.get("ZHENGDAO_TOOL_CACHE")
    if env:
        return Path(env)
    if sys.platform == "win32":
        base = os.environ.get("LOCALAPPDATA") or str(Path.home())
        return Path(base) / "zhengdao-tools"
    return Path.home() / ".cache" / "zhengdao-tools"


def download(url: str, dest: Path) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    req = urllib.request.Request(url, headers={"User-Agent": "zhengdao-osv-scan/43"})
    with urllib.request.urlopen(req, timeout=120) as resp, dest.open("wb") as f:
        while True:
            chunk = resp.read(1 << 20)
            if not chunk:
                break
            f.write(chunk)


def ensure_scanner() -> Path:
    """取 scanner 二进制：缓存里没有/校验不过就下载；每次运行都校验 sha256。"""
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    key = platform_key()
    entry = manifest["files"].get(key)
    if entry is None:
        print("::error title=OSV::manifest 里没有平台 %s 的条目（%s）" % (key, MANIFEST))
        raise SystemExit(2)
    binary = cache_dir() / ("osv-scanner-%s-%s" % (manifest["version"], key))
    need_download = True
    if binary.exists():
        if sha256_file(binary) == entry["sha256"]:
            need_download = False
        else:
            print("::warning title=OSV::缓存二进制 sha256 不符，重新下载")
            binary.unlink(missing_ok=True)
    if need_download:
        url = "%s/%s" % (manifest["base_url"], entry["asset"])
        print("[osv] 下载 scanner %s（%s，约 60MB）" % (manifest["version"], key))
        tmp = binary.with_suffix(".part")
        try:
            download(url, tmp)
        except Exception as exc:  # noqa: BLE001 - 下载失败要给出可读原因
            print("::error title=OSV::scanner 下载失败：%s" % exc)
            raise SystemExit(2)
        actual = sha256_file(tmp)
        if actual != entry["sha256"]:
            print("::error title=OSV::scanner sha256 不符（期望 %s，实得 %s）"
                  % (entry["sha256"][:16], actual[:16]))
            tmp.unlink(missing_ok=True)
            raise SystemExit(2)
        tmp.replace(binary)
    if sys.platform != "win32":
        binary.chmod(0o755)
    return binary


def ensure_db() -> dict:
    """校验离线快照在库且 sha256 与 SNAPSHOT.json 一致，返回快照元数据。"""
    snapshot = json.loads(SNAPSHOT.read_text(encoding="utf-8"))
    db_zip = ROOT / snapshot["path"]
    if not db_zip.exists():
        print("::error title=OSV::离线快照缺失：%s（python3 tools/osv-scan.py --update-db 可重新下载）"
              % snapshot["path"])
        raise SystemExit(2)
    actual = sha256_file(db_zip)
    if actual != snapshot["sha256"]:
        print("::error title=OSV::快照 sha256 与 SNAPSHOT.json 不符（期望 %s…，实得 %s…）"
              % (snapshot["sha256"][:16], actual[:16]))
        raise SystemExit(2)
    return snapshot


def cyclonedx_to_custom_lockfile(sbom: dict) -> tuple[dict, int]:
    """SBOM 主清单 → osv-scanner 自定义 lockfile 格式。返回 (lockfile dict, 构件数)。"""
    packages = []
    seen = set()
    for comp in sbom.get("components", []):
        if comp.get("scope") != "required":
            continue  # 主清单才是随 release 分发的内容
        group, name, version = comp.get("group"), comp.get("name"), comp.get("version")
        if not (group and name and version):
            continue
        key = (group, name, version)
        if key in seen:
            continue
        seen.add(key)
        packages.append({"package": {
            "name": "%s:%s" % (group, name),
            "version": version,
            "ecosystem": "Maven",
        }})
    packages.sort(key=lambda p: (p["package"]["name"], p["package"]["version"]))
    return {"results": [{"packages": packages}]}, len(packages)


def run_scanner(binary: Path, lockfile_path: Path, raw_out: Path) -> int:
    """跑 scanner（离线）。返回 returncode：0=无发现，1=有发现，其他=错误。"""
    env = dict(os.environ)
    env["OSV_SCANNER_LOCAL_DB_CACHE_DIRECTORY"] = str(DB_DIR)
    cmd = [
        str(binary),
        "--lockfile", "osv-scanner:" + lockfile_path.as_posix(),
        "--offline-vulnerabilities",
        "--no-resolve",  # 输入已是全量闭包；禁止 scanner 再联网解析传递依赖
        "--all-packages",  # 干净构件也进 JSON 输出，扫描数可与输出本身核对
        "--format", "json",
        "--output-file", str(raw_out),
    ]
    print("[osv] 扫描命令：" + " ".join(cmd[:6]) + " …")
    proc = subprocess.run(cmd, capture_output=True, text=True,
                          encoding="utf-8", errors="replace", env=env, check=False)
    if proc.returncode not in (0, 1):
        print("::error title=OSV::scanner 退出码 %d" % proc.returncode)
        err = "\n".join((proc.stderr or "").splitlines()[-10:])
        if err:
            print(err)
        raise SystemExit(2)
    return proc.returncode


def load_findings(raw_path: Path) -> list[dict]:
    """把 scanner 原始输出整理成 [(构件, 漏洞)] 列表，稳定排序。"""
    doc = json.loads(raw_path.read_text(encoding="utf-8"))
    findings = []
    for result in doc.get("results", []):
        for pkg in result.get("packages", []):
            info = pkg.get("package", {})
            name, version = info.get("name", "?"), info.get("version", "?")
            for vuln in pkg.get("vulnerabilities", []):
                vuln_id = vuln.get("id", "?")
                aliases = [a for a in (vuln.get("aliases") or [])]
                ranges = []
                for aff in vuln.get("affected", []):
                    if aff.get("package", {}).get("name") != name:
                        continue
                    for rng in aff.get("ranges", []):
                        events = rng.get("events", [])
                        introduced = next((e.get("introduced") for e in events
                                           if e.get("introduced")), "0")
                        fixed = next((e.get("fixed") for e in events if e.get("fixed")), None)
                        ranges.append({"introduced": introduced, "fixed": fixed})
                findings.append({
                    "package": name,
                    "version": version,
                    "id": vuln_id,
                    "aliases": sorted(set(aliases)),
                    "severity": severity_band(vuln),
                    "summary": (vuln.get("summary") or "").strip(),
                    "affected": ranges,
                })
    findings.sort(key=lambda f: (SEVERITY_ORDER.get(f["severity"], 9),
                                 f["package"], f["version"], f["id"]))
    return findings


def render_report(findings: list[dict], scanned: int, snapshot: dict,
                  groups: dict) -> str:
    lines = []
    lines.append("数据截止日：%s（OSV Maven 全量快照 Last-Modified：%s，sha256 %s…）"
                 % (snapshot["cutoff_date"], snapshot["last_modified"],
                    snapshot["sha256"][:12]))
    lines.append("扫描 %d 个构件，发现 %d 高危 / %d 中危 / %d 低危"
                 "（共 %d 条，含未分级 %d 条；report-only，不阻塞 CI）"
                 % (scanned, groups["高危"], groups["中危"], groups["低危"],
                    len(findings), groups["未分级"]))
    lines.append("")
    if not findings:
        lines.append("未发现任何已知漏洞（快照 %s 内，主清单 %d 个构件全部干净）。"
                     % (snapshot["cutoff_date"], scanned))
        return "\n".join(lines) + "\n"
    lines.append("| 级别 | 构件 | 版本 | 漏洞 | CVE | 影响范围 | 摘要 |")
    lines.append("| --- | --- | --- | --- | --- | --- | --- |")
    for f in findings:
        cves = ", ".join(a for a in f["aliases"] if a.startswith("CVE-"))
        ranges = "；".join("%s ≤ v < %s" % (r["introduced"], r["fixed"] or "最新")
                           for r in f["affected"]) or "见 OSV 记录"
        lines.append("| %s | %s | %s | %s | %s | %s | %s |"
                     % (f["severity"], f["package"], f["version"], f["id"],
                        cves or "-", ranges,
                        (f["summary"][:60] + "…") if len(f["summary"]) > 60
                        else (f["summary"] or "-")))
    lines.append("")
    lines.append("> 漏洞匹配与版本序由 osv-scanner 官方二进制判定（规范实现，"
                 "不含本项目自研匹配逻辑）；一条漏洞可能被 OSV/GHSA 双源各记一条。")
    return "\n".join(lines) + "\n"


def count_by_severity(findings: list[dict]) -> dict:
    counts = {"高危": 0, "中危": 0, "低危": 0, "未分级": 0}
    seen = set()
    for f in findings:
        key = (f["package"], f["version"], f["id"])
        if key in seen:
            continue
        seen.add(key)
        counts[f["severity"]] = counts.get(f["severity"], 0) + 1
    return counts


def scan_sbom(sbom_path: Path, out_dir: Path, commit: str, date: str) -> Path:
    binary = ensure_scanner()
    snapshot = ensure_db()
    doc = json.loads(sbom_path.read_text(encoding="utf-8"))
    lockfile, scanned = cyclonedx_to_custom_lockfile(doc)
    out_dir.mkdir(parents=True, exist_ok=True)
    lockfile_path = out_dir / ("osv-input-%s.json" % date)
    lockfile_path.write_text(json.dumps(lockfile, ensure_ascii=False), encoding="utf-8")
    raw_path = out_dir / ("osv-raw-%s.json" % date)
    run_scanner(binary, lockfile_path, raw_path)
    findings = load_findings(raw_path)
    groups = count_by_severity(findings)
    report = render_report(findings, scanned, snapshot, groups)
    base = "vuln-report-%s-%s" % (commit, date)
    (out_dir / (base + ".md")).write_text(report, encoding="utf-8")
    payload = {
        "generator": "tools/osv-scan.py",
        "scanner": json.loads(MANIFEST.read_text(encoding="utf-8"))["version"],
        "snapshot": snapshot,
        "sbom": os.path.relpath(sbom_path, ROOT).replace("\\", "/"),
        "scanned_components": scanned,
        "counts": groups,
        "findings": findings,
    }
    (out_dir / (base + ".json")).write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("[osv] 写入 %s.md / .json（构件 %d，发现 %d 条）" % (base, scanned, len(findings)))
    for f in findings:
        print("::warning title=OSV 发现 %s::%s@%s — %s（%s）"
              % (f["severity"], f["package"], f["version"], f["id"],
                 ", ".join(f["aliases"]) or "无 CVE"))
    if not findings:
        print("[osv] ✓ 本次扫了 %d 个构件，0 高危 / 0 中危 / 0 低危（快照 %s）"
              % (scanned, snapshot["cutoff_date"]))
    return out_dir / (base + ".md")


def selftest() -> int:
    """夹具验收（派工单 §2 验收）：高危必中、干净不中、截止日首行、两次运行 diff 为空。"""
    fixture = ROOT / "tools" / "osv" / "fixture-vuln.cdx.json"
    failures = []
    with tempfile.TemporaryDirectory(prefix="osv-selftest-") as tmp:
        out_dir = Path(tmp)
        # 第一次
        report1 = scan_sbom(fixture, out_dir, "selftest", "run1")
        text1 = report1.read_text(encoding="utf-8")
        findings1 = load_findings(out_dir / "osv-raw-run1.json")
        # 第二次（同输入同快照）
        report2 = scan_sbom(fixture, out_dir, "selftest", "run2")
        text2 = report2.read_text(encoding="utf-8")
        body1 = text1.splitlines()[1:]
        body2 = text2.splitlines()[1:]
        if body1 != body2:
            failures.append("两次运行报告正文不一致（确定性被破坏）")

    ids = {(f["package"], f["version"], f["id"]) for f in findings1}
    aliases = {a for f in findings1 for a in f["aliases"]}
    if not any(p == "org.apache.logging.log4j:log4j-core" and v == "2.14.1"
               for (p, v, _) in ids):
        failures.append("夹具 log4j-core:2.14.1 没有被扫到")
    elif not ("CVE-2021-44228" in aliases or "CVE-2021-45046" in aliases):
        failures.append("夹具 log4j-core:2.14.1 扫到了，但没有 CVE-2021-44228/45046（高危没命中）")
    cc = [f for f in findings1 if f["package"] == "org.apache.commons:commons-compress"]
    if not any("CVE-2024-25710" in f["aliases"] for f in cc):
        failures.append("夹具 commons-compress:1.25.0 没命中 CVE-2024-25710")
    if any(f["package"] == "org.tukaani:xz" for f in findings1):
        failures.append("干净构件 xz:1.9 被误报")
    if not text1.startswith("数据截止日："):
        failures.append("报告首行不是数据截止日")

    if failures:
        for f in failures:
            print("::error title=OSV 自检失败::" + f)
        return 1
    print("[osv] ✓ 自检全过：高危命中（log4j-core 2.14.1 → CVE-2021-44228/45046）、"
          "commons-compress 1.25.0 → CVE-2024-25710、xz 1.9 干净、截止日首行、两次运行一致")
    return 0


def update_db() -> int:
    """手动刷新离线快照（默认不跑）。下载 → 校验 → 替换 → 改写 SNAPSHOT.json。"""
    print("[osv] 刷新快照：%s" % DB_URL)
    tmp = DB_DIR / "all.zip.new"
    try:
        req = urllib.request.Request(DB_URL, headers={"User-Agent": "zhengdao-osv-scan/43"},
                                     method="HEAD")
        with urllib.request.urlopen(req, timeout=60) as resp:
            last_modified = resp.headers.get("Last-Modified", "")
        download(DB_URL, tmp)
    except Exception as exc:  # noqa: BLE001
        print("::error title=OSV::快照下载失败：%s" % exc)
        tmp.unlink(missing_ok=True)
        return 2
    digest = sha256_file(tmp)
    count = 0
    with tempfile.TemporaryDirectory() as td:
        import zipfile
        with zipfile.ZipFile(tmp) as z:
            count = sum(1 for n in z.namelist() if n.endswith(".json"))
    now = __import__("datetime").datetime.now(
        __import__("datetime").timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    snapshot = json.loads(SNAPSHOT.read_text(encoding="utf-8"))
    snapshot.update({
        "last_modified": last_modified,
        "sha256": digest,
        "size_bytes": tmp.stat().st_size,
        "record_count": count,
        "cutoff_date": last_modified[:11].strip().split(" ")[0] if last_modified else "",
        "fetched_at": now,
    })
    # cutoff_date 规范成 ISO：Thu, 16 Oct 2026 20:03:11 GMT → 2026-10-16
    m = re.match(r"(\d{2}) (\w{3}) (\d{4})", last_modified or "")
    if m:
        months = {"Jan": "01", "Feb": "02", "Mar": "03", "Apr": "04", "May": "05",
                  "Jun": "06", "Jul": "07", "Aug": "08", "Sep": "09", "Oct": "10",
                  "Nov": "11", "Dec": "12"}
        snapshot["cutoff_date"] = "%s-%s-%s" % (m.group(3), months[m.group(2)], m.group(1))
    dest = DB_DIR / "osv-scanner" / "Maven" / "all.zip"
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp.replace(dest)
    SNAPSHOT.write_text(json.dumps(snapshot, ensure_ascii=False, indent=2) + "\n",
                        encoding="utf-8")
    print("[osv] 快照已刷新：Last-Modified=%s sha256=%s… 记录 %d 条 → %s"
          % (snapshot["last_modified"], digest[:12], count, dest))
    return 0


def latest_date() -> str:
    import datetime as dt
    return dt.datetime.now().strftime("%Y%m%d")


def git_short_commit() -> str:
    try:
        out = subprocess.run(["git", "rev-parse", "--short", "HEAD"],
                             capture_output=True, text=True, encoding="utf-8",
                             errors="replace", cwd=str(ROOT), check=False)
        if out.returncode == 0 and out.stdout.strip():
            return out.stdout.strip()
    except OSError:
        pass
    return "unknown"


def find_latest_sbom() -> Path | None:
    cands = sorted(glob.glob(str(ROOT / SBOM_GLOB)), key=os.path.getmtime)
    return Path(cands[-1]) if cands else None


def main() -> int:
    ap = argparse.ArgumentParser(description="离线 OSV 漏洞扫描（report-only）")
    ap.add_argument("--sbom", type=Path, default=None)
    ap.add_argument("--out", type=Path, default=DEFAULT_OUT)
    ap.add_argument("--selftest", action="store_true", help="跑夹具验收")
    ap.add_argument("--update-db", action="store_true", help="刷新离线快照（默认关闭）")
    args = ap.parse_args()

    if args.update_db:
        return update_db()
    if args.selftest:
        return selftest()

    sbom = args.sbom or find_latest_sbom()
    if not sbom or not sbom.exists():
        print("::error title=OSV::找不到 SBOM（先跑 python3 tools/sbom.py）")
        return 2
    scan_sbom(sbom, args.out, git_short_commit(), latest_date())
    return 0


if __name__ == "__main__":
    sys.exit(main())
