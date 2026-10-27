// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百九十六批 · 生产端适配层（学习器＋执行器的壳）：
//
// - [BrowserTeachDom]：[TeachDom] 的玄览实现——所有页面访问都走**现有的**
//   [BrowserCommands.execJs] 桥（派工单 §1「不新开桥」），JS 是十条哑查询，
//   决策全在 Kotlin 侧（TeachResolve/TeachExecutor）。
// - [TeachRecorder]：示教录制——往页面注入监听（挂 window.__zlTeach 队列），
//   轮询收割事件；只记录不干预（用户正常点/输/翻页）。
// - [TeachReplay]：回放入口（装技能 → 域检查/指纹/门控/自愈 → 审计+取证）。
//
// 第二百零一批 ② 加的一层：[FormFillingTeachDom]——回放里带值的两步（TYPE/SELECT）
// 不再直接往元素上塞字符串，而是走 201 的 `FormFiller`（探针→拒填闸→填→**回读**→校验），
// 跑完出「待发布卡」。`TeachExecutor.kt` 一个字没改：它每步只问一句 `ask(Act(…))`，
// 换掉这一句的实现就等于换掉了动手的方式，而顺序铁律、门控、取证、审计全留在原处。
//
// 线程契约：BrowserCommands.execJs 是同步桥且**禁止主线程调用**——本文件的
// 录制/回放都跑在调用方给的后台协程里，UI 只碰 [TeachSessionUiModel]。
package com.example.zhengdao.baopu

import android.content.Context
import com.example.zhengdao.bridge.BrowserCommands
import com.example.zhengdao.bridge.TeachCaptureBridge
import com.example.zhengdao.bridge.jsStringLiteral
import com.example.zhengdao.core.RunLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import com.example.zhengdao.core.TimeFormats
import com.example.zhengdao.baopu.kernel.BaopuKernel

// ── 页面查询脚本族（全部自包含 IIFE，返回 JSON 字符串）─────────────────────────

/**
 * 十条哑查询的 JS 侧。要点：
 * - 可见性：getBoundingClientRect 宽高都为 0 的元素不算命中（藏着的诱饵不点）；
 * - 可访问名口径与录制一致（aria-labelledby → aria-label → 关联 label → title → 文本）；
 * - 只做「问什么答什么」，不做任何门控——门控在 Kotlin。
 */
internal object TeachScripts {

    /**
     * 各脚本共用的元素工具（snap/accName/隐式 role/可见性）。
     *
     * 201 起 `internal`：表单场景（`FormScripts`）要**同一份**定位口径与可访问名口径，
     * 复制一份十档匹配器等于埋一个「两边慢慢不一样」的坑。可见性放宽，语义一字未改。
     */
    internal const val HELPERS: String = """
function zlT(el){return (el.textContent||'').trim().replace(/\s+/g,' ').slice(0,40)}
function zlV(el){var r=el.getBoundingClientRect();return (r.width>0||r.height>0)}
 function zlN(el){
 var lb=el.getAttribute('aria-labelledby');
 if(lb){var e=document.getElementById(lb);if(e)return zlT(e)}
 var al=el.getAttribute('aria-label');if(al)return al;
 if(el.id){try{var l=document.querySelector('label[for="'+el.id+'"]');if(l)return zlT(l)}catch(e){/* id 不是合法选择器：当无名，走后面的兜底 */}}
 var wl=el.closest('label');if(wl)return zlT(wl);
 var t=el.getAttribute('title');if(t)return t;
 return zlT(el)}
function zlR(el){var r=el.getAttribute('role');if(r)return r.toLowerCase();
 var t=el.tagName,it=el.getAttribute('type')||'';
 if(t=='BUTTON'||t=='SUMMARY')return 'button';
 if(t=='A')return 'link';
 if(t=='SELECT')return 'combobox';
 if(t=='TEXTAREA')return 'textbox';
 if(t=='TH')return 'columnheader';
 if(t=='INPUT')return (it=='checkbox')?'checkbox':(it=='radio')?'radio':(it=='search')?'searchbox':'textbox';
 return ''}
function zlS(el){return {tag:el.tagName,role:zlR(el),name:zlN(el),text:zlT(el),
 aria:el.getAttribute('aria-label')||'',testid:el.getAttribute('data-testid')||'',
 inputType:(el.tagName=='INPUT'?(el.getAttribute('type')||'text'):''),
 inputName:el.getAttribute('name')||'',placeholder:el.getAttribute('placeholder')||'',id:el.id||''}}
"""

    /** 按定位器描述找元素（tier 匹配器；返回可见元素数组）。201 起 internal，供表单场景复用。 */
    internal fun matcherFor(kind: TeachLocatorKind, value: String): String = when (kind) {
        TeachLocatorKind.ROLE_NAME -> {
            val (role, name) = value.split("|", limit = 2).let { it.getOrElse(0) { "" } to it.getOrElse(1) { "" } }
            "Array.prototype.filter.call(document.querySelectorAll('*'),function(el){" +
                "return zlV(el)&&zlR(el)==${jsStringLiteral(role)}&&zlN(el)==${jsStringLiteral(name)}})"
        }
        TeachLocatorKind.TESTID -> "Array.prototype.filter.call(document.querySelectorAll('[data-testid='+${jsStringLiteral(cssAttrValue(value))}+']'),zlV)"
        TeachLocatorKind.ARIA_LABEL -> "Array.prototype.filter.call(document.querySelectorAll('[aria-label='+${jsStringLiteral(cssAttrValue(value))}+']'),zlV)"
        TeachLocatorKind.ELEM_ID -> "Array.prototype.filter.call(document.querySelectorAll('[id='+${jsStringLiteral(cssAttrValue(value))}+']'),zlV)"
        TeachLocatorKind.INPUT_NAME -> "Array.prototype.filter.call(document.querySelectorAll('[name='+${jsStringLiteral(cssAttrValue(value))}+']'),zlV)"
        TeachLocatorKind.PLACEHOLDER -> "Array.prototype.filter.call(document.querySelectorAll('[placeholder='+${jsStringLiteral(cssAttrValue(value))}+']'),zlV)"
        TeachLocatorKind.TEXT ->
            "Array.prototype.filter.call(document.querySelectorAll(" +
                "'button,a,[role=button],[role=link],[role=tab],summary,option,th,label,input[type=button],input[type=submit]')," +
                "function(el){return zlV(el)&&zlT(el)==${jsStringLiteral(value)}})"
        TeachLocatorKind.FORM_CONTEXT -> {
            val (label, secondary) = value.split("|", limit = 2).let { it.getOrElse(0) { "" } to it.getOrElse(1) { "" } }
            "Array.prototype.filter.call(document.querySelectorAll('*'),function(el){if(!zlV(el))return false;" +
                "var sec=zlT(el)==${jsStringLiteral(secondary)}||el.getAttribute('name')==${jsStringLiteral(secondary)}||zlN(el)==${jsStringLiteral(secondary)};" +
                "if(!sec)return false;var c=el.closest('form,td,th,label,fieldset');return !!c&&(c.textContent||'').indexOf(${jsStringLiteral(label)})>=0})"
        }
        TeachLocatorKind.CSS_CLASS ->
            "Array.prototype.filter.call(document.querySelectorAll(" +
                "'." + value.split(".").joinToString(".", transform = ::cssClassLiteral) + "'),zlV)"
        TeachLocatorKind.XPATH ->
            "(function(){try{var n=document.evaluate(${jsStringLiteral(value)},document,null,9,null).singleNodeValue;" +
                "return n&&zlV(n)?[n]:[]}catch(e){return []}})()"
    }

    /** CSS 属性选择器的裸值（不含引号/反斜杠的值才能这样嵌；含的在学习期就被拦）。 */
    private fun cssAttrValue(v: String): String =
        if (v.any { it == '\'' || it == '"' || it == '\\' || it.isWhitespace() }) "'SKIP-IMPOSSIBLE'" else v

    private fun cssClassLiteral(v: String): String =
        if (v.any { it == '\'' || it == '"' || it == '\\' || it.isWhitespace() }) "SKIP-IMPOSSIBLE" else v

    /** Scan：按一条定位器找元素（≤5 个）。 */
    fun scan(loc: TeachLocator): String =
        "(function(){$HELPERS var els=${matcherFor(loc.kind, loc.value)}.slice(0,5);" +
            "return JSON.stringify({hits:els.map(zlS)})})()"

    /** CurrentUrl。 */
    const val CURRENT_URL: String = "(function(){return JSON.stringify({u:location.href})})()"

    /** TextPresent：正文或输入值里出现该文本。 */
    fun textPresent(text: String): String =
        "(function(){var t=${jsStringLiteral(text)};var body=(document.body&&document.body.innerText)||'';" +
            "if(body.indexOf(t)>=0)return JSON.stringify({p:true});" +
            "var ins=document.querySelectorAll('input,select,textarea');" +
            "for(var i=0;i<ins.length;i++){if(((ins[i].value||'')+'').indexOf(t)>=0)return JSON.stringify({p:true})}" +
            "return JSON.stringify({p:false})})()"

    /** RowCount：表格行＋列表项（录制与回放同一条口径）。 */
    const val ROW_COUNT: String =
        "(function(){return JSON.stringify({n:document.querySelectorAll(" +
            "'table tr,[role=row],ol>li,ul>li').length})})()"

    /** HeadersAndButtons：结构指纹探针。 */
    val HEADERS_AND_BUTTONS: String = "(function(){$HELPERS" +
        "var hs=[],bs=[],h=document.querySelectorAll('th');" +
        "for(var i=0;i<h.length&&hs.length<30;i++){var t=(h[i].textContent||'').trim();if(t)hs.push(t.slice(0,30))}" +
        "var b=document.querySelectorAll('button,a,[role=button]');" +
        "for(var i=0;i<b.length&&bs.length<30;i++){if(!zlV(b[i]))continue;var n=zlN(b[i])||zlT(b[i]);if(n)bs.push(String(n).slice(0,30))}" +
        "return JSON.stringify({h:hs,b:bs})})()"

    /** Act：唯一命中才动手；返回执行后的 URL。 */
    fun act(loc: TeachLocator, action: TeachAction, value: String?): String {
        val els = matcherFor(loc.kind, loc.value)
        val doAct = when (action) {
            TeachAction.CLICK -> "el.click();"
            TeachAction.TYPE ->
                "var d=Object.getOwnPropertyDescriptor(Object.getPrototypeOf(el),'value');" +
                    "if(d&&d.set)d.set.call(el,${jsStringLiteral(value ?: "")});else el.value=${jsStringLiteral(value ?: "")};" +
                    "el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));"
            TeachAction.SELECT ->
                "el.value=${jsStringLiteral(value ?: "")};el.dispatchEvent(new Event('change',{bubbles:true}));"
            TeachAction.SCROLL -> "el.scrollIntoView({block:'center'});"
            TeachAction.WAIT -> ""
        }
        return "(function(){$HELPERS var els=$els;" +
            "if(els.length!=1)return JSON.stringify({ok:false,r:'定位命中 '+els.length+' 个，不动手'});" +
            "var el=els[0];$doAct return JSON.stringify({ok:true,u:location.href})})()"
    }

    /** DomSummary：取证摘要（标题＋URL＋行数＋前 12 个可交互元素名）。 */
    val DOM_SUMMARY: String = "(function(){$HELPERS" +
        "var es=document.querySelectorAll('button,a,[role=button]'),ns=[];" +
        "for(var i=0;i<es.length&&ns.length<12;i++){if(zlV(es[i])){var n=zlN(es[i])||zlT(es[i]);if(n)ns.push(String(n).slice(0,20))}}" +
        "return JSON.stringify({t:document.title,u:location.href," +
        "n:document.querySelectorAll('table tr,[role=row],ol>li,ul>li').length,i:ns})})()"

    /**
     * FindBySnapshot（自愈专用）：按语义快照全等找元素，并为每个命中算**最佳唯一定位器**
     * （十档序，逐档验页面唯一）。命中里带 loc 字段（null＝没有唯一锚）。
     */
    fun findBySnapshot(snap: TeachElementSnapshot): String {
        val tag = snap.tag.uppercase()
        val role = snap.role ?: ""
        val name = snap.name ?: ""
        val text = snap.text ?: ""
        return "(function(){$HELPERS" + """
function zlU(el){var r=zlR(el),n=zlN(el),t=zlT(el);
 if(${jsStringLiteral(role)}.length&&r!=${jsStringLiteral(role)})return null;
 if(${jsStringLiteral(name)}.length&&n!=${jsStringLiteral(name)})return null;
 if(${jsStringLiteral(text)}.length&&t!=${jsStringLiteral(text)})return null;
 return (el.tagName==${jsStringLiteral(tag)})?el:null}
function zlDesc(kind,val){return {k:kind,v:val}}
function zlBest(el){
 var c=[];
 var r=zlR(el),n=zlN(el),t=zlT(el);
 if(r&&n)c.push(zlDesc(1,r+'|'+n));
 if(el.getAttribute('data-testid'))c.push(zlDesc(2,el.getAttribute('data-testid')));
 if(el.getAttribute('aria-label'))c.push(zlDesc(3,el.getAttribute('aria-label')));
 var id=el.id||'';if(id&&!(/[-_]\d+$/.test(id))&&id.indexOf(':r')<0)c.push(zlDesc(4,id));
 if(el.getAttribute('name'))c.push(zlDesc(5,el.getAttribute('name')));
 if(el.getAttribute('placeholder'))c.push(zlDesc(6,el.getAttribute('placeholder')));
 if(t)c.push(zlDesc(7,t));
 var cls=[];for(var i=0;i<el.classList.length;i++){var cl=el.classList[i];
  if(cl.length>=2&&cl.length<=30&&!/\d/.test(cl)&&/^[A-Za-z_-]+$/.test(cl))cls.push(cl)}
 if(cls.length)c.push(zlDesc(9,cls.slice(0,3).join('.')));
 for(var i=0;i<c.length;i++){
  var m={1:function(v){var p=v.split('|');return Array.prototype.filter.call(document.querySelectorAll('*'),function(e){return zlV(e)&&zlR(e)==p[0]&&zlN(e)==p[1]})},
   2:function(v){return document.querySelectorAll('[data-testid="'+v+'"]')},
   3:function(v){return document.querySelectorAll('[aria-label="'+v+'"]')},
   4:function(v){return document.querySelectorAll('[id="'+v+'"]')},
   5:function(v){return document.querySelectorAll('[name="'+v+'"]')},
   6:function(v){return document.querySelectorAll('[placeholder="'+v+'"]')},
   7:function(v){return Array.prototype.filter.call(document.querySelectorAll('button,a,[role=button],[role=link],[role=tab],summary,option,th,label,input[type=button],input[type=submit]'),function(e){return zlV(e)&&zlT(e)==v})},
   9:function(v){return document.querySelectorAll('.'+v.split('.').join('.'))}}[c[i].k];
  try{if(m&&m(c[i].v).length==1)return {tier:c[i].k,value:c[i].v}}catch(e){/* 这一档查询抛错：当不唯一，试下一档 */}}
 return null}
var all=document.querySelectorAll('*'),out=[];
for(var i=0;i<all.length&&out.length<3;i++){var m=zlU(all[i]);if(m){var s=zlS(m);s.loc=zlBest(m);out.push(s)}}
return JSON.stringify({hits:out})})()""".trimIndent() + "})()"
    }
}

// ── TeachDom 的玄览实现（经既有 execJs 桥）────────────────────────────────────

/** 解析 execJs 的 `{"ok":true,"result":"…"}` 包装（result 里是查询脚本自己的 JSON）。 */
internal class BrowserTeachDom(private val tabId: Int?) : TeachDom {

    private fun exec(code: String): JSONObject? {
        val raw = BrowserCommands.execJs(code, tabId?.toString())
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if (!o.optBoolean("ok")) return null
        val result = o.optString("result").takeIf { it.isNotBlank() } ?: return null
        return runCatching { JSONObject(result) }.getOrNull()
    }

    override fun ask(query: TeachQuery): TeachReply {
        fun hits(loc: TeachLocator?, snap: TeachElementSnapshot?): List<TeachHit> {
            val code = when {
                loc != null -> TeachScripts.scan(loc)
                snap != null -> TeachScripts.findBySnapshot(snap)
                else -> return emptyList()
            }
            val o = exec(code) ?: return emptyList()
            val arr = o.optJSONArray("hits") ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val h = arr.optJSONObject(i) ?: return@mapNotNull null
                TeachHit(
                    tag = h.optString("tag").uppercase(),
                    role = h.optString("role").takeIf { it.isNotEmpty() },
                    name = h.optString("name").takeIf { it.isNotEmpty() },
                    text = h.optString("text").takeIf { it.isNotEmpty() },
                    ariaLabel = h.optString("aria").takeIf { it.isNotEmpty() },
                    testid = h.optString("testid").takeIf { it.isNotEmpty() },
                    inputType = h.optString("inputType").takeIf { it.isNotEmpty() },
                    inputName = h.optString("inputName").takeIf { it.isNotEmpty() },
                    placeholder = h.optString("placeholder").takeIf { it.isNotEmpty() },
                    inputId = h.optString("id").takeIf { it.isNotEmpty() },
                    healLocator = h.optJSONObject("loc")?.let { locO ->
                        val tier = locO.optInt("tier", 0)
                        val kind = TeachLocatorKind.entries.firstOrNull { it.tier == tier }
                        val v = locO.optString("value")
                        if (kind != null && v.isNotBlank()) TeachLocator(kind, v) else null
                    },
                )
            }
        }

        return when (query) {
            is TeachQuery.Scan -> TeachReply.Hits(hits(query.locator, null))
            is TeachQuery.FindBySnapshot -> TeachReply.Hits(hits(null, query.snapshot))
            TeachQuery.CurrentUrl -> exec(TeachScripts.CURRENT_URL)
                ?.let { TeachReply.Url(it.optString("u")) }
                ?: TeachReply.Failed("execJs 失败（CurrentUrl）")
            is TeachQuery.TextPresent -> exec(TeachScripts.textPresent(query.text))
                ?.let { TeachReply.Flag(it.optBoolean("p")) }
                ?: TeachReply.Failed("execJs 失败（TextPresent）")
            TeachQuery.RowCount -> exec(TeachScripts.ROW_COUNT)
                ?.let { TeachReply.Count(it.optInt("n")) }
                ?: TeachReply.Failed("execJs 失败（RowCount）")
            TeachQuery.HeadersAndButtons -> exec(TeachScripts.HEADERS_AND_BUTTONS)
                ?.let { TeachReply.Anchors(readStrings(it, "h"), readStrings(it, "b")) }
                ?: TeachReply.Failed("execJs 失败（HeadersAndButtons）")
            is TeachQuery.Act -> exec(TeachScripts.act(query.locator, query.action, query.value))
                ?.let {
                    if (it.optBoolean("ok")) {
                        TeachReply.Acted(ok = true, reason = null, urlAfter = it.optString("u"))
                    } else {
                        TeachReply.Acted(ok = false, reason = it.optString("r"), urlAfter = "")
                    }
                }
                ?: TeachReply.Failed("execJs 失败（Act）")
            TeachQuery.DomSummary -> exec(TeachScripts.DOM_SUMMARY)
                ?.let {
                    TeachReply.Summary(
                        "title=${it.optString("t")} rows=${it.optInt("n")} " +
                            "interactive=[${readStrings(it, "i").joinToString("、")}]",
                    )
                }
                ?: TeachReply.Failed("execJs 失败（DomSummary）")
        }
    }

    private fun readStrings(o: JSONObject, key: String): List<String> {
        val arr = o.optJSONArray(key) ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() } }
    }
}

// ── 示教录制（点击即回传＋轮询兜底；只记录不干预）─────────────────────────────
//
// 第二百二十六批 G2：原实现只有「每文档队列＋600ms 轮询」——点击触发导航后旧文档连队列
// 一起销毁，下一轮收割拿到新文档的空队列 ⇒ 跨导航事件必丢（222 实测 example.com→iana.org
// 全程 0 步）。现在两路并存：
//   ① 即时路：@JavascriptInterface 桥（[TeachCaptureBridge]），事件当场送原生累积；
//   ② 兜底路：600ms 轮询照旧（桥不可用／被站点拦时仍有事件）。
// 两路按 `doc#seq` 去重；步骤顺序按「文档首次到达序＋文档内序号」重排——到达先后不算数。

/**
 * 两路事件流的合并缓冲（纯 Kotlin：JVM 直测去重、排序与「这一步之后到了哪一页」）。
 *
 * 为什么不在到达时就算 before/after：两条路到达顺序不保证（立即路通常先到，轮询可能补
 * 一批更早的），只有按文档序＋序号重排之后，前后关系才是页面上真实发生的顺序。
 * 「文档序」＝该文档 URL 第一次被看到时的页序（`onPageStarted`／收割谁先报都算），
 * **不是**这条事件什么时候到——否则即时路先送到新文档时，旧文档那条会被排到后面。
 */
internal class TeachEventBuffer(private val cap: Int = 500) {

    private data class Entry(val docOrder: Int, val seq: Int, val event: TeachRawEvent, val rows: Int)

    private val byKey = LinkedHashMap<String, Entry>()
    private val docOrder = HashMap<String, Int>()
    /** 页序：URL → 第一次被看到时的次序（`onPageStarted` 与收割都会报，谁先到都算）。 */
    private val pageOrder = LinkedHashMap<String, Int>()
    private var nextPageOrder = 0
    private var lastPageUrl = ""
    private var navSeen = false
    private var rowsNow = 0

    val size: Int @Synchronized get() = byKey.size
    val sawNavigation: Boolean @Synchronized get() = navSeen

    private fun orderOf(url: String): Int = pageOrder.getOrPut(url) { nextPageOrder++ }

    /** 起点页（不算一次导航）。 */
    @Synchronized
    fun seedStart(url: String) {
        if (url.isNotBlank() && lastPageUrl.isBlank()) {
            lastPageUrl = url
            orderOf(url)
        }
    }

    /** 收割／导航看到的页面地址：与上一页不同才算一次导航。 */
    @Synchronized
    fun notePage(url: String, rows: Int = -1) {
        if (url.isNotBlank()) {
            when {
                lastPageUrl.isBlank() -> lastPageUrl = url
                url != lastPageUrl -> {
                    navSeen = true
                    lastPageUrl = url
                }
            }
            orderOf(url)
        }
        if (rows >= 0) rowsNow = rows
    }

    /**
     * 收一条；同一 `doc#seq` 第二次到达 ⇒ 丢掉（两路去重）。
     *
     * 文档序**按页序算，不按这条事件什么时候到**：即时路可能先送到新文档的事件，
     * 轮询随后才补回旧文档那条——按到达先后排会把两步的顺序弄反（222 的根因之一）。
     */
    @Synchronized
    fun add(doc: String, seq: Int, event: TeachRawEvent): Boolean {
        val key = doc.ifBlank { "-" } + "#" + seq
        if (byKey.containsKey(key)) return false
        val order = docOrder.getOrPut(doc.ifBlank { "-" }) { orderOf(event.url) }
        byKey[key] = Entry(order, seq, event, rowsNow)
        if (byKey.size > cap) byKey.remove(byKey.keys.first())
        return true
    }

    /**
     * 出最终事件流。`beforeUrl`＝上一步所在页，`afterUrl`＝下一步所在页（最后一步用收尾页）——
     * 这样「点击触发导航」那一步仍会带上 URL_CHANGED 的 expect（196 的语义一字不丢）。
     * 行数同理：before＝上一步时的行数快照，after＝下一步的（最后一步用收尾快照）。
     */
    @Synchronized
    fun snapshot(): List<TeachRawEvent> {
        val sorted = byKey.values.sortedWith(compareBy({ it.docOrder }, { it.seq }))
        if (sorted.isEmpty()) return emptyList()
        val tailUrl = lastPageUrl.ifBlank { sorted.last().event.url }
        return sorted.mapIndexed { i, e ->
            val before = if (i == 0) e.event.url else sorted[i - 1].event.url
            val after = if (i < sorted.size - 1) sorted[i + 1].event.url else tailUrl
            val beforeRows = if (i == 0) 0 else sorted[i - 1].rows
            val afterRows = if (i < sorted.size - 1) sorted[i + 1].rows else rowsNow
            e.event.copy(
                beforeUrl = before,
                afterUrl = after,
                beforeRows = beforeRows,
                afterRows = afterRows,
            )
        }
    }
}

/** 停止示教的三种结局（负例「0 步却当成功」的靶子：空就是空，还得说清为什么空）。 */
internal enum class TeachStopKind { DRAFT, EMPTY_NO_EVENTS, EMPTY_NAV_ONLY }

internal object TeachStopRules {
    /** 有步骤 ⇒ 出卡；没步骤但见过导航 ⇒ 说「录到导航」；否则说「没有录到操作」。 */
    fun kind(stepCount: Int, sawNavigation: Boolean): TeachStopKind = when {
        stepCount > 0 -> TeachStopKind.DRAFT
        sawNavigation -> TeachStopKind.EMPTY_NAV_ONLY
        else -> TeachStopKind.EMPTY_NO_EVENTS
    }
}

/** [TeachRecorder.finish] 的产物。 */
internal data class TeachCollected(
    val events: List<TeachRawEvent>,
    val sawNavigation: Boolean,
)

/** 录制会话采到的原始事件流（两路合并；stop 时一次性移交）。 */
internal class TeachRecorder(
    private val tabId: Int?,
    private val scope: CoroutineScope,
    private val pollMs: Long = 600,
) {
    var job: Job? = null
        private set

    private val buffer = TeachEventBuffer()

    /** 即时回传桥（没有标签时为 null ⇒ 只剩轮询兜底）。 */
    private var capture: TeachCaptureBridge.Handle? = null

    /** 会话起点页事实（指纹原料）。 */
    internal var startFacts: TeachPageFacts? = null
        private set

    internal val rawCount: Int get() = buffer.size

    private var lastUrl: String = ""
    private var lastRows: Int = 0
    private var syntheticSeq = 0

    /** 注入＋收割一体的脚本（幂等：没有队列就装监听；装完把这批事件带走）。 */
    private fun harvestScript(): String = "(function(){\n" + TeachRecorderJs.INJECT + """
var t=window.__zlTeach;var q=t?t.q:[];
if(t)t.q=[];
var rows=document.querySelectorAll('table tr,[role=row],ol>li,ul>li').length;
return JSON.stringify({u:location.href,r:rows,e:q.slice(0,50)})})()
"""

    fun start(onError: (String) -> Unit) {
        if (job?.isActive == true) return
        // 即时路先挂（主线程：解析标签、挂 JS 接口），再起收割循环当兜底。
        capture = TeachCaptureBridge.attach(
            tabId = tabId,
            injectJs = TeachRecorderJs.SCRIPT,
            onEvent = { json -> ingest(json, null) },
            onNavigation = { url -> buffer.notePage(url) },
        )
        job = scope.launch(Dispatchers.IO) {
            // 起点页事实（指纹与 startUrl）
            runCatching {
                val o = JSONObject(BrowserCommands.execJs(FACTS_SCRIPT, tabId?.toString()))
                if (o.optBoolean("ok")) {
                    val r = JSONObject(o.optString("result"))
                    startFacts = TeachPageFacts(
                        url = r.optString("u"),
                        headers = jsonArr(r.optJSONArray("h")),
                        buttons = jsonArr(r.optJSONArray("b")),
                    )
                    lastUrl = r.optString("u")
                    lastRows = r.optInt("r")
                    buffer.seedStart(lastUrl)
                }
            }
            var failures = 0
            while (!Thread.currentThread().isInterrupted) {
                val ok = runCatching { harvestOnce() }.getOrElse { false }
                if (ok) {
                    failures = 0
                } else {
                    failures++
                    // 有即时路时轮询失败不打扰用户（桥还在收）；没有桥才报（且只报一次）。
                    if (capture == null && failures == 1) {
                        onError("示教收割失败（页面可能已关闭或还在加载）")
                    }
                }
                delay(pollMs)
            }
        }
    }

    private fun harvestOnce(): Boolean {
        val rawJson = BrowserCommands.execJs(harvestScript(), tabId?.toString())
        val o = runCatching { JSONObject(rawJson) }.getOrNull() ?: return false
        if (!o.optBoolean("ok")) return false
        val r = runCatching { JSONObject(o.optString("result")) }.getOrNull() ?: return false
        val url = r.optString("u")
        val rows = r.optInt("r")
        buffer.notePage(url, rows)
        val events = r.optJSONArray("e") ?: JSONArray()
        for (i in 0 until events.length()) {
            val ev = events.optJSONObject(i) ?: continue
            ingest(ev.toString(), url)
        }
        lastUrl = url
        lastRows = rows
        return true
    }

    /**
     * 一条事件 JSON → 缓冲（两路共用；`doc#seq` 去重）。
     * 即时路与轮询路的入参形状完全一样（同一个 INJECT 产出），所以转换只有这一处。
     */
    private fun ingest(json: String, fallbackUrl: String?): Boolean {
        val ev = runCatching { JSONObject(json) }.getOrNull() ?: return false
        val action = TeachAction.fromWire(ev.optString("a")) ?: return false
        val el = ev.optJSONObject("el") ?: return false
        val url = ev.optString("url").ifBlank { fallbackUrl.orEmpty() }
        // 旧脚本（无 s）不该出现；真出现了也不能全挤到同一个 key —— 给个本地序号。
        val seq = if (ev.has("s")) ev.optInt("s") else ++syntheticSeq
        return buffer.add(
            doc = ev.optString("d"),
            seq = seq,
            event = TeachRawEvent(
                action = action,
                url = url,
                element = toElementInfo(el),
                value = ev.optString("value").takeIf { it.isNotEmpty() },
                // before/after 由 snapshot 按真实顺序算（见 TeachEventBuffer 的 KDoc）
                beforeUrl = url,
                afterUrl = url,
                beforeRows = 0,
                afterRows = 0,
            ),
        )
    }

    /** 元素语义面（录制口径；与 TeachScripts 的可访问名规则一致）。 */
    private fun toElementInfo(el: JSONObject): TeachElementInfo = TeachElementInfo(
        tag = el.optString("tag").uppercase(),
        role = el.optString("role").takeIf { it.isNotEmpty() },
        name = el.optString("name").takeIf { it.isNotEmpty() },
        text = el.optString("text").takeIf { it.isNotEmpty() },
        ariaLabel = el.optString("aria").takeIf { it.isNotEmpty() },
        testid = el.optString("testid").takeIf { it.isNotEmpty() },
        elemId = el.optString("id").takeIf { it.isNotEmpty() },
        inputName = el.optString("inputName").takeIf { it.isNotEmpty() },
        placeholder = el.optString("placeholder").takeIf { it.isNotEmpty() },
        inputType = el.optString("inputType").takeIf { it.isNotEmpty() },
        formLabel = el.optString("formLabel").takeIf { it.isNotEmpty() },
        classes = jsonArr(el.optJSONArray("classes")),
        xpath = el.optString("xpath").takeIf { it.isNotEmpty() },
        options = run {
            val oa = el.optJSONArray("options") ?: JSONArray()
            (0 until oa.length()).mapNotNull { i ->
                val o = oa.optJSONObject(i) ?: return@mapNotNull null
                val v = o.optString("v")
                if (v.isBlank()) null else TeachOption(v, o.optString("t").ifBlank { v })
            }
        },
    )

    /** 收尾：停循环 → 尾批兜底收割 → 出事件流 → 摘桥。阻塞，调用方放 IO。 */
    fun finish(): TeachCollected {
        job?.cancel()
        job = null
        runCatching { harvestOnce() }
        val events = buffer.snapshot()
        val nav = buffer.sawNavigation
        capture?.detach()
        capture = null
        return TeachCollected(events, nav)
    }

    private fun jsonArr(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() } }
    }

    private companion object {
        val FACTS_SCRIPT: String = "(function(){" + TeachRecorderJs.FACTS + "})()"
    }
}

/** 录制注入脚本的原始字符串（[TeachRecorder] 拼收割／重装脚本用）。 */
internal object TeachRecorderJs {

    /** 整段注入（IIFE）：跨导航时由桥重装；也可单独执行。 */
    val SCRIPT: String get() = "(function(){" + INJECT + "})()"

    /**
     * 注入的监听（挂 window.__zlTeach）。
     *
     * 第二百二十六批的三处改动：
     * ① `push` 先把事件 `emit` 给原生（`window.__zdTeachBridge`），会话不再依赖文档存活；
     * ② 事件带 `d`（文档 id）与 `s`（文档内单调序号），原生按 `d#s` 去重；
     * ③ 队列改成 `window.__zlTeach.q` 现取现推 —— 旧写法把数组抓进闭包，收割把
     *    `__zlTeach.q` 换成新数组后闭包还往旧数组里推，**第一批之后的事件再也没被收到过**。
     */
    val INJECT: String = """

if(!window.__zlTeach){
 var doc=Date.now().toString(36)+Math.random().toString(36).slice(2,8);
 function txt(el){return (el.textContent||'').trim().replace(/\s+/g,' ').slice(0,40)}
 function iRole(el){var r=el.getAttribute('role');if(r)return r.toLowerCase();
  var t=el.tagName,it=el.getAttribute('type')||'';
  if(t=='BUTTON'||t=='SUMMARY')return 'button';if(t=='A')return 'link';
  if(t=='SELECT')return 'combobox';if(t=='TEXTAREA')return 'textbox';if(t=='TH')return 'columnheader';
  if(t=='INPUT')return (it=='checkbox')?'checkbox':(it=='radio')?'radio':(it=='search')?'searchbox':'textbox';
  return ''}
 function accName(el){var lb=el.getAttribute('aria-labelledby');
  if(lb){var e=document.getElementById(lb);if(e)return txt(e)}
  var al=el.getAttribute('aria-label');if(al)return al;
  if(el.id){try{var l=document.querySelector('label[for="'+el.id+'"]');if(l)return txt(l)}catch(e){/* id 不是合法选择器：当无名，走后面的兜底 */}}
  var wl=el.closest('label');if(wl)return txt(wl);
  var t=el.getAttribute('title');if(t)return t;return txt(el)}
 function snap(el){var cls=[];for(var i=0;i<el.classList.length;i++){cls.push(el.classList[i])}
  var xp='';var n=el;var parts=[];
  while(n&&n.nodeType==1&&n!=document.body){var idx=1,sib=n.previousElementSibling;
   while(sib){if(sib.tagName==n.tagName)idx++;sib=sib.previousElementSibling}
   parts.unshift(n.tagName.toLowerCase()+'['+idx+']');n=n.parentElement}
  xp='/html/body'+(parts.length?'/'+parts.join('/'):'');
 var c=el.closest('form,td,th,label,fieldset');var fl='';
 if(c){var ct=(c.tagName=='LABEL'||c.tagName=='TH')?txt(c):(c.getAttribute('aria-label')||'');fl=ct.slice(0,40)}
 var ops=[];
 if(el.tagName=='SELECT'&&el.options){for(var oi=0;oi<el.options.length&&ops.length<40;oi++){
  ops.push({v:el.options[oi].value||'',t:((el.options[oi].text||'').trim()).slice(0,30)})}}
 return {tag:el.tagName,role:iRole(el),name:accName(el),text:txt(el),
  aria:el.getAttribute('aria-label')||'',testid:el.getAttribute('data-testid')||'',
  id:el.id||'',inputName:el.getAttribute('name')||'',placeholder:el.getAttribute('placeholder')||'',
  inputType:(el.tagName=='INPUT'?(el.getAttribute('type')||'text'):''),
  formLabel:fl,classes:cls.slice(0,8),xpath:xp,options:ops}}
 function push(a,el,v){var t=window.__zlTeach;if(!t)return;var ev={a:a,url:location.href,el:snap(el),value:v||'',d:t.doc,s:++t.seq};t.q.push(ev);try{var b=window.__zdTeachBridge;if(b&&b.emit){b.emit(JSON.stringify(ev))}}catch(e){/* 桥不在或 emit 抛错：轮询兜底照收，不打断页面 */}}
 document.addEventListener('click',function(e){
  var el=e.target.closest?e.target.closest('button,a,[role=button],input,select,summary,label,[onclick]'):null;
  if(!el)el=e.target;if(el&&el.nodeType==1)push('click',el,'')},true);
 document.addEventListener('change',function(e){
  var el=e.target;if(!el||el.nodeType!=1)return;
  var t=el.tagName;
  if(t=='SELECT')push('select',el,el.value);
  else if(t=='TEXTAREA'||(t=='INPUT'&&(el.getAttribute('type')=='text'||el.getAttribute('type')=='search'||!el.getAttribute('type'))))push('type',el,el.value)},true);
 var lastScroll=0;
 document.addEventListener('scroll',function(e){
  var now=Date.now();if(now-lastScroll<800)return;lastScroll=now;
  var el=e.target;if(el&&el.nodeType==1)push('scroll',(el==document||el==document.body||el==document.documentElement)?document.body:el,'')},true);
 window.__zlTeach={q:[],doc:doc,seq:0};
}
"""

    /** 起点页事实（url＋行数＋表头/按钮集合）。 */
    val FACTS: String = """
var hs=[],bs=[],h=document.querySelectorAll('th');
for(var i=0;i<h.length&&hs.length<30;i++){var t=(h[i].textContent||'').trim();if(t)hs.push(t.slice(0,30))}
var b=document.querySelectorAll('button,a,[role=button]');
for(var i=0;i<b.length&&bs.length<30;i++){var n=(b[i].getAttribute('aria-label')||b[i].textContent||'').trim();if(n)bs.push(n.slice(0,30))}
return JSON.stringify({u:location.href,r:document.querySelectorAll('table tr,[role=row],ol>li,ul>li').length,h:hs,b:bs})
"""
}

// ── 会话控制（UI 用：开始/停止/存/回放）───────────────────────────────────────

/** UI 侧的可观察状态（Compose 持有；动作跑在 [scope]）。 */
internal class TeachSessionUiModel(
    val scope: CoroutineScope,
    val context: Context,
    val tabId: () -> Int?,
    val projectRoot: () -> File,
    val globalRoot: () -> File,
    val personaFiles: () -> PersonaFiles,
) {
    var recording: Boolean = false
        private set
    var stepCount: Int = 0
        private set
    var draft: TeachSkillDraft? = null
        private set

    private var recorder: TeachRecorder? = null

    /** 到目前为止录到的原始事件数（UI 指示条轮询用；两路合并后的条数）。 */
    fun recordedSoFar(): Int = recorder?.rawCount ?: 0

    /** 开始示教（失败给一句人话）。 */
    fun start(onError: (String) -> Unit) {
        if (recording) return
        val rec = TeachRecorder(tabId(), scope)
        recorder = rec
        recording = true
        stepCount = 0
        rec.start(onError)
    }

    /**
     * 结束示教 → 出草稿（不落盘；确认在 [save]）。
     *
     * 第二百二十六批：回调多一个 [TeachStopKind]——「0 步」不再一律说"没有录到操作"：
     * 见过导航就说「录到了导航但没有可复用的操作」（222 验收里 example.com→iana.org 正是
     * 这种：页面明明跳了，用户却被告知"没录到任何操作"）。
     */
    fun stop(onDone: (TeachStopKind, TeachSkillDraft?) -> Unit) {
        val rec = recorder ?: return
        recorder = null
        recording = false
        scope.launch {
            val collected = withContext(Dispatchers.IO) { rec.finish() }
            val facts = rec.startFacts ?: TeachPageFacts("", emptyList(), emptyList())
            val d = if (collected.events.isEmpty()) null else TeachLearner.buildDraft(
                events = collected.events,
                name = suggestName(facts.url),
                purpose = "在 ${facts.url.substringAfter("//").substringBefore('/')} 上重复示教的操作",
                startFacts = facts,
                nowMs = System.currentTimeMillis(),
            )
            val kind = TeachStopRules.kind(d?.skill?.steps?.size ?: 0, collected.sawNavigation)
            stepCount = d?.skill?.steps?.size ?: 0
            draft = if (kind == TeachStopKind.DRAFT) d else null
            withContext(Dispatchers.Main) { onDone(kind, draft) }
        }
    }

    /** 确认保存（走 TeachReplayStore → 184 写口）；结果一句话回调。 */
    fun save(skill: TeachSkill, warnings: List<String>, onDone: (String) -> Unit) {
        val d = TeachSkillDraft(skill, warnings, TeachLearner.summarize(skill, warnings))
        draft = null
        scope.launch(Dispatchers.IO) {
            val r = TeachReplayStore.saveConfirmed(
                draft = d,
                projectRoot = projectRoot(),
                globalRoot = globalRoot(),
                personaFiles = personaFiles(),
                stampText = com.example.zhengdao.core.TimeFormats.fileStamp(),
            )
            val msg = if (r.written != null) {
                "已保存技能「${skill.name}」" + (r.notice?.let { "（$it）" } ?: "")
            } else {
                r.reason ?: "保存失败"
            }
            withContext(Dispatchers.Main) { onDone(msg) }
        }
    }

    /** 放弃草稿（一个字节不写）。 */
    fun discard() {
        draft = null
    }

    /** 回放一个已存技能（给接线层用；UI 本批不挂入口）。 */
    fun replay(name: String, workspace: File, onDone: (TeachRunReport) -> Unit) {
        scope.launch(Dispatchers.IO) {
            val report = TeachReplay.replaySkill(
                context = context,
                projectRoot = projectRoot(),
                workspace = workspace,
                name = name,
                tabId = tabId(),
            )
            withContext(Dispatchers.Main) { onDone(report) }
        }
    }

    /**
     * 第二百批：**带参数**回放——一句话进，回答一句出（取不到就反问，一个页面动作都不做）。
     * 判据全在 [TeachParams.plan]（纯函数，JVM 直测），这里只负责挪到后台线程。
     */
    fun replayWith(name: String, sentence: String, workspace: File, onDone: (TeachParamRun) -> Unit) {
        scope.launch(Dispatchers.IO) {
            val run = replaySkillWithParams(
                context = context,
                projectRoot = projectRoot(),
                workspace = workspace,
                name = name,
                sentence = sentence,
                tabId = tabId(),
            )
            withContext(Dispatchers.Main) { onDone(run) }
        }
    }

    /** 第二百批：这条技能的参数表（编辑器/回放前给界面看；没有 ⇒ 空表）。 */
    fun paramsOf(name: String, onDone: (List<TeachParam>) -> Unit) {
        scope.launch(Dispatchers.IO) {
            val p = TeachReplayStore.load(projectRoot(), name)?.params.orEmpty()
            withContext(Dispatchers.Main) { onDone(p) }
        }
    }

    private fun suggestName(url: String): String {
        val host = TeachSafety.hostOf(url) ?: "网页"
        return "$host-流程"
    }
}

// ── 第二百零一批 ②：回放遇表单步 ⇒ 走 201 的 FormFiller ──────────────────────

/**
 * 回放时的「动手」装饰器：196 执行器每步只问一句 `ask(Act(locator, action, value))`，
 * 本类把 **TYPE/SELECT**（带值的两支）换成 201 的 [FormFiller]——
 * 探针 → 拒填安全闸 → 填 → **回读** → 校验；其余查询原样透传。
 *
 * 为什么装饰而不改执行器：`TeachExecutor.kt` 不归本单（红线），而顺序铁律、门控、自愈、
 * 取证、审计都在它里面——换掉动手的实现，那一条链一步不少。
 *
 * 四条口径：
 * · **只接带值的两支**：CLICK/SCROLL/WAIT 一律透传。本单不许放宽拒点名单，「不点创建」
 *   在 196 那一侧已由 [TeachSafety.refuseClickReason] 兜住，表单侧再由 [FormDom] 没有
 *   点击通道兜住——两道都不动。
 * · **判据不重写**：控件是什么由 [TeachFormRules.classify] 判，值对不对由
 *   [TeachFormRules.check] 判（走 [FormFiller.fillField]），定位口径复用同一套
 *   [TeachLocator]；本类只做「这一步的目标 → 一条 201 的字段」这一件翻译。
 * · **回读不一致 ⇒ `Acted(ok=false)`**：执行器据此判这一步失败并停在玄览。228-C 会先复核
 *   指纹、再滚入视口重试一次——重试＝把探针→拒填→填→回读整条再走一遍；填同一个值进同一个
 *   格子是幂等的，不会多点任何东西。
 * · **动手之前被拒也留一行红的**：形状变了／探针说是提交类或凭证框这类「还没碰就被拒」，
 *   除了把原因串交出去，还要在 [formOutcomes] 里留一行——这一趟没跑完，卡上只印那几格绿的
 *   就等于把「停在哪一格」藏起来了（逐张核对的人看的正是这张卡）。
 */
internal class FormFillingTeachDom(
    private val delegate: TeachDom,
    private val formDom: FormDom,
) : TeachDom {

    /** 目标元素 → 一次填表结局（自愈后同一格重填 ⇒ 后一次覆盖前一次：留的是页面上的最终状态）。 */
    private val outcomes = LinkedHashMap<String, FieldOutcome>()

    /** 这次回放走过的表单步结局（出「待发布卡」的原料；执行器不读它）。 */
    val formOutcomes: List<FieldOutcome> get() = outcomes.values.toList()

    /** 最后一次看到的页面地址（卡的「打开页面」落点；回放不改导航，这里只是抄一手）。 */
    var lastUrl: String = ""
        private set

    override fun ask(query: TeachQuery): TeachReply {
        val reply = when (query) {
            is TeachQuery.Act -> when (query.action) {
                TeachAction.TYPE, TeachAction.SELECT -> fillFormField(query.locator, query.action, query.value)
                else -> delegate.ask(query)
            }
            else -> delegate.ask(query)
        }
        if (reply is TeachReply.Url) lastUrl = reply.url
        return reply
    }

    /** 「这一步要往格子里塞一个值」——改成 201 的填法，并只认回读。 */
    private fun fillFormField(loc: TeachLocator, action: TeachAction, raw: String?): TeachReply {
        val value = raw?.takeIf { it.isNotBlank() }
            ?: return actFailed("这一步没有要填的值（技能不完整）")

        val picked = pickField(loc, action)
        val field = when (picked) {
            is FieldPick.Refused -> return refuseStep(loc, action, value, picked)
            is FieldPick.Field -> picked.field
        }
        val op = TeachFormRules.resolveOp(field, value)
            ?: if (field.control == FormControl.SELECT || field.control == FormControl.RADIO) {
                // 选项表不在探针面上（走的是「认不回模板、只按页面事实判」那一支）时按原值塞：
                // 回放的值本来就是页面要的 value——录制取的是 `el.value`，200 的枚举取值
                // 也在这里之前就把用户说的中文映射回了 value。选不中由 fill 的 JS 拒、
                // 再由 [TeachFormRules.check] 回读拦。
                TeachFormRules.FormOp(field, value, value)
            } else {
                return refuseStep(
                    loc, action, value,
                    FieldPick.Refused("这个值落不进「${field.label.ifBlank { field.key }}」", field),
                )
            }

        val outcome = FormFiller(formDom).fillField(op)
        outcomes[loc.describe()] = outcome
        if (!outcome.ok) {
            // 原因串原样交出去：[FieldOutcome.reason] 两种来源都写清了——过闸前被拒（「密码框不填…」）
            // 还是填完回读不一致（「回读不一致」/「下拉没选中…」）。执行器据此判这一步失败。
            return actFailed(outcome.reason ?: "回读不一致")
        }
        val url = (delegate.ask(TeachQuery.CurrentUrl) as? TeachReply.Url)?.url ?: lastUrl
        return TeachReply.Acted(ok = true, reason = null, urlAfter = url)
    }

    private fun actFailed(reason: String): TeachReply.Acted =
        TeachReply.Acted(ok = false, reason = reason, urlAfter = "")

    /**
     * 动手之前就被拒（形状变了／是提交类／是凭证框）。
     *
     * 除了把原因串交给执行器（这一步判失败、停在玄览），还必须在结局里留一行红的：
     * 这一趟没跑完，卡上若只有那几格绿的，就等于把「停在哪一格」藏起来了——
     * 逐张核对的人看的正是这张卡。
     */
    private fun refuseStep(
        loc: TeachLocator,
        action: TeachAction,
        value: String,
        picked: FieldPick.Refused,
    ): TeachReply {
        val field = picked.field
        outcomes[loc.describe()] = FieldOutcome(
            key = field?.key ?: loc.value,
            label = field?.label?.takeIf { it.isNotBlank() }
                ?: picked.label.takeIf { it.isNotBlank() }
                ?: loc.value,
            // 卡面不印这一项，但 FieldOutcome 要它：认不出控件时按这一步录的动作给个名目
            control = field?.control
                ?: if (action == TeachAction.SELECT) FormControl.SELECT else FormControl.SINGLE_LINE,
            blockIndex = field?.blockIndex ?: 0,
            ok = false,
            expected = value,
            actual = "",
            reason = picked.reason,
        )
        return actFailed(picked.reason)
    }

    // ── 「这一步的目标」→「201 的一条字段」────────────────────────────────────

    private sealed class FieldPick {
        data class Field(val field: FormField) : FieldPick()

        /** 拒的理由给人看；[field]/[label] 是页面对这一格的认知（拿不到就留空）。 */
        data class Refused(
            val reason: String,
            val field: FormField? = null,
            val label: String = "",
        ) : FieldPick()
    }

    /**
     * 先按「当前页扫一遍 ＋ 定位器对得上」认——认回来就带页面标签、选项表、重复块的段号，
     * 判据最全（与 224 的 `form_fill` 同一个来源：[TeachFormRules.buildFields]）。
     *
     * 认不回（自愈后的新定位器、或这一格被扫描面排除）退到**探针事实**：只问它是什么，
     * 再由 [TeachFormRules.classify] 分类。两条路都拿不到、或它不是可填控件 ⇒ 拒，不动手。
     */
    private fun pickField(loc: TeachLocator, action: TeachAction): FieldPick {
        val fromPage = formDom.scan()?.let { cands ->
            val scan = TeachFormRules.buildFields(cands)
            (scan.fields + scan.blocks.flatMap { it.fields }).firstOrNull { loc in it.locators }
        }
        if (fromPage != null) {
            val mismatch = controlVsAction(fromPage.control, action, fromPage.label.ifBlank { fromPage.key })
            if (mismatch != null) return FieldPick.Refused(mismatch, fromPage)
            return FieldPick.Field(fromPage)
        }

        val probe = (formDom.probe(listOf(loc)) as? FormReply.Probed)?.probe
            ?: return FieldPick.Refused("探不到这一格（页面没响应，或它不在页面上）", label = loc.value)
        if (!probe.found) {
            return FieldPick.Refused(
                "页面里找不到这一格（${probe.reason ?: "定位器已失效"}）",
                label = probe.text ?: probe.placeholder ?: probe.inputName ?: loc.value,
            )
        }
        // 认不回模板时只有探针这一份事实：分类照 [classify] 的口径判，判不进去（不是可填控件、
        // 像提交类、是密码/验证码）就拒——停下问人比塞错格子好。
        // 注：单选在这一路只可能来自手改的技能：录制端的 `change` 只推 SELECT／TEXTAREA／
        // INPUT(text|search|无 type)（[TeachScripts] 里那条监听），radio 的改动只留成一个
        // CLICK 步，而 CLICK 归 196 的拒点名单管，不走这里。
        val label = probe.text ?: probe.placeholder ?: probe.inputName ?: loc.value
        val cls = TeachFormRules.classify(candidateOf(probe))
        val control = when (cls) {
            is TeachFormRules.Classified.Accepted -> cls.control
            is TeachFormRules.Classified.Excluded -> return FieldPick.Refused(cls.reason, label = label)
        }
        val asField = FormField(
            key = loc.describe(),
            label = label,
            control = control,
            locators = listOf(loc),
            inputType = probe.inputType,
            inputName = probe.inputName,
            placeholder = probe.placeholder,
        )
        controlVsAction(control, action, label)?.let { return FieldPick.Refused(it, asField) }
        return FieldPick.Field(asField)
    }

    /** 录的是下拉、当前页面上它成了输入框（或反过来）⇒ 不猜（页面改了形状，这一步该重教）。 */
    private fun controlVsAction(control: FormControl, action: TeachAction, label: String): String? =
        when {
            action == TeachAction.SELECT && control != FormControl.SELECT && control != FormControl.RADIO ->
                "这一步录的是下拉，当前页面上「$label」不是下拉（是 $control）——页面形状变了，请重新教一遍"

            action == TeachAction.TYPE && control == FormControl.SELECT ->
                "这一步录的是输入框，当前页面上「$label」是下拉——页面形状变了，请重新教一遍"

            else -> null
        }

    /**
     * 探针面 → [FormCandidate]（只为复用 [TeachFormRules.classify] 的分类判据）。
     *
     * `disabled/readOnly` 探针不带 ⇒ 只能报 false；这两条在回放这一路不是红线（填得进去
     * 只读框会把值留在页面上，但回读校验读的是页面真值，且端口没有点击通道），
     * 在 `form_fill` 那一路仍由扫描期排除兜住。
     */
    private fun candidateOf(probe: FormProbe): FormCandidate = FormCandidate(
        tag = probe.tag,
        inputType = probe.inputType,
        contentEditable = probe.contentEditable,
        elemId = probe.elemId,
        inputName = probe.inputName,
        placeholder = probe.placeholder,
        autocomplete = probe.autocomplete,
        text = probe.text,
        visible = true, // 找得到就说明可见：所有档的 matcher 都带 zlV（宽高非 0）
    )
}

/**
 * 回放跑完 ⇒ 「待发布卡」。
 *
 * 只填不提交这条线在卡上是结构性的：[PublishCard] 里没有 submit 字段，行里只有最终值、
 * 截图与「复制全文／打开页面」两个出口（渲染层 `FormFillSheet.kt` 同样只有这两个按钮）。
 *
 * 卡片文本落进这次运行的证据目录（[TeachRunReport.evidenceDir]），值一律先过
 * [TeachParams.redact]——200 的口径「参数值不进日志」在这一路同样成立；界面拿的是
 * [PublishCard] 对象本身（用户要复制的就是那份原文）。
 *
 * internal 而不是 private：这一段是纯数据＋落盘，JVM 单测要能直接盯住「红行／脱敏／
 * 没表单步不编空卡」三件事（`TeachReplayFormTest`）；带 Context 的截图那一拍留在
 * [publishReplayCard]，由真机验收覆盖。
 */
internal fun buildReplayPublishCard(
    skillName: String,
    outcomes: List<FieldOutcome>,
    evidenceDir: String?,
    openUrl: String,
    shotPath: String?,
    redactValues: Map<String, String>,
): PublishCard? {
    if (outcomes.isEmpty()) return null // 这一趟没有表单步 ⇒ 不编一张空卡
    val card = TeachFormRules.publishCardFromSteps(skillName, outcomes, openUrl, shotPath)
    val dir = evidenceDir?.let { File(it) }?.takeIf { it.isDirectory } ?: return card
    val text = buildString {
        appendLine(card.title)
        card.rows.forEach { row ->
            append(if (row.ok) "✓ " else "✗ ")
                .append(row.label).append(" = ").append(row.value)
            if (!row.ok) append(" —— ").append(row.reason ?: "回读不一致")
            appendLine()
        }
        card.notes.forEach { appendLine("· ").append(it) }
        appendLine("截图：").append(card.shotPath ?: "（无）")
        appendLine("页面：").append(card.openUrl)
        appendLine("———「复制全文」的内容———")
        appendLine(card.copyText)
    }
    runCatching {
        File(dir, "待发布卡.txt").writeText(TeachParams.redact(text, redactValues), Charsets.UTF_8)
    }.onFailure { RunLog.log("回放待发布卡落盘失败：skill=$skillName it=${it.message}") }
    return card
}

/**
 * 回放收尾（两条回放入口共用）：表单步出卡 ＋ 一行运行日志。
 * 日志带的是真机验收要看的三样——几格、几格回读没过、截图与卡路径。
 * 没有表单步的回放（纯点选翻页）不出卡、也不记这行。
 */
private fun publishReplayCard(
    context: Context,
    tabId: Int?,
    report: TeachRunReport,
    dom: FormFillingTeachDom,
    skill: TeachSkill,
    redactValues: Map<String, String> = emptyMap(),
): PublishCard? {
    val outcomes = dom.formOutcomes
    if (outcomes.isEmpty()) return null
    val shot = when (val c = BrowserCommands.captureShotRef(context, tabId?.toString(), "viewport", null)) {
        is com.example.zhengdao.bridge.BrowserCommands.ShotCapture.Ok -> c.ref.hostPath
        else -> null
    }
    val card = buildReplayPublishCard(
        skillName = skill.name,
        outcomes = outcomes,
        evidenceDir = report.evidenceDir,
        openUrl = dom.lastUrl.ifBlank { skill.startUrl },
        shotPath = shot,
        redactValues = redactValues,
    )
    RunLog.log(
        "回放表单步：技能=${skill.name} 共 ${outcomes.size} 格，回读没过 ${outcomes.count { !it.ok }} 格；" +
            "截图=${card?.shotPath ?: "无"} 卡=${report.evidenceDir?.let { "$it/待发布卡.txt" } ?: "未落盘"}",
    )
    return card
}

// ── 回放入口 ──────────────────────────────────────────────────────────────────

object TeachReplay {

    /**
     * 回放一个已保存的技能。全流程：读技能 → 域检查 → 指纹 → 逐步定位/门控/安全闸/
     * 自愈 → expect → 取证（产物/玄览技能/<名>/<时间>/）→ 审计行。
     * **同步阻塞**（内部走 execJs 桥），调用方放后台线程。
     *
     * 第二百零一批 ②：TYPE/SELECT 两支的「动手」走 [FormFillingTeachDom]（＝201 的
     * [FormFiller]），跑完出「待发布卡」（数据在 [PublishCard]，渲染在 `FormFillSheet.kt`）。
     */
    fun replaySkill(
        context: Context,
        projectRoot: File,
        workspace: File,
        name: String,
        tabId: Int?,
    ): TeachRunReport {
        val skill = TeachReplayStore.load(projectRoot, name)
            ?: return failedReport(name, "没有这个回放技能：$name（先在玄览里教一遍）")

        // 第二百五十五批 U3：上次环境指纹对不上 ⇒ fail-closed 判 SKILL_STALE，一个页面动作都不做
        val nowMs = System.currentTimeMillis()
        preAcceptanceBlock(projectRoot, name, nowMs)?.let { return failedReport(name, it) }
        val evidenceRoot = File(MemoryLayout.productsDir(workspace), "玄览技能")
        val sink = FileTeachEvidenceSink(evidenceRoot) { _ ->
            when (val c = BrowserCommands.captureShotRef(context, tabId?.toString(), "viewport", null)) {
                is com.example.zhengdao.bridge.BrowserCommands.ShotCapture.Ok -> c.ref.hostPath
                else -> null
            }
        }
        val dom = FormFillingTeachDom(BrowserTeachDom(tabId), BrowserFormDom(tabId))
        val executor = TeachExecutor(
            dom = dom,
            evidence = sink,
            audit = { report, now ->
                val opId = SkillAcceptance.opIdOf(report, now)
                if (!TeachReplayStore.appendAudit(projectRoot, report.auditLine(now, opId))) {
                    RunLog.log("回放审计落盘失败：skill=${report.skillName}")
                }
                recordAcceptance(context, projectRoot, skill, report, now, opId)
            },
        )
        val report = executor.run(skill)
        publishReplayCard(context, tabId, report, dom, skill)
        return report
    }

    private fun failedReport(name: String, reason: String): TeachRunReport = TeachRunReport(
        skillName = name,
        ok = false,
        stepsDone = 0,
        failedStep = null,
        stopReason = reason,
        durationMs = 0,
        drifts = emptyList(),
        refusals = emptyList(),
        ask = null,
        evidenceDir = null,
    )
}

// ── 第二百批：带参数的回放 ────────────────────────────────────────────────────
//
// 参数层只做三件事：**算值**（TeachParams.plan）、**填值**（applyValues）、**脱敏**（redact）。
// 定位/门控/安全/自愈/取证一条都不改——那些仍是 196 的 [TeachExecutor]，本层只是它的调用方。

/** 一次参数化回放的结局：report 为空时 ask／stopReason 必有一个非空。 */
data class TeachParamRun(
    val report: TeachRunReport?,
    val ask: String?,
    val display: List<TeachParamValue>,
    val stopReason: String?,
    /**
     * 「待发布卡」（第二百零一批 ②）：这一趟有表单步才有，纯点选翻页的回放是 null。
     *
     * 默认 null ⇒ 老调用点（含 226 的源码门禁）一个字节不改。卡上只有「复制全文／打开页面」
     * 两个出口——渲染层 `FormFillSheet.kt` 同样只有这两个按钮，没有第三个。
     */
    val publish: PublishCard? = null,
) {
    val ok: Boolean get() = report?.ok == true

    /** 回答里那句「昨天换成哪天」（没有换算 ⇒ 空串）。 */
    fun noteLine(): String = display.filter { it.note != null }.joinToString("；") { it.answerLine() }

    /** 全量人话（界面上直接显示）。 */
    fun answerLine(): String = when {
        ask != null -> ask
        stopReason != null -> stopReason
        report == null -> "没跑起来"
        else -> buildString {
            append(if (report.ok) "跑完了" else "停下了")
            append("：${report.stepsDone} 步")
            report.stopReason?.let { append("，原因：$it") }
            noteLine().takeIf { it.isNotEmpty() }?.let { append("（").append(it).append("）") }
            // 第二百零一批 ②：填了内容就要明说「等你点创建」——不说这句，用户会以为已经发出去了
            if (publish != null) append("；内容已填好，最后一下（创建/发布）你自己点，卡上有「复制全文」")
        }
    }
}

/**
 * 取证口装饰器：把每一步的 DOM 摘要与收尾的 run.json 里出现的**参数值**换回占位符。
 *
 * 为什么不改 196 的 `FileTeachEvidenceSink`：那是它的形状，本批红线「不许碰 TeachExecutor」。
 * 装饰器只做「过一道脱敏再交给它」，行为与原来逐字节一致（除了值变成 `${name}`）。
 */
internal class RedactingEvidenceSink(
    private val delegate: TeachEvidenceSink,
    private val values: Map<String, String>,
) : TeachEvidenceSink {
    private var dir: String? = null

    override fun begin(skillName: String, stamp: String): String? {
        val d = delegate.begin(skillName, stamp)
        dir = d
        return d
    }

    override fun step(index: Int, action: TeachAction, summary: String, screenshotRef: String?) {
        delegate.step(index, action, TeachParams.redact(summary, values), screenshotRef)
    }

    override fun finish(report: TeachRunReport) {
        delegate.finish(report)
        // 196 的 sink 把 `report.toString()` 原样落 run.json：这里把它覆写成脱敏版
        val f = dir?.let { File(it, "run.json") } ?: return
        runCatching {
            if (f.isFile) f.writeText(TeachParams.redact(f.readText(Charsets.UTF_8), values), Charsets.UTF_8)
        }
    }
}

/**
 * 带参数回放（生产壳）。全流程：
 * 读技能 → `plan`（取参数/反问/结构闸）→ 需要就先导航（先过授权域）→ 196 执行器 → 脱敏取证与审计。
 *
 * **取不到参数一个页面动作都不做**：plan 阶段就返回 Ask，连导航都不发生。
 */
fun replaySkillWithParams(
    context: Context,
    projectRoot: File,
    workspace: File,
    name: String,
    sentence: String,
    tabId: Int?,
    nowMs: Long = System.currentTimeMillis(),
): TeachParamRun {
    val skill = TeachReplayStore.load(projectRoot, name)
        ?: return TeachParamRun(null, null, emptyList(), "没有这个回放技能：$name（先在玄览里教一遍）")

    // 第二百五十五批 U3：stale ⇒ 连导航都不发生（fail-closed，不带病执行）
    preAcceptanceBlock(projectRoot, name, nowMs)?.let { return TeachParamRun(null, null, emptyList(), it) }

    when (val pl = TeachParams.plan(skill, sentence, nowMs)) {
        is TeachParams.TeachRunPlan.Ask -> return TeachParamRun(null, pl.line, emptyList(), null)
        is TeachParams.TeachRunPlan.Stop -> return TeachParamRun(null, null, emptyList(), pl.reason)
        is TeachParams.TeachRunPlan.Ready -> {
            pl.navigateTo?.let { url ->
                val r = runCatching { BrowserCommands.navigate(url, tabId?.toString()) }
                val okNav = r.getOrNull()?.contains("\"ok\":true") == true
                if (!okNav) {
                    // 导航失败就别跑了（跑的是「别的页面」，结果不能当技能结果）
                    return TeachParamRun(
                        null, null, pl.display,
                        "换参数后打不开起点页（${TeachSafety.hostOf(url) ?: "?"}），没开跑",
                    )
                }
            }
            val evidenceRoot = File(MemoryLayout.productsDir(workspace), "玄览技能")
            val raw = FileTeachEvidenceSink(evidenceRoot) { _ ->
                when (val c = BrowserCommands.captureShotRef(context, tabId?.toString(), "viewport", null)) {
                    is com.example.zhengdao.bridge.BrowserCommands.ShotCapture.Ok -> c.ref.hostPath
                    else -> null
                }
            }
            val dom = FormFillingTeachDom(BrowserTeachDom(tabId), BrowserFormDom(tabId))
            val executor = TeachExecutor(
                dom = dom,
                evidence = RedactingEvidenceSink(raw, pl.values),
                audit = { report, now ->
                    val opId = SkillAcceptance.opIdOf(report, now)
                    if (!TeachReplayStore.appendAudit(projectRoot, TeachParams.redact(report.auditLine(now, opId), pl.values))) {
                        RunLog.log("回放审计落盘失败：skill=${report.skillName}")
                    }
                    recordAcceptance(context, projectRoot, pl.skill, report, now, opId)
                },
            )
            val report = executor.run(pl.skill)
            // 表单步出「待发布卡」；落盘的文本按 200 的口径把参数值换回占位符，界面拿的对象不换
            val card = publishReplayCard(context, tabId, report, dom, pl.skill, pl.values)
            return TeachParamRun(report, null, pl.display, null, card)
        }
    }
}

// ── 第二百五十五批：回放前的验收闸与记账（两个回放入口共用；放文件级，TeachReplay 与带参数回放都能调）──

/**
 * 第二百五十五批 U3：回放前的验收闸（stale ⇒ 返回停手话，null ⇒ 可以跑）。
 * 状态读失败/没有状态文件 ⇒ 不拦（没有账不等于有罪——第一次跑总是没有样本）。
 */
private fun preAcceptanceBlock(projectRoot: File, name: String, nowMs: Long): String? = runCatching {
    val md = TeachReplayStore.skillMdFile(projectRoot, name).takeIf { it.isFile }
        ?.readText(Charsets.UTF_8)
    val status = md?.let { SkillStore.statusOf(it) } ?: "active"
    SkillAcceptance.preflight(TeachReplayStore.loadAcceptance(projectRoot, name), status, nowMs).blocked
}.getOrNull()

/**
 * 第二百五十五批 · 第二段：一次执行实例记一条验收（幂等 opId；U1 触发当场降级）。
 * 记账失败只 log——验收账不许把回放本身弄丢。
 */
private fun recordAcceptance(
    context: Context,
    projectRoot: File,
    skill: TeachSkill,
    report: TeachRunReport,
    nowMs: Long,
    opId: String,
) {
    runCatching {
        val pf = BaopuRuntime.personaFiles(context)
        val globalRoot = File(context.applicationContext.filesDir, BaopuKernel.Data.DIR_GLOBAL_MEMORY)
        TeachReplayStore.recordRun(projectRoot, globalRoot, pf, skill, report, nowMs, opId)
    }.onFailure {
        RunLog.log("验收记账失败：${skill.name}（${it.javaClass.simpleName}）")
    }
}
