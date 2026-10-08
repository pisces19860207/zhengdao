#!/usr/bin/env bash
# =====================================================================
# 证道（Zhengdao）—— Debian 13.7 (trixie) RootFS 构建脚本
# 独立开发声明：本脚本为本项目从零编写，未参考任何第三方同类项目的构建脚本。
# 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
# （参考资料仅为官方工具文档：debootstrap(8)、deb.nodesource.com 官方安装说明、
#   GitHub releases（astral-sh/uv）、zstd/tar 官方文档。）
#
# 产物：debian-13.7-base-arm64.tar.zst + 同名 .sha256
#       （App 端按 manifest 下载、解压、校验后写 .zhengdao-rootfs-ok 标记，
#        ProotLauncher 检测到该标记即经 proot 启动本环境的 /bin/bash）
#
# 用法（需要 root；在 Debian 12+ / Ubuntu 22.04+ 构建机上执行）：
#   sudo bash build-rootfs.sh [输出目录，默认 ./out]
#
# 构建机要求：
#   - arm64 构建机：单阶段直接构建
#   - x86_64 构建机：自动走 qemu-user-static 两阶段交叉构建
#   - 磁盘可用空间 ≥ 5GB；能访问 deb.debian.org / deb.nodesource.com / github.com
#   - 本脚本为 POSIX 换行符（LF）；若从 Windows 传输后执行报错，先执行
#     sed -i 's/\r$//' build-rootfs.sh
#
# 依赖锁定（设计文档 §6 v3.3，用户定案）：
#   glibc   = Debian 13.7 自带（2.41），只读不升
#   Python  = Debian 13.7 自带（3.13），直接使用
#   Node.js = NodeSource 官方源 26.x（不用 Debian 源旧版）
# =====================================================================
set -euo pipefail

# ---------------------------------------------------------------------
# 失败自述（2026-10-08，见 docs/ERRATA.md E-039）：
#   GitHub 的 Actions 日志对未登录用户是「Sign in to view logs」，构建失败时外面
#   只看到注解里一句「Process completed with exit code 2」，等于没有信息（Run 162
#   就卡在这一步：RootFS 构建失败，谁都不知道死在哪一行）。
#   所以失败点自己发一条 `::error::` 注解 —— **注解是匿名可见的**，把"哪个小节、
#   哪一行、哪条命令、退出码多少"钉死；§2.8 里每条外部命令还额外附带它自己的 stderr。
# ---------------------------------------------------------------------
annot() { printf '::error::%s\n' "$(printf '%s' "$1" | tr '\n' '|' | cut -c1-1500)"; }
trap 'rc=$?; annot "build-rootfs.sh 失败：[$STEP_OUTER] 第 $LINENO 行 \`$BASH_COMMAND\`，退出码 $rc"' ERR
STEP_OUTER="构建机自检"

DEBIAN_RELEASE="trixie"
DEBIAN_VERSION="13.7"
NODE_MAJOR="26"
ARCH="arm64"
MIRROR="http://deb.debian.org/debian"
OUT_DIR="${1:-$(pwd)/out}"

# ---------- 构建机自检 ----------
if [ "$(id -u)" -ne 0 ]; then
  annot "[错误] 请用 root 运行（debootstrap 与 mount 需要 root 权限）"
  exit 1
fi
for tool in debootstrap zstd tar curl sha256sum; do
  command -v "$tool" >/dev/null 2>&1 || {
    annot "[错误] 构建机缺少 $tool，请先安装（Debian/Ubuntu: apt install ${tool}）"
    exit 1
  }
done
HOST_ARCH="$(uname -m)"
if [ "$HOST_ARCH" != "aarch64" ] && [ "$HOST_ARCH" != "x86_64" ]; then
  annot "[错误] 不支持的构建机架构: $HOST_ARCH（需要 arm64 或 x86_64）"
  exit 1
fi
if [ "$HOST_ARCH" = "x86_64" ]; then
  command -v qemu-aarch64-static >/dev/null 2>&1 || {
    annot "[错误] x86_64 构建机需要 qemu-user-static（apt install qemu-user-static）"
    exit 1
  }
fi

ROOTFS_DIR="$(mktemp -d /tmp/zhengdao-rootfs.XXXXXX)"
MNT_LIST=()
cleanup() {
  for m in "${MNT_LIST[@]:-}"; do
    if [ -n "$m" ]; then umount -l "$m" 2>/dev/null || true; fi
  done
  rm -rf "$ROOTFS_DIR"
}
trap cleanup EXIT

STEP_OUTER="[1/4] debootstrap"
echo "[1/4] debootstrap 引导最小 Debian $DEBIAN_VERSION ($ARCH) ..."
if [ "$HOST_ARCH" = "x86_64" ]; then
  # 交叉构建：第一阶段只解包，第二阶段在 qemu 里于 chroot 内完成
  debootstrap --arch="$ARCH" --variant=minbase --foreign \
    --include=ca-certificates "$DEBIAN_RELEASE" "$ROOTFS_DIR" "$MIRROR"
  cp /usr/bin/qemu-aarch64-static "$ROOTFS_DIR/usr/bin/"
  chroot "$ROOTFS_DIR" /debootstrap/debootstrap --second-stage
else
  debootstrap --arch="$ARCH" --variant=minbase \
    --include=ca-certificates "$DEBIAN_RELEASE" "$ROOTFS_DIR" "$MIRROR"
fi

STEP_OUTER="[2/4] 挂载与写入 chroot 脚本"
echo "[2/4] 挂载虚拟文件系统并写入 chroot 配置脚本 ..."
# chroot 内联网必需：先落 DNS（App 端每次启动前还会再确保一次，见 ProotLauncher）
printf 'nameserver 1.1.1.1\nnameserver 8.8.8.8\n' > "$ROOTFS_DIR/etc/resolv.conf"
mount --bind /proc "$ROOTFS_DIR/proc"; MNT_LIST+=("$ROOTFS_DIR/proc")
mount --bind /sys  "$ROOTFS_DIR/sys";  MNT_LIST+=("$ROOTFS_DIR/sys")
mount --bind /dev  "$ROOTFS_DIR/dev";  MNT_LIST+=("$ROOTFS_DIR/dev")

cat > "$ROOTFS_DIR/zhengdao-configure.sh" <<'CONF'
#!/bin/bash
# chroot 内配置脚本（由 build-rootfs.sh 写入并执行；NODE_MAJOR 经 env 传入）
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
# 失败自述（与 build-rootfs.sh 顶部同一套，见 docs/ERRATA.md E-039）：
# chroot 脚本的输出在 Actions 页面上匿名不可见，所以失败时自己发 `::error::` 注解，
# 带上当前小节（STEP）、行号、命令与退出码。
annot() { printf '::error::%s\n' "$(printf '%s' "$1" | tr '\n' '|' | cut -c1-1500)"; }
trap 'rc=$?; annot "RootFS 配置脚本失败：[$STEP] 第 $LINENO 行 \`$BASH_COMMAND\`，退出码 $rc"' ERR
STEP="2.1 容器通病"

echo "---- 2.1 修复容器通病（实测坑 #2）----"
mkdir -p /var/log/apt /var/log/dpkg
touch /var/log/apt/history.log /var/log/apt/term.log

STEP="2.2 基础依赖"
echo "---- 2.2 基础依赖（git / tmux / libatomic1 / busybox 等）----"
apt-get update
apt-get install -y --no-install-recommends \
  git curl wget ca-certificates gnupg \
  tmux procps busybox ripgrep libatomic1 ffmpeg sqlite3 \
  locales bash-completion less xz-utils zstd sudo \
  tzdata

STEP="2.3 locale"
echo "---- 2.3 locale：确认 C.UTF-8 可用 ----"
locale -a 2>/dev/null | grep -qi '^C\.utf8' || {
  sed -i 's/^# *\(C\.UTF-8.*\)/\1/' /etc/locale.gen
  locale-gen
}

STEP="2.4 Node.js"
echo "---- 2.4 Node.js ${NODE_MAJOR}：NodeSource 官方源（设计文档 §6，不用 Debian 源旧版）----"
curl -fsSL "https://deb.nodesource.com/setup_${NODE_MAJOR}.x" | bash -
apt-get install -y nodejs

STEP="2.5 uv"
echo "---- 2.5 uv：官方发行包，仅作 pip 安装器（设计文档 §6）----"
case "$(uname -m)" in
  aarch64) UV_TARGET="aarch64-unknown-linux-gnu" ;;
  x86_64)  UV_TARGET="x86_64-unknown-linux-gnu"  ;;
  *) annot "[错误] 未知架构: $(uname -m)"; exit 1 ;;
esac
curl -fsSL "https://github.com/astral-sh/uv/releases/latest/download/uv-${UV_TARGET}.tar.gz" \
  | tar -xz -C /tmp
install -m 0755 "/tmp/uv-${UV_TARGET}/uv"  /usr/local/bin/uv
install -m 0755 "/tmp/uv-${UV_TARGET}/uvx" /usr/local/bin/uvx
rm -rf "/tmp/uv-${UV_TARGET}"

STEP="2.6 UV_LINK_MODE"
echo "---- 2.6 防硬链接报错：UV_LINK_MODE=copy 全局生效（实测坑 #4）----"
printf 'export UV_LINK_MODE=copy\n' > /etc/profile.d/zhengdao-uv.sh
chmod 0644 /etc/profile.d/zhengdao-uv.sh
grep -q 'UV_LINK_MODE' /etc/environment || printf 'UV_LINK_MODE=copy\n' >> /etc/environment

# uv 系统级配置：Android/proot 无硬链接（SELinux 拒绝 + bind 边界）。用户级
# uv.toml 会被 hermes 的 XDG 重定向绕过，/etc/uv/uv.toml 是系统级发现路径
#（App 端 ProotLauncher 对已装环境做同样的启动时补写）
mkdir -p /etc/uv
printf '# zhengdao: proot has no working hardlinks\nlink-mode = "copy"\n' > /etc/uv/uv.toml
chmod 0644 /etc/uv/uv.toml

STEP="2.7 时区/DNS/hosts"
echo "---- 2.7 时区、DNS 与 hosts 兜底 ----"
# 时区 = 北京时间（用户反馈：tmux 状态栏时钟慢 8 小时 = 镜像默认 UTC）。
# App 端 ProotLauncher 也有同样的启动时校准（老镜像用户升级 App 即生效，无需重装环境）
ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime
echo Asia/Shanghai > /etc/timezone

# DNS 锁定（v1.2 网络优化 P0）：proot 内没有 systemd-resolved，guest 里所有解析都直接读
# /etc/resolv.conf。内容与 App 端 EnvSelfHeal 的运行时自愈保持**同一份**（options + 四路
# nameserver，国内源在前）——否则镜像里一份、运行时另一份，会互相打架。
#   options timeout:1 attempts:3 rotate —— 移动网络丢包时 1 秒换源，替代默认 5 秒死等
[ -s /etc/resolv.conf ] || cat > /etc/resolv.conf <<'RESOLVEOF'
options timeout:1 attempts:3 rotate
nameserver 223.5.5.5
nameserver 119.29.29.29
nameserver 1.1.1.1
nameserver 8.8.8.8
RESOLVEOF
chmod 0444 /etc/resolv.conf
# ⚠️ 0444 只是"防误改"的姿态：文件在 App 私有目录里，属主就是 App 自己；
#    App 端 EnvSelfHeal 写之前会临时放开权限、写完再锁回（见 EnvSelfHeal.ensureDnsFiles），
#    否则"锁定"会把自己锁死、DNS 再也自愈不了。

# hosts：仅遥测屏蔽（P1）。这四条都是 0.0.0.0，不依赖任何外部 IP，无轮换风险。
#
# ⚠️ 这里**曾经**钉过 `172.65.90.21 opencode.ai`（P0，规避 IPv6 黑洞下的长超时），
#    2026-10-07 **已撤除**，不要再往回加。撤除理由（真机实测）：
#    1. 收益 ≈ 0：太极 serve 跑在宿主 bionic，不读 rootfs 的 /etc/hosts ⇒ 对主产品完全无效；
#       终端 opencode 已卸载；guest 内即便有进程也只省首次请求 8–25 ms，连接复用后归零。
#       端到端由 TLS + 跨境 RTT（~1.7 s）主导，DNS 只占 ~1%。
#    2. 风险是硬故障：Cloudflare 前置、4 个轮换 IP（172.65.90.20–.23），轮换即连不上，
#       不可控不可自愈。用零收益换硬故障风险不划算。
#    详见故障排查手册 坑 #0（先确认作用域）与 坑 #4（该条目的完整始末）。
cat > /etc/hosts <<'HOSTSEOF'
127.0.0.1 localhost
::1 localhost ip6-localhost ip6-loopback
0.0.0.0 statsig.anthropic.com
0.0.0.0 statsig.com
0.0.0.0 telemetry.opencode.ai
0.0.0.0 telemetry.anthropic.com
HOSTSEOF

STEP="2.8 剔除 GPU 栈"
echo "---- 2.8 剔除 GPU 软件渲染栈（用户拍板「终端确实没用就删」2026-10-08）----"
# 依据（真机只读取证，脚本与原始输出见 docs/ERRATA.md E-038）：
#   1) 真机 `ldd /usr/bin/ffmpeg` 的 NEEDED 闭包里**没有** libgallium / libLLVM：它们只是
#      apt 声明上的依赖（libgbm1 → mesa-libgallium → libllvm19），不是加载期依赖；
#   2) proot 里没有 /dev/dri、没有 X/Wayland display ⇒ mesa 的驱动后端没有任何被拉起的入口，
#      ffplay（SDL2/GBM 输出路径）本来就不可能用；ffmpeg/ffprobe 走纯 CPU 编解码；
#   3) 全仓 app/ 对 libgallium|mesa|libgbm|SDL2|vulkan 零命中，客户端不碰这三样。
# 于是卸掉 mesa-libgallium(装 34MB) + libllvm19(装 118MB) + libglx-mesa0 + libgl1-mesa-dri。
# ⚠️ 但 libgbm1 必须留：ffmpeg/ffprobe 二进制 NEEDED libgbm.so.1（经 libsdl2 的 GBM 路径）。
#    它声明了 `Depends: mesa-libgallium (= 版本)`，不摘掉这条，apt 就会顺着
#    ffmpeg → libsdl2 → libgbm1 → mesa-libgallium → libllvm19 把 ffmpeg 整串带走
#    （真机反向依赖扫描：mesa-libgallium 的父包只有 libgbm1 与 libglx-mesa0）。
#    真实依赖是运行时 dlopen、不是 NEEDED ⇒ 摘掉声明是安全的。
# ⚠️ 也**不跑** `apt-get autoremove`：ffmpeg 链接的 libGL.so.1（libgl1）并没有被任何包
#    声明成依赖，autoremove 会把它当垃圾清掉、ffmpeg 随即起不来（§2.9 的 ldd 断言就是抓这个）。
echo "[剔GPU] 重打包 libgbm1：摘掉它对 mesa-libgallium 的声明依赖"
GPU_TMPDIR="$(mktemp -d)"
# 每一条外部命令都单独抓输出：失败时把它的 stderr 直接塞进 `::error::` 注解
# （注解匿名可见，见文件头；Run 162 就是死在这一段但外面只能看到 exit code 2）。
if ! GBM_DL_OUT="$( ( cd "$GPU_TMPDIR" && apt-get download libgbm1 ) 2>&1 )"; then
  annot "[2.8] apt-get download libgbm1 失败：$GBM_DL_OUT"; exit 1
fi
GBM_DEB="$(ls "$GPU_TMPDIR"/libgbm1_*.deb 2>/dev/null | head -n1 || true)"
if [ -z "$GBM_DEB" ]; then
  annot "[2.8] 取不到 libgbm1 的 .deb（apt-get download 输出：$GBM_DL_OUT），不敢盲删 mesa"; exit 1
fi
if ! GBM_RX_OUT="$(dpkg-deb -R "$GBM_DEB" "$GPU_TMPDIR/gbm" 2>&1)"; then
  annot "[2.8] dpkg-deb -R $GBM_DEB 失败：$GBM_RX_OUT"; exit 1
fi
# 摘依赖：把 `mesa-libgallium (= 版本)` 这一条连同它前面的分隔逗号一起删掉。
# ⚠️ 2026-10-08 的坑（Run 162 死在 exit 2、Run 163 的自述注解把它原样带回来）：
#    原写法 `s/, *mesa-libgallium[^,)]*//g` 的字符类里带了 `)`，而版本约束自己就含括号
#    （`(= 25.0.7-2+deb13u1)`）⇒ 只吃到右括号**之前**，把那个孤零零的 `)` 留在原地，
#    `dpkg-deb -b` 随即报
#    `'Depends' field, syntax error after reference to package 'libwayland-server0'`。
#    现在改成「以逗号为界吃掉整条版本约束」，再逐项收尾：空项、尾逗号、行首逗号。
sed -i -E \
  -e 's/(,[[:space:]]*)?mesa-libgallium[^,]*//g' \
  -e 's/,[[:space:]]*,/,/g' \
  -e 's/,[[:space:]]*$//' \
  -e 's/:[[:space:]]*,[[:space:]]*/: /' \
  -e 's/[[:space:]]+$//' \
  -e '/^(Depends|Pre-Depends|Recommends|Suggests|Breaks|Conflicts|Provides|Replaces|Enhances):[[:space:]]*$/d' \
  "$GPU_TMPDIR/gbm/DEBIAN/control"
if grep -q 'mesa-libgallium' "$GPU_TMPDIR/gbm/DEBIAN/control"; then
  annot "[2.8] 重打包后 libgbm1 的 control 里仍残留 mesa-libgallium"; exit 1
fi
# 自己先看一眼依赖字段的语法（`dpkg-deb -b` 也会拦，但这样报错更直白、也不用等它跑完）：
if grep -nE '^(Depends|Pre-Depends|Recommends):' "$GPU_TMPDIR/gbm/DEBIAN/control" \
   | grep -qE ',[[:space:]]*,|,[[:space:]]*$|:[[:space:]]*,|\([[:space:]]*\)'; then
  annot "[2.8] 摘掉 mesa 依赖后 control 的依赖字段语法有问题：$(grep -nE '^(Depends|Pre-Depends|Recommends):' "$GPU_TMPDIR/gbm/DEBIAN/control" | tr '\n' '|')"; exit 1
fi
if ! GBM_B_OUT="$(dpkg-deb -b "$GPU_TMPDIR/gbm" "$GPU_TMPDIR/libgbm1-local.deb" 2>&1)"; then
  annot "[2.8] dpkg-deb -b 重打包 libgbm1 失败：$GBM_B_OUT"; exit 1
fi
if ! GBM_I_OUT="$(dpkg -i "$GPU_TMPDIR/libgbm1-local.deb" 2>&1)"; then
  annot "[2.8] dpkg -i 重打包后的 libgbm1 失败：$GBM_I_OUT"; exit 1
fi
rm -rf "$GPU_TMPDIR"
echo "[剔GPU] 先模拟卸载，确认不会连带删掉关键包"
if ! GPU_SIM="$(apt-get -s -y purge mesa-libgallium libllvm19 libglx-mesa0 libgl1-mesa-dri 2>&1)"; then
  annot "[2.8] apt 模拟卸载直接失败：$GPU_SIM"; exit 1
fi
echo "$GPU_SIM" | grep -E '^(Remv|Purg) ' | head -n 20 || true
if echo "$GPU_SIM" | grep -E '^(Remv|Purg) (ffmpeg|ffprobe|libavdevice61|libsdl2-2\.0-0|libgbm1|libplacebo349|libvulkan1|libgl1|libglx0|libglvnd0|nodejs|python3|git|tmux|busybox|ripgrep|coreutils)(:arm64)? '; then
  annot "[2.8] 卸载 mesa 会连带移除关键包，已中止：$(echo "$GPU_SIM" | grep -E '^(Remv|Purg) ' | head -n 20)"; exit 1
fi
if ! GPU_PURGE_OUT="$(apt-get -y purge mesa-libgallium libllvm19 libglx-mesa0 libgl1-mesa-dri 2>&1)"; then
  annot "[2.8] apt-get purge 真的卸载时失败：$GPU_PURGE_OUT"; exit 1
fi
echo "[剔GPU] 剔除后落盘体积: $(du -smx / 2>/dev/null | awk '{print $1}')MB"

STEP="2.9 版本断言"
echo "---- 2.9 版本断言（构建即验收，漂移即失败）----"
GLIBC_VER="$(ldd --version | head -n1 | awk '{print $NF}')"
PY_VER="$(python3 --version | awk '{print $2}')"
NODE_VER="$(node --version)"
UV_VER="$(uv --version | awk '{print $2}')"
echo "[zhengdao] glibc=${GLIBC_VER} python=${PY_VER} node=${NODE_VER} uv=${UV_VER}"
case "$GLIBC_VER" in
  2.4[1-9]|2.[5-9]*) : ;;
  *) annot "[断言失败] glibc=${GLIBC_VER}，期望 2.41+（Debian 13.7 自带，不手动升级）"; exit 1 ;;
esac
case "$PY_VER" in
  3.13*) : ;;
  *) annot "[断言失败] python=${PY_VER}，期望 3.13.x（Debian 13.7 自带）"; exit 1 ;;
esac
case "$NODE_VER" in
  v${NODE_MAJOR}.*) : ;;
  *) annot "[断言失败] node=${NODE_VER}，期望 v${NODE_MAJOR}.x（NodeSource 官方源）"; exit 1 ;;
esac
dpkg -s libatomic1 >/dev/null 2>&1 || { annot "[断言失败] libatomic1 未安装"; exit 1; }
command -v tmux    >/dev/null 2>&1 || { annot "[断言失败] tmux 未安装"; exit 1; }
command -v git     >/dev/null 2>&1 || { annot "[断言失败] git 未安装"; exit 1; }
command -v busybox >/dev/null 2>&1 || { annot "[断言失败] busybox 未安装"; exit 1; }
command -v ffmpeg  >/dev/null 2>&1 || { annot "[断言失败] ffmpeg 未安装"; exit 1; }
[ "$(readlink /etc/localtime)" = "/usr/share/zoneinfo/Asia/Shanghai" ] || { annot "[断言失败] /etc/localtime 未指向 Asia/Shanghai"; exit 1; }

# ── GPU 软件渲染栈剔除后的断言（E-038）：既要"真删掉了"，也要"没删坏" ──
if dpkg -s mesa-libgallium >/dev/null 2>&1; then annot "[断言失败] mesa-libgallium 仍在（§2.8 剔除段没生效）"; exit 1; fi
if dpkg -s libllvm19      >/dev/null 2>&1; then annot "[断言失败] libllvm19 仍在（§2.8 剔除段没生效）"; exit 1; fi
command -v ffprobe >/dev/null 2>&1 || { annot "[断言失败] ffprobe 未安装"; exit 1; }
for BIN in ffmpeg ffprobe ffplay; do
  # ffplay 本来就跑不起来（无显示），这里只验"动态库都还在"，即剔除没有误伤加载期依赖
  if ldd "/usr/bin/$BIN" 2>/dev/null | grep -q 'not found'; then
    annot "[断言失败] $BIN 有缺失的动态库："; ldd "/usr/bin/$BIN" | grep 'not found'; exit 1
  fi
done
ffmpeg -hide_banner -loglevel error -f lavfi -i testsrc=size=64x64:rate=1 -frames:v 1 -f null - \
  || { annot "[断言失败] ffmpeg 编解码冒烟失败（剔除 GPU 栈后 ffmpeg 不可用）"; exit 1; }
if dpkg --audit | grep -q .; then annot "[断言失败] dpkg --audit 有输出（依赖图破了）："; dpkg --audit; exit 1; fi
apt-get check >/dev/null 2>&1 || { annot "[断言失败] apt-get check 失败（dpkg 依赖图破了）"; exit 1; }
for TOOL in node python3 git tmux rg busybox sqlite3 curl zstd uv; do
  command -v "$TOOL" >/dev/null 2>&1 || { annot "[断言失败] $TOOL 未安装"; exit 1; }
done

STEP="2.10 清理"
echo "---- 2.10 清理（控制落盘体积）----"
apt-get clean
rm -rf /var/lib/apt/lists/* /tmp/* /var/tmp/*
rm -rf /usr/share/doc/* /usr/share/man/* /usr/share/info/*
# 构建残留清理（2026-10-08 瘦身取证，见 docs/milestones/证道-环境包瘦身与压缩方案-2026-10-08.md §4 A）：
# 下面这些只有**构建期**才用得到，运行时没有任何东西引用它们（合计约 13 MB 落盘 / 3.8 MB 包体积）。
rm -f  /usr/bin/qemu-aarch64-static   # 只在 debootstrap --foreign 引导期做跨架构 chroot 用
rm -rf /usr/share/gitweb              # git 自带的 CGI 样例，环境里没有 web 服务
rm -rf /var/cache/debconf/*           # debconf 缓存，装完即失效（保留目录本身）
rm -rf /var/log/*                     # 清内容、保留目录（系统与 App 仍需可写日志目录）
# 语言包裁剪（2026-10-08 用户拍板「只留中英文也可以」）：
# 依据 = App 启动环境时注入的是 LANG=C.UTF-8（app/src/main/java/com/example/zhengdao/terminal/
# ProotLauncher.kt:433 与 :617），而本脚本 §2.3 也只生成 C.UTF-8 —— 这 70 MB 的翻译目录（.mo）
# 在 App 里从来没被读过。实测：只留 zh_CN/en ⇒ 包 326.6 → 301.9 MB（−24.7 MB）；
# 留 4 种语言（zh_CN/zh_TW/en/en_GB）只能省 18.8 MB ⇒ 中英两种正好，多的都是压舱物。
find /usr/share/locale -mindepth 1 -maxdepth 1 \
  ! -name 'zh_CN' ! -name 'en' ! -name 'locale.alias' -exec rm -rf {} +
rm -rf /usr/share/i18n                 # locale 生成源码（charmaps/locales 源，15.7 MB 落盘）；
                                       # C.UTF-8 是 glibc 内置、已生成的 locale 不受影响，
                                       # 代价只是环境里不能再 locale-gen 出新语言（App 用不到）
STEP="2.11 体积断言"
echo "---- 2.11 体积断言（防构建配置错误导致异常膨胀，v3.4）----"
# -x 不跨文件系统：跳过 bind 挂载的 /proc /sys /dev。du 探进 /proc 会因进程条目
# 消失而报错退出，被 pipefail 放大成构建失败——CI 首轮实测教训（v3.4 修复）
SIZE_MB="$(du -smx / 2>/dev/null | awk '{print $1}' || echo 0)"
if [ "$SIZE_MB" -ge 400 ] && [ "$SIZE_MB" -le 3000 ]; then
  echo "[zhengdao] rootfs 落盘体积: ${SIZE_MB}MB（符合 400–3000MB 预期；真实基线出来后可收紧）"
else
  annot "[断言失败] rootfs 落盘体积 ${SIZE_MB}MB 超出预期范围（400–3000MB），请检查预装清单与清理步骤"
  exit 1
fi
rm -f /zhengdao-configure.sh
echo "CONFIGURE_OK"
CONF
chmod 0755 "$ROOTFS_DIR/zhengdao-configure.sh"

STEP_OUTER="[2.5/4] chroot 内配置（2.1–2.11）"
chroot "$ROOTFS_DIR" /usr/bin/env NODE_MAJOR="$NODE_MAJOR" /bin/bash /zhengdao-configure.sh
STEP_OUTER="[3/4] 卸载与规整目录"

echo "[3/4] 卸载虚拟文件系统并规整目录 ..."
for m in "${MNT_LIST[@]}"; do umount -l "$m" 2>/dev/null || true; done
MNT_LIST=()
rm -rf "$ROOTFS_DIR/debootstrap"
# 打包前重写 resolv.conf（与 2.7 同一份内容 + 0444 锁定，见 2.7 注释）
rm -f  "$ROOTFS_DIR/etc/resolv.conf"
cat > "$ROOTFS_DIR/etc/resolv.conf" <<'RESOLVEOF'
options timeout:1 attempts:3 rotate
nameserver 223.5.5.5
nameserver 119.29.29.29
nameserver 1.1.1.1
nameserver 8.8.8.8
RESOLVEOF
chmod 0444 "$ROOTFS_DIR/etc/resolv.conf"
# home 与系统分离（设计文档 §8）：包内只留空的 /root，用户数据由 App 端独立目录 bind 挂入
rm -rf "$ROOTFS_DIR/root"
mkdir -p "$ROOTFS_DIR/root" && chmod 0700 "$ROOTFS_DIR/root"
printf 'distro=debian-%s\narch=%s\nglibc=2.41\npython=3.13\nnode_major=%s\n' \
  "$DEBIAN_VERSION" "$ARCH" "$NODE_MAJOR" > "$ROOTFS_DIR/etc/zhengdao-rootfs.info"

STEP_OUTER="[4/4] 打包 tar.zst"
echo "[4/4] 打包 tar.zst 并计算 SHA256 ..."
mkdir -p "$OUT_DIR"
ASSET="debian-${DEBIAN_VERSION}-base-arm64.tar.zst"
# 压缩级别：zstd 默认 3 → 19（2026-10-08 实测：同一份内容 326.6MB → 245.7MB，**−24.8%**）。
# 端侧不需要任何改动：Rust `zstd 0.13` 与 Java `zstd-jni 1.5.6-4` 都能解 -19 的帧（窗口 8MB）。
# **刻意不开** `--long` / `window_log 27`：只再省 2.4%（约 6MB），却要让端侧分配 128MB 解码窗口，
# 低端机上就是一次 OOM 风险（见 docs/milestones/证道-环境包瘦身与压缩方案-2026-10-08.md §2）。
# 注：GNU tar 经管道调用 zstd，`-T#` 多线程对管道输出无效（zstd 只在输出为普通文件时开多线程），
# 这里就是单线程；对已经要跑 30–90 分钟的 qemu 交叉构建来说，多花几分钟压缩可以接受。
# ZSTD_CLEVEL 与显式 `-19` 两道都写上（zstd 认环境变量，但显式参数更不容易被误删）。
if ! TAR_OUT="$(ZSTD_CLEVEL=19 tar --use-compress-program="zstd -19" -cf "$OUT_DIR/$ASSET" --numeric-owner -C "$ROOTFS_DIR" . 2>&1)"; then
  annot "[4/4] 打包 tar.zst 失败：$(echo "$TAR_OUT" | tail -n 20)"; exit 1
fi
if [ -n "$TAR_OUT" ]; then echo "$TAR_OUT"; fi
sha256sum "$OUT_DIR/$ASSET" | awk '{print $1}' > "$OUT_DIR/$ASSET.sha256"

# 包体积门禁（2026-10-08 新增）：防"某个预装包又把大依赖整棵拖回来"而无人发现。
# 基线：zstd-19 全量 ≈ 245.7MB（门禁留到 280MB）；若将来执行方案 B1（剔 ffmpeg 闭包）应降到 ≈158MB。
PKG_MB=$(( $(stat -c%s "$OUT_DIR/$ASSET") / 1000000 ))
if [ "$PKG_MB" -le 280 ]; then
  echo "[zhengdao] 包体积: ${PKG_MB}MB（门禁 280MB）"
else
  annot "[断言失败] 包体积 ${PKG_MB}MB 超过门禁 280MB —— 检查预装清单是否又拖进大依赖"
  echo "           历史最大项：ffmpeg 及其 201 个私有依赖（安装体积 397.8MB / 包体积 158MB）"
  exit 1
fi

# ---------------------------------------------------------------------
# [5/5] 增量下发素材：清单 / 差分补丁 / 索引（2026-10-08 新增）
#   格式契约：docs/milestones/证道-环境包增量下发协议.md
#   产物：rootfs-manifest.txt(+.sha256)
#         rootfs-patch-<baseEnv>-to-<newEnv>.tar.zst(+.sha256)  ← 拿得到基线才产
#         rootfs-index.json                                     ← App 端唯一的版本入口
#   基线清单由 CI 在构建前下到 ${BASE_MANIFEST}（默认 ../rootfs-base/rootfs-manifest.txt）。
#   取不到基线 ⇒ 只产全量包 + 清单 + 索引，不产补丁，**不因此失败**（首次构建就是这种）。
#   缺 python3/工具本身 ⇒ 整段跳过（清单是增值产物，不能让整包构建失败）。
#   但工具**跑起来之后**出错 ⇒ 直接失败（宁可 CI 红，也不要偷偷发一份对不上的清单）。
# ---------------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MANIFEST_TOOL="$SCRIPT_DIR/../tools/rootfs-manifest.py"
BASE_MANIFEST="${BASE_MANIFEST:-}"
NEW_MANIFEST="$OUT_DIR/rootfs-manifest.txt"
if command -v python3 >/dev/null 2>&1 && [ -f "$MANIFEST_TOOL" ]; then
  STEP_OUTER="[5/5] 清单/补丁/索引"
  echo "[5/5] 生成环境清单（增量下发基线）..."
  if ! MF_OUT="$(python3 "$MANIFEST_TOOL" manifest --root "$ROOTFS_DIR" --out "$NEW_MANIFEST" --distro "debian-$DEBIAN_VERSION" 2>&1)"; then
    annot "[5/5] 生成清单失败（manifest）：$MF_OUT"; exit 1
  fi
  sha256sum "$NEW_MANIFEST" | awk '{print $1}' > "$NEW_MANIFEST.sha256"
  NEW_ENV="$(python3 "$MANIFEST_TOOL" env --manifest "$NEW_MANIFEST" | sed -n 's/^env=//p')"
  if [ -z "$NEW_ENV" ]; then
    annot "[断言失败] 清单生成了但 env 算不出来（$NEW_MANIFEST）"
    exit 1
  fi
  PATCH_ASSET=""
  if [ -n "$BASE_MANIFEST" ] && [ -f "$BASE_MANIFEST" ]; then
    BASE_ENV="$(python3 "$MANIFEST_TOOL" env --manifest "$BASE_MANIFEST" | sed -n 's/^env=//p')"
    if [ -z "$BASE_ENV" ]; then
      echo "[警告] 上一版清单读不出 env（$BASE_MANIFEST），本次不产补丁"
    elif [ "$BASE_ENV" = "$NEW_ENV" ]; then
      echo "[5/5] 内容与上一版一致（env=$NEW_ENV），不产补丁"
    else
      PATCH_ASSET="rootfs-patch-${BASE_ENV}-to-${NEW_ENV}.tar.zst"
      if ! PT_OUT="$(python3 "$MANIFEST_TOOL" patch --root "$ROOTFS_DIR" --base "$BASE_MANIFEST" --new "$NEW_MANIFEST" \
        --out "$OUT_DIR/$PATCH_ASSET" 2>&1)"; then
        annot "[5/5] 生成差分补丁失败（patch）：$PT_OUT"; exit 1
      fi
      sha256sum "$OUT_DIR/$PATCH_ASSET" | awk '{print $1}' > "$OUT_DIR/$PATCH_ASSET.sha256"
    fi
  else
    echo "[5/5] 没有上一版清单（BASE_MANIFEST='${BASE_MANIFEST}'），本次不产补丁"
  fi
  INDEX_ARGS=(index --new-manifest "$NEW_MANIFEST" --pkg "$OUT_DIR/$ASSET"
    --repo "${GITHUB_REPOSITORY:-pisces19860207/zhengdao}"
    --distro "debian-$DEBIAN_VERSION" --version "$DEBIAN_VERSION" --asset "$ASSET"
    --out "$OUT_DIR/rootfs-index.json")
  if [ -n "$PATCH_ASSET" ]; then INDEX_ARGS+=(--patch "$OUT_DIR/$PATCH_ASSET"); fi
  if [ -n "${BUILT_AT:-}" ]; then INDEX_ARGS+=(--built-at "$BUILT_AT"); fi
  if ! IX_OUT="$(python3 "$MANIFEST_TOOL" "${INDEX_ARGS[@]}" 2>&1)"; then
    annot "[5/5] 生成 rootfs-index.json 失败（index）：$IX_OUT"; exit 1
  fi
  if [ -n "$IX_OUT" ]; then echo "$IX_OUT"; fi
  echo "ENV     : $NEW_ENV"
  echo "PATCH   : ${PATCH_ASSET:-（本次无补丁）}"
else
  echo "[5/5] 跳过清单/补丁/索引：缺少 python3 或 $MANIFEST_TOOL"
fi

echo "----------------------------------------"
echo "BUILD_OK: $OUT_DIR/$ASSET"
echo "SHA256  : $(cat "$OUT_DIR/$ASSET.sha256")"
echo "SIZE    : $(stat -c%s "$OUT_DIR/$ASSET") bytes"
echo "资产目录: $OUT_DIR（完整包 + 清单 + 补丁 + rootfs-index.json 全部挂同一个 Release）"
echo "----------------------------------------"
