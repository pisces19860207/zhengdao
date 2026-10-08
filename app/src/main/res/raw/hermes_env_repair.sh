#!/bin/sh
# 证道 · Hermes 依赖环境修复（ERRATA E-025）
#
# 由 App 在「首页体检 → Hermes 依赖环境 → 修复」或「设置 → Hermes 依赖环境 → 修复依赖环境」
# 时写入工作区 /workspace/.zhengdao/scripts/hermes-env-repair.sh 并在终端里执行，
# 输出全程可见（App 侧不静默改 Hermes 的东西）。
#
# 为什么需要它：
#   Hermes 把"当前依赖环境是哪一代"记在 installs/<hash>/facts.json 里。这个记录一旦指向
#   不存在的目录（搬家包带进来的是旧机的记录、或更新被中断），hermes 启动即报
#   "dependency environment is missing or outside this install" 并退出；而
#   `hermes update` 也救不了——非 pm 子命令在 bootstrap 阶段就退出。能用的只有 pm 系列。
#
# 修法与 2026-10-08 真机手工修复完全一致（当时就是这么修好的）。

set -u
H="${HOME:-/root}/.hermes"
echo "[证道] 修复 Hermes 依赖环境 …"

# ① 补回被中断的自我更新删掉的源码锁（uv.lock / flake.lock 都受 git 管理）
if [ -d "$H/hermes-agent/.git" ]; then
    cd "$H/hermes-agent" || exit 1
    for f in uv.lock flake.lock; do
        if [ ! -f "$f" ] && git checkout -- "$f" 2>/dev/null; then
            echo "[证道] 已恢复源码锁 $f"
        fi
    done
    cd / || true
fi

# ② 指向不存在环境的依赖环境记录：备份后删掉，让 pm repair 能重新登记
#    （记录还在时 pm repair 会以 "recorded dependency lock is missing" 拒绝重建）
PY=$(command -v python3 || true)
if [ -n "$PY" ]; then
    "$PY" - <<'PYEOF'
import glob, json, os, shutil

H = os.path.expanduser("~/.hermes")
for facts in sorted(glob.glob(os.path.join(H, "installs", "*", "facts.json"))):
    try:
        with open(facts) as fh:
            data = json.load(fh)
    except Exception as exc:
        print("[证道] 读不了 %s（%s），跳过" % (facts, exc))
        continue
    venv = ((data.get("packages") or {}).get("venv") or {})
    env = venv.get("environment")
    lock = venv.get("resolved_lock")
    bad = bool(env) and not os.path.isfile(os.path.join(env, "pyvenv.cfg"))
    if not bad and lock:
        bad = not os.path.isfile(lock)
    if bad:
        bak = facts + ".bak-修复"
        shutil.copy2(facts, bak)
        os.remove(facts)
        print("[证道] 删除失效的依赖环境记录（已备份 %s）" % bak)
PYEOF
else
    echo "[证道] 环境里没有 python3，跳过记录清理"
fi

# ③ 清掉更新/修复中断留下的陈旧标记
rm -f "$H"/installs/*/.recovery.lock "$H"/installs/*/.repair-incomplete \
      "$H"/.recovery.lock "$H"/.repair-incomplete "$H"/.hermes-update-in-progress.lock 2>/dev/null

# ④ 重建并登记依赖环境（会真的装 Python 依赖，耗时几分钟，输出很长，属正常）
export UV_LINK_MODE=copy
mkdir -p /root/tmp
export TMPDIR=/root/tmp
if hermes pm repair; then
    echo "[证道] 依赖环境已重建"
else
    echo "[证道] pm repair 失败——把上面的输出发我，别急着重装"
    exit 1
fi

# ⑤ 复验
if hermes --version; then
    echo "[证道] 修复完成：直接敲 hermes 即可使用"
else
    echo "[证道] 仍未通过：把 hermes --version 的输出发我"
    exit 1
fi
