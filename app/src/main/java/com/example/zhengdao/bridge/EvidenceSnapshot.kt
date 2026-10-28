// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：262 批第一步（工单原文：新增/复用页面快照命令 {url, title, DOM 摘要, 可访问性
// 文本, 元素计数}，**不依赖前台可见**——DOM/属性读取与渲染无关；体积钳制与失败
// 原因同批核定）。纯内核：无 Android 依赖 ⇒ JVM 直测（同 parseEyeProbe/parseGeometry
// 的做法），WebView 交互在 [BrowserCommands.evidenceSnapshot]。
package com.example.zhengdao.bridge

/**
 * 页面证据快照的纯内核（262 批）。
 *
 * 为什么不依赖前台可见：取的全是 DOM 树与属性（`textContent`/`getAttribute`——
 * **不用 `innerText`**，那是要排版渲染的，离屏未组合的标签会拿空）。
 *
 * 钳制口径（工单逐条）：DOM 摘要里正文前 [DOM_TEXT_MAX_CHARS] 字；可访问性文本前
 * [AX_TEXT_MAX_CHARS] 字；骨架 [MAX_SKELETON_LINES] 行、AX [MAX_AX_ENTRIES] 条；
 * 单次回包 ≤[MAX_TOTAL_BYTES]，超了标 truncated——**字节级总闸必须在 Kotlin**
 * （CJK 每字 UTF-8 3 字节：300 行骨架＋2000＋4000 字符最坏 ~100KB，字符钳制挡不满 64KB）。
 */
internal object EvidenceSnapshot {

    /** DOM 摘要里正文文本的字符上限（工单口径 2000）。 */
    internal const val DOM_TEXT_MAX_CHARS = 2_000

    /** 可访问性文本的字符上限（工单口径 4000）。 */
    internal const val AX_TEXT_MAX_CHARS = 4_000

    /** 结构骨架行数上限。 */
    internal const val MAX_SKELETON_LINES = 300

    /** 可访问性文本条目数上限。 */
    internal const val MAX_AX_ENTRIES = 200

    /** 单次回包总字节上限（工单口径 64KB）。 */
    internal const val MAX_TOTAL_BYTES = 64 * 1024

    /** 一次页面证据读数（字段语义见 [evidenceJsScript]）。 */
    internal data class Result(
        val url: String,
        val title: String,
        val readyState: String,
        val elementCount: Int,
        /** 跨域 iframe 数（内容受同源策略保护拿不到——如实计数，不静默）。 */
        val crossOriginFrames: Int,
        /** 结构骨架＋正文（正文部分 JS 侧已按 [DOM_TEXT_MAX_CHARS] 截过）。 */
        val domSummary: String,
        val axText: String,
        val domTruncated: Boolean,
        val axTruncated: Boolean,
    )

    /** [clampTotal] 的产物：钳后读数 ＋ 64KB 总闸是否触发。 */
    internal data class Clamped(val result: Result, val totalTruncated: Boolean)

    /**
     * 解析 [evidenceJsScript] 的回包：`\u0001` 分三段（meta／DOM 摘要／AX），
     * meta 按 `\u0002` 分七字段（url、title、元素数、跨域 iframe 数、两个截断位、readyState）。
     * 缺段／数字坏 ⇒ null（调用方如实报「回包解析失败」，不猜）。
     */
    internal fun parse(raw: String?): Result? {
        if (raw == null) return null
        val seg = raw.split('\u0001')
        if (seg.size != 3) return null
        val meta = seg[0].split('\u0002')
        if (meta.size != 7) return null
        val count = meta[2].trim().toIntOrNull() ?: return null
        val cross = meta[3].trim().toIntOrNull() ?: return null
        val domTrunc = when (meta[4].trim()) {
            "1" -> true
            "0" -> false
            else -> return null
        }
        val axTrunc = when (meta[5].trim()) {
            "1" -> true
            "0" -> false
            else -> return null
        }
        return Result(
            url = meta[0],
            title = meta[1],
            readyState = meta[6],
            elementCount = count,
            crossOriginFrames = cross,
            domSummary = seg[1],
            axText = seg[2],
            domTruncated = domTrunc,
            axTruncated = axTrunc,
        )
    }

    /**
     * 64KB 字节级总闸（纯函数）：先保 DOM 摘要、把 AX 砍到半闸以内，剩余预算全给 DOM。
     * 砍用 [cutToBytes] 二分到 UTF-8 字节边界——「单次 ≤64KB」是硬保证，不是估算。
     */
    internal fun clampTotal(r: Result): Clamped {
        if (r.domSummary.utf8Bytes() + r.axText.utf8Bytes() <= MAX_TOTAL_BYTES) {
            return Clamped(r, totalTruncated = false)
        }
        val axCut = cutToBytes(r.axText, MAX_TOTAL_BYTES / 2)
        val domBudget = MAX_TOTAL_BYTES - axCut.utf8Bytes()
        return Clamped(
            r.copy(domSummary = cutToBytes(r.domSummary, domBudget), axText = axCut),
            totalTruncated = true,
        )
    }

    /** 按字节预算二分截断（UTF-8 边界；代理对切中的极端情形编码端归一，只少不多）。 */
    private fun cutToBytes(s: String, budget: Int): String {
        if (budget <= 0) return ""
        if (s.utf8Bytes() <= budget) return s
        var lo = 0
        var hi = s.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (s.substring(0, mid).utf8Bytes() <= budget) lo = mid else hi = mid - 1
        }
        return s.substring(0, lo)
    }

    private fun String.utf8Bytes(): Int = toByteArray(Charsets.UTF_8).size

    /**
     * 模型可见回执（纯函数；进工具层前不再截——64KB 闸已在 [clampTotal] 收过）。
     * 空 content 都带原因行（未加载／跨域主体／无标注），**不许静默空**。
     */
    internal fun render(c: Clamped): String = buildString {
        val r = c.result
        append("页面证据（DOM/属性读取，不依赖前台可见——用户在别的页签也能取）：\n")
        append("url: ").append(r.url.ifBlank { "（空）" }).append('\n')
        append("title: ").append(r.title.ifBlank { "（空）" }).append('\n')
        append("readyState: ").append(r.readyState).append('\n')
        append("元素数: ").append(r.elementCount)
        if (r.crossOriginFrames > 0) {
            append("；跨域 iframe ").append(r.crossOriginFrames)
                .append(" 个（其内容受同源策略保护取不到，已如实计数——若页面主体在其中，本证据会偏薄）")
        }
        append('\n')
        append("DOM 摘要:\n")
        append(
            r.domSummary.ifBlank {
                "（无内容——页面可能还在加载，或可见主体在跨域 iframe 里）"
            },
        )
        if (r.domTruncated) append("\n…（DOM 正文已截到前 $DOM_TEXT_MAX_CHARS 字）")
        append("\n\n可访问性文本:\n")
        append(r.axText.ifBlank { "（无可访问性标注——页面可能没有 aria/alt/placeholder/label）" })
        if (r.axTruncated) append("\n…（可访问性文本已截到前 $AX_TEXT_MAX_CHARS 字）")
        if (c.totalTruncated) {
            append("\n…（单次回包超 ${MAX_TOTAL_BYTES / 1024}KB 总闸：可访问性文本与 DOM 摘要已按字节预算再钳，骨架优先保留）")
        }
    }

    /**
     * 证据取数脚本（**单次执行**）：meta 七字段 `\u0002` 连接、`\u0001` 后跟骨架＋正文、
     * 再 `\u0001` 跟 AX 文本——与 [parseEyeFetch] 的控制符分段同规。
     *
     * 骨架行＝`标签#id 短文本(≤40字)`，行内空白已折叠（不含换行 ⇒ 段内行结构可信）；
     * 正文取 `body.textContent`（渲染无关）；AX 取 aria-label/alt/placeholder/title/value，
     * 缺了退 `textContent`，去重后拼 `role: 标签文本`；跨域 iframe 按 `contentDocument`
     * 不可访问计数（同源的能访问，不计）。
     */
    internal fun evidenceJsScript(): String =
        "(function(){" +
            "var d=document,DOMC=$DOM_TEXT_MAX_CHARS,AXC=$AX_TEXT_MAX_CHARS;" +
            "var sk=[],skn=d.querySelectorAll('h1,h2,h3,h4,h5,h6,p,button,a,input,select,textarea,img,table,tr,form,nav,main,header,footer,section,article,ul,ol,li,label');" +
            "for(var i=0;i<skn.length&&i<$MAX_SKELETON_LINES;i++){var el=skn[i];" +
            "var t=(el.textContent||'').replace(/\\s+/g,' ').trim().slice(0,40);" +
            "sk.push(el.tagName.toLowerCase()+(el.id?('#'+el.id):'')+(t?(' '+t):''));}" +
            "var dt=(d.body?(d.body.textContent||''):'').replace(/\\s+/g,' ').trim();" +
            "var dtr=dt.length>DOMC?1:0;dt=dt.slice(0,DOMC);" +
            "var ax=[],axn=d.querySelectorAll('[aria-label],[role],img[alt],input[placeholder],button,a,select,textarea,label,[title]');" +
            "var seen={};" +
            "for(var i=0;i<axn.length&&ax.length<$MAX_AX_ENTRIES;i++){var el=axn[i];" +
            "var lab=el.getAttribute('aria-label')||el.getAttribute('alt')||el.getAttribute('placeholder')||el.getAttribute('title')||el.value||'';" +
            "if(!lab)lab=(el.textContent||'').replace(/\\s+/g,' ').trim();" +
            "if(!lab)continue;" +
            "var line=(el.getAttribute('role')||el.tagName.toLowerCase())+': '+String(lab).replace(/\\s+/g,' ').trim().slice(0,80);" +
            "if(seen[line])continue;seen[line]=1;ax.push(line);}" +
            "var axt=ax.join('\\n');var atr=0;if(axt.length>AXC){axt=axt.slice(0,AXC);atr=1}" +
            "var cross=0,ifr=d.querySelectorAll('iframe');" +
            "for(var i=0;i<ifr.length;i++){try{if(!ifr[i].contentDocument)cross++;}catch(e){cross++;}}" +
            "return [location.href,d.title||'',d.querySelectorAll('*').length,cross,dtr,atr,String(d.readyState)].join('\\u0002')" +
            "+'\\u0001'+sk.join('\\n')+(sk.length?'\\n':'')+dt+'\\u0001'+axt;})()"
}
