// 证道 OBSERVE 固定观察脚本 v1（645，2026-11-14）。
// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝；可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 用途与边界：BrowserPort.OBSERVE（READ 档）唯一执行体。端口按观察项枚举
// （fields_text / node_table / value_shape）把本文件包装成「(<本文件>)("<枚举>")」
// 交给执行层 evaluateJavascript——调用方传不进任何脚本或参数，唯一的变量是端口
// 写死的枚举字面量。取回的是一次性观察面，不是持续监听（自装自收的收割脚本归 624）。
//
// 隐私三闸（全部在页内源头做；Kotlin 侧 execJs 的 redactText/redactPairs 是纵深第二道）：
//   ① 密码框/验证码整条丢弃：type=password、autocomplete 敏感值、name/id/placeholder/
//      aria-label 命中敏感词表 ⇒ 该元素不出现在任何观察项里（连身份面都不出页）；
//   ② 值只出形状不出值：el.value 只在读进 shp() 时碰（value_shape 一项），出页的只有
//      vs（empty/short/digits/date/long）；fields_text 与 node_table 根本不读值；
//   ③ 不回整页 HTML：全文件没有 innerHTML/outerHTML/document.write 等 HTML 通道，
//      取的全是属性与可及名（身份面），正文文本不取（可及名≤40 属十级定位同源的身份面）。
//
// 改本文件 ⇒ 必须同步改 bridge/ObserveAsset.kt 写死的 SHA-256（等于强制 review；
// hash 闸在运行时 fail-closed 拒执行，判据测试在 BrowserPortObserveTest）。
(function(item){
 var SENS=/pass|pwd|密码|验证码|校验码|captcha|otp|短信/i;
 var ACS=/one-time-code|cc-|current-password|new-password/i;
 var CAP=120;
 function sens(el){
  if(el.nodeType!=1)return true;
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
 function iRole(el){
  var r=el.getAttribute('role');if(r)return r.toLowerCase();
  var t=el.tagName,it=(el.getAttribute('type')||'').toLowerCase();
  if(t=='BUTTON'||t=='SUMMARY')return 'button';if(t=='A')return 'link';
  if(t=='SELECT')return 'combobox';if(t=='TEXTAREA')return 'textbox';if(t=='TH')return 'columnheader';
  if(t=='INPUT')return (it=='checkbox')?'checkbox':(it=='radio')?'radio':(it=='search')?'searchbox':'textbox';
  return ''}
 function accName(el){
  var lb=el.getAttribute('aria-labelledby');
  if(lb){var e=document.getElementById(lb);if(e)return txt(e)}
  var al=el.getAttribute('aria-label');if(al)return al;
  if(el.id){try{var l=document.querySelector('label[for="'+el.id+'"]');if(l)return txt(l)}catch(e){}}
  var wl=el.closest('label');if(wl)return txt(wl);
  var t=el.getAttribute('title');if(t)return t;return txt(el)}
 function path(el){
  var n=el,parts=[];
  while(n&&n.nodeType==1&&n!=document.body&&parts.length<12){var idx=1,sib=n.previousElementSibling;
   while(sib){if(sib.tagName==n.tagName)idx++;sib=sib.previousElementSibling}
   parts.unshift(n.tagName.toLowerCase()+'['+idx+']');n=n.parentElement}
  return parts.join('>')}
 function faces(el){
  var cls=[],i;for(i=0;i<el.classList.length&&i<8;i++)cls.push(el.classList[i]);
  var c=el.closest('form,td,th,label,fieldset');var fl='';
  if(c){var ct=(c.tagName=='LABEL'||c.tagName=='TH')?txt(c):(c.getAttribute('aria-label')||'');fl=ct.slice(0,40)}
  return {tag:el.tagName,role:iRole(el),name:accName(el),aria:el.getAttribute('aria-label')||'',
   testid:el.getAttribute('data-testid')||'',id:(el.id||'').slice(0,60),
   inputName:el.getAttribute('name')||'',placeholder:el.getAttribute('placeholder')||'',
   inputType:(el.tagName=='INPUT'?(el.getAttribute('type')||''):''),
   auto:el.getAttribute('autocomplete')||'',formLabel:fl,xp:path(el),cls:cls}}
 function page(){return {url:location.href,title:(document.title||'').slice(0,40),nc:document.querySelectorAll('*').length}}
 function nodes(){
  var els=document.querySelectorAll('button,a,[role=button],input,select,textarea,summary,[onclick],h1,h2,h3,th,[data-testid]');
  var out=[],i,el;
  for(i=0;i<els.length&&out.length<CAP;i++){el=els[i];if(sens(el))continue;out.push(faces(el))}
  return out}
 function fields(){
  var els=document.querySelectorAll('input,select,textarea');
  var out=[],i,el;
  for(i=0;i<els.length&&out.length<CAP;i++){el=els[i];if(sens(el))continue;out.push(faces(el))}
  return out}
 function shapes(){
  var els=document.querySelectorAll('input,select,textarea');
  var out=[],i,el;
  for(i=0;i<els.length&&out.length<CAP;i++){el=els[i];if(sens(el))continue;
   out.push({tag:el.tagName,xp:path(el),inputType:(el.tagName=='INPUT'?(el.getAttribute('type')||''):''),vs:shp(el.value||'')})}
  return out}
 var pg=page();
 if(item==='fields_text')return JSON.stringify({item:item,page:pg,fields:fields()});
 if(item==='node_table')return JSON.stringify({item:item,page:pg,nodes:nodes()});
 if(item==='value_shape')return JSON.stringify({item:item,page:pg,shapes:shapes()});
 return 'ERR:observe-item:'+item;
})
