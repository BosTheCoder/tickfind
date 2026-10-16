package io.github.bosthecoder.tickfind

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/** The same cases as the Windows app's SearchTests.cs, so the two apps rank alike. */
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

    private fun titles(tasks: List<TTask>, q: String, sort: Sort = Sort.Priority) =
        Search.run(tasks, q, sort, today, ZoneOffset.UTC).map { it.task.title }

    @Test fun priority_sort_puts_high_priority_first_and_best_match_puts_the_title_hit_first() {
        val tasks = listOf(t("Glance widget", 3), t("Calendar ideas", 5, content = "something like glance"), t("Unrelated", 5))
        assertEquals(listOf("Calendar ideas", "Glance widget"), titles(tasks, "glance"))
        assertEquals(listOf("Glance widget", "Calendar ideas"), titles(tasks, "glance", Sort.Best))
        assertEquals(listOf("Glance widget", "Calendar ideas"), titles(tasks, "glance sort:best"))
    }

    @Test fun every_word_must_match_somewhere_ignoring_case_and_accents() {
        val tasks = listOf(t("Café plan", content = "book the room"), t("Café menu"), t("Room booking", items = listOf("call the café")))
        assertEquals(listOf("Café plan", "Room booking"), titles(tasks, "CAFE room", Sort.Best))
    }

    @Test fun filters_narrow_by_tag_list_priority_kind_due_and_excluded_words() {
        val tasks = listOf(
            t("Plumber quote", 5, list = "🏘 Property", tags = listOf("urgent"), dueDays = -1),
            t("Plumber invoice", 1, list = "🏘 Property", dueDays = 0),
            t("Plumber notes", 0, list = "Quick Notes", kind = "NOTE"),
        )
        assertEquals(listOf("Plumber quote"), titles(tasks, "plumber #urg"))
        assertEquals(listOf("Plumber quote", "Plumber invoice"), titles(tasks, "plumber ~property"))
        assertEquals(listOf("Plumber notes"), titles(tasks, "plumber -~property"))
        assertEquals(listOf("Plumber invoice"), titles(tasks, "!low"))
        assertEquals(listOf("Plumber notes"), titles(tasks, "is:note"))
        assertEquals(listOf("Plumber quote"), titles(tasks, "due:overdue"))
        assertEquals(listOf("Plumber invoice"), titles(tasks, "due:today"))
        assertEquals(listOf("Plumber quote", "Plumber notes"), titles(tasks, "plumber -invoice"))
    }

    @Test fun a_typo_still_finds_the_task_but_only_when_nothing_matches_exactly() {
        val tasks = listOf(t("Glance widget", 3), t("Glace dessert"))
        assertEquals(listOf("Glance widget", "Glace dessert"), titles(tasks, "glnace"))
        assertEquals(listOf("Glance widget"), titles(tasks, "glance"))
    }

    @Test fun empty_query_lists_everything_in_the_default_sort() {
        val tasks = listOf(t("Old", ageDays = 9), t("New"), t("Urgent", 5, ageDays = 30))
        assertEquals(listOf("Urgent", "New", "Old"), titles(tasks, ""))
        assertEquals(listOf("New", "Old", "Urgent"), titles(tasks, "", Sort.Modified))
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
