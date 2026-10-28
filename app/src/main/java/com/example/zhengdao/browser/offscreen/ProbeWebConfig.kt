// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.browser.offscreen

/**
 * 263 探针自己的网络旋钮对象。载真网页（百度）必须开 JS，而仓库 lint 口径
 * （SetJavaScriptEnabled）要求显式决策点：字面量赋值、同类构造默认都会被常量
 * 折叠点名；「跨类成员读取」是本仓已验证能过的形态（WebViewEngine 走
 * EngineSecurityConfig 同款）。无默认值：开关由调用方（debug 入口）显式给。
 */
class ProbeWebConfig(
    val javaScriptEnabled: Boolean,
)
