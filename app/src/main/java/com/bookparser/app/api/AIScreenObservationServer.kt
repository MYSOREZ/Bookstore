package com.bookparser.app.api

import android.webkit.WebView
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * Local HTTP server that exposes the app's UI state and accepts action commands.
 * An external AI agent can connect to http://localhost:8765 to "see" and "control"
 * the application — analogous to how Comet Browser works for web pages.
 *
 * Endpoints:
 *   GET  /api/status  — server info and active tab
 *   GET  /api/screen  — current WebView DOM state (all interactive elements)
 *   POST /api/action  — execute an action (click / fill / navigate / js)
 */
class AIScreenObservationServer(
    port: Int = 8765,
    private val getActiveWebView: () -> Pair<String, WebView?>,
    private val runOnMain: (Runnable) -> Unit,
    private val navigateToTab: (String) -> Unit
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        return try {
            when {
                session.method == Method.OPTIONS -> corsOk()
                session.uri == "/api/status" -> handleStatus()
                session.uri == "/api/screen" && session.method == Method.GET -> handleScreen()
                session.uri == "/api/action" && session.method == Method.POST -> handleAction(session)
                else -> json(Response.Status.NOT_FOUND, """{"error":"unknown endpoint"}""")
            }
        } catch (e: Exception) {
            json(Response.Status.INTERNAL_ERROR, errorJson(e.message ?: "internal error"))
        }
    }

    // ── GET /api/status ───────────────────────────────────────────────────────

    private fun handleStatus(): Response {
        val (tab, _) = getActiveWebView()
        return json(
            Response.Status.OK,
            JSONObject().apply {
                put("running", true)
                put("activeTab", tab)
                put("port", 8765)
                put("endpoints", JSONArray().apply {
                    put("GET  /api/status  — server info")
                    put("GET  /api/screen  — current UI state")
                    put("POST /api/action  — execute action")
                })
                put("actionTypes", JSONArray().apply {
                    put("navigate  {tab: 'parser'|'translator'|'forum'}")
                    put("click     {selector: '<css>'}")
                    put("fill      {selector: '<css>', value: '<text>'}")
                    put("js        {code: '<javascript>'}")
                })
            }.toString()
        )
    }

    // ── GET /api/screen ───────────────────────────────────────────────────────

    private fun handleScreen(): Response {
        val (tab, webView) = getActiveWebView()
        if (webView == null) {
            return json(
                Response.Status.OK,
                JSONObject().apply {
                    put("activeTab", tab)
                    put("elements", JSONArray())
                }.toString()
            )
        }

        val result = runBlocking {
            withTimeoutOrNull(6_000) { queryDom(webView, tab) }
                ?: JSONObject().apply {
                    put("activeTab", tab)
                    put("error", "DOM query timed out")
                    put("elements", JSONArray())
                }.toString()
        }
        return json(Response.Status.OK, result)
    }

    private suspend fun queryDom(webView: WebView, tab: String): String {
        return suspendCancellableCoroutine { cont ->
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

                            var isCheck = el.type === 'checkbox' || el.type === 'radio';
                            elements.push({
                                tag:         el.tagName.toLowerCase(),
                                selector:    sel,
                                id:          el.id   || null,
                                name:        el.name || null,
                                type:        el.type || null,
                                text:        (el.textContent || el.innerText || '').trim().substring(0, 150),
                                value:       el.value !== undefined ? el.value.substring(0, 300) : null,
                                placeholder: el.placeholder || null,
                                disabled:    !!el.disabled,
                                checked:     isCheck ? el.checked : null,
                                href:        el.tagName.toLowerCase() === 'a' ? el.href : null
                            });
                        }
                        return JSON.stringify({
                            activeTab:   '$tab',
                            title:       document.title,
                            url:         window.location.href,
                            bodyPreview: (document.body ? document.body.innerText : '').trim().substring(0, 400),
                            elements:    elements
                        });
                    } catch(e) {
                        return JSON.stringify({activeTab:'$tab', error: String(e), elements: []});
                    }
                })()
            """.trimIndent()

            runOnMain {
                webView.evaluateJavascript(js) { raw ->
                    cont.resume(unquote(raw))
                }
            }
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

        if (type == "navigate") {
            val tab = req.optString("tab").takeIf { it.isNotBlank() }
                ?: return json(Response.Status.BAD_REQUEST, errorJson("missing 'tab' field"))
            runOnMain { navigateToTab(tab) }
            return json(Response.Status.OK, """{"ok":true,"navigated":"$tab"}""")
        }

        val (_, webView) = getActiveWebView()
        if (webView == null) {
            return json(Response.Status.OK, errorJson("no active WebView"))
        }

        val js = when (type) {
            "click" -> {
                val sel = req.optString("selector").takeIf { it.isNotBlank() }
                    ?: return json(Response.Status.BAD_REQUEST, errorJson("missing 'selector'"))
                clickJs(sel)
            }
            "fill" -> {
                val sel = req.optString("selector").takeIf { it.isNotBlank() }
                    ?: return json(Response.Status.BAD_REQUEST, errorJson("missing 'selector'"))
                val value = req.optString("value")
                fillJs(sel, value)
            }
            "js" -> {
                val code = req.optString("code").takeIf { it.isNotBlank() }
                    ?: return json(Response.Status.BAD_REQUEST, errorJson("missing 'code'"))
                wrapJs(code)
            }
            else -> return json(Response.Status.BAD_REQUEST, errorJson("unknown type: $type"))
        }

        val result = runBlocking {
            withTimeoutOrNull(8_000) { evalJs(webView, js) }
                ?: """{"ok":false,"error":"action timed out"}"""
        }
        return json(Response.Status.OK, result)
    }

    private fun clickJs(selector: String): String {
        val esc = selector.replace("'", "\\'")
        return """
            (function(){
                var el = document.querySelector('$esc');
                if (!el) return JSON.stringify({ok:false,error:'not found: $esc'});
                el.click();
                return JSON.stringify({ok:true,tag:el.tagName,text:(el.textContent||'').trim().substring(0,60)});
            })()
        """.trimIndent()
    }

    private fun fillJs(selector: String, value: String): String {
        val escSel = selector.replace("'", "\\'")
        val escVal = value.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")
        return """
            (function(){
                var el = document.querySelector('$escSel');
                if (!el) return JSON.stringify({ok:false,error:'not found: $escSel'});
                el.value = '$escVal';
                el.dispatchEvent(new Event('input',  {bubbles:true}));
                el.dispatchEvent(new Event('change', {bubbles:true}));
                return JSON.stringify({ok:true,newValue:el.value.substring(0,100)});
            })()
        """.trimIndent()
    }

    private fun wrapJs(code: String): String {
        return "(function(){ try { var r=($code); return JSON.stringify({ok:true,result:r}); } catch(e){ return JSON.stringify({ok:false,error:String(e)}); } })()"
    }

    private suspend fun evalJs(webView: WebView, js: String): String {
        return suspendCancellableCoroutine { cont ->
            runOnMain {
                webView.evaluateJavascript(js) { raw -> cont.resume(unquote(raw)) }
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Strip the outer JSON string quotes that evaluateJavascript wraps around string results. */
    private fun unquote(raw: String?): String {
        if (raw == null || raw == "null") return "{}"
        val trimmed = raw.trim()
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length - 1)
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\/", "/")
        }
        return trimmed
    }

    private fun errorJson(msg: String) = """{"ok":false,"error":"${msg.replace("\"", "'")}"}"""

    private fun json(status: Response.Status, body: String): Response =
        newFixedLengthResponse(status, "application/json", body).also { addCors(it) }

    private fun corsOk(): Response =
        newFixedLengthResponse(Response.Status.OK, "text/plain", "").also { addCors(it) }

    private fun addCors(r: Response) {
        r.addHeader("Access-Control-Allow-Origin", "*")
        r.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        r.addHeader("Access-Control-Allow-Headers", "Content-Type")
    }
}
