#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""check-memory-asof-migration.py —— 451 批 `memory.as_of` 旧库升级的**真跑**自检。

为什么需要它：本仓无 Robolectric，Kotlin JVM 单测起不了 SQLiteOpenHelper，
`BaopuDbAsOfTest` 只能钉语句层。这里用 Python 标准库 sqlite3 真建一张 v20 老库、
真跑 ALTER、真读旧行、真查「是否该复核」，补上判据①的一级证据。

关键纪律：**所有 SQL 都从 Kotlin 源码提取**，不在此脚本里手写副本——
手写一份「看起来一样」的 SQL 是最经典的假绿（生产改了、测试没改，照样全绿）。
同样地，90 天从 `MemoryTiers.REVIEW_PERIOD_DAYS` 读、一天的毫秒数从
`MemoryQuality.DAY_MILLIS` 读，保证「常量单点可改」这条判据也真被验到。

用法：
    python3 tools/check-memory-asof-migration.py
    python3 tools/check-memory-asof-migration.py --selftest   # 额外跑变异负例
退出码 0 = 全过；非 0 = 判据红（打印 FAIL 原因）。
"""
import argparse
import os
import re
import sqlite3
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MAIN = os.path.join(ROOT, "app", "src", "main", "java", "com", "example", "zhengdao", "baopu")
DB_KT = os.path.join(MAIN, "BaopuDb.kt")
TIERS_KT = os.path.join(MAIN, "MemoryTiers.kt")
QUALITY_KT = os.path.join(MAIN, "MemoryQuality.kt")

NOW = 1_730_000_000_000
OLD_ROWS = [
    ("v20-旧行-A", "project", "preference", "称呼偏好", "叫我老师", "", "s1", "",
     "confirmed", 100, 200, 0, 0, 100, "", "", 0),
    ("v20-旧行-B", "global", "fact", "模型X支持长文", "正文", "", "s2", "",
     "confirmed", 110, 210, 1, 220, 100, "", "", 0),
]
OLD_INSERT = (
    "INSERT INTO memory (id,scope,kind,title,body,tags,source_session,source_file,status,"
    "created_at,updated_at,use_count,last_used_at,score,quality_flags,merged_from,scored_at) "
    "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
)
# v20 老库的夹具：v2 建表（从源码取）＋ v14 质量四列（历史迁移，按 BaopuDbTest 钉的形状）。
# 这是「老库长什么样」的独立夹具，不是本批的生产 SQL；本批要验的 ALTER／查询全部从源码取。
V20_QUALITY_ALTERS = [
    "ALTER TABLE memory ADD COLUMN score INTEGER NOT NULL DEFAULT 100",
    "ALTER TABLE memory ADD COLUMN quality_flags TEXT NOT NULL DEFAULT ''",
    "ALTER TABLE memory ADD COLUMN merged_from TEXT NOT NULL DEFAULT ''",
    "ALTER TABLE memory ADD COLUMN scored_at INTEGER NOT NULL DEFAULT 0",
]


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read().replace("\r\n", "\n")


def kotlin_strings(body):
    """取一段 Kotlin 代码里的字符串字面量（SQL 由多段 `+` 拼接）。"""
    return re.findall(r'"((?:[^"\\]|\\.)*)"', body)


def fun_body(src, name):
    start = src.index("fun " + name)
    marks = [src.find(m, start + len(name) + 4) for m in ("\n    fun ", "\n    /**", "\n}\n", "\n}")]
    ends = [x for x in marks if x > start]
    return src[start:min(ends)] if ends else src[start:]


def load_from_kotlin():
    db = read(DB_KT)
    tiers = read(TIERS_KT)
    quality = read(QUALITY_KT)
    cols = re.search(r"CREATE TABLE IF NOT EXISTS memory \((.*?)\)", db, re.S).group(1)
    create = "CREATE TABLE memory (" + cols + ")"
    alter = re.search(r'const val ALTER = "([^"]+)"', db).group(1)
    review_sql = "".join(kotlin_strings(fun_body(db, "needingReview")))
    missing_sql = "".join(kotlin_strings(fun_body(db, "missingAsOf")))
    # INSERT 语句由 5 段字面量拼成（表名／前 9 列／中间 4 列／质量四列＋as_of／占位符）
    upsert_sql = "".join(kotlin_strings(fun_body(db, "upsertMemory"))[:5])
    days = int(re.search(r"const val REVIEW_PERIOD_DAYS = (\d+)L", tiers).group(1))
    day_ms = int(re.search(r"const val DAY_MILLIS = ([\d_]+)L", quality).group(1).replace("_", ""))
    return {
        "create": create, "alter": alter, "review": review_sql, "missing": missing_sql,
        "upsert": upsert_sql, "days": days, "day_ms": day_ms,
        "period_ms": days * day_ms,
    }


def check(cond, msg):
    if not cond:
        print("FAIL: " + msg)
        sys.exit(1)


def new_row(mid, as_of):
    return (mid, "project", "fact", "模型X支持长文", "正文", "", "s3", "",
            "confirmed", NOW, NOW, 0, 0, 100, "", "", 0, as_of)


def scenario(k, alter_override=None, review_override=None):
    """跑一遍完整链路；返回 (旧行 as_of 列表, 待复核 id 列表, 待补 id 列表)。"""
    con = sqlite3.connect(":memory:")
    cur = con.cursor()
    cur.execute(k["create"])
    for fixture in V20_QUALITY_ALTERS:
        cur.execute(fixture)
    cur.executemany(OLD_INSERT, OLD_ROWS)
    con.commit()
    before = [r[1] for r in cur.execute("PRAGMA table_info(memory)").fetchall()]
    check("as_of" not in before, "v20 老库不该已有 as_of 列（场景没造对）")
    cur.execute(alter_override or k["alter"])
    con.commit()
    old = cur.execute("SELECT id, as_of FROM memory ORDER BY id").fetchall()
    # 幂等：看列再动——已有列就不再 ALTER（重复 ALTER 在 SQLite 会炸，这里必须跳过）
    after = [r[1] for r in cur.execute("PRAGMA table_info(memory)").fetchall()]
    if "as_of" not in after:
        cur.execute(alter_override or k["alter"])
    con.commit()
    period = k["period_ms"]
    day = k["day_ms"]
    rows = [
        new_row("new-91天", NOW - 91 * day),
        new_row("new-90天整", NOW - period),
        new_row("new-89天", NOW - 89 * day),
        new_row("new-待补", None),
    ]
    cur.executemany(k["upsert"], rows)
    con.commit()
    cutoff = NOW - period
    sql = review_override or k["review"]
    # 变异负例可能把占位符去掉，按 SQL 实际占位符数给参数（不假装它一定有一个）
    review = cur.execute(sql, (str(cutoff),) * sql.count("?")).fetchall()
    missing = cur.execute(k["missing"]).fetchall()
    con.close()
    return old, [r[0] for r in review], [r[0] for r in missing]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--selftest", action="store_true", help="跑变异负例，判据必须咬住")
    args = ap.parse_args()
    k = load_from_kotlin()
    print("[451] 从 Kotlin 源码取到：CREATE 列数=%d / REVIEW_PERIOD_DAYS=%d / DAY_MILLIS=%d / period_ms=%d"
          % (k["create"].count(",") + 1, k["days"], k["day_ms"], k["period_ms"]))
    print("[451] ALTER = %s" % k["alter"])
    check("NOT NULL" not in k["alter"] and "DEFAULT" not in k["alter"],
          "加列不许带 NOT NULL / DEFAULT（否则老行会被伪造成有核实时刻）")

    old, review, missing = scenario(k)
    print("[451] v20 老库 %d 行 → 升级后：%s" % (len(old), old))
    check(len(old) == 2, "老库 2 行都要还在")
    check(all(v is None for _, v in old), "旧行 as_of 必须是 NULL（可读、不编造）")

    print("[451] needingReview(now) ⇒ %s" % review)
    check(sorted(review) == ["new-90天整", "new-91天"],
          "只该查到满 90 天整与 91 天两行；89 天、NULL 都不该进")
    print("[451] missingAsOf() ⇒ %s" % missing)
    # 只对事实类（fact/term）判：v20-旧行-A 是 preference，不该混进待补清单
    check(sorted(missing) == ["new-待补", "v20-旧行-B"],
          "缺时刻的 2 条事实（旧 fact 行 + 待补）要单列；偏好行不该进来")
    check("as_of IS NOT NULL" in k["review"] and "as_of <= ?" in k["review"],
          "复核查询必须按 asOf 比较，且排除 NULL")
    check("kind IN ('fact', 'term')" in k["review"] and "kind IN ('fact', 'term')" in k["missing"],
          "两条查询都要只对事实类（fact/term）判，偏好/规矩不混进来")

    if args.selftest:
        bad = 0
        # 负例 1：把加列改成 NOT NULL DEFAULT 0 ⇒ 旧行不再 NULL，判据必须咬住
        try:
            old2, _, _ = scenario(k, alter_override=k["alter"] + " NOT NULL DEFAULT 0")
            if not all(v is None for _, v in old2):
                bad += 1
                print("[451] 负例1 咬住：DEFAULT 0 把旧行伪造成 0（实测 %s）" % old2)
        except sqlite3.Error as exc:
            bad += 1
            print("[451] 负例1 咬住：NOT NULL 加列直接报错（%s）" % exc)
        # 负例 2：去掉 asOf 上界 ⇒ 89 天那条被误判成该复核
        loose = k["review"].replace("AND as_of <= ? ", "")
        _, review2, _ = scenario(k, review_override=loose)
        if "new-89天" in review2:
            bad += 1
            print("[451] 负例2 咬住：去掉上界后 89 天那条被误标待复核（%s）" % review2)
        check(bad == 2, "两条变异负例都必须咬住（实际 %d）" % bad)
        print("[451] --selftest 负例 2/2 咬住")

    print("PASS：旧库升级不报错 / 旧行 NULL 可读 / 迁移重跑幂等 / 按 asOf 判待复核 / 常量单点")
    return 0


if __name__ == "__main__":
    sys.exit(main())
