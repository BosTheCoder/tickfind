package io.github.bosthecoder.tickfind

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Settings and the sign-in token (app-private preferences), and the task index (an app-private JSON file). */
class Store(ctx: Context) {
    val sp = ctx.getSharedPreferences("tickfind", Context.MODE_PRIVATE)
    private val cache = File(ctx.filesDir, "tasks.json")

    val token: String? get() = sp.getString("token", null)?.takeIf { System.currentTimeMillis() < sp.getLong("expires", 0) }
    fun setToken(token: String?, expires: Long) = sp.edit().putString("token", token).putLong("expires", expires).apply()
    val signedIn get() = token != null
    /** The sign-in under way (client id, PKCE verifier, state) while the browser is open. */
    var pending: String?
        get() = sp.getString("pending", null)
        set(v) = sp.edit().putString("pending", v).apply()

    var sort: Sort
        get() = Search.sortOf(sp.getString("sort", "priority")!!) ?: Sort.Priority
        set(v) = sp.edit().putString("sort", v.name).apply()
    var results: Int
        get() = sp.getInt("results", 6)
        set(v) = sp.edit().putInt("results", v).apply()
    var expanded: Boolean
        get() = sp.getBoolean("expanded", false)
        set(v) = sp.edit().putBoolean("expanded", v).apply()
    var defaultFilter: String
        get() = sp.getString("defaultFilter", "")!!
        set(v) = sp.edit().putString("defaultFilter", v).apply()
    var excludeLists: Set<String>
        get() = sp.getStringSet("excludeLists", setOf())!!
        set(v) = sp.edit().putStringSet("excludeLists", v).apply()
    var archived: Boolean
        get() = sp.getBoolean("archived", false)
        set(v) = sp.edit().putBoolean("archived", v).apply()
    /** Open in TickTick: false = the TickTick app, true = the browser. */
    var web: Boolean
        get() = sp.getBoolean("web", false)
        set(v) = sp.edit().putBoolean("web", v).apply()
    var bubble: Boolean
        get() = sp.getBoolean("bubble", false)
        set(v) = sp.edit().putBoolean("bubble", v).apply()
    var bubbleY: Int
        get() = sp.getInt("bubbleY", -1)
        set(v) = sp.edit().putInt("bubbleY", v).apply()
    var bubbleRight: Boolean
        get() = sp.getBoolean("bubbleRight", true)
        set(v) = sp.edit().putBoolean("bubbleRight", v).apply()

    /** A saved search, shown as a chip while the box is empty. [query] is the search syntax; "a | b" is either. */
    data class View(val name: String, val query: String)

    /** In order: the first shows when the box opens. */
    var views: List<View>
        get() = sp.getString("views", null)?.let { s ->
            runCatching { JSONArray(s).let { a -> (0 until a.length()).map { a.getJSONObject(it).run { View(optString("name"), optString("query")) } } } }.getOrNull()
        } ?: DEFAULT_VIEWS
        set(v) = sp.edit().putString("views", JSONArray(v.map { JSONObject().put("name", it.name).put("query", it.query) }).toString()).apply()

    companion object {
        val DEFAULT_VIEWS = listOf(
            View("Today", "due:overdue due:today -~gcal | #today"),
            View("Inbox", "~inbox"),
            View("Recently edited", "sort:modified -~gcal"),
            View("Recently added", "sort:created"),
            View("Lists", "is:list"),
        )
    }

    // ---------- The index ----------

    data class Index(val tasks: List<TTask>, val lists: Map<String, String>, val synced: Long)

    fun load(): Index = runCatching {
        val j = JSONObject(cache.readText())
        val a = j.getJSONArray("tasks")
        val l = j.getJSONObject("lists")
        Index((0 until a.length()).map { fromJson(a.getJSONObject(it)) }, l.keys().asSequence().associateWith { l.getString(it) }, j.getLong("synced"))
    }.getOrDefault(Index(listOf(), mapOf(), 0))

    fun save(i: Index) {
        val a = JSONArray()
        i.tasks.forEach { a.put(toJson(it)) }
        val tmp = File(cache.path + ".tmp")
        tmp.writeText(JSONObject().put("tasks", a).put("lists", JSONObject(i.lists)).put("synced", i.synced).toString())
        tmp.renameTo(cache)
    }

    fun clear() { cache.delete() }

    private fun toJson(t: TTask) = JSONObject().put("id", t.id).put("projectId", t.projectId).put("title", t.title).put("content", t.content)
        .put("kind", t.kind).put("priority", t.priority).put("tags", JSONArray(t.tags)).put("items", JSONArray(t.items))
        .put("due", t.due ?: JSONObject.NULL).put("modified", t.modified).put("created", t.created).put("list", t.list)

    private fun fromJson(j: JSONObject): TTask {
        fun strings(k: String) = j.getJSONArray(k).let { a -> (0 until a.length()).map { a.getString(it) } }
        return TTask(j.getString("id"), j.getString("projectId"), j.getString("title"), j.getString("content"), j.getString("kind"),
            j.getInt("priority"), strings("tags"), strings("items"), if (j.isNull("due")) null else j.getLong("due"),
            j.getLong("modified"), j.getLong("created"), j.getString("list"))
    }
}
