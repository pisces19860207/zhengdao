#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Activity 注册防回归门禁（435，2026-11-04）：**代码里显式拉起的组件，必须在清单里注册。**

## 为什么要有这道闸

`79c998c9`（第四刀：终端整仓搬走）把 manifest 里一整段 activity 注册连注释一起删掉了，
后来补回了设置二级页，却漏了 `.ui.KeepaliveLogActivity` 与 `.ui.OpenSourceLicensesActivity`：

  * 两个页面的源文件一直都在，活代码也一直在拉起它们 ——
    `ui/SettingsKeepaliveActivity.kt` 的「保活记录」行、
    `ui/SettingsAboutActivity.kt` 的「开源许可」行，都是 `startActivity(Intent(ctx, X::class.java))`；
  * 后果是「设置→后台与保活→保活记录」「设置→关于→开源许可」点进去必
    `ActivityNotFoundException`；
  * 而**编译、单测、detekt、lint 全都不会红** —— 这是纯清单/源码之间的裂缝，
    只有把「引用 ↔ 注册」当成一条会红的断言才挡得住。

## 断言什么

1. **引用侧（派工单主诉求）**：源码里每个 `X::class.java` 类字面量，只要 X 是本仓声明的
   Activity，就必须在 `<activity>` 注册。这样 `startActivity(Intent(..., X::class.java))`、
   PendingIntent 的 `Intent(ctx, X::class.java)`、以及设置 Hub 的 `open(X::class.java)`
   （形参是 `Class<*>`，不是 `Intent` 字面量）三种写法一起兜住 —— 只盯 `Intent(...)` 会漏第三种。
2. **声明侧（防的就是"删注册"这个动作）**：本仓 `class X : ComponentActivity()` 声明的每个
   Activity 都必须在 `<activity>` 注册。删掉一条注册 ⇒ 这条立刻红，哪怕暂时没人引用它。
3. **非 Activity 组件**：白名单里的 `<receiver>` / `<service>` 目标不走 `<activity>` 规则，
   但**不是免检** —— 脚本仍要求它出现在对应节点里（见下方 ALLOWLIST）。
4. **白名单不许滥用**：每条必须有非空理由；不许把本仓 Activity 塞进白名单；
   白名单项自己也必须在 `<receiver>` / `<service>` / `<provider>` 里真的注册过。

不在扫描范围内的（`getSystemService(NotificationManager::class.java)` 这类框架类、
`private val LOCK = XxxStore::class.java` 这类纯 Class 对象）：它们既不是本仓声明的 Activity，
也不在白名单里，天然排除。**隐式 Intent（`Intent(ACTION_VIEW, uri)`）没有 `::class.java`
目标，同样天然不在范围内** —— 这两类不是"例外"，是规则本身不适用，无需白名单。

## 用法

    python3 tools/check-activity-registration.py                # 查真仓，红则 exit 1
    python3 tools/check-activity-registration.py --selftest     # 合成数据正例＋负例各一条
    python3 tools/check-activity-registration.py --manifest <变体.xml>   # 变异演练用

`--selftest` 全程用临时目录里的合成 manifest／合成 Kotlin，**不读真仓状态**（仓库账不平时
也不会把环境红当成代码红）。断言"闸门会拒"时盯的是具体条目行，不是汇总行或关键词。

CI：`.github/workflows/ci.yml` 的 verify job 在 `assembleDebug` 之前跑本脚本（静态扫描不依赖
产物，红了就不用白烧一次构建）。
"""

import argparse
import re
import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

try:  # Windows 控制台默认 GBK：个别字符编不出来时降级替换，而不是让门禁自己崩
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # noqa: BLE001 - reconfigure 是尽力而为
    pass

ANDROID_NS = "{http://schemas.android.com/apk/res/android}"

# 白名单：**不按 `<activity>` 规则检查、但改按对应组件节点复核**的显式启动目标。
# 每一条都必须写清"凭什么可以没有 <activity> 注册"，且脚本会验证它确实在别的节点注册过。
ALLOWLIST = {
    "ScheduleAlarmReceiver": (
        "BroadcastReceiver，目标注册在 <receiver> 而非 <activity>；"
        "schedule/ScheduleScheduler.kt 用 PendingIntent.getBroadcast 拉起它，不走 startActivity。"
        "脚本另核 <receiver> 静态注册（该组件必须跨进程重启仍存在）。"
    ),
    "UnattendedDutyService": (
        "Service，目标注册在 <service> 而非 <activity>；"
        "unattended/UnattendedDutyService.kt 的 startForegroundService / stopService 目标。"
        "脚本另核 <service> 静态注册。"
    ),
}

# 本仓 Activity 例外：**当前为空，且不允许有**。若将来真出现"本仓声明但故意不注册"的
# Activity（例如只被别的构建变体使用），必须在这里写明理由并同步改 CI，不许静默加名字。
DECLARED_EXEMPT = {}

# `class X : ComponentActivity() {` —— **只认同一行内的继承列表**。
# 踩过的坑（435 首跑）：若允许 `[^{]*?` 跨行，`data class PromptRequest(val ctx: ComponentActivity, …)`
# 里的参数类型冒号会被当成继承冒号，把 6 个普通 data class 误判成 Activity ⇒ 门禁假红。
# 本项目 8 个 Activity 声明全是单行 `class XxxActivity : ComponentActivity() {`。
CLASS_DECL = re.compile(
    r"(?m)^[ \t]*(?:(?:abstract|open|internal|sealed|data|private|protected|final)\s+)*"
    r"class\s+(\w+)\b[^\n{]*?:\s*([^\n{]+)\{"
)
# `X::class.java` / `a.b.X::class.java`
CLASS_REF = re.compile(r"\b([A-Za-z_][\w.]*)::class\.java\b")
ACTIVITY_BASE = re.compile(
    r"\b(?:ComponentActivity|AppCompatActivity|FragmentActivity|Activity)\b"
)


def simple_name(name):
    """`.ui.KeepaliveLogActivity` / 全限定名 → `KeepaliveLogActivity`。"""
    return name.lstrip(".").split(".")[-1]


def parse_manifest(path):
    """返回 (activities:set, others:dict[name -> nodeTag])。"""
    root = ET.fromstring(path.read_text(encoding="utf-8"))
    app = root.find("application")
    if app is None:
        raise ValueError("manifest 里没有 <application> 节点")
    activities = set()
    for tag in ("activity", "activity-alias"):
        for node in app.findall(tag):
            name = node.get(ANDROID_NS + "name")
            if name:
                activities.add(simple_name(name))
    others = {}
    for tag in ("service", "receiver", "provider"):
        for node in app.findall(tag):
            name = node.get(ANDROID_NS + "name")
            if name:
                others[simple_name(name)] = tag
    return activities, others


def scan_sources(src_root):
    """返回 (declared:dict[name -> (rel,line)], refs:list[(simple, rel, line, raw)])."""
    declared = {}
    refs = []
    for kt in sorted(src_root.rglob("*.kt")):
        text = kt.read_text(encoding="utf-8", errors="replace")
        lines = text.splitlines()
        try:
            rel = kt.relative_to(src_root).as_posix()
        except ValueError:
            rel = kt.name
        for m in CLASS_DECL.finditer(text):
            cls, base = m.group(1), m.group(2)
            # 双条件：类名以 Activity 结尾 **且** 基类确是 Activity 家族。
            # 只靠基类会把「参数类型含 ComponentActivity 的 data class」认进来。
            if cls.endswith("Activity") and ACTIVITY_BASE.search(base):
                line = text.count("\n", 0, m.start()) + 1
                declared[cls] = (rel, line)
        for i, line_text in enumerate(lines, 1):
            for m in CLASS_REF.finditer(line_text):
                full = m.group(1)
                refs.append((simple_name(full), full, rel, i, line_text.strip()))
    return declared, refs


def is_app_activity(simple, full, declared):
    """引用是否指向本 App 的 Activity。

    declared 命中优先；未命中时用「类名以 Activity 结尾且不是框架包」兜底 ——
    万一将来有人把类声明写成跨行形式（CLASS_DECL 只认单行），引用侧仍不会漏。
    """
    if simple in declared:
        return True
    if not simple.endswith("Activity"):
        return False
    return not full.startswith(("android.", "androidx.", "java.", "kotlin.", "com.android."))


def check(manifest_path, src_root):
    """核心断言。返回 violations:list[str]（判红只看这个列表，不看汇总文本）。"""
    activities, others = parse_manifest(manifest_path)
    declared, refs = scan_sources(src_root)
    violations = []

    # 0) 白名单自身的合法性：有理由、不是本仓 Activity、且在对应节点真的注册过。
    for name, reason in ALLOWLIST.items():
        if not reason or not reason.strip():
            violations.append("✗ 白名单项 %s 缺少理由（每条例外都必须写明凭什么）" % name)
        if name in declared:
            violations.append(
                "✗ 白名单项 %s 是本仓声明的 Activity（%s:%s），不允许用白名单豁免 <activity> 注册"
                % (name, *declared[name])
            )
        if name not in others:
            violations.append(
                "✗ 白名单项 %s 也未在 <service>/<receiver>/<provider> 注册（白名单不是免检）" % name
            )
    for name, reason in DECLARED_EXEMPT.items():
        if not reason or not reason.strip():
            violations.append("✗ 本仓 Activity 例外 %s 缺少理由" % name)
        if name not in declared:
            violations.append("✗ 本仓 Activity 例外 %s 已不存在（僵尸例外，请删掉）" % name)

    # 1) 引用侧：显式类字面量指向的本仓 Activity 必须注册。
    for simple, full, rel, line, raw in refs:
        if is_app_activity(simple, full, declared) and simple not in activities:
            violations.append(
                "✗ %s:%s  显式类引用 → %s 未在 <activity> 注册"
                "（startActivity 会抛 ActivityNotFoundException）｜ %s"
                % (rel, line, simple, raw)
            )

    # 2) 声明侧：本仓每个 Activity 都要有注册 —— 删注册就是在这里被抓。
    for name, (rel, line) in sorted(declared.items()):
        if name in DECLARED_EXEMPT:
            continue
        if name not in activities:
            violations.append(
                "✗ 本仓 Activity 未在 <activity> 注册: %s （声明于 %s:%s）" % (name, rel, line)
            )

    # 3) 非 Activity 组件引用：改按对应节点复核。
    for simple, full, rel, line, raw in refs:
        if simple in ALLOWLIST and simple not in others:
            violations.append(
                "✗ %s:%s  白名单目标 %s 未在 <%s> 注册｜ %s"
                % (rel, line, simple, others.get(simple, "service/receiver/provider"), raw)
            )

    stats = {
        "registered_activities": len(activities),
        "declared_activities": len(declared),
        "other_components": len(others),
        "class_refs": len(refs),
        "allowlist": len(ALLOWLIST),
    }
    return violations, stats


def run(repo, manifest, src):
    """跑一次真仓检查，返回 (exit_code, output_lines)。"""
    lines = []
    try:
        violations, stats = check(manifest, src)
    except (OSError, ValueError, ET.ParseError) as exc:
        return 2, ["✗ 门禁自身无法完成检查: %s: %s" % (type(exc).__name__, exc)]
    lines.append("manifest: %s" % manifest)
    lines.append("src     : %s" % src)
    lines.append(
        "读数: <activity> %d 条 / 本仓 Activity 声明 %d 个 / 其他组件 %d 个 / "
        "::class.java 引用 %d 处 / 白名单 %d 项"
        % (
            stats["registered_activities"], stats["declared_activities"],
            stats["other_components"], stats["class_refs"], stats["allowlist"],
        )
    )
    if violations:
        lines.extend("  " + v for v in violations)
        lines.append("结果: FAIL（%d 条违规）" % len(violations))
        return 1, lines
    lines.append("结果: PASS（所有显式启动目标与声明的 Activity 均已注册）")
    return 0, lines


SYNTH_MANIFEST = """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application android:label="synth">
        <activity android:name=".MainActivity" android:exported="true" />
        <activity android:name=".ui.SettingsKeepaliveActivity" android:exported="false" />
        <activity android:name=".ui.KeepaliveLogActivity" android:exported="false"
            android:label="保活记录" />
        <activity android:name=".ui.OpenSourceLicensesActivity" android:exported="false"
            android:label="开源许可" />
        <receiver android:name=".schedule.ScheduleAlarmReceiver" android:exported="false" />
        <service android:name=".unattended.UnattendedDutyService" android:exported="false" />
    </application>
</manifest>
"""

SYNTH_KT = """package com.example.zhengdao.ui

import android.content.Intent
import androidx.activity.ComponentActivity

// 合成类体必须带 `{}`：本仓的真实 Activity 都有类体，CLASS_DECL 按 `...{` 收口。
class KeepaliveLogActivity : ComponentActivity() {
}
class OpenSourceLicensesActivity : ComponentActivity() {
}

class SettingsKeepaliveActivity : ComponentActivity() {
    fun onClick(ctx: android.content.Context) {
        ctx.startActivity(Intent(ctx, KeepaliveLogActivity::class.java))
        ctx.startActivity(Intent(ctx, OpenSourceLicensesActivity::class.java))
        ctx.sendBroadcast(Intent(ctx, ScheduleAlarmReceiver::class.java))
        ctx.startForegroundService(Intent(ctx, UnattendedDutyService::class.java))
    }
}
"""


def selftest():
    """合成数据正例＋负例各一条；不读真仓。返回 exit code。"""
    results = []
    with tempfile.TemporaryDirectory(prefix="zd435-selftest-") as td:
        root = Path(td)
        mf = root / "app/src/main/AndroidManifest.xml"
        src = root / "app/src/main/java/com/example/zhengdao/ui"
        mf.parent.mkdir(parents=True)
        src.mkdir(parents=True)
        (src / "Synth.kt").write_text(SYNTH_KT, encoding="utf-8")

        # 正例：三条注册齐全 ⇒ 必须绿
        mf.write_text(SYNTH_MANIFEST, encoding="utf-8")
        code, out = run(root, mf, root / "app/src/main/java")
        ok_pos = code == 0
        results.append((ok_pos, "[正例] 合成仓库注册齐全 → 期望绿(0)，实得 %d" % code, out))

        # 负例：临时删掉 .ui.KeepaliveLogActivity 那条注册 ⇒ 必须红，且红在**具体条目行**
        mutated = SYNTH_MANIFEST.replace(
            '        <activity android:name=".ui.KeepaliveLogActivity" android:exported="false"\n'
            '            android:label="保活记录" />\n', ""
        )
        if mutated == SYNTH_MANIFEST:
            print("✗ 自测负例构造失败：没删掉目标注册，演练无效")
            return 1
        mf.write_text(mutated, encoding="utf-8")
        code, out = run(root, mf, root / "app/src/main/java")
        hit = [
            line for line in out
            if line.strip().startswith("✗") and "KeepaliveLogActivity" in line
            and "未在 <activity> 注册" in line
        ]
        ok_neg = code != 0 and len(hit) >= 1
        results.append((
            ok_neg,
            "[负例] 删掉 .ui.KeepaliveLogActivity 注册 → 期望红(非0)且命中具体条目，"
            "实得 exit=%d / 命中 %d 条" % (code, len(hit)),
            out,
        ))

    ok = all(r[0] for r in results)
    print("=== --selftest（全程合成数据，不读真仓） ===")
    for good, headline, out in results:
        print(("  [OK] " if good else "  [FAIL] ") + headline)
        for line in out:
            if line.strip().startswith("✗") or line.startswith("结果:"):
                print("      " + line)
    print("自测结论:", "PASS 正例绿、负例红" if ok else "FAIL")
    return 0 if ok else 1


def main():
    default_repo = Path(__file__).resolve().parent.parent
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--repo", type=Path, default=default_repo, help="仓库根（默认脚本上级的上级）")
    ap.add_argument("--manifest", type=Path, help="覆盖 manifest 路径（变异演练用）")
    ap.add_argument("--src", type=Path, help="覆盖 Kotlin 源码根")
    ap.add_argument("--selftest", action="store_true", help="合成数据正例＋负例各一条")
    args = ap.parse_args()

    if args.selftest:
        return selftest()
    repo = args.repo.resolve()
    manifest = (args.manifest or repo / "app/src/main/AndroidManifest.xml").resolve()
    src = (args.src or repo / "app/src/main/java").resolve()
    code, lines = run(repo, manifest, src)
    for line in lines:
        print(line)
    return code


if __name__ == "__main__":
    sys.exit(main())
