# 独立开发声明（PROVENANCE）

本项目（zhengdao / 安卓轻量级 AI Agent App）为**从零独立开发**。
本文件是整个仓库的原创性规则，所有协作者（包括 AI）必须遵守。

## 允许参考（官方一手资料）

- Android 官方开发者文档（developer.android.com / developer.android.google.cn）
- proot 上游官方仓库与官方文档（proot-me/proot）及其官方运行参数
- xterm.js 官方文档与官方发行包（MIT 许可证）
- 标准库官方文档：OkHttp（Apache-2.0）、Apache Commons Compress、zstd / zstd-jni
- POSIX 标准 API（forkpty 等）与 ADB 公开协议文档

## 严格禁止

- 阅读、引用、反编译任何同类第三方应用的代码或安装包
  （包括但不限于太墟 / TaiXu、Termux 及其分支）
- 复刻任何同类应用的 UI 交互细节
- 把 AI 生成的"疑似来自某项目"的代码片段直接入库——发现即删除重写

## 第三方组件使用方式

| 组件 | 许可证 | 使用方式 |
|---|---|---|
| xterm.js | MIT | 官方发行包，按官方文档接入 |
| proot | 上游 GPL | 官方源码自行编译，作为独立可执行文件分发（聚合分发，不链接） |
| OkHttp / Compose 等 | Apache-2.0 | Maven 依赖，按官方文档使用 |

## 代码文件头

本项目所有第一方代码文件头部须带声明：

```kotlin
// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
```
