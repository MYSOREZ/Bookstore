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
 * AI Screen Observation API — local HTTP server on port 8765.
 *
 * Designed for an AI agent that runs in an external script and controls
 * this Android app by observing its UI and sending commands.
 *
 * Endpoints:
 *   GET  /api/status   — server info, active tab, schema
 *   GET  /api/screen   — full DOM snapshot: every visible element with its selector + available actions
 *   GET  /api/context  — screen + appState + recent logs in one call (ideal first call for AI)
 *   GET  /api/logs     — recent app log lines
 *   GET  /api/events   — SSE stream (text/event-stream) for real-time event push
 *   POST /api/action   — execute action; response includes screenAfter so AI sees result immediately
 *
 * Action types (POST /api/action body JSON):
 *   {"type":"navigate", "tab":"parser|translator|forum"}
 *   {"type":"click",    "selector":"#btn-publish",  "waitMs":600}
 *   {"type":"fill",     "selector":"#title-input", "value":"text", "waitMs":600}
 *   {"type":"js",       "code":"document.title",   "waitMs":0}
 *
 * SSE event types pushed on /api/events:
 *   connected, tab_changed, login, publish_progress, error, log
 */
class AIScreenObservationServer(
    port: Int = 8765,
    private val getActiveWebView: () -> Pair<String, WebView?>,
    private val runOnMain: (Runnable) -> Unit,
    private val navigateToTab: (String) -> Unit,
    private val getAppState: () -> JSONObject,
    private val getRecentLogs: () -> List<String>
) : NanoHTTPD(port) {

    // ── SSE client registry ───────────────────────────────────────────────────

    private data class SseClient(
        val send: (String) -> Unit,
        val output: PipedOutputStream
    )

    private val sseClients = CopyOnWriteArrayList<SseClient>()

    /** Push an event to all connected SSE subscribers. */
    fun pushEvent(type: String, data: JSONObject) {
        val msg = "event: $type\ndata: $data\n\n"
        sseClients.removeAll { client ->
            try {
                client.send(msg)
                false
            } catch (_: Exception) {
                runCatching { client.output.close() }
                true
            }
        }
    }

    fun pushEvent(type: String, key: String, value: String) {
        pushEvent(type, JSONObject().apply { put(key, value) })
    }

    // ── Router ────────────────────────────────────────────────────────────────

    override fun serve(session: IHTTPSession): Response = try {
        when {
            session.method == Method.OPTIONS            -> corsOk()
            session.uri == "/api/status"               -> handleStatus()
            session.uri == "/api/screen"               -> handleScreen()
            session.uri == "/api/context"              -> handleContext()
            session.uri == "/api/logs"                 -> handleLogs()
            session.uri == "/api/events"               -> handleEvents()
            session.uri == "/api/action"
                && session.method == Method.POST       -> handleAction(session)
            else -> json(Response.Status.NOT_FOUND, errorJson("unknown endpoint — see GET /api/status"))
        }
    } catch (e: Exception) {
        json(Response.Status.INTERNAL_ERROR, errorJson(e.message ?: "internal error"))
    }

    // ── GET /api/status ───────────────────────────────────────────────────────

    private fun handleStatus(): Response {
        val (tab, _) = getActiveWebView()
        return json(Response.Status.OK, JSONObject().apply {
            put("running",   true)
            put("activeTab", tab)
            put("port",      8765)
            put("endpoints", JSONArray().apply {
                put("GET  /api/status   — server info + action schema")
                put("GET  /api/screen   — full DOM snapshot of current WebView")
                put("GET  /api/context  — screen + appState + logs in one call (start here)")
                put("GET  /api/logs     — recent app log lines")
                put("GET  /api/events   — SSE stream, subscribe for real-time updates")
                put("POST /api/action   — execute action; returns screenAfter automatically")
            })
            put("actionSchema", JSONObject().apply {
                put("navigate", JSONObject().apply {
                    put("tab", "parser | translator | forum")
                })
                put("click", JSONObject().apply {
                    put("selector", "<css selector>")
                    put("waitMs",   "optional ms to wait before capturing screenAfter (default 600)")
                })
                put("fill", JSONObject().apply {
                    put("selector", "<css selector>")
                    put("value",    "<text to type>")
                    put("waitMs",   "optional (default 600)")
                })
                put("js", JSONObject().apply {
                    put("code",   "<javascript expression>")
                    put("waitMs", "optional (default 0)")
                })
            })
            put("sseEventTypes", JSONArray().apply {
                put("connected       — fires once on subscribe")
                put("tab_changed     — user or AI switched tab")
                put("login           — {loggedIn:bool, username:str}")
                put("publish_progress— {stage:str} upload_files|fill_form|complete|error")
                put("error           — {message:str}")
                put("log             — {line:str} important app log entry")
            })
        }.toString())
    }

    // ── GET /api/screen ───────────────────────────────────────────────────────

    private fun handleScreen(): Response {
        val (tab, wv) = getActiveWebView()
        if (wv == null) return json(Response.Status.OK, emptyScreen(tab))
        val result = runBlocking {
            withTimeoutOrNull(6_000) { queryDom(wv, tab) } ?: emptyScreen(tab, "DOM query timed out")
        }
        return json(Response.Status.OK, result)
    }

    // ── GET /api/context ──────────────────────────────────────────────────────

    private fun handleContext(): Response {
        val (tab, wv) = getActiveWebView()
        val screenJson = if (wv != null) {
            runBlocking { withTimeoutOrNull(6_000) { queryDom(wv, tab) } ?: emptyScreen(tab) }
        } else {
            emptyScreen(tab)
        }
        val ctx = JSONObject().apply {
            put("activeTab",  tab)
            put("appState",   getAppState())
            put("screen",     JSONObject(screenJson))
            put("recentLogs", JSONArray().apply { getRecentLogs().forEach { put(it) } })
            put("tip", "Call POST /api/action to interact. Each action response includes 'screenAfter' so you see results without an extra GET /api/screen call.")
        }
        return json(Response.Status.OK, ctx.toString())
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

        val client = SseClient(
            send = { msg -> writer.write(msg); writer.flush() },
            output = output
        )
        sseClients.add(client)

        // Initial event so the AI knows the connection is live
        runCatching {
            val (tab, _) = getActiveWebView()
            client.send(
                "event: connected\ndata: ${
                    JSONObject().apply {
                        put("activeTab", tab)
                        put("connectedClients", sseClients.size)
                    }
                }\n\n"
            )
        }

        return newChunkedResponse(Response.Status.OK, "text/event-stream", input).also {
            it.addHeader("Cache-Control",                "no-cache")
            it.addHeader("Connection",                   "keep-alive")
            it.addHeader("Access-Control-Allow-Origin",  "*")
        }
    }

    // ── POST /api/action ──────────────────────────────────────────────────────

    private fun handleAction(session: IHTTPSession): Response {
        val body = HashMap<String, String>()
        session.parseBody(body)
        val raw = body["postData"]?.takeIf { it.isNotBlank() }
            ?: return json(Response.Status.BAD_REQUEST, errorJson("request body is empty"))

        val req = runCatching { JSONObject(raw) }.getOrElse {
            return json(Response.Status.BAD_REQUEST, errorJson("invalid JSON"))
        }

        val type = req.optString("type").takeIf { it.isNotBlank() }
            ?: return json(Response.Status.BAD_REQUEST, errorJson("missing 'type' field"))

        val waitMs = req.optLong("waitMs", 600L).coerceIn(0L, 5_000L)

        // navigate — no WebView needed
        if (type == "navigate") {
            val tab = req.optString("tab").takeIf { it.isNotBlank() }
                ?: return json(Response.Status.BAD_REQUEST, errorJson("missing 'tab'"))
            runOnMain { navigateToTab(tab) }
            Thread.sleep(400)
            return actionResponse(
                req    = req,
                result = JSONObject().apply { put("ok", true); put("navigated", tab) },
                waitMs = 0
            )
        }

        val (tab, wv) = getActiveWebView()
        if (wv == null) return json(Response.Status.OK, errorJson("no active WebView"))

        val actionJs = when (type) {
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
            "js" -> {
                val code = req.optString("code").takeIf { it.isNotBlank() }
                    ?: return json(Response.Status.BAD_REQUEST, errorJson("missing 'code'"))
                wrapJs(code)
            }
            else -> return json(Response.Status.BAD_REQUEST, errorJson("unknown action type: $type"))
        }

        val actionResult = runBlocking {
            withTimeoutOrNull(8_000) { evalJs(wv, actionJs) } ?: errorJson("action timed out")
        }

        return actionResponse(req, JSONObject(actionResult), waitMs, wv, tab)
    }

    /** Build the standard action response: {ok, action, actionResult, screenAfter}. */
    private fun actionResponse(
        req:       JSONObject,
        result:    JSONObject,
        waitMs:    Long,
        wv:        WebView? = null,
        tab:       String   = getActiveWebView().first
    ): Response {
        if (waitMs > 0) Thread.sleep(waitMs)

        val screenAfterJson = if (wv != null) {
            runBlocking { withTimeoutOrNull(5_000) { queryDom(wv, tab) } ?: emptyScreen(tab) }
        } else {
            val (t, w) = getActiveWebView()
            if (w != null) runBlocking { withTimeoutOrNull(5_000) { queryDom(w, t) } ?: emptyScreen(t) }
            else emptyScreen(tab)
        }

        return json(Response.Status.OK, JSONObject().apply {
            put("ok",           result.optBoolean("ok", true))
            put("action",       req)
            put("actionResult", result)
            put("screenAfter",  JSONObject(screenAfterJson))
        }.toString())
    }

    // ── DOM snapshot ──────────────────────────────────────────────────────────

    private suspend fun queryDom(wv: WebView, tab: String): String =
        suspendCancellableCoroutine { cont ->
            val js = """
                (function() {
                    try {
                        var elements = [];
                        var seen = {};
                        var nodes = document.querySelectorAll(
                            'button, input, textarea, select, [role="button"], a[href], [onclick]'
                        );
                        for (var i = 0; i < nodes.length; i++) {
                            var el = nodes[i];
                            var rect = el.getBoundingClientRect();
                            if (rect.width === 0 && rect.height === 0) continue;
                            var st = window.getComputedStyle(el);
                            if (st.display === 'none' || st.visibility === 'hidden') continue;

                            var sel = null;
                            if (el.id)        sel = '#' + el.id;
                            else if (el.name) sel = '[name="' + el.name + '"]';
                            else if (el.className) {
                                var cls = el.className.toString().trim().split(/\s+/)[0];
                                if (cls) sel = el.tagName.toLowerCase() + '.' + cls;
                            }
                            if (sel && seen[sel]) continue;
                            if (sel) seen[sel] = true;

                            var tag    = el.tagName.toLowerCase();
                            var isChk  = el.type === 'checkbox' || el.type === 'radio';
                            var acts   = [];
                            var isBtn  = tag === 'button' || tag === 'a' || el.onclick ||
                                         el.getAttribute('role') === 'button';
                            if (isBtn)  acts.push('click');
                            if (isChk)  acts.push('click');
                            if ((tag === 'input' && !isChk) || tag === 'textarea' || tag === 'select')
                                        acts.push('fill');

                            elements.push({
                                tag:         tag,
                                selector:    sel,
                                id:          el.id   || null,
                                name:        el.name || null,
                                type:        el.type || null,
                                text:        (el.textContent || el.innerText || '').trim().substring(0, 150),
                                value:       el.value !== undefined ? el.value.substring(0, 300) : null,
                                placeholder: el.placeholder || null,
                                disabled:    !!el.disabled,
                                checked:     isChk ? el.checked : null,
                                href:        tag === 'a' ? el.href : null,
                                actions:     acts
                            });
                        }
                        return JSON.stringify({
                            activeTab:   '$tab',
                            title:       document.title,
                            url:         window.location.href,
                            bodyPreview: (document.body ? document.body.innerText : '')
                                            .trim().substring(0, 400),
                            elements:    elements
                        });
                    } catch(e) {
                        return JSON.stringify({activeTab:'$tab', error: String(e), elements: []});
                    }
                })()
            """.trimIndent()

            runOnMain {
                wv.evaluateJavascript(js) { raw -> cont.resume(unquote(raw)) }
            }
        }

    // ── JS action builders ────────────────────────────────────────────────────

    private fun clickJs(sel: String): String {
        val e = sel.replace("'", "\\'")
        return "(function(){ var el=document.querySelector('$e'); if(!el) return JSON.stringify({ok:false,error:'not found: $e'}); el.click(); return JSON.stringify({ok:true,tag:el.tagName,text:(el.textContent||'').trim().substring(0,80)}); })()"
    }

    private fun fillJs(sel: String, value: String): String {
        val es = sel.replace("'", "\\'")
        val ev = value.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")
        return "(function(){ var el=document.querySelector('$es'); if(!el) return JSON.stringify({ok:false,error:'not found: $es'}); el.value='$ev'; el.dispatchEvent(new Event('input',{bubbles:true})); el.dispatchEvent(new Event('change',{bubbles:true})); return JSON.stringify({ok:true,newValue:el.value.substring(0,100)}); })()"
    }

    private fun wrapJs(code: String): String =
        "(function(){ try{ var r=($code); return JSON.stringify({ok:true,result:r}); }catch(e){ return JSON.stringify({ok:false,error:String(e)}); } })()"

    private suspend fun evalJs(wv: WebView, js: String): String =
        suspendCancellableCoroutine { cont ->
            runOnMain { wv.evaluateJavascript(js) { raw -> cont.resume(unquote(raw)) } }
        }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun emptyScreen(tab: String, error: String? = null) = JSONObject().apply {
        put("activeTab", tab)
        put("elements",  JSONArray())
        if (error != null) put("error", error)
    }.toString()

    /** evaluateJavascript wraps string returns in quotes — strip them. */
    private fun unquote(raw: String?): String {
        if (raw == null || raw == "null") return "{}"
        val t = raw.trim()
        return if (t.startsWith("\"") && t.endsWith("\""))
            t.substring(1, t.length - 1)
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\/", "/")
        else t
    }

    private fun errorJson(msg: String) =
        """{"ok":false,"error":"${msg.replace("\"", "'")}"}"""

    private fun json(status: Response.Status, body: String): Response =
        newFixedLengthResponse(status, "application/json", body).also { addCors(it) }

    private fun corsOk(): Response =
        newFixedLengthResponse(Response.Status.OK, "text/plain", "").also { addCors(it) }

    private fun addCors(r: Response) {
        r.addHeader("Access-Control-Allow-Origin",  "*")
        r.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        r.addHeader("Access-Control-Allow-Headers", "Content-Type")
    }
}
