// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百二十四批 · 学习板块阶段一 · 收割脚本（一段 IIFE：自装＋自收）。
//
// 分工（645 定）：
//   · 本文件＝**自装自收的事件收割**（页内监听＋事件队列＋页事实行）——按 645 交付报告
//     §四2/§七1 的授权口径走端口 PROBE（TeachRecorder 同款：脚本本层固定模板常量，
//     观察者身份 PortCaller.TEACH_RECORD，不是模型可注入的自由 JS）；
//   · 节点表/字段面/值形状（一次性观察面）归端口 `PortAction.OBSERVE`（assets/bridge/
//     observe_v1.js，hash 写死进门禁），调用方只传 ObserveItem 枚举——本文件不再页内
//     收割节点表。
// 通道：**不直调 BrowserCommands**（622/645 判据①：除 BrowserPort 内部实现外零引用），
// 唯一出口是 BrowserPort（见 ObserveCenter 的注入实现）。
//
// 隐私三闸（全部在**页内源头**做，原生侧第二道过滤是纵深防御，见 [ObserveRows]）：
//   ① 密码/验证码源（type=password、autocomplete 敏感值、name/id/placeholder/aria
//      命中敏感词表）**整条丢弃**——事件连身份面都不出页，队列里根本没有这条；
//   ② `faces()` 只出**身份面**（tag/role/可及名≤40/aria/testid/id/inputName/
//      placeholder≤40/inputType/autocomplete/formLabel/xpath/classes≤8），
//      **无 text 字段、无 value、无 options、无任何 HTML**；
//   ③ 输入事件只出 `vs`（值**形状**：empty/short/digits/date/long），值本身不出页。
// 可及名（accName，≤40 字）属**元素身份面**（十级定位同源），页面段落正文不取。
package com.example.zhengdao.baopu.learn

/** 收割脚本（页内源头闸的唯一真身；原生侧的敏感过滤是纵深防御的第二道，见 [ObserveRows]）。 */
internal object ObserveScript {

    /** 页内队列封顶：丢最旧（seq 单调，去重不靠它）。 */
    const val QUEUE_CAP: Int = 300

    val DRAIN: String = """
(function(){
 var SENS=/pass|pwd|密码|验证码|校验码|captcha|otp|短信/i;
 var ACS=/one-time-code|cc-|current-password|new-password/i;
 function sens(el){
  var t=el.tagName;
  if(t=='INPUT'&&(el.getAttribute('type')||'').toLowerCase()=='password')return true;
  if(ACS.test(el.getAttribute('autocomplete')||''))return true;
  return SENS.test((el.getAttribute('name')||'')+' '+(el.id||'')+' '+(el.getAttribute('placeholder')||'')+' '+(el.getAttribute('aria-label')||''));
 }
 function shp(v){
  v=(v||'').replace(/^\s+|\s+$/g,'');
  if(!v)return 'empty';
  if(/^[0-9]{4,}$/.test(v))return 'digits';
  if(/^[0-9]{4}[-/][0-9]{1,2}[-/][0-9]{1,2}/.test(v))return 'date';
  return v.length>40?'long':'short';
 }
 function txt(el){return (el.textContent||'').replace(/^\s+|\s+$/g,'').replace(/\s+/g,' ').slice(0,40)}
 function iRole(el){var r=el.getAttribute('role');if(r)return r.toLowerCase();
  var t=el.tagName,it=(el.getAttribute('type')||'').toLowerCase();
  if(t=='BUTTON'||t=='SUMMARY')return 'button';if(t=='A')return 'link';
  if(t=='SELECT')return 'combobox';if(t=='TEXTAREA')return 'textbox';if(t=='TH')return 'columnheader';
  if(t=='INPUT')return (it=='checkbox')?'checkbox':(it=='radio')?'radio':(it=='search')?'searchbox':'textbox';
  return ''}
 function accName(el){var lb=el.getAttribute('aria-labelledby');
  if(lb){var e=document.getElementById(lb);if(e)return txt(e)}
  var al=el.getAttribute('aria-label');if(al)return al;
  if(el.id){try{var l=document.querySelector('label[for="'+el.id+'"]');if(l)return txt(l)}catch(e){}}
  var wl=el.closest('label');if(wl)return txt(wl);
  var t=el.getAttribute('title');if(t)return t;return txt(el)}
 function path(el){var n=el,parts=[];
  while(n&&n.nodeType==1&&n!=document.body&&parts.length<12){var idx=1,sib=n.previousElementSibling;
   while(sib){if(sib.tagName==n.tagName)idx++;sib=sib.previousElementSibling}
   parts.unshift(n.tagName.toLowerCase()+'['+idx+']');n=n.parentElement}
  return parts.join('>')}
 function faces(el){var cls=[],i;for(i=0;i<el.classList.length&&i<8;i++)cls.push(el.classList[i]);
  var c=el.closest('form,td,th,label,fieldset');var fl='';
  if(c){var ct=(c.tagName=='LABEL'||c.tagName=='TH')?txt(c):(c.getAttribute('aria-label')||'');fl=ct.slice(0,40)}
  return {tag:el.tagName,role:iRole(el),name:accName(el),aria:el.getAttribute('aria-label')||'',
   testid:el.getAttribute('data-testid')||'',id:(el.id||'').slice(0,60),
   inputName:el.getAttribute('name')||'',placeholder:el.getAttribute('placeholder')||'',
   inputType:(el.tagName=='INPUT'?(el.getAttribute('type')||''):''),
   auto:el.getAttribute('autocomplete')||'',formLabel:fl,xp:path(el),cls:cls}}
 function push(a,el,vs){
  var t=window.__zlObs;if(!t)return;
  if(sens(el))return;
  var ev={a:a,url:location.href,el:faces(el),nc:document.querySelectorAll('*').length,t:Date.now(),d:t.doc,s:++t.seq};
  if(vs!==null)ev.vs=vs;
  if(t.q.length>=300)t.q.shift();
  t.q.push(ev);
 }
 if(!window.__zlObs){
  document.addEventListener('click',function(e){
   var el=e.target.closest?e.target.closest('button,a,[role=button],input,select,summary,label,[onclick]'):null;
   if(!el)el=e.target;if(el&&el.nodeType==1)push('click',el,null)},true);
  document.addEventListener('change',function(e){
   var el=e.target;if(!el||el.nodeType!=1)return;var t=el.tagName;
   if(t=='SELECT')push('select',el,shp(el.value));
   else if(t=='TEXTAREA'||(t=='INPUT'&&(el.getAttribute('type')=='text'||el.getAttribute('type')=='search'||!el.getAttribute('type'))))push('type',el,shp(el.value))},true);
  var lastScroll=0;
  document.addEventListener('scroll',function(e){
   var now=Date.now();if(now-lastScroll<800)return;lastScroll=now;
   var el=e.target;if(el&&el.nodeType==1)push('scroll',(el==document||el==document.body||el==document.documentElement)?document.body:el,null)},true);
  window.__zlObs={q:[],doc:Date.now().toString(36)+Math.random().toString(36).slice(2,8),seq:0,pg:false,lastU:'',lastNc:-1,lastPgTs:0};
 }
 var t=window.__zlObs,evs=t.q.splice(0,t.q.length),pg=null;
 var curU=location.href,curNc=document.querySelectorAll('*').length,now=Date.now();
 // 页事实行（url/title/nc）：首割必发＋2s 心跳——「看过、没变化」是缺证据的证据。
 // 节点表不在这里收割：645 起走端口 OBSERVE(NODE_TABLE) 按枚举现取（assets 一份、hash 闸）。
 var changed=!t.pg||t.lastU!==curU||t.lastNc!==curNc;
 var due=now-t.lastPgTs>=2000;
 if(due&&(changed||t.pg)){
  t.pg=true;t.lastU=curU;t.lastNc=curNc;t.lastPgTs=now;
  pg={url:curU,title:(document.title||'').slice(0,40),nc:curNc};
 }
 return JSON.stringify({doc:t.doc,seq:t.seq,ev:evs,pg:pg});
})()
""".trimIndent()
}
