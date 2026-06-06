#!/usr/bin/env python3
"""
Bookstore AI Agent
==================
Управляет Android-приложением Bookstore через AI Screen Observation API.

Поиск данных о книгах и авторах:
  1. DuckDuckGo    — бесплатно, без API ключа, реальный поиск в интернете
  2. Google CSE    — 100 запросов/день бесплатно (нужен API ключ + CX)
  3. Brave Search  — 2000 запросов/месяц бесплатно (нужен API ключ)
  4. Wikipedia     — дополнительный источник
  5. Ruwiki AI     — через приложение, сам ищет в интернете (лучший для RU)

AI провайдеры:
  - OpenRouter (бесплатные модели: Nemotron, Llama, DeepSeek и др.)
  - Google Gemini
  - Ruwiki AI (встроен в приложение)

Установка: pip install requests
Настройка: отредактируйте config.json или откройте http://IP:8765/settings

Использование:
  python agent.py "загрузи книгу и опубликуй"
  python agent.py --fill          AI заполняет поля книги с поиском в интернете
  python agent.py --screen        посмотреть текущий экран приложения
  python agent.py --search "Лев Толстой биография"   тест поиска
  python agent.py --settings      показать текущие настройки
"""

import json, re, sys, time, os
from html.parser import HTMLParser

try:
    import requests
except ImportError:
    print("Установите: pip install requests")
    sys.exit(1)

# ─── Конфигурация ─────────────────────────────────────────────────────────────

DEFAULT_CONFIG = {
    # Адрес сервера (localhost если Termux на том же телефоне, иначе — IP телефона)
    "server": "http://localhost:8765",

    # AI провайдер: openrouter | gemini | ruwiki
    "provider": "openrouter",

    # OpenRouter — бесплатные модели на openrouter.ai/keys
    "openrouter_key": "",
    "openrouter_model": "nvidia/llama-3.1-nemotron-70b-instruct:free",

    # Google Gemini — ключ на aistudio.google.com
    "gemini_key": "",
    "gemini_model": "gemini-2.0-flash",

    # Поиск в интернете: duckduckgo | google | brave
    # duckduckgo — бесплатно, без ключа, работает сразу
    "search_provider": "duckduckgo",

    # Google Custom Search: programmablesearchengine.google.com (100 запросов/день)
    "google_search_key": "",
    "google_cx": "",

    # Brave Search API: api.search.brave.com (2000 запросов/месяц)
    "brave_search_key": "",

    # Что использовать для обогащения данных книги
    "search_web": True,           # Поиск через поисковик
    "search_wikipedia": True,     # Поиск в Wikipedia
    "use_ruwiki_search": True,    # Поиск через Ruwiki AI (встроен в приложение)
    "fetch_top_pages": 1,         # Загружать текст со страниц (0 = только сниппеты)

    "wiki_lang": "ru",
    "max_steps": 25,
    "step_delay": 1.5,
    "debug": False,
}

CONFIG_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "config.json")
CONFIG = dict(DEFAULT_CONFIG)
try:
    with open(CONFIG_FILE) as f:
        CONFIG.update(json.load(f))
except FileNotFoundError:
    pass

def _dbg(msg):
    if CONFIG.get("debug"):
        print(f"  [dbg] {msg}")


# ─── Bookstore API ────────────────────────────────────────────────────────────

BASE = CONFIG["server"].rstrip("/")
S = requests.Session()
S.headers.update({"Content-Type": "application/json"})

def _get(path):
    try:
        r = S.get(BASE + path, timeout=15)
        return r.json()
    except Exception as e:
        return {"error": str(e)}

def _post(path, body=None):
    try:
        r = S.post(BASE + path, json=body or {}, timeout=30)
        return r.json()
    except Exception as e:
        return {"error": str(e)}

def get_context():   return _get("/api/context")
def get_screen():    return _get("/api/screen")
def get_logs():      return _get("/api/logs")
def get_settings():  return _get("/api/settings")
def save_settings(d): return _post("/api/settings/save", d)

def do_action(type_, **kw):  return _post("/api/action", {"type": type_, **kw})
def click(sel, ms=800):      return do_action("click", selector=sel, waitMs=ms)
def fill(sel, val, ms=600):  return do_action("fill", selector=sel, value=val, waitMs=ms)
def navigate(tab):           return do_action("navigate", tab=tab)
def wait_app(ms):            return do_action("wait", ms=ms)
def scroll_to(sel):          return do_action("scroll_to", selector=sel)
def run_js(code, ms=0):      return do_action("js", code=code, waitMs=ms)
def clipboard_write(text):   return do_action("clipboard_write", text=text)
def clipboard_read():        return do_action("clipboard_read")
def get_value(sel):          return do_action("get_value", selector=sel)
def select_option(sel, val): return do_action("select_option", selector=sel, value=val)

def ruwiki_search(query):
    return _post("/api/ai/search", {"query": query})


# ─── Web Search ───────────────────────────────────────────────────────────────

class _DDGParser(HTMLParser):
    """Парсит HTML-результаты DuckDuckGo."""
    def __init__(self):
        super().__init__()
        self.results, self._cur, self._mode = [], {}, None

    def handle_starttag(self, tag, attrs):
        d = dict(attrs)
        cls = d.get("class", "")
        if "result__a" in cls:
            self._mode = "title"
            self._cur = {"url": d.get("href", ""), "title": "", "snippet": ""}
        elif "result__snippet" in cls:
            self._mode = "snippet"

    def handle_data(self, data):
        if self._mode == "title":
            self._cur["title"] += data
        elif self._mode == "snippet":
            self._cur["snippet"] += data

    def handle_endtag(self, tag):
        if tag == "a" and self._mode in ("title", "snippet"):
            if self._mode == "snippet" and self._cur.get("snippet"):
                self.results.append(dict(self._cur))
                self._cur = {}
            self._mode = None


def duckduckgo_search(query, num=6):
    """DuckDuckGo — бесплатно, без API ключа. Реальный поиск в интернете."""
    try:
        r = requests.post(
            "https://html.duckduckgo.com/html/",
            data={"q": query, "kl": "ru-ru"},
            headers={"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"},
            timeout=15,
        )
        p = _DDGParser()
        p.feed(r.text)
        results = p.results[:num]
        _dbg(f"DDG '{query}': {len(results)} результатов")
        return results
    except Exception as e:
        _dbg(f"DDG ошибка: {e}")
        return []


def google_search(query, num=5):
    """Google Custom Search API — 100 запросов/день бесплатно.
    Настройка: programmablesearchengine.google.com → получите CX и API ключ."""
    key = CONFIG.get("google_search_key", "")
    cx  = CONFIG.get("google_cx", "")
    if not key or not cx:
        return []
    try:
        r = requests.get(
            "https://www.googleapis.com/customsearch/v1",
            params={"key": key, "cx": cx, "q": query, "num": num, "lr": "lang_ru"},
            timeout=15,
        )
        items = r.json().get("items", [])
        return [{"title": i.get("title",""), "url": i.get("link",""), "snippet": i.get("snippet","")} for i in items]
    except Exception as e:
        _dbg(f"Google ошибка: {e}")
        return []


def brave_search(query, num=5):
    """Brave Search API — 2000 запросов/месяц бесплатно.
    Регистрация: api.search.brave.com"""
    key = CONFIG.get("brave_search_key", "")
    if not key:
        return []
    try:
        r = requests.get(
            "https://api.search.brave.com/res/v1/web/search",
            params={"q": query, "count": num, "search_lang": "ru", "country": "ru"},
            headers={"Accept": "application/json", "X-Subscription-Token": key},
            timeout=15,
        )
        results = r.json().get("web", {}).get("results", [])
        return [{"title": i.get("title",""), "url": i.get("url",""), "snippet": i.get("description","")} for i in results]
    except Exception as e:
        _dbg(f"Brave ошибка: {e}")
        return []


def fetch_page_text(url, max_chars=3000):
    """Загружает страницу и извлекает читаемый текст (без HTML тегов)."""
    try:
        r = requests.get(
            url,
            headers={"User-Agent": "Mozilla/5.0"},
            timeout=12,
            allow_redirects=True,
        )
        if "text/html" not in r.headers.get("Content-Type", ""):
            return ""
        text = r.text
        # Убираем скрипты и стили
        text = re.sub(r"<script[^>]*>.*?</script>", " ", text, flags=re.DOTALL | re.IGNORECASE)
        text = re.sub(r"<style[^>]*>.*?</style>",   " ", text, flags=re.DOTALL | re.IGNORECASE)
        # Убираем теги
        text = re.sub(r"<[^>]+>", " ", text)
        # HTML entities
        text = re.sub(r"&nbsp;",  " ",  text)
        text = re.sub(r"&amp;",   "&",  text)
        text = re.sub(r"&lt;",    "<",  text)
        text = re.sub(r"&gt;",    ">",  text)
        text = re.sub(r"&[a-z]+;", " ", text)
        text = re.sub(r"\s+", " ", text).strip()
        return text[:max_chars]
    except Exception as e:
        _dbg(f"fetch_page {url}: {e}")
        return ""


def web_search(query, num=5):
    """
    Поиск с фолбэком:
      1. Настроенный провайдер (google/brave)
      2. DuckDuckGo (если основной не сработал)
    Возвращает список {title, url, snippet, [page_text]}.
    """
    provider = CONFIG.get("search_provider", "duckduckgo")

    if provider == "google":
        results = google_search(query, num)
        if not results:
            _dbg("Google вернул 0, фолбэк на DuckDuckGo")
            results = duckduckgo_search(query, num)
    elif provider == "brave":
        results = brave_search(query, num)
        if not results:
            _dbg("Brave вернул 0, фолбэк на DuckDuckGo")
            results = duckduckgo_search(query, num)
    else:
        results = duckduckgo_search(query, num)

    # Загружаем текст со страниц если нужно
    fetch_n = CONFIG.get("fetch_top_pages", 1)
    for r in results[:fetch_n]:
        url = r.get("url", "")
        if url and url.startswith("http"):
            text = fetch_page_text(url, max_chars=2500)
            if text:
                r["page_text"] = text

    return results


def format_results(results, max_chars=2500):
    """Форматирует результаты поиска в текст для AI."""
    lines = []
    for i, r in enumerate(results, 1):
        lines.append(f"{i}. {r.get('title','')}")
        snip = r.get("snippet", "")
        if snip:
            lines.append(f"   {snip}")
        page = r.get("page_text", "")
        if page:
            lines.append(f"   [Текст страницы]: {page[:600]}")
    return "\n".join(lines)[:max_chars]


# ─── Wikipedia ────────────────────────────────────────────────────────────────

def wikipedia_search(query, lang=None):
    lang = lang or CONFIG.get("wiki_lang", "ru")
    try:
        s = requests.get(
            f"https://{lang}.wikipedia.org/w/api.php",
            params={"action":"query","format":"json","list":"search","srsearch":query,"srlimit":1},
            timeout=10,
        ).json()
        hits = s.get("query", {}).get("search", [])
        if not hits:
            return wikipedia_search(query, "en") if lang != "en" else ""
        title = hits[0]["title"]
        p = requests.get(
            f"https://{lang}.wikipedia.org/w/api.php",
            params={"action":"query","format":"json","titles":title,
                    "prop":"extracts","exintro":1,"explaintext":1,"exlimit":1},
            timeout=10,
        ).json()
        for page in p.get("query",{}).get("pages",{}).values():
            t = page.get("extract","")
            if t:
                return t[:2500]
    except Exception as e:
        _dbg(f"Wiki: {e}")
    return ""


# ─── Обогащение данных о книге ────────────────────────────────────────────────

def enrich_book_data(title, author):
    """
    Собирает информацию о книге и авторе из всех доступных источников:
    Wikipedia, поисковик (DDG/Google/Brave), Ruwiki.
    """
    info = {"author_bio": "", "book_info": "", "web_snippets": ""}

    # 1. Ruwiki — самый богатый источник для RU (поиск в интернете через приложение)
    if CONFIG.get("use_ruwiki_search"):
        resp = ruwiki_search(f"Биография писателя {author}")
        if not resp.get("error") and resp.get("result"):
            info["author_bio"] = resp["result"]
            _dbg(f"Ruwiki биография: {len(info['author_bio'])} симв.")

        resp2 = ruwiki_search(f"Книга «{title}» автор {author}: аннотация, о чём книга")
        if not resp2.get("error") and resp2.get("result"):
            info["book_info"] = resp2["result"]

    # 2. Wikipedia — структурированная энциклопедия
    if CONFIG.get("search_wikipedia"):
        if not info["author_bio"]:
            bio = wikipedia_search(author)
            if bio:
                info["author_bio"] = bio
                _dbg(f"Wikipedia биография: {len(bio)} симв.")

        if not info["book_info"]:
            book = wikipedia_search(f"{title} {author}") or wikipedia_search(title)
            if book:
                info["book_info"] = book

    # 3. Поисковик — реальные страницы интернета (LiveLib, FantLab, рецензии и т.д.)
    if CONFIG.get("search_web"):
        all_results = []

        # Биография если ещё не нашли
        if not info["author_bio"]:
            r = web_search(f"{author} биография писатель книги", num=4)
            all_results.extend(r)
            _dbg(f"Поиск биографии: {len(r)} результатов")

        # Данные о книге
        r_book = web_search(f"«{title}» {author} о чём книга аннотация", num=4)
        all_results.extend(r_book)
        _dbg(f"Поиск книги: {len(r_book)} результатов")

        # Специфичные источники
        r_livelib = web_search(f"site:livelib.ru {author} {title}", num=2)
        r_fantlab  = web_search(f"site:fantlab.ru {author}", num=2)
        all_results.extend(r_livelib + r_fantlab)

        if all_results:
            info["web_snippets"] = format_results(all_results, max_chars=3000)
            _dbg(f"Итого web данных: {len(info['web_snippets'])} симв.")

    return info


# ─── Генерация полей через AI ────────────────────────────────────────────────

FIELDS_SYSTEM = "Ты — помощник для заполнения карточки книги. Отвечай ТОЛЬКО валидным JSON без markdown. Пустые поля — пустая строка. Пиши на русском."

def generate_book_fields(title, author, info):
    ctx = ""
    if info.get("author_bio"):
        ctx += f"\n\nБиография автора (из Ruwiki/Wikipedia):\n{info['author_bio'][:1200]}"
    if info.get("book_info"):
        ctx += f"\n\nДанные о книге:\n{info['book_info'][:800]}"
    if info.get("web_snippets"):
        ctx += f"\n\nРезультаты поиска в интернете:\n{info['web_snippets'][:2000]}"

    prompt = f"""
Заполни карточку книги. Используй все предоставленные данные. Верни ТОЛЬКО JSON.

Книга: «{title}»
Автор: {author}{ctx}

Верни JSON:
{{
  "annotation": "Аннотация книги — о чём она, 3-5 предложений",
  "author_bio": "Биография автора — когда родился, чем известен, главные произведения. 3-6 предложений",
  "keywords": "ключевые слова через запятую (жанр, темы, эпоха)"
}}
"""
    resp, err = ask_ai(prompt, FIELDS_SYSTEM)
    if err:
        print(f"  AI ошибка: {err}")
        return {}
    data = _parse_json(resp)
    return data or {}


# ─── AI провайдеры ────────────────────────────────────────────────────────────

def _ask_openrouter(messages):
    key = CONFIG.get("openrouter_key", "")
    if not key:
        return None, "OpenRouter ключ не задан (config.json → openrouter_key)"
    try:
        r = requests.post(
            "https://openrouter.ai/api/v1/chat/completions",
            json={"model": CONFIG.get("openrouter_model", "nvidia/llama-3.1-nemotron-70b-instruct:free"),
                  "messages": messages, "max_tokens": 1500, "temperature": 0.3},
            headers={"Authorization": f"Bearer {key}",
                     "HTTP-Referer": "https://bookstore-agent", "X-Title": "Bookstore AI Agent"},
            timeout=45,
        )
        r.raise_for_status()
        return r.json()["choices"][0]["message"]["content"], None
    except Exception as e:
        return None, str(e)

def _ask_gemini(messages):
    key = CONFIG.get("gemini_key", "")
    if not key:
        return None, "Gemini ключ не задан"
    model = CONFIG.get("gemini_model", "gemini-2.0-flash")
    contents = [{"role": "user" if m["role"] != "assistant" else "model",
                 "parts": [{"text": m["content"]}]} for m in messages]
    try:
        r = requests.post(
            f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={key}",
            json={"contents": contents, "generationConfig": {"maxOutputTokens": 1500, "temperature": 0.3}},
            timeout=45,
        )
        r.raise_for_status()
        return r.json()["candidates"][0]["content"]["parts"][0]["text"], None
    except Exception as e:
        return None, str(e)

def ask_ai(prompt, system=None):
    msgs = []
    if system:
        msgs.append({"role": "system", "content": system})
    msgs.append({"role": "user", "content": prompt})
    provider = CONFIG.get("provider", "openrouter")
    if provider == "openrouter":
        return _ask_openrouter(msgs)
    elif provider == "gemini":
        return _ask_gemini(msgs)
    elif provider == "ruwiki":
        resp = ruwiki_search(prompt)
        if resp.get("error"):
            return None, resp["error"]
        return resp.get("result", ""), None
    return None, f"Неизвестный провайдер: {provider}"

def _parse_json(text):
    if not text:
        return None
    text = re.sub(r"```(?:json)?\s*", "", text).replace("```", "").strip()
    try:
        return json.loads(text)
    except:
        m = re.search(r"\{.*\}", text, re.DOTALL)
        if m:
            try:
                return json.loads(m.group())
            except:
                pass
    return None


# ─── Главный цикл агента ─────────────────────────────────────────────────────

AGENT_SYSTEM = """
Ты — AI агент, управляющий Android-приложением для публикации книг на форум 4PDA.
Вкладки: parser (загрузка/парсинг), translator (перевод), forum (публикация).

Доступные команды (отвечай ТОЛЬКО JSON):
  navigate   → {tab: parser|translator|forum}
  click      → {selector: "#id"}
  fill       → {selector: "#id", value: "текст"}
  select_option → {selector: "#sel", value: "вариант"}
  scroll_to  → {selector: "#id"}
  clipboard_write → {text: "текст"}
  wait       → {ms: 1000}
  js         → {code: "..."}
  done       → {} (задача выполнена)

Формат: {"action":"...","selector":"...","value":"...","tab":"...","ms":0,"text":"...","code":"...","reason":"кратко"}
Отвечай ТОЛЬКО валидным JSON. Никакого текста кроме JSON.
""".strip()

def _fmt_screen(ctx):
    sc  = ctx.get("screen", {})
    st  = ctx.get("appState", {})
    lg  = ctx.get("recentLogs", [])
    els = sc.get("elements", [])
    lines = []
    for el in els[:45]:
        sel  = el.get("selector") or "?"
        tag  = el.get("tag", "")
        txt  = (el.get("text") or "")[:70]
        val  = (el.get("value") or "")[:60]
        acts = el.get("actions") or []
        dis  = " [off]" if el.get("disabled") else ""
        chk  = f" ✓" if el.get("checked") else ""
        line = f"  {sel} [{tag}]{dis}{chk}"
        if txt: line += f"  text='{txt}'"
        if val: line += f"  val='{val}'"
        if acts: line += f"  ← {'/'.join(acts)}"
        lines.append(line)
    return (
        f"Вкладка: {ctx.get('activeTab')} | {sc.get('title','')}\n"
        f"loggedIn={st.get('loggedIn')} publishing={st.get('isPublishing')} files={st.get('stagedFilesCount',0)}\n"
        f"Контент: {sc.get('bodyPreview','')[:250]}\n\n"
        f"Элементы ({len(els)}):\n" + ("\n".join(lines) or "  (нет)") + "\n\n"
        f"Логи:\n" + "\n".join("  " + l for l in lg[-5:])
    )

def agent_loop(task):
    print(f"\n{'─'*60}\nЗадача : {task}\nАгент  : {CONFIG['provider']}\nСервер : {CONFIG['server']}\n{'─'*60}\n")
    history = []
    for step in range(1, CONFIG.get("max_steps", 25) + 1):
        ctx = get_context()
        if "error" in ctx:
            print(f"[!] Нет связи: {ctx['error']}")
            return False

        hist_block = ("\nИстория:\n" + "\n".join(f"  {i+1}. {h}" for i, h in enumerate(history[-6:]))) if history else ""
        prompt = f"Задача: {task}\n\n{_fmt_screen(ctx)}{hist_block}\n\nЧто делать?"

        print(f"[{step:02d}] ", end="", flush=True)
        resp, err = ask_ai(prompt, AGENT_SYSTEM)
        if err:
            print(f"AI ошибка: {err}")
            time.sleep(3); continue
        decision = _parse_json(resp)
        if not decision:
            print(f"Нечитаемый ответ: {(resp or '')[:80]}")
            continue

        act    = decision.get("action", "")
        reason = decision.get("reason", "")
        print(f"{act:15s} {reason}")
        _dbg(json.dumps(decision, ensure_ascii=False))

        if act == "done":
            print(f"\n✓ Выполнено за {step} шагов")
            return True
        elif act == "navigate":
            navigate(decision.get("tab", "parser"))
            history.append(f"navigate({decision.get('tab')})")
        elif act == "click":
            sel = decision.get("selector", "")
            r = click(sel)
            ok = r.get("actionResult", {}).get("ok")
            history.append(f"click('{sel}') → {'ok' if ok else 'FAIL: ' + str(r.get('actionResult',{}).get('error','?'))}")
        elif act == "fill":
            sel, val = decision.get("selector", ""), decision.get("value", "")
            fill(sel, val)
            history.append(f"fill('{sel}', '{val[:30]}')")
        elif act == "select_option":
            select_option(decision.get("selector",""), decision.get("value",""))
            history.append(f"select_option")
        elif act == "scroll_to":
            scroll_to(decision.get("selector",""))
            history.append("scroll_to")
        elif act == "clipboard_write":
            clipboard_write(decision.get("text",""))
            history.append("clipboard_write")
        elif act == "wait":
            wait_app(decision.get("ms", 1000))
            history.append(f"wait({decision.get('ms')}ms)")
        elif act == "js":
            r = run_js(decision.get("code",""))
            history.append(f"js → {r.get('actionResult',{})}")
        else:
            history.append(f"unknown({act})")

        time.sleep(CONFIG.get("step_delay", 1.5))

    print(f"\n✗ Лимит шагов достигнут")
    return False


# ─── Режим заполнения полей ───────────────────────────────────────────────────

def fill_book_fields():
    print("\n═ Заполнение полей книги ════════════════════════")
    ctx = get_context()
    sc  = ctx.get("screen", {})

    title = author = ""
    for el in sc.get("elements", []):
        sel = (el.get("selector") or "").lower()
        val = el.get("value") or ""
        if not title and "title" in sel and val:
            title = val
        if not author and "author" in sel and val:
            author = val

    title  = title  or input("Название книги: ").strip()
    author = author or input("Автор: ").strip()
    if not title or not author:
        print("Нужны название и автор"); return

    print(f"\nКнига: «{title}» — {author}")
    print("Собираю данные из интернета...", flush=True)
    info = enrich_book_data(title, author)

    sources = []
    if info["author_bio"]:  sources.append(f"биография ({len(info['author_bio'])} симв.)")
    if info["book_info"]:   sources.append(f"данные книги ({len(info['book_info'])} симв.)")
    if info["web_snippets"]:sources.append(f"поисковик ({len(info['web_snippets'])} симв.)")
    print(f"Найдено: {', '.join(sources) or 'ничего'}")

    print("Генерирую поля через AI...", end=" ", flush=True)
    fields = generate_book_fields(title, author, info)
    print("готово")

    if not fields:
        print("AI не вернул поля"); return

    print("\nСгенерированные поля:")
    for k, v in fields.items():
        print(f"  {k}: {str(v)[:120]}")

    if input("\nЗаполнить через агента? (y/n): ").strip().lower() != "y":
        return

    task = "Найди и заполни следующие поля на текущем экране:\n" + \
           "\n".join(f"  {k}: {v}" for k, v in fields.items() if v)
    agent_loop(task)


# ─── CLI ─────────────────────────────────────────────────────────────────────

def cmd_screen():
    ctx = get_context()
    print(_fmt_screen(ctx))

def cmd_settings():
    srv = get_settings()
    print("\n═ Настройки приложения (/api/settings):")
    print(json.dumps(srv, ensure_ascii=False, indent=2))
    print(f"\n═ Локальный config.json ({CONFIG_FILE}):")
    for k, v in CONFIG.items():
        print(f"  {k}: {'*'*8 if 'key' in k and v else v}")
    print(f"\nНастройки в браузере: {CONFIG['server']}/settings")

def cmd_search(query):
    print(f"Поиск: {query}\nПровайдер: {CONFIG.get('search_provider','duckduckgo')}\n")

    print("─ DuckDuckGo:")
    for r in duckduckgo_search(query, num=4):
        print(f"  • {r['title']}")
        print(f"    {r['snippet'][:120]}")
        if r.get("page_text"):
            print(f"    [страница]: {r['page_text'][:200]}")

    if CONFIG.get("google_search_key"):
        print("\n─ Google:")
        for r in google_search(query, num=3):
            print(f"  • {r['title']}: {r['snippet'][:100]}")

    if CONFIG.get("brave_search_key"):
        print("\n─ Brave:")
        for r in brave_search(query, num=3):
            print(f"  • {r['title']}: {r['snippet'][:100]}")

def main():
    args = sys.argv[1:]
    if not args or args[0] in ("-h", "--help"):
        print(__doc__); return

    cmd = args[0]
    if cmd == "--screen":
        cmd_screen()
    elif cmd == "--fill":
        fill_book_fields()
    elif cmd == "--settings":
        cmd_settings()
    elif cmd == "--search":
        cmd_search(" ".join(args[1:]) or "Лев Толстой биография")
    elif cmd.startswith("--"):
        print(f"Неизвестная команда: {cmd}")
    else:
        agent_loop(" ".join(args))

if __name__ == "__main__":
    main()
