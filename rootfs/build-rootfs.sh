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
  locales bash-completion less xz-utils zstd sudo

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

echo "---- 2.7 DNS 与 hosts 兜底 ----"
[ -s /etc/resolv.conf ] || printf 'nameserver 1.1.1.1\nnameserver 8.8.8.8\n' > /etc/resolv.conf
[ -s /etc/hosts ] || printf '127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n' > /etc/hosts

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

echo "---- 2.9 清理（控制落盘体积）----"
apt-get clean
rm -rf /var/lib/apt/lists/* /tmp/* /var/tmp/*
rm -rf /usr/share/doc/* /usr/share/man/* /usr/share/info/*
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
rm -f  "$ROOTFS_DIR/etc/resolv.conf"
printf 'nameserver 1.1.1.1\nnameserver 8.8.8.8\n' > "$ROOTFS_DIR/etc/resolv.conf"
# home 与系统分离（设计文档 §8）：包内只留空的 /root，用户数据由 App 端独立目录 bind 挂入
rm -rf "$ROOTFS_DIR/root"
mkdir -p "$ROOTFS_DIR/root" && chmod 0700 "$ROOTFS_DIR/root"
printf 'distro=debian-%s\narch=%s\nglibc=2.41\npython=3.13\nnode_major=%s\n' \
  "$DEBIAN_VERSION" "$ARCH" "$NODE_MAJOR" > "$ROOTFS_DIR/etc/zhengdao-rootfs.info"

echo "[4/4] 打包 tar.zst 并计算 SHA256 ..."
mkdir -p "$OUT_DIR"
ASSET="debian-${DEBIAN_VERSION}-base-arm64.tar.zst"
tar --zstd -cf "$OUT_DIR/$ASSET" --numeric-owner -C "$ROOTFS_DIR" .
sha256sum "$OUT_DIR/$ASSET" | awk '{print $1}' > "$OUT_DIR/$ASSET.sha256"

echo "----------------------------------------"
echo "BUILD_OK: $OUT_DIR/$ASSET"
echo "SHA256  : $(cat "$OUT_DIR/$ASSET.sha256")"
echo "SIZE    : $(stat -c%s "$OUT_DIR/$ASSET") bytes"
echo "下一步  : 把产物挂到 GitHub Release + CDN，并把 SHA256/size 写进 manifest.json（§6）"
echo "----------------------------------------"
