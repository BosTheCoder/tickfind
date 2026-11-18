package io.github.bosthecoder.tickfind

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset

/** Most search behaviour is checked against tests/search-cases.json, the checks every implementation runs
 *  (Windows, this app, the tasks page), so they all rank alike. These cover the rest. */
class SearchTest {
    private val today = LocalDate.of(2026, 10, 16)
    private val now = today.atTime(9, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val day = 86_400_000L
    private var n = 0

    private fun t(title: String, priority: Int = 0, content: String = "", list: String = "Inbox", tags: List<String> = listOf(),
                  items: List<String> = listOf(), dueDays: Int? = null, kind: String = "TEXT", ageDays: Int = 0) =
        TTask("t${++n}", "p", title, content, kind, priority, tags, items,
            dueDays?.let { today.plusDays(it.toLong()).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() },
            now - ageDays * day, now - ageDays * day, list)

    @Test fun search_does_what_search_cases_json_says() {
        val cases = JSONObject(File("../../tests/search-cases.json").readText()).getJSONArray("cases")
        for (c in (0 until cases.length()).map(cases::getJSONObject)) {
            val ts = c.getJSONArray("tasks").let { a -> (0 until a.length()).map(a::getJSONObject) }.map { j ->
                t(j.getString("title"), j.optInt("priority"), j.optString("content"), j.optString("list", "Inbox"),
                    strings(j.optJSONArray("tags")), strings(j.optJSONArray("items")),
                    if (j.has("dueDays")) j.getInt("dueDays") else null, j.optString("kind", "TEXT"), j.optInt("ageDays"))
            }
            val checks = c.getJSONArray("checks")
            for (k in (0 until checks.length()).map(checks::getJSONObject)) {
                val sort = Search.sortOf(k.optString("sort", "priority"))!!
                assertEquals("${c.getString("name")}: \"${k.getString("q")}\"", strings(k.getJSONArray("want")),
                    titles(ts, k.getString("q"), sort, k.optString("filter")))
            }
        }
    }

    private fun strings(a: JSONArray?) = a?.let { (0 until it.length()).map(it::getString) } ?: listOf()

    private fun titles(tasks: List<TTask>, q: String, sort: Sort = Sort.Priority, filter: String = "") =
        Search.run(tasks, q, sort, today, ZoneOffset.UTC, filter).map { it.task.title }

    @Test fun is_list_shows_lists_by_task_count_and_words_narrow_by_name() {
        val tasks = listOf(t("a"), t("b", list = "🏠Property"), t("c", list = "🏠Property"), t("d", list = "Prop shopping"))
        assertEquals(true, Search.wantsLists("is:list prop"))
        assertEquals(false, Search.wantsLists("~inbox"))
        assertEquals(listOf("🏠Property" to 2, "Inbox" to 1, "Prop shopping" to 1), Search.lists(tasks, "is:list"))
        assertEquals(listOf("🏠Property" to 2), Search.lists(tasks, "is:list PROPERTY"))
    }

    @Test fun keepEdits_counts_an_api_edit_and_keeps_our_own_stamp() {
        fun v(id: String, modified: Long, etag: String) = TTask(id, "p", id, "", "TEXT", 0, listOf(), listOf(), null, modified, 0, "Inbox", etag)
        val old = listOf(v("api", 0, "e1"), v("saved", 5, "e2"), v("app", 0, "e3"), v("same", 0, "e4"), v("new-cache", 0, ""))
        val fresh = listOf(v("api", 0, "e1b"), v("saved", 0, "e2"), v("app", 3, "e3b"), v("same", 0, "e4"), v("new-cache", 0, "x"), v("added", 1, "e5"))
        val got = Search.keepEdits(fresh, old, 9).associate { it.id to it.modified }
        assertEquals(mapOf("api" to 9L, "saved" to 5L, "app" to 3L, "same" to 0L, "new-cache" to 0L, "added" to 1L), got)
    }

    @Test fun lev1() {
        for ((a, ok) in listOf("glance" to true, "glnace" to true, "glace" to true, "glancce" to true, "glence" to true, "gnalce" to false, "gl" to false))
            assertEquals(a, ok, Search.lev1(a, "glance"))
    }

    @Test fun marks_point_at_the_original_text_even_with_accents() {
        assertEquals(listOf(0..3, 9..12), Search.marks("Café and CAFE", listOf("cafe")))
        val s = Search.snippet("x".repeat(200) + " the Plumber came " + "y".repeat(200), listOf("plumber"), 40)!!
        assertTrue("Plumber" in s && s.startsWith("…") && s.endsWith("…"))
        assertFalse(Search.snippet("nothing here", listOf("plumber")) != null)
    }
}
