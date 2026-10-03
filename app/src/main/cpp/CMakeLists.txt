# 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
# 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
cmake_minimum_required(VERSION 3.22)
project(zhengdao_pty C)

add_library(zhengdao_pty SHARED pty.c)

target_link_libraries(zhengdao_pty PRIVATE log)

# 16KB 页对齐：Android 15+ 的 16KB 页设备必需（设计文档 §4）
target_link_options(zhengdao_pty PRIVATE "-Wl,-z,max-page-size=16384")
