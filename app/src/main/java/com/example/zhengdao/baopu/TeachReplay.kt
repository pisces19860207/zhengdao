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
// 线程契约：BrowserCommands.execJs 是同步桥且**禁止主线程调用**——本文件的
// 录制/回放都跑在调用方给的后台协程里，UI 只碰 [TeachSessionUiModel]。
package com.example.zhengdao.baopu

import android.content.Context
import com.example.zhengdao.bridge.BrowserCommands
import com.example.zhengdao.bridge.jsStringLiteral
import com.example.zhengdao.rootfs.RunLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// ── 页面查询脚本族（全部自包含 IIFE，返回 JSON 字符串）─────────────────────────

/**
 * 十条哑查询的 JS 侧。要点：
 * - 可见性：getBoundingClientRect 宽高都为 0 的元素不算命中（藏着的诱饵不点）；
 * - 可访问名口径与录制一致（aria-labelledby → aria-label → 关联 label → title → 文本）；
 * - 只做「问什么答什么」，不做任何门控——门控在 Kotlin。
 */
internal object TeachScripts {

    /** 各脚本共用的元素工具（snap/accName/隐式 role/可见性）。 */
    private const val HELPERS: String = """
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

    /** 按定位器描述找元素（tier 匹配器；返回可见元素数组）。 */
    private fun matcherFor(kind: TeachLocatorKind, value: String): String = when (kind) {
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

// ── 示教录制（注入＋轮询收割；只记录不干预）───────────────────────────────────

/** 录制会话采到的原始事件流（线程安全由录制协程单写、stop 时移交）。 */
internal class TeachRecorder(
    private val tabId: Int?,
    private val scope: CoroutineScope,
    private val pollMs: Long = 600,
) {
    var job: Job? = null
        private set

    /** 收割循环活着时持续追加（只在录制协程里写）。 */
    internal val raw = mutableListOf<TeachRawEvent>()

    /** 会话起点页事实（指纹原料）。 */
    internal var startFacts: TeachPageFacts? = null
        private set

    private var lastUrl: String = ""
    private var lastRows: Int = 0

    /** 注入＋收割一体的脚本（幂等：没有队列就装监听，装完把这批事件带走）。 */
    private fun harvestScript(): String = "(function(){\n" + TeachRecorderJs.INJECT + """
var q=window.__zlTeach.q;window.__zlTeach.q=[];
var rows=document.querySelectorAll('table tr,[role=row],ol>li,ul>li').length;
return JSON.stringify({u:location.href,r:rows,e:q.slice(0,20)})})()
"""

    fun start(onError: (String) -> Unit) {
        if (job?.isActive == true) return
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
                }
            }
            while (!Thread.currentThread().isInterrupted) {
                val ok = runCatching { harvestOnce() }.getOrElse { false }
                if (!ok) onError("示教收割失败（页面可能已关闭或还在加载）")
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
        val events = r.optJSONArray("e") ?: JSONArray()
        for (i in 0 until events.length()) {
            val ev = events.optJSONObject(i) ?: continue
            val action = TeachAction.fromWire(ev.optString("a")) ?: continue
            val el = ev.optJSONObject("el") ?: continue
            val info = TeachElementInfo(
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
            )
            raw += TeachRawEvent(
                action = action,
                url = ev.optString("url").ifBlank { lastUrl },
                element = info,
                value = ev.optString("value").takeIf { it.isNotEmpty() },
                beforeUrl = lastUrl,
                afterUrl = url,
                beforeRows = lastRows,
                afterRows = rows,
            )
        }
        lastUrl = url
        lastRows = rows
        return true
    }

    /** 停止并移交事件流（幂等）。 */
    fun stop(): List<TeachRawEvent> {
        job?.cancel()
        job = null
        return raw.toList()
    }

    private fun jsonArr(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() } }
    }

    private companion object {
        val FACTS_SCRIPT: String = "(function(){" + TeachRecorderJs.FACTS + "})()"
    }
}

/** 录制注入脚本的原始字符串（[TeachRecorder] 拼 HARVEST 用）。 */
internal object TeachRecorderJs {
    /** 注入的监听（挂 window.__zlTeach；导航换页后由下一轮收割重新装）。 */
    val INJECT: String = """
if(!window.__zlTeach){
 var q=[];
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
  return {tag:el.tagName,role:iRole(el),name:accName(el),text:txt(el),
   aria:el.getAttribute('aria-label')||'',testid:el.getAttribute('data-testid')||'',
   id:el.id||'',inputName:el.getAttribute('name')||'',placeholder:el.getAttribute('placeholder')||'',
   inputType:(el.tagName=='INPUT'?(el.getAttribute('type')||'text'):''),
   formLabel:fl,classes:cls.slice(0,8),xpath:xp}}
 function push(a,el,v){q.push({a:a,url:location.href,el:snap(el),value:v||''})}
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
 window.__zlTeach={q:q};
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

    /** 到目前为止录到的原始事件数（UI 指示条轮询用）。 */
    fun recordedSoFar(): Int = recorder?.raw?.size ?: 0

    /** 开始示教（失败给一句人话）。 */
    fun start(onError: (String) -> Unit) {
        if (recording) return
        val rec = TeachRecorder(tabId(), scope)
        recorder = rec
        recording = true
        stepCount = 0
        rec.start(onError)
    }

    /** 结束示教 → 出草稿（不落盘；确认在 [save]）。 */
    fun stop(onDraft: (TeachSkillDraft?) -> Unit) {
        val rec = recorder ?: return
        val events = rec.stop()
        val facts = rec.startFacts ?: TeachPageFacts("", emptyList(), emptyList())
        recording = false
        scope.launch(Dispatchers.IO) {
            val d = if (events.isEmpty()) null else TeachLearner.buildDraft(
                events = events,
                name = suggestName(facts.url),
                purpose = "在 ${facts.url.substringAfter("//").substringBefore('/')} 上重复示教的操作",
                startFacts = facts,
                nowMs = System.currentTimeMillis(),
            )
            stepCount = d?.skill?.steps?.size ?: 0
            draft = d
            withContext(Dispatchers.Main) { onDraft(d) }
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
                stampText = com.example.zhengdao.util.TimeFormats.fileStamp(),
            )
            val msg = if (r.written != null) "已保存技能「${skill.name}」" else (r.reason ?: "保存失败")
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

    private fun suggestName(url: String): String {
        val host = TeachSafety.hostOf(url) ?: "网页"
        return "$host-流程"
    }
}

// ── 回放入口 ──────────────────────────────────────────────────────────────────

object TeachReplay {

    /**
     * 回放一个已保存的技能。全流程：读技能 → 域检查 → 指纹 → 逐步定位/门控/安全闸/
     * 自愈 → expect → 取证（产物/玄览技能/<名>/<时间>/）→ 审计行。
     * **同步阻塞**（内部走 execJs 桥），调用方放后台线程。
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
        val evidenceRoot = File(MemoryLayout.productsDir(workspace), "玄览技能")
        val sink = FileTeachEvidenceSink(evidenceRoot) { _ ->
            when (val c = BrowserCommands.captureShotRef(context, tabId?.toString(), "viewport", null)) {
                is com.example.zhengdao.bridge.BrowserCommands.ShotCapture.Ok -> c.ref.hostPath
                else -> null
            }
        }
        val executor = TeachExecutor(
            dom = BrowserTeachDom(tabId),
            evidence = sink,
            audit = { report, now ->
                if (!TeachReplayStore.appendAudit(projectRoot, report.auditLine(now))) {
                    RunLog.log("回放审计落盘失败：skill=${report.skillName}")
                }
            },
        )
        return executor.run(skill)
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
