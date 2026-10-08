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

DEBIAN_RELEASE="trixie"
DEBIAN_VERSION="13.7"
NODE_MAJOR="26"
ARCH="arm64"
MIRROR="http://deb.debian.org/debian"
OUT_DIR="${1:-$(pwd)/out}"

# ---------- 构建机自检 ----------
if [ "$(id -u)" -ne 0 ]; then
  echo "[错误] 请用 root 运行（debootstrap 与 mount 需要 root 权限）"
  exit 1
fi
for tool in debootstrap zstd tar curl sha256sum; do
  command -v "$tool" >/dev/null 2>&1 || {
    echo "[错误] 构建机缺少 $tool，请先安装（Debian/Ubuntu: apt install ${tool}）"
    exit 1
  }
done
HOST_ARCH="$(uname -m)"
if [ "$HOST_ARCH" != "aarch64" ] && [ "$HOST_ARCH" != "x86_64" ]; then
  echo "[错误] 不支持的构建机架构: $HOST_ARCH（需要 arm64 或 x86_64）"
  exit 1
fi
if [ "$HOST_ARCH" = "x86_64" ]; then
  command -v qemu-aarch64-static >/dev/null 2>&1 || {
    echo "[错误] x86_64 构建机需要 qemu-user-static（apt install qemu-user-static）"
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

echo "---- 2.1 修复容器通病（实测坑 #2）----"
mkdir -p /var/log/apt /var/log/dpkg
touch /var/log/apt/history.log /var/log/apt/term.log

echo "---- 2.2 基础依赖（git / tmux / libatomic1 / busybox 等）----"
apt-get update
apt-get install -y --no-install-recommends \
  git curl wget ca-certificates gnupg \
  tmux procps busybox ripgrep libatomic1 ffmpeg sqlite3 \
  locales bash-completion less xz-utils zstd sudo \
  tzdata

echo "---- 2.3 locale：确认 C.UTF-8 可用 ----"
locale -a 2>/dev/null | grep -qi '^C\.utf8' || {
  sed -i 's/^# *\(C\.UTF-8.*\)/\1/' /etc/locale.gen
  locale-gen
}

echo "---- 2.4 Node.js ${NODE_MAJOR}：NodeSource 官方源（设计文档 §6，不用 Debian 源旧版）----"
curl -fsSL "https://deb.nodesource.com/setup_${NODE_MAJOR}.x" | bash -
apt-get install -y nodejs

echo "---- 2.5 uv：官方发行包，仅作 pip 安装器（设计文档 §6）----"
case "$(uname -m)" in
  aarch64) UV_TARGET="aarch64-unknown-linux-gnu" ;;
  x86_64)  UV_TARGET="x86_64-unknown-linux-gnu"  ;;
  *) echo "[错误] 未知架构: $(uname -m)"; exit 1 ;;
esac
curl -fsSL "https://github.com/astral-sh/uv/releases/latest/download/uv-${UV_TARGET}.tar.gz" \
  | tar -xz -C /tmp
install -m 0755 "/tmp/uv-${UV_TARGET}/uv"  /usr/local/bin/uv
install -m 0755 "/tmp/uv-${UV_TARGET}/uvx" /usr/local/bin/uvx
rm -rf "/tmp/uv-${UV_TARGET}"

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

echo "---- 2.8 版本断言（构建即验收，漂移即失败）----"
GLIBC_VER="$(ldd --version | head -n1 | awk '{print $NF}')"
PY_VER="$(python3 --version | awk '{print $2}')"
NODE_VER="$(node --version)"
UV_VER="$(uv --version | awk '{print $2}')"
echo "[zhengdao] glibc=${GLIBC_VER} python=${PY_VER} node=${NODE_VER} uv=${UV_VER}"
case "$GLIBC_VER" in
  2.4[1-9]|2.[5-9]*) : ;;
  *) echo "[断言失败] glibc=${GLIBC_VER}，期望 2.41+（Debian 13.7 自带，不手动升级）"; exit 1 ;;
esac
case "$PY_VER" in
  3.13*) : ;;
  *) echo "[断言失败] python=${PY_VER}，期望 3.13.x（Debian 13.7 自带）"; exit 1 ;;
esac
case "$NODE_VER" in
  v${NODE_MAJOR}.*) : ;;
  *) echo "[断言失败] node=${NODE_VER}，期望 v${NODE_MAJOR}.x（NodeSource 官方源）"; exit 1 ;;
esac
dpkg -s libatomic1 >/dev/null 2>&1 || { echo "[断言失败] libatomic1 未安装"; exit 1; }
command -v tmux    >/dev/null 2>&1 || { echo "[断言失败] tmux 未安装"; exit 1; }
command -v git     >/dev/null 2>&1 || { echo "[断言失败] git 未安装"; exit 1; }
command -v busybox >/dev/null 2>&1 || { echo "[断言失败] busybox 未安装"; exit 1; }
command -v ffmpeg  >/dev/null 2>&1 || { echo "[断言失败] ffmpeg 未安装"; exit 1; }
[ "$(readlink /etc/localtime)" = "/usr/share/zoneinfo/Asia/Shanghai" ] || { echo "[断言失败] /etc/localtime 未指向 Asia/Shanghai"; exit 1; }

echo "---- 2.9 清理（控制落盘体积）----"
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
echo "---- 2.10 体积断言（防构建配置错误导致异常膨胀，v3.4）----"
# -x 不跨文件系统：跳过 bind 挂载的 /proc /sys /dev。du 探进 /proc 会因进程条目
# 消失而报错退出，被 pipefail 放大成构建失败——CI 首轮实测教训（v3.4 修复）
SIZE_MB="$(du -smx / 2>/dev/null | awk '{print $1}' || echo 0)"
if [ "$SIZE_MB" -ge 400 ] && [ "$SIZE_MB" -le 3000 ]; then
  echo "[zhengdao] rootfs 落盘体积: ${SIZE_MB}MB（符合 400–3000MB 预期；真实基线出来后可收紧）"
else
  echo "[断言失败] rootfs 落盘体积 ${SIZE_MB}MB 超出预期范围（400–3000MB），请检查预装清单与清理步骤"
  exit 1
fi
rm -f /zhengdao-configure.sh
echo "CONFIGURE_OK"
CONF
chmod 0755 "$ROOTFS_DIR/zhengdao-configure.sh"

chroot "$ROOTFS_DIR" /usr/bin/env NODE_MAJOR="$NODE_MAJOR" /bin/bash /zhengdao-configure.sh

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
ZSTD_CLEVEL=19 tar --use-compress-program="zstd -19" -cf "$OUT_DIR/$ASSET" --numeric-owner -C "$ROOTFS_DIR" .
sha256sum "$OUT_DIR/$ASSET" | awk '{print $1}' > "$OUT_DIR/$ASSET.sha256"

# 包体积门禁（2026-10-08 新增）：防"某个预装包又把大依赖整棵拖回来"而无人发现。
# 基线：zstd-19 全量 ≈ 245.7MB（门禁留到 280MB）；若将来执行方案 B1（剔 ffmpeg 闭包）应降到 ≈158MB。
PKG_MB=$(( $(stat -c%s "$OUT_DIR/$ASSET") / 1000000 ))
if [ "$PKG_MB" -le 280 ]; then
  echo "[zhengdao] 包体积: ${PKG_MB}MB（门禁 280MB）"
else
  echo "[断言失败] 包体积 ${PKG_MB}MB 超过门禁 280MB —— 检查预装清单是否又拖进大依赖"
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
  echo "[5/5] 生成环境清单（增量下发基线）..."
  python3 "$MANIFEST_TOOL" manifest --root "$ROOTFS_DIR" --out "$NEW_MANIFEST" --distro "debian-$DEBIAN_VERSION"
  sha256sum "$NEW_MANIFEST" | awk '{print $1}' > "$NEW_MANIFEST.sha256"
  NEW_ENV="$(python3 "$MANIFEST_TOOL" env --manifest "$NEW_MANIFEST" | sed -n 's/^env=//p')"
  if [ -z "$NEW_ENV" ]; then
    echo "[断言失败] 清单生成了但 env 算不出来（$NEW_MANIFEST）"
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
      python3 "$MANIFEST_TOOL" patch --root "$ROOTFS_DIR" --base "$BASE_MANIFEST" --new "$NEW_MANIFEST" \
        --out "$OUT_DIR/$PATCH_ASSET"
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
  python3 "$MANIFEST_TOOL" "${INDEX_ARGS[@]}"
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
