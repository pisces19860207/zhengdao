#!/usr/bin/env bash
# 684 · 「三连失败」一键复现脚本（真机 / 模拟器）
#
# 它做什么（全部经 adb，不需要 root、不改用户数据）：
#   1. 在 debug 包里写注入配置 `files/baopu/fault-inject.txt`（`read_file` 前 3 次必失败，码=protocol）；
#   2. 把聊天页拉起来（生产收尾钩子挂在它身上——约束卡就是它出的）；
#   3. 拉起 debug 探针 `FaultInjectProbeActivity`：探针用**真引擎**发 3 轮消息，
#      失败由注入器在 GuardPipeline 第 ④ 段冒充，`run_log` 那几行是引擎自己写的；
#   4. 等探针出结论（logcat tag=FaultInjectProbe），把读数与结果文件原文打出来；
#   5. 收尾：删掉注入配置（除非 --keep）——删掉即"注入关闭"，行为立刻回到原样。
#
# 判据（脚本会逐条判，红就 exit 1）：
#   · run_log 里 ≥3 条 `ok=0` 且 `err` 非空；
#   · 归因全为「行为类」；
#   · 候选档里 1 条 TOOL_RULE 约束卡（target=工具名）。
#   （判据③「release 不含入口」不归本脚本管：见 tools/check-fault-inject-absent.py。）
#
# 用法：
#   tools/fault-inject-3x.sh                      # 自动选唯一设备
#   tools/fault-inject-3x.sh -s emulator-5554     # 指定设备
#   tools/fault-inject-3x.sh -t list_dir -r 3     # 换工具/轮数
#   tools/fault-inject-3x.sh --keep               # 跑完不删配置（方便再跑一遍看差异）
#
# 前置：debug 包已安装（`./gradlew :app:installDebug`），且 App 里配好了模型通道
#       ——探针发的是**真消息**，没有可用通道时会停在"第 1 轮没被引擎收下"或直接失败。
set -u

PKG="${ZD_PKG:-com.example.zhengdao}"
TOOL="read_file"
ROUNDS=3
SERIAL=""
KEEP=0
TIMEOUT_SEC=600

usage() { sed -n '2,30p' "$0" | sed 's/^# \{0,1\}//'; }

while [ $# -gt 0 ]; do
  case "$1" in
    -s|--serial) SERIAL="$2"; shift 2 ;;
    -t|--tool)   TOOL="$2"; shift 2 ;;
    -r|--rounds) ROUNDS="$2"; shift 2 ;;
    --keep)      KEEP=1; shift ;;
    --timeout)   TIMEOUT_SEC="$2"; shift 2 ;;
    -h|--help)   usage; exit 0 ;;
    *) echo "未知参数：$1" >&2; usage; exit 2 ;;
  esac
done

# ── adb 解析（本机 adb 不在 PATH 里，见 _zd-toolroom/02-build/device-lock.ps1 的同一条注释）──
ADB="${ADB:-}"
if [ -z "$ADB" ]; then
  if command -v adb >/dev/null 2>&1; then
    ADB="adb"
  else
    for cand in \
      "${ANDROID_HOME:-}/platform-tools/adb" \
      "${ANDROID_SDK_ROOT:-}/platform-tools/adb" \
      "$HOME/AppData/Local/Android/Sdk/platform-tools/adb.exe" \
      "/c/Users/$USER/AppData/Local/Android/Sdk/platform-tools/adb.exe"; do
      if [ -n "$cand" ] && [ -x "$cand" ]; then ADB="$cand"; break; fi
    done
  fi
fi
if [ -z "$ADB" ]; then echo "✗ 找不到 adb（设 ADB=/path/to/adb 或装 platform-tools）" >&2; exit 2; fi

if [ -z "$SERIAL" ]; then
  SERIAL="$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1; exit}')"
fi
if [ -z "$SERIAL" ]; then echo "✗ 没有在线设备（adb devices 看一眼）" >&2; exit 2; fi
ADB="$ADB -s $SERIAL"

echo "== 设备 $SERIAL =="
if ! $ADB shell pm path "$PKG" >/dev/null 2>&1; then
  echo "✗ 没装 $PKG（先 ./gradlew :app:installDebug）" >&2
  exit 2
fi
DEBUGGABLE="$($ADB shell dumpsys package "$PKG" | grep -c 'flags=\[.*DEBUGGABLE' || true)"
if [ "${DEBUGGABLE:-0}" -eq 0 ]; then
  echo "⚠ 这个包不像 debuggable（run-as 可能用不了）；装的是 debug 包吗？"
fi

# ── 1. 写注入配置 ────────────────────────────────────────────────────────────
# 格式：tool | nth | limit | code | argsRegex（nth=0 每次命中，limit=3 前三次都失败）
CONF_HDR='# 684 fault injection (debug only) — 删掉本文件即关闭'
LINE="$TOOL | 0 | 3 | protocol |"
echo "== 写注入配置：$LINE =="
$ADB shell "run-as $PKG sh -c 'mkdir -p files/baopu; printf \"%s\n%s\n\" \"$CONF_HDR\" \"$LINE\" > files/baopu/fault-inject.txt'" \
  || { echo "✗ 写配置失败（run-as 被拒？装的是 release 包？）" >&2; exit 2; }
echo "---- 配置原文 ----"
$ADB shell "run-as $PKG cat files/baopu/fault-inject.txt"
echo "------------------"

cleanup() {
  if [ "$KEEP" -eq 0 ]; then
    $ADB shell "run-as $PKG rm -f files/baopu/fault-inject.txt" >/dev/null 2>&1 || true
    echo "== 已删注入配置（注入关闭）=="
  else
    echo "== --keep：配置留着（下次跑之前记得删，否则计数接着数）=="
  fi
  echo "（探针在工作区 \`临时/故障注入-验收.txt\` 留了一个一行小文件，可随手删）"
}
trap cleanup EXIT

# ── 2/3. 拉起聊天页（生产收尾钩子）＋探针 ───────────────────────────────────
$ADB logcat -c >/dev/null 2>&1 || true
echo "== 重启 App（force-stop ⇒ 聊天页开机会取 latestSessionId，探针用同一个会话）=="
$ADB shell am force-stop "$PKG" >/dev/null 2>&1 || true
$ADB shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1 || true
sleep 8
echo "== 拉起探针（真引擎，$ROUNDS 轮）=="
$ADB shell am start -n "$PKG/com.example.zhengdao.debug.FaultInjectProbeActivity" \
  --es tool "$TOOL" --ei rounds "$ROUNDS" >/dev/null || {
    echo "✗ 拉不起探针（debug 包才有的组件）" >&2; exit 2; }

# ── 4. 等结论 ───────────────────────────────────────────────────────────────
echo "== 等探针出结论（最多 ${TIMEOUT_SEC}s；每轮一次模型往返）=="
deadline=$(( $(date +%s) + TIMEOUT_SEC ))
sum=""
while [ "$(date +%s)" -lt "$deadline" ]; do
  sum="$($ADB logcat -d -s FaultInjectProbe:V 2>/dev/null | grep 'ROW|SUM|' | tail -1)"
  if [ -n "$sum" ]; then break; fi
  sleep 5
done

echo
echo "==== 探针读数（logcat）===="
$ADB logcat -d -s FaultInjectProbe:V 2>/dev/null | grep 'ROW|' | sed 's/.*ROW|/ROW|/'
echo
echo "==== 结果文件（内部 filesDir 原文）===="
$ADB shell "run-as $PKG cat files/fault-probe/result.txt" 2>/dev/null || echo "(结果文件还没写出来)"
echo

if [ -z "$sum" ]; then
  echo "✗ 超时：探针没在 ${TIMEOUT_SEC}s 内出结论（设备慢就 --timeout 1200；或通道没配好）" >&2
  exit 1
fi

# ── 5. 判据 ─────────────────────────────────────────────────────────────────
# SUM 行形如 `ROW|SUM|总结|PASS|PASS 三连失败可复现：…`（第 4 段是判定，第 5 段是细节）。
mark="$(echo "$sum" | awk -F'|' '{print $4}')"
detail="$(echo "$sum" | cut -d'|' -f5-)"
echo "==== 判据 ===="
if [ "$mark" = "PASS" ]; then
  echo "  ✓ 三连失败 / 归因行为类 / 约束卡 1 张：$detail"
  echo "== 结论：PASS =="
  exit 0
else
  echo "  ✗ 探针判定 $mark：$detail"
  echo "== 结论：FAIL（看上面 ROW|…|FAIL 那几行与探针读数）=="
  exit 1
fi
