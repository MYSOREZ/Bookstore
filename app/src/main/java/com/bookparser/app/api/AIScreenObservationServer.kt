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
            else -> json(Response.Status.NOT_FOUND, errorJson("unknown endpoint — see /docs"))
        }
    } catch (e: Exception) {
        json(Response.Status.INTERNAL_ERROR, errorJson(e.message ?: "internal error"))
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
<div class="nav"><a href="/docs">Справочник</a><a href="/api/context">Контекст</a></div>

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
