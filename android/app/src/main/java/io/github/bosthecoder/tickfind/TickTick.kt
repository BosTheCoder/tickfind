package io.github.bosthecoder.tickfind

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

class NeedsSignIn : Exception("Sign in to TickTick")

/**
 * TickTick through its hosted MCP endpoint (JSON-RPC tools/call), the same as the Windows app. Sign-in is OAuth with
 * dynamic client registration and PKCE, coming back through the io.github.bosthecoder.tickfind: scheme (see MainActivity).
 * TickTick issues no refresh token; its access tokens last about 180 days, then you sign in again. Call off the main thread.
 */
class TickTick(private val store: Store) {
    companion object {
        private const val MCP = "https://mcp.ticktick.com/"
        private const val SCOPE = "tasks:read tasks:write"
        const val REDIRECT = "io.github.bosthecoder.tickfind:/oauth"
        const val PACKAGE = "com.ticktick.task"

        fun webUrl(projectId: String, id: String) = "https://ticktick.com/webapp/#p/$projectId/tasks/$id"

        /** The TickTick app claims ticktick.com/webapp links, so the task's web link goes to the app if it's installed, else the browser. */
        fun open(ctx: Context, projectId: String, id: String, web: Boolean) {
            val view = Intent(Intent.ACTION_VIEW, Uri.parse(webUrl(projectId, id))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (!web) try { ctx.startActivity(Intent(view).setPackage(PACKAGE)); return } catch (_: ActivityNotFoundException) { }
            ctx.startActivity(view)
        }

        private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        private val iso = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss[.SSS]xx")

        fun date(s: String?): Long? = if (s.isNullOrEmpty()) null else runCatching { OffsetDateTime.parse(s, iso).toInstant().toEpochMilli() }.getOrNull()

        fun parse(t: JSONObject, list: String) = TTask(
            t.getString("id"), t.optString("projectId"), t.str("title").trim(),
            listOf(t.str("content"), t.str("desc")).filter { it.isNotEmpty() }.joinToString("\n"),
            t.str("kind").ifEmpty { "TEXT" }, t.optInt("priority"),
            t.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: listOf(),
            t.optJSONArray("items")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).str("title") } } ?: listOf(),
            date(t.str("dueDate")), date(t.str("modifiedTime")) ?: 0, date(t.str("createdTime")) ?: 0, list,
        )
    }

    // ---------- Sign-in ----------

    /** Registers a client and returns the URL to open in the browser; [finishSignIn] completes it when TickTick redirects back. */
    fun startSignIn(): String {
        val reg = post("https://api.ticktick.com/oauth/register", JSONObject()
            .put("client_name", "Tickfind for Android").put("redirect_uris", JSONArray().put(REDIRECT))
            .put("grant_types", JSONArray().put("authorization_code")).put("response_types", JSONArray().put("code"))
            .put("token_endpoint_auth_method", "none").put("scope", SCOPE).toString(), "application/json")
        val rnd = SecureRandom()
        val verifier = b64(ByteArray(32).also(rnd::nextBytes))
        val state = b64(ByteArray(16).also(rnd::nextBytes))
        val clientId = reg.getString("client_id")
        store.pending = JSONObject().put("client", clientId).put("verifier", verifier).put("state", state).toString()
        val challenge = b64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        return "https://ticktick.com/oauth/authorize?" + mapOf(
            "client_id" to clientId, "redirect_uri" to REDIRECT, "response_type" to "code", "scope" to SCOPE, "state" to state,
            "code_challenge" to challenge, "code_challenge_method" to "S256", "resource" to MCP,
        ).entries.joinToString("&") { "${it.key}=${enc(it.value)}" }
    }

    fun finishSignIn(back: Uri) {
        val p = JSONObject(store.pending ?: throw IOException("no sign-in in progress"))
        back.getQueryParameter("error")?.let { throw IOException(back.getQueryParameter("error_description") ?: it) }
        if (back.getQueryParameter("state") != p.getString("state")) throw IOException("state mismatch")
        val form = mapOf(
            "grant_type" to "authorization_code", "code" to (back.getQueryParameter("code") ?: throw IOException("no code")),
            "redirect_uri" to REDIRECT, "client_id" to p.getString("client"), "code_verifier" to p.getString("verifier"), "resource" to MCP,
        ).entries.joinToString("&") { "${it.key}=${enc(it.value)}" }
        val tok = post("https://api.ticktick.com/oauth/token", form, "application/x-www-form-urlencoded")
        val secs = tok.optLong("expires_in", 0)
        store.setToken(tok.getString("access_token"), if (secs > 0) System.currentTimeMillis() + (secs - 300) * 1000 else Long.MAX_VALUE)
        store.pending = null
    }

    private fun post(url: String, body: String, type: String): JSONObject {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 15_000; readTimeout = 30_000
            setRequestProperty("Content-Type", type)
        }
        try {
            c.outputStream.use { it.write(body.toByteArray()) }
            val text = (if (c.responseCode < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (c.responseCode >= 400) throw IOException("TickTick answered ${c.responseCode}: ${text.take(200)}")
            return JSONObject(text)
        } finally { c.disconnect() }
    }

    // ---------- MCP ----------

    /** TickTick's MCP answers "unknown_exception" or a 5xx for a few seconds at a time, so those get one retry. */
    private fun call(tool: String, args: JSONObject): Any? = try { callOnce(tool, args) } catch (e: IOException) {
        Thread.sleep(1500); callOnce(tool, args)
    }

    private fun callOnce(tool: String, args: JSONObject): Any? {
        val token = store.token ?: throw NeedsSignIn()
        val c = (URL(MCP).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 15_000; readTimeout = 60_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json, text/event-stream")
            setRequestProperty("Authorization", "Bearer $token")
        }
        try {
            c.outputStream.use { it.write(JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "tools/call")
                .put("params", JSONObject().put("name", tool).put("arguments", args)).toString().toByteArray()) }
            if (c.responseCode == 401 || c.responseCode == 403) { store.setToken(null, 0); throw NeedsSignIn() }
            if (c.responseCode != 200) throw IOException("TickTick answered ${c.responseCode}")
            var text = c.inputStream.bufferedReader().use { it.readText() }
            if (text.startsWith("event:") || text.startsWith("data:")) text = text.lines().last { it.startsWith("data:") }.substring(5)
            val resp = JSONObject(text)
            resp.optJSONObject("error")?.let { throw RuntimeException("$tool: ${it.optString("message")}") }
            val res = resp.getJSONObject("result")
            val first = res.optJSONArray("content")?.optJSONObject(0)?.optString("text")
            if (res.optBoolean("isError")) {
                if (first?.contains("unknown_exception") == true) throw IOException(first)
                throw RuntimeException("$tool: $first")
            }
            // structuredContent carries the whole result; content[] has the same as text.
            res.optJSONObject("structuredContent")?.let { return if (it.has("result")) it.get("result") else it }
            return first?.let { org.json.JSONTokener(it).nextValue() }
        } finally { c.disconnect() }
    }

    /** Every open task and note in every open list (and archived ones if asked), read list by list in parallel.
     *  A list that can't be read is skipped and named in [Synced.failed]. */
    fun sync(archived: Boolean): Synced {
        val all = call("list_projects", JSONObject()) as JSONArray
        val projects = (0 until all.length()).map { all.getJSONObject(it) }
        val names = projects.associate { it.getString("id") to it.str("name") }.toMutableMap()
        // Calendar-synced lists ("Daily Schedule - GCAL", "Events - GCAL") are never indexed, so no search or view shows them.
        val wanted = projects.filter { (archived || !it.optBoolean("closed")) && !it.str("name").contains("gcal", ignoreCase = true) }
        val failed = mutableListOf<String>()
        val pool = Executors.newFixedThreadPool(6)
        try {
            val parts = wanted.map { p ->
                pool.submit<List<TTask>> {
                    try {
                        val data = call("get_project_with_undone_tasks", JSONObject().put("project_id", p.getString("id"))) as JSONObject
                        val ts = data.optJSONArray("tasks") ?: JSONArray()
                        (0 until ts.length()).map { parse(ts.getJSONObject(it), p.str("name")) }
                    } catch (e: NeedsSignIn) { throw e } catch (e: Exception) {
                        synchronized(failed) { failed += p.str("name") }; listOf()
                    }
                }
            }
            val tasks = parts.flatMap { f -> try { f.get() } catch (e: java.util.concurrent.ExecutionException) { throw e.cause ?: e } }
            // The inbox's real id (inbox123…) is only on its tasks; list_projects calls it "inbox".
            tasks.filter { it.list == "Inbox" }.forEach { names.putIfAbsent(it.projectId, "Inbox") }
            return Synced(tasks, names, failed)
        } finally { pool.shutdown() }
    }

    data class Synced(val tasks: List<TTask>, val lists: Map<String, String>, val failed: List<String>)

    fun get(id: String) = call("get_task_by_id", JSONObject().put("task_id", id)) as JSONObject

    /** Sends only [changes]; TickTick keeps every field it isn't sent (dates, reminders, tags). */
    fun update(id: String, projectId: String, changes: JSONObject) =
        call("update_task", JSONObject().put("task_id", id).put("task", changes.put("id", id).put("projectId", projectId))) as JSONObject

    fun complete(projectId: String, id: String) { call("complete_task", JSONObject().put("project_id", projectId).put("task_id", id)) }

    data class Found(val id: String, val title: String, val projectId: String)

    /** TickTick's own server search, which also covers completed tasks. */
    fun serverSearch(query: String): List<Found> {
        val res = call("search_task", JSONObject().put("query", query))
        val a = (res as? JSONObject)?.optJSONArray("results") ?: res as? JSONArray ?: JSONArray()
        return (0 until a.length()).map { a.getJSONObject(it) }.map { t ->
            val project = Regex("#p/([^/]+)/tasks/").find(t.str("url"))?.groupValues?.get(1) ?: ""
            val title = t.str("title").let { s -> Regex("^\\[(.+)]\\(.*\\)$").find(s)?.groupValues?.get(1) ?: s }   // a task that's just a link
            Found(t.getString("id"), title.trim(), project)
        }
    }
}

/** optString turns JSON null into "null"; this doesn't. */
fun JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key)
