#!/usr/bin/env python3
"""
Bookstore AI Agent
==================
Управляет Android-приложением Bookstore через AI Screen Observation API.
Поддерживает OpenRouter (бесплатные модели), Gemini и Ruwiki AI.
Обогащает данные о книгах через Wikipedia.

Использование:
  python agent.py "загрузи книгу и опубликуй её"
  python agent.py --screen          # посмотреть текущий экран
  python agent.py --fill            # AI заполняет поля книги
  python agent.py --settings        # показать настройки
  python agent.py --context         # полный контекст приложения

Зависимости: pip install requests
"""

import json
import re
import sys
import time
import os

try:
    import requests
except ImportError:
    print("Установите requests: pip install requests")
    sys.exit(1)

# ─── Конфигурация ─────────────────────────────────────────────────────────────

DEFAULT_CONFIG = {
    # Адрес сервера (localhost если на том же телефоне, иначе IP телефона)
    "server": "http://localhost:8765",

    # AI провайдер: openrouter | gemini | ruwiki
    "provider": "openrouter",

    # OpenRouter
    "openrouter_key": "",
    "openrouter_model": "nvidia/llama-3.1-nemotron-70b-instruct:free",

    # Gemini
    "gemini_key": "",
    "gemini_model": "gemini-2.0-flash",

    # Поведение агента
    "max_steps": 25,
    "step_delay": 1.5,

    # Поиск данных для заполнения полей
    "search_wikipedia": True,
    "wiki_lang": "ru",           # Язык поиска: ru | en
    "use_ruwiki_search": True,   # Поиск через Ruwiki (встроен в приложение)

    "debug": False,
}

CONFIG_FILE = os.path.join(os.path.dirname(__file__), "config.json")
CONFIG = dict(DEFAULT_CONFIG)
try:
    with open(CONFIG_FILE) as f:
        CONFIG.update(json.load(f))
except FileNotFoundError:
    pass


# ─── Bookstore API ────────────────────────────────────────────────────────────

BASE = CONFIG["server"].rstrip("/")
SESSION = requests.Session()
SESSION.headers["Content-Type"] = "application/json"

def _get(path, **kwargs):
    try:
        r = SESSION.get(BASE + path, timeout=15, **kwargs)
        r.raise_for_status()
        return r.json()
    except Exception as e:
        return {"error": str(e)}

def _post(path, body=None, **kwargs):
    try:
        r = SESSION.post(BASE + path, json=body or {}, timeout=30, **kwargs)
        r.raise_for_status()
        return r.json()
    except Exception as e:
        return {"error": str(e)}

# Состояние приложения
def get_context():    return _get("/api/context")
def get_screen():     return _get("/api/screen")
def get_logs():       return _get("/api/logs")
def get_settings():   return _get("/api/settings")

def save_settings(data: dict):
    return _post("/api/settings/save", data)

# Действия с UI
def do_action(type_, **kwargs):
    return _post("/api/action", {"type": type_, **kwargs})

def click(selector, wait_ms=800):
    return do_action("click", selector=selector, waitMs=wait_ms)

def fill(selector, value, wait_ms=600):
    return do_action("fill", selector=selector, value=value, waitMs=wait_ms)

def navigate(tab):
    return do_action("navigate", tab=tab)

def wait(ms):
    return do_action("wait", ms=ms)

def scroll_to(selector):
    return do_action("scroll_to", selector=selector)

def run_js(code, wait_ms=0):
    return do_action("js", code=code, waitMs=wait_ms)

def clipboard_write(text):
    return do_action("clipboard_write", text=text)

def clipboard_read():
    return do_action("clipboard_read")

def get_value(selector):
    return do_action("get_value", selector=selector)

def select_option(selector, value):
    return do_action("select_option", selector=selector, value=value)

# AI поиск через Ruwiki (встроен в приложение)
def ruwiki_search(query):
    return _post("/api/ai/search", {"query": query})


# ─── Поиск информации ─────────────────────────────────────────────────────────

def wikipedia_search(query, lang=None):
    """Ищет в Википедии и возвращает вводный текст статьи."""
    lang = lang or CONFIG.get("wiki_lang", "ru")
    try:
        q = requests.utils.quote(query)
        # Поиск по заголовку
        s = requests.get(
            f"https://{lang}.wikipedia.org/w/api.php",
            params={"action":"query","format":"json","list":"search",
                    "srsearch":query,"srlimit":1,"srprop":"snippet"},
            timeout=10
        ).json()
        results = s.get("query", {}).get("search", [])
        if not results:
            if lang != "en":
                return wikipedia_search(query, "en")
            return ""
        title = results[0]["title"]
        # Текст статьи
        p = requests.get(
            f"https://{lang}.wikipedia.org/w/api.php",
            params={"action":"query","format":"json","titles":title,
                    "prop":"extracts","exintro":1,"explaintext":1,"exlimit":1},
            timeout=10
        ).json()
        pages = p.get("query", {}).get("pages", {})
        for page in pages.values():
            text = page.get("extract", "")
            if text:
                return text[:2500]
    except Exception as e:
        if CONFIG.get("debug"):
            print(f"  [Wiki] {e}")
    return ""

def enrich_book_data(title, author):
    """Получает биографию автора и описание книги из доступных источников."""
    result = {"author_bio": "", "book_info": ""}

    if CONFIG.get("use_ruwiki_search") and CONFIG.get("provider") == "ruwiki":
        # Ruwiki — через приложение (имеет доступ к интернету)
        resp = ruwiki_search(f"Биография автора {author}")
        if not resp.get("error"):
            result["author_bio"] = resp.get("result", "")
        resp2 = ruwiki_search(f"Книга {title} автор {author} аннотация")
        if not resp2.get("error"):
            result["book_info"] = resp2.get("result", "")
    else:
        # Wikipedia для OpenRouter / Gemini
        if CONFIG.get("search_wikipedia"):
            bio = wikipedia_search(author)
            if bio:
                result["author_bio"] = bio
                _dbg(f"[Wiki] Биография найдена ({len(bio)} симв.)")
            book = wikipedia_search(f"{title} {author}")
            if not book:
                book = wikipedia_search(title)
            if book:
                result["book_info"] = book
                _dbg(f"[Wiki] Данные книги найдены ({len(book)} симв.)")

        # Дополнительно через Ruwiki если разрешено
        if CONFIG.get("use_ruwiki_search") and not result["author_bio"]:
            resp = ruwiki_search(f"Биография {author}")
            if not resp.get("error"):
                result["author_bio"] = resp.get("result", "")

    return result


# ─── AI провайдеры ────────────────────────────────────────────────────────────

def _ask_openrouter(messages):
    key = CONFIG.get("openrouter_key", "")
    if not key:
        return None, "OpenRouter ключ не задан. Добавьте в config.json или /settings"
    try:
        r = requests.post(
            "https://openrouter.ai/api/v1/chat/completions",
            json={
                "model": CONFIG.get("openrouter_model", "nvidia/llama-3.1-nemotron-70b-instruct:free"),
                "messages": messages,
                "max_tokens": 1500,
                "temperature": 0.3,
            },
            headers={
                "Authorization": f"Bearer {key}",
                "HTTP-Referer": "https://bookstore-agent",
                "X-Title": "Bookstore AI Agent",
            },
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
    contents = [
        {"role": "user" if m["role"] != "assistant" else "model",
         "parts": [{"text": m["content"]}]}
        for m in messages
    ]
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

def _ask_ruwiki(prompt):
    resp = ruwiki_search(prompt)
    if resp.get("error"):
        return None, resp["error"]
    return resp.get("result", ""), None

def ask_ai(prompt, system=None):
    """Вызывает текущий AI провайдер."""
    messages = []
    if system:
        messages.append({"role": "system", "content": system})
    messages.append({"role": "user", "content": prompt})

    provider = CONFIG.get("provider", "openrouter")
    if provider == "openrouter":
        return _ask_openrouter(messages)
    elif provider == "gemini":
        return _ask_gemini(messages)
    elif provider == "ruwiki":
        return _ask_ruwiki(prompt)
    else:
        return None, f"Неизвестный провайдер: {provider}"

def _parse_json(text):
    """Извлекает JSON из текста AI-ответа."""
    if not text:
        return None
    text = text.strip()
    # Убираем markdown code blocks
    text = re.sub(r'```(?:json)?\s*', '', text).replace('```', '')
    try:
        return json.loads(text)
    except:
        m = re.search(r'\{.*\}', text, re.DOTALL)
        if m:
            try:
                return json.loads(m.group())
            except:
                pass
    return None

def _dbg(msg):
    if CONFIG.get("debug"):
        print(f"  [DBG] {msg}")


# ─── Генерация полей книги через AI ──────────────────────────────────────────

FIELDS_SYSTEM = """
Ты — помощник для заполнения карточки книги.
Отвечай ТОЛЬКО валидным JSON без markdown. Пустые поля — пустая строка "".
Пиши на русском языке.
""".strip()

def generate_book_fields(title, author, author_bio="", book_info=""):
    wiki_ctx = ""
    if author_bio:
        wiki_ctx += f"\nИнформация об авторе:\n{author_bio[:1000]}"
    if book_info:
        wiki_ctx += f"\nИнформация о книге:\n{book_info[:800]}"

    prompt = f"""
Заполни карточку книги. Верни ТОЛЬКО JSON.

Книга: «{title}»
Автор: {author}{wiki_ctx}

JSON формат:
{{
  "annotation": "Аннотация книги (3-5 предложений, о чём книга)",
  "author_bio": "Биография автора (3-5 предложений: когда родился, чем известен, главные произведения)",
  "keywords": "ключевые слова через запятую"
}}
"""
    resp, err = ask_ai(prompt, FIELDS_SYSTEM)
    if err:
        print(f"  AI ошибка: {err}")
        return {}
    result = _parse_json(resp)
    return result or {}


# ─── Главный цикл агента ─────────────────────────────────────────────────────

AGENT_SYSTEM = """
Ты — AI агент, управляющий Android-приложением для публикации книг на форум 4PDA.
Приложение: Bookstore. Вкладки: parser (загрузка/парсинг файлов), translator (перевод), forum (публикация).

Ты видишь текущий экран — список элементов с их CSS-селекторами и доступными действиями (actions).
Доступные команды:
  click      {selector}           — нажать кнопку или ссылку
  fill       {selector, value}    — заполнить текстовое поле
  navigate   {tab}                — переключить вкладку: parser|translator|forum
  js         {code}               — выполнить JavaScript
  wait       {ms}                 — подождать N миллисекунд
  scroll_to  {selector}           — прокрутить к элементу
  clipboard_write {text}          — записать в буфер обмена
  done                            — задача выполнена

ВАЖНО: отвечай ТОЛЬКО валидным JSON, без markdown, без пояснений.
Формат: {"action":"...","selector":"...","value":"...","tab":"...","ms":0,"code":"...","text":"...","reason":"кратко почему"}
""".strip()

def _format_screen(ctx):
    screen = ctx.get("screen", {})
    state  = ctx.get("appState", {})
    logs   = ctx.get("recentLogs", [])
    els    = screen.get("elements", [])

    el_lines = []
    for el in els[:45]:
        sel  = el.get("selector") or "?"
        tag  = el.get("tag", "")
        txt  = (el.get("text") or "")[:70]
        val  = (el.get("value") or "")[:60]
        acts = el.get("actions") or []
        dis  = " [off]" if el.get("disabled") else ""
        chk  = f" checked={el['checked']}" if el.get("checked") is not None else ""
        line = f"  {sel} [{tag}]{dis}{chk}"
        if txt: line += f"  text='{txt}'"
        if val: line += f"  val='{val}'"
        if acts: line += f"  ← {'/'.join(acts)}"
        el_lines.append(line)

    return f"""Вкладка: {ctx.get('activeTab')} | {screen.get('title','')}
loggedIn={state.get('loggedIn')} publishing={state.get('isPublishing')} files={state.get('stagedFilesCount',0)}
Контент: {screen.get('bodyPreview','')[:250]}

Элементы ({len(els)}):
{chr(10).join(el_lines) or '  (нет)'}

Логи:
{chr(10).join('  '+l for l in logs[-6:])}"""

def agent_loop(task):
    print(f"\n{'─'*60}")
    print(f"Задача : {task}")
    print(f"Агент  : {CONFIG['provider']} | {CONFIG.get('openrouter_model','')}")
    print(f"Сервер : {CONFIG['server']}")
    print(f"{'─'*60}\n")

    history = []
    max_steps = CONFIG.get("max_steps", 25)

    for step in range(1, max_steps + 1):
        ctx = get_context()
        if "error" in ctx:
            print(f"[!] Нет связи с приложением: {ctx['error']}")
            print(f"    Запустите приложение и проверьте адрес в config.json")
            return False

        screen_text = _format_screen(ctx)
        history_block = ""
        if history:
            history_block = "\nИстория шагов:\n" + "\n".join(
                f"  {i+1}. {h}" for i, h in enumerate(history[-6:])
            )

        prompt = f"Задача: {task}\n\n{screen_text}{history_block}\n\nЧто делать?"

        print(f"[{step:02d}/{max_steps}] ", end="", flush=True)
        resp, err = ask_ai(prompt, AGENT_SYSTEM)
        if err:
            print(f"AI ошибка: {err}")
            time.sleep(3)
            continue
        if not resp:
            print("нет ответа, повтор...")
            time.sleep(2)
            continue

        decision = _parse_json(resp)
        if not decision:
            print(f"нечитаемый ответ: {resp[:80]}")
            continue

        act    = decision.get("action", "")
        reason = decision.get("reason", "")
        print(f"{act:15s} {reason}")
        _dbg(json.dumps(decision, ensure_ascii=False))

        result = {}
        if act == "done":
            print(f"\n✓ Задача выполнена за {step} шагов")
            return True

        elif act == "navigate":
            result = navigate(decision.get("tab", "parser"))
            history.append(f"navigate({decision.get('tab')})")

        elif act == "click":
            sel = decision.get("selector", "")
            result = click(sel)
            ok = result.get("actionResult", {}).get("ok")
            history.append(f"click('{sel}') → {'ok' if ok else 'FAIL'}")
            if not ok:
                print(f"     ✗ {result.get('actionResult',{}).get('error','?')}")

        elif act == "fill":
            sel = decision.get("selector", "")
            val = decision.get("value", "")
            result = fill(sel, val)
            ok = result.get("actionResult", {}).get("ok")
            history.append(f"fill('{sel}', '{val[:30]}') → {'ok' if ok else 'FAIL'}")

        elif act == "wait":
            ms = int(decision.get("ms", 1000))
            wait(ms)
            history.append(f"wait({ms}ms)")

        elif act == "scroll_to":
            scroll_to(decision.get("selector", ""))
            history.append(f"scroll_to")

        elif act == "js":
            code = decision.get("code", "")
            result = run_js(code)
            history.append(f"js({code[:30]})")

        elif act == "clipboard_write":
            clipboard_write(decision.get("text", ""))
            history.append("clipboard_write")

        elif act == "clipboard_read":
            result = clipboard_read()
            txt = result.get("actionResult", {}).get("text", "")
            history.append(f"clipboard_read → '{txt[:30]}'")

        else:
            print(f"     Неизвестное действие: {act}")
            history.append(f"unknown({act})")

        time.sleep(CONFIG.get("step_delay", 1.5))

    print(f"\n✗ Лимит шагов достигнут ({max_steps})")
    return False


# ─── Режим заполнения полей ───────────────────────────────────────────────────

def fill_book_fields():
    """AI читает экран, ищет информацию в интернете и заполняет поля."""
    print("\n═ Заполнение полей книги ════════════════════════")
    ctx = get_context()
    screen = ctx.get("screen", {})

    # Пытаемся взять название и автора с экрана
    title = author = ""
    for el in screen.get("elements", []):
        sel = (el.get("selector") or "").lower()
        val = el.get("value") or ""
        if "title" in sel and val and not title:
            title = val
        if "author" in sel and val and not author:
            author = val

    if not title:
        title = input("Название книги: ").strip()
    else:
        print(f"Название (с экрана): {title}")

    if not author:
        author = input("Автор: ").strip()
    else:
        print(f"Автор (с экрана): {author}")

    if not title or not author:
        print("Название и автор обязательны")
        return

    print(f"\nИщу данные для «{title}» / {author}...")
    info = enrich_book_data(title, author)

    print("Генерирую поля через AI...", end=" ", flush=True)
    fields = generate_book_fields(title, author, info["author_bio"], info["book_info"])
    print("готово")

    if not fields:
        print("AI не вернул поля, проверьте настройки провайдера")
        return

    print("\nСгенерированные поля:")
    for k, v in fields.items():
        print(f"  {k}: {str(v)[:120]}")

    if input("\nЗаполнить через агента? (y/n): ").strip().lower() != "y":
        return

    parts = [f"поле '{k}': {v}" for k, v in fields.items() if v]
    task = f"Найди и заполни следующие поля на текущем экране:\n" + "\n".join(parts)
    agent_loop(task)


# ─── CLI ─────────────────────────────────────────────────────────────────────

def cmd_screen():
    ctx = get_context()
    print(_format_screen(ctx))

def cmd_settings():
    srv = get_settings()
    print("\n═ Настройки приложения (из /api/settings) ═══")
    print(json.dumps(srv, ensure_ascii=False, indent=2))
    print("\n═ Локальный config.json ═══")
    for k, v in CONFIG.items():
        masked = ("*"*8) if ("key" in k and v) else v
        print(f"  {k}: {masked}")
    print(f"\nОткройте {CONFIG['server']}/settings в браузере для изменения настроек.")

def cmd_context():
    ctx = get_context()
    print(json.dumps(ctx, ensure_ascii=False, indent=2))

def main():
    args = sys.argv[1:]

    if not args or args[0] in ("-h", "--help"):
        print(__doc__)
        return

    cmd = args[0]
    if cmd == "--screen":
        cmd_screen()
    elif cmd == "--fill":
        fill_book_fields()
    elif cmd == "--settings":
        cmd_settings()
    elif cmd == "--context":
        cmd_context()
    elif cmd.startswith("--"):
        print(f"Неизвестная команда: {cmd}\nИспользуйте --help")
    else:
        agent_loop(" ".join(args))

if __name__ == "__main__":
    main()
