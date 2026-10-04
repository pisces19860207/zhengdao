# 证道（Zhengdao）

![build](https://github.com/pisces19860207/zhengdao/actions/workflows/build.yml/badge.svg)

> 安卓上的轻量级 AI Agent 运行环境——免 root、零命令、一键安装你自己的 Agent。

让普通用户在 Android 手机上拥有一套真实的 Linux 环境（Debian 13.7），
并在其中一键运行 Claude Code、Hermes Agent 等官方 CLI Agent。
设计目标：即开即用、按需下载、全程零命令行。

## 当前状态

开发中（M1 阶段：最小闭环）。CI 产物（APK / RootFS / proot）见
[Actions 页面](https://github.com/pisces19860207/zhengdao/actions)。

## 系统要求

- **Android 15（API 35）或更高版本**；推荐 arm64 架构设备。
- 约 2.5GB 可用存储空间（安装 Debian 13.7 环境后）。

## 隐私

本应用不上传用户数据；网络访问仅用于下载资源与检查更新。
服务器访问日志仅保留 7 天，仅用于排障，不用于任何用户行为分析。

## 独立开发声明

本项目全部第一方代码为从零独立编写，未参考任何第三方同类应用的代码。
允许参考的官方资料清单与禁止事项见 [PROVENANCE.md](PROVENANCE.md)。

## 许可

第一方代码许可待定；内置 proot 组件基于上游 GPL 项目（proot-me/proot）编译，
作为独立可执行文件聚合分发。
