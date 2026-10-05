# 独立开发声明：本 CMake 脚本为本项目从零编写。
# 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
#
# v3.9（2026-10-05）：自研 JNI PTY 桥（pty.c / libzhengdao_pty.so）已随终端渲染层
# 切换为 Termux TerminalView 而退役删除——进程创建与 IO 现由 Termux 官方
# terminal-emulator 的 native 半边（libtermux.so）承担。历史实现保留在 git 中。
cmake_minimum_required(VERSION 3.22)
project(zhengdao C)

# Termux 官方终端 JNI（terminal-emulator / terminal-view 的 native 半边，v0.119.0-beta.3）
# ⚠️ 许可证更正：这两个库为 Apache-2.0（见上游 termux-app/LICENSE.md 的 Exceptions 一节），
# 仅 termux-app 主应用本体为 GPL-3.0，本项目未聚合主应用。此前误标为 GPL-3.0。
add_library(termux SHARED termux/termux.c)

target_link_libraries(termux PRIVATE log)

# 16KB 页对齐：Android 15+ 的 16KB 页设备必需（设计文档 §4）
target_link_options(termux PRIVATE "-Wl,-z,max-page-size=16384")
