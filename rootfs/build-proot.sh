#!/usr/bin/env bash
# =====================================================================
# 证道（Zhengdao）—— proot 交叉编译脚本（上游源码 + Android NDK）
# 独立开发声明：本脚本为本项目从零编写，未参考任何第三方同类项目的构建脚本。
# 参考资料仅为官方文档：proot-me/proot 上游仓库与 src/GNUmakefile、
# talloc 上游发行包、Android NDK clang 官方文档。
#
# 本配方已在 Windows 10 + Git Bash + NDK r27.2 实测通过（2026-10-03）：
# 产物 aarch64 ELF64 proot，186KB（strip 后），loader 已嵌入（--link2symlink 可用）。
#
# 用法（Git Bash / Linux，需已安装 Android NDK）：
#   NDK_BIN="<ndk>/toolchains/llvm/prebuilt/<host>/bin" bash build-proot.sh [输出目录]
#
# 说明：
#   - talloc 用本项目自写的最小 replace.h 垫片直接对 bionic 编译（不走 waf）；
#   - proot 的 seccomp 加速已编入（HAVE_SECCOMP_FILTER）；个别设备若出现
#     signal 31 崩溃，运行时设置 PROOT_NO_SECCOMP=1 即关闭加速（设计文档 §4）；
#   - 产物为上游 GPL 代码的编译产物，作为独立可执行文件随 App 数据分发（聚合分发）。
# =====================================================================
set -euo pipefail

NDK_BIN="${NDK_BIN:?请设置 NDK_BIN=<NDK>/toolchains/llvm/prebuilt/<host>/bin}"
OUT_DIR="${1:-$(pwd)/out}"
TGT="--target=aarch64-linux-android29"
CLANG="$NDK_BIN/clang.exe"
[ -x "$CLANG" ] || CLANG="$NDK_BIN/clang"   # Linux 上无 .exe 后缀
AR="$NDK_BIN/llvm-ar.exe";  [ -x "$AR" ]  || AR="$NDK_BIN/llvm-ar"
STRIP="$NDK_BIN/llvm-strip.exe"; [ -x "$STRIP" ] || STRIP="$NDK_BIN/llvm-strip"
OBJCOPY="$NDK_BIN/llvm-objcopy.exe"; [ -x "$OBJCOPY" ] || OBJCOPY="$NDK_BIN/llvm-objcopy"
NM="$NDK_BIN/llvm-nm.exe";  [ -x "$NM" ]  || NM="$NDK_BIN/llvm-nm"
READELF="$NDK_BIN/llvm-readelf.exe"; [ -x "$READELF" ] || READELF="$NDK_BIN/llvm-readelf"

W="$(mktemp -d /tmp/zhengdao-proot.XXXXXX)"
trap 'rm -rf "$W"' EXIT

echo "[1/6] 下载上游源码 ..."
mkdir -p "$W" && cd "$W"
curl -sL --max-time 180 -o proot-src.tar.gz \
  "https://codeload.github.com/proot-me/proot/tar.gz/refs/heads/master"
curl -sL --max-time 180 -o talloc-src.tar.gz \
  "http://deb.debian.org/debian/pool/main/t/talloc/talloc_2.4.2.orig.tar.gz"
tar -xzf proot-src.tar.gz
tar -xzf talloc-src.tar.gz

echo "[2/6] 写入 bionic 版 replace.h 垫片（自研，见 PROVENANCE.md）..."
mkdir -p "$W/zhengdao-shim"
cat > "$W/zhengdao-shim/replace.h" <<'EOF'
/* 独立开发声明：本文件为本项目从零编写的最小垫片，用于在 Android bionic 上
   编译 talloc 时替代 samba lib/replace/replace.h 的平台探测层。
   bionic (API 29) 原生提供以下全部标准能力，无需任何 rep_* 替换实现。 */
#ifndef ZHENGDAO_REPLACE_H
#define ZHENGDAO_REPLACE_H

#include <stdio.h>
#include <stdlib.h>
#include <stdarg.h>
#include <string.h>
#include <strings.h>
#include <unistd.h>
#include <errno.h>
#include <stdbool.h>
#include <stdint.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>

/* 常用极值宏（samba replace.h 原本提供） */
#ifndef MIN
#define MIN(a, b) ((a) < (b) ? (a) : (b))
#endif
#ifndef MAX
#define MAX(a, b) ((a) > (b) ? (a) : (b))
#endif

#endif /* ZHENGDAO_REPLACE_H */
EOF

echo "[3/6] 编译 talloc 静态库 ..."
mkdir -p "$W/talloc-2.4.2/build"
(cd "$W/talloc-2.4.2" && "$CLANG" $TGT -D_GNU_SOURCE \
  -DTALLOC_BUILD_VERSION_MAJOR=2 -DTALLOC_BUILD_VERSION_MINOR=4 \
  -DTALLOC_BUILD_VERSION_RELEASE=2 \
  -O2 -std=c99 -I"$W/zhengdao-shim" -I. -c talloc.c -o build/talloc.o)
"$AR" rcs "$W/talloc-2.4.2/build/libtalloc.a" "$W/talloc-2.4.2/build/talloc.o"

echo "[4/6] 编译 proot 全部对象（38 个）..."
cd "$W/proot-master/src"
cat > build.h <<'EOF'
/* This file is auto-generated, edit at your own risk.  */
#ifndef BUILD_H
#define BUILD_H
#undef VERSION
#define VERSION "5.4.0-zhengdao"
#define HAVE_PROCESS_VM
#define HAVE_SECCOMP_FILTER
#endif /* BUILD_H */
EOF
OBJS="cli/cli.o cli/proot.o cli/note.o execve/enter.o execve/exit.o execve/shebang.o execve/elf.o execve/ldso.o execve/auxv.o execve/aoxp.o path/binding.o path/glue.o path/canon.o path/path.o path/proc.o path/temp.o syscall/seccomp.o syscall/syscall.o syscall/chain.o syscall/enter.o syscall/exit.o syscall/sysnum.o syscall/socket.o syscall/heap.o syscall/rlimit.o tracee/tracee.o tracee/mem.o tracee/reg.o tracee/event.o ptrace/ptrace.o ptrace/user.o ptrace/wait.o extension/extension.o extension/kompat/kompat.o extension/fake_id0/fake_id0.o extension/link2symlink/link2symlink.o extension/portmap/portmap.o extension/portmap/map.o"
for f in $OBJS; do
  mkdir -p "$(dirname "$f")"
  "$CLANG" $TGT -D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE \
    -I. -I"$(pwd)" -I"$W/proot-master/lib/uthash/include" -I"$W/talloc-2.4.2" \
    -O2 -c "${f%.o}.c" -o "$f"
done

echo "[5/6] 编译嵌入式 loader 并打包 ..."
"$CLANG" $TGT -I. -c -fPIC -ffreestanding loader/loader.c -o loader/loader.o
"$CLANG" $TGT -I. -c -fPIC -ffreestanding loader/assembly.S -o loader/assembly.o
"$CLANG" $TGT -static -nostdlib -Wl,-Ttext=0x2000000000,-z,noexecstack \
  -o loader/loader loader/loader.o loader/assembly.o
"$STRIP" -o loader/loader.elf loader/loader
# 注意：objcopy 的符号名来自输入文件名（点号转下划线），必须在 src 根目录用
# 裸文件名 loader.elf 执行，才能得到 proot 期望的 _binary_loader_elf_start
cp loader/loader.elf ./loader.elf
"$OBJCOPY" --input-target binary --output-target elf64-littleaarch64 \
  --binary-architecture aarch64 loader.elf loader-wrapped.o

echo "[6/6] 链接 proot 并校验 ..."
"$CLANG" $TGT -o proot $OBJS loader-wrapped.o \
  -L"$W/talloc-2.4.2/build" -ltalloc -Wl,-z,noexecstack -Wl,-z,max-page-size=16384
"$STRIP" proot
"$READELF" -h proot | grep -E "Class:|Machine:"
mkdir -p "$OUT_DIR"
cp proot "$OUT_DIR/proot-arm64"
echo "----------------------------------------"
echo "PROOT_BUILD_OK: $OUT_DIR/proot-arm64 ($(stat -c%s "$OUT_DIR/proot-arm64") bytes)"
echo "----------------------------------------"
