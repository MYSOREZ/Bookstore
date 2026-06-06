package com.bookparser.app.api

import android.webkit.WebView
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.resume

/**
 * AI Screen Observation API — HTTP-сервер на порту 8765.
 *
 * Позволяет внешнему AI-агенту видеть UI приложения и отправлять команды.
 * Аналог Comet Browser, но для этого конкретного Android-приложения.
 *
 * Браузер: http://ТЕЛЕФОН_IP:8765/docs   — справочник
 *          http://ТЕЛЕФОН_IP:8765/settings — настройки AI
 *
 * Эндпоинты:
 *   GET  /              → /docs
 *   GET  /docs          — HTML справочник всех методов
 *   GET  /settings      — HTML настройки AI агента
 *   GET  /api/status    — статус сервера JSON
 *   GET  /api/screen    — DOM снимок экрана
 *   GET  /api/context   — экран + состояние + логи (стартовый вызов агента)
 *   GET  /api/logs      — последние логи
 *   GET  /api/settings  — настройки JSON
 *   GET  /api/events    — SSE поток событий
 *   POST /api/action    — выполнить действие (возвращает screenAfter)
 *   POST /api/settings/save — сохранить настройки
 *   POST /api/ai/search — поиск через Ruwiki AI
 */
class AIScreenObservationServer(
    port: Int = 8765,
    private val getActiveWebView: () -> Pair<String, WebView?>,
    private val runOnMain: (Runnable) -> Unit,
    private val navigateToTab: (String) -> Unit,
    private val getAppState: () -> JSONObject,
    private val getRecentLogs: () -> List<String>,
    private val getSettings: () -> JSONObject,
    private val saveSettings: (JSONObject) -> Unit,
    private val getClipboard: () -> String,
    private val setClipboard: (String) -> Unit,
    private val searchRuwiki: ((String, (String) -> Unit) -> Unit)?
) : NanoHTTPD(port) {

    // ── SSE ───────────────────────────────────────────────────────────────────

    private data class SseClient(val send: (String) -> Unit, val output: PipedOutputStream)
    private val sseClients = CopyOnWriteArrayList<SseClient>()

    fun pushEvent(type: String, data: JSONObject) {
        val msg = "event: $type\ndata: $data\n\n"
        sseClients.removeAll { c ->
            try { c.send(msg); false } catch (_: Exception) { runCatching { c.output.close() }; true }
        }
    }

    fun pushEvent(type: String, key: String, value: String) =
        pushEvent(type, JSONObject().apply { put(key, value) })

    // ── Router ────────────────────────────────────────────────────────────────

    override fun serve(session: IHTTPSession): Response = try {
        val uri = session.uri.trimEnd('/')
        when {
            session.method == Method.OPTIONS         -> corsOk()
            uri == "" || uri == "/docs"              -> handleDocs()
            uri == "/agent"                          -> handleAgentPage()
            uri == "/settings"                       -> handleSettingsPage()
            uri == "/api/status"                     -> handleStatus()
            uri == "/api/screen"                     -> handleScreen()
            uri == "/api/context"                    -> handleContext()
            uri == "/api/logs"                       -> handleLogs()
            uri == "/api/settings"                   -> json(Response.Status.OK, getSettings().toString())
            uri == "/api/events"                     -> handleEvents()
            uri == "/api/action"   && session.method == Method.POST -> handleAction(session)
            uri == "/api/settings/save" && session.method == Method.POST -> handleSaveSettings(session)
            uri == "/api/ai/search" && session.method == Method.POST -> handleAiSearch(session)
            uri == "/api/tools/search" && session.method == Method.POST -> handleToolsSearch(session)
            else -> json(Response.Status.NOT_FOUND, errorJson("unknown endpoint — see /docs"))
        }
    } catch (e: Exception) {
        json(Response.Status.INTERNAL_ERROR, errorJson(e.message ?: "internal error"))
    }

    // ── GET /agent — AI агент в браузере ─────────────────────────────────────

    private fun handleAgentPage(): Response {
        val html = """<!DOCTYPE html><html lang="ru"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>AI Агент — Bookstore</title>
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:'Courier New',monospace;background:#0d1117;color:#c9d1d9;display:flex;flex-direction:column;height:100vh;overflow:hidden}
#top{padding:10px 12px;background:#161b22;border-bottom:1px solid #30363d;flex-shrink:0}
h1{color:#58a6ff;font-size:1em;margin-bottom:8px}
.row{display:flex;gap:8px;align-items:center;flex-wrap:wrap}
#task{flex:1;min-width:180px;background:#010409;border:1px solid #30363d;border-radius:6px;color:#e6edf3;padding:7px 10px;font-size:.9em;font-family:inherit}
#task:focus{outline:none;border-color:#58a6ff}
.btn{padding:7px 14px;border:none;border-radius:6px;cursor:pointer;font-size:.85em;font-family:inherit;white-space:nowrap}
#btnRun{background:#238636;color:#fff}.btnStop{background:#da3633;color:#fff}.btnClear{background:#21262d;color:#c9d1d9}.btnFill{background:#1f6feb;color:#fff}
.btn:hover{opacity:.85}.btn:disabled{opacity:.4;cursor:not-allowed}
#info{font-size:.78em;color:#6e7681;margin-top:6px}
.tabs{display:flex;gap:1px;background:#21262d;border-radius:6px;padding:2px;margin-top:8px}
.tab{flex:1;text-align:center;padding:5px;border-radius:4px;cursor:pointer;font-size:.8em;color:#8b949e}
.tab.active{background:#0d1117;color:#e6edf3}
#main{flex:1;display:flex;overflow:hidden}
#pane-log,#pane-screen,#pane-fill{flex:1;overflow-y:auto;padding:10px 12px;display:none;font-size:.82em}
#pane-log.show,#pane-screen.show,#pane-fill.show{display:block}
.step{padding:3px 0;border-bottom:1px solid #21262d}
.step.h{color:#58a6ff;font-weight:bold;font-size:.95em;border:none;margin:4px 0}
.step.s{color:#3fb950}.step.e{color:#f85149}.step.w{color:#e3b341}.step.i{color:#8b949e}
#screen-pre{white-space:pre-wrap;word-break:break-all;color:#8b949e;font-size:.8em}
#fill-form label{display:block;color:#8b949e;font-size:.85em;margin:8px 0 3px}
#fill-form input,#fill-form textarea{width:100%;background:#010409;border:1px solid #30363d;border-radius:5px;color:#e6edf3;padding:7px;font-size:.85em;font-family:inherit;margin-bottom:4px}
#fill-status{margin-top:8px;color:#8b949e;font-size:.85em}
.nav-links{font-size:.75em;margin-top:6px}
.nav-links a{color:#58a6ff;text-decoration:none;margin-right:10px}
</style></head><body>
<div id="top">
<h1>&#x1F916; Bookstore AI Агент</h1>
<div class="row">
  <input id="task" placeholder="Что сделать? (напр: заполни поля книги и опубликуй)" onkeydown="if(event.key==='Enter')run()">
  <button class="btn" id="btnRun" onclick="run()">&#9654; Запуск</button>
  <button class="btn btnStop" onclick="stop()">&#9632; Стоп</button>
  <button class="btn btnFill" onclick="switchTab('fill')">&#128218; Заполнить поля</button>
  <button class="btn btnClear" onclick="clearLog()">&#10006;</button>
</div>
<div id="info">Загрузка настроек...</div>
<div class="tabs">
  <div class="tab active" id="tab-log" onclick="switchTab('log')">Лог агента</div>
  <div class="tab" id="tab-screen" onclick="switchTab('screen')">Экран</div>
  <div class="tab" id="tab-fill" onclick="switchTab('fill')">Заполнение полей</div>
</div>
<div class="nav-links"><a href="/docs">Справочник</a><a href="/settings">Настройки</a></div>
</div>
<div id="main">
  <div id="pane-log" class="show"><div id="log-inner"></div></div>
  <div id="pane-screen"><pre id="screen-pre">Нажмите "Экран" чтобы обновить</pre><button class="btn btnClear" style="margin-top:8px" onclick="refreshScreen()">Обновить</button></div>
  <div id="pane-fill">
    <div id="fill-form">
      <b style="color:#79c0ff">Заполнение полей книги через AI + поиск в интернете</b>
      <label>Название книги</label><input id="f-title" placeholder="Война и мир">
      <label>Автор</label><input id="f-author" placeholder="Лев Толстой">
      <label>Дополнительный контекст (необязательно)</label>
      <textarea id="f-extra" rows="2" placeholder="жанр, год, серия..."></textarea>
      <div class="row" style="gap:8px;margin-top:4px">
        <button class="btn" style="background:#238636;color:#fff;flex:1" onclick="fillMode()">&#128269; Найти и заполнить</button>
        <button class="btn btnClear" onclick="autoReadFields()">Взять с экрана</button>
      </div>
      <div id="fill-status"></div>
      <div id="fill-result" style="margin-top:12px;display:none">
        <b style="color:#79c0ff">Сгенерированные поля:</b>
        <pre id="fill-data" style="white-space:pre-wrap;color:#c9d1d9;margin-top:6px;font-size:.82em"></pre>
        <button class="btn" style="background:#238636;color:#fff;margin-top:8px;width:100%" onclick="applyFields()">Применить через агента</button>
      </div>
    </div>
  </div>
</div>
<script>
var settings={}, running=false, generatedFields={};

const SYSTEM=`You control an Android book publishing app via API. Tabs: parser (load files), translator, forum (publish).
Available actions (reply ONLY valid JSON, no markdown):
  search    {"action":"search","query":"search terms","reason":"why"}  ← search internet for info
  navigate  {"action":"navigate","tab":"parser|translator|forum"}
  click     {"action":"click","selector":"#id"}
  fill      {"action":"fill","selector":"#id","value":"text"}
  select_option {"action":"select_option","selector":"#id","value":"opt"}
  scroll_to {"action":"scroll_to","selector":"#id"}
  clipboard_write {"action":"clipboard_write","text":"..."}
  wait      {"action":"wait","ms":1000}
  js        {"action":"js","code":"..."}
  done      {"action":"done","reason":"task complete"}
RULE: When filling book fields (biography, annotation, keywords) — use search FIRST to get real data from internet. After getting search results they appear in history — use them to fill fields.
Reply with ONE action per message. No markdown, no explanation outside JSON.`;

async function init(){
  try{
    settings=await fetch('/api/settings').then(r=>r.json());
    const p=settings.ai_provider||'openrouter';
    const m=p==='gemini'?settings.gemini_model:p==='openrouter'?settings.openrouter_model:'Ruwiki';
    document.getElementById('info').textContent='Провайдер: '+p+' | '+m+' | Откройте /settings для изменения';
  }catch(e){document.getElementById('info').textContent='Ошибка загрузки настроек: '+e;}
}

function log(msg,cls){
  const d=document.getElementById('log-inner');
  const el=document.createElement('div');
  el.className='step '+(cls||'n');
  el.textContent=msg;
  d.appendChild(el);
  d.parentElement.scrollTop=d.parentElement.scrollHeight;
}

function clearLog(){document.getElementById('log-inner').innerHTML='';}

function switchTab(t){
  ['log','screen','fill'].forEach(x=>{
    document.getElementById('pane-'+x).classList.toggle('show',x===t);
    document.getElementById('tab-'+x).classList.toggle('active',x===t);
  });
  if(t==='screen') refreshScreen();
}

async function refreshScreen(){
  const pre=document.getElementById('screen-pre');
  pre.textContent='Загрузка...';
  const ctx=await fetch('/api/context').then(r=>r.json());
  pre.textContent=formatScreen(ctx);
}

function formatScreen(ctx){
  const sc=ctx.screen||{}, st=ctx.appState||{}, els=sc.elements||[];
  let lines=[
    'Tab: '+(ctx.activeTab||'?')+' | '+(sc.title||''),
    'loggedIn='+st.loggedIn+' publishing='+st.isPublishing+' files='+st.stagedFilesCount,
    'Content: '+(sc.bodyPreview||'').substring(0,250),
    '\nElements ('+els.length+'):'
  ];
  els.slice(0,45).forEach(el=>{
    let line='  '+(el.selector||'?')+' ['+el.tag+']';
    if(el.disabled) line+=' [off]';
    if(el.checked) line+=' ✓';
    if(el.text) line+=" text='"+(el.text||'').substring(0,70)+"'";
    if(el.value) line+=" val='"+(el.value||'').substring(0,60)+"'";
    if(el.actions&&el.actions.length) line+=' ← '+el.actions.join('/');
    lines.push(line);
  });
  lines.push('\nLogs:');
  (ctx.recentLogs||[]).slice(-5).forEach(l=>lines.push('  '+l));
  return lines.join('\n');
}

async function toolSearch(query){
  try{
    const r=await fetch('/api/tools/search',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({query})});
    return await r.json();
  }catch(e){return {ok:false,results:[],error:String(e)};}
}

async function askAI(prompt){
  const p=settings.ai_provider||'openrouter';
  try{
    if(p==='openrouter'){
      const r=await fetch('https://openrouter.ai/api/v1/chat/completions',{
        method:'POST',
        headers:{'Authorization':'Bearer '+settings.openrouter_key,'Content-Type':'application/json','HTTP-Referer':'https://bookstore-agent','X-Title':'Bookstore Agent'},
        body:JSON.stringify({model:settings.openrouter_model||'nvidia/llama-3.1-nemotron-70b-instruct:free',
          messages:[{role:'system',content:SYSTEM},{role:'user',content:prompt}],
          max_tokens:600,temperature:0.3})
      });
      return (await r.json()).choices?.[0]?.message?.content||null;
    }
    if(p==='gemini'){
      const r=await fetch('https://generativelanguage.googleapis.com/v1beta/models/'+(settings.gemini_model||'gemini-2.0-flash')+':generateContent?key='+settings.gemini_key,{
        method:'POST',headers:{'Content-Type':'application/json'},
        body:JSON.stringify({contents:[{role:'user',parts:[{text:SYSTEM+'\n\n'+prompt}]}],
          generationConfig:{maxOutputTokens:600,temperature:0.3}})
      });
      return (await r.json()).candidates?.[0]?.content?.parts?.[0]?.text||null;
    }
    if(p==='ruwiki'){
      const r=await fetch('/api/ai/search',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({query:prompt})});
      return (await r.json()).result||null;
    }
  }catch(e){log('AI ошибка: '+e,'e');}
  return null;
}

function parseDecision(text){
  if(!text) return null;
  text=text.replace(/```json?\s*/g,'').replace(/```/g,'').trim();
  try{return JSON.parse(text);}catch{}
  const m=text.match(/\{[\s\S]*?\}/);
  if(m){try{return JSON.parse(m[0]);}catch{}}
  return null;
}

async function doAction(d){
  const body={type:d.action};
  if(d.selector!==undefined) body.selector=d.selector;
  if(d.value!==undefined)    body.value=d.value;
  if(d.tab!==undefined)      body.tab=d.tab;
  if(d.ms!==undefined)       body.ms=d.ms;
  if(d.text!==undefined)     body.text=d.text;
  if(d.code!==undefined)     body.code=d.code;
  body.waitMs=600;
  return fetch('/api/action',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)}).then(r=>r.json());
}

async function run(){
  if(running) return;
  const task=document.getElementById('task').value.trim();
  if(!task){alert('Введите задачу');return;}
  switchTab('log');
  running=true;
  document.getElementById('btnRun').disabled=true;
  log('▶ Задача: '+task,'h');
  await init();
  const history=[], max=parseInt(settings.agent_max_steps)||25;
  for(let step=1;step<=max&&running;step++){
    const ctx=await fetch('/api/context').then(r=>r.json()).catch(e=>({error:String(e)}));
    if(ctx.error){log('✗ Нет связи: '+ctx.error,'e');break;}
    document.getElementById('screen-pre').textContent=formatScreen(ctx);
    const histBlock=history.length?'\n\nИстория:\n'+history.slice(-5).map((h,i)=>(i+1)+'. '+h).join('\n'):'';
    const prompt='Задача: '+task+'\n\n'+formatScreen(ctx)+histBlock+'\n\nЧто делать?';
    log('['+step+'/'+max+'] Думаю...','i');
    const resp=await askAI(prompt);
    const d=parseDecision(resp);
    if(!d){log('Нечитаемый ответ: '+(resp||'').substring(0,80),'w');continue;}
    log('['+step+'/'+max+'] '+d.action+(d.reason?' — '+d.reason:''),d.action==='done'?'s':'n');
    if(d.action==='done'){log('✓ Задача выполнена!','s');break;}
    if(d.action==='search'){
      const q=d.query||d.reason||'';
      log('  &#128269; Поиск: '+q,'i');
      const sr=await toolSearch(q);
      if(sr.results&&sr.results.length){
        const snippets=sr.results.slice(0,4).map((r,i)=>(i+1)+'. '+r.title+': '+r.snippet).join('\n');
        history.push('SEARCH("'+q+'")\n'+snippets.substring(0,900));
        log('  ✓ Найдено '+sr.results.length+' результатов','s');
      }else{
        history.push('SEARCH("'+q+'") — ничего не найдено'+(sr.error?' ('+sr.error+')':''));
        log('  ✗ Ничего не найдено','w');
      }
      continue;
    }
    const r=await doAction(d);
    if(r?.actionResult?.ok===false) log('  ✗ '+(r.actionResult.error||'?'),'w');
    history.push(d.action+'('+(d.selector||d.tab||'')+') '+(d.reason||''));
    await new Promise(res=>setTimeout(res,parseInt(settings.agent_step_delay_ms)||1500));
  }
  running=false;
  document.getElementById('btnRun').disabled=false;
}

function stop(){running=false;log('■ Остановлено','w');document.getElementById('btnRun').disabled=false;}

/* ── Заполнение полей ── */
async function wikiSearch(q,lang){
  lang=lang||settings.wiki_lang||'ru';
  try{
    const s=await fetch('https://'+lang+'.wikipedia.org/w/api.php?action=query&format=json&origin=*&list=search&srsearch='+encodeURIComponent(q)+'&srlimit=1').then(r=>r.json());
    const hits=(s.query?.search)||[];
    if(!hits.length){return lang!=='en'?wikiSearch(q,'en'):'';}
    const title=hits[0].title;
    const p=await fetch('https://'+lang+'.wikipedia.org/w/api.php?action=query&format=json&origin=*&titles='+encodeURIComponent(title)+'&prop=extracts&exintro=1&explaintext=1&exlimit=1').then(r=>r.json());
    const pages=Object.values(p.query?.pages||{});
    return (pages[0]?.extract||'').substring(0,2500);
  }catch(e){return '';}
}

async function autoReadFields(){
  const ctx=await fetch('/api/context').then(r=>r.json());
  const els=ctx.screen?.elements||[];
  let title='',author='';
  els.forEach(el=>{
    const s=(el.selector||'').toLowerCase(), v=el.value||'';
    if(!title&&s.includes('title')&&v) title=v;
    if(!author&&s.includes('author')&&v) author=v;
  });
  if(title) document.getElementById('f-title').value=title;
  if(author) document.getElementById('f-author').value=author;
}

async function fillMode(){
  const title=document.getElementById('f-title').value.trim();
  const author=document.getElementById('f-author').value.trim();
  const extra=document.getElementById('f-extra').value.trim();
  if(!title||!author){alert('Введите название и автора');return;}
  const st=document.getElementById('fill-status');
  document.getElementById('fill-result').style.display='none';
  await init();
  st.textContent='Ищу данные в интернете...';
  let context='';
  // Веб-поиск (DDG/Google/Brave) — через прокси /api/tools/search (нет CORS)
  if(settings.search_web!==false){
    st.textContent='Поиск: биография '+author+'...';
    const sb=await toolSearch('биография писателя '+author);
    if(sb.results&&sb.results.length) context+='\nВеб-поиск биография:\n'+sb.results.slice(0,3).map(r=>r.title+': '+r.snippet).join('\n').substring(0,1000);
    st.textContent='Поиск: книга «'+title+'»...';
    const sk=await toolSearch('книга «'+title+'» '+author+' аннотация о чём');
    if(sk.results&&sk.results.length) context+='\nВеб-поиск книга:\n'+sk.results.slice(0,3).map(r=>r.title+': '+r.snippet).join('\n').substring(0,700);
  }
  // Wikipedia
  if(settings.search_wikipedia!==false){
    st.textContent='Wikipedia: биография автора...';
    const bio=await wikiSearch(author);
    if(bio) context+='\nБиография (Wikipedia):\n'+bio.substring(0,1200);
    st.textContent='Wikipedia: данные книги...';
    const book=await wikiSearch(title+' '+author)||(await wikiSearch(title));
    if(book) context+='\nДанные книги (Wikipedia):\n'+book.substring(0,800);
  }
  // Ruwiki
  if(settings.use_ruwiki_search!==false){
    st.textContent='Ruwiki: биография...';
    const rb=await fetch('/api/ai/search',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({query:'Биография писателя '+author})}).then(r=>r.json());
    if(rb.result) context+='\nRuwiki биография:\n'+rb.result.substring(0,1200);
    st.textContent='Ruwiki: данные книги...';
    const rk=await fetch('/api/ai/search',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({query:'Книга «'+title+'» аннотация о чём'})}).then(r=>r.json());
    if(rk.result) context+='\nRuwiki книга:\n'+rk.result.substring(0,800);
  }
  if(extra) context+='\nДоп. контекст: '+extra;
  st.textContent='AI генерирует поля...';
  const prompt='Заполни карточку книги. Книга: «'+title+'». Автор: '+author+'.'+context+'\n\nВерни ТОЛЬКО JSON:\n{"annotation":"...","author_bio":"...","keywords":"..."}';
  const resp=await askAI(prompt);
  const fields=parseDecision(resp);
  if(!fields){st.textContent='AI не вернул данные. Проверьте настройки провайдера.';return;}
  generatedFields=fields;
  document.getElementById('fill-data').textContent=JSON.stringify(fields,null,2);
  document.getElementById('fill-result').style.display='block';
  st.textContent='Готово! Проверьте поля и нажмите "Применить".';
}

async function applyFields(){
  if(!Object.keys(generatedFields).length) return;
  switchTab('log');
  const parts=Object.entries(generatedFields).filter(([,v])=>v).map(([k,v])=>k+': '+v);
  document.getElementById('task').value='Найди и заполни поля на экране: '+parts.join('; ');
  run();
}

init();
</script></body></html>"""
        return html(html)
    }

    // ── GET / и /docs — HTML справочник ──────────────────────────────────────

    private fun handleDocs(): Response {
        val (tab, _) = getActiveWebView()
        val html = """<!DOCTYPE html><html lang="ru"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Bookstore AI API</title>
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:'Courier New',monospace;background:#0d1117;color:#c9d1d9;padding:12px;font-size:14px}
h1{color:#58a6ff;margin-bottom:4px;font-size:1.3em}
.sub{color:#8b949e;font-size:.85em;margin-bottom:16px}
h2{color:#79c0ff;margin:20px 0 8px;font-size:1em;border-bottom:1px solid #21262d;padding-bottom:4px}
.ep{background:#161b22;border:1px solid #30363d;border-radius:6px;padding:10px;margin:6px 0}
.m{font-weight:bold;padding:2px 6px;border-radius:3px;margin-right:6px;font-size:.85em}
.get{background:#0d4a1a;color:#3fb950}.post{background:#0d2447;color:#58a6ff}
.path{color:#e6edf3;font-weight:bold}.desc{color:#8b949e;font-size:.85em;margin-top:4px}
pre{background:#010409;border:1px solid #21262d;border-radius:4px;padding:8px;margin-top:6px;
    overflow-x:auto;font-size:.8em;color:#e6edf3;white-space:pre-wrap;word-break:break-all}
.tag{background:#21262d;color:#79c0ff;padding:1px 6px;border-radius:10px;font-size:.8em;margin:2px}
.status{background:#1a4c1a;color:#3fb950;padding:4px 10px;border-radius:4px;display:inline-block;margin-bottom:12px}
a{color:#58a6ff;text-decoration:none}a:hover{text-decoration:underline}
.nav{display:flex;gap:8px;flex-wrap:wrap;margin-bottom:16px}
.nav a{background:#21262d;padding:6px 12px;border-radius:6px;color:#58a6ff;font-size:.85em}
</style></head><body>
<h1>📡 Bookstore AI Screen API</h1>
<div class="sub">Внешний ИИ-агент управляет Android-приложением через HTTP</div>
<div class="status">● Сервер работает | Вкладка: <b>$tab</b></div>
<div class="nav">
  <a href="/docs">Справочник</a>
  <a href="/agent">&#x1F916; Агент</a>
  <a href="/settings">Настройки AI</a>
  <a href="/api/status">Статус</a>
  <a href="/api/context">Контекст</a>
  <a href="/api/screen">Экран</a>
  <a href="/api/logs">Логи</a>
</div>

<h2>Эндпоинты</h2>
<div class="ep"><span class="m get">GET</span><span class="path">/api/context</span>
<div class="desc">Главный стартовый запрос агента — экран + состояние + логи в одном ответе</div>
<pre>curl http://ТЕЛЕФОН_IP:8765/api/context</pre></div>

<div class="ep"><span class="m get">GET</span><span class="path">/api/screen</span>
<div class="desc">DOM снимок: все видимые элементы с selector, actions, value, text, checked</div>
<pre>curl http://ТЕЛЕФОН_IP:8765/api/screen</pre></div>

<div class="ep"><span class="m post">POST</span><span class="path">/api/action</span>
<div class="desc">Выполнить действие. Ответ содержит <b>screenAfter</b> — не нужен отдельный GET /screen</div>
<pre># Нажать кнопку
curl -X POST http://ТЕЛЕФОН_IP:8765/api/action \
  -H "Content-Type: application/json" \
  -d '{"type":"click","selector":"#btn-publish","waitMs":800}'

# Заполнить поле
curl -X POST http://ТЕЛЕФОН_IP:8765/api/action \
  -d '{"type":"fill","selector":"#title","value":"Война и мир","waitMs":600}'

# Переключить вкладку
curl -X POST http://ТЕЛЕФОН_IP:8765/api/action \
  -d '{"type":"navigate","tab":"forum"}'

# Прочитать буфер обмена
curl -X POST http://ТЕЛЕФОН_IP:8765/api/action \
  -d '{"type":"clipboard_read"}'

# Записать в буфер обмена
curl -X POST http://ТЕЛЕФОН_IP:8765/api/action \
  -d '{"type":"clipboard_write","text":"Мой текст"}'

# Прокрутить к элементу
curl -X POST http://ТЕЛЕФОН_IP:8765/api/action \
  -d '{"type":"scroll_to","selector":"#bottom"}'

# Ждать (мс)
curl -X POST http://ТЕЛЕФОН_IP:8765/api/action \
  -d '{"type":"wait","ms":2000}'

# Получить значение поля
curl -X POST http://ТЕЛЕФОН_IP:8765/api/action \
  -d '{"type":"get_value","selector":"#title"}'

# Выбрать вариант в select
curl -X POST http://ТЕЛЕФОН_IP:8765/api/action \
  -d '{"type":"select_option","selector":"#genre","value":"fiction"}'

# Выполнить JavaScript
curl -X POST http://ТЕЛЕФОН_IP:8765/api/action \
  -d '{"type":"js","code":"document.title"}'</pre></div>

<div class="ep"><span class="m post">POST</span><span class="path">/api/tools/search</span>
<div class="desc">Веб-поиск через Android (нет CORS). AI вызывает как инструмент. Провайдер берётся из настроек (DDG/Google/Brave).</div>
<pre>curl -X POST http://ТЕЛЕФОН_IP:8765/api/tools/search \
  -d '{"query":"Биография Льва Толстого"}'
# → {"ok":true,"provider":"duckduckgo","results":[{"title":"...","snippet":"...","url":"..."}]}

# Переопределить провайдер разово:
curl -X POST http://ТЕЛЕФОН_IP:8765/api/tools/search \
  -d '{"query":"...","provider":"brave"}'</pre></div>

<div class="ep"><span class="m post">POST</span><span class="path">/api/ai/search</span>
<div class="desc">Поиск через Ruwiki AI (встроен в приложение, имеет доступ к интернету)</div>
<pre>curl -X POST http://ТЕЛЕФОН_IP:8765/api/ai/search \
  -d '{"query":"Биография Льва Толстого"}'</pre></div>

<div class="ep"><span class="m get">GET</span><span class="path">/api/events</span>
<div class="desc">SSE поток событий. AI подписывается и получает push-уведомления</div>
<pre>curl -N http://ТЕЛЕФОН_IP:8765/api/events

# Типы событий: connected, tab_changed, login, publish_progress, error</pre></div>

<div class="ep"><span class="m get">GET</span><span class="path">/api/logs</span>
<div class="desc">Последние 30 строк лога приложения</div></div>

<div class="ep"><span class="m get">GET</span><span class="path">/api/settings</span>
<div class="desc">Настройки AI агента (JSON)</div></div>

<div class="ep"><span class="m post">POST</span><span class="path">/api/settings/save</span>
<div class="desc">Сохранить настройки</div>
<pre>curl -X POST http://ТЕЛЕФОН_IP:8765/api/settings/save \
  -d '{"ai_provider":"openrouter","openrouter_key":"sk-or-..."}'</pre></div>

<h2>Типы действий (POST /api/action)</h2>
<pre>Действие       | Параметры                          | Описание
─────────────────────────────────────────────────────────────────
navigate       | tab: parser|translator|forum        | Переключить вкладку
click          | selector, waitMs (default 800)      | Нажать элемент
fill           | selector, value, waitMs (600)       | Заполнить поле (input/textarea)
select_option  | selector, value                     | Выбрать в &lt;select&gt;
clipboard_read |                                     | Прочитать буфер обмена
clipboard_write| text                                | Записать в буфер обмена
scroll_to      | selector                            | Прокрутить к элементу
get_value      | selector                            | Получить значение поля
wait           | ms                                  | Задержка в миллисекундах
js             | code, waitMs (0)                    | Выполнить JavaScript</pre>

<h2>Структура ответа /api/action</h2>
<pre>{
  "ok": true,
  "action": {"type":"click","selector":"#btn"},
  "actionResult": {"ok":true,"tag":"BUTTON","text":"Опубликовать"},
  "screenAfter": {          // снимок экрана после действия
    "activeTab": "forum",
    "elements": [
      {
        "tag": "button",
        "selector": "#btn-publish",
        "text": "Опубликовать",
        "disabled": false,
        "actions": ["click"]   // что можно делать с элементом
      },
      {
        "tag": "input",
        "selector": "#title",
        "value": "Война и мир",
        "placeholder": "Название книги",
        "actions": ["fill"]
      }
    ]
  }
}</pre>

<h2>Python агент</h2>
<pre>cd ai_agent/
pip install requests
python agent.py "загрузи книгу и заполни все поля"
python agent.py --fill     # режим заполнения полей с поиском в интернете
python agent.py --screen   # посмотреть текущий экран</pre>
</body></html>"""
        return html(html)
    }

    // ── GET /settings — HTML настройки ───────────────────────────────────────

    private fun handleSettingsPage(): Response {
        val settingsJson = getSettings()
        val html = """<!DOCTYPE html><html lang="ru"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>AI Настройки — Bookstore</title>
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:system-ui,sans-serif;background:#0d1117;color:#c9d1d9;padding:16px;max-width:600px}
h1{color:#58a6ff;margin-bottom:16px;font-size:1.2em}
.card{background:#161b22;border:1px solid #30363d;border-radius:8px;padding:16px;margin-bottom:12px}
.card h2{color:#79c0ff;font-size:.95em;margin-bottom:12px}
label{display:block;color:#8b949e;font-size:.85em;margin-bottom:4px}
input,select,textarea{width:100%;background:#010409;border:1px solid #30363d;border-radius:6px;
  color:#e6edf3;padding:8px;font-size:.9em;margin-bottom:12px;font-family:monospace}
input:focus,select:focus{outline:none;border-color:#58a6ff}
.btn{background:#238636;color:#fff;border:none;border-radius:6px;padding:10px 20px;
  font-size:.9em;cursor:pointer;width:100%;margin-top:4px}
.btn:hover{background:#2ea043}
.msg{padding:8px 12px;border-radius:6px;margin-top:8px;font-size:.85em;display:none}
.ok{background:#1a4c1a;color:#3fb950}.err{background:#4c1a1a;color:#f85149}
.nav{display:flex;gap:8px;margin-bottom:16px}
.nav a{background:#21262d;padding:6px 12px;border-radius:6px;color:#58a6ff;font-size:.85em;text-decoration:none}
.hint{color:#6e7681;font-size:.8em;margin-top:-8px;margin-bottom:12px}
select option{background:#161b22}
</style></head><body>
<h1>⚙️ AI Агент — Настройки</h1>
<div class="nav"><a href="/docs">Справочник</a><a href="/agent">&#x1F916; Агент</a><a href="/api/context">Контекст</a></div>

<form id="f">
<div class="card">
<h2>AI Провайдер</h2>
<label>Провайдер</label>
<select name="ai_provider" id="prov">
  <option value="openrouter">OpenRouter (бесплатные модели)</option>
  <option value="gemini">Google Gemini</option>
  <option value="ruwiki">Ruwiki AI (встроен, поиск в интернете)</option>
</select>
</div>

<div class="card" id="sec-or">
<h2>OpenRouter</h2>
<label>API Ключ (openrouter.ai → Keys)</label>
<input type="password" name="openrouter_key" id="or_key" placeholder="sk-or-v1-...">
<label>Модель</label>
<select name="openrouter_model" id="or_model">
  <option value="nvidia/llama-3.1-nemotron-70b-instruct:free">Nvidia Nemotron 70B (free)</option>
  <option value="meta-llama/llama-3.3-70b-instruct:free">Llama 3.3 70B (free)</option>
  <option value="google/gemma-3-27b-it:free">Google Gemma 3 27B (free)</option>
  <option value="microsoft/phi-4-reasoning:free">Microsoft Phi-4 (free)</option>
  <option value="deepseek/deepseek-r1:free">DeepSeek R1 (free)</option>
  <option value="mistralai/mistral-7b-instruct:free">Mistral 7B (free)</option>
</select>
</div>

<div class="card" id="sec-gemini">
<h2>Google Gemini</h2>
<label>API Ключ (aistudio.google.com)</label>
<input type="password" name="gemini_key" id="g_key" placeholder="AIza...">
<label>Модель</label>
<select name="gemini_model">
  <option value="gemini-2.0-flash">Gemini 2.0 Flash (быстрый)</option>
  <option value="gemini-2.0-flash-thinking-exp">Gemini 2.0 Flash Thinking</option>
  <option value="gemini-1.5-pro">Gemini 1.5 Pro</option>
</select>
</div>

<div class="card">
<h2>Поиск в интернете</h2>
<label>Поисковик</label>
<select name="search_provider" id="srch">
  <option value="duckduckgo">DuckDuckGo (бесплатно, без ключа)</option>
  <option value="google">Google Custom Search (100/день, нужен ключ)</option>
  <option value="brave">Brave Search (2000/месяц, нужен ключ)</option>
</select>
<div id="sec-google">
<label>Google Search API Ключ</label>
<input type="password" name="google_search_key" placeholder="AIza...">
<label>Google CX (Search Engine ID)</label>
<input type="text" name="google_cx" placeholder="1234567890:abc...">
<div class="hint">Настройка: programmablesearchengine.google.com</div>
</div>
<div id="sec-brave">
<label>Brave Search API Ключ</label>
<input type="password" name="brave_search_key" placeholder="BSAx...">
<div class="hint">Регистрация: api.search.brave.com (2000 запросов/месяц бесплатно)</div>
</div>
<label style="margin-top:4px"><input type="checkbox" name="search_web" value="true"> Использовать поисковик для биографий/аннотаций</label>
<label><input type="checkbox" name="fetch_top_pages" value="1"> Загружать текст страниц (медленнее, но полнее)</label>
</div>

<div class="card">
<h2>Поведение агента</h2>
<label>Макс. шагов</label>
<input type="number" name="agent_max_steps" value="25" min="5" max="100">
<label>Пауза между шагами (мс)</label>
<input type="number" name="agent_step_delay_ms" value="1500" min="200" max="10000">
<label>Язык поиска Wikipedia</label>
<select name="wiki_lang">
  <option value="ru">Русский</option>
  <option value="en">Английский</option>
</select>
</div>

<div class="card">
<h2>Поиск данных для полей книги</h2>
<label><input type="checkbox" name="search_wikipedia" value="true"> Искать в Wikipedia (для OpenRouter/Gemini)</label>
<div class="hint">Ruwiki сам ищет в интернете — Wikipedia не нужна</div>
<label style="margin-top:8px"><input type="checkbox" name="use_ruwiki_search" value="true"> Использовать Ruwiki для биографий</label>
</div>

<div class="card">
<h2>Тест поиска</h2>
<label>Поисковый запрос</label>
<input type="text" id="test-q" value="Биография Льва Толстого" style="margin-bottom:8px">
<button type="button" class="btn" style="background:#1f6feb;width:auto;padding:8px 16px;margin-bottom:10px" onclick="testSearch()">&#128269; Проверить поиск</button>
<div id="test-out" style="font-size:.8em;white-space:pre-wrap;word-break:break-all;max-height:200px;overflow-y:auto;color:#8b949e"></div>
</div>
<button type="submit" class="btn">💾 Сохранить настройки</button>
<div class="msg" id="msg"></div>
</form>

<script>
const S = $settingsJson;
const f = document.getElementById('f');
const prov = document.getElementById('prov');

function applyVisibility() {
  const p = prov.value;
  document.getElementById('sec-or').style.display = p==='openrouter'?'':'none';
  document.getElementById('sec-gemini').style.display = p==='gemini'?'':'none';
}

// Заполняем форму текущими настройками
function fill(name,val){
  const el=f.elements[name];
  if(!el) return;
  if(el.type==='checkbox') el.checked=val===true||val==='true';
  else el.value=val||'';
}
fill('ai_provider', S.ai_provider||'openrouter');
fill('openrouter_key', S.openrouter_key||'');
fill('openrouter_model', S.openrouter_model||'nvidia/llama-3.1-nemotron-70b-instruct:free');
fill('gemini_key', S.gemini_key||'');
fill('gemini_model', S.gemini_model||'gemini-2.0-flash');
fill('agent_max_steps', S.agent_max_steps||25);
fill('agent_step_delay_ms', S.agent_step_delay_ms||1500);
fill('wiki_lang', S.wiki_lang||'ru');
fill('search_wikipedia', S.search_wikipedia!==false);
fill('use_ruwiki_search', S.use_ruwiki_search!==false);
prov.value = S.ai_provider||'openrouter';
applyVisibility();
prov.addEventListener('change', applyVisibility);
const srch = document.getElementById('srch');
function applySrchVis(){
  const p=srch.value;
  document.getElementById('sec-google').style.display=p==='google'?'':'none';
  document.getElementById('sec-brave').style.display=p==='brave'?'':'none';
}
fill('search_provider', S.search_provider||'duckduckgo');
fill('google_search_key', S.google_search_key||'');
fill('google_cx', S.google_cx||'');
fill('brave_search_key', S.brave_search_key||'');
fill('search_web', S.search_web!==false);
fill('fetch_top_pages', !!S.fetch_top_pages);
srch.value = S.search_provider||'duckduckgo';
applySrchVis();
srch.addEventListener('change', applySrchVis);

f.addEventListener('submit', async e=>{
  e.preventDefault();
  const data={};
  new FormData(f).forEach((v,k)=>{
    if(v==='true'||v==='on') data[k]=true;
    else if(v==='false') data[k]=false;
    else if(!isNaN(v)&&v!=='') data[k]=Number(v);
    else data[k]=v;
  });
  // checkboxes не попадают в FormData если unchecked
  ['search_wikipedia','use_ruwiki_search'].forEach(k=>{
    if(!(k in data)) data[k]=false;
  });
  const msg=document.getElementById('msg');
  try{
    const r=await fetch('/api/settings/save',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(data)});
    const j=await r.json();
    msg.className='msg '+(j.ok?'ok':'err');
    msg.textContent=j.ok?'✓ Сохранено':'✗ '+j.error;
    msg.style.display='block';
  }catch(e){
    msg.className='msg err';msg.textContent='Ошибка: '+e;msg.style.display='block';
  }
});

async function testSearch(){
  const q=document.getElementById('test-q').value.trim();
  if(!q) return;
  const out=document.getElementById('test-out');
  const provider=document.getElementById('srch').value||'duckduckgo';
  const t0=Date.now();
  let tick;
  function setOut(color,text){out.style.color=color;out.textContent=text;}
  function elapsed(){return ((Date.now()-t0)/1000).toFixed(1)+'с';}
  setOut('#8b949e','[0.0с] Провайдер: '+provider+'\n[0.0с] Отправляю запрос...');
  tick=setInterval(()=>{
    const lines=out.textContent.split('\n');
    const last=lines[lines.length-1].replace(/^\[\S+\] /,'');
    lines[lines.length-1]='['+elapsed()+'] '+last;
    out.textContent=lines.join('\n');
  },300);
  const ctrl=new AbortController();
  const abortTimer=setTimeout(()=>ctrl.abort(),10000);
  try{
    const r=await fetch('/api/tools/search',{
      method:'POST',headers:{'Content-Type':'application/json'},
      body:JSON.stringify({query:q}),signal:ctrl.signal
    });
    clearTimeout(abortTimer); clearInterval(tick);
    const j=await r.json();
    if(j.ok&&j.results&&j.results.length){
      setOut('#3fb950',
        '['+elapsed()+'] ✓ Провайдер: '+j.provider+' | Найдено: '+j.results.length+'\n\n'+
        j.results.slice(0,3).map((x,i)=>(i+1)+'. '+x.title+'\n   '+(x.snippet||'').substring(0,140)).join('\n\n')
      );
    }else{
      setOut('#f85149',
        '['+elapsed()+'] ✗ Провайдер: '+(j.provider||provider)+'\n'+
        'Ошибка: '+(j.error||'результатов нет')+'\n'+
        (j.httpCode?'HTTP код: '+j.httpCode+'\n':'')+
        '\nПопробуйте другой поисковик в настройках выше.'
      );
    }
  }catch(e){
    clearTimeout(abortTimer); clearInterval(tick);
    setOut('#f85149',
      e.name==='AbortError'
        ?'['+elapsed()+'] ✗ Таймаут 10с — '+provider+' не ответил за отведённое время.\nПопробуйте Google или Brave.'
        :'['+elapsed()+'] ✗ '+e.name+': '+e.message
    );
  }
}
</script></body></html>""".replace("\$settingsJson", settingsJson.toString())
        return html(html)
    }

    // ── GET /api/status ───────────────────────────────────────────────────────

    private fun handleStatus(): Response {
        val (tab, _) = getActiveWebView()
        return json(Response.Status.OK, JSONObject().apply {
            put("running",   true)
            put("activeTab", tab)
            put("port",      8765)
            put("docsUrl",   "/docs")
            put("settingsUrl", "/settings")
        }.toString())
    }

    // ── GET /api/screen ───────────────────────────────────────────────────────

    private fun handleScreen(): Response {
        val (tab, wv) = getActiveWebView()
        if (wv == null) return json(Response.Status.OK, emptyScreen(tab))
        val result = runBlocking {
            withTimeoutOrNull(6_000) { queryDom(wv, tab) } ?: emptyScreen(tab, "timeout")
        }
        return json(Response.Status.OK, result)
    }

    // ── GET /api/context ──────────────────────────────────────────────────────

    private fun handleContext(): Response {
        val (tab, wv) = getActiveWebView()
        val screenJson = if (wv != null)
            runBlocking { withTimeoutOrNull(6_000) { queryDom(wv, tab) } ?: emptyScreen(tab) }
        else emptyScreen(tab)

        return json(Response.Status.OK, JSONObject().apply {
            put("activeTab",  tab)
            put("appState",   getAppState())
            put("screen",     JSONObject(screenJson))
            put("recentLogs", JSONArray().apply { getRecentLogs().forEach { put(it) } })
            put("tip", "Используйте POST /api/action для управления. Ответ включает screenAfter.")
        }.toString())
    }

    // ── GET /api/logs ─────────────────────────────────────────────────────────

    private fun handleLogs(): Response {
        val logs = getRecentLogs()
        return json(Response.Status.OK, JSONObject().apply {
            put("logs",  JSONArray().apply { logs.forEach { put(it) } })
            put("count", logs.size)
        }.toString())
    }

    // ── GET /api/events (SSE) ────────────────────────────────────────────────

    private fun handleEvents(): Response {
        val output = PipedOutputStream()
        val input  = PipedInputStream(output, 16_384)
        val writer = output.writer(Charsets.UTF_8)
        val client = SseClient(send = { msg -> writer.write(msg); writer.flush() }, output = output)
        sseClients.add(client)
        runCatching {
            val (tab, _) = getActiveWebView()
            client.send("event: connected\ndata: ${JSONObject().apply { put("activeTab", tab) }}\n\n")
        }
        return newChunkedResponse(Response.Status.OK, "text/event-stream", input).also {
            it.addHeader("Cache-Control",               "no-cache")
            it.addHeader("Connection",                  "keep-alive")
            it.addHeader("Access-Control-Allow-Origin", "*")
        }
    }

    // ── POST /api/action ──────────────────────────────────────────────────────

    private fun handleAction(session: IHTTPSession): Response {
        val body = HashMap<String, String>()
        session.parseBody(body)
        val raw = body["postData"]?.takeIf { it.isNotBlank() }
            ?: return json(Response.Status.BAD_REQUEST, errorJson("empty body"))
        val req = runCatching { JSONObject(raw) }.getOrElse {
            return json(Response.Status.BAD_REQUEST, errorJson("invalid JSON"))
        }
        val type   = req.optString("type").takeIf { it.isNotBlank() }
            ?: return json(Response.Status.BAD_REQUEST, errorJson("missing 'type'"))
        val waitMs = req.optLong("waitMs", 600L).coerceIn(0L, 5_000L)

        // Действия без WebView
        when (type) {
            "navigate" -> {
                val tab = req.optString("tab").takeIf { it.isNotBlank() }
                    ?: return json(Response.Status.BAD_REQUEST, errorJson("missing 'tab'"))
                runOnMain { navigateToTab(tab) }
                Thread.sleep(400)
                return actionResponse(req, JSONObject().apply { put("ok", true); put("navigated", tab) }, 0)
            }
            "wait" -> {
                val ms = req.optLong("ms", 1000L).coerceIn(0L, 30_000L)
                Thread.sleep(ms)
                return actionResponse(req, JSONObject().apply { put("ok", true); put("waited_ms", ms) }, 0)
            }
            "clipboard_read" -> {
                val text = runBlocking {
                    withTimeoutOrNull(3_000) { readClipboard() } ?: ""
                }
                return actionResponse(req, JSONObject().apply { put("ok", true); put("text", text) }, 0)
            }
            "clipboard_write" -> {
                val text = req.optString("text")
                runBlocking { withTimeoutOrNull(3_000) { writeClipboard(text) } }
                return actionResponse(req, JSONObject().apply { put("ok", true) }, 0)
            }
        }

        val (tab, wv) = getActiveWebView()
        if (wv == null) return json(Response.Status.OK, errorJson("no active WebView"))

        val js = when (type) {
            "click" -> {
                val sel = req.optString("selector").takeIf { it.isNotBlank() }
                    ?: return json(Response.Status.BAD_REQUEST, errorJson("missing 'selector'"))
                clickJs(sel)
            }
            "fill" -> {
                val sel = req.optString("selector").takeIf { it.isNotBlank() }
                    ?: return json(Response.Status.BAD_REQUEST, errorJson("missing 'selector'"))
                fillJs(sel, req.optString("value"))
            }
            "select_option" -> {
                val sel = req.optString("selector").takeIf { it.isNotBlank() }
                    ?: return json(Response.Status.BAD_REQUEST, errorJson("missing 'selector'"))
                selectJs(sel, req.optString("value"))
            }
            "scroll_to" -> {
                val sel = req.optString("selector").takeIf { it.isNotBlank() }
                    ?: return json(Response.Status.BAD_REQUEST, errorJson("missing 'selector'"))
                "(function(){ var el=document.querySelector('${sel.escJs()}'); if(!el) return JSON.stringify({ok:false,error:'not found'}); el.scrollIntoView({behavior:'smooth',block:'center'}); return JSON.stringify({ok:true}); })()"
            }
            "get_value" -> {
                val sel = req.optString("selector").takeIf { it.isNotBlank() }
                    ?: return json(Response.Status.BAD_REQUEST, errorJson("missing 'selector'"))
                "(function(){ var el=document.querySelector('${sel.escJs()}'); if(!el) return JSON.stringify({ok:false,error:'not found'}); return JSON.stringify({ok:true,value:el.value||'',text:(el.textContent||'').trim(),checked:el.checked}); })()"
            }
            "js" -> {
                val code = req.optString("code").takeIf { it.isNotBlank() }
                    ?: return json(Response.Status.BAD_REQUEST, errorJson("missing 'code'"))
                wrapJs(code)
            }
            else -> return json(Response.Status.BAD_REQUEST, errorJson("unknown type: $type"))
        }

        val actionResult = runBlocking {
            withTimeoutOrNull(8_000) { evalJs(wv, js) } ?: errorJson("timed out")
        }
        return actionResponse(req, JSONObject(actionResult), waitMs, wv, tab)
    }

    // ── POST /api/settings/save ───────────────────────────────────────────────

    private fun handleSaveSettings(session: IHTTPSession): Response {
        val body = HashMap<String, String>()
        session.parseBody(body)
        val raw = body["postData"]?.takeIf { it.isNotBlank() }
            ?: return json(Response.Status.BAD_REQUEST, errorJson("empty body"))
        return try {
            saveSettings(JSONObject(raw))
            json(Response.Status.OK, """{"ok":true}""")
        } catch (e: Exception) {
            json(Response.Status.INTERNAL_ERROR, errorJson(e.message ?: "save failed"))
        }
    }

    // ── POST /api/ai/search ───────────────────────────────────────────────────

    private fun handleAiSearch(session: IHTTPSession): Response {
        val body = HashMap<String, String>()
        session.parseBody(body)
        val raw = body["postData"]?.takeIf { it.isNotBlank() }
            ?: return json(Response.Status.BAD_REQUEST, errorJson("empty body"))
        val query = runCatching { JSONObject(raw).optString("query") }.getOrElse { "" }
            .takeIf { it.isNotBlank() }
            ?: return json(Response.Status.BAD_REQUEST, errorJson("missing 'query'"))

        if (searchRuwiki == null)
            return json(Response.Status.OK, JSONObject().apply {
                put("ok", false)
                put("error", "Ruwiki не настроен в приложении")
                put("hint", "Используйте Wikipedia REST API напрямую")
            }.toString())

        val result = runBlocking {
            withTimeoutOrNull(30_000) {
                suspendCancellableCoroutine { cont ->
                    searchRuwiki.invoke(query) { answer -> cont.resume(answer) }
                }
            }
        }
        return json(Response.Status.OK, JSONObject().apply {
            put("ok", result != null)
            put("query", query)
            put("result", result ?: "")
        }.toString())
    }

    // ── POST /api/tools/search — прокси поиска (нет CORS с Android) ─────────

    private fun handleToolsSearch(session: IHTTPSession): Response {
        val body = HashMap<String, String>()
        session.parseBody(body)
        val raw = body["postData"]?.takeIf { it.isNotBlank() }
            ?: return json(Response.Status.BAD_REQUEST, errorJson("empty body"))
        val req = runCatching { JSONObject(raw) }.getOrElse {
            return json(Response.Status.BAD_REQUEST, errorJson("invalid JSON"))
        }
        val query = req.optString("query").takeIf { it.isNotBlank() }
            ?: return json(Response.Status.BAD_REQUEST, errorJson("missing 'query'"))
        val cfg = getSettings()
        val provider = req.optString("provider").takeIf { it.isNotBlank() }
            ?: cfg.optString("search_provider", "duckduckgo")
        return try {
            val results = when (provider) {
                "google" -> webSearchGoogle(query, cfg)
                "brave"  -> webSearchBrave(query, cfg)
                else     -> webSearchDDG(query)
            }
            json(Response.Status.OK, JSONObject().apply {
                put("ok", true); put("query", query); put("provider", provider); put("results", results)
            }.toString())
        } catch (e: java.net.SocketTimeoutException) {
            json(Response.Status.OK, JSONObject().apply {
                put("ok", false); put("provider", provider)
                put("error", "таймаут — $provider не ответил за 8с")
                put("results", JSONArray())
            }.toString())
        } catch (e: java.net.ConnectException) {
            json(Response.Status.OK, JSONObject().apply {
                put("ok", false); put("provider", provider)
                put("error", "нет соединения с $provider: ${e.message}")
                put("results", JSONArray())
            }.toString())
        } catch (e: java.io.IOException) {
            json(Response.Status.OK, JSONObject().apply {
                put("ok", false); put("provider", provider)
                put("error", "ошибка сети (${e.javaClass.simpleName}): ${e.message}")
                put("results", JSONArray())
            }.toString())
        } catch (e: Exception) {
            json(Response.Status.OK, JSONObject().apply {
                put("ok", false); put("provider", provider)
                put("error", "${e.javaClass.simpleName}: ${e.message}")
                put("results", JSONArray())
            }.toString())
        }
    }

    private fun webSearchDDG(query: String): JSONArray {
        val url = java.net.URL("https://lite.duckduckgo.com/lite/?q=${java.net.URLEncoder.encode(query, "UTF-8")}")
        val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android 14; Mobile; rv:124.0) Gecko/124.0 Firefox/124.0")
            setRequestProperty("Accept-Language", "ru,en;q=0.9")
            setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            connectTimeout = 8_000; readTimeout = 8_000
            instanceFollowRedirects = true
        }
        val code = conn.responseCode
        if (code != 200) {
            conn.disconnect()
            throw java.io.IOException("HTTP $code от DuckDuckGo${if (code == 429) " (rate limit — слишком много запросов)" else ""}")
        }
        val html = try {
            conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
        } finally {
            conn.disconnect()
        }
        val out = JSONArray()
        // href может стоять до или после class — матчим тег целиком, потом проверяем атрибуты
        val snippets = Regex("""class=.result-snippet[^>]*>\s*([^<]+)""")
            .findAll(html).map { it.groupValues[1].trim() }.toList()
        var idx = 0
        Regex("""<a\b([^>]*)>([^<]*)</a>""").findAll(html).forEach { m ->
            val attrs = m.groupValues[1]
            if (!attrs.contains("result-link")) return@forEach
            val title = m.groupValues[2].trim().takeIf { it.isNotBlank() } ?: return@forEach
            val href  = Regex("""href="([^"]+)"""").find(attrs)?.groupValues?.getOrNull(1) ?: ""
            val realUrl = Regex("""uddg=([^&"]+)""").find(href)?.groupValues?.getOrNull(1)
                ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrElse { it } } ?: href
            if (out.length() < 8) out.put(JSONObject().apply {
                put("title", htmlEntities(title))
                put("snippet", htmlEntities(snippets.getOrNull(idx) ?: ""))
                put("url", realUrl)
            })
            idx++
        }
        if (out.length() == 0)
            throw java.io.IOException("DDG lite: результаты не распознаны. HTML[400]: ${html.take(400).replace("\n"," ")}")
        return out
    }

    private fun webSearchGoogle(query: String, cfg: JSONObject): JSONArray {
        val key = cfg.optString("google_search_key").takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Google Search key not set")
        val cx  = cfg.optString("google_cx").takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Google CX not set")
        val url = java.net.URL(
            "https://www.googleapis.com/customsearch/v1?q=${java.net.URLEncoder.encode(query,"UTF-8")}&key=$key&cx=$cx&num=5"
        )
        val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 15_000
        }
        val resp = conn.inputStream.bufferedReader(Charsets.UTF_8).readText(); conn.disconnect()
        val items = JSONObject(resp).optJSONArray("items") ?: return JSONArray()
        val out = JSONArray()
        for (i in 0 until minOf(items.length(), 5)) items.getJSONObject(i).let {
            out.put(JSONObject().apply { put("title", it.optString("title")); put("snippet", it.optString("snippet")); put("url", it.optString("link")) })
        }
        return out
    }

    private fun webSearchBrave(query: String, cfg: JSONObject): JSONArray {
        val key = cfg.optString("brave_search_key").takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Brave Search key not set")
        val url = java.net.URL(
            "https://api.search.brave.com/res/v1/web/search?q=${java.net.URLEncoder.encode(query,"UTF-8")}&count=5"
        )
        val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-Subscription-Token", key)
            connectTimeout = 10_000; readTimeout = 15_000
        }
        val inStream = if (conn.contentEncoding == "gzip") java.util.zip.GZIPInputStream(conn.inputStream) else conn.inputStream
        val resp = inStream.bufferedReader(Charsets.UTF_8).readText(); conn.disconnect()
        val webRes = JSONObject(resp).optJSONObject("web")?.optJSONArray("results") ?: return JSONArray()
        val out = JSONArray()
        for (i in 0 until minOf(webRes.length(), 5)) webRes.getJSONObject(i).let {
            out.put(JSONObject().apply { put("title", it.optString("title")); put("snippet", it.optString("description")); put("url", it.optString("url")) })
        }
        return out
    }

    private fun htmlEntities(s: String) = s
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")

    // ── actionResponse ────────────────────────────────────────────────────────

    private fun actionResponse(
        req: JSONObject, result: JSONObject, waitMs: Long,
        wv: WebView? = null, tab: String = getActiveWebView().first
    ): Response {
        if (waitMs > 0) Thread.sleep(waitMs)
        val screenJson = if (wv != null)
            runBlocking { withTimeoutOrNull(5_000) { queryDom(wv, tab) } ?: emptyScreen(tab) }
        else {
            val (t, w) = getActiveWebView()
            if (w != null) runBlocking { withTimeoutOrNull(5_000) { queryDom(w, t) } ?: emptyScreen(t) }
            else emptyScreen(tab)
        }
        return json(Response.Status.OK, JSONObject().apply {
            put("ok",           result.optBoolean("ok", true))
            put("action",       req)
            put("actionResult", result)
            put("screenAfter",  JSONObject(screenJson))
        }.toString())
    }

    // ── DOM snapshot ──────────────────────────────────────────────────────────

    private suspend fun queryDom(wv: WebView, tab: String): String =
        suspendCancellableCoroutine { cont ->
            val js = """
                (function(){try{
                    var els=[],seen={};
                    var nodes=document.querySelectorAll('button,input,textarea,select,[role="button"],a[href],[onclick]');
                    for(var i=0;i<nodes.length;i++){
                        var el=nodes[i];
                        var r=el.getBoundingClientRect();
                        if(r.width===0&&r.height===0) continue;
                        var st=window.getComputedStyle(el);
                        if(st.display==='none'||st.visibility==='hidden') continue;
                        var sel=null;
                        if(el.id) sel='#'+el.id;
                        else if(el.name) sel='[name="'+el.name+'"]';
                        else if(el.className){var c=el.className.toString().trim().split(/\s+/)[0];if(c)sel=el.tagName.toLowerCase()+'.'+c;}
                        if(sel&&seen[sel]) continue;
                        if(sel) seen[sel]=true;
                        var tag=el.tagName.toLowerCase();
                        var isChk=el.type==='checkbox'||el.type==='radio';
                        var acts=[];
                        if(tag==='button'||tag==='a'||el.onclick||el.getAttribute('role')==='button') acts.push('click');
                        if(isChk) acts.push('click');
                        if((tag==='input'&&!isChk)||tag==='textarea') acts.push('fill');
                        if(tag==='select') acts.push('select_option');
                        els.push({tag:tag,selector:sel,id:el.id||null,name:el.name||null,
                            type:el.type||null,
                            text:(el.textContent||el.innerText||'').trim().substring(0,150),
                            value:el.value!==undefined?el.value.substring(0,300):null,
                            placeholder:el.placeholder||null,disabled:!!el.disabled,
                            checked:isChk?el.checked:null,
                            href:tag==='a'?el.href:null,actions:acts});
                    }
                    return JSON.stringify({activeTab:'$tab',title:document.title,url:window.location.href,
                        bodyPreview:(document.body?document.body.innerText:'').trim().substring(0,400),elements:els});
                }catch(e){return JSON.stringify({activeTab:'$tab',error:String(e),elements:[]});}})()
            """.trimIndent()
            runOnMain { wv.evaluateJavascript(js) { raw -> cont.resume(unquote(raw)) } }
        }

    // ── JS builders ───────────────────────────────────────────────────────────

    private fun clickJs(sel: String) =
        "(function(){var el=document.querySelector('${sel.escJs()}');if(!el)return JSON.stringify({ok:false,error:'not found: ${sel.escJs()}'});el.click();return JSON.stringify({ok:true,tag:el.tagName,text:(el.textContent||'').trim().substring(0,80)});})()"

    private fun fillJs(sel: String, value: String) =
        "(function(){var el=document.querySelector('${sel.escJs()}');if(!el)return JSON.stringify({ok:false,error:'not found'});el.value='${value.escJs()}';el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));return JSON.stringify({ok:true,newValue:el.value.substring(0,100)});})()"

    private fun selectJs(sel: String, value: String) =
        "(function(){var el=document.querySelector('${sel.escJs()}');if(!el)return JSON.stringify({ok:false,error:'not found'});el.value='${value.escJs()}';el.dispatchEvent(new Event('change',{bubbles:true}));return JSON.stringify({ok:true,selected:el.value});})()"

    private fun wrapJs(code: String) =
        "(function(){try{var r=($code);return JSON.stringify({ok:true,result:r});}catch(e){return JSON.stringify({ok:false,error:String(e)});}})()"

    private suspend fun evalJs(wv: WebView, js: String): String =
        suspendCancellableCoroutine { cont ->
            runOnMain { wv.evaluateJavascript(js) { raw -> cont.resume(unquote(raw)) } }
        }

    // ── Clipboard (main thread) ───────────────────────────────────────────────

    private suspend fun readClipboard(): String = suspendCancellableCoroutine { cont ->
        runOnMain { cont.resume(runCatching { getClipboard() }.getOrElse { "" }) }
    }

    private suspend fun writeClipboard(text: String) = suspendCancellableCoroutine<Unit> { cont ->
        runOnMain { runCatching { setClipboard(text) }; cont.resume(Unit) }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun emptyScreen(tab: String, error: String? = null) = JSONObject().apply {
        put("activeTab", tab); put("elements", JSONArray())
        if (error != null) put("error", error)
    }.toString()

    private fun unquote(raw: String?): String {
        if (raw == null || raw == "null") return "{}"
        val t = raw.trim()
        return if (t.startsWith("\"") && t.endsWith("\""))
            t.substring(1, t.length - 1).replace("\\\"", "\"").replace("\\\\", "\\").replace("\\/", "/")
        else t
    }

    private fun String.escJs() = replace("\\", "\\\\").replace("'", "\\'")
    private fun errorJson(msg: String) = """{"ok":false,"error":"${msg.replace("\"", "'")}"}"""

    private fun json(status: Response.Status, body: String): Response =
        newFixedLengthResponse(status, "application/json", body).also { addCors(it) }

    private fun html(body: String): Response =
        newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", body).also { addCors(it) }

    private fun corsOk(): Response =
        newFixedLengthResponse(Response.Status.OK, "text/plain", "").also { addCors(it) }

    private fun addCors(r: Response) {
        r.addHeader("Access-Control-Allow-Origin",  "*")
        r.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        r.addHeader("Access-Control-Allow-Headers", "Content-Type")
    }
}
