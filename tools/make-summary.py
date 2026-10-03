# -*- coding: utf-8 -*-
"""证道项目工具：把全部第一方代码汇总成 TXT（供其他 AI 评审）。
用法：python tools/make-summary.py
输出：C:\\Users\\guoli\\证道-完整代码汇总.txt"""
import io, os

PROJ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DOC = r"C:\Users\guoli\WorkBuddy\2026-10-03-13-19-55\安卓AgentApp-设计方案-v3.md"
OUT = r"C:\Users\guoli\证道-完整代码汇总.txt"

FILES = [
    "PROVENANCE.md",
    ".github/workflows/build.yml",
    "app/build.gradle.kts",
    "settings.gradle.kts",
    "build.gradle.kts",
    "gradle/libs.versions.toml",
    "gradle/wrapper/gradle-wrapper.properties",
    "app/src/main/AndroidManifest.xml",
    "app/src/main/res/layout/activity_main.xml",
    "app/src/main/res/values/strings.xml",
    "app/src/main/assets/terminal/index.html",
    "app/src/main/cpp/CMakeLists.txt",
    "app/src/main/cpp/pty.c",
    "app/src/main/java/com/example/zhengdao/MainActivity.kt",
    "app/src/main/java/com/example/zhengdao/terminal/Pty.kt",
    "app/src/main/java/com/example/zhengdao/terminal/TerminalSession.kt",
    "app/src/main/java/com/example/zhengdao/terminal/TerminalBridge.kt",
    "app/src/main/java/com/example/zhengdao/terminal/ProotLauncher.kt",
    "app/src/main/java/com/example/zhengdao/rootfs/RootfsDownloader.kt",
    "app/src/main/java/com/example/zhengdao/rootfs/RootfsInstaller.kt",
    "rootfs/build-rootfs.sh",
    "rootfs/build-proot.sh",
]

def read(path):
    with io.open(path, "r", encoding="utf-8-sig", errors="replace") as f:
        return f.read()

parts = []
parts.append("证道（Zhengdao）——安卓轻量级 AI Agent App 完整代码汇总")
parts.append("=" * 70)
parts.append("生成：2026-10-03（v3.4 文档同步版，含 CI 工作流）")
parts.append("项目路径：%s" % PROJ)
parts.append("设计文档：%s（v3.4，附在文末）" % DOC)
parts.append("")
parts.append("工程状态摘要：")
parts.append("  - 全部第一方代码带独立开发声明，未参考任何第三方同类 App（PROVENANCE.md）")
parts.append("  - targetSdk 28 钉死（架构生死线）；minSdk 29；arm64 主架构")
parts.append("  - 自研 JNI 伪终端（forkpty）+ xterm.js（官方 MIT 发行包）终端，已编译通过")
parts.append("  - RootFS 下载器（断点续传 + SHA256）与原子解压器已实现并编译通过")
parts.append("  - proot 已从上游源码（proot-me/proot + talloc 2.4.2）用 NDK 自编译成功：")
parts.append("    aarch64 ELF64 186KB，build-proot.sh 为可复现配方（含自研 bionic 垫片）")
parts.append("  - CI：.github/workflows/build.yml 在 ubuntu runner 上构建 APK + RootFS + proot")
parts.append("  - 真机待验证项：xterm.js 中文 IME、zstd-jni 运行时加载、proot 真机可用性（M1.2）")
parts.append("  - 说明：assets/terminal/ 下的 xterm.min.js / xterm.min.css / addon-fit.min.js")
parts.append("    为 xterm.js 官方发行包（MIT），非本项目代码，不在本文件内。")
parts.append("")
parts.append("文件清单（%d 个）：" % len(FILES))
for rel in FILES:
    p = os.path.join(PROJ, rel)
    lines = read(p).count("\n") + 1
    parts.append("  %s (%d 行)" % (rel, lines))
parts.append("")
parts.append("=" * 70)

for rel in FILES:
    p = os.path.join(PROJ, rel)
    parts.append("")
    parts.append("■" * 50)
    parts.append("■ 文件：%s" % rel)
    parts.append("■" * 50)
    parts.append("")
    parts.append(read(p))

parts.append("")
parts.append("=" * 70)
parts.append("附录：设计文档全文（v3.4）")
parts.append("=" * 70)
parts.append(read(DOC))

text = "\n".join(parts)
with io.open(OUT, "w", encoding="utf-8") as f:
    f.write(text)
print("OK: %s（%d 字符，%d 行）" % (OUT, len(text), text.count("\n") + 1))
